package com.armsx3.core;

import android.view.Surface;

/**
 * The ARMSX3 engine's JNI entry points (libarmsx3-jni.so, from the 1.6.7 release; see
 * cores/fetch-armsx3.sh). The native side binds to this class name and these signatures;
 * only the calls Mini Emulator uses are declared.
 */
public final class ARMSX3 {
    public static final int STATE_STOPPED = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_STOPPING = 2;
    public static final int STATE_RUNNING = 3;
    public static final int STATE_PAUSED = 4;

    public static final int BOOT_OK = 0;
    public static final int BOOT_ALREADY_ADDED = 15;

    public static final int SURFACE_CREATED = 0;
    public static final int SURFACE_CHANGED = 1;
    public static final int SURFACE_DESTROYED = 2;

    public static final ARMSX3 instance = new ARMSX3();

    static {
        System.loadLibrary("armsx3-jni");
    }

    private ARMSX3() {
    }

    public native boolean openLibrary(String path);

    public native String getLibraryVersion(String path);

    public native boolean initialize(String rootDir, String user, String socInfo);

    public native void setPrivateCacheDir(String dir);

    public native boolean installFw(int fd, long progressId);

    public native boolean install(int fd, long progressId);

    public native boolean installKey(int fd, long progressId, String gamePath);

    public native boolean isInstallableFile(int fd);

    public native String probePkgInfo(int fd);

    public native int boot(String path);

    public native int changeDisc(String path);

    public native boolean surfaceEvent(Surface surface, int event);

    public native void surfaceSizeChanged(int width, int height);

    public native boolean overlayPadData(int port, int digital1, int digital2, int leftStickX, int leftStickY, int rightStickX, int rightStickY);

    public native void setPadSensor(int port, int x, int y, int z, int g);

    public native boolean processCompilationQueue();

    public native boolean startMainThreadProcessor();

    public native int getState();

    public native void kill();

    public native void resume();

    public native void pause();

    public native void setAppBackgrounded(boolean backgrounded);

    public native String getTitle();

    public native String getTitleId();

    public native String getVersion();

    public native void openHomeMenu();

    public native boolean saveStateToSlot(int slot);

    public native boolean loadStateFromSlot(int slot);

    public native boolean hasStateInSlot(int slot);

    public native boolean deleteStateFromSlot(int slot);

    public native boolean settingsSet(String path, String value);

    public native String settingsGet(String path);

    public native void settingsBeginBatch();

    public native void settingsEndBatch();

    public native void setGpuTurbo(boolean on);

    public native long getFramePeriodNs();

    public native long getFrameWorkNs();

    public native String takeGameError();

    // Game patches (patch.yml): the list for a title as JSON, one switched on or off, an
    // imported file, and the engine's patch format version.
    public native String patchesList(String titleId);

    public native boolean patchSetEnabled(String hash, String title, String serial, String version, boolean enabled);

    public native int patchesImport(String content);

    public native int patchesWrite(String file, String content);

    public native boolean isRestartPending();

    public native String patchEngineVersion();
}
