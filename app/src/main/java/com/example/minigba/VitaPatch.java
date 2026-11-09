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
        File patchFile;             // the unpacked "data_rom.apk.xdelta"
        String path;                // "data/data_rom.apk" under the game folder
        File target;
        long originalSize = -1;
        String originalSha;         // lower-case hex, or null when the zip doesn't say
        long patchedSize = -1;
        String patchedSha;
        int state;
    }

    // A file of the patch put in place of a game file, or added (0.0.349).
    static final class Put {
        String path;                // shown: "data/DATA00.PAC" or "업데이트/data/DATA00.PAC"
        String rel;                 // under its root folder
        String root;                // "app" (ux0/app/<ID>) or "patch" (the update, ux0/patch/<ID>)
        File source;                // the unpacked file
        File target;
        boolean exists;             // replaces a file (else a new one)
        boolean exec;               // eboot.bin / a module
        String skip;                // why it stays out, or null
    }

    static final class Plan {
        File folder;                // the unpacked patch
        File gameDir;
        File updateDir;             // the installed update, or null
        final List<Item> items = new ArrayList<>();
        final List<Put> puts = new ArrayList<>();
        boolean checksums;          // the zip had a checksums list
        String code;                // a Vita3K code patch, written clean, or null
        int codeLines;
        final List<String> codeNames = new ArrayList<>();
        String otherGame;           // the patch is for this other title ID
        String error;

        int putCount(boolean withExec) {
            int n = 0;
            for (Put put : puts) {
                if (put.skip == null && (withExec || !put.exec)) {
                    n++;
                }
            }
            return n;
        }

        boolean hasExec() {
            for (Put put : puts) {
                if (put.skip == null && put.exec) {
                    return true;
                }
            }
            return false;
        }

        long putBytes(boolean withExec, boolean replacedOnly) {
            long n = 0;
            for (Put put : puts) {
                if (put.skip == null && (withExec || !put.exec) && (!replacedOnly || put.exists)) {
                    n += replacedOnly ? put.target.length() : put.source.length();
                }
            }
            return n;
        }

        long matchBytes() {
            long n = 0;
            for (Item item : items) {
                if (item.state == MATCH) {
                    n += item.target.length();
                }
            }
            return n;
        }

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
            if (lower.endsWith(FOLDER_EXT)) {
                Map<String, String> sfo = folderSfo(folderContent(folderOf(game)));
                return sfo == null ? null : sfo.get("TITLE_ID");
            }
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
    // ---- A game folder (PS Vita dump: app/<TITLE ID>/ with eboot.bin and sce_sys/param.sfo)
    // in the list: a small "<name>.vitadir" file holding the folder's path; the engine is given
    // the folder and installs (and for NoNpDrm dumps decrypts) it from there. ----

    static final String FOLDER_EXT = ".vitadir";

    static boolean isFolderGame(File game) {
        return game.getName().toLowerCase(Locale.ROOT).endsWith(FOLDER_EXT);
    }

    /** The folder a .vitadir file points at (may be gone). */
    static File folderOf(File pointer) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(pointer.toPath());
            String path = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
            int line = path.indexOf('\n');
            return new File(line >= 0 ? path.substring(0, line).trim() : path);
        } catch (IOException | RuntimeException e) {
            return new File("/nonexistent");
        }
    }

    /** The game's own folder inside: itself, app/<ID>/, or a sub folder — as the engine finds it. */
    static File folderContent(File folder) {
        if (new File(folder, "sce_sys/param.sfo").isFile()) {
            return folder;
        }
        File[] apps = new File(folder, "app").listFiles(File::isDirectory);
        for (File app : apps == null ? new File[0] : apps) {
            if (new File(app, "sce_sys/param.sfo").isFile()) {
                return app;
            }
        }
        File[] subs = folder.listFiles(File::isDirectory);
        for (File sub : subs == null ? new File[0] : subs) {
            if (new File(sub, "sce_sys/param.sfo").isFile()) {
                return sub;
            }
        }
        return null;
    }

    /** param.sfo of a game folder (the content folder from folderContent), or null. */
    static Map<String, String> folderSfo(File content) {
        if (content == null) {
            return null;
        }
        File sfo = new File(content, "sce_sys/param.sfo");
        if (!sfo.isFile() || sfo.length() > (1 << 20)) {
            return null;
        }
        try {
            return parseSfo(java.nio.file.Files.readAllBytes(sfo.toPath()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Map<String, String> archiveSfo(File archive) throws IOException {
        if (isFolderGame(archive)) {
            return folderSfo(folderContent(folderOf(archive)));
        }
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

    // Finds what a patch (unpacked into a folder) does to an installed game (0.0.349):
    //  · xdelta patches (with an optional checksums list) — the hashing is in check();
    //  · files to put over the game: "rePatch/<ID>/…", "<ID>/…", "app/<ID>/…" or the game's own
    //    paths ("data/…"); a file the installed update has goes into the update folder;
    //  · a Vita3K code patch ("<ID>.txt", or this game's part of a patchlist.txt).
    static Plan analyze(File folder, File gameDir, File updateDir, String titleId) {
        Plan plan = new Plan();
        plan.folder = folder;
        plan.gameDir = gameDir;
        boolean haveUpdate = updateDir != null && updateDir.isDirectory();
        plan.updateDir = haveUpdate ? updateDir : null;
        String id = titleId.toUpperCase(Locale.ROOT);
        try {
            List<String> files = new ArrayList<>();
            listFiles(folder, "", files, 0);
            Map<String, Item> sums = new HashMap<>();
            List<String> patches = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            StringBuilder code = new StringBuilder();
            for (String rel : files) {
                String lower = rel.toLowerCase(Locale.ROOT);
                String base = baseName(lower);
                File f = new File(folder, rel);
                if (lower.endsWith(".xdelta") || lower.endsWith(".vcdiff")) {
                    patches.add(rel);
                } else if (base.startsWith("checksum") && base.endsWith(".txt") && f.length() < (1 << 20)) {
                    sums.putAll(parseChecksums(readText(f)));
                } else if (base.endsWith(".txt") && f.length() < (1 << 20) && (base.equals("patchlist.txt") || base.toUpperCase(Locale.ROOT).contains(id))) {
                    String part = codePatch(readText(f), id, base.equals("patchlist.txt"));
                    if (part != null) {
                        code.append(part);
                        plan.codeNames.add(baseName(rel));
                    } else {
                        rest.add(rel);
                    }
                } else {
                    rest.add(rel);
                }
            }
            if (code.length() > 0) {
                plan.code = code.toString();
                plan.codeLines = countPatchLines(plan.code);
            }
            analyzeDeltas(plan, folder, patches, sums, gameDir, haveUpdate ? updateDir : null);
            analyzeFiles(plan, folder, rest, id, gameDir, haveUpdate ? updateDir : null);
            if (plan.items.isEmpty() && plan.puts.isEmpty() && plan.code == null) {
                plan.error = plan.otherGame != null
                        ? "다른 게임(" + plan.otherGame + ")의 패치예요. 이 게임은 " + id + "예요.\n같은 게임이라도 지역판(일본 PCSG · 아시아 PCSH · 북미 PCSE …)이 다르면 패치도 달라요."
                        : "이 압축에서 게임에 넣을 파일을 찾지 못했어요. 넣을 수 있는 패치 모양:\n · rePatch/" + id + "/… (실기용 rePatch 폴더)\n · " + id + "/… · app/" + id + "/…\n · 게임 안 경로 그대로 (예: data/…)\n · xdelta 패치 (.xdelta)\n · Vita3K 코드 패치 (" + id + ".txt)";
            } else if (!plan.items.isEmpty() && plan.count(MATCH) == 0 && plan.puts.isEmpty() && plan.code == null) {
                StringBuilder names = new StringBuilder();
                int shown = 0;
                for (Item item : plan.items) {
                    if (shown++ < 6) {
                        names.append("\n · ").append(item.path);
                    }
                }
                if (plan.items.size() > 6) {
                    names.append("\n · … 모두 ").append(plan.items.size()).append("개");
                }
                plan.error = "패치할 파일이 설치된 게임에 없어요. 이 패치가 찾는 파일:" + names
                        + "\n\n· 다른 지역판(예: 아시아판 PCSH · 북미판 PCSE)이나 다른 게임의 패치일 수 있어요."
                        + (haveUpdate ? "" : "\n· 업데이트를 설치한 게임용 패치일 수 있어요. 이 게임에는 설치된 업데이트가 없어요 (게임 길게 누르기 → 업데이트 · DLC PKG).")
                        + "\n· 파일 이름이 다르게 붙은 패치라면, 패치 설명서에 적힌 대상 파일 이름으로 xdelta 이름을 바꿔 zip에 넣어 보세요 (예: eboot.bin.xdelta).";
            }
        } catch (IOException e) {
            plan.error = "패치를 읽지 못했어요: " + e.getMessage();
        }
        return plan;
    }

    private static void analyzeDeltas(Plan plan, File folder, List<String> patches, Map<String, Item> sums, File gameDir, File updateDir) throws IOException {
        if (patches.isEmpty()) {
            return;
        }
        boolean haveUpdate = updateDir != null;
        plan.checksums = !sums.isEmpty();
        Map<String, List<File>> byName = new HashMap<>();
        Map<String, List<File>> byNameUpdate = new HashMap<>();
        if (haveUpdate) {
            index(updateDir, byNameUpdate, 0);
        }
        index(gameDir, byName, 0);
        for (String rel : patches) {
            File patchFile = new File(folder, rel);
            String name = baseName(rel);
            String fileName = name.substring(0, name.lastIndexOf('.'));
            // A patch named after the release ("grand-kingdom-…-data00.xdelta"): the file
            // it was made from, as xdelta3 wrote it in its header, or the name's last part
            // that is a file of the game (0.0.332).
            if (findIn(byNameUpdate, byName, fileName) == null) {
                String fromHeader;
                try (InputStream in = new FileInputStream(patchFile)) {
                    fromHeader = vcdiffSourceName(in);
                }
                if (fromHeader != null && findIn(byNameUpdate, byName, fromHeader) != null) {
                    fileName = fromHeader;
                } else {
                    String bySuffix = suffixMatch(fileName, byNameUpdate, byName);
                    if (bySuffix != null) {
                        fileName = bySuffix;
                    } else if (fromHeader != null) {
                        fileName = fromHeader;
                    }
                }
            }
            Item item = new Item();
            item.patchFile = patchFile;
            Item sum = sums.get(fileName.toLowerCase(Locale.ROOT));
            if (sum != null) {
                item.path = sum.path;
                item.originalSize = sum.originalSize;
                item.originalSha = sum.originalSha;
                item.patchedSize = sum.patchedSize;
                item.patchedSha = sum.patchedSha;
                item.target = haveUpdate && new File(updateDir, sum.path).isFile() ? new File(updateDir, sum.path) : new File(gameDir, sum.path);
            } else {
                List<File> inUpdate = byNameUpdate.get(fileName.toLowerCase(Locale.ROOT));
                List<File> found = byName.get(fileName.toLowerCase(Locale.ROOT));
                if (inUpdate != null && inUpdate.size() == 1) {
                    item.target = inUpdate.get(0);
                    item.path = "업데이트/" + relative(updateDir, item.target);
                } else if (found != null && found.size() == 1) {
                    item.target = found.get(0);
                    item.path = relative(gameDir, item.target);
                } else {
                    item.path = fileName;
                    item.target = new File(gameDir, fileName);
                }
            }
            item.state = item.target.isFile() ? MATCH : MISSING;
            plan.items.add(item);
        }
    }

    private static final Pattern TITLE_ID = Pattern.compile("^[A-Z]{4}\\d{5}$");

    private static void analyzeFiles(Plan plan, File folder, List<String> rest, String id, File gameDir, File updateDir) {
        List<String[]> mapped = new ArrayList<>();   // {path in the patch, path in the game}
        String other = null;
        for (String rel : rest) {
            String[] segs = rel.split("/");
            int at = -1;
            for (int i = 0; i < segs.length - 1; i++) {
                String seg = segs[i].toUpperCase(Locale.ROOT);
                if (seg.equals(id)) {
                    at = i;
                } else if (other == null && TITLE_ID.matcher(seg).matches()) {
                    other = seg;
                }
            }
            if (at >= 0) {
                mapped.add(new String[]{rel, join(segs, at + 1)});
            }
        }
        if (mapped.isEmpty()) {
            if (other != null) {
                plan.otherGame = other;
                return;
            }
            // No title ID folder: the game's own paths, maybe under a folder or two
            // ("한글패치/data/…"): the depth at which most files are files of the game.
            int bestDepth = -1;
            int bestCount = 0;
            for (int depth = 0; depth <= 3; depth++) {
                int count = 0;
                for (String rel : rest) {
                    String[] segs = rel.split("/");
                    if (segs.length > depth && inGame(join(segs, depth), gameDir, updateDir)) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    bestDepth = depth;
                }
            }
            if (bestDepth < 0) {
                return;
            }
            for (String rel : rest) {
                String[] segs = rel.split("/");
                if (segs.length <= bestDepth) {
                    continue;
                }
                String path = join(segs, bestDepth);
                int slash = path.lastIndexOf('/');
                // a new file only in a folder the game has (a readme beside it stays out)
                if (inGame(path, gameDir, updateDir) || (slash > 0 && (isDir(resolve(gameDir, path.substring(0, slash)))
                        || (updateDir != null && isDir(resolve(updateDir, path.substring(0, slash))))))) {
                    mapped.add(new String[]{rel, path});
                }
            }
        }
        for (String[] m : mapped) {
            Put put = new Put();
            put.source = new File(folder, m[0]);
            String path = m[1];
            String lower = path.toLowerCase(Locale.ROOT);
            put.exec = lower.equals("eboot.bin") || lower.endsWith(".suprx") || lower.endsWith(".self") || lower.endsWith(".skprx");
            if (lower.equals("sce_sys/param.sfo") || lower.startsWith("sce_sys/package/") || lower.startsWith("sce_pfs/")
                    || lower.equals("sce_sys/clearsign") || lower.endsWith("work.bin")) {
                put.skip = "설치 정보 · 라이선스 파일이라 그대로 둬요";
            }
            File inUpdate = updateDir == null ? null : resolve(updateDir, path);
            File inApp = resolve(gameDir, path);
            File base;
            if (inUpdate != null && inUpdate.isFile()) {
                put.root = "patch";
                put.target = inUpdate;
                base = updateDir;
            } else if (inApp != null && inApp.isFile()) {
                put.root = "app";
                put.target = inApp;
                base = gameDir;
            } else {
                base = updateDir != null ? updateDir : gameDir;
                put.root = updateDir != null ? "patch" : "app";
                put.target = resolveNew(base, path);
                if (put.target.isDirectory()) {
                    put.skip = "같은 이름의 폴더가 있어요";
                }
            }
            put.exists = put.target.isFile();
            put.rel = relative(base, put.target);
            put.path = ("patch".equals(put.root) ? "업데이트/" : "") + put.rel;
            plan.puts.add(put);
        }
    }

    private static boolean inGame(String path, File gameDir, File updateDir) {
        File f = resolve(gameDir, path);
        if (f != null && f.isFile()) {
            return true;
        }
        f = updateDir == null ? null : resolve(updateDir, path);
        return f != null && f.isFile();
    }

    private static boolean isDir(File f) {
        return f != null && f.isDirectory();
    }

    private static String join(String[] segs, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < segs.length; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(segs[i]);
        }
        return sb.toString();
    }

    static String relative(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(b + "/") ? p.substring(b.length() + 1) : f.getName();
    }

    // The file or folder at rel under base, matching names without case (the game's folders
    // were written by the Vita's case-blind file system); null when it isn't there.
    static File resolve(File base, String rel) {
        File cur = base;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            File exact = new File(cur, seg);
            if (exact.exists()) {
                cur = exact;
                continue;
            }
            String[] names = cur.list();
            File found = null;
            if (names != null) {
                for (String n : names) {
                    if (n.equalsIgnoreCase(seg)) {
                        found = new File(cur, n);
                        break;
                    }
                }
            }
            if (found == null) {
                return null;
            }
            cur = found;
        }
        return cur;
    }

    // Where a new file goes: the folders that are there in their own case, the rest as given.
    private static File resolveNew(File base, String rel) {
        File cur = base;
        boolean matching = true;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            if (matching) {
                File found = resolve(cur, seg);
                if (found != null) {
                    cur = found;
                    continue;
                }
                matching = false;
            }
            cur = new File(cur, seg);
        }
        return cur;
    }

    private static void listFiles(File dir, String prefix, List<String> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 12) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            String name = f.getName();
            if (name.equals("__MACOSX") || name.equals(".DS_Store") || name.endsWith(".unpacking") || name.equalsIgnoreCase("Thumbs.db")) {
                continue;
            }
            if (f.isDirectory()) {
                listFiles(f, rel, out, depth + 1);
            } else {
                out.add(rel);
            }
        }
    }

    private static String readText(File f) throws IOException {
        byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
        int skip = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(data, skip, data.length - skip, StandardCharsets.UTF_8);
    }

    // ---- Vita3K code patch: "[bin]" headers (in patchlist.txt "[TITLEID, bin]") and
    // "<segment>:<offset> <values>" lines, applied by the engine to the game's code as it
    // loads. Written again clean, so the engine never sees a line it would trip on. ----

    private static final Pattern PATCH_LINE = Pattern.compile("^\\d{1,3}\\s*:\\s*(0[xX])?[0-9A-Fa-f]{1,8}\\s+\\S.*$");

    static String codePatch(String text, String titleId, boolean list) {
        StringBuilder out = new StringBuilder();
        boolean take = !list;
        int lines = 0;
        for (String raw : text.split("\\r?\\n|\\r")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (take) {
                    out.append(line).append('\n');
                }
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                String[] args = end > 0 ? line.substring(1, end).split(",") : new String[0];
                String bin = null;
                if (list) {
                    take = args.length >= 1 && args[0].trim().equalsIgnoreCase(titleId);
                    bin = args.length > 1 ? args[1].trim() : "eboot.bin";
                } else if (args.length >= 1) {
                    take = true;
                    bin = args[args.length > 1 ? 1 : 0].trim();
                }
                if (bin == null || bin.isEmpty() || bin.contains("[") || bin.contains("]")) {
                    take = false;
                    continue;
                }
                if (take) {
                    out.append('[').append(bin).append("]\n");
                }
                continue;
            }
            if (take && PATCH_LINE.matcher(line).matches()) {
                out.append(line.replaceFirst("^(\\d{1,3})\\s*:\\s*", "$1:")).append('\n');
                lines++;
            }
        }
        return lines > 0 ? out.toString() : null;
    }

    static int countPatchLines(String code) {
        int n = 0;
        for (String line : code.split("\n")) {
            if (PATCH_LINE.matcher(line).matches()) {
                n++;
            }
        }
        return n;
    }

    private static List<File> findIn(Map<String, List<File>> first, Map<String, List<File>> second, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        List<File> found = first.get(key);
        return found != null ? found : second.get(key);
    }

    // The game file whose name ends the patch's name after a '-', '_', '.' or space
    // ("…-ko-v0.9-data00" → "data00", or "data00.psarc" for "…-data00").
    private static String suffixMatch(String patchName, Map<String, List<File>> first, Map<String, List<File>> second) {
        String lower = patchName.toLowerCase(Locale.ROOT);
        String best = null;
        for (Map<String, List<File>> map : java.util.Arrays.asList(first, second)) {
            for (String file : map.keySet()) {
                if (map.get(file).size() != 1) continue;
                String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
                for (String candidate : new String[]{file, stem}) {
                    if (candidate.length() < 3 || !lower.endsWith(candidate)) continue;
                    int at = lower.length() - candidate.length();
                    if (at > 0 && "-_. ".indexOf(lower.charAt(at - 1)) < 0) continue;
                    if (best == null || file.length() > best.length()) best = file;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    // xdelta3 writes "target//source/" (with compression marks) in the VCDIFF application
    // header; the source's file name, or null.
    static String vcdiffSourceName(InputStream in) throws IOException {
        byte[] head = new byte[4096];
        int n = 0;
        while (n < head.length) {
            int r = in.read(head, n, head.length - n);
            if (r < 0) break;
            n += r;
        }
        if (n < 6 || (head[0] & 0xFF) != 0xD6 || (head[1] & 0xFF) != 0xC3 || (head[2] & 0xFF) != 0xC4) return null;
        int indicator = head[4] & 0xFF;
        int at = 5;
        if ((indicator & 0x01) != 0) at++;                       // secondary compressor id
        if ((indicator & 0x02) != 0) {                            // code table: skip its length
            long[] len = varint(head, at, n);
            if (len == null) return null;
            at = (int) len[1] + (int) len[0];
        }
        if ((indicator & 0x04) == 0) return null;
        long[] len = varint(head, at, n);
        if (len == null || len[0] <= 0 || len[1] + len[0] > n) return null;
        String app = new String(head, (int) len[1], (int) len[0], StandardCharsets.UTF_8);
        String[] parts = app.split("/", -1);
        String source = parts.length >= 3 ? parts[2] : null;
        if (source == null || source.isEmpty()) return null;
        source = baseName(source.replace('\\', '/'));
        return source.isEmpty() ? null : source;
    }

    // {value, next position} of a VCDIFF varint, or null.
    private static long[] varint(byte[] d, int at, int end) {
        long v = 0;
        for (int i = 0; i < 9 && at < end; i++) {
            int b = d[at++] & 0xFF;
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return new long[]{v, at};
        }
        return null;
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

    // ---- backup (0.0.349): the original of every file a patch replaces is moved to
    // <dir>/<root>/<path> and listed in <dir>/manifest.txt ("R app/data/x" replaced,
    // "N patch/data/y" new), so the patch can be taken off again without reinstalling. ----

    static final class Backup {
        final File dir;
        final Map<String, File> roots;      // "app" / "patch" / "code" → its folder
        final boolean keep;
        private final java.util.Set<String> known = new java.util.HashSet<>();

        Backup(File dir, Map<String, File> roots, boolean keep) {
            this.dir = dir;
            this.roots = roots;
            this.keep = keep;
            for (String[] line : manifest(dir)) {
                known.add(line[1]);
            }
        }

        // Before target (root/rel) is replaced or made: keeps the first original only.
        void before(String root, String rel, File target) throws IOException {
            if (!keep) {
                return;
            }
            String key = root + "/" + rel;
            if (known.contains(key)) {
                return;
            }
            dir.mkdirs();
            boolean replace = target.isFile();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "manifest.txt"), true)) {
                out.write(((replace ? "R " : "N ") + key + "\n").getBytes(StandardCharsets.UTF_8));
            }
            known.add(key);
            if (replace) {
                File saved = new File(dir, key);
                File parent = saved.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("백업 폴더를 만들지 못했어요");
                }
                if (!target.renameTo(saved)) {
                    copyFile(target, saved);
                }
            }
        }
    }

    static List<String[]> manifest(File dir) {
        List<String[]> out = new ArrayList<>();
        File f = new File(dir, "manifest.txt");
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String line : readText(f).split("\n")) {
                if (line.length() > 2 && (line.startsWith("R ") || line.startsWith("N ")) && line.indexOf('/') > 2) {
                    out.add(new String[]{line.substring(0, 1), line.substring(2).trim()});
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    // Puts every backed-up original back and takes the new files away; the backup is gone after.
    static int revert(File dir, Map<String, File> roots) throws IOException {
        List<String[]> lines = manifest(dir);
        int done = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String key = lines.get(i)[1];
            int slash = key.indexOf('/');
            File base = roots.get(key.substring(0, slash));
            String rel = key.substring(slash + 1);
            if (base == null || rel.contains("..")) {
                continue;
            }
            File target = new File(base, rel);
            if ("R".equals(lines.get(i)[0])) {
                File saved = new File(dir, key);
                if (!saved.isFile()) {
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                if (!saved.renameTo(target)) {
                    copyFile(saved, target);
                }
            } else {
                target.delete();
            }
            done++;
        }
        deleteTree(dir);
        return done;
    }

    static void deleteTree(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File c : files) {
                deleteTree(c);
            }
        }
        f.delete();
    }

    // target ← source through "<target>.kopatch" (copied whole first, then renamed over).
    static void copyFile(File source, File target) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".kopatch");
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[1 << 18];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException(target.getName() + "을(를) 바꾸지 못했어요");
        }
    }

    private static String rootOf(Plan plan, File target) {
        if (plan.updateDir != null && target.getAbsolutePath().startsWith(plan.updateDir.getAbsolutePath() + "/")) {
            return "patch";
        }
        return "app";
    }

    // Patches every MATCH file: "<file>.kopatch" beside it, checked, then renamed over it.
    // Returns the paths patched; throws with the reason on the first failure (files already
    // patched stay patched — each one is whole).
    static List<String> apply(Plan plan, Backup backup, Progress progress) throws IOException {
        List<String> patched = new ArrayList<>();
        List<Item> todo = new ArrayList<>();
        for (Item item : plan.items) {
            if (item.state == MATCH) {
                todo.add(item);
            }
        }
        for (int k = 0; k < todo.size(); k++) {
            if (progress.cancelled()) {
                break;
            }
            Item item = todo.get(k);
            final int at = k;
            final int count = todo.size();
            progress.update(at, count, item.path, "패치 적용", k * 1000 / count);
            File delta = item.patchFile;
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
                String root = rootOf(plan, item.target);
                backup.before(root, relative("patch".equals(root) ? plan.updateDir : plan.gameDir, item.target), item.target);
                if (!out.renameTo(item.target)) {
                    throw new IOException(item.path + "을(를) 바꾸지 못했어요");
                }
                patched.add(item.path);
            } finally {
                if (out.exists()) {
                    out.delete();
                }
            }
        }
        progress.update(todo.size(), todo.size(), "", "끝", 1000);
        return patched;
    }

    // Moves the patch's files in place (the unpacked folder is on the same storage, so
    // it's a rename; a copy otherwise). Returns the paths put.
    static List<String> applyPuts(Plan plan, Backup backup, boolean withExec, Progress progress) throws IOException {
        List<String> done = new ArrayList<>();
        List<Put> todo = new ArrayList<>();
        for (Put put : plan.puts) {
            if (put.skip == null && (withExec || !put.exec)) {
                todo.add(put);
            }
        }
        for (int k = 0; k < todo.size(); k++) {
            if (progress.cancelled()) {
                break;
            }
            Put put = todo.get(k);
            progress.update(k, todo.size(), put.path, "덮어쓰기", k * 1000 / todo.size());
            File parent = put.target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException(put.path + ": 폴더를 만들지 못했어요");
            }
            backup.before(put.root, put.rel, put.target);
            if (!put.source.renameTo(put.target)) {
                copyFile(put.source, put.target);
            }
            done.add(put.path);
        }
        progress.update(todo.size(), todo.size(), "", "끝", 1000);
        return done;
    }

    // The code patch as <patch folder>/<ID>.txt, where the engine looks when the game starts.
    static void applyCode(Plan plan, File codeDir, String titleId, Backup backup) throws IOException {
        if (plan.code == null) {
            return;
        }
        if (!codeDir.isDirectory() && !codeDir.mkdirs()) {
            throw new IOException("코드 패치 폴더를 만들지 못했어요");
        }
        File target = new File(codeDir, titleId + ".txt");
        backup.before("code", target.getName(), target);
        File temp = new File(codeDir, "." + titleId + ".txt.new");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(("# Mini Emulator: " + String.join(", ", plan.codeNames) + "\n" + plan.code).getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("코드 패치를 넣지 못했어요");
        }
    }
}
