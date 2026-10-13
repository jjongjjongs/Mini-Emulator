// Mini GBA native bridge.
//
// Loads a libretro core (mGBA by default) with dlopen, runs it on its own
// thread, draws frames into an ANativeWindow and forwards audio to the Java
// AudioSink. This file was rewritten from the behaviour of the original
// libgbabridge.so shipped in the 0.0.1-gba APK.

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/stat.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "libretro.h"

#define LOG_TAG "MiniGBA"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Button bits sent from Java (see GbaButton.java).
enum : int {
    kButtonA = 1,
    kButtonB = 2,
    kButtonSelect = 4,
    kButtonStart = 8,
    kButtonRight = 16,
    kButtonLeft = 32,
    kButtonUp = 64,
    kButtonDown = 128,
    kButtonR = 256,
    kButtonL = 512,
    kButtonC = 1024,
    kButtonD = 2048,
    kButtonE = 4096,
    kButtonF = 8192,
    // PlayStation-only buttons.
    kButtonL2 = 16384,
    kButtonR2 = 32768,
    kButtonL3 = 65536,
    kButtonR3 = 131072,
};

// PCSX ReARMed's DualShock device id (RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 1)).
constexpr unsigned kDeviceDualShock = (1 + 1) << 8 | RETRO_DEVICE_ANALOG;

constexpr int kDefaultSampleRate = 32768;
constexpr double kDefaultFps = 59.64;

struct Core {
    JavaVM* vm = nullptr;
    jobject audioSink = nullptr;
    jmethodID audioConfigure = nullptr;
    jmethodID audioWrite = nullptr;
    ANativeWindow* window = nullptr;
    void* library = nullptr;

    void (*retroSetEnvironment)(retro_environment_t) = nullptr;
    void (*retroSetVideoRefresh)(retro_video_refresh_t) = nullptr;
    void (*retroSetAudioSample)(retro_audio_sample_t) = nullptr;
    void (*retroSetAudioSampleBatch)(retro_audio_sample_batch_t) = nullptr;
    void (*retroSetInputPoll)(retro_input_poll_t) = nullptr;
    void (*retroSetInputState)(retro_input_state_t) = nullptr;
    void (*retroInit)() = nullptr;
    void (*retroDeinit)() = nullptr;
    unsigned (*retroApiVersion)() = nullptr;
    bool (*retroLoadGame)(const retro_game_info*) = nullptr;
    void (*retroUnloadGame)() = nullptr;
    void (*retroRun)() = nullptr;
    void (*retroReset)() = nullptr;
    void (*retroGetSystemAvInfo)(retro_system_av_info*) = nullptr;
    void* (*retroGetMemoryData)(unsigned) = nullptr;
    size_t (*retroGetMemorySize)(unsigned) = nullptr;
    size_t (*retroSerializeSize)() = nullptr;
    bool (*retroSerialize)(void*, size_t) = nullptr;
    bool (*retroUnserialize)(const void*, size_t) = nullptr;
    // Optional: only some cores export it.
    void (*retroSetControllerPortDevice)(unsigned, unsigned) = nullptr;
    retro_disk_control_callback diskControl{};
    bool hasDiskControl = false;

    std::thread thread;
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<int> speedMultiplier{1};
    std::atomic<int> inputMask{0};
    // Left X/Y and right X/Y analog axes, -32767..32767.
    std::atomic<int> analog[4] = {{0}, {0}, {0}, {0}};
    // Guards window, which the UI thread can swap while the core draws.
    std::mutex videoMutex;
    // Guards every call into the core.
    std::mutex coreMutex;

    std::string romPath;
    std::string coreId;
    std::string saveDir;
    std::string systemDir;
    std::string sramPath;
    std::string statePath;
    std::unordered_map<std::string, std::string> variables;

    unsigned pixelFormat = RETRO_PIXEL_FORMAT_RGB565;
    double fps = kDefaultFps;
    int sampleRate = kDefaultSampleRate;
    bool initialized = false;
    bool gameLoaded = false;
};

JavaVM* gVm = nullptr;
// libretro callbacks carry no user pointer, so the running core is global.
Core* gCore = nullptr;

