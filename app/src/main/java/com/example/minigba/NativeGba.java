package com.example.minigba;

import android.view.Surface;

final class NativeGba {
    static native void clearSurface(long j);

    static native long create(Surface surface, String str, String str2, String str3, String str4, String str5, AudioSink audioSink);

    static native void destroy(long j);

    static native boolean loadStateNow(long j);

    static native void pause(long j, boolean z);

    static native void reset(long j);

    static native boolean saveNow(long j);

    static native boolean saveSramNow(long j);

    static native boolean saveStateNow(long j);

    static native void setInput(long j, int i);

    static native void setSpeedMultiplier(long j, int i);

    static native void setSurface(long j, Surface surface);

    static native void setAnalog(long j, int leftX, int leftY, int rightX, int rightY);

    static native void setAnalogPad(long j, boolean enabled);

    static native int getDiskCount(long j);

    static native int getDiskIndex(long j);

    static native boolean setDiskIndex(long j, int index);

    static {
        System.loadLibrary("gbabridge");
    }

    private NativeGba() {
    }
}
