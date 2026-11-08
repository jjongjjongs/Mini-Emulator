package com.example.minigba;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 압축 풀기: finds the archives in some folders (.zip · .7z · .rar, and split ones — .7z.001 …,
// .zip.001 …, .part1.rar …, .rar + .r00 …) and unpacks one next to itself, in the folder it is in.
// zip and 7z are read here (commons-compress, xz); RAR through libarchive (RarReader).
final class ArchiveExtract {

    static final String ZIP = "zip";
    static final String SEVEN = "7z";
    static final String RAR = "rar";

    static final class Entry {
        String name;     // the path inside, with '/'
        String path;     // where it goes, relative to the folder (made safe)
        long size;       // -1 when not known
        boolean encrypted;
    }

    /** One archive: a single file, or the parts of a split one. */
    static final class Archive {
        File dir;
        String name;              // shown: the file's name, or the set's name without the part number
        String kind;
        List<File> parts = new ArrayList<>();
        boolean split;
        long packed;
        String problem;           // why it can't be unpacked (missing part, password …), or null
        List<Entry> entries;      // after inspect()
        long unpacked;
        boolean done;             // every file is already there, same size

        String partsLabel() {
            if (!split) {
                return "";
            }
            String first = parts.get(0).getName();
            String last = parts.get(parts.size() - 1).getName();
            return "분할 " + parts.size() + "조각 (" + suffix(first) + " ~ " + suffix(last) + ")";
        }

        private static String suffix(String name) {
            Matcher m = Pattern.compile("(?i)(\\.part\\d+\\.rar|\\.r\\d\\d|\\.\\d{3,}|\\.rar)$").matcher(name);
            return m.find() ? m.group(1) : name;
        }
    }

    interface Progress {
        /** False to stop. */
        boolean update(String name, long done, long total);
    }

    static final class Cancelled extends IOException {
        Cancelled() {
            super("취소했어요");
        }
    }

    private ArchiveExtract() {
    }

    // ---- Finding ----

    private static final Pattern SPLIT = Pattern.compile("(?i)^(.+\\.(7z|zip))\\.(\\d{3,})$");

    static boolean isArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") || SPLIT.matcher(name).matches()
                || lower.matches(".*\\.r\\d\\d$");
    }

    /** The archives in a folder (not below it), grouped: split parts become one. */
    static List<Archive> inFolder(File dir) {
        List<Archive> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        Set<String> names = new HashSet<>();
        for (File f : files) {
            names.add(f.getName().toLowerCase(Locale.ROOT));
        }
        Map<String, List<File>> splitSets = new LinkedHashMap<>();
        Map<String, List<File>> rarSets = new LinkedHashMap<>();
        for (File f : files) {
            if (!f.isFile() || f.getName().startsWith(".")) {
                continue;
            }
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            Matcher m = SPLIT.matcher(name);
            if (m.matches()) {
                splitSets.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT) + "\u0001" + m.group(1), k -> new ArrayList<>()).add(f);
            } else if (lower.endsWith(".rar") || lower.matches(".*\\.r\\d\\d$")) {
                Object[] part = RarReader.partOf(name);
                if (part != null) {
                    rarSets.computeIfAbsent(((String) part[0]).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(f);
                }
            } else if (lower.endsWith(".zip") || lower.endsWith(".7z")) {
                Archive a = new Archive();
                a.dir = dir;
                a.name = name;
                a.kind = lower.endsWith(".7z") ? SEVEN : ZIP;
                a.parts.add(f);
                a.packed = f.length();
                String stem = name.substring(0, name.length() - 4);
                if (a.kind.equals(ZIP) && names.contains((stem + ".z01").toLowerCase(Locale.ROOT))) {
                    a.split = true;
                    a.problem = "WinZip 분할(.z01 …) 방식이라 풀 수 없어요. 컴퓨터에서 풀어 주세요.";
                }
                out.add(a);
            }
        }
        for (Map.Entry<String, List<File>> set : splitSets.entrySet()) {
            List<File> parts = set.getValue();
            parts.sort((x, y) -> Long.compare(partNumber(x.getName()), partNumber(y.getName())));
            Archive a = new Archive();
            a.dir = dir;
            a.name = set.getKey().substring(set.getKey().indexOf('\u0001') + 1);
            a.kind = a.name.toLowerCase(Locale.ROOT).endsWith(".7z") ? SEVEN : ZIP;
            a.parts.addAll(parts);
            a.split = true;
            for (File p : parts) {
                a.packed += p.length();
            }
            List<String> missing = new ArrayList<>();
            int width = parts.get(0).getName().length() - parts.get(0).getName().lastIndexOf('.') - 1;
            long max = partNumber(parts.get(parts.size() - 1).getName());
            Set<Long> have = new HashSet<>();
            for (File p : parts) {
                have.add(partNumber(p.getName()));
            }
            for (long i = 1; i <= max && missing.size() < 4; i++) {
                if (!have.contains(i)) {
                    missing.add(a.name + "." + String.format(Locale.ROOT, "%0" + width + "d", i));
                }
            }
            if (!missing.isEmpty()) {
                a.problem = missingText(missing);
            }
            out.add(a);
        }
        for (List<File> parts : rarSets.values()) {
            List<String> partNames = new ArrayList<>();
            for (File p : parts) {
                partNames.add(p.getName());
            }
            RarReader.sortParts(partNames);
            Archive a = new Archive();
            a.dir = dir;
            a.kind = RAR;
            for (String n : partNames) {
                File p = new File(dir, n);
                a.parts.add(p);
                a.packed += p.length();
            }
            String first = partNames.get(0);
            a.split = partNames.size() > 1 || RarReader.isSplitName(first);
            Matcher m = Pattern.compile("(?i)^(.*)\\.part\\d+\\.rar$").matcher(first);
            if (m.matches()) {
                a.name = m.group(1) + ".rar";
            } else if (first.toLowerCase(Locale.ROOT).matches(".*\\.r\\d\\d$")) {
                a.name = first.substring(0, first.length() - 4) + ".rar";
            } else {
                a.name = first;
            }
            if (a.split) {
                List<String> missing = RarReader.missingParts(partNames);
                if (!missing.isEmpty()) {
                    a.problem = missingText(missing);
                }
            }
            out.add(a);
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    private static String missingText(List<String> missing) {
        String list = missing.size() > 3 ? String.join(", ", missing.subList(0, 3)) + " …" : String.join(", ", missing);
        return "조각이 빠졌어요: " + list;
    }

    private static long partNumber(String name) {
        int dot = name.lastIndexOf('.');
        try {
            return Long.parseLong(name.substring(dot + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- What is inside ----

    /** Reads the list of files; sets problem when it can't be read. Never throws. */
    static void inspect(Archive a) {
        if (a.problem != null) {
            return;
        }
        try {
            List<Entry> entries = new ArrayList<>();
            if (a.kind.equals(RAR)) {
                for (RarReader.Entry e : RarReader.entries(a.parts)) {
                    Entry x = new Entry();
                    x.name = e.name;
                    x.size = e.size;
                    x.encrypted = e.encrypted;
                    entries.add(x);
                }
            } else if (a.kind.equals(SEVEN)) {
                try (SeekableByteChannel ch = open(a.parts);
                     org.apache.commons.compress.archivers.sevenz.SevenZFile seven = new org.apache.commons.compress.archivers.sevenz.SevenZFile(ch)) {
                    for (org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e : seven.getEntries()) {
                        if (e.isDirectory() || e.isAntiItem()) {
                            continue;
                        }
                        Entry x = new Entry();
                        x.name = e.getName().replace('\\', '/');
                        x.size = e.getSize();
                        Iterable<? extends org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration> methods = e.hasStream() ? e.getContentMethods() : null;
                        if (methods != null) {
                            for (org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration c : methods) {
                                if (c.getMethod() == org.apache.commons.compress.archivers.sevenz.SevenZMethod.AES256SHA256) {
                                    x.encrypted = true;
                                }
                            }
                        }
                        entries.add(x);
                    }
                }
            } else {
                try (SeekableByteChannel ch = open(a.parts);
                     org.apache.commons.compress.archivers.zip.ZipFile zip = new org.apache.commons.compress.archivers.zip.ZipFile(ch, a.name, "UTF-8", false)) {
                    java.util.Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> all = zip.getEntriesInPhysicalOrder();
                    while (all.hasMoreElements()) {
                        org.apache.commons.compress.archivers.zip.ZipArchiveEntry e = all.nextElement();
                        if (e.isDirectory()) {
                            continue;
                        }
                        Entry x = new Entry();
                        x.name = zipName(e);
                        x.size = e.getSize();
                        x.encrypted = e.getGeneralPurposeBit().usesEncryption();
                        entries.add(x);
                    }
                }
            }
            Set<String> seen = new HashSet<>();
            long total = 0;
            boolean done = true;
            boolean encrypted = false;
            List<Entry> kept = new ArrayList<>();
            for (Entry e : entries) {
                e.path = safePath(e.name);
                if (e.path == null || !seen.add(e.path.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                encrypted |= e.encrypted;
                total += Math.max(0, e.size);
                File out = new File(a.dir, e.path);
                if (!out.isFile() || (e.size >= 0 && out.length() != e.size)) {
                    done = false;
                }
                kept.add(e);
            }
            a.entries = kept;
            a.unpacked = total;
            a.done = done && !kept.isEmpty();
            if (encrypted) {
                a.problem = "암호가 걸려 있어 풀 수 없어요.";
            } else if (kept.isEmpty()) {
                a.problem = "안에 파일이 없어요.";
            }
        } catch (org.apache.commons.compress.PasswordRequiredException e) {
            a.problem = "암호가 걸려 있어 풀 수 없어요.";
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            String why = e.getMessage() == null ? "" : e.getMessage();
            if (why.contains("암호")) {
                a.problem = "암호가 걸려 있어 풀 수 없어요.";
            } else if (a.split) {
                a.problem = "조각이 빠졌거나 손상돼서 읽을 수 없어요. 조각을 모두 같은 폴더에 넣어 주세요.";
            } else {
                a.problem = "압축 파일이 손상돼서 읽을 수 없어요.";
            }
        }
    }

    // What is inside, kept while the parts are unchanged (the home screen looks every time).
    private static final Map<String, Archive> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    static void inspectCached(Archive a) {
        if (a.problem != null) {
            return;
        }
        StringBuilder key = new StringBuilder();
        for (File p : a.parts) {
            key.append(p.getAbsolutePath()).append('|').append(p.length()).append('|').append(p.lastModified()).append('\n');
        }
        Archive known = CACHE.get(key.toString());
        if (known == null) {
            inspect(a);
            if (CACHE.size() > 500) {
                CACHE.clear();
            }
            CACHE.put(key.toString(), a);
            return;
        }
        a.entries = known.entries;
        a.unpacked = known.unpacked;
        a.problem = known.problem;
        a.done = false;
        if (a.problem == null && a.entries != null && !a.entries.isEmpty()) {
            boolean done = true;
            for (Entry e : a.entries) {
                File out = new File(a.dir, e.path);
                if (!out.isFile() || (e.size >= 0 && out.length() != e.size)) {
                    done = false;
                    break;
                }
            }
            a.done = done;
        }
    }

    // Names in a zip: UTF-8 when they read as such (marked or not — some tools mark wrongly),
    // else as written by Windows: the Korean code page, then the Japanese one.
    private static String zipName(org.apache.commons.compress.archivers.zip.ZipArchiveEntry e) {
        byte[] raw = e.getRawName();
        if (raw == null) {
            return e.getName().replace('\\', '/');
        }
        for (String charset : new String[]{"UTF-8", "MS949", "Shift_JIS"}) {
            try {
                return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString().replace('\\', '/');
            } catch (CharacterCodingException | RuntimeException ignored) {
                // The next one.
            }
        }
        return new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\\', '/');
    }

    /** A name inside to a path under the folder: no "..", no root, no characters the phone's
     *  storage refuses. Null when nothing is left. */
    static String safePath(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                continue;
            }
            String clean = part.replaceAll("[\\x00-\\x1f:*?\"<>|]", "_").trim();
            if (clean.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(clean);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static SeekableByteChannel open(List<File> parts) throws IOException {
        if (parts.size() == 1) {
            return java.nio.file.Files.newByteChannel(parts.get(0).toPath(), java.nio.file.StandardOpenOption.READ);
        }
        return org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel.forFiles(parts.toArray(new File[0]));
    }

    // ---- Unpacking ----

    /** Unpacks every file into the archive's folder; returns the files written. Files already
     *  there with the same size are left. A file cut short (cancel, error) is removed; the
     *  ones finished before it stay. */
    static List<File> extract(Archive a, Progress progress) throws IOException {
        if (a.entries == null) {
            inspect(a);
        }
        if (a.problem != null) {
            throw new IOException(a.problem);
        }
        Map<String, Entry> wanted = new LinkedHashMap<>();
        long total = 0;
        for (Entry e : a.entries) {
            File out = new File(a.dir, e.path);
            if (out.isFile() && e.size >= 0 && out.length() == e.size) {
                continue;
            }
            wanted.put(e.name, e);
            total += Math.max(0, e.size);
        }
        List<File> written = new ArrayList<>();
        if (wanted.isEmpty()) {
            return written;
        }
        if (a.kind.equals(RAR)) {
            extractRar(a, wanted, total, progress, written);
        } else {
            extractStream(a, wanted, total, progress, written);
        }
        return written;
    }

    private static File temp(File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("폴더를 만들지 못했어요: " + parent.getName());
        }
        return new File(parent, "." + out.getName() + ".unpacking");
    }

    private static void finish(File temp, File out) throws IOException {
        if (out.exists() && !out.delete()) {
            temp.delete();
            throw new IOException(out.getName() + "을(를) 바꾸지 못했어요.");
        }
        if (!temp.renameTo(out)) {
            temp.delete();
            throw new IOException(out.getName() + "을(를) 만들지 못했어요.");
        }
    }

    private static void extractStream(Archive a, Map<String, Entry> wanted, long total, Progress progress, List<File> written) throws IOException {
        byte[] buffer = new byte[1 << 18];
        long[] done = {0};
        if (a.kind.equals(SEVEN)) {
            try (SeekableByteChannel ch = open(a.parts);
                 org.apache.commons.compress.archivers.sevenz.SevenZFile seven = new org.apache.commons.compress.archivers.sevenz.SevenZFile(ch)) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e;
                int left = wanted.size();
                while (left > 0 && (e = seven.getNextEntry()) != null) {
                    Entry x = e.isDirectory() ? null : wanted.get(e.getName().replace('\\', '/'));
                    if (x == null) {
                        continue;
                    }
                    left--;
                    try (InputStream in = seven.getInputStream(e)) {
                        copy(in, a.dir, x, buffer, done, total, progress, written);
                    }
                }
            }
        } else {
            try (SeekableByteChannel ch = open(a.parts);
                 org.apache.commons.compress.archivers.zip.ZipFile zip = new org.apache.commons.compress.archivers.zip.ZipFile(ch, a.name, "UTF-8", false)) {
                java.util.Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> all = zip.getEntriesInPhysicalOrder();
                while (all.hasMoreElements()) {
                    org.apache.commons.compress.archivers.zip.ZipArchiveEntry e = all.nextElement();
                    Entry x = e.isDirectory() ? null : wanted.get(zipName(e));
                    if (x == null) {
                        continue;
                    }
                    if (!zip.canReadEntryData(e)) {
                        throw new IOException(x.name + ": 이 압축 방식은 풀 수 없어요.");
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        copy(in, a.dir, x, buffer, done, total, progress, written);
                    }
                }
            }
        }
    }

    private static void copy(InputStream in, File dir, Entry x, byte[] buffer, long[] done, long total, Progress progress, List<File> written) throws IOException {
        File out = new File(dir, x.path);
        File temp = temp(out);
        boolean ok = false;
        long reported = done[0];
        try (OutputStream os = new FileOutputStream(temp)) {
            if (!progress.update(x.path, done[0], total)) {
                throw new Cancelled();
            }
            int n;
            while ((n = in.read(buffer)) > 0) {
                os.write(buffer, 0, n);
                done[0] += n;
                if (done[0] - reported >= (4 << 20)) {
                    reported = done[0];
                    if (!progress.update(x.path, done[0], total)) {
                        throw new Cancelled();
                    }
                }
            }
            ok = true;
        } finally {
            if (!ok) {
                temp.delete();
            }
        }
        if (x.size >= 0 && temp.length() != x.size) {
            temp.delete();
            throw new IOException(x.path + "이(가) 덜 풀렸어요. 압축 파일이 손상됐을 수 있어요.");
        }
        finish(temp, out);
        written.add(out);
    }

    private static void extractRar(Archive a, Map<String, Entry> wanted, long total, Progress progress, List<File> written) throws IOException {
        List<String> names = new ArrayList<>();
        List<File> temps = new ArrayList<>();
        List<File> outs = new ArrayList<>();
        for (Entry x : wanted.values()) {
            File out = new File(a.dir, x.path);
            names.add(x.name);
            outs.add(out);
            temps.add(temp(out));
        }
        final boolean[] stopped = {false};
        IOException failure = null;
        try {
            progress.update(names.get(0), 0, total);
            RarReader.extract(a.parts, names, temps, doneBytes -> {
                if (!progress.update(null, doneBytes, total)) {
                    stopped[0] = true;
                    throw new RuntimeException("cancel");
                }
            });
        } catch (IOException e) {
            failure = e;
        }
        // The files finished before a stop or an error are kept; the one cut short is gone.
        for (int i = 0; i < temps.size(); i++) {
            Entry x = wanted.get(names.get(i));
            File temp = temps.get(i);
            if (temp.isFile() && (x.size < 0 || temp.length() == x.size)) {
                finish(temp, outs.get(i));
                written.add(outs.get(i));
            } else {
                temp.delete();
            }
        }
        if (stopped[0]) {
            throw new Cancelled();
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Removes the archive's files (every part). */
    static void deleteParts(Archive a) {
        for (File p : a.parts) {
            p.delete();
        }
    }
}
