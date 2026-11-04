package com.example.minigba;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// A zip, 7z or RAR a patch came in: its files sorted into patches (by their first bytes),
// read-me files, Windows programs (the patcher the app stands in for) and the rest. Patches
// and read-mes are taken out into a folder; the rest only listed.
final class PatchArchive {
    static final int PATCH = 0;
    static final int DOC = 1;
    static final int WINDOWS = 2;
    static final int OTHER = 3;

    private static final String[] DOCS = {".txt", ".md", ".nfo", ".diz", ".1st", ".log"};
    private static final String[] PROGRAMS = {".exe", ".bat", ".cmd", ".ps1", ".dll", ".vbs", ".msi", ".com", ".sh", ".jar"};
    private static final long MAX_DOC = 1L << 20;

    static final class Item {
        String path;
        long size;
        int type;
        String format;      // the patch format's name
        File file;          // taken out (patches and read-mes)
        boolean recommended;
        String note = "";
    }

    private PatchArchive() {
    }

    private static boolean endsWithAny(String name, String[] ends) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String end : ends) {
            if (lower.endsWith(end)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSevenZip(File file) {
        byte[] head = new byte[6];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(head);
        } catch (IOException e) {
            return false;
        }
        return head[0] == '7' && head[1] == 'z' && (head[2] & 0xFF) == 0xBC && (head[3] & 0xFF) == 0xAF && head[4] == 0x27 && head[5] == 0x1C;
    }

    static boolean isRar(File file) {
        byte[] head = new byte[4];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(head);
        } catch (IOException e) {
            return false;
        }
        return head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!';
    }

    // The patch format of a file from its first bytes, or null.
    static String sniff(File file) {
        byte[] head = new byte[8];
        int got;
        try (InputStream in = new java.io.FileInputStream(file)) {
            got = in.read(head);
        } catch (IOException e) {
            return null;
        }
        if (got < 4) {
            return null;
        }
        String text = new String(head, 0, got, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (text.startsWith("PATCH")) return "IPS";
        if (text.startsWith("UPS1")) return "UPS";
        if (text.startsWith("BPS1")) return "BPS";
        if (text.startsWith("PPF")) return "PPF";
        if ((head[0] & 0xFF) == 0xD6 && (head[1] & 0xFF) == 0xC3 && (head[2] & 0xFF) == 0xC4) return "xdelta";
        if (text.startsWith("APS1")) return "APS";
        if (text.startsWith("NINJA2")) return "RUP";
        if (text.startsWith("BSDIFF40")) return "BDF";
        if (file.getName().toLowerCase(Locale.ROOT).endsWith(".dcp") && text.startsWith("PK")) return ".dcp";
        return null;
    }

    private static Item classify(String path, long size) {
        Item item = new Item();
        item.path = path;
        item.size = size;
        String name = new File(path).getName();
        if (endsWithAny(name, PROGRAMS)) {
            item.type = WINDOWS;
        } else if (endsWithAny(name, DOCS) || name.toLowerCase(Locale.ROOT).startsWith("readme") || name.contains("읽어")) {
            item.type = DOC;
        } else {
            item.type = PATCH;  // until its first bytes say otherwise
        }
        return item;
    }

    private static boolean wanted(Item item) {
        return item.type == PATCH || (item.type == DOC && item.size <= MAX_DOC);
    }

    private static File target(File dir, String path) {
        String safe = path.replace('\\', '/').replaceAll("^/+", "").replace("../", "_/");
        return new File(dir, safe);
    }

    private static void copy(InputStream in, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("압축 파일을 풀지 못했어요.");
        }
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                o.write(buffer, 0, n);
            }
        }
    }

    // Lists and takes out a zip or 7z into dir.
    static List<Item> open(File archive, File dir) throws IOException {
        List<Item> items = new ArrayList<>();
        if (isRar(archive)) {
            List<String> names = new ArrayList<>();
            List<File> outs = new ArrayList<>();
            for (Listed l : list(archive)) {
                Item item = classify(l.name, l.size);
                if (wanted(item)) {
                    item.file = target(dir, l.name);
                    names.add(l.name);
                    outs.add(item.file);
                }
                items.add(item);
            }
            if (!names.isEmpty()) {
                extract(archive, names, outs, null);
            }
        } else if (isSevenZip(archive)) {
            try (RandomAccessFile raf = new RandomAccessFile(archive, "r");
                 org.apache.commons.compress.archivers.sevenz.SevenZFile seven =
                         new org.apache.commons.compress.archivers.sevenz.SevenZFile(raf.getChannel())) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                while ((entry = seven.getNextEntry()) != null) {
                    if (entry.isDirectory() || entry.isAntiItem()) {
                        continue;
                    }
                    Item item = classify(entry.getName(), entry.getSize());
                    if (wanted(item)) {
                        item.file = target(dir, entry.getName());
                        try (InputStream in = seven.getInputStream(entry)) {
                            copy(in, item.file);
                        }
                    }
                    items.add(item);
                }
            } catch (org.apache.commons.compress.PasswordRequiredException e) {
                throw new IOException("암호가 걸린 7z라서 풀 수 없어요.");
            }
        } else {
            try (ZipFile zip = new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String path = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || path.startsWith("__MACOSX/")) {
                        continue;
                    }
                    Item item = classify(path, entry.getSize());
                    if (wanted(item)) {
                        item.file = target(dir, path);
                        try (InputStream in = zip.getInputStream(entry)) {
                            copy(in, item.file);
                        }
                    }
                    items.add(item);
                }
            } catch (java.util.zip.ZipException e) {
                throw new IOException("zip 파일을 열 수 없어요. 손상됐거나 zip이 아니에요.");
            }
        }
        for (Item item : items) {
            if (item.type == PATCH) {
                item.format = item.file == null ? null : sniff(item.file);
                if (item.format == null) {
                    item.type = OTHER;
                    if (item.file != null) {
                        item.file.delete();
                        item.file = null;
                    }
                }
            }
        }
        return items;
    }

    // ---- Any archive the app reads: zip, 7z (Commons Compress), RAR (libarchive) ----

    static final class Listed {
        String name;
        long size;
        boolean encrypted;
    }

    static List<Listed> list(File archive) throws IOException {
        List<Listed> out = new ArrayList<>();
        if (isRar(archive)) {
            for (RarReader.Entry e : RarReader.entries(archive)) {
                Listed l = new Listed();
                l.name = e.name;
                l.size = e.size;
                l.encrypted = e.encrypted;
                out.add(l);
            }
        } else if (isSevenZip(archive)) {
            try (RandomAccessFile raf = new RandomAccessFile(archive, "r");
                 org.apache.commons.compress.archivers.sevenz.SevenZFile seven =
                         new org.apache.commons.compress.archivers.sevenz.SevenZFile(raf.getChannel())) {
                for (org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e : seven.getEntries()) {
                    if (e.isDirectory() || e.isAntiItem()) {
                        continue;
                    }
                    Listed l = new Listed();
                    l.name = e.getName().replace('\\', '/');
                    l.size = e.getSize();
                    out.add(l);
                }
            } catch (org.apache.commons.compress.PasswordRequiredException e) {
                throw new IOException("암호가 걸린 7z라서 풀 수 없어요.");
            }
        } else {
            try (ZipFile zip = new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    Listed l = new Listed();
                    l.name = e.getName().replace('\\', '/');
                    l.size = e.getSize();
                    out.add(l);
                }
            } catch (java.util.zip.ZipException e) {
                throw new IOException("zip 파일을 열 수 없어요. 손상됐거나 zip이 아니에요.");
            }
        }
        return out;
    }

    // Takes the named entries out to the given files.
    static void extract(File archive, List<String> names, List<File> outs, RarReader.Progress progress) throws IOException {
        if (isRar(archive)) {
            RarReader.extract(archive, names, outs, progress);
            return;
        }
        java.util.Map<String, File> wanted = new java.util.HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            wanted.put(names.get(i), outs.get(i));
        }
        long[] done = {0};
        if (isSevenZip(archive)) {
            try (RandomAccessFile raf = new RandomAccessFile(archive, "r");
                 org.apache.commons.compress.archivers.sevenz.SevenZFile seven =
                         new org.apache.commons.compress.archivers.sevenz.SevenZFile(raf.getChannel())) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
                while (!wanted.isEmpty() && (entry = seven.getNextEntry()) != null) {
                    File out = wanted.remove(entry.getName().replace('\\', '/'));
                    if (out == null) {
                        continue;
                    }
                    try (InputStream in = seven.getInputStream(entry)) {
                        copyCounting(in, out, done, progress);
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(archive)) {
                for (java.util.Map.Entry<String, File> w : wanted.entrySet()) {
                    ZipEntry entry = zip.getEntry(w.getKey());
                    if (entry == null) {
                        throw new IOException(w.getKey() + "을(를) 찾지 못했어요.");
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        copyCounting(in, w.getValue(), done, progress);
                    }
                }
                wanted.clear();
            }
        }
        if (!wanted.isEmpty()) {
            throw new IOException("압축 파일 안에서 파일을 찾지 못했어요.");
        }
    }

    private static void copyCounting(InputStream in, File out, long[] done, RarReader.Progress progress) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("폴더를 만들지 못했어요.");
        }
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] buffer = new byte[1 << 18];
            int n;
            long reported = done[0];
            while ((n = in.read(buffer)) > 0) {
                o.write(buffer, 0, n);
                done[0] += n;
                if (progress != null && done[0] - reported >= (8 << 20)) {
                    reported = done[0];
                    progress.onProgress(done[0]);
                }
            }
        }
    }

    // A read-me's text: UTF-8, else the Korean code page (EUC-KR / CP949).
    static String readText(File file) throws IOException {
        byte[] bytes = new byte[(int) Math.min(file.length(), MAX_DOC)];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(bytes);
        }
        java.nio.charset.CharsetDecoder utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try {
            return utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            try {
                return new String(bytes, "MS949");
            } catch (java.io.UnsupportedEncodingException e2) {
                return new String(bytes, "EUC-KR");
            }
        }
    }
}
