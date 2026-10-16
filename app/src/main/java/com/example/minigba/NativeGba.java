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

    // PSP rendering scale (1x, 2x or 3x the PSP's 480x272) for the next game created.
    static native void setPspScale(int scale);

    // PSP "속도 우선" (speed hacks and auto frameskip) for the next game created.
    static native void setPspFast(boolean fast);

    // PSP on Vulkan (where the device has it) for the next game created; false keeps OpenGL ES.
    static native void setPspVulkan(boolean allowed);

    // DS screen layout: 0 stacked with a 4-row gap, 1 stacked and touching, 2 side by side.
    // With handle 0 it applies to the next game created; a running game changes layout.
    static native void setNdsLayout(long handle, int layout);

    // Quick save slots: write the game's state to a file of the app's choosing, or load one.
    static native boolean saveStateFile(long handle, String path);

    static native boolean loadStateFile(long handle, String path);

    // "선명하게" screen filter: software frames scaled up with nearest neighbour first.
    static native void setSharpScaling(boolean sharp);

    // The game picture's width and height (0, 0 before the first frame).
    static native int[] frameSize(long handle);

    static native void setSurface(long j, Surface surface);

    static {
        System.loadLibrary("gbabridge");
    }

    private NativeGba() {
    }

    static native void setRewindEnabled(long handle, boolean enabled);

    static native void setRewinding(long handle, boolean held);

    static native float rewoundSeconds(long handle);

    static native boolean setCheats(long handle, String[] codes);

    static native void setWonderSwanTall(boolean tall);
}
