package net.rpcsx;

import android.view.Surface;

// The PS3 engine's JNI glue (libarmsx3-jni.so, from ARMSX3 1.3.2) binds to this class by name and
// forwards each call to the engine library (libarmsx3-core.so), which it dlopen()s in openLibrary.
// Only the calls Mini Emulator makes are declared; the glue's other entries stay unbound.
public final class RPCSX {
    // The engine's state (Emu.GetStatus) by ordinal.
    public static final int STATE_STOPPED = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_STOPPING = 2;
    public static final int STATE_RUNNING = 3;
    public static final int STATE_PAUSED = 4;

    // boot() results that are not failures.
    public static final int BOOT_OK = 0;
    public static final int BOOT_ALREADY_ADDED = 15;

    public static final int SURFACE_CREATED = 0;
    public static final int SURFACE_CHANGED = 1;
    public static final int SURFACE_DESTROYED = 2;

    public static final RPCSX instance = new RPCSX();

    static {
        System.loadLibrary("armsx3-jni");
    }

    private RPCSX() {
    }

    public native boolean openLibrary(String path);
    public native boolean initialize(String rootDir, String user, String socInfo);
    public native boolean installFw(int fd, long progressId);
    public native int boot(String path);
    public native boolean surfaceEvent(Surface surface, int event);
    public native void surfaceSizeChanged(int width, int height);
    public native boolean overlayPadData(int port, int digital1, int digital2, int leftStickX, int leftStickY, int rightStickX, int rightStickY);
    public native boolean processCompilationQueue();
    public native boolean startMainThreadProcessor();
    public native int getState();
    public native void kill();
    public native void resume();
    public native void pause();
    public native void setAppBackgrounded(boolean backgrounded);
    public native String getTitle();
    public native String getVersion();
    public native void openHomeMenu();
    public native boolean saveStateToSlot(int slot);
    public native boolean loadStateFromSlot(int slot);
    public native boolean hasStateInSlot(int slot);
    // A config value by "@@"-joined path (e.g. "Video@@Resolution Scale"), as text.
    public native boolean settingsSet(String path, String value);
}
