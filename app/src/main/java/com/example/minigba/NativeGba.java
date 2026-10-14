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

    static native void setAnalog(long j, int x, int y);

    static native void setAnalogRight(long j, int x, int y);

    // Touch on the game screen (DS): x and y in -32767..32767 across the screen view.
    static native void setPointer(long handle, int x, int y, boolean pressed);

    // 50, 100 or 200 (0.5x, 1x, 2x).
    static native void setSpeedPercent(long j, int percent);

    // PSP system language ("Korean", "English", "Japanese"). With handle 0 it applies to the
    // next game created; with a running game it applies and restarts the game.
    static native void setPspLanguage(long handle, String language);

    // DS: true stacks the two screens with no gap (landscape), false with a 4-row gap.
    // With handle 0 it applies to the next game created; a running game changes layout.
    static native void setNdsGapless(long handle, boolean gapless);

    static native void setSurface(long j, Surface surface);

    static {
        System.loadLibrary("gbabridge");
    }

    private NativeGba() {
    }
}
