package com.example.minigba;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// PS Vita 한글패치 (0.0.297): a zip of xdelta patches ("data_rom.apk.xdelta" ...) for the
// files of an installed game (Vita3K's ux0/app/<TITLE ID>/), usually with a checksums.txt
// that names each file's path and its SHA-256 before and after. Each file is checked, patched
// into a new file beside it, checked again and only then put in place of the original.
//
// Also reads a game's title ID: from a .pkg's header (its content ID) or from the
// sce_sys/param.sfo inside a .vpk / .zip.
final class VitaPatch {
    static final int MATCH = 0;     // the original the patch was made for
    static final int ALREADY = 1;   // already the patched file
    static final int MISMATCH = 2;  // a different file: left as it is
    static final int MISSING = 3;   // not in the installed game

    static final class Item {
        String patchEntry;          // "data_rom.apk.xdelta" in the zip
        String path;                // "data/data_rom.apk" under the game folder
        File target;
        long originalSize = -1;
        String originalSha;         // lower-case hex, or null when the zip doesn't say
        long patchedSize = -1;
        String patchedSha;
        int state;
    }

    static final class Plan {
        File zip;
        File gameDir;
        final List<Item> items = new ArrayList<>();
        boolean checksums;          // the zip had a checksums list
        String error;

        int count(int state) {
            int n = 0;
            for (Item item : items) {
                if (item.state == state) {
                    n++;
                }
            }
            return n;
        }

        long biggest() {
            long max = 0;
            for (Item item : items) {
                if (item.state == MATCH) {
                    max = Math.max(max, Math.max(item.patchedSize, item.target.length()));
                }
            }
            return max;
        }
    }

    interface Progress {
        // file is 0-based among the files to patch; permille within the whole job
        void update(int file, int files, String name, String step, int permille);

        boolean cancelled();
    }

    private VitaPatch() {
    }

    // ---- title ID ----

