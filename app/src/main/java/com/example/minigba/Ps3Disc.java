package com.example.minigba;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PS3 games outside the engine (0.0.392): a disc's title id and name from its PARAM.SFO
 * (an .iso read as ISO 9660, Joliet names when the disc has them; or a game folder), a
 * decrypted .iso unpacked into a game folder (files over 4 GB come as several extents), and
 * a patch's files laid over a game folder with the originals kept aside.
 */
final class Ps3Disc {
    private static final int SECTOR = 2048;

    private Ps3Disc() {
    }

    /** A game folder: PS3_GAME/PARAM.SFO inside. */
    static boolean isGameFolder(File dir) {
        return dir != null && dir.isDirectory() && new File(dir, "PS3_GAME/PARAM.SFO").isFile();
    }

    // ---- PARAM.SFO ----

    /** key → value (strings and numbers as text). */
    static java.util.Map<String, String> parseSfo(byte[] data) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (data == null || data.length < 20 || data[0] != 0 || data[1] != 'P' || data[2] != 'S' || data[3] != 'F') {
            return map;
        }
        int keys = le32(data, 8);
        int values = le32(data, 12);
        int count = le32(data, 16);
        for (int k = 0; k < count && 20 + k * 16 + 16 <= data.length; k++) {
            int at = 20 + k * 16;
            int keyOffset = (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8;
            int format = (data[at + 2] & 0xFF) | (data[at + 3] & 0xFF) << 8;
            int length = le32(data, at + 4);
            int offset = le32(data, at + 12);
            int keyAt = keys + keyOffset;
            int end = keyAt;
            while (end < data.length && data[end] != 0) end++;
            if (keyAt >= data.length) continue;
            String key = new String(data, keyAt, end - keyAt, StandardCharsets.UTF_8);
            int valueAt = values + offset;
            if (valueAt < 0 || valueAt + length > data.length) continue;
            if (format == 0x0404 && length >= 4) {
                map.put(key, String.valueOf(le32(data, valueAt)));
            } else {
                int len = length;
                while (len > 0 && data[valueAt + len - 1] == 0) len--;
                map.put(key, new String(data, valueAt, len, StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static final java.util.Map<String, java.util.Map<String, String>> SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** The game's PARAM.SFO (empty when it cannot be read). */
    static java.util.Map<String, String> sfo(File game) {
        if (game == null || !game.exists()) {
            return new java.util.HashMap<>();
        }
        String key = game.getAbsolutePath() + ":" + game.length() + ":" + game.lastModified();
        java.util.Map<String, String> seen = SEEN.get(key);
        if (seen != null) {
            return seen;
        }
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            if (game.isDirectory()) {
                File file = new File(game, "PS3_GAME/PARAM.SFO");
                if (!file.isFile()) file = new File(game, "PARAM.SFO");
                if (file.isFile() && file.length() < (1 << 20)) {
                    map = parseSfo(java.nio.file.Files.readAllBytes(file.toPath()));
                }
            } else {
                try (Iso iso = new Iso(game)) {
                    Entry entry = iso.find("PS3_GAME/PARAM.SFO");
                    if (entry != null && entry.size < (1 << 20)) {
                        map = parseSfo(iso.read(entry));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable: no id.
        }
        SEEN.put(key, map);
        return map;
    }

    static String titleId(File game) {
        String id = sfo(game).get("TITLE_ID");
        return id == null ? "" : id.trim();
    }

    // ---- ISO 9660 ----

    static final class Entry {
        final String name;
        final boolean dir;
        long size;
        final List<long[]> extents = new ArrayList<>();  // {lba, bytes}

        Entry(String name, boolean dir) {
            this.name = name;
            this.dir = dir;
        }
    }

    static final class Iso implements AutoCloseable {
        private final RandomAccessFile raf;
        private final Entry root;
        private final boolean joliet;

        Iso(File file) throws IOException {
            this.raf = new RandomAccessFile(file, "r");
            Entry primary = null;
            Entry jolietRoot = null;
            for (int sector = 16; sector < 32; sector++) {
                byte[] d = sector(sector);
                if (d == null || d[1] != 'C' || d[2] != 'D' || d[3] != '0' || d[4] != '0' || d[5] != '1') break;
                int type = d[0] & 0xFF;
                if (type == 255) break;
                if (type == 1 && primary == null) {
                    primary = record(d, 156, false);
                } else if (type == 2 && d[88] == '%' && d[89] == '/' && (d[90] == '@' || d[90] == 'C' || d[90] == 'E')) {
                    jolietRoot = record(d, 156, true);
                }
            }
            if (primary == null && jolietRoot == null) {
                this.raf.close();
                throw new IOException("ISO 9660 디스크가 아니에요");
            }
            this.joliet = jolietRoot != null;
            this.root = jolietRoot != null ? jolietRoot : primary;
        }

        @Override
        public void close() throws IOException {
            this.raf.close();
        }

        private byte[] sector(long lba) throws IOException {
            long at = lba * SECTOR;
            if (at + SECTOR > this.raf.length()) return null;
            byte[] d = new byte[SECTOR];
            this.raf.seek(at);
            this.raf.readFully(d);
            return d;
        }

        private static long le32u(byte[] b, int at) {
            return le32(b, at) & 0xFFFFFFFFL;
        }

        private Entry record(byte[] d, int at, boolean ucs2) {
            int nameLen = d[at + 32] & 0xFF;
            boolean dir = (d[at + 25] & 0x02) != 0;
            String name;
            if (nameLen == 1 && (d[at + 33] == 0 || d[at + 33] == 1)) {
                name = d[at + 33] == 0 ? "." : "..";
            } else if (ucs2) {
                name = new String(d, at + 33, nameLen & ~1, StandardCharsets.UTF_16BE);
            } else {
                name = new String(d, at + 33, nameLen, StandardCharsets.ISO_8859_1);
            }
            int semi = name.indexOf(';');
            if (semi >= 0) name = name.substring(0, semi);
            if (!dir && name.endsWith(".")) name = name.substring(0, name.length() - 1);
            Entry entry = new Entry(name, dir);
            long bytes = le32u(d, at + 10);
            entry.extents.add(new long[]{le32u(d, at + 2), bytes});
            entry.size = bytes;
            return entry;
        }

        List<Entry> list(Entry dir) throws IOException {
            List<Entry> out = new ArrayList<>();
            long lba = dir.extents.get(0)[0];
            long left = dir.extents.get(0)[1];
            Entry pending = null;
            while (left > 0) {
                byte[] d = sector(lba);
                if (d == null) break;
                int at = 0;
                while (at < SECTOR) {
                    int len = d[at] & 0xFF;
                    if (len == 0) break;
                    if (at + 33 > SECTOR) break;
                    Entry entry = record(d, at, this.joliet);
                    boolean more = (d[at + 25] & 0x80) != 0;
                    if (!".".equals(entry.name) && !"..".equals(entry.name)) {
                        if (pending != null && pending.name.equals(entry.name)) {
                            // The next part of a file over 4 GB.
                            pending.extents.add(entry.extents.get(0));
                            pending.size += entry.size;
                        } else {
                            pending = entry;
                            out.add(entry);
                        }
                        if (!more) pending = null;
                    }
                    at += len;
                }
                lba++;
                left -= SECTOR;
            }
            return out;
        }

        /** By a path like "PS3_GAME/PARAM.SFO" (any letter case). */
        Entry find(String path) throws IOException {
            Entry at = this.root;
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                Entry next = null;
                for (Entry e : list(at)) {
                    if (e.name.equalsIgnoreCase(part)) {
                        next = e;
                        break;
                    }
                }
                if (next == null) return null;
                at = next;
            }
            return at;
        }

        byte[] read(Entry entry) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) entry.size);
            copy(entry, out, null, null);
            return out.toByteArray();
        }

        void copy(Entry entry, OutputStream out, Progress progress, long[] done) throws IOException {
            byte[] buffer = new byte[1 << 20];
            for (long[] extent : entry.extents) {
                long left = extent[1];
                this.raf.seek(extent[0] * SECTOR);
                while (left > 0) {
                    int n = this.raf.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) throw new IOException("ISO 파일이 중간에 끝났어요");
                    out.write(buffer, 0, n);
                    left -= n;
                    if (done != null) {
                        done[0] += n;
                        if (progress != null && !progress.at(done[0])) throw new IOException("취소했어요");
                    }
                }
            }
        }

        Entry root() {
            return this.root;
        }
    }

    interface Progress {
        /** Bytes so far; false stops. */
        boolean at(long done);
    }

    /** The bytes the files of the disc take (for the space check and the progress bar). */
    static long totalBytes(Iso iso, Entry dir) throws IOException {
        long sum = 0;
        for (Entry e : iso.list(dir)) {
            sum += e.dir ? totalBytes(iso, e) : e.size;
        }
        return sum;
    }

    /**
     * Whether the disc's program can be read: on a disc image still encrypted with its disc
     * key, the files are scrambled and EBOOT.BIN does not start with "SCE".
     */
    static boolean decrypted(Iso iso) throws IOException {
        Entry eboot = iso.find("PS3_GAME/USRDIR/EBOOT.BIN");
        if (eboot == null || eboot.extents.isEmpty()) return false;
        byte[] head = new byte[4];
        iso.raf.seek(eboot.extents.get(0)[0] * SECTOR);
        iso.raf.readFully(head);
        return head[0] == 'S' && head[1] == 'C' && head[2] == 'E' && head[3] == 0;
    }

    /** Unpacks every file of the disc under dest. */
    static void extract(Iso iso, Entry dir, File dest, Progress progress, long[] done) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("폴더를 만들 수 없어요: " + dest.getName());
        for (Entry e : iso.list(dir)) {
            String safe = e.name.replace('/', '_').replace('\\', '_');
            if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) continue;
            File target = new File(dest, safe);
            if (e.dir) {
                extract(iso, e, target, progress, done);
            } else {
                try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(target), 1 << 20)) {
                    iso.copy(e, out, progress, done);
                }
            }
        }
    }

    // ---- 한글패치 덮어쓰기 ----

    /** The folder the patch's originals are kept in, inside the game folder. */
    static File backupDir(File game) {
        return new File(game, ".mini_original");
    }

    /**
     * Where a patch file goes in the game folder: a path through PS3_GAME, USRDIR or the
     * game folder's own top names keeps its place; a bare name goes where the one file of
     * that name already is; anything else under PS3_GAME/USRDIR. Null for nothing to do.
     */
    static String targetFor(File game, String entry, java.util.Map<String, List<String>> byName) {
        String path = entry.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.endsWith("/")) return null;
        String upper = path.toUpperCase(Locale.ROOT);
        int at = upper.indexOf("PS3_GAME/");
        if (at >= 0) return path.substring(at);
        at = upper.indexOf("USRDIR/");
        if (at >= 0) return "PS3_GAME/" + path.substring(at);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty() || name.startsWith(".") || name.toLowerCase(Locale.ROOT).matches(".*\\.(txt|nfo|url|md|jpg|png|exe|bat)$") && !byName.containsKey(name.toLowerCase(Locale.ROOT))) {
            return null;  // the patch's own readme, pictures or Windows tools
        }
        // The longest tail of the patch's path that some game file ends with.
        List<String> same = byName.get(name.toLowerCase(Locale.ROOT));
        if (same != null && !same.isEmpty()) {
            String best = null;
            int bestScore = -1;
            String[] parts = path.toLowerCase(Locale.ROOT).split("/");
            for (String candidate : same) {
                String[] have = candidate.toLowerCase(Locale.ROOT).split("/");
                int score = 0;
                while (score < parts.length && score < have.length && parts[parts.length - 1 - score].equals(have[have.length - 1 - score])) score++;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null && (same.size() == 1 || bestScore >= 2)) return best;
            if (best != null && bestScore >= 1 && same.size() > 1) return null;  // ambiguous
        }
        // A new file: under USRDIR, at the deepest of its folders the game already has (the
        // patch zip's own top folder, like "한글패치/", is not part of the game).
        String[] parts = path.split("/");
        for (int k = 0; k < parts.length - 1; k++) {
            StringBuilder dir = new StringBuilder();
            for (int j = k; j < parts.length - 1; j++) dir.append(j == k ? "" : "/").append(parts[j]);
            if (new File(game, "PS3_GAME/USRDIR/" + dir).isDirectory() || new File(game, "USRDIR/" + dir).isDirectory()) {
                return "PS3_GAME/USRDIR/" + dir + "/" + name;
            }
        }
        return "PS3_GAME/USRDIR/" + name;
    }

    /** Every file of the game folder by its lower-case name (paths relative to the folder). */
    static java.util.Map<String, List<String>> index(File game) {
        java.util.Map<String, List<String>> map = new java.util.HashMap<>();
        index(game, "", map);
        return map;
    }

    private static void index(File dir, String prefix, java.util.Map<String, List<String>> map) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (prefix.isEmpty() && f.getName().equals(".mini_original")) continue;
            String rel = prefix + f.getName();
            if (f.isDirectory()) {
                index(f, rel + "/", map);
            } else {
                map.computeIfAbsent(f.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rel);
            }
        }
    }

    /** Writes one patch file into the game folder, keeping the original the first time. */
    static void put(File game, String target, InputStream in) throws IOException {
        File dest = new File(game, target);
        String canonicalGame = game.getCanonicalPath() + File.separator;
        if (!dest.getCanonicalPath().startsWith(canonicalGame)) throw new IOException("잘못된 경로: " + target);
        File backup = new File(backupDir(game), target);
        File added = new File(backupDir(game), target + ".added");
        if (dest.isFile() && !backup.exists() && !added.exists()) {
            backup.getParentFile().mkdirs();
            if (!dest.renameTo(backup)) throw new IOException("원본을 옮기지 못했어요: " + target);
        } else if (!dest.exists() && !backup.exists() && !added.exists()) {
            // A new file: noted, so 원본으로 되돌리기 takes it away again.
            added.getParentFile().mkdirs();
            added.createNewFile();
        }
        dest.getParentFile().mkdirs();
        try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dest), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    static boolean hasBackup(File game) {
        File dir = backupDir(game);
        String[] names = dir.list();
        return names != null && names.length > 0;
    }

    /** Puts the originals back and removes what the patch added. */
    static int restore(File game) throws IOException {
        File dir = backupDir(game);
        int[] count = {0};
        restore(dir, game, count);
        deleteTree(dir);
        return count[0];
    }

    private static void restore(File from, File to, int[] count) throws IOException {
        File[] files = from.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                restore(f, new File(to, f.getName()), count);
            } else if (f.getName().endsWith(".added")) {
                File added = new File(to, f.getName().substring(0, f.getName().length() - 6));
                if (added.isFile() && added.delete()) count[0]++;
            } else {
                File dest = new File(to, f.getName());
                if (dest.exists() && !dest.delete()) throw new IOException("되돌리지 못했어요: " + f.getName());
                dest.getParentFile().mkdirs();
                if (!f.renameTo(dest)) throw new IOException("되돌리지 못했어요: " + f.getName());
                count[0]++;
            }
        }
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory() && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (File f : files) deleteTree(f);
                }
            }
        } catch (RuntimeException ignored) {
            // Delete what can be.
        }
        file.delete();
    }

    // ---- 세이브 (PS3 save data folders <title id>…) ----

    static File saveDir(android.content.Context context) {
        return new File(Ps3Activity.rootDir(context), "config/dev_hdd0/home/00000001/savedata");
    }

    /** The save folders of a game: those whose names start with its title id. */
    static List<File> saves(android.content.Context context, String titleId) {
        List<File> out = new ArrayList<>();
        if (titleId == null || titleId.isEmpty()) return out;
        File[] dirs = saveDir(context).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().toUpperCase(Locale.ROOT).startsWith(titleId.toUpperCase(Locale.ROOT))) out.add(d);
        }
        return out;
    }

    static void zipSaves(List<File> dirs, OutputStream sink) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(sink)) {
            for (File d : dirs) zipTree(zip, d, d.getName() + "/");
        }
    }

    private static void zipTree(java.util.zip.ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipTree(zip, f, prefix + f.getName() + "/");
            } else {
                zip.putNextEntry(new java.util.zip.ZipEntry(prefix + f.getName()));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * A save zip back into savedata: each top folder must start with the title id (or the
     * zip is one save folder's files, which go into the game's first save folder). The save
     * folders replaced are moved to savedata_backup first. The folders written, or -1.
     */
    static int unzipSaves(android.content.Context context, String titleId, InputStream source) throws IOException {
        File work = new File(context.getCacheDir(), "ps3save_" + System.currentTimeMillis());
        deleteTree(work);
        work.mkdirs();
        try {
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(source)) {
                java.util.zip.ZipEntry entry;
                String canonical = work.getCanonicalPath() + File.separator;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    File out = new File(work, entry.getName());
                    if (!out.getCanonicalPath().startsWith(canonical)) continue;
                    out.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(out)) {
                        byte[] buffer = new byte[1 << 16];
                        int n;
                        while ((n = zip.read(buffer)) > 0) o.write(buffer, 0, n);
                    }
                }
            }
            File[] tops = work.listFiles();
            List<File> folders = new ArrayList<>();
            if (tops != null) {
                for (File t : tops) {
                    if (t.isDirectory() && t.getName().toUpperCase(Locale.ROOT).startsWith(titleId.toUpperCase(Locale.ROOT))) folders.add(t);
                }
            }
            if (folders.isEmpty()) {
                return -1;
            }
            File saves = saveDir(context);
            saves.mkdirs();
            File backup = new File(saves.getParentFile(), "savedata_backup/" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()));
            for (File f : folders) {
                File dest = new File(saves, f.getName());
                if (dest.exists()) {
                    backup.mkdirs();
                    if (!dest.renameTo(new File(backup, f.getName()))) throw new IOException("지금 세이브를 백업하지 못했어요");
                }
                if (!f.renameTo(dest)) throw new IOException("세이브를 넣지 못했어요");
            }
            return folders.size();
        } finally {
            deleteTree(work);
        }
    }

    static long treeBytes(File file) {
        if (file.isFile()) return file.length();
        long sum = 0;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) sum += treeBytes(f);
        }
        return sum;
    }
}
