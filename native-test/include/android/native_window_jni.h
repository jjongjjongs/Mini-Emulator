#pragma once
#include <jni.h>
#include <android/native_window.h>
#ifdef __cplusplus
extern "C" {
#endif
ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface);
#ifdef __cplusplus
}
#endif
