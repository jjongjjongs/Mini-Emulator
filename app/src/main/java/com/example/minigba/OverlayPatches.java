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
    static final String KIND_WIIU = "wiiu";
    static final String KIND_RIIV = "riiv";
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

    static void linkTree(File from, File to) throws IOException {
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

    // ---- Reading a file of a picked zip or folder ----

    interface Reader {
        byte[] read(String path) throws IOException;
    }

    static Reader zipReader(File zip) {
        return path -> {
            try (ZipFile zf = new ZipFile(zip)) {
                ZipEntry entry = zf.getEntry(path);
                if (entry == null) {
                    throw new IOException(path + "이(가) 없어요.");
                }
                try (InputStream in = zf.getInputStream(entry)) {
                    return readAll(in);
                }
            }
        };
    }

    static Reader dirReader(File dir) {
        return path -> {
            try (InputStream in = new FileInputStream(new File(dir, path))) {
                return readAll(in);
            }
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // Copies the files under prefix (a zip or a copied folder) into dest, keeping their paths.
    static void copyUnder(File source, boolean zip, List<String> paths, String prefix, File dest) throws IOException {
        if (zip) {
            try (ZipFile zf = new ZipFile(source)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                byte[] buffer = new byte[1 << 16];
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String path = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !path.startsWith(prefix) || path.contains("../") || path.startsWith("__MACOSX/")) {
                        continue;
                    }
                    File out = new File(dest, path.substring(prefix.length()));
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
            return;
        }
        for (String path : paths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            File from = new File(source, path);
            File out = new File(dest, path.substring(prefix.length()));
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("패치 파일을 옮기지 못했어요.");
            }
            if (!from.renameTo(out)) {
                copy(from, out);
            }
        }
    }

    // ---- Wii U: Cemu graphic packs (rules.txt + content/ ...) ----

    static final class WiiuAnalysis {
        String prefix = "";
        String name = "";
        List<String> titleIds = new ArrayList<>();
        java.util.LinkedHashMap<String, List<String>> presets = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> presetDefaults = new java.util.HashMap<>();
        int contentFiles;
        long size;
        boolean wrap;          // content/ only (loadiine style): a rules.txt is made for it
        int otherPacks;
    }

    static WiiuAnalysis analyzeWiiu(List<String> paths, List<Long> sizes, Reader reader, long gameTitle) throws IOException {
        List<String> rules = new ArrayList<>();
        for (String p : paths) {
            if (!p.startsWith("__MACOSX/") && new File(p).getName().equalsIgnoreCase("rules.txt")) {
                rules.add(p);
            }
        }
        WiiuAnalysis a = new WiiuAnalysis();
        if (!rules.isEmpty()) {
            String chosen = rules.get(0);
            String chosenText = null;
            for (String r : rules) {
                String text = new String(reader.read(r), StandardCharsets.UTF_8);
                if (chosenText == null) {
                    chosenText = text;
                }
                if (gameTitle != 0 && rulesTitleIds(text).contains(String.format(Locale.US, "%016x", gameTitle))) {
                    chosen = r;
                    chosenText = text;
                    break;
                }
            }
            a.otherPacks = rules.size() - 1;
            a.prefix = chosen.contains("/") ? chosen.substring(0, chosen.lastIndexOf('/') + 1) : "";
            parseRules(chosenText, a);
        } else {
            for (String p : paths) {
                String[] seg = p.split("/");
                for (int i = 0; i < seg.length - 1; i++) {
                    if (seg[i].equalsIgnoreCase("content")) {
                        StringBuilder prefix = new StringBuilder();
                        for (int k = 0; k < i; k++) {
                            prefix.append(seg[k]).append('/');
                        }
                        a.prefix = prefix.toString();
                        a.wrap = true;
                        break;
                    }
                }
                if (a.wrap) {
                    break;
                }
            }
            if (!a.wrap) {
                throw new IOException("Wii U 그래픽 팩(rules.txt)이나 content 폴더를 찾지 못했어요.");
            }
        }
        for (int k = 0; k < paths.size(); k++) {
            String p = paths.get(k);
            if (!p.startsWith(a.prefix)) {
                continue;
            }
            a.size += sizes.get(k);
            if (p.substring(a.prefix.length()).toLowerCase(Locale.ROOT).startsWith("content/")) {
                a.contentFiles++;
            }
        }
        return a;
    }

    static List<String> rulesTitleIds(String text) {
        List<String> ids = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("titleids")) {
                for (String id : t.substring(t.indexOf('=') + 1).split(",")) {
                    String clean = id.trim().toLowerCase(Locale.ROOT);
                    if (!clean.isEmpty()) {
                        ids.add(clean);
                    }
                }
            }
        }
        return ids;
    }

    private static String rulesValue(String line) {
        String v = line.substring(line.indexOf('=') + 1).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    // [Definition] name / titleIds, and the [Preset] sections: category (or none) and name.
    private static void parseRules(String text, WiiuAnalysis a) {
        a.titleIds = rulesTitleIds(text);
        String section = "";
        String presetName = null;
        String presetCategory = "";
        boolean presetDefault = false;
        List<String[]> found = new ArrayList<>();
        for (String raw : (text + "\n[End]").split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                if ("preset".equalsIgnoreCase(section) && presetName != null) {
                    found.add(new String[]{presetCategory, presetName, presetDefault ? "1" : "0"});
                }
                section = line.substring(1, line.length() - 1).trim();
                presetName = null;
                presetCategory = "";
                presetDefault = false;
                continue;
            }
            if (!line.contains("=")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('=')).trim().toLowerCase(Locale.ROOT);
            if ("definition".equalsIgnoreCase(section) && key.equals("name")) {
                a.name = rulesValue(line);
            } else if ("preset".equalsIgnoreCase(section)) {
                if (key.equals("name")) {
                    presetName = rulesValue(line);
                } else if (key.equals("category")) {
                    presetCategory = rulesValue(line);
                } else if (key.equals("default")) {
                    presetDefault = rulesValue(line).equals("1") || rulesValue(line).equalsIgnoreCase("true");
                }
            }
        }
        for (String[] p : found) {
            a.presets.computeIfAbsent(p[0], c -> new ArrayList<>()).add(p[1]);
            if (p[2].equals("1") || !a.presetDefaults.containsKey(p[0])) {
                if (p[2].equals("1") || a.presets.get(p[0]).size() == 1) {
                    a.presetDefaults.put(p[0], p[1]);
                }
            }
        }
    }

    // Cemu's core option key for a pack in customGraphicPacks (FNV-1a over "custom\x1f" folder
    // "\x1f" category, as the libretro Cemu names them).
    static String cemuPackKey(String folder, String category) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : ("custom\u001f" + folder + "\u001f" + category).getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xFF;
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.US, "cemu_zgp_%016x", hash);
    }

    static final String SEP = "\u001f";

    // The packs that are on, in <system>/Cemu/customGraphicPacks/MiniEmulator/<id>, and the
    // options that turn them on (with their presets) in options.txt next to them.
    static void syncWiiu(File filesDir, File cemuDir, String key) throws IOException {
        File base = new File(new File(cemuDir, "customGraphicPacks"), "MiniEmulator");
        deleteTree(base);
        StringBuilder options = new StringBuilder();
        for (Entry e : list(filesDir, KIND_WIIU, key)) {
            if (!e.on) {
                continue;
            }
            File dest = new File(base, e.id);
            if (!dest.mkdirs()) {
                throw new IOException("그래픽 팩 폴더를 만들지 못했어요.");
            }
            linkTree(new File(e.dir, "files"), dest);
            Properties meta = meta(e);
            String folder = "MiniEmulator/" + e.id;
            String cats = meta.getProperty("pcats", "");
            boolean presets = meta.getProperty("haspresets", "0").equals("1");
            options.append(cemuPackKey(folder, presets ? "\u001eenabled" : "")).append("=enabled\n");
            if (presets) {
                int n = 0;
                for (String cat : cats.split(SEP, -1)) {
                    String sel = meta.getProperty("psel." + n, "");
                    if (!sel.isEmpty()) {
                        options.append(cemuPackKey(folder, cat)).append('=').append(sel).append('\n');
                    }
                    n++;
                }
            }
        }
        if (options.length() > 0) {
            try (OutputStream out = new FileOutputStream(new File(base, "options.txt"))) {
                out.write(options.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static Properties meta(Entry e) {
        Properties meta = new Properties();
        try (InputStream in = new FileInputStream(new File(e.dir, "meta.properties"))) {
            meta.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Empty.
        }
        return meta;
    }

    static void setMeta(Entry e, String key, String value) throws IOException {
        Properties meta = meta(e);
        meta.setProperty(key, value);
        try (OutputStream out = new FileOutputStream(new File(e.dir, "meta.properties"))) {
            meta.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), null);
        }
    }

    // The title ID of a Wii U game in a .wua (ZArchive): its top folders are "<title>_v<version>";
    // the game's (0005000 0) one, else 0.
    static long wuaTitleId(File wua) {
        try (RandomAccessFile in = new RandomAccessFile(wua, "r")) {
            long length = in.length();
            if (length < 144) {
                return 0;
            }
            byte[] footer = new byte[144];
            in.seek(length - 144);
            in.readFully(footer);
            if (be32(footer, 140) != 0x169F52D6L) {
                return 0;
            }
            long namesAt = be64(footer, 32);
            long namesSize = be64(footer, 40);
            long treeAt = be64(footer, 48);
            long treeSize = be64(footer, 56);
            if (treeSize < 16 || namesSize > 64L * 1024 * 1024 || treeAt + treeSize > length) {
                return 0;
            }
            byte[] root = new byte[16];
            in.seek(treeAt);
            in.readFully(root);
            long start = be32(root, 4);
            long count = be32(root, 8);
            long fallback = 0;
            for (long i = 0; i < Math.min(count, 64); i++) {
                byte[] node = new byte[16];
                in.seek(treeAt + (start + i) * 16);
                in.readFully(node);
                long nameOffset = be32(node, 0) & 0x7FFFFFFFL;
                in.seek(namesAt + nameOffset);
                int len = in.read();
                if ((len & 0x80) != 0) {
                    len = (len & 0x7F) | (in.read() << 7);
                }
                if (len <= 0 || len > 255) {
                    continue;
                }
                byte[] name = new byte[len];
                in.readFully(name);
                String text = new String(name, StandardCharsets.ISO_8859_1);
                if (text.length() >= 16 && text.substring(0, 16).matches("[0-9a-fA-F]{16}")) {
                    long tid = Long.parseUnsignedLong(text.substring(0, 16), 16);
                    if ((tid >>> 32) == 0x00050000L) {
                        return tid;
                    }
                    if (fallback == 0) {
                        fallback = tid;
                    }
                }
            }
            return fallback;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static long be32(byte[] b, int at) {
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | (b[at + 3] & 0xFFL);
    }

    private static long be64(byte[] b, int at) {
        return be32(b, at) << 32 | be32(b, at + 4);
    }

    // ---- Wii: Riivolution (riivolution/*.xml + the folders it names) ----

    static final class RiivOption {
        String id;
        String name;
        List<String> choices = new ArrayList<>();
        int defaultChoice;   // 0 off, 1.. a choice
    }

    static final class RiivAnalysis {
        String prefix = "";
        String game = "";
        List<String> regions = new ArrayList<>();
        List<RiivOption> options = new ArrayList<>();
        int xmls;
        int files;
        long size;
    }

    static RiivAnalysis analyzeRiiv(List<String> paths, List<Long> sizes, Reader reader, String gameId) throws IOException {
        RiivAnalysis a = new RiivAnalysis();
        List<String> xmls = new ArrayList<>();
        for (String p : paths) {
            String[] seg = p.split("/");
            if (seg.length >= 2 && seg[seg.length - 2].equalsIgnoreCase("riivolution") && seg[seg.length - 1].toLowerCase(Locale.ROOT).endsWith(".xml")) {
                xmls.add(p);
            }
        }
        if (xmls.isEmpty()) {
            throw new IOException("Riivolution 패치(riivolution 폴더 안의 .xml)를 찾지 못했어요.");
        }
        String first = xmls.get(0);
        a.prefix = first.substring(0, first.length() - ("riivolution/" + new File(first).getName()).length());
        for (String x : xmls) {
            if (!x.startsWith(a.prefix)) {
                continue;
            }
            a.xmls++;
            parseRiiv(reader.read(x), a, gameId);
        }
        for (int k = 0; k < paths.size(); k++) {
            if (paths.get(k).startsWith(a.prefix)) {
                a.files++;
                a.size += sizes.get(k);
            }
        }
        return a;
    }

    private static void parseRiiv(byte[] xml, RiivAnalysis a, String gameId) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
            org.w3c.dom.NodeList ids = doc.getElementsByTagName("id");
            if (ids.getLength() > 0) {
                org.w3c.dom.Element id = (org.w3c.dom.Element) ids.item(0);
                if (a.game.isEmpty()) {
                    a.game = id.getAttribute("game");
                }
                org.w3c.dom.NodeList regions = id.getElementsByTagName("region");
                for (int i = 0; i < regions.getLength(); i++) {
                    String r = ((org.w3c.dom.Element) regions.item(i)).getAttribute("type");
                    if (!r.isEmpty() && !a.regions.contains(r)) {
                        a.regions.add(r);
                    }
                }
            }
            org.w3c.dom.NodeList sections = doc.getElementsByTagName("section");
            for (int s = 0; s < sections.getLength(); s++) {
                org.w3c.dom.Element section = (org.w3c.dom.Element) sections.item(s);
                org.w3c.dom.NodeList options = section.getElementsByTagName("option");
                for (int o = 0; o < options.getLength(); o++) {
                    org.w3c.dom.Element option = (org.w3c.dom.Element) options.item(o);
                    RiivOption ro = new RiivOption();
                    ro.name = option.getAttribute("name");
                    ro.id = option.getAttribute("id").isEmpty() ? section.getAttribute("name") + ro.name : option.getAttribute("id");
                    try {
                        ro.defaultChoice = option.getAttribute("default").isEmpty() ? 0 : Integer.parseInt(option.getAttribute("default"));
                    } catch (NumberFormatException e) {
                        ro.defaultChoice = 0;
                    }
                    org.w3c.dom.NodeList choices = option.getElementsByTagName("choice");
                    for (int c = 0; c < choices.getLength(); c++) {
                        ro.choices.add(((org.w3c.dom.Element) choices.item(c)).getAttribute("name"));
                    }
                    // A translation is on when added: an option that starts off gets its first choice.
                    if (ro.defaultChoice == 0 && !ro.choices.isEmpty()) {
                        ro.defaultChoice = 1;
                    }
                    a.options.add(ro);
                }
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("Riivolution XML을 읽지 못했어요: " + e.getMessage());
        }
    }

    // Whether the patch's game filter takes this disc (game ID prefix, and region if listed).
    static boolean riivFits(RiivAnalysis a, String gameId) {
        if (gameId == null || gameId.length() < 4) {
            return true;
        }
        if (!a.game.isEmpty() && !gameId.startsWith(a.game)) {
            return false;
        }
        return a.regions.isEmpty() || a.regions.contains(gameId.substring(3, 4));
    }

    // The patches that are on, in <Dolphin User>/Load/Riivolution, with
    // riivolution/config/<game>.xml holding the chosen options.
    static boolean syncRiiv(File filesDir, File dolphinUser, String key) throws IOException {
        File root = new File(new File(dolphinUser, "Load"), "Riivolution");
        if (root.exists() && !new File(root, MARKER).exists() && new File(root, "riivolution").exists()) {
            return false;
        }
        deleteTree(root);
        List<Entry> on = new ArrayList<>();
        for (Entry e : list(filesDir, KIND_RIIV, key)) {
            if (e.on) {
                on.add(e);
            }
        }
        if (on.isEmpty()) {
            return true;
        }
        if (!root.mkdirs()) {
            throw new IOException("Riivolution 폴더를 만들지 못했어요.");
        }
        new File(root, MARKER).createNewFile();
        StringBuilder config = new StringBuilder("<riivolution version=\"2\">\n");
        for (Entry e : on) {
            linkTree(new File(e.dir, "files"), root);
            Properties meta = meta(e);
            int count = Integer.parseInt(meta.getProperty("opts", "0"));
            for (int i = 0; i < count; i++) {
                config.append("  <option id=\"").append(xmlEscape(meta.getProperty("opt." + i + ".id", ""))).append("\" default=\"")
                        .append(meta.getProperty("opt." + i + ".sel", "0")).append("\" />\n");
            }
        }
        config.append("</riivolution>\n");
        File configDir = new File(new File(root, "riivolution"), "config");
        if (!configDir.isDirectory() && !configDir.mkdirs()) {
            throw new IOException("Riivolution 설정을 쓰지 못했어요.");
        }
        try (OutputStream out = new FileOutputStream(new File(configDir, key.substring(0, Math.min(4, key.length())) + ".xml"))) {
            out.write(config.toString().getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // The 6-letter ID of a GameCube / Wii disc image (.iso .gcm, .wbfs, .rvz .wia, .ciso), and
    // whether it is a Wii disc; null when it can't be read (.gcz and others).
    static String[] discId(File disc) {
        try (RandomAccessFile in = new RandomAccessFile(disc, "r")) {
            byte[] head = new byte[0x60];
            if (in.length() < 0x60) {
                return null;
            }
            in.readFully(head);
            long at;
            String magic = new String(head, 0, 4, StandardCharsets.ISO_8859_1);
            if (magic.equals("WBFS")) {
                at = 1L << (head[8] & 0xFF);
            } else if (magic.equals("RVZ\u0001") || magic.equals("WIA\u0001")) {
                at = 0x58;
            } else if (magic.equals("CISO")) {
                at = 0x8000;
            } else {
                at = 0;
            }
            byte[] disc0 = new byte[0x20];
            in.seek(at);
            in.readFully(disc0);
            String id = new String(disc0, 0, 6, StandardCharsets.ISO_8859_1);
            if (!id.matches("[0-9A-Z]{6}")) {
                return null;
            }
            boolean wii = be32(disc0, 0x18) == 0x5D1C9EA3L;
            return new String[]{id, wii ? "wii" : "gc"};
        } catch (IOException | RuntimeException e) {
            return null;
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
