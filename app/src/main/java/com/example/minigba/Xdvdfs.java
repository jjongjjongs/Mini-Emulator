package com.example.minigba;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// (0.0.414) The file system of Xbox and Xbox 360 discs (XDVDFS), for 파일 바꾸기 패치: a zip
// whose folders follow the disc's ("media/text/ko.bin") makes a copy of the image with those
// files replaced. A file that fits the sectors it already has is written there; a bigger one
// goes to the end of the image and its folder entry is pointed at it. Files the disc doesn't
// have can't be added. Also reads the title ID from an Xbox 360 disc's default.xex, for the
// engine's game patches.
final class Xdvdfs {
    static final int SECTOR = 2048;
    private static final long[] PARTITIONS = {0L, 0x18300000L, 0xFD90000L, 0x2080000L};

    static final class Entry {
        String path;         // lower case, '/' between folders
        String shownPath;    // as the disc spells it
        long entryOffset;    // where its sector and size fields are in the image
        long sector;
        long size;
        boolean directory;
    }

    final long base;
    final Map<String, Entry> entries = new LinkedHashMap<>();

    private Xdvdfs(long base) {
        this.base = base;
    }

    static long findBase(RandomAccessFile in) throws IOException {
        byte[] magic = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
        for (long base : PARTITIONS) {
            if (base + 0x10000 + SECTOR > in.length()) continue;
            byte[] head = new byte[20];
            in.seek(base + 0x10000);
            in.readFully(head);
            if (java.util.Arrays.equals(head, magic)) return base;
        }
        return -1;
    }

