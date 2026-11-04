package com.example.minigba;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// 덧씌우는 한글 패치: patches the engine lays over a game when it starts, the game file left as
// it is. Each patch is kept in files/overlays/<kind>/<game key>/<id>/ with a meta.properties
// (name, on, order, what it holds); before a game starts the patches that are on are put where
// the engine looks:
//   3DS (Citra LayeredFS): <Citra>/load/mods/<title ID>/ (romfs/, romfs_ext/, exefs/, code.ips ...),
//     built of hard links to the kept files, later patches over earlier ones;
//   PS2 (PCSX2 .pnach): <system>/pcsx2/patches/<CRC>_mini_<id>.pnach, group labels taken out so
//     every line is on (PCSX2 turns on unlabelled patches by itself).
final class OverlayPatches {
    static final String KIND_3DS = "3ds";
    static final String KIND_PNACH = "pnach";
    private static final String MARKER = ".mini_overlay";

    // The 3DS mod folder entries Citra reads.
    private static final String[] N3DS_ROOT_FILES = {"code.ips", "code.bps", "code.bin", "exheader.bin", "icon.bin", "banner.bnr", "logo.bcma.lz"};
    private static final String[] N3DS_ROOT_DIRS = {"romfs", "romfs_ext", "exefs"};

    private OverlayPatches() {
    }

    static final class Entry {
        File dir;
        String id;
        String name;
        boolean on;
        int order;
        String detail;
        long size;
    }

    static File root(File filesDir, String kind, String key) {
        return new File(new File(new File(filesDir, "overlays"), kind), key);
    }

