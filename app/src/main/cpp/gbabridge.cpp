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
#include <sys/mman.h>
#include <sys/stat.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
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
    // PlayStation pads' second shoulder buttons and stick clicks.
    kButtonL2 = 16384,
    kButtonR2 = 32768,
    kButtonL3 = 65536,
    kButtonR3 = 131072,
};

// ---- Hardware rendering (OpenGL ES 3 through EGL) ---------------------------
// Only the PSP core renders with OpenGL. EGL and GLES are opened at run time so
// the bridge has no link-time dependency on them (and builds for the host test).
namespace gl {
typedef void* EGLDisplay;
typedef void* EGLConfig;
typedef void* EGLContext;
typedef void* EGLSurface;
typedef int32_t EGLint;
typedef unsigned GLenum;
typedef unsigned GLuint;
typedef int GLint;
typedef int GLsizei;
typedef unsigned GLbitfield;
typedef unsigned char GLboolean;

constexpr EGLint EGL_NONE = 0x3038, EGL_RED_SIZE = 0x3024, EGL_GREEN_SIZE = 0x3023, EGL_BLUE_SIZE = 0x3022,
                 EGL_ALPHA_SIZE = 0x3021, EGL_SURFACE_TYPE = 0x3033, EGL_WINDOW_BIT = 0x0004, EGL_PBUFFER_BIT = 0x0001,
                 EGL_RENDERABLE_TYPE = 0x3040, EGL_OPENGL_ES3_BIT = 0x0040, EGL_CONTEXT_CLIENT_VERSION = 0x3098,
                 EGL_NATIVE_VISUAL_ID = 0x302E, EGL_WIDTH = 0x3057, EGL_HEIGHT = 0x3056;
constexpr GLenum GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9,
                 GL_RENDERBUFFER = 0x8D41, GL_TEXTURE_2D = 0x0DE1, GL_RGBA = 0x1908, GL_RGBA8 = 0x8058,
                 GL_UNSIGNED_BYTE = 0x1401, GL_TEXTURE_MIN_FILTER = 0x2801, GL_TEXTURE_MAG_FILTER = 0x2800,
                 GL_LINEAR = 0x2601, GL_COLOR_ATTACHMENT0 = 0x8CE0, GL_DEPTH24_STENCIL8 = 0x88F0,
                 GL_DEPTH_STENCIL_ATTACHMENT = 0x821A, GL_FRAMEBUFFER_COMPLETE = 0x8CD5, GL_COLOR_BUFFER_BIT = 0x4000,
                 GL_SCISSOR_TEST = 0x0C11, GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6, GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;

struct Api {
    void* egl = nullptr;
    void* gles = nullptr;
    EGLDisplay (*GetDisplay)(void*) = nullptr;
    unsigned (*Initialize)(EGLDisplay, EGLint*, EGLint*) = nullptr;
    unsigned (*ChooseConfig)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*) = nullptr;
    unsigned (*GetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint*) = nullptr;
    EGLContext (*CreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint*) = nullptr;
    EGLSurface (*CreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint*) = nullptr;
    EGLSurface (*CreateWindowSurface)(EGLDisplay, EGLConfig, ANativeWindow*, const EGLint*) = nullptr;
    unsigned (*MakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = nullptr;
    unsigned (*SwapBuffers)(EGLDisplay, EGLSurface) = nullptr;
    unsigned (*DestroySurface)(EGLDisplay, EGLSurface) = nullptr;
    unsigned (*DestroyContext)(EGLDisplay, EGLContext) = nullptr;
    unsigned (*QuerySurface)(EGLDisplay, EGLSurface, EGLint, EGLint*) = nullptr;
    unsigned (*SwapInterval)(EGLDisplay, EGLint) = nullptr;
    void* (*GetProcAddress)(const char*) = nullptr;
    void (*GenFramebuffers)(GLsizei, GLuint*) = nullptr;
    void (*BindFramebuffer)(GLenum, GLuint) = nullptr;
    void (*DeleteFramebuffers)(GLsizei, const GLuint*) = nullptr;
    void (*GenTextures)(GLsizei, GLuint*) = nullptr;
    void (*BindTexture)(GLenum, GLuint) = nullptr;
    void (*DeleteTextures)(GLsizei, const GLuint*) = nullptr;
    void (*TexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*) = nullptr;
    void (*TexParameteri)(GLenum, GLenum, GLint) = nullptr;
    void (*FramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint) = nullptr;
    void (*GenRenderbuffers)(GLsizei, GLuint*) = nullptr;
    void (*BindRenderbuffer)(GLenum, GLuint) = nullptr;
    void (*DeleteRenderbuffers)(GLsizei, const GLuint*) = nullptr;
    void (*RenderbufferStorage)(GLenum, GLenum, GLsizei, GLsizei) = nullptr;
    void (*FramebufferRenderbuffer)(GLenum, GLenum, GLenum, GLuint) = nullptr;
    GLenum (*CheckFramebufferStatus)(GLenum) = nullptr;
    void (*BlitFramebuffer)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum) = nullptr;
    void (*GetIntegerv)(GLenum, GLint*) = nullptr;
    GLboolean (*IsEnabled)(GLenum) = nullptr;
    void (*Enable)(GLenum) = nullptr;
    void (*Disable)(GLenum) = nullptr;
};

template <typename T>
bool bind(void* lib, T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(lib, name));
    return fn != nullptr;
}