    // "PCSG00213", or null.
    static String titleId(File game) {
        String lower = game.getName().toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".pkg")) {
                String content = pkgContentId(game);
                return content != null && content.length() >= 16 ? content.substring(7, 16) : null;
            }
            Map<String, String> sfo = archiveSfo(game);
            return sfo == null ? null : sfo.get("TITLE_ID");
        } catch (IOException e) {
            return null;
        }
    }

    // The content ID in a PS Vita .pkg's header ("JP0177-PCSG00213_00-..."), or null.
    static String pkgContentId(File pkg) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(pkg, "r")) {
            if (in.length() < 0x60) {
                return null;
            }
            byte[] head = new byte[0x60];
            in.readFully(head);
            if ((head[0] & 0xFF) != 0x7F || head[1] != 'P' || head[2] != 'K' || head[3] != 'G') {
                return null;
            }
            int end = 0x30;
            while (end < 0x54 && head[end] != 0) {
                end++;
            }
            String id = new String(head, 0x30, end - 0x30, StandardCharsets.US_ASCII);
            return id.length() >= 16 && id.charAt(6) == '-' ? id : null;
        }
    }

    // The game's param.sfo in a .vpk / .zip (the "gd" one when there are several).
    static Map<String, String> archiveSfo(File archive) throws IOException {
        Map<String, String> first = null;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith("sce_sys/param.sfo") || e.getSize() > (1 << 20)) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(e)) {
                    data = readAll(in, (int) Math.max(e.getSize(), 0));
                }
                Map<String, String> sfo = parseSfo(data);
                if (sfo == null) {
                    continue;
                }
                String category = sfo.get("CATEGORY");
                if (category != null && category.startsWith("gd")) {
                    return sfo;
                }
                if (first == null) {
                    first = sfo;
                }
            }
        }
        return first;
    }

    static Map<String, String> parseSfo(byte[] d) {
        if (d.length < 20 || d[0] != 0 || d[1] != 'P' || d[2] != 'S' || d[3] != 'F') {
            return null;
        }
        int keys = le32(d, 8);
        int data = le32(d, 12);
        int count = le32(d, 16);
        Map<String, String> out = new HashMap<>();
        for (int k = 0; k < count; k++) {
            int at = 20 + k * 16;
            if (at + 16 > d.length) {
                break;
            }
            int keyOffset = (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8;
            int format = (d[at + 2] & 0xFF) | (d[at + 3] & 0xFF) << 8;
            int length = le32(d, at + 4);
            int dataOffset = le32(d, at + 12);
            int keyStart = keys + keyOffset;
            int keyEnd = keyStart;
            while (keyEnd < d.length && d[keyEnd] != 0) {
                keyEnd++;
            }
            if (keyStart >= d.length) {
                continue;
            }
            String key = new String(d, keyStart, keyEnd - keyStart, StandardCharsets.US_ASCII);
            int valueStart = data + dataOffset;
            if (format == 0x0204 && valueStart + length <= d.length) {
                int end = valueStart;
                while (end < valueStart + length && d[end] != 0) {
                    end++;
                }
                out.put(key, new String(d, valueStart, end - valueStart, StandardCharsets.UTF_8));
            } else if (format == 0x0404 && valueStart + 4 <= d.length) {
                out.put(key, String.valueOf(le32(d, valueStart)));
            }
        }
        return out;
    }

    private static int le32(byte[] d, int at) {
        return (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8 | (d[at + 2] & 0xFF) << 16 | (d[at + 3] & 0xFF) << 24;
    }

    private static byte[] readAll(InputStream in, int hint) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(hint, 1024));
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // ---- the patch ----

    private static final Pattern PATH_LINE = Pattern.compile("^([A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)\\s*$");
    private static final Pattern STATE_LINE = Pattern.compile("^\\s*(original|patched)\\s+(\\d+)");
    private static final Pattern SHA256 = Pattern.compile("SHA-?256\\s+([0-9a-fA-F]{64})");

    // checksums.txt: a path line, then "original <size> ... SHA-256 <hex>" and
    // "patched <size> ... SHA-256 <hex>" (the hash may sit on the next line).
    static Map<String, Item> parseChecksums(String text) {
        Map<String, Item> out = new HashMap<>();
        Item current = null;
        String state = null;
        for (String line : text.split("\\r?\\n")) {
            Matcher path = PATH_LINE.matcher(line);
            if (!line.startsWith(" ") && path.matches() && !line.toLowerCase(Locale.ROOT).endsWith(".xdelta")) {
                current = new Item();
                current.path = path.group(1);
                out.put(baseName(current.path).toLowerCase(Locale.ROOT), current);
                state = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher st = STATE_LINE.matcher(line);
            if (st.find()) {
                state = st.group(1);
                long size = Long.parseLong(st.group(2));
                if ("original".equals(state)) {
                    current.originalSize = size;
                } else {
                    current.patchedSize = size;
                }
            } else if (line.trim().endsWith(".xdelta") || line.trim().contains(".xdelta ")) {
                state = null;
            }
            Matcher sha = SHA256.matcher(line);
            if (sha.find() && state != null) {
                if ("original".equals(state)) {
                    current.originalSha = sha.group(1).toLowerCase(Locale.ROOT);
                } else {
                    current.patchedSha = sha.group(1).toLowerCase(Locale.ROOT);
                }
            }
        }
        return out;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    // Finds where each patch goes; the hashing (slow for big files) is in check().
    static Plan analyze(File zipFile, File gameDir) {
        Plan plan = new Plan();
        plan.zip = zipFile;
        plan.gameDir = gameDir;
        try (ZipFile zip = new ZipFile(zipFile)) {
            Map<String, Item> sums = new HashMap<>();
            List<ZipEntry> patches = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String lower = e.getName().toLowerCase(Locale.ROOT);
                if (e.isDirectory()) {
                    continue;
                }
                if (lower.endsWith(".xdelta") || lower.endsWith(".vcdiff")) {
                    patches.add(e);
                } else if (baseName(lower).startsWith("checksum") && lower.endsWith(".txt") && e.getSize() < (1 << 20)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        sums.putAll(parseChecksums(new String(readAll(in, (int) e.getSize()), StandardCharsets.UTF_8)));
                    }
                }
            }
            if (patches.isEmpty()) {
                plan.error = "zip 안에 xdelta 패치(.xdelta)가 없어요. PS Vita 한글패치는 xdelta 파일 방식만 넣을 수 있어요.";
                return plan;
            }
            plan.checksums = !sums.isEmpty();
            Map<String, List<File>> byName = new HashMap<>();
            index(gameDir, byName, 0);
            for (ZipEntry e : patches) {
                String name = baseName(e.getName().replace('\\', '/'));
                String fileName = name.substring(0, name.lastIndexOf('.'));
                Item item = new Item();
                item.patchEntry = e.getName();
                Item sum = sums.get(fileName.toLowerCase(Locale.ROOT));
                if (sum != null) {
                    item.path = sum.path;
                    item.originalSize = sum.originalSize;
                    item.originalSha = sum.originalSha;
                    item.patchedSize = sum.patchedSize;
                    item.patchedSha = sum.patchedSha;
                    item.target = new File(gameDir, sum.path);
                } else {
                    List<File> found = byName.get(fileName.toLowerCase(Locale.ROOT));
                    if (found != null && found.size() == 1) {
                        item.target = found.get(0);
                        item.path = gameDir.toURI().relativize(item.target.toURI()).getPath();
                    } else {
                        item.path = fileName;
                        item.target = new File(gameDir, fileName);
                    }
                }
                item.state = item.target.isFile() ? MATCH : MISSING;
                plan.items.add(item);
            }
            if (plan.count(MATCH) == 0) {
                plan.error = "패치할 파일이 설치된 게임에 없어요. 이 게임의 패치인지 확인해 주세요.";
            }
        } catch (IOException e) {
            plan.error = "zip을 읽지 못했어요: " + e.getMessage();
        }
        return plan;
    }

    private static void index(File dir, Map<String, List<File>> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 6) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                index(f, out, depth + 1);
            } else {
                String key = f.getName().toLowerCase(Locale.ROOT);
                List<File> list = out.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    out.put(key, list);
                }
                list.add(f);
            }
        }
    }

    // Hashes each file there is a checksum for: the original, already patched, or neither.
    static void check(Plan plan, Progress progress) throws IOException {
        long total = 0;
        for (Item item : plan.items) {
            if (item.state == MATCH) {
                total += item.target.length();
            }
        }
        long[] done = {0};
        int index = 0;
        for (Item item : plan.items) {
            if (item.state != MATCH) {
                continue;
            }
            long length = item.target.length();
            if (item.originalSize >= 0 && length != item.originalSize && length != item.patchedSize) {
                item.state = MISMATCH;
                done[0] += length;
                continue;
            }
            if (item.originalSha != null || item.patchedSha != null) {
                final long base = done[0];
                final long all = Math.max(total, 1);
                final int at = index;
                String sha = sha256(item.target, progress, read -> progress.update(at, plan.items.size(), item.path, "원본 확인",
                        (int) ((base + read) * 1000 / all)));
                if (sha == null) {
                    return;
                }
                if (sha.equals(item.patchedSha) && length == (item.patchedSize >= 0 ? item.patchedSize : length)) {
                    item.state = ALREADY;
                } else if (item.originalSha != null && !sha.equals(item.originalSha)) {
                    item.state = MISMATCH;
                }
            }
            done[0] += length;
            index++;
        }
    }

    interface Read {
        void at(long bytes);
    }

    // Lower-case hex SHA-256, or null when cancelled.
    static String sha256(File file, Progress progress, Read read) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buffer = new byte[1 << 20];
        long count = 0;
        long shown = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                count += n;
                if (count - shown >= (16 << 20)) {
                    shown = count;
                    if (progress != null && progress.cancelled()) {
                        return null;
                    }
                    if (read != null) {
                        read.at(count);
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // Patches every MATCH file: "<file>.kopatch" beside it, checked, then renamed over it.
    // Returns the paths patched; throws with the reason on the first failure (files already
    // patched stay patched — each one is whole).
    static List<String> apply(Plan plan, File work, Progress progress) throws IOException {
        List<String> patched = new ArrayList<>();
        List<Item> todo = new ArrayList<>();
        for (Item item : plan.items) {
            if (item.state == MATCH) {
                todo.add(item);
            }
        }
        if (!work.isDirectory() && !work.mkdirs()) {
            throw new IOException("작업 폴더를 만들 수 없어요");
        }
        try (ZipFile zip = new ZipFile(plan.zip)) {
            for (int k = 0; k < todo.size(); k++) {
                if (progress.cancelled()) {
                    break;
                }
                Item item = todo.get(k);
                final int at = k;
                final int count = todo.size();
                progress.update(at, count, item.path, "패치 적용", k * 1000 / count);
                File delta = new File(work, "patch.xdelta");
                try (InputStream in = zip.getInputStream(zip.getEntry(item.patchEntry)); FileOutputStream out = new FileOutputStream(delta)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                File out = new File(item.target.getParentFile(), item.target.getName() + ".kopatch");
                try {
                    try (RomPatcher.Big p = RomPatcher.Big.map(delta, 0, delta.length(), false)) {
                        RomPatcher.Check check = RomPatcher.check(p, item.target);
                        check.skip = 0;
                        RomPatcher.apply(p, item.target, check, out);
                    }
                    if (item.patchedSize >= 0 && out.length() != item.patchedSize) {
                        throw new IOException(item.path + ": 결과 크기가 패치가 말한 것과 달라요");
                    }
                    if (item.patchedSha != null) {
                        progress.update(at, count, item.path, "결과 확인", (k * 1000 + 700) / count);
                        String sha = sha256(out, progress, null);
                        if (sha == null) {
                            out.delete();
                            break;
                        }
                        if (!sha.equals(item.patchedSha)) {
                            throw new IOException(item.path + ": 결과 해시가 맞지 않아요 (원래 파일은 그대로 뒀어요)");
                        }
                    }
                    if (!out.renameTo(item.target)) {
                        throw new IOException(item.path + "을(를) 바꾸지 못했어요");
                    }
                    patched.add(item.path);
                } finally {
                    if (out.exists()) {
                        out.delete();
                    }
                    delta.delete();
                }
            }
        }
        progress.update(todo.size(), todo.size(), "", "끝", 1000);
        return patched;
    }
}