    static List<Entry> list(File filesDir, String kind, String key) {
        List<Entry> out = new ArrayList<>();
        File[] dirs = key == null ? null : root(filesDir, kind, key).listFiles(File::isDirectory);
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            Properties meta = new Properties();
            try (InputStream in = new FileInputStream(new File(dir, "meta.properties"))) {
                meta.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                continue;
            }
            Entry e = new Entry();
            e.dir = dir;
            e.id = dir.getName();
            e.name = meta.getProperty("name", dir.getName());
            e.on = "1".equals(meta.getProperty("on", "1"));
            e.detail = meta.getProperty("detail", "");
            try {
                e.order = Integer.parseInt(meta.getProperty("order", "0"));
                e.size = Long.parseLong(meta.getProperty("size", "0"));
            } catch (NumberFormatException ignored) {
                // Defaults.
            }
            out.add(e);
        }
        out.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : a.id.compareTo(b.id));
        return out;
    }

    static void save(Entry e) throws IOException {
        Properties meta = new Properties();
        meta.setProperty("name", e.name);
        meta.setProperty("on", e.on ? "1" : "0");
        meta.setProperty("order", String.valueOf(e.order));
        meta.setProperty("detail", e.detail == null ? "" : e.detail);
        meta.setProperty("size", String.valueOf(e.size));
        try (OutputStream out = new FileOutputStream(new File(e.dir, "meta.properties"))) {
            meta.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), null);
        }
    }

    static int onCount(File filesDir, String kind, String key) {
        int n = 0;
        for (Entry e : list(filesDir, kind, key)) {
            if (e.on) {
                n++;
            }
        }
        return n;
    }

    static Entry create(File filesDir, String kind, String key, String name, String detail) throws IOException {
        File root = root(filesDir, kind, key);
        int order = 0;
        for (Entry e : list(filesDir, kind, key)) {
            order = Math.max(order, e.order + 1);
        }
        File dir = new File(root, Long.toString(System.currentTimeMillis(), 36));
        if (!dir.mkdirs()) {
            throw new IOException("패치를 넣을 폴더를 만들지 못했어요.");
        }
        Entry e = new Entry();
        e.dir = dir;
        e.id = dir.getName();
        e.name = name;
        e.on = true;
        e.order = order;
        e.detail = detail;
        save(e);
        return e;
    }

    static void deleteTree(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    static long treeSize(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += treeSize(child);
            }
        }
        return total;
    }

    // ---- 3DS: which part of a zip or folder is the mod, and for which title ----

    static final class N3dsAnalysis {
        long titleId;          // 0 when the patch does not say
        String prefix = "";    // the path inside the zip / folder that is the mod's root ("" or ending in /)
        int romfsFiles;
        boolean code;
        long size;
        List<String> otherTitles = new ArrayList<>();
    }

    private static boolean hex16(String s) {
        return s.length() == 16 && s.matches("[0-9A-Fa-f]{16}");
    }

    private static boolean modRootChild(String name, boolean dir) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String d : N3DS_ROOT_DIRS) {
            if (dir && lower.equals(d)) {
                return true;
            }
        }
        for (String f : N3DS_ROOT_FILES) {
            if (!dir && lower.equals(f)) {
                return true;
            }
        }
        return false;
    }

    // From the files' paths ("a/b/c", directories left out); gameTitle picks among several titles.
    static N3dsAnalysis analyze3ds(List<String> paths, List<Long> sizes, long gameTitle) throws IOException {
        // Candidate roots: a 16-hex folder holding mod entries, else the folder holding them.
        java.util.LinkedHashMap<String, Long> roots = new java.util.LinkedHashMap<>();
        for (String path : paths) {
            if (path.startsWith("__MACOSX/") || path.contains("/.")) {
                continue;
            }
            String[] seg = path.split("/");
            for (int i = 0; i < seg.length; i++) {
                boolean isDir = i < seg.length - 1;
                if (modRootChild(seg[i], isDir)) {
                    StringBuilder prefix = new StringBuilder();
                    for (int k = 0; k < i; k++) {
                        prefix.append(seg[k]).append('/');
                    }
                    long tid = i > 0 && hex16(seg[i - 1]) ? Long.parseUnsignedLong(seg[i - 1], 16) : 0;
                    roots.putIfAbsent(prefix.toString(), tid);
                    break;
                }
            }
        }
        if (roots.isEmpty()) {
            throw new IOException("3DS 한글 패치(LayeredFS) 파일을 찾지 못했어요. romfs 폴더나 code.ips가 들어 있는 zip · 폴더를 골라 주세요.");
        }
        N3dsAnalysis a = new N3dsAnalysis();
        String chosen = null;
        for (java.util.Map.Entry<String, Long> r : roots.entrySet()) {
            if (r.getValue() != 0 && gameTitle != 0 && sameTitle(r.getValue(), gameTitle)) {
                chosen = r.getKey();
            }
        }
        if (chosen == null) {
            chosen = roots.keySet().iterator().next();
        }
        a.prefix = chosen;
        a.titleId = roots.get(chosen);
        for (java.util.Map.Entry<String, Long> r : roots.entrySet()) {
            if (!r.getKey().equals(chosen) && r.getValue() != 0) {
                a.otherTitles.add(String.format(Locale.US, "%016X", r.getValue()));
            }
        }
        for (int k = 0; k < paths.size(); k++) {
            String path = paths.get(k);
            if (!path.startsWith(a.prefix)) {
                continue;
            }
            String rel = path.substring(a.prefix.length());
            String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
            if (!modRootChild(first, rel.contains("/"))) {
                continue;
            }
            a.size += sizes.get(k);
            String lower = first.toLowerCase(Locale.ROOT);
            if (lower.startsWith("romfs")) {
                a.romfsFiles++;
            } else {
                a.code = true;
            }
        }
        return a;
    }

    // The same game: updates (0004000E) and the game (00040000) share the low half.
    static boolean sameTitle(long a, long b) {
        return (a & 0xFFFFFFFFL) == (b & 0xFFFFFFFFL);
    }

    static boolean inMod(N3dsAnalysis a, String path) {
        if (!path.startsWith(a.prefix)) {
            return false;
        }
        String rel = path.substring(a.prefix.length());
        String first = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
        return !rel.isEmpty() && modRootChild(first, rel.contains("/"));
    }

    // The mod part of a zip into dest (keeps the folder names, lower-cased roots).
    static void extract3ds(File zip, N3dsAnalysis a, File dest) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            byte[] buffer = new byte[1 << 16];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !inMod(a, path) || path.contains("../")) {
                    continue;
                }
                File out = new File(dest, rootLower(path.substring(a.prefix.length())));
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("패치 파일을 풀지 못했어요.");
                }
                try (InputStream in = zf.getInputStream(entry); OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        o.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    // Moves the mod part of a copied folder into dest.
    static void move3ds(File folder, N3dsAnalysis a, List<String> paths, File dest) throws IOException {
        for (String path : paths) {
            if (!inMod(a, path)) {
                continue;
            }
            File from = new File(folder, path);
            File out = new File(dest, rootLower(path.substring(a.prefix.length())));
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("패치 파일을 옮기지 못했어요.");
            }
            if (!from.renameTo(out)) {
                copy(from, out);
            }
        }
    }

    private static String rootLower(String rel) {
        int slash = rel.indexOf('/');
        return slash < 0 ? rel.toLowerCase(Locale.ROOT) : rel.substring(0, slash).toLowerCase(Locale.ROOT) + rel.substring(slash);
    }

    static void listZip(File zip, List<String> paths, List<Long> sizes) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    paths.add(entry.getName().replace('\\', '/'));
                    sizes.add(Math.max(0, entry.getSize()));
                }
            }
        }
    }

    static void listDir(File dir, String prefix, List<String> paths, List<Long> sizes) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                listDir(child, prefix + child.getName() + "/", paths, sizes);
            } else {
                paths.add(prefix + child.getName());
                sizes.add(child.length());
            }
        }
    }

    // Puts the patches that are on into Citra's mod folder for the title (rebuilt each time).
    // A mod folder someone made by hand (no marker) is left alone; false then.
    static boolean sync3ds(File filesDir, File citraUserDir, String key) throws IOException {
        File mods = new File(new File(new File(citraUserDir, "load"), "mods"), key);
        if (mods.exists() && !new File(mods, MARKER).exists()) {
            return false;
        }
        deleteTree(mods);
        List<Entry> on = new ArrayList<>();
        for (Entry e : list(filesDir, KIND_3DS, key)) {
            if (e.on) {
                on.add(e);
            }
        }
        if (on.isEmpty()) {
            return true;
        }
        if (!mods.mkdirs()) {
            throw new IOException("3DS 모드 폴더를 만들지 못했어요.");
        }
        new File(mods, MARKER).createNewFile();
        for (Entry e : on) {
            linkTree(new File(e.dir, "files"), mods);
        }
        return true;
    }

    private static void linkTree(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                if (target.isFile()) {
                    target.delete();
                }
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("3DS 모드 폴더를 만들지 못했어요.");
                }
                linkTree(child, target);
            } else {
                if (target.exists()) {
                    deleteTree(target);
                }
                try {
                    android.system.Os.link(child.getAbsolutePath(), target.getAbsolutePath());
                } catch (Exception | LinkageError e) {
                    copy(child, target);
                }
            }
        }
    }

    static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    // ---- PS2: .pnach ----

    // The CRC in a .pnach's name ("1234ABCD.pnach", "SLUS-20312_1234ABCD.pnach"), or -1.
    static long crcFromName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[_\\- ])([0-9A-Fa-f]{8})(?:\\.pnach)?$").matcher(name.replaceAll("(?i)\\.pnach$", ""));
        return m.find() ? Long.parseLong(m.group(1), 16) : -1;
    }

    // The text with group labels ([Name]) taken out, and how many patch lines it has.
    static String unlabel(String text, int[] patchLines) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                out.append("// ").append(t).append('\n');
                continue;
            }
            if (t.toLowerCase(Locale.ROOT).startsWith("patch") || t.toLowerCase(Locale.ROOT).startsWith("dpatch")) {
                count++;
            }
            out.append(line).append('\n');
        }
        patchLines[0] = count;
        return out.toString();
    }

    static void syncPnach(File filesDir, File patchesDir, String key) throws IOException {
        if (!patchesDir.isDirectory() && !patchesDir.mkdirs()) {
            throw new IOException("PS2 패치 폴더를 만들지 못했어요.");
        }
        File[] old = patchesDir.listFiles((d, n) -> n.startsWith(key + "_mini_") && n.endsWith(".pnach"));
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
        for (Entry e : list(filesDir, KIND_PNACH, key)) {
            if (e.on) {
                copy(new File(e.dir, "patch.pnach"), new File(patchesDir, key + "_mini_" + e.id + ".pnach"));
            }
        }
    }

    // The CRC PCSX2 gives a PS2 disc image (.iso): the XOR of the 32-bit words of the ELF that
    // SYSTEM.CNF boots; -1 when it can't be read (CHD, CSO, a CD image, no SYSTEM.CNF).
    static long ps2IsoCrc(File iso) {
        try (RandomAccessFile in = new RandomAccessFile(iso, "r")) {
            byte[] pvd = new byte[2048];
            in.seek(16L * 2048);
            in.readFully(pvd);
            if (pvd[0] != 1 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0') {
                return -1;
            }
            long rootLba = le32(pvd, 156 + 2);
            long rootSize = le32(pvd, 156 + 10);
            long[] cnf = findFile(in, rootLba, rootSize, "SYSTEM.CNF");
            if (cnf == null || cnf[1] > 4096) {
                return -1;
            }
            byte[] text = new byte[(int) cnf[1]];
            in.seek(cnf[0] * 2048);
            in.readFully(text);
            String boot = null;
            for (String line : new String(text, StandardCharsets.ISO_8859_1).split("\r?\n")) {
                String t = line.trim();
                if (t.toUpperCase(Locale.ROOT).startsWith("BOOT2")) {
                    boot = t.substring(t.indexOf('=') + 1).trim();
                }
            }
            if (boot == null) {
                return -1;
            }
            boot = boot.replaceFirst("(?i)^cdrom0?:", "").replace('\\', '/');
            boot = boot.replaceFirst(";\\d+$", "");
            long lba = rootLba;
            long size = rootSize;
            long[] found = null;
            for (String part : boot.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                found = findFile(in, lba, size, part);
                if (found == null) {
                    return -1;
                }
                lba = found[0];
                size = found[1];
            }
            if (found == null || size > 64L * 1024 * 1024) {
                return -1;
            }
            in.seek(lba * 2048);
            long crc = 0;
            byte[] buffer = new byte[1 << 16];
            long left = size & ~3L;
            while (left > 0) {
                int n = (int) Math.min(buffer.length, left);
                in.readFully(buffer, 0, n);
                for (int k = 0; k < n; k += 4) {
                    crc ^= le32(buffer, k);
                }
                left -= n;
            }
            return crc & 0xFFFFFFFFL;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    // A directory's entry by name (case-insensitive, version ";1" ignored): {lba, size}.
    private static long[] findFile(RandomAccessFile in, long lba, long size, String name) throws IOException {
        byte[] dir = new byte[(int) Math.min(size, 1 << 20)];
        in.seek(lba * 2048);
        in.readFully(dir);
        int pos = 0;
        while (pos < dir.length) {
            int len = dir[pos] & 0xFF;
            if (len == 0) {
                pos = (pos / 2048 + 1) * 2048;
                continue;
            }
            int nameLen = dir[pos + 32] & 0xFF;
            String entry = new String(dir, pos + 33, nameLen, StandardCharsets.ISO_8859_1);
            int semi = entry.indexOf(';');
            if (semi >= 0) {
                entry = entry.substring(0, semi);
            }
            if (entry.endsWith(".")) {
                entry = entry.substring(0, entry.length() - 1);
            }
            if (entry.equalsIgnoreCase(name)) {
                return new long[]{le32(dir, pos + 2), le32(dir, pos + 10)};
            }
            pos += len;
        }
        return null;
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    static String readText(File file) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