// Gets a JNIEnv for the current thread, attaching it to the VM if needed.
class ScopedEnv {
public:
    explicit ScopedEnv(JavaVM* vm) : vm_(vm) {
        if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            JNIEnv** target = &env_;
#else
            // Desktop JDK headers (used by the host tests) take void**.
            void** target = reinterpret_cast<void**>(&env_);
#endif
            if (vm_->AttachCurrentThread(target, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        }
    }
    ~ScopedEnv() {
        if (attached_) {
            vm_->DetachCurrentThread();
        }
    }
    JNIEnv* get() const { return env_; }

private:
    JavaVM* vm_;
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

void configureAudio(Core* core, int sampleRate) {
    ScopedEnv env(core->vm);
    if (env.get() != nullptr) {
        env.get()->CallVoidMethod(core->audioSink, core->audioConfigure, sampleRate);
    }
}

void writeAudio(const int16_t* samples, jsize count) {
    Core* core = gCore;
    if (core == nullptr) {
        return;
    }
    ScopedEnv env(core->vm);
    JNIEnv* e = env.get();
    if (e == nullptr) {
        return;
    }
    jshortArray array = e->NewShortArray(count);
    e->SetShortArrayRegion(array, 0, count, samples);
    e->CallVoidMethod(core->audioSink, core->audioWrite, array, count);
    e->DeleteLocalRef(array);
}

// Replaces characters that are not safe in a file name.
std::string sanitizeFileName(std::string name) {
    for (char& c : name) {
        unsigned char u = static_cast<unsigned char>(c);
        if (u < 0x20 || c == '\\' || c == '/') {
            c = '_';
        }
    }
    return name;
}

// Save files are named after the ROM unless Java passes an explicit name.
std::string saveBaseName(const char* requested, const std::string& romPath) {
    std::string name;
    if (requested != nullptr && requested[0] != '\0') {
        name = requested;
    } else {
        size_t slash = romPath.find_last_of("/\\");
        name = slash == std::string::npos ? romPath : romPath.substr(slash + 1);
        size_t dot = name.rfind('.');
        if (dot != std::string::npos) {
            name = name.substr(0, dot);
        }
    }
    name = sanitizeFileName(name);
    return name.empty() ? "game" : name;
}

const char* coreLibraryFor(const std::string& coreId) {
    if (coreId == "gba") return "libmgba_libretro_android.so";
    if (coreId == "fbneo") return "libfbneo_libretro_android.so";
    if (coreId == "mamearcade") return "libmamearcade_libretro_android.so";
    if (coreId == "mame2003plus") return "libmame2003_plus_libretro_android.so";
    if (coreId == "mame2010") return "libmame2010_libretro_android.so";
    if (coreId == "ps") return "libpcsx_rearmed_libretro_android.so";
    return nullptr;
}

template <typename T>
bool loadSymbol(void* library, T& target, const char* name) {
    target = reinterpret_cast<T>(dlsym(library, name));
    if (target == nullptr) {
        LOGE("Missing libretro symbol: %s", name);
        return false;
    }
    return true;
}

bool loadCoreSymbols(Core* core) {
    void* lib = core->library;
    return loadSymbol(lib, core->retroSetEnvironment, "retro_set_environment") &&
           loadSymbol(lib, core->retroSetVideoRefresh, "retro_set_video_refresh") &&
           loadSymbol(lib, core->retroSetAudioSample, "retro_set_audio_sample") &&
           loadSymbol(lib, core->retroSetAudioSampleBatch, "retro_set_audio_sample_batch") &&
           loadSymbol(lib, core->retroSetInputPoll, "retro_set_input_poll") &&
           loadSymbol(lib, core->retroSetInputState, "retro_set_input_state") &&
           loadSymbol(lib, core->retroInit, "retro_init") &&
           loadSymbol(lib, core->retroDeinit, "retro_deinit") &&
           loadSymbol(lib, core->retroApiVersion, "retro_api_version") &&
           loadSymbol(lib, core->retroLoadGame, "retro_load_game") &&
           loadSymbol(lib, core->retroUnloadGame, "retro_unload_game") &&
           loadSymbol(lib, core->retroRun, "retro_run") &&
           loadSymbol(lib, core->retroReset, "retro_reset") &&
           loadSymbol(lib, core->retroGetSystemAvInfo, "retro_get_system_av_info") &&
           loadSymbol(lib, core->retroGetMemoryData, "retro_get_memory_data") &&
           loadSymbol(lib, core->retroGetMemorySize, "retro_get_memory_size") &&
           loadSymbol(lib, core->retroSerializeSize, "retro_serialize_size") &&
           loadSymbol(lib, core->retroSerialize, "retro_serialize") &&
           loadSymbol(lib, core->retroUnserialize, "retro_unserialize");
}

void loadOptionalSymbols(Core* core) {
    core->retroSetControllerPortDevice = reinterpret_cast<void (*)(unsigned, unsigned)>(
        dlsym(core->library, "retro_set_controller_port_device"));
}

// ---- Save data -------------------------------------------------------------

bool saveState(Core* core) {
    if (core->retroSerializeSize == nullptr || core->retroSerialize == nullptr) {
        return false;
    }
    size_t size = core->retroSerializeSize();
    if (size == 0) {
        return false;
    }
    std::vector<uint8_t> buffer(size);
    if (!core->retroSerialize(buffer.data(), size)) {
        return false;
    }
    FILE* file = fopen(core->statePath.c_str(), "wb");
    if (file == nullptr) {
        LOGE("Could not write state: %s", core->statePath.c_str());
        return false;
    }
    size_t written = fwrite(buffer.data(), 1, size, file);
    fclose(file);
    return written == size;
}

bool loadState(Core* core) {
    if (core->retroUnserialize == nullptr) {
        return false;
    }
    struct stat st {};
    if (stat(core->statePath.c_str(), &st) != 0) {
        return false;
    }
    FILE* file = fopen(core->statePath.c_str(), "rb");
    if (file == nullptr) {
        return false;
    }
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    rewind(file);
    if (size <= 0) {
        fclose(file);
        return false;
    }
    std::vector<uint8_t> buffer(static_cast<size_t>(size));
    size_t read = fread(buffer.data(), 1, buffer.size(), file);
    fclose(file);
    if (read != buffer.size()) {
        return false;
    }
    return core->retroUnserialize(buffer.data(), read);
}

bool saveSram(Core* core) {
    void* data = core->retroGetMemoryData(RETRO_MEMORY_SAVE_RAM);
    size_t size = core->retroGetMemorySize(RETRO_MEMORY_SAVE_RAM);
    if (data == nullptr || size == 0) {
        return false;
    }
    FILE* file = fopen(core->sramPath.c_str(), "wb");
    if (file == nullptr) {
        LOGE("Could not write save: %s", core->sramPath.c_str());
        return false;
    }
    size_t written = fwrite(data, 1, size, file);
    fclose(file);
    return written == size;
}

void loadSram(Core* core) {
    void* data = core->retroGetMemoryData(RETRO_MEMORY_SAVE_RAM);
    size_t size = core->retroGetMemorySize(RETRO_MEMORY_SAVE_RAM);
    if (data == nullptr || size == 0) {
        return;
    }
    struct stat st {};
    if (stat(core->sramPath.c_str(), &st) != 0) {
        return;
    }
    FILE* file = fopen(core->sramPath.c_str(), "rb");
    if (file == nullptr) {
        return;
    }
    size_t read = fread(data, 1, size, file);
    fclose(file);
    if (read != size) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Save file is %zu of %zu bytes: %s", read, size,
                            core->sramPath.c_str());
    }
}