// Opens libEGL/libGLESv3 once; false when the device has neither.
bool load(Api& a) {
    if (a.egl != nullptr) {
        return true;
    }
    void* egl = dlopen("libEGL.so", RTLD_NOW);
    void* gles = dlopen("libGLESv3.so", RTLD_NOW);
    if (gles == nullptr) {
        gles = dlopen("libGLESv2.so", RTLD_NOW);
    }
    if (egl == nullptr || gles == nullptr) {
        return false;
    }
    bool ok = bind(egl, a.GetDisplay, "eglGetDisplay") && bind(egl, a.Initialize, "eglInitialize") &&
              bind(egl, a.ChooseConfig, "eglChooseConfig") && bind(egl, a.GetConfigAttrib, "eglGetConfigAttrib") &&
              bind(egl, a.CreateContext, "eglCreateContext") && bind(egl, a.CreatePbufferSurface, "eglCreatePbufferSurface") &&
              bind(egl, a.CreateWindowSurface, "eglCreateWindowSurface") && bind(egl, a.MakeCurrent, "eglMakeCurrent") &&
              bind(egl, a.SwapBuffers, "eglSwapBuffers") && bind(egl, a.DestroySurface, "eglDestroySurface") &&
              bind(egl, a.DestroyContext, "eglDestroyContext") && bind(egl, a.QuerySurface, "eglQuerySurface") &&
              bind(egl, a.SwapInterval, "eglSwapInterval") && bind(egl, a.GetProcAddress, "eglGetProcAddress") &&
              bind(gles, a.GenFramebuffers, "glGenFramebuffers") && bind(gles, a.BindFramebuffer, "glBindFramebuffer") &&
              bind(gles, a.DeleteFramebuffers, "glDeleteFramebuffers") && bind(gles, a.GenTextures, "glGenTextures") &&
              bind(gles, a.BindTexture, "glBindTexture") && bind(gles, a.DeleteTextures, "glDeleteTextures") &&
              bind(gles, a.TexImage2D, "glTexImage2D") && bind(gles, a.TexParameteri, "glTexParameteri") &&
              bind(gles, a.FramebufferTexture2D, "glFramebufferTexture2D") && bind(gles, a.GenRenderbuffers, "glGenRenderbuffers") &&
              bind(gles, a.BindRenderbuffer, "glBindRenderbuffer") && bind(gles, a.DeleteRenderbuffers, "glDeleteRenderbuffers") &&
              bind(gles, a.RenderbufferStorage, "glRenderbufferStorage") &&
              bind(gles, a.FramebufferRenderbuffer, "glFramebufferRenderbuffer") &&
              bind(gles, a.CheckFramebufferStatus, "glCheckFramebufferStatus") && bind(gles, a.BlitFramebuffer, "glBlitFramebuffer") &&
              bind(gles, a.GetIntegerv, "glGetIntegerv") && bind(gles, a.IsEnabled, "glIsEnabled") &&
              bind(gles, a.Enable, "glEnable") && bind(gles, a.Disable, "glDisable");
    if (!ok) {
        return false;
    }
    a.egl = egl;
    a.gles = gles;
    return true;
}
}  // namespace gl

gl::Api gGl;

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
    // Optional: only PCSX ReARMed needs a controller type (DualShock).
    void (*retroSetControllerPortDevice)(unsigned, unsigned) = nullptr;
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
    // Emulation speed in percent of real time: 50, 100 or 200.
    std::atomic<int> speedPercent{100};
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
    // Samples the core produced this frame; handed to Java once per frame. Guarded by
    // coreMutex like every call into the core.
    std::vector<int16_t> audioPending;
    bool initialized = false;
    bool gameLoaded = false;
    // Frames run so far; guarded by coreMutex.
    uint64_t framesRun = 0;
    // The quick save to resume from once the first frame has run.
    bool resumePending = false;
    // The next frame a pending quick save is tried again (PCEE2 refuses one while booting).
    uint64_t resumeRetryAt = 0;
    // While a restored quick save is still on trial, the frame its marker comes off at.
    uint64_t resumeTrialEnd = 0;

    // Left analog stick, -32767..32767 (PSP).
    std::atomic<int> analogX{0};
    std::atomic<int> analogY{0};
    // Right analog stick (PS1 DualShock, PS2).
    std::atomic<int> analogRX{0};
    std::atomic<int> analogRY{0};
    // Touch on the game screen (DS), in libretro pointer units across the screen view.
    std::atomic<int> pointerX{0};
    std::atomic<int> pointerY{0};
    std::atomic<bool> pointerPressed{false};

    // Hardware rendering: the core draws with OpenGL ES into fbo on the emulation
    // thread, which then stretches it onto the window.
    bool hwRender = false;
    retro_hw_render_callback hw{};
    bool hwReady = false;
    gl::EGLDisplay eglDisplay = nullptr;
    gl::EGLConfig eglConfig = nullptr;
    gl::EGLContext eglContext = nullptr;
    gl::EGLSurface eglPbuffer = nullptr;
    gl::EGLSurface eglWindowSurface = nullptr;
    ANativeWindow* eglWindow = nullptr;
    gl::GLuint fbo = 0, fboTexture = 0, fboDepth = 0;
    int fboWidth = 0, fboHeight = 0;
    unsigned maxWidth = 0, maxHeight = 0;
    unsigned lastWidth = 0, lastHeight = 0;
    // Calls from Java that must run on the GL thread (save states and reset).
    std::mutex jobMutex;
    std::condition_variable jobCv;
    std::function<bool()> job;
    bool jobDone = false;
    bool jobResult = false;
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
    if (coreId == "snes9x") return "libsnes9x_libretro_android.so";
    if (coreId == "fceumm") return "libfceumm_libretro_android.so";
    if (coreId == "genesis_plus_gx") return "libgenesis_plus_gx_libretro_android.so";
    if (coreId == "pce_fast") return "libmednafen_pce_fast_libretro_android.so";
    if (coreId == "ppsspp") return "libppsspp_libretro_android.so";
    if (coreId == "pcsx_rearmed") return "libpcsx_rearmed_libretro_android.so";
    if (coreId == "play") return "libplay_libretro_android.so";
    if (coreId == "pcee2") return "libpcee2_libretro_android.so";
    if (coreId == "melonds") return "libmelondsds_libretro_android.so";
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

// MAME 2010/2015 only build their machine inside the first retro_run, so a state
// restored before that dereferences a machine that does not exist yet; PPSSPP and
// Play! restore through their GL context, which is made on the emulation thread.
bool needsFrameBeforeState(const Core* core) {
    return core->coreId == "mame2010" || core->coreId == "mame2015" || core->coreId == "ppsspp" || core->coreId == "play" ||
           core->coreId == "pcee2";
}

// Play! states saved before 0.0.55 were taken while its VM thread kept running and crash
// the game when restored. States saved since then start with this header, so the old ones
// can be told apart wherever the file has been (backups, renames).
constexpr char kPlayStateHeader[8] = {'M', 'E', 'P', 'S', '2', 'S', 'T', '1'};

size_t stateHeaderSize(const Core* core) {
    return core->coreId == "play" ? sizeof(kPlayStateHeader) : 0;
}

// Whether the quick save is written when a game closes and restored when it starts. Not for
// Play!: its states are slow (40 MB) and not reliable enough to restore on every launch;
// PS2 games keep their progress on the memory card, and the menu's quick save still works.
bool autoState(const Core* core) {
    return core->coreId != "play";
}

