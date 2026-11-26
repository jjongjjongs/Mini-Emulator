package com.rfandango.haku_x;

import org.libsdl.app.SDLActivity;

// (0.0.415) The original Xbox engine's Java side: HakuX's core (libhakuxcore.so, GPL-3.0-or-later,
// https://github.com/rfandango/hakuX, release v1.3.0; cores/fetch-hakux.sh) runs on SDL3 and binds
// its functions to this class by name (Java_com_rfandango_haku_1x_NativeCoreActivity_*), so the
// class keeps HakuX's package and name. The screen, buttons and menus are the app's
// (com.example.minigba.XboxActivity, which extends this). The declarations follow HakuX's
// NativeCoreActivity.kt at v1.3.0.
public abstract class NativeCoreActivity extends SDLActivity {
    /** fps, average frame time (ms), last frame time (ms), 1 if push-buffer decoding renders. */
    public static native float[] nativeGetFrameStats();

    /** Why the game can't run on this device (the GPU driver, or the game), or null. */
    public static native String nativeGetFatal();

    /** Whose fault that is: 1 the driver, 2 the game, 3 a savestate, 4 the GPU lost, 5 the boot, or 0. */
    public static native int nativeGetFatalKind();

    /** The core stopped to boot another of the title's XBEs. */
    public static native boolean nativeGetRelaunch();

    /** The core stopped as the title left (to the dashboard, a reboot, a shutdown). */
    public static native boolean nativeGetQuit();

    /** The players with a controller plugged in (bit n: player n + 1). */
    public static native void nativeSetPads(int mask);

    /** A console on the network with this one's identity, or null. */
    public static native String nativeTakeTwinConsole();

    /** A title frame has been on screen. */
    public static native boolean nativeGetFrameShown();

    /** The HLE GPU's stutter and hang monitor lines. */
    public static native String nativeGetHleHitches();

    /** How long the GPU has been on the game's work, in ms. */
    public static native int nativeGpuBusyMs();

    /** A moment of idle GPU for a window frame. */
    public static native void nativeGpuGap();

    /** The cached shaders' build before the game: {built, to build}. */
    public static native int[] nativeGetShaderBuild();

    /** Player 1's pad from the screen: NC_PAD_* buttons, A B X Y Black White as bits 0-5, sticks -1..1 (down +), triggers 0..1. */
    public static native void nativeSetOverlayPad(int buttons, int analogMask, float lx, float ly, float rx, float ry, float lt, float rt);

    /** A player's pad from a physical controller. */
    public static native void nativeSetPad(int player, int buttons, long analog, int lx, int ly, int rx, int ry);

    /** The rumble the title last sent to a player: left << 16 | right. */
    public static native int nativeGetRumble(int player);

    /** The emulation paused or resumed. */
    public static native void nativeSetPaused(boolean paused);

    /** Saves the running game to path (not on the UI thread): null, or why it couldn't. */
    public static native String nativeSaveState(String path);

    /** Whether the savestate at path can load into this game: null, or why not. */
    public static native String nativeStateCheck(String path);

    /** debug: a state request, or null. */
    public static native String nativeTakeStateRequest();

    /** Savestates work for the running game (push-buffer mode). */
    public static native boolean nativeStatesAvailable();

    /** A cheat of the run's manifest on or off. */
    public static native void nativeSetCheat(int index, boolean on);

    @Override
    protected String[] getLibraries() {
        return new String[]{"SDL3", "hakuxcore"};
    }

    /** The libraries loaded and SDL started (SDLActivity.onCreate went through). */
    protected static boolean engineLoaded() {
        return !mBrokenLibraries && mSingleton != null;
    }
}