// ---- libretro callbacks ----------------------------------------------------

void RETRO_CALLCONV logCallback(enum retro_log_level level, const char* fmt, ...) {
    if (fmt == nullptr) {
        return;
    }
    int priority;
    switch (level) {
        case RETRO_LOG_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        case RETRO_LOG_INFO: priority = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: priority = ANDROID_LOG_WARN; break;
        default: priority = ANDROID_LOG_ERROR; break;
    }
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, LOG_TAG, fmt, args);
    va_end(args);
}

// Core options the app pins regardless of what the core reports as default.
const char* fixedVariable(const char* key) {
    if (strcmp(key, "mgba_use_bios") == 0) return "ON";
    if (strcmp(key, "mgba_skip_bios") == 0) return "OFF";
    if (strcmp(key, "mgba_idle_optimization") == 0) return "Remove Known";
    if (strcmp(key, "mgba_frameskip") == 0) return "0";
    // MAME 2010 otherwise stops on info/warning screens that wait for a key press.
    if (strcmp(key, "mame_current_skip_warnings") == 0) return "enabled";
    if (strcmp(key, "mame_current_skip_gameinfo") == 0) return "enabled";
    if (strcmp(key, "mame_current_skip_nagscreen") == 0) return "enabled";
    return nullptr;
}

