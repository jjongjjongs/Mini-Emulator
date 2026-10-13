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
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
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
};

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
    // MAME 2010 crashes in its static destructors when unloaded after a failed load.
    bool keepLibraryLoaded = false;
    void (*retroUnloadGame)() = nullptr;
    void (*retroRun)() = nullptr;
    void (*retroReset)() = nullptr;
    void (*retroGetSystemAvInfo)(retro_system_av_info*) = nullptr;
    void* (*retroGetMemoryData)(unsigned) = nullptr;
    size_t (*retroGetMemorySize)(unsigned) = nullptr;
    size_t (*retroSerializeSize)() = nullptr;
    bool (*retroSerialize)(void*, size_t) = nullptr;
    bool (*retroUnserialize)(const void*, size_t) = nullptr;

    pthread_t thread{};
    bool threadStarted = false;
    std::atomic<bool> running{false};
    std::atomic<bool> paused{false};
    std::atomic<int> speedMultiplier{1};
    std::atomic<int> inputMask{0};
    // Guards window, which the UI thread can swap while the core draws.
    std::mutex videoMutex;
    // Guards every call into the core.
    std::mutex coreMutex;
    // Calls from Java waiting for coreMutex; the emulation thread lets them in
    // between frames instead of re-taking the lock straight away.
    std::atomic<int> coreWaiters{0};

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
    if (coreId == "mame2015") return "libmame2015_libretro_android.so";
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

// ---- Crash report ----------------------------------------------------------
//
// A core that crashes takes the game process down with it. To make that
// diagnosable on a phone, the last log lines and the crash location are
// written to files/last_crash.txt, which the game list shows next time.

namespace crash {

constexpr int kLines = 48;
constexpr int kLineLength = 240;
char gLines[kLines][kLineLength];
std::atomic<unsigned> gNextLine{0};
char gPath[512];
char gContext[512];
bool gInstalled = false;
struct sigaction gPrevious[NSIG];
const int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};

void remember(const char* text) {
    if (text == nullptr || *text == '\0') {
        return;
    }
    unsigned slot = gNextLine.fetch_add(1) % kLines;
    snprintf(gLines[slot], kLineLength, "%s", text);
    size_t length = strlen(gLines[slot]);
    while (length > 0 && (gLines[slot][length - 1] == '\n' || gLines[slot][length - 1] == '\r')) {
        gLines[slot][--length] = '\0';
    }
}

void writeText(int fd, const char* text) {
    size_t length = strlen(text);
    while (length > 0) {
        ssize_t written = write(fd, text, length);
        if (written <= 0) {
            return;
        }
        text += written;
        length -= static_cast<size_t>(written);
    }
}

void describeAddress(int fd, const char* label, uintptr_t address) {
    char line[512];
    Dl_info info{};
    if (address != 0 && dladdr(reinterpret_cast<void*>(address), &info) != 0 && info.dli_fname != nullptr) {
        const char* file = strrchr(info.dli_fname, '/');
        file = file == nullptr ? info.dli_fname : file + 1;
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        if (info.dli_sname != nullptr) {
            snprintf(line, sizeof(line), "%s %s+0x%zx (%s+0x%zx)\n", label, file, static_cast<size_t>(address - base),
                     info.dli_sname, static_cast<size_t>(address - reinterpret_cast<uintptr_t>(info.dli_saddr)));
        } else {
            snprintf(line, sizeof(line), "%s %s+0x%zx\n", label, file, static_cast<size_t>(address - base));
        }
    } else {
        snprintf(line, sizeof(line), "%s 0x%zx\n", label, static_cast<size_t>(address));
    }
    writeText(fd, line);
}

struct Unwind {
    int fd;
    int frames;
};

