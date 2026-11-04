package com.example.minigba;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// RAR archives (RAR4 and RAR5) through libarchive (minirar.cpp): listing and taking files out.
// Encrypted archives can't be read; split ones (.part1.rar, .r00) are told apart by name.
final class RarReader {
    private static boolean loaded;

    interface Progress {
        void onProgress(long done);
    }

    static final class Entry {
        String name;
        long size;
        boolean encrypted;
    }

    private RarReader() {
    }

    private static synchronized void load() throws IOException {
        if (!loaded) {
            try {
                System.loadLibrary("minirar");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("RAR를 풀 수 있는 부분을 불러오지 못했어요.");
            }
        }
    }

    private static native String[] list(String path, String[] err);

    private static native String extract(String path, String[] names, String[] outs, Progress progress);

    // A part of a split RAR by its name: name.part2.rar, name.r00 ...
    static boolean isSplitName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.part\\d+\\.rar$") || lower.matches(".*\\.r\\d\\d$");
    }

    static List<Entry> entries(File rar) throws IOException {
        load();
        String[] err = new String[1];
        String[] lines = list(rar.getAbsolutePath(), err);
        if (lines == null) {
            String why = err[0] == null ? "" : err[0];
            if (why.toLowerCase(Locale.ROOT).contains("encrypt") || why.toLowerCase(Locale.ROOT).contains("password")) {
                throw new IOException("암호 걸린 RAR라 풀 수 없어요. 컴퓨터에서 암호를 넣고 풀어서 가져와 주세요.");
            }
            if (why.toLowerCase(Locale.ROOT).contains("multivolume") || why.toLowerCase(Locale.ROOT).contains("volume")) {
                throw new IOException("여러 파일로 나뉜 RAR는 아직 못 풀어요. 한 파일로 된 압축이나 푼 파일을 골라 주세요.");
            }
            throw new IOException("RAR를 읽지 못했어요" + (why.isEmpty() ? "." : " (" + why + ")."));
        }
        List<Entry> out = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) {
                continue;
            }
            Entry e = new Entry();
            e.size = Long.parseLong(parts[0]);
            e.encrypted = "1".equals(parts[1]);
            e.name = parts[2].replace('\\', '/');
            out.add(e);
        }
        return out;
    }

    // Takes the named entries out to the given files in one pass.
    static void extract(File rar, List<String> names, List<File> outs, Progress progress) throws IOException {
        load();
        String[] n = names.toArray(new String[0]);
        String[] o = new String[outs.size()];
        for (int i = 0; i < o.length; i++) {
            File parent = outs.get(i).getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("폴더를 만들지 못했어요.");
            }
            o[i] = outs.get(i).getAbsolutePath();
        }
        String error = extract(rar.getAbsolutePath(), n, o, progress);
        if (error != null) {
            if (error.toLowerCase(Locale.ROOT).contains("encrypt")) {
                throw new IOException("암호 걸린 RAR라 풀 수 없어요.");
            }
            throw new IOException(error);
        }
    }
}
