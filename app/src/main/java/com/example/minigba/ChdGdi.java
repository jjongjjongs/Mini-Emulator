package com.example.minigba;

// "GDI로 풀기" / "CUE로 풀기" / "ISO로 풀기": a CHD as a Dreamcast .gdi with its tracks, a CD's
// .cue with its tracks, or a DVD's .iso (app/src/main/cpp/chdgdi.cpp, with libchdr). CHD is
// compressed, so it is unpacked like this before a patch goes on.
final class ChdGdi {
    interface Progress {
        // False cancels.
        boolean onProgress(long done, long total);
    }

    private static boolean loaded;

    private ChdGdi() {
    }

    static synchronized boolean load() {
        if (!loaded) {
            try {
                System.loadLibrary("chdgdi");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                return false;
            }
        }
        return true;
    }

    // {bytes the unpacked game takes, tracks, kind: 0 GD-ROM, 1 CD, 2 DVD}, or null with the
    // reason in error[0].
    static native long[] info(String chd, String[] error);

    // Writes "<base>.gdi" / "<base>.cue" with "<base> (Track NN).bin…", or "<base>.iso", into dir.
    // Null when done, else why.
    static native String convert(String chd, String dir, String base, Progress progress);
}
