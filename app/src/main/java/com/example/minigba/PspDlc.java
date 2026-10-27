package com.example.minigba;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// "DLC 가져오기" for PSP games: PPSSPP reads downloadable content from the memory stick's
// PSP/GAME/<id>/ (EDAT, PBP) and PSP/SAVEDATA/<id>/ (quest and bonus data). This finds those
// inside a picked ZIP or folder, says what it found, and copies it into the app's memory stick.
final class PspDlc {
    // One file to copy: where it came from, and where on the memory stick it goes.
    static final class Item {
        final String relative;      // "PSP/GAME/ULJM05800DLC/EBOOT.PBP"
        final String zipEntry;      // for ZIPs
        final Uri uri;              // for folders
        final long size;

        Item(String relative, String zipEntry, Uri uri, long size) {
            this.relative = relative;
            this.zipEntry = zipEntry;
            this.uri = uri;
            this.size = size;
        }
    }

    static final class Plan {
        final List<Item> items = new ArrayList<>();
        final Map<String, Integer> packages = new LinkedHashMap<>();  // "PSP/GAME/ULJM05800DLC" -> file count
        int overwrites;
        String discId = "";           // the game's id from its disc, "" when unknown
        boolean idMismatch;
        String why;                   // nothing usable found

        int fileCount() {
            return this.items.size();
        }
    }

    // ---- Looking inside what was picked ----