    static Xdvdfs read(File image) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(image, "r")) {
            long base = findBase(in);
            if (base < 0) {
                throw new IOException("Xbox 디스크 이미지가 아니에요 (XDVDFS를 찾지 못했어요).");
            }
            Xdvdfs disc = new Xdvdfs(base);
            byte[] head = new byte[8];
            in.seek(base + 0x10000 + 20);
            in.readFully(head);
            long rootSector = u32le(head, 0);
            long rootSize = u32le(head, 4);
            disc.readDirectory(in, rootSector, rootSize, "", "", new HashSet<>());
            return disc;
        }
    }

    private void readDirectory(RandomAccessFile in, long sector, long size, String path, String shown, Set<Long> seen) throws IOException {
        if (size <= 0 || size > (64 << 20) || !seen.add(sector)) return;
        long at = base + sector * SECTOR;
        if (at + size > in.length()) throw new IOException("디스크 이미지가 잘렸어요.");
        byte[] table = new byte[(int) size];
        in.seek(at);
        in.readFully(table);
        List<Integer> stack = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        stack.add(0);
        while (!stack.isEmpty()) {
            int offset = stack.remove(stack.size() - 1);
            if (offset < 0 || offset + 14 > table.length || !visited.add(offset)) continue;
            int left = u16le(table, offset);
            int right = u16le(table, offset + 2);
            if (left == 0xFFFF && right == 0xFFFF) continue;  // padding (an empty folder)
            int length = table[offset + 13] & 0xFF;
            if (offset + 14 + length > table.length) continue;
            Entry entry = new Entry();
            entry.shownPath = shown + new String(table, offset + 14, length, StandardCharsets.ISO_8859_1);
            entry.path = entry.shownPath.toLowerCase(Locale.ROOT);
            entry.entryOffset = at + offset + 4;
            entry.sector = u32le(table, offset + 4);
            entry.size = u32le(table, offset + 8);
            entry.directory = (table[offset + 12] & 0x10) != 0;
            entries.put(entry.path, entry);
            if (left != 0) stack.add(left * 4);
            if (right != 0) stack.add(right * 4);
            if (entry.directory) {
                readDirectory(in, entry.sector, entry.size, entry.path + "/", entry.shownPath + "/", seen);
            }
        }
    }

    // ---- 파일 바꾸기 패치 ----

    static final class Plan {
        final List<String> zipNames = new ArrayList<>();   // zip entries that replace a file
        final List<Entry> targets = new ArrayList<>();
        final List<String> unknown = new ArrayList<>();    // files the disc doesn't have
        String prefix = "";                                // the zip's own top folders, skipped
        long grows;                                        // bytes added at the end of the image
    }

    // Lines up the zip's files with the disc's: the zip's top folder(s) ("JSRF 한글패치/") are
    // dropped when that makes more of them match. Read-mes and Windows tools are left out.
    static Plan plan(Xdvdfs disc, java.util.zip.ZipFile zip) {
        List<java.util.zip.ZipEntry> files = new ArrayList<>();
        for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            java.util.zip.ZipEntry entry = e.nextElement();
            String name = entry.getName().replace('\\', '/');
            if (entry.isDirectory() || name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store")) continue;
            files.add(entry);
        }
        Set<String> prefixes = new HashSet<>();
        prefixes.add("");
        for (java.util.zip.ZipEntry entry : files) {
            String name = entry.getName().replace('\\', '/');
            for (int slash = name.indexOf('/'); slash > 0; slash = name.indexOf('/', slash + 1)) {
                prefixes.add(name.substring(0, slash + 1).toLowerCase(Locale.ROOT));
            }
        }
        Plan best = null;
        for (String prefix : prefixes) {
            Plan plan = new Plan();
            plan.prefix = prefix;
            for (java.util.zip.ZipEntry entry : files) {
                String name = entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith(prefix)) {
                    if (!ignorable(lower)) plan.unknown.add(name);
                    continue;
                }
                Entry target = disc.entries.get(lower.substring(prefix.length()));
                if (target != null && !target.directory) {
                    plan.zipNames.add(entry.getName());
                    plan.targets.add(target);
                    long have = roundUp(target.size);
                    if (entry.getSize() > have) plan.grows += roundUp(entry.getSize());
                } else if (!ignorable(lower)) {
                    plan.unknown.add(name);
                }
            }
            if (best == null || plan.targets.size() > best.targets.size()) best = plan;
        }
        return best;
    }

    private static boolean ignorable(String lower) {
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".url") || name.endsWith(".htm") || name.endsWith(".html")
                || name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd") || name.endsWith(".dll") || name.endsWith(".jpg")
                || name.endsWith(".png") || name.endsWith(".nfo") || name.endsWith(".pdf") || name.equals("thumbs.db") || name.startsWith("readme");
    }

    static long roundUp(long size) {
        return (size + SECTOR - 1) / SECTOR * SECTOR;
    }

    interface Progress {
        void at(long done, long total);
    }

    // Copies the image to out, then puts the zip's files in.
    static void apply(File image, Xdvdfs disc, java.util.zip.ZipFile zip, Plan plan, File out, Progress progress) throws IOException {
        long total = image.length() + plan.grows;
        long done = 0;
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = new FileInputStream(image); FileOutputStream copy = new FileOutputStream(out)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                copy.write(buffer, 0, n);
                done += n;
                if (progress != null) progress.at(done, total);
            }
        }
        try (RandomAccessFile target = new RandomAccessFile(out, "rw")) {
            long end = target.length();
            long pad = (end - disc.base) % SECTOR;
            if (pad != 0) end += SECTOR - pad;
            for (int k = 0; k < plan.targets.size(); k++) {
                Entry entry = plan.targets.get(k);
                java.util.zip.ZipEntry source = zip.getEntry(plan.zipNames.get(k));
                long size = source.getSize();
                if (size < 0 || size > 0xFFFFFFFFL) throw new IOException(source.getName() + " 크기를 읽지 못했어요.");
                long at;
                long sector;
                boolean appended = size > roundUp(entry.size);
                if (!appended) {
                    sector = entry.sector;
                    at = disc.base + sector * SECTOR;
                } else {
                    at = end;
                    sector = (at - disc.base) / SECTOR;
                    if (sector > 0xFFFFFFFFL) throw new IOException("이미지가 너무 커요.");
                    end = at + roundUp(size);
                }
                target.seek(at);
                long written = 0;
                try (InputStream data = zip.getInputStream(source)) {
                    for (int n; (n = data.read(buffer)) > 0; ) {
                        target.write(buffer, 0, n);
                        written += n;
                        if (appended) {
                            done += n;
                            if (progress != null) progress.at(done, total);
                        }
                    }
                }
                if (written != size) throw new IOException(source.getName() + "을(를) 다 읽지 못했어요.");
                long rest = roundUp(size) - size;
                if (rest > 0) target.write(new byte[(int) rest]);
                byte[] fields = new byte[8];
                putU32le(fields, 0, sector);
                putU32le(fields, 4, size);
                target.seek(entry.entryOffset);
                target.write(fields);
            }
            if (target.length() < end) target.setLength(end);
        }
    }

    // ---- Xbox 360: the title ID from default.xex (XEX2 execution info) ----

    static String titleId(File image) {
        try {
            if (image.getName().toLowerCase(Locale.ROOT).endsWith(".xex")) {
                try (RandomAccessFile in = new RandomAccessFile(image, "r")) {
                    byte[] head = new byte[(int) Math.min(in.length(), 0x10000)];
                    in.readFully(head);
                    return xexTitleId(head);
                }
            }
            Xdvdfs disc = read(image);
            Entry xex = disc.entries.get("default.xex");
            if (xex == null || xex.size < 24) return null;
            try (RandomAccessFile in = new RandomAccessFile(image, "r")) {
                byte[] head = new byte[(int) Math.min(xex.size, 0x10000)];
                in.seek(disc.base + xex.sector * SECTOR);
                in.readFully(head);
                return xexTitleId(head);
            }
        } catch (Exception ignored) {
            // not readable: no game patches
        }
        return null;
    }

    static String xexTitleId(byte[] head) {
        if (head.length < 0x18 || head[0] != 'X' || head[1] != 'E' || head[2] != 'X' || head[3] != '2') return null;
        long count = u32be(head, 0x14);
        for (int k = 0; k < count && 0x18 + k * 8 + 8 <= head.length; k++) {
            long key = u32be(head, 0x18 + k * 8);
            long value = u32be(head, 0x18 + k * 8 + 4);
            if (key == 0x00040006L && value + 16 <= head.length) {
                return String.format(Locale.ROOT, "%08X", u32be(head, (int) value + 12));
            }
        }
        return null;
    }

    private static int u16le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static long u32le(byte[] b, int o) {
        return (b[o] & 0xFFL) | (b[o + 1] & 0xFFL) << 8 | (b[o + 2] & 0xFFL) << 16 | (b[o + 3] & 0xFFL) << 24;
    }

    private static long u32be(byte[] b, int o) {
        return (b[o] & 0xFFL) << 24 | (b[o + 1] & 0xFFL) << 16 | (b[o + 2] & 0xFFL) << 8 | (b[o + 3] & 0xFFL);
    }

    private static void putU32le(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
