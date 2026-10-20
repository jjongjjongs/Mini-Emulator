package net.rpcsx;

// The engine reports long jobs (firmware install, shader and PPU compilation) here.
public final class ProgressRepository {
    public interface Listener {
        // value < 0: failed. value == max (> 0): done.
        void onProgress(long id, long value, long max, String message);
    }

    private static volatile Listener listener;

    private ProgressRepository() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    // Called by the engine (JNI signature (JJJLjava/lang/String;)Z); false asks it to cancel.
    public static boolean onProgressEvent(long id, long value, long max, String message) {
        Listener l = listener;
        if (l != null) {
            l.onProgress(id, value, max, message);
        }
        return true;
    }
}
