package com.example.minigba;

// "GDI로 풀기": a Dreamcast CHD as a .gdi with its track files (app/src/main/cpp/chdgdi.cpp,
// with libchdr). CHD is compressed, so it is unpacked like this before a patch goes on.
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

    // {bytes the GDI takes, tracks}, or null with the reason in error[0].
    static native long[] info(String chd, String[] error);

    // Writes "<base> (Track NN).bin/.raw" and "<base>.gdi" into dir. Null when done, else why.
    static native String convert(String chd, String dir, String base, Progress progress);
}