bool saveState(Core* core) {
    // Before the quick save has been restored (or before MAME has a machine), saving
    // would replace the player's state with the bare start of the game.
    if (core->resumePending || (needsFrameBeforeState(core) && core->framesRun == 0)) {
        return false;
    }
    if (core->retroSerializeSize == nullptr || core->retroSerialize == nullptr) {
        return false;
    }
    size_t size = core->retroSerializeSize();
    if (size == 0) {
        return false;
    }
    size_t header = stateHeaderSize(core);
    std::vector<uint8_t> buffer(header + size);
    memcpy(buffer.data(), kPlayStateHeader, header);
    if (!core->retroSerialize(buffer.data() + header, size)) {
        return false;
    }
    size += header;
    if (core->coreId == "pcee2" && size >= 16) {
        // PCEE2 pads its state to a fixed 96 MB; the real length follows its 8-byte
        // magic/reserved words, and it takes the state back without the padding.
        uint64_t zipSize = 0;
        memcpy(&zipSize, buffer.data() + 8, sizeof(zipSize));
        if (zipSize + 16 <= size) {
            size = static_cast<size_t>(zipSize + 16);
        }
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
    size_t header = stateHeaderSize(core);
    if (header != 0) {
        if (read <= header || memcmp(buffer.data(), kPlayStateHeader, header) != 0) {
            LOGE("Quick save from an older version; moving it aside: %s", core->statePath.c_str());
            rename(core->statePath.c_str(), (core->statePath + ".failed").c_str());
            return false;
        }
    }
    return core->retroUnserialize(buffer.data() + header, read - header);
}

// Frames a restored quick save must run before it counts as good. Play! crashes from a bad
// state only after its own thread picks it up, well after retro_unserialize returns.
constexpr uint64_t kResumeTrialFrames = 600;

std::string resumeMarker(const Core* core) {
    return core->statePath + ".resuming";
}

void endResumeTrial(Core* core) {
    if (core->resumeTrialEnd != 0) {
        core->resumeTrialEnd = 0;
        unlink(resumeMarker(core).c_str());
    }
}

// Restores the quick save when a game starts. A marker file is kept while the core
// reads the state and for the first frames after; if it is still there next time, that
// state crashed the core, so it is moved aside instead of crashing every launch.
// Returns false only when a quick save is there but the core would not take it.
bool resumeState(Core* core) {
    std::string marker = resumeMarker(core);
    struct stat st {};
    if (stat(marker.c_str(), &st) == 0) {
        LOGE("Quick save crashed the core last time; moving it aside: %s", core->statePath.c_str());
        rename(core->statePath.c_str(), (core->statePath + ".failed").c_str());
        unlink(marker.c_str());
        return true;
    }
    if (stat(core->statePath.c_str(), &st) != 0) {
        return true;
    }
    FILE* file = fopen(marker.c_str(), "wb");
    if (file != nullptr) {
        fclose(file);
    }
    if (loadState(core)) {
        core->resumeTrialEnd = core->framesRun + kResumeTrialFrames;
        return true;
    }
    unlink(marker.c_str());
    return false;
}

// PCEE2 takes a state only once its virtual machine runs, which can be many frames
// after the first while the PS2 boots; keep trying for about 15 seconds.
bool retryResume(const Core* core) {
    return core->coreId == "pcee2" && core->framesRun < 900;
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
const int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP, SIGSYS};
// The same lines in files/game_log.bin (a 4-byte count, then the ring), mapped so
// they survive a process the system kills outright, which no handler sees.
char* gMapped = nullptr;

void remember(const char* text) {
    if (text == nullptr || *text == '\0') {
        return;
    }
    unsigned number = gNextLine.fetch_add(1);
    unsigned slot = number % kLines;
    snprintf(gLines[slot], kLineLength, "%s", text);
    size_t length = strlen(gLines[slot]);
    while (length > 0 && (gLines[slot][length - 1] == '\n' || gLines[slot][length - 1] == '\r')) {
        gLines[slot][--length] = '\0';
    }
    if (gMapped != nullptr) {
        memcpy(gMapped + 4 + slot * kLineLength, gLines[slot], kLineLength);
        uint32_t count = number + 1;
        memcpy(gMapped, &count, 4);
    }
}

void mapLogFile(const std::string& filesDir) {
    std::string path = filesDir + "/game_log.bin";
    size_t size = 4 + static_cast<size_t>(kLines) * kLineLength;
    int fd = open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }
    if (ftruncate(fd, static_cast<off_t>(size)) == 0) {
        void* map = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (map != MAP_FAILED) {
            memset(map, 0, size);
            gMapped = static_cast<char*>(map);
        }
    }
    close(fd);
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
// A SIGSEGV handler a core installed over ours (see wrapForeignSegvHandler).
struct sigaction gForeignSegv;

uintptr_t parseHex(const char*& p) {
    uintptr_t value = 0;
    for (;; ++p) {
        char c = *p;
        int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
        if (digit < 0) {
            return value;
        }
        value = value * 16 + static_cast<uintptr_t>(digit);
    }
}

// Writes the /proc/self/maps lines around a fault address (signal safe: no allocation).
void describeMapping(int fd, uintptr_t address) {
    int maps = open("/proc/self/maps", O_RDONLY);
    if (maps < 0) {
        return;
    }
    static char buffer[4096];
    static char line[512];
    static char previous[512];
    size_t used = 0;
    bool found = false;
    int after = 0;
    previous[0] = '\0';
    for (;;) {
        ssize_t count = read(maps, buffer + used, sizeof(buffer) - 1 - used);
        if (count <= 0 && used == 0) {
            break;
        }
        used += count > 0 ? static_cast<size_t>(count) : 0;
        buffer[used] = '\0';
        char* newline = strchr(buffer, '\n');
        if (newline == nullptr) {
            if (count <= 0 || used >= sizeof(buffer) - 1) {
                break;
            }
            continue;
        }
        size_t length = static_cast<size_t>(newline - buffer);
        size_t copy = length < sizeof(line) - 1 ? length : sizeof(line) - 1;
        memcpy(line, buffer, copy);
        line[copy] = '\0';
        memmove(buffer, newline + 1, used - length - 1);
        used -= length + 1;
        if (found) {
            writeText(fd, "  next ");
            writeText(fd, line);
            writeText(fd, "\n");
            if (++after >= 1) {
                break;
            }
            continue;
        }
        const char* p = line;
        uintptr_t start = parseHex(p);
        if (*p == '-') {
            ++p;
        }
        uintptr_t end = parseHex(p);
        if (address >= start && address < end) {
            found = true;
            if (previous[0] != '\0') {
                writeText(fd, "  prev ");
                writeText(fd, previous);
                writeText(fd, "\n");
            }
            writeText(fd, "  map  ");
            writeText(fd, line);
            writeText(fd, "\n");
        } else if (start > address) {
            writeText(fd, "  (no mapping)\n  prev ");
            writeText(fd, previous);
            writeText(fd, "\n  next ");
            writeText(fd, line);
            writeText(fd, "\n");
            break;
        }
        memcpy(previous, line, copy + 1);
    }
    close(maps);
}

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
        if (signal == SIGSEGV || signal == SIGBUS) {
            writeText(fd, "memory:\n");
            describeMapping(fd, info != nullptr ? reinterpret_cast<uintptr_t>(info->si_addr) : 0);
            if (gForeignSegv.sa_sigaction != nullptr) {
                describeAddress(fd, "engine handler", reinterpret_cast<uintptr_t>(gForeignSegv.sa_sigaction));
            }
        }
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

// Play! installs its own SIGSEGV handler on its emulation thread (for its memory
// protection) without passing faults on; for a fault it cannot use it resets SIGSEGV to
// the default and the process dies silently. Wrap whatever handler a core installed:
// the core's runs first, and if it gave up, the fault is reported like any other.
void foreignSegvWrapper(int signal, siginfo_t* info, void* context) {
    if ((gForeignSegv.sa_flags & SA_SIGINFO) != 0 && gForeignSegv.sa_sigaction != nullptr) {
        gForeignSegv.sa_sigaction(signal, info, context);
    } else if (gForeignSegv.sa_handler != SIG_DFL && gForeignSegv.sa_handler != SIG_IGN && gForeignSegv.sa_handler != nullptr) {
        gForeignSegv.sa_handler(signal);
    }
    struct sigaction now{};
    sigaction(SIGSEGV, nullptr, &now);
    if ((now.sa_flags & SA_SIGINFO) == 0 && now.sa_handler == SIG_DFL) {
        handler(signal, info, context);
    }
}

void wrapForeignSegvHandler() {
    if (!gInstalled) {
        return;
    }
    struct sigaction current{};
    if (sigaction(SIGSEGV, nullptr, &current) != 0) {
        return;
    }
    bool siginfo = (current.sa_flags & SA_SIGINFO) != 0;
    if (siginfo && (current.sa_sigaction == foreignSegvWrapper || current.sa_sigaction == handler)) {
        return;
    }
    if (!siginfo && (current.sa_handler == SIG_DFL || current.sa_handler == SIG_IGN)) {
        return;
    }
    gForeignSegv = current;
    struct sigaction wrapper{};
    wrapper.sa_sigaction = foreignSegvWrapper;
    wrapper.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&wrapper.sa_mask);
    sigaction(SIGSEGV, &wrapper, nullptr);
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
    mapLogFile(filesDir);
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

// PSP system language, chosen per game ("Korean" unless the player picked another). Only
// changed before the game starts or on the emulation thread, which reads it.
std::string gPspLanguage = "Korean";
// Set when an option changed while the game runs; the core asks for it every frame.
std::atomic<bool> gVariablesUpdated{false};

// Core options the app pins regardless of what the core reports as default.
const char* fixedVariable(const char* key) {
    if (strcmp(key, "mgba_use_bios") == 0) return "ON";
    if (strcmp(key, "mgba_skip_bios") == 0) return "OFF";
    if (strcmp(key, "mgba_idle_optimization") == 0) return "Remove Known";
    if (strcmp(key, "mgba_frameskip") == 0) return "0";
    // The Famicom pad's TA/TB buttons are FCEUmm's turbo A/B (the X/Y inputs).
    if (strcmp(key, "fceumm_turbo_enable") == 0) return "Player 1";
    // PSP: the bridge offers OpenGL ES only; render at twice the PSP's resolution.
    if (strcmp(key, "ppsspp_backend") == 0) return "opengl";
    if (strcmp(key, "ppsspp_internal_resolution") == 0) return "960x544";
    // PSP system language, Korean by default: Korean games (and Korean patches) pick their
    // text and the kr0.pgf system font from it. Games without Korean that still follow it
    // (Call of Duty: Roads to Victory's name keyboard) are set to English per game.
    if (strcmp(key, "ppsspp_language") == 0) return gPspLanguage.c_str();
    // PS2: native resolution; Play! is heavy enough on phones as it is.
    if (strcmp(key, "play_res_multi") == 0) return "1x";
    // "Fit Screen" letterboxes games whose picture is smaller than the output (GTA San
    // Andreas showed in a box); fill the 4:3 output instead.
    if (strcmp(key, "play_presentation_mode") == 0) return "Fill Screen";
    // PCSX2 skips presenting frames the GS did not redraw (a 30 fps game such as GTA San
    // Andreas) and runs that time as extra emulated frames inside one retro_run, counting on
    // the frontend blocking on audio to hold the speed. Audio here does not block, so the
    // game ran up to twice as fast; present every frame and let the bridge pace them.
    if (strcmp(key, "pcsx2_skip_duplicate_frames") == 0) return "disabled";
    // DS: both screens stacked, with the gap the app draws its hinge into (16 of the 400
    // rows), touched with a finger (no cursor drawn).
    if (strcmp(key, "melonds_number_of_screen_layouts") == 0) return "1";
    if (strcmp(key, "melonds_screen_layout1") == 0) return "top-bottom";
    if (strcmp(key, "melonds_screen_gap") == 0) return "16";
    if (strcmp(key, "melonds_touch_mode") == 0) return "touch";
    if (strcmp(key, "melonds_show_cursor") == 0) return "disabled";
    // Super Game Boy borders would turn the 160x144 Game Boy picture into 256x224.
    if (strcmp(key, "mgba_sgb_borders") == 0) return "OFF";
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

uintptr_t RETRO_CALLCONV hwCurrentFramebuffer() {
    Core* core = gCore;
    return core != nullptr ? core->fbo : 0;
}

retro_proc_address_t RETRO_CALLCONV hwProcAddress(const char* sym) {
    void* fn = gGl.gles != nullptr ? dlsym(gGl.gles, sym) : nullptr;
    if (fn == nullptr && gGl.GetProcAddress != nullptr) {
        fn = gGl.GetProcAddress(sym);
    }
    return reinterpret_cast<retro_proc_address_t>(fn);
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
            *static_cast<bool*>(data) = gVariablesUpdated.exchange(false);
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data == nullptr) return false;
            static_cast<retro_log_callback*>(data)->log = logCallback;
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        // Plain 51 without the experimental flag, as accepted by the original build.
        case 51:
            return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            // OpenGL ES only; the context itself is made on the emulation thread.
            auto* hw = static_cast<retro_hw_render_callback*>(data);
            if (hw == nullptr) return false;
            if (hw->context_type != RETRO_HW_CONTEXT_OPENGLES2 && hw->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
                hw->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION && hw->context_type != RETRO_HW_CONTEXT_OPENGL) {
                return false;
            }
            if (!gl::load(gGl)) {
                LOGE("OpenGL ES is not available");
                return false;
            }
            hw->get_current_framebuffer = hwCurrentFramebuffer;
            hw->get_proc_address = hwProcAddress;
            core->hw = *hw;
            core->hwRender = true;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto* info = static_cast<const retro_system_av_info*>(data);
            if (info == nullptr) return false;
            core->maxWidth = info->geometry.max_width;
            core->maxHeight = info->geometry.max_height;
            if (info->timing.fps > 1.0) {
                core->fps = info->timing.fps;
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
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

// Stretches the core's last frame from fbo onto the window and shows it. Runs on
// the emulation thread with the context current.
void presentHwFrame(Core* core) {
    if (core->eglWindowSurface == nullptr || core->lastWidth == 0 || core->lastHeight == 0) {
        return;
    }
    gl::EGLint width = 0, height = 0;
    gGl.QuerySurface(core->eglDisplay, core->eglWindowSurface, gl::EGL_WIDTH, &width);
    gGl.QuerySurface(core->eglDisplay, core->eglWindowSurface, gl::EGL_HEIGHT, &height);
    if (width <= 0 || height <= 0) {
        return;
    }
    // The core keeps its own GL state; put back what the blit touches.
    gl::GLint readBinding = 0, drawBinding = 0;
    gGl.GetIntegerv(gl::GL_READ_FRAMEBUFFER_BINDING, &readBinding);
    gGl.GetIntegerv(gl::GL_DRAW_FRAMEBUFFER_BINDING, &drawBinding);
    bool scissor = gGl.IsEnabled(gl::GL_SCISSOR_TEST) != 0;
    if (scissor) gGl.Disable(gl::GL_SCISSOR_TEST);
    gGl.BindFramebuffer(gl::GL_READ_FRAMEBUFFER, core->fbo);
    gGl.BindFramebuffer(gl::GL_DRAW_FRAMEBUFFER, 0);
    int w = static_cast<int>(core->lastWidth), h = static_cast<int>(core->lastHeight);
    // Without bottom_left_origin the frame's first row is its top, so it is flipped
    // onto the window, whose first row is the bottom.
    if (core->hw.bottom_left_origin) {
        gGl.BlitFramebuffer(0, 0, w, h, 0, 0, width, height, gl::GL_COLOR_BUFFER_BIT, gl::GL_LINEAR);
    } else {
        gGl.BlitFramebuffer(0, 0, w, h, 0, height, width, 0, gl::GL_COLOR_BUFFER_BIT, gl::GL_LINEAR);
    }
    gGl.SwapBuffers(core->eglDisplay, core->eglWindowSurface);
    gGl.BindFramebuffer(gl::GL_READ_FRAMEBUFFER, static_cast<gl::GLuint>(readBinding));
    gGl.BindFramebuffer(gl::GL_DRAW_FRAMEBUFFER, static_cast<gl::GLuint>(drawBinding));
    if (scissor) gGl.Enable(gl::GL_SCISSOR_TEST);
}

// Copies one frame into the window, converting it to RGB565.
void RETRO_CALLCONV videoRefreshCallback(const void* data, unsigned width, unsigned height, size_t pitch) {
    Core* core = gCore;
    if (core != nullptr && core->hwRender) {
        if (!core->hwReady) {
            return;
        }
        if (data == RETRO_HW_FRAME_BUFFER_VALID) {
            core->lastWidth = width;
            core->lastHeight = height;
        }
        presentHwFrame(core);
        return;
    }
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
    Core* core = gCore;
    if (core != nullptr) {
        core->audioPending.push_back(left);
        core->audioPending.push_back(right);
    }
}

size_t RETRO_CALLCONV audioSampleBatchCallback(const int16_t* data, size_t frames) {
    Core* core = gCore;
    if (core != nullptr && data != nullptr && frames != 0) {
        core->audioPending.insert(core->audioPending.end(), data, data + frames * 2);
    }
    return frames;
}

// One JNI call per frame instead of one per callback (some cores send a few samples
// at a time, which cost a JNI round trip each).
void flushAudio(Core* core) {
    if (core->audioPending.empty()) {
        return;
    }
    writeAudio(core->audioPending.data(), static_cast<jsize>(core->audioPending.size()));
    core->audioPending.clear();
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
        case RETRO_DEVICE_ID_JOYPAD_L2: return kButtonL2;
        case RETRO_DEVICE_ID_JOYPAD_R2: return kButtonR2;
        case RETRO_DEVICE_ID_JOYPAD_L3: return kButtonL3;
        case RETRO_DEVICE_ID_JOYPAD_R3: return kButtonR3;
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

int16_t RETRO_CALLCONV inputStateCallback(unsigned port, unsigned device, unsigned index, unsigned id) {
    Core* core = gCore;
    if (port == 0 && device == RETRO_DEVICE_ANALOG && core != nullptr && index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
        return static_cast<int16_t>(id == RETRO_DEVICE_ID_ANALOG_X ? core->analogX.load() : core->analogY.load());
    }
    if (port == 0 && device == RETRO_DEVICE_ANALOG && core != nullptr && index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
        return static_cast<int16_t>(id == RETRO_DEVICE_ID_ANALOG_X ? core->analogRX.load() : core->analogRY.load());
    }
    if (port == 0 && device == RETRO_DEVICE_POINTER && core != nullptr && index == 0) {
        switch (id) {
            case RETRO_DEVICE_ID_POINTER_X: return static_cast<int16_t>(core->pointerX.load());
            case RETRO_DEVICE_ID_POINTER_Y: return static_cast<int16_t>(core->pointerY.load());
            case RETRO_DEVICE_ID_POINTER_PRESSED: return core->pointerPressed.load() ? 1 : 0;
            case RETRO_DEVICE_ID_POINTER_COUNT: return core->pointerPressed.load() ? 1 : 0;
            default: return 0;
        }
    }
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || core == nullptr) {
        return 0;
    }
    int mask = core->inputMask.load();
    int bit;
    if (core->coreId == "mame2010" || core->coreId == "mame2015") {
        bit = mame2010ButtonBitFor(id);
    } else if (core->coreId == "pce_fast") {
        // TI/TII (the C and D bits) fire I/II for 2 of every 4 frames while held; PC Engine
        // games need a press to last 2 frames. The core's own turbo is an on/off toggle on
        // X/Y, so those stay unpressed.
        bool turbo = (core->framesRun % 4) < 2;
        if (id == RETRO_DEVICE_ID_JOYPAD_A) {
            return ((mask & kButtonA) != 0 || (turbo && (mask & kButtonC) != 0)) ? 1 : 0;
        }
        if (id == RETRO_DEVICE_ID_JOYPAD_B) {
            return ((mask & kButtonB) != 0 || (turbo && (mask & kButtonD) != 0)) ? 1 : 0;
        }
        if (id == RETRO_DEVICE_ID_JOYPAD_X || id == RETRO_DEVICE_ID_JOYPAD_Y) {
            return 0;
        }
        bit = buttonBitFor(id, false);
    } else {
        // Handheld/console pads map A/B/X/Y/L/R one to one (X and Y use the C and D bits).
        bool console = core->coreId == "gba" || core->coreId == "snes9x" || core->coreId == "fceumm" ||
                       core->coreId == "genesis_plus_gx" || core->coreId == "ppsspp" ||
                       core->coreId == "pcsx_rearmed" || core->coreId == "play" || core->coreId == "pcee2" ||
                       core->coreId == "melonds";
        bit = buttonBitFor(id, !console);
    }
    return (mask & bit) != 0 ? 1 : 0;
}

// ---- Emulation thread ------------------------------------------------------

// Makes the context the core draws with, plus an offscreen surface to keep it current
// while no window is attached, and the framebuffer it renders into.
bool setUpHwContext(Core* core) {
    core->eglDisplay = gGl.GetDisplay(nullptr);
    if (core->eglDisplay == nullptr || !gGl.Initialize(core->eglDisplay, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }
    const gl::EGLint configAttribs[] = {gl::EGL_RED_SIZE, 8, gl::EGL_GREEN_SIZE, 8, gl::EGL_BLUE_SIZE, 8,
                                        gl::EGL_ALPHA_SIZE, 8, gl::EGL_SURFACE_TYPE, gl::EGL_WINDOW_BIT | gl::EGL_PBUFFER_BIT,
                                        gl::EGL_RENDERABLE_TYPE, gl::EGL_OPENGL_ES3_BIT, gl::EGL_NONE};
    gl::EGLint count = 0;
    if (!gGl.ChooseConfig(core->eglDisplay, configAttribs, &core->eglConfig, 1, &count) || count < 1) {
        LOGE("No OpenGL ES 3 config");
        return false;
    }
    const gl::EGLint contextAttribs[] = {gl::EGL_CONTEXT_CLIENT_VERSION, 3, gl::EGL_NONE};
    core->eglContext = gGl.CreateContext(core->eglDisplay, core->eglConfig, nullptr, contextAttribs);
    const gl::EGLint pbufferAttribs[] = {gl::EGL_WIDTH, 16, gl::EGL_HEIGHT, 16, gl::EGL_NONE};
    core->eglPbuffer = gGl.CreatePbufferSurface(core->eglDisplay, core->eglConfig, pbufferAttribs);
    if (core->eglContext == nullptr || core->eglPbuffer == nullptr ||
        !gGl.MakeCurrent(core->eglDisplay, core->eglPbuffer, core->eglPbuffer, core->eglContext)) {
        LOGE("Could not create the OpenGL ES context");
        return false;
    }
    int width = static_cast<int>(core->maxWidth > 0 ? core->maxWidth : 1024);
    int height = static_cast<int>(core->maxHeight > 0 ? core->maxHeight : 1024);
    // Play! reports room for 8x its 640x448 output, far more than the 1x it renders.
    int cap = core->coreId == "play" ? 2048 : 4096;
    width = width > cap ? cap : width;
    height = height > cap ? cap : height;
    gGl.GenTextures(1, &core->fboTexture);
    gGl.BindTexture(gl::GL_TEXTURE_2D, core->fboTexture);
    gGl.TexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MIN_FILTER, gl::GL_LINEAR);
    gGl.TexParameteri(gl::GL_TEXTURE_2D, gl::GL_TEXTURE_MAG_FILTER, gl::GL_LINEAR);
    gGl.TexImage2D(gl::GL_TEXTURE_2D, 0, gl::GL_RGBA8, width, height, 0, gl::GL_RGBA, gl::GL_UNSIGNED_BYTE, nullptr);
    gGl.GenFramebuffers(1, &core->fbo);
    gGl.BindFramebuffer(gl::GL_FRAMEBUFFER, core->fbo);
    gGl.FramebufferTexture2D(gl::GL_FRAMEBUFFER, gl::GL_COLOR_ATTACHMENT0, gl::GL_TEXTURE_2D, core->fboTexture, 0);
    if (core->hw.depth || core->hw.stencil) {
        gGl.GenRenderbuffers(1, &core->fboDepth);
        gGl.BindRenderbuffer(gl::GL_RENDERBUFFER, core->fboDepth);
        gGl.RenderbufferStorage(gl::GL_RENDERBUFFER, gl::GL_DEPTH24_STENCIL8, width, height);
        gGl.FramebufferRenderbuffer(gl::GL_FRAMEBUFFER, gl::GL_DEPTH_STENCIL_ATTACHMENT, gl::GL_RENDERBUFFER, core->fboDepth);
    }
    if (gGl.CheckFramebufferStatus(gl::GL_FRAMEBUFFER) != gl::GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer incomplete");
        return false;
    }
    core->fboWidth = width;
    core->fboHeight = height;
    core->hwReady = true;
    if (core->hw.context_reset != nullptr) {
        core->hw.context_reset();
    }
    return true;
}

// Follows the window Java hands over: a new EGL surface for a new window, the
// offscreen one while there is none. The bridge holds its own reference to the
// window it draws into, so Java releasing its one does not pull it away mid-frame.
void bindHwWindow(Core* core) {
    ANativeWindow* wanted;
    {
        std::lock_guard<std::mutex> lock(core->videoMutex);
        wanted = core->window;
        if (wanted != nullptr) {
            ANativeWindow_acquire(wanted);
        }
    }
    if (wanted == core->eglWindow) {
        if (wanted != nullptr) {
            ANativeWindow_release(wanted);
        }
        return;
    }
    if (core->eglWindowSurface != nullptr) {
        gGl.MakeCurrent(core->eglDisplay, core->eglPbuffer, core->eglPbuffer, core->eglContext);
        gGl.DestroySurface(core->eglDisplay, core->eglWindowSurface);
        core->eglWindowSurface = nullptr;
    }
    if (core->eglWindow != nullptr) {
        ANativeWindow_release(core->eglWindow);
    }
    core->eglWindow = wanted;
    if (wanted == nullptr) {
        return;
    }
    gl::EGLint format = 0;
    gGl.GetConfigAttrib(core->eglDisplay, core->eglConfig, gl::EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(wanted, 0, 0, format);
    core->eglWindowSurface = gGl.CreateWindowSurface(core->eglDisplay, core->eglConfig, wanted, nullptr);
    if (core->eglWindowSurface == nullptr ||
        !gGl.MakeCurrent(core->eglDisplay, core->eglWindowSurface, core->eglWindowSurface, core->eglContext)) {
        LOGE("Could not draw into the window");
        if (core->eglWindowSurface != nullptr) {
            gGl.DestroySurface(core->eglDisplay, core->eglWindowSurface);
            core->eglWindowSurface = nullptr;
        }
        gGl.MakeCurrent(core->eglDisplay, core->eglPbuffer, core->eglPbuffer, core->eglContext);
        return;
    }
    // The emulation thread paces frames itself.
    gGl.SwapInterval(core->eglDisplay, 0);
    // Show the last frame straight away, e.g. while paused after a rotation.
    presentHwFrame(core);
}

// Runs a call Java queued for the GL thread.
void runPendingJob(Core* core) {
    std::function<bool()> job;
    {
        std::lock_guard<std::mutex> lock(core->jobMutex);
        if (!core->job) {
            return;
        }
        job = core->job;
        core->job = nullptr;
    }
    bool result;
    {
        std::lock_guard<std::mutex> lock(core->coreMutex);
        result = job();
    }
    {
        std::lock_guard<std::mutex> lock(core->jobMutex);
        core->jobResult = result;
        core->jobDone = true;
    }
    core->jobCv.notify_all();
}

// On the way out a hardware-rendered core saves, lets go of its GL objects and
// unloads while the context is still current. context_destroy comes before
// unload, as in RetroArch: PPSSPP frees the object context_destroy uses when it
// unloads the game.
void tearDownHw(Core* core) {
    if (!core->hwReady) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(core->coreMutex);
        if (core->gameLoaded) {
            if (autoState(core)) {
                saveState(core);
            }
            saveSram(core);
            if (core->hw.context_destroy != nullptr) {
                core->hw.context_destroy();
            }
            core->retroUnloadGame();
            core->gameLoaded = false;
        }
    }
    if (core->fbo != 0) gGl.DeleteFramebuffers(1, &core->fbo);
    if (core->fboTexture != 0) gGl.DeleteTextures(1, &core->fboTexture);
    if (core->fboDepth != 0) gGl.DeleteRenderbuffers(1, &core->fboDepth);
    core->fbo = core->fboTexture = core->fboDepth = 0;
    gGl.MakeCurrent(core->eglDisplay, nullptr, nullptr, nullptr);
    if (core->eglWindowSurface != nullptr) gGl.DestroySurface(core->eglDisplay, core->eglWindowSurface);
    if (core->eglPbuffer != nullptr) gGl.DestroySurface(core->eglDisplay, core->eglPbuffer);
    if (core->eglContext != nullptr) gGl.DestroyContext(core->eglDisplay, core->eglContext);
    core->eglWindowSurface = core->eglPbuffer = core->eglContext = nullptr;
    if (core->eglWindow != nullptr) {
        ANativeWindow_release(core->eglWindow);
        core->eglWindow = nullptr;
    }
    core->hwReady = false;
}

void runLoop(Core* core) {
    // Stay attached to Java for the whole session: audio goes to Java every frame and
    // attaching for each call is expensive.
    JNIEnv* threadEnv = nullptr;
#ifdef __ANDROID__
    bool attached = core->vm != nullptr && core->vm->AttachCurrentThread(&threadEnv, nullptr) == JNI_OK;
#else
    bool attached = core->vm != nullptr && core->vm->AttachCurrentThread(reinterpret_cast<void**>(&threadEnv), nullptr) == JNI_OK;
#endif
    core->audioPending.reserve(8192);
    if (core->hwRender && !setUpHwContext(core)) {
        // Nothing can be drawn; keep answering Java until the game is closed.
        core->hwRender = false;
        LOGE("Hardware rendering unavailable");
    }
    // Frames run against fixed deadlines, so a long frame or a late wake-up is made up by
    // the frames after it. Sleeping "what is left of this frame" lost that time for good,
    // and the game ran a few percent slow (the audio queue drained, the music dragged).
    auto deadline = std::chrono::steady_clock::now();
    while (core->running.load()) {
        if (core->hwReady) {
            bindHwWindow(core);
            runPendingJob(core);
        }
        double speed = core->speedPercent.load() / 100.0;
        double fps = core->fps;
        if (!core->paused.load()) {
            while (core->coreWaiters.load() > 0 && core->running.load()) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
            std::lock_guard<std::mutex> lock(core->coreMutex);
            if (core->coreId == "play" || core->coreId == "pcee2" || core->coreId == "melonds") {
                crash::wrapForeignSegvHandler();
            }
            core->retroRun();
            core->framesRun++;
            flushAudio(core);
            if (core->resumePending) {
                if (core->framesRun >= core->resumeRetryAt) {
                    if (resumeState(core) || !retryResume(core)) {
                        core->resumePending = false;
                    } else {
                        core->resumeRetryAt = core->framesRun + 30;
                    }
                }
            } else if (core->resumeTrialEnd != 0 && core->framesRun >= core->resumeTrialEnd) {
                endResumeTrial(core);
            }
        }
        double frameNs = 1.0 / (fps * speed) * 1e9;
        deadline += std::chrono::nanoseconds(static_cast<int64_t>(frameNs));
        auto now = std::chrono::steady_clock::now();
        if (deadline > now) {
            std::this_thread::sleep_until(deadline);
        } else if (now - deadline > std::chrono::milliseconds(100)) {
            // Too far behind (a game slower than real time, a pause, a save): carry on from
            // here instead of racing through frames to catch up.
            deadline = now;
        }
    }
    {
        std::lock_guard<std::mutex> lock(core->coreMutex);
        endResumeTrial(core);
    }
    tearDownHw(core);
    if (attached) {
        core->vm->DetachCurrentThread();
    }
    // Wake a Java call still waiting for a job that will not run now.
    {
        std::lock_guard<std::mutex> lock(core->jobMutex);
        if (core->job) {
            core->job = nullptr;
            core->jobResult = false;
            core->jobDone = true;
        }
    }
    core->jobCv.notify_all();
}

// Runs fn with the core locked: directly for software cores, on the emulation
// thread (where the GL context lives) for hardware-rendered ones.
bool callCore(Core* core, const std::function<bool()>& fn) {
    if (!core->hwReady || !core->threadStarted || !core->running.load()) {
        CoreLock lock(core);
        return fn();
    }
    std::unique_lock<std::mutex> lock(core->jobMutex);
    core->jobCv.wait_for(lock, std::chrono::seconds(5), [core] { return !core->job; });
    if (core->job) {
        return false;
    }
    core->job = fn;
    core->jobDone = false;
    if (!core->jobCv.wait_for(lock, std::chrono::seconds(10), [core] { return core->jobDone; })) {
        core->job = nullptr;
        return false;
    }
    return core->jobResult;
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
        if (autoState(core)) {
            saveState(core);
        }
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
    if (core->coreId == "play") {
        // Play! keeps its data ("Play Data Files": memory cards, settings) under
        // $EXTERNAL_STORAGE, which the app may not write to; give it the save folder.
        std::string dataDir = core->saveDir + "/play";
        mkdir(dataDir.c_str(), 0700);
        setenv("EXTERNAL_STORAGE", dataDir.c_str(), 1);
        // Its emulation thread attaches to Java through a VM pointer its own app sets in
        // JNI_OnLoad, which a dlopen'd core never gets; hand it ours.
        auto setJavaVm = reinterpret_cast<void (*)(JavaVM*)>(
                dlsym(core->library, "_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM"));
        if (setJavaVm != nullptr) {
            setJavaVm(gVm);
        } else {
            LOGE("Play! has no CJavaVM::SetJavaVM; its emulation thread cannot start");
        }
    }
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
    // PCSX ReARMed starts with a digital pad; the app shows a DualShock.
    core->retroSetControllerPortDevice =
            reinterpret_cast<void (*)(unsigned, unsigned)>(dlsym(core->library, "retro_set_controller_port_device"));
    if (core->coreId == "pcsx_rearmed" && core->retroSetControllerPortDevice != nullptr) {
        core->retroSetControllerPortDevice(0, RETRO_DEVICE_SUBCLASS(RETRO_DEVICE_ANALOG, 1));
    }

    retro_system_av_info info{};
    core->retroGetSystemAvInfo(&info);
    if (info.timing.fps > 1.0) {
        core->fps = info.timing.fps;
    }
    if (core->maxWidth == 0 || core->maxHeight == 0) {
        core->maxWidth = info.geometry.max_width;
        core->maxHeight = info.geometry.max_height;
    }
    core->sampleRate = info.timing.sample_rate > 1.0 ? static_cast<int>(info.timing.sample_rate)
                                                      : kDefaultSampleRate;
    configureAudio(core, core->sampleRate);

    // Resume where the player left off: in-game save first, then the quick save.
    loadSram(core);
    if (!autoState(core)) {
        // Nothing to restore.
    } else if (needsFrameBeforeState(core)) {
        core->resumePending = true;
    } else {
        resumeState(core);
    }

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

// Speed is 0.5x, 1x or 2x (50, 100 or 200 percent). Audio is re-opened at the scaled rate,
// so the samples play out at that speed (and pitch).
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setSpeedPercent(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint percent) {
    Core* core = fromHandle(handle);
    if (core == nullptr) {
        return;
    }
    int speed = percent >= 200 ? 200 : percent <= 50 ? 50 : 100;
    if (core->speedPercent.exchange(speed) == speed) {
        return;
    }
    configureAudio(core, core->sampleRate * speed / 100);
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveNow(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    return callCore(core, [core] {
        bool state = autoState(core) && saveState(core);
        bool sram = saveSram(core);
        return state || sram;
    }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveStateNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    return callCore(core, [core] { return saveState(core); }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_loadStateNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    return callCore(core, [core] {
        if (needsFrameBeforeState(core) && core->framesRun == 0) {
            return false;
        }
        core->resumePending = false;
        return loadState(core);
    }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_example_minigba_NativeGba_saveSramNow(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded) {
        return JNI_FALSE;
    }
    return callCore(core, [core] { return saveSram(core); }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_reset(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    Core* core = fromHandle(handle);
    if (core == nullptr || !core->gameLoaded || core->retroReset == nullptr) {
        return;
    }
    callCore(core, [core] {
        core->retroReset();
        return true;
    });
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setPspLanguage(
        JNIEnv* env, jclass /*cls*/, jlong handle, jstring language) {
    std::string value = jstringToString(env, language);
    if (value != "Korean" && value != "English" && value != "Japanese") {
        return;
    }
    Core* core = fromHandle(handle);
    if (core == nullptr) {
        gPspLanguage = value;
        return;
    }
    if (!core->gameLoaded || core->retroReset == nullptr) {
        return;
    }
    // The PSP game reads the language while it runs, so restart it; the next frame's
    // retro_run picks up the new value before any game code runs.
    callCore(core, [core, value] {
        gPspLanguage = value;
        gVariablesUpdated.store(true);
        core->retroReset();
        return true;
    });
}

// PSP left stick, each axis -32767..32767.
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setAnalog(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint x, jint y) {
    Core* core = fromHandle(handle);
    if (core != nullptr) {
        core->analogX.store(x);
        core->analogY.store(y);
    }
}

// Right stick (PS1 DualShock, PS2), each axis -32767..32767.
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setAnalogRight(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint x, jint y) {
    Core* core = fromHandle(handle);
    if (core != nullptr) {
        core->analogRX.store(x);
        core->analogRY.store(y);
    }
}

// Touch on the game screen: x and y in -32767..32767 across the screen view.
JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_setPointer(
        JNIEnv* /*env*/, jclass /*cls*/, jlong handle, jint x, jint y, jboolean pressed) {
    Core* core = fromHandle(handle);
    if (core != nullptr) {
        core->pointerX.store(x);
        core->pointerY.store(y);
        core->pointerPressed.store(pressed == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL Java_com_example_minigba_NativeGba_destroy(JNIEnv* /*env*/, jclass /*cls*/, jlong handle) {
    destroyCore(fromHandle(handle));
}

}  // extern "C"