// Records the default value of each option from "Description; default|other|...".
void storeDefaultVariables(Core* core, const retro_variable* vars) {
    for (; vars->key != nullptr; ++vars) {
        if (vars->value == nullptr) {
            continue;
        }
        const char* semicolon = strchr(vars->value, ';');
        if (semicolon == nullptr) {
            continue;
        }
        const char* start = semicolon + 1;
        while (*start == ' ') {
            ++start;
        }
        const char* bar = strchr(start, '|');
        std::string value = bar != nullptr ? std::string(start, bar - start) : std::string(start);
        core->variables[vars->key] = value;
    }
}

bool RETRO_CALLCONV environmentCallback(unsigned cmd, void* data) {
    Core* core = gCore;
    if (core == nullptr) {
        return false;
    }
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data == nullptr) return false;
            *static_cast<const char**>(data) = core->systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data == nullptr) return false;
            *static_cast<const char**>(data) = core->saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            if (data == nullptr) return false;
            unsigned format = *static_cast<const unsigned*>(data);
            if (format > RETRO_PIXEL_FORMAT_RGB565) return false;
            core->pixelFormat = format;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            if (var == nullptr || var->key == nullptr) return false;
            if (const char* fixed = fixedVariable(var->key)) {
                var->value = fixed;
                return true;
            }
            auto it = core->variables.find(var->key);
            if (it == core->variables.end()) return false;
            var->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            if (data == nullptr) return true;
            storeDefaultVariables(core, static_cast<const retro_variable*>(data));
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (data == nullptr) return false;
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data == nullptr) return false;
            static_cast<retro_log_callback*>(data)->log = logCallback;
            return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
            if (data == nullptr) return false;
            core->diskControl = *static_cast<const retro_disk_control_callback*>(data);
            core->hasDiskControl = true;
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        // Plain 51 without the experimental flag, as accepted by the original build.
        case 51:
            return true;
        default:
            return false;
    }
}

// Copies one frame into the window, converting it to RGB565.
void RETRO_CALLCONV videoRefreshCallback(const void* data, unsigned width, unsigned height, size_t pitch) {
    Core* core = gCore;
    if (data == nullptr || core == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(core->videoMutex);
    if (core->window == nullptr) {
        return;
    }
    ANativeWindow_setBuffersGeometry(core->window, static_cast<int32_t>(width), static_cast<int32_t>(height),
                                     WINDOW_FORMAT_RGB_565);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(core->window, &buffer, nullptr) != 0) {
        return;
    }
    const auto* src = static_cast<const uint8_t*>(data);
    auto* dst = static_cast<uint8_t*>(buffer.bits);
    size_t dstPitch = static_cast<size_t>(buffer.stride) * 2;
    for (unsigned y = 0; y < height; ++y, src += pitch, dst += dstPitch) {
        auto* out = reinterpret_cast<uint16_t*>(dst);
        if (core->pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            memcpy(dst, src, dstPitch < pitch ? dstPitch : pitch);
        } else if (core->pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            const auto* in = reinterpret_cast<const uint32_t*>(src);
            for (unsigned x = 0; x < width; ++x) {
                uint32_t p = in[x];
                out[x] = static_cast<uint16_t>(((p >> 8) & 0xF800) | ((p >> 5) & 0x07E0) | ((p >> 3) & 0x001F));
            }
        } else {  // RETRO_PIXEL_FORMAT_0RGB1555
            const auto* in = reinterpret_cast<const uint16_t*>(src);
            for (unsigned x = 0; x < width; ++x) {
                uint16_t p = in[x];
                out[x] = static_cast<uint16_t>(((p << 1) & 0xFFC0) | (p & 0x001F));
            }
        }
    }
    ANativeWindow_unlockAndPost(core->window);
}