    static Plan planZip(ContentResolver resolver, Uri zip, File memstick, String discId) throws IOException {
        List<String[]> entries = new ArrayList<>();  // {path, size}
        try (InputStream in = resolver.openInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            if (in == null) {
                throw new IOException("파일을 열 수 없습니다");
            }
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.add(new String[]{entry.getName().replace('\\', '/'), String.valueOf(entry.getSize())});
                }
                zin.closeEntry();
            }
        }
        Plan plan = new Plan();
        plan.discId = discId;
        List<String> paths = new ArrayList<>();
        for (String[] e : entries) {
            paths.add(e[0]);
        }
        java.util.Set<String> packages = packageFolders(paths);
        for (String[] e : entries) {
            String relative = memstickPath(e[0], packages);
            if (relative != null) {
                plan.items.add(new Item(relative, e[0], null, Long.parseLong(e[1])));
            }
        }
        finish(plan, memstick);
        return plan;
    }

    static Plan planTree(ContentResolver resolver, Uri tree, File memstick, String discId) {
        List<Object[]> files = new ArrayList<>();  // {path, uri, size}
        String rootName = treeName(resolver, tree);
        walk(resolver, tree, DocumentsContract.getTreeDocumentId(tree), rootName, 0, files);
        List<String> paths = new ArrayList<>();
        for (Object[] f : files) {
            paths.add((String) f[0]);
        }
        java.util.Set<String> packages = packageFolders(paths);
        Plan plan = new Plan();
        plan.discId = discId;
        for (Object[] f : files) {
            String path = (String) f[0];
            String relative = memstickPath(path, packages);
            if (relative != null) {
                plan.items.add(new Item(relative, null, (Uri) f[1], (Long) f[2]));
            }
        }
        finish(plan, memstick);
        return plan;
    }

    private static String treeName(ContentResolver resolver, Uri tree) {
        try (Cursor cursor = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "DLC";
    }

    private static void walk(ContentResolver resolver, Uri tree, String documentId, String prefix, int depth, List<Object[]> out) {
        if (depth > 8) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> directories = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    directories.add(new String[]{id, name});
                } else {
                    out.add(new Object[]{prefix + "/" + name, DocumentsContract.buildDocumentUriUsingTree(tree, id), cursor.isNull(3) ? 0L : cursor.getLong(3)});
                }
            }
        } catch (RuntimeException ignored) {
        }
        for (String[] directory : directories) {
            walk(resolver, tree, directory[0], prefix + "/" + directory[1], depth + 1, out);
        }
    }

    // Folders that hold a PARAM.SFO or an EDAT directly and are named like a title id: a
    // loose DLC package picked without its PSP/GAME parents.
    static java.util.Set<String> packageFolders(List<String> paths) {
        java.util.Set<String> folders = new java.util.HashSet<>();
        for (String path : paths) {
            int slash = path.lastIndexOf('/');
            if (slash <= 0) {
                continue;
            }
            String name = path.substring(slash + 1).toLowerCase(Locale.ROOT);
            if (name.equals("param.sfo") || name.endsWith(".edat")) {
                String folder = path.substring(0, slash);
                String last = folder.substring(folder.lastIndexOf('/') + 1);
                if (looksLikeId(last)) {
                    folders.add(folder);
                }
            }
        }
        return folders;
    }

    // Where a file goes on the memory stick, or null when it is not DLC. Paths with a PSP/,
    // GAME/ or SAVEDATA/ folder keep their place under PSP/; a loose package goes under
    // PSP/GAME/<its folder name>/.
    static String memstickPath(String path, java.util.Set<String> packages) {
        String[] parts = path.split("/");
        for (int k = 0; k < parts.length - 1; k++) {
            String part = parts[k].toUpperCase(Locale.ROOT);
            if (part.equals("PSP") && k + 1 < parts.length - 1) {
                String next = parts[k + 1].toUpperCase(Locale.ROOT);
                if (next.equals("GAME") || next.equals("SAVEDATA")) {
                    return "PSP/" + next + "/" + join(parts, k + 2);
                }
            }
            if ((part.equals("GAME") || part.equals("SAVEDATA")) && k + 1 < parts.length - 1) {
                return "PSP/" + part + "/" + join(parts, k + 1);
            }
        }
        // The nearest package folder above this file.
        String folder = path;
        while (true) {
            int slash = folder.lastIndexOf('/');
            if (slash <= 0) {
                return null;
            }
            folder = folder.substring(0, slash);
            if (packages.contains(folder)) {
                String name = folder.substring(folder.lastIndexOf('/') + 1);
                return "PSP/GAME/" + name + "/" + path.substring(folder.length() + 1);
            }
        }
    }

    private static String join(String[] parts, int from) {
        StringBuilder text = new StringBuilder();
        for (int k = from; k < parts.length; k++) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(parts[k]);
        }
        return text.toString();
    }

    private static boolean looksLikeId(String folder) {
        return folder.matches("[A-Z]{4}[0-9]{5}.*");
    }

    private static void finish(Plan plan, File memstick) {
        if (plan.items.isEmpty()) {
            plan.why = "DLC로 보이는 파일이 없어요. PSP/GAME 이나 PSP/SAVEDATA 폴더, 또는 PARAM.SFO · EDAT 가 든 폴더가 필요해요.";
            return;
        }
        for (Item item : plan.items) {
            String[] parts = item.relative.split("/");
            String pkg = parts.length >= 3 ? parts[0] + "/" + parts[1] + "/" + parts[2] : item.relative;
            Integer count = plan.packages.get(pkg);
            plan.packages.put(pkg, count == null ? 1 : count + 1);
            if (new File(memstick, item.relative).exists()) {
                plan.overwrites++;
            }
        }
        if (!plan.discId.isEmpty()) {
            boolean match = false;
            for (String pkg : plan.packages.keySet()) {
                String folder = pkg.substring(pkg.lastIndexOf('/') + 1);
                if (folder.toUpperCase(Locale.ROOT).startsWith(plan.discId)) {
                    match = true;
                }
            }
            plan.idMismatch = !match;
        }
    }

    // ---- The game's id ----

    // The disc id (ULJM05800) from an ISO's UMD_DATA.BIN, or a PBP's PARAM.SFO; "" when unknown.
    static String discId(File rom) {
        String lower = rom.getName().toLowerCase(Locale.ROOT);
        try (RandomAccessFile file = new RandomAccessFile(rom, "r")) {
            if (lower.endsWith(".pbp")) {
                byte[] header = new byte[0x28];
                file.readFully(header);
                int sfoOffset = le32(header, 0x08);
                int iconOffset = le32(header, 0x0C);
                byte[] sfo = new byte[Math.max(0, Math.min(iconOffset - sfoOffset, 1 << 20))];
                file.seek(sfoOffset);
                file.readFully(sfo);
                return sfoString(sfo, "DISC_ID");
            }
            if (lower.endsWith(".iso")) {
                return isoDiscId(file);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return "";
    }

    private static String isoDiscId(RandomAccessFile file) throws IOException {
        // The primary volume descriptor at sector 16 names the root directory record.
        byte[] pvd = new byte[2048];
        file.seek(16L * 2048);
        file.readFully(pvd);
        if (pvd[0] != 1 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0') {
            return "";
        }
        int rootExtent = le32(pvd, 156 + 2);
        int rootSize = le32(pvd, 156 + 10);
        byte[] root = new byte[Math.min(rootSize, 1 << 16)];
        file.seek((long) rootExtent * 2048);
        file.readFully(root);
        int at = 0;
        while (at < root.length) {
            int length = root[at] & 0xFF;
            if (length == 0) {
                at = (at / 2048 + 1) * 2048;
                continue;
            }
            int nameLength = root[at + 32] & 0xFF;
            String name = new String(root, at + 33, nameLength, StandardCharsets.US_ASCII);
            if (name.startsWith("UMD_DATA.BIN")) {
                int extent = le32(root, at + 2);
                byte[] data = new byte[32];
                file.seek((long) extent * 2048);
                file.readFully(data);
                String text = new String(data, StandardCharsets.US_ASCII);
                int bar = text.indexOf('|');
                String id = (bar > 0 ? text.substring(0, bar) : text.substring(0, 10)).replace("-", "").trim();
                return id.toUpperCase(Locale.ROOT);
            }
            at += length;
        }
        return "";
    }

    private static String sfoString(byte[] sfo, String key) {
        if (sfo.length < 20 || sfo[0] != 0 || sfo[1] != 'P' || sfo[2] != 'S' || sfo[3] != 'F') {
            return "";
        }
        int keyTable = le32(sfo, 8);
        int dataTable = le32(sfo, 12);
        int count = le32(sfo, 16);
        for (int k = 0; k < count; k++) {
            int index = 20 + k * 16;
            int keyOffset = keyTable + (sfo[index] & 0xFF | (sfo[index + 1] & 0xFF) << 8);
            int length = le32(sfo, index + 4);
            int dataOffset = dataTable + le32(sfo, index + 12);
            int end = keyOffset;
            while (end < sfo.length && sfo[end] != 0) {
                end++;
            }
            String name = new String(sfo, keyOffset, end - keyOffset, StandardCharsets.US_ASCII);
            if (name.equals(key)) {
                int stop = dataOffset;
                while (stop < sfo.length && stop < dataOffset + length && sfo[stop] != 0) {
                    stop++;
                }
                return new String(sfo, dataOffset, stop - dataOffset, StandardCharsets.US_ASCII).replace("-", "").toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static int le32(byte[] data, int at) {
        return (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16 | (data[at + 3] & 0xFF) << 24;
    }

    // ---- Copying ----

    static void install(ContentResolver resolver, Uri source, Plan plan, File memstick) throws IOException {
        boolean zip = !plan.items.isEmpty() && plan.items.get(0).zipEntry != null;
        if (zip) {
            Map<String, Item> wanted = new LinkedHashMap<>();
            for (Item item : plan.items) {
                wanted.put(item.zipEntry, item);
            }
            try (InputStream in = resolver.openInputStream(source); ZipInputStream zin = new ZipInputStream(in)) {
                if (in == null) {
                    throw new IOException("파일을 열 수 없습니다");
                }
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    Item item = wanted.get(entry.getName().replace('\\', '/'));
                    if (item != null) {
                        write(zin, new File(memstick, item.relative));
                    }
                    zin.closeEntry();
                }
            }
        } else {
            for (Item item : plan.items) {
                try (InputStream in = resolver.openInputStream(item.uri)) {
                    if (in == null) {
                        throw new IOException(item.relative + " 파일을 열 수 없습니다");
                    }
                    write(in, new File(memstick, item.relative));
                }
            }
        }
    }

    private static void write(InputStream in, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("폴더를 만들 수 없습니다: " + parent.getName());
        }
        File temp = new File(target.getPath() + ".import");
        try (OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[1 << 16];
            int got;
            while ((got = in.read(buffer)) > 0) {
                out.write(buffer, 0, got);
            }
        }
        if (!temp.renameTo(target)) {
            throw new IOException(target.getName() + " 파일을 저장할 수 없습니다");
        }
    }

    // ---- What is installed for a game: folders under PSP/GAME and PSP/SAVEDATA named by its id ----

    static List<File> installed(File memstick, String discId) {
        List<File> folders = new ArrayList<>();
        if (discId.isEmpty()) {
            return folders;
        }
        for (String area : new String[]{"PSP/GAME", "PSP/SAVEDATA"}) {
            File[] all = new File(memstick, area).listFiles();
            if (all == null) {
                continue;
            }
            for (File folder : all) {
                if (folder.isDirectory() && folder.getName().toUpperCase(Locale.ROOT).startsWith(discId) && !folder.getName().equalsIgnoreCase(discId)) {
                    folders.add(folder);
                }
            }
        }
        return folders;
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    static long sizeOf(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    // Reads a file's bytes (small helper for callers that need the raw PARAM.SFO).
    static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 1 << 20)];
            int at = 0;
            while (at < data.length) {
                int got = in.read(data, at, data.length - at);
                if (got <= 0) {
                    break;
                }
                at += got;
            }
            return data;
        }
    }
}
