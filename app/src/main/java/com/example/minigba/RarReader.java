package com.example.minigba;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// RAR archives (RAR4 and RAR5) through libarchive (minirar.cpp): listing and taking files out.
// Encrypted archives can't be read; split ones (.part1.rar …, .rar + .r00 …) are read as one
// when all their parts are given in order.
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

    private static native String[] list(String[] paths, String[] err);

    private static native String extract(String[] paths, String[] names, String[] outs, Progress progress);

    private static String[] pathsOf(List<File> parts) {
        String[] out = new String[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parts.get(i).getAbsolutePath();
        }
        return out;
    }

    // ---- Split archives: name.part1.rar, name.part2.rar … or name.rar, name.r00, name.r01 … ----

    private static final java.util.regex.Pattern NEW_STYLE = java.util.regex.Pattern.compile("(?i)^(.*)\\.part(\\d+)\\.rar$");
    private static final java.util.regex.Pattern OLD_STYLE = java.util.regex.Pattern.compile("(?i)^(.*)\\.r(\\d\\d)$");

    // The order of a part within its set: {base, index} (index 0 for name.rar of the old style),
    // or null for a name that is not part of a split set.
    static Object[] partOf(String name) {
        java.util.regex.Matcher m = NEW_STYLE.matcher(name);
        if (m.matches()) {
            return new Object[]{m.group(1) + "\u0001new", Integer.parseInt(m.group(2))};
        }
        m = OLD_STYLE.matcher(name);
        if (m.matches()) {
            return new Object[]{m.group(1) + "\u0001old", Integer.parseInt(m.group(2)) + 1};
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".rar")) {
            return new Object[]{name.substring(0, name.length() - 4) + "\u0001old", 0};
        }
        return null;
    }

    static boolean sameSet(String a, String b) {
        Object[] pa = partOf(a);
        Object[] pb = partOf(b);
        return pa != null && pb != null && pa[0].equals(pb[0]);
    }

    // Sorts the names of one set in reading order.
    static void sortParts(List<String> names) {
        names.sort((x, y) -> Integer.compare((Integer) partOf(x)[1], (Integer) partOf(y)[1]));
    }

    // The names missing from a set (sorted names of one set): gaps, a missing first part, or —
    // for a lone .part1 — the part after it.
    static List<String> missingParts(List<String> sorted) {
        List<String> missing = new ArrayList<>();
        if (sorted.isEmpty()) {
            return missing;
        }
        String sample = sorted.get(0);
        java.util.regex.Matcher m = NEW_STYLE.matcher(sample);
        boolean newStyle = m.matches();
        java.util.Set<Integer> have = new java.util.HashSet<>();
        int max = 0;
        for (String n : sorted) {
            int index = (Integer) partOf(n)[1];
            have.add(index);
            max = Math.max(max, index);
        }
        if (newStyle) {
            String base = m.group(1);
            int width = m.group(2).length();
            if (max == 1) {
                max = 2;  // a set of one part is no set
            }
            for (int i = 1; i <= max; i++) {
                if (!have.contains(i)) {
                    missing.add(base + ".part" + String.format(Locale.ROOT, "%0" + width + "d", i) + ".rar");
                }
            }
        } else {
            Object[] p = partOf(sample);
            String base = ((String) p[0]).substring(0, ((String) p[0]).length() - 4);
            if (!have.contains(0)) {
                missing.add(base + ".rar");
            }
            for (int i = 1; i <= max; i++) {
                if (!have.contains(i)) {
                    missing.add(base + String.format(Locale.ROOT, ".r%02d", i - 1));
                }
            }
        }
        return missing;
    }

    // The other parts of the set a file belongs to, from its folder, sorted (the file included).
    static List<File> siblings(File part) {
        List<File> out = new ArrayList<>();
        File dir = part.getParentFile();
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) {
            out.add(part);
            return out;
        }
        List<String> names = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && sameSet(f.getName(), part.getName())) {
                names.add(f.getName());
            }
        }
        sortParts(names);
        for (String n : names) {
            out.add(new File(dir, n));
        }
        return out;
    }

    // A part of a split RAR by its name: name.part2.rar, name.r00 ...
    static boolean isSplitName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.part\\d+\\.rar$") || lower.matches(".*\\.r\\d\\d$");
    }

    static List<Entry> entries(File rar) throws IOException {
        return entries(java.util.Collections.singletonList(rar));
    }

    static List<Entry> entries(List<File> parts) throws IOException {
        load();
        String[] err = new String[1];
        String[] lines = list(pathsOf(parts), err);
        if (lines == null) {
            String why = err[0] == null ? "" : err[0];
            if (why.toLowerCase(Locale.ROOT).contains("encrypt") || why.toLowerCase(Locale.ROOT).contains("password")) {
                throw new IOException("암호 걸린 RAR라 풀 수 없어요. 컴퓨터에서 암호를 넣고 풀어서 가져와 주세요.");
            }
            if (why.toLowerCase(Locale.ROOT).contains("volume") || why.toLowerCase(Locale.ROOT).contains("truncated")) {
                throw new IOException("분할 압축의 조각이 빠졌거나 순서가 맞지 않아요. 조각을 모두 함께 골라 주세요.");
            }
            throw new IOException("RAR를 읽지 못했어요" + (why.isEmpty() ? "." : " (" + why + ")."));
        }
        List<Entry> out = new ArrayList<>();
        for (String line : lines) {
            String[] fields = line.split("\t", 3);
            if (fields.length < 3) {
                continue;
            }
            Entry e = new Entry();
            e.size = Long.parseLong(fields[0]);
            e.encrypted = "1".equals(fields[1]);
            e.name = fields[2].replace('\\', '/');
            out.add(e);
        }
        return out;
    }

    // Takes the named entries out to the given files in one pass.
    static void extract(File rar, List<String> names, List<File> outs, Progress progress) throws IOException {
        extract(java.util.Collections.singletonList(rar), names, outs, progress);
    }

    static void extract(List<File> parts, List<String> names, List<File> outs, Progress progress) throws IOException {
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
        String error = extract(pathsOf(parts), n, o, progress);
        if (error != null) {
            if (error.toLowerCase(Locale.ROOT).contains("encrypt")) {
                throw new IOException("암호 걸린 RAR라 풀 수 없어요.");
            }
            throw new IOException(error);
        }
    }
}
