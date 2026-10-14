// Host stand-ins for the Android NDK APIs used by gbabridge.cpp.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

struct ANativeWindow {
    int refs = 1;
    int width = 0, height = 0, stride = 0;
    uint16_t* pixels = nullptr;
};

static std::atomic<int> gFrames{0};
static std::atomic<int> gWindows{0};
static std::mutex gPixelLock;
static uint16_t gLastPixel = 0;
static int gLastWidth = 0, gLastHeight = 0;

extern "C" int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int r = __android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return r;
}
extern "C" int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
    if (prio < ANDROID_LOG_WARN) return 0;
    fprintf(stderr, "[%s/%d] ", tag, prio);
    int r = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    return r;
}
extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject surface) {
    if (surface == nullptr) return nullptr;
    gWindows++;
    return new ANativeWindow();
}
extern "C" void ANativeWindow_acquire(ANativeWindow* w) {
    w->refs++;
}
extern "C" void ANativeWindow_release(ANativeWindow* w) {
    if (--w->refs > 0) return;
    gWindows--;
    free(w->pixels);
    delete w;
}
extern "C" int32_t ANativeWindow_setBuffersGeometry(ANativeWindow* w, int32_t width, int32_t height, int32_t format) {
    if (format != WINDOW_FORMAT_RGB_565) abort();
    if (w->width != width || w->height != height) {
        free(w->pixels);
        w->width = width;
        w->height = height;
        w->stride = (width + 7) & ~7;  // padded like real gralloc buffers
        w->pixels = static_cast<uint16_t*>(calloc(static_cast<size_t>(w->stride) * height, 2));
    }
    return 0;
}
extern "C" int32_t ANativeWindow_lock(ANativeWindow* w, ANativeWindow_Buffer* b, ARect*) {
    memset(b, 0, sizeof(*b));
    b->width = w->width;
    b->height = w->height;
    b->stride = w->stride;
    b->format = WINDOW_FORMAT_RGB_565;
    b->bits = w->pixels;
    return 0;
}
extern "C" int32_t ANativeWindow_unlockAndPost(ANativeWindow* w) {
    std::lock_guard<std::mutex> lock(gPixelLock);
    gLastPixel = w->pixels[(w->height / 2) * w->stride + w->width / 2];
    gLastWidth = w->width;
    gLastHeight = w->height;
    gFrames++;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL Java_HostTest_frames(JNIEnv*, jclass) { return gFrames.load(); }
extern "C" JNIEXPORT jint JNICALL Java_HostTest_liveWindows(JNIEnv*, jclass) { return gWindows.load(); }
extern "C" JNIEXPORT jintArray JNICALL Java_HostTest_lastFrame(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(gPixelLock);
    jint v[3] = {gLastPixel, gLastWidth, gLastHeight};
    jintArray a = env->NewIntArray(3);
    env->SetIntArrayRegion(a, 0, 3, v);
    return a;
}