void RETRO_CALLCONV audioSampleCallback(int16_t left, int16_t right) {
    int16_t frame[2] = {left, right};
    writeAudio(frame, 2);
}

size_t RETRO_CALLCONV audioSampleBatchCallback(const int16_t* data, size_t frames) {
    if (data != nullptr && frames != 0) {
        writeAudio(data, static_cast<jsize>(frames * 2));
    }
    return frames;
}

void RETRO_CALLCONV inputPollCallback() {}

// Maps a libretro joypad id to the app's button bit. Arcade cores use a
// different face-button layout from the GBA core.
int buttonBitFor(unsigned id, bool arcade) {
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_B: return arcade ? kButtonA : kButtonB;
        case RETRO_DEVICE_ID_JOYPAD_Y: return arcade ? kButtonC : kButtonD;
        case RETRO_DEVICE_ID_JOYPAD_SELECT: return kButtonSelect;
        case RETRO_DEVICE_ID_JOYPAD_START: return kButtonStart;
        case RETRO_DEVICE_ID_JOYPAD_UP: return kButtonUp;
        case RETRO_DEVICE_ID_JOYPAD_DOWN: return kButtonDown;
        case RETRO_DEVICE_ID_JOYPAD_LEFT: return kButtonLeft;
        case RETRO_DEVICE_ID_JOYPAD_RIGHT: return kButtonRight;
        case RETRO_DEVICE_ID_JOYPAD_A: return arcade ? kButtonB : kButtonA;
        case RETRO_DEVICE_ID_JOYPAD_X: return arcade ? kButtonD : kButtonC;
        case RETRO_DEVICE_ID_JOYPAD_L: return arcade ? kButtonE : kButtonL;
        case RETRO_DEVICE_ID_JOYPAD_R: return arcade ? kButtonF : kButtonR;
        default: return 0;
    }
}

// MAME 2010 reads buttons 1-4 from A/B/X/Y rather than B/A/Y/X.
int mame2010ButtonBitFor(unsigned id) {
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A: return kButtonA;
        case RETRO_DEVICE_ID_JOYPAD_B: return kButtonB;
        case RETRO_DEVICE_ID_JOYPAD_X: return kButtonC;
        case RETRO_DEVICE_ID_JOYPAD_Y: return kButtonD;
        default: return buttonBitFor(id, true);
    }
}

// PlayStation pad: cross/circle/square/triangle come in as B/A/C/D.
int psButtonBitFor(unsigned id) {
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_B: return kButtonB;
        case RETRO_DEVICE_ID_JOYPAD_A: return kButtonA;
        case RETRO_DEVICE_ID_JOYPAD_Y: return kButtonC;
        case RETRO_DEVICE_ID_JOYPAD_X: return kButtonD;
        case RETRO_DEVICE_ID_JOYPAD_L2: return kButtonL2;
        case RETRO_DEVICE_ID_JOYPAD_R2: return kButtonR2;
        case RETRO_DEVICE_ID_JOYPAD_L3: return kButtonL3;
        case RETRO_DEVICE_ID_JOYPAD_R3: return kButtonR3;
        default: return buttonBitFor(id, false);
    }
}

int16_t RETRO_CALLCONV inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    Core* core = gCore;
    if (port != 0 || core == nullptr) {
        return 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        if (index > RETRO_DEVICE_INDEX_ANALOG_RIGHT || id > RETRO_DEVICE_ID_ANALOG_Y) {
            return 0;
        }
        return static_cast<int16_t>(core->analog[index * 2 + id].load());
    }
    if (device != RETRO_DEVICE_JOYPAD) {
        return 0;
    }
    int mask = core->inputMask.load();
    int bit;
    if (core->coreId == "ps") {
        bit = psButtonBitFor(id);
    } else if (core->coreId == "mame2010") {
        bit = mame2010ButtonBitFor(id);
    } else {
        bit = buttonBitFor(id, core->coreId != "gba");
    }
    return (mask & bit) != 0 ? 1 : 0;
}

// ---- Emulation thread ------------------------------------------------------