_Unwind_Reason_Code unwindFrame(struct _Unwind_Context* context, void* arg) {
    auto* state = static_cast<Unwind*>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc == 0 || state->frames >= 24) {
        return _URC_END_OF_STACK;
    }
    char label[16];
    snprintf(label, sizeof(label), "  #%02d", state->frames++);
    describeAddress(state->fd, label, pc);
    return _URC_NO_REASON;
}

std::atomic<bool> gReported{false};

void handler(int signal, siginfo_t* info, void* context) {
    // Only the first fault is reported; the abort that often follows would overwrite it.
    int fd = gReported.exchange(true) ? -1 : open(gPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        char line[768];
        snprintf(line, sizeof(line), "signal %d code %d fault addr %p\n%s\n", signal, info != nullptr ? info->si_code : 0,
                 info != nullptr ? info->si_addr : nullptr, gContext);
        writeText(fd, line);
#if defined(__aarch64__)
        auto* uc = static_cast<ucontext_t*>(context);
        describeAddress(fd, "pc", uc->uc_mcontext.pc);
        describeAddress(fd, "lr", uc->uc_mcontext.regs[30]);
#elif defined(__x86_64__)
        auto* uc = static_cast<ucontext_t*>(context);
        describeAddress(fd, "pc", static_cast<uintptr_t>(uc->uc_mcontext.gregs[REG_RIP]));
#else
        (void) context;
#endif
        writeText(fd, "backtrace:\n");
        Unwind state{fd, 0};
        _Unwind_Backtrace(unwindFrame, &state);
        writeText(fd, "log:\n");
        unsigned next = gNextLine.load();
        unsigned first = next > kLines ? next - kLines : 0;
        for (unsigned i = first; i < next; ++i) {
            writeText(fd, gLines[i % kLines]);
            writeText(fd, "\n");
        }
        close(fd);
    }
    // Hand the signal to the previous handler (the system crash reporter).
    sigaction(signal, &gPrevious[signal], nullptr);
    if (signal == SIGABRT) {
        raise(signal);
    }
}

// The handler runs on its own stack so a stack overflow can still be reported.
void useAlternateStack() {
    static thread_local bool done = false;
    if (done) {
        return;
    }
    done = true;
    stack_t stack{};
    stack.ss_size = 64 * 1024;
    stack.ss_sp = malloc(stack.ss_size);
    if (stack.ss_sp != nullptr) {
        sigaltstack(&stack, nullptr);
    }
}

#ifdef __ANDROID__
// Cores also print to stdout/stderr, which Android drops; keep those lines too.
void captureStdio() {
    int fds[2];
    if (pipe(fds) != 0) {
        return;
    }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    std::thread([fd = fds[0]]() {
        char buffer[1024];
        std::string pending;
        ssize_t count;
        while ((count = read(fd, buffer, sizeof(buffer))) > 0) {
            pending.append(buffer, static_cast<size_t>(count));
            size_t newline;
            while ((newline = pending.find('\n')) != std::string::npos) {
                std::string line = pending.substr(0, newline);
                remember(line.c_str());
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", line.c_str());
                pending.erase(0, newline + 1);
            }
        }
    }).detach();
}
#endif

void install(const std::string& systemDir, const std::string& coreId, const std::string& romPath) {
    std::string filesDir = systemDir;
    size_t slash = filesDir.find_last_of('/');
    if (slash != std::string::npos) {
        filesDir.resize(slash);
    }
    snprintf(gPath, sizeof(gPath), "%s/last_crash.txt", filesDir.c_str());
    size_t nameStart = romPath.find_last_of('/');
    snprintf(gContext, sizeof(gContext), "core %s, rom %s", coreId.c_str(),
             nameStart == std::string::npos ? romPath.c_str() : romPath.c_str() + nameStart + 1);
    useAlternateStack();
    if (gInstalled) {
        return;
    }
    gInstalled = true;
#ifdef __ANDROID__
    captureStdio();
#else
    // Desktop JVMs use SIGSEGV themselves; the host test opts in explicitly.
    if (getenv("MINIGBA_CRASH_REPORT") == nullptr) {
        return;
    }
#endif
    struct sigaction action{};
    action.sa_sigaction = handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    for (int signal : kSignals) {
        sigaction(signal, &action, &gPrevious[signal]);
    }
}

}  // namespace crash

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
    char message[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);
    crash::remember(message);
    __android_log_print(priority, LOG_TAG, "%s", message);
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
    if (strcmp(key, "mame2015_hide_nagscreen") == 0) return "enabled";
    if (strcmp(key, "mame2015_hide_infoscreen") == 0) return "enabled";
    if (strcmp(key, "mame2015_hide_warnings") == 0) return "enabled";
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
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        // Plain 51 without the experimental flag, as accepted by the original build.
        case 51:
            return true;
        default:
            return false;
    }
}

