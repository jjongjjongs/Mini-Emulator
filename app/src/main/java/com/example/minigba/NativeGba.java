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

    static native void setN3dsScale(int scale);

    // 3DS CIA install through the Citra core; see the bridge for the status codes.
    interface CiaProgress {
        void onProgress(long done, long total);
    }

    static native int installCia(String userDir, String ciaPath, CiaProgress progress);

    // PSP ad-hoc over the LAN: 0 off, 1 host (built-in server), 2 guest; server is the address to join.
    static native void setPspAdhoc(int mode, String server);

    // DS 통신: 0 off, 1 local host, 2 local guest (host is its address), 3 Wiimmfi, 4 Kaeru WFC.
    static native void setDsNet(int mode, String host);

    // "속도 우선" (speed hacks) for the next PSP or PS2 (PCSX2) game created.
    static native void setSpeedFirst(boolean fast);

    // "속도 우선 · 강하게" for the next PS2 (PCSX2) game created: the EE at 60% with moderate
    // cycle skipping, on top of setSpeedFirst(true).
    static native void setSpeedFirstStrong(boolean strong);

    // "FSR 업스케일" screen filter: 3D engines enlarge the picture with AMD FSR 1.0. Takes effect
    // at once.
    static native void setFsr(boolean on);

    // "전원 모드" for the next game created: 0 성능, 1 균형, 2 절전.
    static native void setPowerMode(int mode);

    // DS microphone: what the game's mic hears (0 the phone's mic, 1 the built-in blowing
    // sound, 2 silence) and how the MIC button works (0 hold, 1 toggle, 2 always).
    static native void setDsMic(int input, int button);

    // Whether the DS game's microphone is on (the core opened it and the MIC button allows).
    static native boolean isDsMicOn();

    // Microphone samples (16-bit mono, 44.1 kHz) for the DS game.
    static native void pushMic(short[] samples, int count);

    // N64 rendering scale for the next game created: 1 (320x240) or 2 (640x480).
    static native void setN64Scale(int scale);

    // For the next game created: no quick save written on leaving or restored on starting.
    static native void setNoAutoState(boolean off);

    // Wii U: where the TV and GamePad pictures go, as fractions of the output (x, y, width,
    // height; a zero width hides one); stack 2 makes the output two TV heights tall.
    static native void setWiiuLayout(int stack, float[] tv, float[] pad);

    // Wii U internal resolution for the next game ("0.5x", "0.75x", "native", "1.5x").
    static native void setWiiuResolution(String value);

    // {frames run, pictures shown, the frame rate the game is paced at}, for the FPS readout.
    static native double[] frameStats(long handle);

    // Vulkan (where the device has it) for the next PSP, Dreamcast or PS2 game created;
    // false keeps OpenGL ES.
    static native void setVulkan(boolean allowed);

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

    static native void setWiiControl(int type);

    // GameCube / Wii: resolution (1-3) and picture shape (0 the game's, 1 4:3, 2 16:9
    // stretched, 3 widescreen hack) for the next game.
    static native void setDolphinVideo(int scale, int aspect);

    // 모션 (자이로): offered to the next game created; readings in the screen's axes,
    // the accelerometer in g and the gyroscope in rad/s.
    static native void setMotionEnabled(boolean on);

    static native void setMotion(float ax, float ay, float az, float gx, float gy, float gz);
}