void runLoop(Core* core) {
    while (core->running.load()) {
        int speed = core->speedMultiplier.load();
        if (speed < 1) {
            speed = 1;
        }
        double fps = core->fps;
        auto start = std::chrono::steady_clock::now();
        if (!core->paused.load()) {
            std::lock_guard<std::mutex> lock(core->coreMutex);
            core->retroRun();
        }
        double frameNs = 1.0 / (fps * speed) * 1e9;
        double elapsedNs = static_cast<double>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count());
        double remainingNs = frameNs - elapsedNs;
        if (remainingNs > 0) {
            std::this_thread::sleep_for(std::chrono::nanoseconds(static_cast<int64_t>(std::ceil(remainingNs))));
        }
    }
}

void destroyCore(Core* core) {
    if (core == nullptr) {
        return;
    }
    core->running.store(false);
    if (core->thread.joinable()) {
        core->thread.join();
    }
    if (core->window != nullptr) {
        ANativeWindow_release(core->window);
        core->window = nullptr;
    }
    if (core->gameLoaded) {
        saveState(core);
        saveSram(core);
        core->retroUnloadGame();
        core->gameLoaded = false;
    }
    if (core->initialized) {
        core->retroDeinit();
        core->initialized = false;
    }
    if (gCore == core) {
        gCore = nullptr;
    }
    if (core->audioSink != nullptr) {
        ScopedEnv env(core->vm);
        if (env.get() != nullptr) {
            env.get()->DeleteGlobalRef(core->audioSink);
        }
        core->audioSink = nullptr;
    }
    if (core->library != nullptr) {
        dlclose(core->library);
        core->library = nullptr;
    }
    delete core;
}

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

