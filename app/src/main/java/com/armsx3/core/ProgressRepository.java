package com.armsx3.core;

/** Called back by the engine with the progress of an install or a compile. */
public final class ProgressRepository {
    public interface Listener {
        void onProgress(long id, long value, long max, String message);
    }

    private static volatile Listener listener;

    private ProgressRepository() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static boolean onProgressEvent(long id, long value, long max, String message) {
        Listener l = listener;
        if (l != null) {
            l.onProgress(id, value, max, message);
        }
        return true;
    }
}