// Takes coreMutex for a call from Java, ahead of the emulation thread's next frame.
class CoreLock {
public:
    explicit CoreLock(Core* core) : core_(core) {
        core_->coreWaiters.fetch_add(1);
        core_->coreMutex.lock();
        core_->coreWaiters.fetch_sub(1);
    }
    ~CoreLock() { core_->coreMutex.unlock(); }
    CoreLock(const CoreLock&) = delete;
    CoreLock& operator=(const CoreLock&) = delete;

private:
    Core* core_;
};

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

// MAME 2010 and 2015 read buttons 1-4 from A/B/X/Y rather than B/A/Y/X.
int mame2010ButtonBitFor(unsigned id) {
    switch (id) {
        case RETRO_DEVICE_ID_JOYPAD_A: return kButtonA;
        case RETRO_DEVICE_ID_JOYPAD_B: return kButtonB;
        case RETRO_DEVICE_ID_JOYPAD_X: return kButtonC;
        case RETRO_DEVICE_ID_JOYPAD_Y: return kButtonD;
        default: return buttonBitFor(id, true);
    }
}

int16_t RETRO_CALLCONV inputStateCallback(unsigned port, unsigned device, unsigned /*index*/, unsigned id) {
    Core* core = gCore;
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || core == nullptr) {
        return 0;
    }
    int mask = core->inputMask.load();
    int bit;
    if (core->coreId == "mame2010" || core->coreId == "mame2015") {
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
            while (core->coreWaiters.load() > 0 && core->running.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
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

void* emulationThread(void* arg) {
    crash::useAlternateStack();
    runLoop(static_cast<Core*>(arg));
    return nullptr;
}

void destroyCore(Core* core) {
    if (core == nullptr) {
        return;
    }
    core->running.store(false);
    if (core->threadStarted) {
        pthread_join(core->thread, nullptr);
        core->threadStarted = false;
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
    if (core->library != nullptr && !core->keepLibraryLoaded) {
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
    crash::install(core->systemDir, core->coreId, core->romPath);
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
        core->keepLibraryLoaded = core->coreId == "mame2010" || core->coreId == "mame2015";
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
    // Arcade cores recurse deeply; give the emulation thread far more than the default 1 MB.
    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    pthread_attr_setstacksize(&attributes, 16 * 1024 * 1024);
    core->threadStarted = pthread_create(&core->thread, &attributes, emulationThread, core) == 0;
    pthread_attr_destroy(&attributes);
    if (!core->threadStarted) {
        core->running.store(false);
        destroyCore(core);
        throwIllegalState(env, "ROM load failure: could not start the emulation thread");
        return 0;
    }
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
    CoreLock lock(core);
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
    CoreLock lock(core);
    return saveState(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_loadStateNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    CoreLock lock(core);
    return loadState(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveSramNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    CoreLock lock(core);
    return saveSram(core) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_reset(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded || core->retroReset == nullptr) {
        return;
    }
    CoreLock lock(core);
    core->retroReset();
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_destroy(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    destroyCore(fromHandle(handle));
}

}  // extern "C"