Core* fromHandle(jlong handle) {
    return reinterpret_cast<Core*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_example_minigba_NativeGba_create(
        JNIEnv* env, jclass /*cls*/, jobject surface, jstring romPath, jstring saveDir, jstring systemDir,
        jstring saveName, jstring coreId, jobject audioSink) {
    auto* core = new Core();
    core->vm = gVm;
    core->romPath = jstringToString(env, romPath);
    core->saveDir = jstringToString(env, saveDir);
    core->systemDir = jstringToString(env, systemDir);
    core->coreId = jstringToString(env, coreId);
    std::string requestedName = jstringToString(env, saveName);
    std::string baseName = saveBaseName(requestedName.c_str(), core->romPath);
    core->sramPath = core->saveDir + "/" + baseName + ".sav";
    core->statePath = core->saveDir + "/" + baseName + ".state";

    core->window = ANativeWindow_fromSurface(env, surface);
    if (core->window == nullptr) {
        delete core;
        throwIllegalState(env, "ROM load failure: invalid render surface");
        return 0;
    }

    jclass sinkClass = env->GetObjectClass(audioSink);
    core->audioSink = env->NewGlobalRef(audioSink);
    core->audioConfigure = env->GetMethodID(sinkClass, "configure", "(I)V");
    core->audioWrite = env->GetMethodID(sinkClass, "write", "([SI)V");
    if (core->audioConfigure == nullptr || core->audioWrite == nullptr) {
        env->ExceptionClear();
        destroyCore(core);
        throwIllegalState(env, "Audio initialization failure");
        return 0;
    }

    const char* library = coreLibraryFor(core->coreId);
    if (library == nullptr) {
        LOGE("Unsupported core id: %s", core->coreId.c_str());
    } else {
        core->library = dlopen(library, RTLD_NOW);
        if (core->library == nullptr) {
            LOGE("dlopen core failed: %s", dlerror());
        }
    }
    if (core->library != nullptr) {
        loadOptionalSymbols(core);
    }
    if (core->library == nullptr || !loadCoreSymbols(core)) {
        destroyCore(core);
        throwIllegalState(env, "Core load failure: selected libretro core was not found or is incomplete");
        return 0;
    }
    if (core->retroApiVersion() != RETRO_API_VERSION) {
        destroyCore(core);
        throwIllegalState(env, "Core load failure: incompatible libretro API version");
        return 0;
    }

    gCore = core;
    core->retroSetEnvironment(environmentCallback);
    core->retroSetVideoRefresh(videoRefreshCallback);
    core->retroSetAudioSample(audioSampleCallback);
    core->retroSetAudioSampleBatch(audioSampleBatchCallback);
    core->retroSetInputPoll(inputPollCallback);
    core->retroSetInputState(inputStateCallback);
    core->retroInit();
    core->initialized = true;

    retro_game_info game{};
    game.path = core->romPath.c_str();
    if (!core->retroLoadGame(&game)) {
        destroyCore(core);
        throwIllegalState(env, "ROM load failure: selected file could not be loaded by the core");
        return 0;
    }
    core->gameLoaded = true;

    retro_system_av_info info{};
    core->retroGetSystemAvInfo(&info);
    if (info.timing.fps > 1.0) {
        core->fps = info.timing.fps;
    }
    core->sampleRate = info.timing.sample_rate > 1.0 ? static_cast<int>(info.timing.sample_rate)
                                                      : kDefaultSampleRate;
    configureAudio(core, core->sampleRate);

    // Resume where the player left off: in-game save first, then the quick save.
    loadSram(core);
    loadState(core);

    core->running.store(true);
    core->thread = std::thread(runLoop, core);
    return reinterpret_cast<jlong>(core);
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setSurface(
        JNIEnv* env, jclass /*cls*/, jlong handle, jobject surface) {
    Core* core = fromHandle(handle);
    if (core == nullptr || surface == nullptr) {
        return;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(core->videoMutex);
    ANativeWindow* old = core->window;
    core->window = window;
    if (old != nullptr) {
        ANativeWindow_release(old);
    }
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_clearSurface(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(core->videoMutex);
    if (core->window != nullptr) {
        ANativeWindow_release(core->window);
        core->window = nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setInput(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint mask) {
    Core* core = fromHandle(handle);
    if (core != nullptr) {
        core->inputMask.store(mask);
    }
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_pause(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean paused) {
    Core* core = fromHandle(handle);
    if (core != nullptr) {
        core->paused.store(paused == JNI_TRUE);
    }
}

// Fast-forward is either 1x or 2x. Audio is re-opened at the scaled rate so
// the extra samples play out at the faster speed.
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setSpeedMultiplier(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint multiplier) {
    Core* core = fromHandle(handle);
    if (core == nullptr) {
        return;
    }
    int speed = multiplier > 1 ? 2 : 1;
    if (core->speedMultiplier.exchange(speed) == speed) {
        return;
    }
    configureAudio(core, core->sampleRate * speed);
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveNow(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    bool state = saveState(core);
    bool sram = saveSram(core);
    return (state || sram) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveStateNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    return saveState(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_loadStateNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    return loadState(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveSramNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    return saveSram(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_reset(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded || core->retroReset == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    core->retroReset();
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setAnalog(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint leftX, jint leftY, jint rightX, jint rightY) {
    Core* core = fromHandle(handle);
    if (core == nullptr) {
        return;
    }
    core->analog[0].store(leftX);
    core->analog[1].store(leftY);
    core->analog[2].store(rightX);
    core->analog[3].store(rightY);
}

// Switches port 1 between a digital pad and a DualShock (PlayStation).
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setAnalogPad(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jboolean enabled) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded || core->retroSetControllerPortDevice == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    core->retroSetControllerPortDevice(0, enabled == JNI_TRUE ? kDeviceDualShock : RETRO_DEVICE_JOYPAD);
}

JNIEXPORT jint JNICALL Java_com_example_minigba_NativeGba_getDiskCount(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->hasDiskControl || core->diskControl.get_num_images == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    return static_cast<jint>(core->diskControl.get_num_images());
}

JNIEXPORT jint JNICALL Java_com_example_minigba_NativeGba_getDiskIndex(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->hasDiskControl || core->diskControl.get_image_index == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    return static_cast<jint>(core->diskControl.get_image_index());
}

// Opens the tray, swaps to the given disc and closes it again.
JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_setDiskIndex(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint index) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->hasDiskControl || core->diskControl.set_eject_state == nullptr ||
        core->diskControl.set_image_index == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(core->coreMutex);
    bool ok = core->diskControl.set_eject_state(true) &&
              core->diskControl.set_image_index(static_cast<unsigned>(index));
    ok = core->diskControl.set_eject_state(false) && ok;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_destroy(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    destroyCore(fromHandle(handle));
}

}  // extern "C"
