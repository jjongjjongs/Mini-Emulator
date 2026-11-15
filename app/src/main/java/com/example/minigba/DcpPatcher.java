package com.example.minigba;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// 드림캐스트 .dcp 패치 (Universal Dreamcast Patcher's format): a zip holding files of the disc's
// file system at their paths, each one either a whole new file or "<name>.xdelta" (a patch for
// that file), and optionally bootsector/IP.BIN (a new boot sector). Applying it means rebuilding
// the disc's high-density area: the GD-ROM file system is read from the GDI's data tracks, the
// files are swapped, and the data tracks are written again the way GD-ROM builders lay them out
// (boot sector and file system tables at LBA 45000, the files packed against the end of the disc
// with the boot program last, music tracks between the two with their 150-sector gaps), with the
// IP.BIN track table following the new track positions. Low-density tracks (1, 2) are copied.
final class DcpPatcher {
    static final int GD_START = 45000;
    static final int GD_END = 549150;
    private static final int SECTOR = 2048;
    private static final int RAW = 2352;
    private static final int IP_SIZE = 0x8000;

    interface Progress {
        // stage: 1 reading the disc, 2 swapping files, 3 writing data tracks, 4 copying the
        // other tracks. False cancels.
        boolean onProgress(int stage, long done, long total, String detail);
    }

    // What the patch does to this disc, shown before applying.
    static final class Plan {
        int replaced;
        int added;
        int patched;   // xdelta
        boolean ipBin;
        long needed;   // bytes the new game takes
        final List<String> files = new ArrayList<>();
    }

    // ---- the source disc ----

    private static final class Track {
        int number;
        int lba;
        int ctrl;
        int sectorSize;
        long sectors;
        File file;
        RandomAccessFile raf;
    }

    private static final class Disc implements java.io.Closeable {
        final List<Track> tracks = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        Track trackAt(long lba) {
            for (Track track : this.tracks) {
                if (lba >= track.lba && lba < track.lba + track.sectors) {
                    return track;
                }
            }
            return null;
        }

        // The 2048 bytes of user data of a sector (zeros outside every track).
        void read(long lba, byte[] out, int at) throws IOException {
            Track track = trackAt(lba);
            if (track == null || track.ctrl != 4) {
                java.util.Arrays.fill(out, at, at + SECTOR, (byte) 0);
                return;
            }
            if (track.raf == null) {
                track.raf = new RandomAccessFile(track.file, "r");
            }
            track.raf.seek((lba - track.lba) * track.sectorSize + (track.sectorSize == RAW ? 16 : 0));
            track.raf.readFully(out, at, SECTOR);
        }

        @Override
        public void close() {
            for (Track track : this.tracks) {
                if (track.raf != null) {
                    try {
                        track.raf.close();
                    } catch (IOException ignored) {
                        // Read only.
                    }
                    track.raf = null;
                }
            }
        }
    }

    private static final java.util.regex.Pattern GDI_LINE = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\"([^\"]+)\"|(\\S+))(\\s+.*)?$");

    private static Disc readGdi(File gdi) throws IOException {
        if (gdi.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".cue")) {
            return readCue(gdi);
        }
        Disc disc = new Disc();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(gdi), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                java.util.regex.Matcher match = GDI_LINE.matcher(line);
                if (!match.matches()) {
                    continue;
                }
                Track track = new Track();
                track.number = Integer.parseInt(match.group(1));
                track.lba = Integer.parseInt(match.group(2));
                track.ctrl = Integer.parseInt(match.group(3));
                track.sectorSize = Integer.parseInt(match.group(4));
                String name = match.group(6) != null ? match.group(6) : match.group(7);
                track.file = new File(gdi.getParentFile(), new File(name).getName());
                if (!track.file.isFile()) {
                    throw new IOException("GDI에 적힌 트랙 파일(" + track.file.getName() + ")이 없어요.");
                }
                if (track.sectorSize != RAW && track.sectorSize != SECTOR) {
                    throw new IOException("지원하지 않는 트랙 형식이에요 (섹터 " + track.sectorSize + ").");
                }
                track.sectors = track.file.length() / track.sectorSize;
                disc.tracks.add(track);
                disc.lines.add(line);
            }
        }
        if (disc.tracks.size() < 3 || disc.trackAt(GD_START) == null || disc.trackAt(GD_START).ctrl != 4) {
            throw new IOException("드림캐스트 GD-ROM GDI가 아니에요 (Track 03이 LBA 45000의 데이터 트랙이어야 해요).");
        }
        return disc;
    }

    // A Redump-style GD-ROM .cue (Track 1 data, Track 2 audio, "REM HIGH-DENSITY AREA",
    // Track 3 data …) read as the same disc a .gdi would describe: the low-density area from
    // LBA 0, the high-density area from 45000, each track at the first sector of its file.
    // Audio tracks keep their pregap in the file (their sound starts that much later, as on a
    // .gdi made from them); a data track whose file starts with a pregap is refused, since its
    // sectors would not sit where the disc's file system says.
    private static Disc readCue(File cue) throws IOException {
        Disc disc = new Disc();
        List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(cue.toPath(), StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException e) {
            lines = java.nio.file.Files.readAllLines(cue.toPath(), StandardCharsets.ISO_8859_1);
        }
        java.util.regex.Pattern fileLine = java.util.regex.Pattern.compile("(?i)^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+)).*$");
        java.util.regex.Pattern trackLine = java.util.regex.Pattern.compile("(?i)^\\s*TRACK\\s+(\\d+)\\s+(\\S+).*$");
        java.util.regex.Pattern indexLine = java.util.regex.Pattern.compile("(?i)^\\s*INDEX\\s+(\\d+)\\s+(\\d+):(\\d+):(\\d+).*$");
        File current = null;
        boolean high = false;
        long lowCursor = 0;
        long highCursor = GD_START;
        Track track = null;
        for (String raw : lines) {
            String line = raw.trim();
            java.util.regex.Matcher m;
            if (line.toUpperCase(java.util.Locale.ROOT).startsWith("REM HIGH-DENSITY")) {
                high = true;
            } else if ((m = fileLine.matcher(line)).matches()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                current = new File(cue.getParentFile(), new File(name).getName());
                if (!current.isFile()) {
                    throw new IOException(".cue에 적힌 트랙 파일(" + current.getName() + ")이 없어요.");
                }
                track = null;
            } else if ((m = trackLine.matcher(line)).matches()) {
                if (current == null) {
                    throw new IOException(".cue를 읽지 못했어요 (FILE 줄이 없어요).");
                }
                if (track != null && track.file == current) {
                    throw new IOException("한 파일에 트랙이 여러 개인 .cue는 .dcp에 쓸 수 없어요. 트랙마다 파일이 따로인 덤프를 써 주세요.");
                }
                String type = m.group(2).toUpperCase(java.util.Locale.ROOT);
                track = new Track();
                track.number = Integer.parseInt(m.group(1));
                track.ctrl = type.equals("AUDIO") ? 0 : 4;
                track.sectorSize = type.endsWith("/2048") ? SECTOR : RAW;
                track.file = current;
                track.sectors = current.length() / track.sectorSize;
                long start = high ? highCursor : lowCursor;
                track.lba = (int) start;
                if (high) {
                    highCursor += track.sectors;
                } else {
                    lowCursor += track.sectors;
                }
                disc.tracks.add(track);
                disc.lines.add(line);
            } else if ((m = indexLine.matcher(line)).matches() && track != null && Integer.parseInt(m.group(1)) == 1) {
                long offset = (Long.parseLong(m.group(2)) * 60 + Long.parseLong(m.group(3))) * 75 + Long.parseLong(m.group(4));
                if (offset != 0 && track.ctrl == 4) {
                    throw new IOException("Track " + track.number + "(데이터)이 파일 처음에서 시작하지 않는 .cue라 .dcp에 쓸 수 없어요. GDI로 된 덤프를 써 주세요.");
                }
            }
        }
        if (disc.tracks.size() < 3 || disc.trackAt(GD_START) == null || disc.trackAt(GD_START).ctrl != 4) {
            throw new IOException("드림캐스트 GD-ROM .cue가 아니에요 (고밀도 영역의 첫 트랙이 LBA 45000의 데이터 트랙이어야 해요).");
        }
        return disc;
    }

    // ---- the file system ----

    private static final class Node {
        String name;        // as on the disc: "FILE.BIN;1", or a directory's name
        boolean dir;
        Node parent;
        final List<Node> children = new ArrayList<>();
        byte[] date = new byte[7];
        int flags;
        long sourceLba = -1;   // the original's data on the source disc
        long size;
        long order = Long.MAX_VALUE;
        ZipEntry entry;     // a whole new file from the patch
        File file;          // a file made by an xdelta
        ZipEntry xdelta;
        // layout
        long lba;
        int number;         // directory number for the path tables
        int dirBytes;
        byte[] dirData;

        String key() {
            String plain = this.name;
            int semi = plain.indexOf(';');
            if (semi >= 0) {
                plain = plain.substring(0, semi);
            }
            if (plain.endsWith(".")) {
                plain = plain.substring(0, plain.length() - 1);
            }
            return plain.toUpperCase(Locale.ROOT);
        }

        Node child(String key) {
            for (Node node : this.children) {
                if (node.key().equals(key)) {
                    return node;
                }
            }
            return null;
        }

        String path() {
            return this.parent == null ? "" : (this.parent.parent == null ? "" : this.parent.path() + "/") + key();
        }
    }

    private static final class Fs {
        Node root;
        byte[] pvd;
        byte[] ip = new byte[IP_SIZE];
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    private static Fs readFs(Disc disc) throws IOException {
        Fs fs = new Fs();
        for (int k = 0; k < 16; k++) {
            disc.read(GD_START + k, fs.ip, k * SECTOR);
        }
        if (!new String(fs.ip, 0, 16, StandardCharsets.ISO_8859_1).startsWith("SEGA SEGAKATANA")) {
            throw new IOException("드림캐스트 부트 정보(IP.BIN)를 찾지 못했어요. GDI가 손상됐을 수 있어요.");
        }
        fs.pvd = new byte[SECTOR];
        disc.read(GD_START + 16, fs.pvd, 0);
        if (fs.pvd[0] != 1 || !"CD001".equals(new String(fs.pvd, 1, 5, StandardCharsets.ISO_8859_1))) {
            throw new IOException("디스크의 파일 목록(ISO9660)을 읽지 못했어요.");
        }
        fs.root = new Node();
        fs.root.name = "";
        fs.root.dir = true;
        fs.root.sourceLba = le32(fs.pvd, 156 + 2);
        fs.root.size = le32(fs.pvd, 156 + 10);
        System.arraycopy(fs.pvd, 156 + 18, fs.root.date, 0, 7);
        fs.root.flags = 2;
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(fs.root);
        int dirs = 0;
        while (!queue.isEmpty()) {
            Node dir = queue.poll();
            if (++dirs > 20000 || dir.size > 64L * 1024 * 1024) {
                throw new IOException("디스크의 파일 목록이 이상해요.");
            }
            byte[] data = new byte[(int) ((dir.size + SECTOR - 1) / SECTOR * SECTOR)];
            for (int k = 0; k < data.length / SECTOR; k++) {
                disc.read(dir.sourceLba + k, data, k * SECTOR);
            }
            int at = 0;
            while (at < dir.size) {
                int length = data[at] & 0xFF;
                if (length == 0) {
                    at = (at / SECTOR + 1) * SECTOR;
                    continue;
                }
                if (at + length > data.length || length < 34) {
                    break;
                }
                int nameLength = data[at + 32] & 0xFF;
                if (!(nameLength == 1 && (data[at + 33] == 0 || data[at + 33] == 1))) {
                    Node node = new Node();
                    node.name = new String(data, at + 33, nameLength, StandardCharsets.ISO_8859_1);
                    node.flags = data[at + 25] & 0xFF;
                    node.dir = (node.flags & 2) != 0;
                    node.sourceLba = le32(data, at + 2);
                    node.order = node.sourceLba;
                    node.size = le32(data, at + 10);
                    System.arraycopy(data, at + 18, node.date, 0, 7);
                    node.parent = dir;
                    dir.children.add(node);
                    if (node.dir) {
                        queue.add(node);
                    }
                }
                at += length;
            }
        }
        return fs;
    }

    private static String bootName(byte[] ip) {
        return new String(ip, 0x60, 16, StandardCharsets.ISO_8859_1).trim().toUpperCase(Locale.ROOT);
    }

    // ---- the patch ----

    // Puts the patch's files into the tree (nothing is extracted yet).
    private static Plan merge(Fs fs, ZipFile zip, List<Node> xdeltas, byte[] dcpIp) throws IOException {
        Plan plan = new Plan();
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            if (!entry.isDirectory()) {
                entries.add(entry);
            }
        }
        // Whole files first, then the xdeltas (which patch whatever is there by then).
        List<ZipEntry> deltas = new ArrayList<>();
        long order = Long.MAX_VALUE / 2;
        for (ZipEntry entry : entries) {
            String path = entry.getName().replace('\\', '/');
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            String[] parts = path.split("/");
            if (parts.length == 2 && parts[0].equalsIgnoreCase("bootsector")) {
                if (parts[1].equalsIgnoreCase("IP.BIN")) {
                    if (entry.getSize() != IP_SIZE) {
                        throw new IOException("패치의 IP.BIN 크기가 이상해요.");
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        readFully(in, dcpIp);
                    }
                    plan.ipBin = true;
                }
                continue;
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(".xdelta")) {
                deltas.add(entry);
                continue;
            }
            Node dir = fs.root;
            for (int k = 0; k < parts.length - 1; k++) {
                String key = parts[k].toUpperCase(Locale.ROOT);
                if (key.isEmpty()) {
                    continue;
                }
                Node next = dir.child(key);
                if (next == null) {
                    next = new Node();
                    next.name = key;
                    next.dir = true;
                    next.flags = 2;
                    next.date = fs.root.date.clone();
                    next.parent = dir;
                    dir.children.add(next);
                } else if (!next.dir) {
                    throw new IOException("패치의 폴더 이름이 디스크의 파일과 겹쳐요: " + path);
                }
                dir = next;
            }
            String key = parts[parts.length - 1].toUpperCase(Locale.ROOT);
            Node node = dir.child(key);
            if (node == null) {
                node = new Node();
                node.name = (key.indexOf('.') >= 0 ? key : key + ".") + ";1";
                node.date = fs.root.date.clone();
                node.parent = dir;
                node.order = order++;
                dir.children.add(node);
                plan.added++;
            } else if (node.dir) {
                throw new IOException("패치의 파일 이름이 디스크의 폴더와 겹쳐요: " + path);
            } else {
                plan.replaced++;
            }
            node.entry = entry;
            node.file = null;
            node.size = entry.getSize();
            if (node.size < 0 || node.size > 0xFFFFFFFFL) {
                throw new IOException("패치 안의 파일 크기를 알 수 없어요: " + path);
            }
            plan.files.add(node.path());
        }
        for (ZipEntry entry : deltas) {
            String path = entry.getName().replace('\\', '/');
            path = path.substring(0, path.length() - ".xdelta".length());
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            Node node = fs.root;
            for (String part : path.split("/")) {
                if (!part.isEmpty()) {
                    node = node == null ? null : node.child(part.toUpperCase(Locale.ROOT));
                }
            }
            if (node == null || node.dir) {
                throw new IOException("패치가 바꾸는 파일이 원본 디스크에 없어요: " + path
                        + "\n\n패치가 만들어진 원본과 다른 버전(지역판)의 디스크일 수 있어요.");
            }
            node.xdelta = entry;
            xdeltas.add(node);
            plan.patched++;
            plan.files.add(node.path());
        }
        return plan;
    }

    private static void readFully(InputStream in, byte[] out) throws IOException {
        int at = 0;
        while (at < out.length) {
            int n = in.read(out, at, out.length - at);
            if (n < 0) {
                throw new IOException("패치 파일이 손상됐어요.");
            }
            at += n;
        }
    }

    // What the patch does, without changing anything.
    static Plan inspect(File gdi, File dcp) throws IOException {
        try (Disc disc = readGdi(gdi); ZipFile zip = openZip(dcp)) {
            Fs fs = readFs(disc);
            byte[] dcpIp = new byte[IP_SIZE];
            Plan plan = merge(fs, zip, new ArrayList<>(), dcpIp);
            if (plan.replaced + plan.added + plan.patched == 0 && !plan.ipBin) {
                throw new IOException(".dcp 안에 바꿀 파일이 없어요.");
            }
            byte[] ip = plan.ipBin ? dcpIp : fs.ip;
            String boot = bootName(ip);
            if (fs.root.child(boot) == null) {
                throw new IOException("부트 프로그램(" + boot + ")이 디스크에 없어요.");
            }
            long bytes = 0;
            for (Track track : disc.tracks) {
                bytes += track.file.length();
            }
            plan.needed = bytes + 64L * 1024 * 1024;
            return plan;
        }
    }

    private static ZipFile openZip(File dcp) throws IOException {
        try {
            return new ZipFile(dcp);
        } catch (IOException e) {
            throw new IOException(".dcp 파일을 열 수 없어요. 받다가 손상됐을 수 있어요.");
        }
    }

    // ---- build ----

    private static final class Extent {
        long start;
        long sectors;
        Node node;      // a file
        byte[] data;    // tables, directories, the boot sector
    }

    // Applies the patch and writes "<base>.gdi" with "<base> (Track NN).bin/.raw" into dir.
    // Returns the .gdi. Files written are removed again when it fails.
    static File apply(File gdi, File dcp, File dir, String base, File work, Progress progress) throws IOException {
        List<File> written = new ArrayList<>();
        boolean ok = false;
        work.mkdirs();
        try (Disc disc = readGdi(gdi); ZipFile zip = openZip(dcp)) {
            report(progress, 1, 0, 1, "");
            Fs fs = readFs(disc);
            List<Node> xdeltas = new ArrayList<>();
            byte[] dcpIp = new byte[IP_SIZE];
            Plan plan = merge(fs, zip, xdeltas, dcpIp);
            byte[] ip = plan.ipBin ? dcpIp : fs.ip;
            report(progress, 1, 1, 1, "");
            // xdeltas: the file as it is now, patched, into the work folder.
            int done = 0;
            for (Node node : xdeltas) {
                report(progress, 2, done, xdeltas.size(), node.path());
                File original = new File(work, "x" + done + ".orig");
                File patch = new File(work, "x" + done + ".xdelta");
                File out = new File(work, "x" + done + ".new");
                try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(original))) {
                    copyNode(disc, zip, node, stream);
                }
                try (InputStream in = zip.getInputStream(node.xdelta); OutputStream stream = new FileOutputStream(patch)) {
                    copy(in, stream);
                }
                try (RomPatcher.Big big = RomPatcher.Big.map(patch, 0, patch.length(), false)) {
                    RomPatcher.Check check = RomPatcher.check(big, original);
                    RomPatcher.apply(big, original, check, out);
                } catch (IOException e) {
                    throw new IOException("이 패치를 원본 디스크에 적용하지 못했어요. 실패한 파일: " + node.path()
                            + "\n\n대부분 패치가 만들어진 원본과 다른 버전(지역판)의 디스크라서 그래요." + (e.getMessage() == null ? "" : "\n(" + e.getMessage() + ")"));
                }
                original.delete();
                patch.delete();
                if (node.file != null) {
                    node.file.delete();
                }
                node.file = out;
                node.entry = null;
                node.size = out.length();
                done++;
            }
            report(progress, 2, xdeltas.size(), Math.max(1, xdeltas.size()), "");
            Node boot = fs.root.child(bootName(ip));
            if (boot == null || boot.dir) {
                throw new IOException("부트 프로그램(" + bootName(ip) + ")이 디스크에 없어요.");
            }
            // Low-density tracks and the music tracks of the high-density area.
            List<Track> low = new ArrayList<>();
            List<Track> cdda = new ArrayList<>();
            Track track3 = disc.trackAt(GD_START);
            for (Track track : disc.tracks) {
                if (track.lba < GD_START) {
                    low.add(track);
                } else if (track.ctrl == 0) {
                    cdda.add(track);
                }
            }
            int sectorSize = track3.sectorSize;
            // Directories (breadth first, as the path tables number them) and files.
            List<Node> dirs = new ArrayList<>();
            List<Node> files = new ArrayList<>();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(fs.root);
            while (!queue.isEmpty()) {
                Node node = queue.poll();
                Collections.sort(node.children, DcpPatcher::compareNames);
                dirs.add(node);
                node.number = dirs.size();
                for (Node child : node.children) {
                    if (child.dir) {
                        queue.add(child);
                    } else if (child != boot) {
                        files.add(child);
                    }
                }
            }
            Collections.sort(files, (a, b) -> Long.compare(a.order, b.order));
            files.add(boot);
            for (Node node : dirs) {
                node.dirBytes = directoryLength(node);
            }
            byte[] pathL = pathTable(dirs, false);
            byte[] pathM = pathTable(dirs, true);
            long pathSectors = (pathL.length + SECTOR - 1) / SECTOR;
            long at = GD_START + 18 + 2 * pathSectors;
            long pathLLba = GD_START + 18;
            long pathMLba = GD_START + 18 + pathSectors;
            for (Node node : dirs) {
                node.lba = at;
                at += node.dirBytes / SECTOR;
            }
            long headerEnd = at;
            long bootSectors = sectors(boot.size);
            long fileSectors = 0;
            for (Node node : files) {
                fileSectors += sectors(node.size);
            }
            long firstFile = GD_END - 150 - fileSectors;
            if (firstFile < headerEnd) {
                throw new IOException("패치한 파일이 GD-ROM 한 장에 들어가지 않아요.");
            }
            long cursor = firstFile;
            for (Node node : files) {
                node.lba = cursor;
                cursor += sectors(node.size);
            }
            if (boot.lba != GD_END - 150 - bootSectors) {
                throw new IOException("디스크 배치를 계산하지 못했어요.");
            }
            for (Node node : dirs) {
                node.dirData = directory(node);
            }
            // The path tables again, now with the directories' positions (same size).
            pathL = pathTable(dirs, false);
            pathM = pathTable(dirs, true);
            // Tracks of the high-density area.
            List<long[]> hd = new ArrayList<>();       // {lba, sectors, ctrl}
            List<Track> hdSource = new ArrayList<>();  // the music track copied there (or null)
            if (cdda.isEmpty()) {
                hd.add(new long[]{GD_START, GD_END - GD_START, 4});
                hdSource.add(null);
            } else {
                long end = firstFile - 150;
                long[] lbas = new long[cdda.size()];
                for (int k = cdda.size() - 1; k >= 0; k--) {
                    end -= (cdda.get(k).file.length() + RAW - 1) / RAW;
                    lbas[k] = end;
                }
                end -= 150;
                if (end < headerEnd) {
                    throw new IOException("패치한 파일과 음악 트랙이 GD-ROM 한 장에 들어가지 않아요.");
                }
                hd.add(new long[]{GD_START, end - GD_START, 4});
                hdSource.add(null);
                for (int k = 0; k < cdda.size(); k++) {
                    hd.add(new long[]{lbas[k], (cdda.get(k).file.length() + RAW - 1) / RAW, 0});
                    hdSource.add(cdda.get(k));
                }
                hd.add(new long[]{firstFile, GD_END - firstFile, 4});
                hdSource.add(null);
            }
            updateToc(ip, hd);
            byte[] pvd = volumeDescriptor(fs.pvd, fs.root, pathL.length, pathLLba, pathMLba);
            byte[] terminator = new byte[SECTOR];
            terminator[0] = (byte) 255;
            System.arraycopy("CD001".getBytes(StandardCharsets.ISO_8859_1), 0, terminator, 1, 5);
            terminator[6] = 1;
            List<Extent> extents = new ArrayList<>();
            addData(extents, GD_START, ip);
            addData(extents, GD_START + 16, pvd);
            addData(extents, GD_START + 17, terminator);
            addData(extents, pathLLba, pathL);
            addData(extents, pathMLba, pathM);
            for (Node node : dirs) {
                addData(extents, node.lba, node.dirData);
            }
            for (Node node : files) {
                if (node.size > 0) {
                    Extent extent = new Extent();
                    extent.start = node.lba;
                    extent.sectors = sectors(node.size);
                    extent.node = node;
                    extents.add(extent);
                }
            }
            Collections.sort(extents, (a, b) -> Long.compare(a.start, b.start));
            // Write.
            List<String> lines = new ArrayList<>();
            int number = 1;
            long copyTotal = 0;
            for (Track track : low) {
                copyTotal += track.file.length();
            }
            for (Track track : cdda) {
                copyTotal += track.file.length();
            }
            long dataTotal = 0;
            for (int k = 0; k < hd.size(); k++) {
                if (hd.get(k)[2] == 4) {
                    dataTotal += hd.get(k)[1];
                }
            }
            // Names and sheet lines in track order; the data tracks are written first, then
            // the other tracks copied (the order the steps are shown in).
            long copied = 0;
            long dataDone = 0;
            String[] lowNames = new String[low.size()];
            for (int k = 0; k < low.size(); k++) {
                Track track = low.get(k);
                lowNames[k] = String.format(Locale.ROOT, "%s (Track %02d).%s", base, number, extensionFor(track.ctrl, track.sectorSize));
                lines.add(number + " " + track.lba + " " + track.ctrl + " " + track.sectorSize + " \"" + lowNames[k] + "\" 0");
                number++;
            }
            String[] hdNames = new String[hd.size()];
            for (int k = 0; k < hd.size(); k++) {
                long[] t = hd.get(k);
                int ctrl = (int) t[2];
                int size = ctrl == 4 ? sectorSize : RAW;
                hdNames[k] = String.format(Locale.ROOT, "%s (Track %02d).%s", base, number, extensionFor(ctrl, size));
                lines.add(number + " " + t[0] + " " + ctrl + " " + size + " \"" + hdNames[k] + "\" 0");
                number++;
            }
            for (int k = 0; k < hd.size(); k++) {
                long[] t = hd.get(k);
                if (t[2] == 4) {
                    File out = new File(dir, hdNames[k]);
                    written.add(out);
                    dataDone = writeData(disc, zip, extents, t[0], t[1], sectorSize == RAW, out, dataDone, dataTotal, progress);
                }
            }
            for (int k = 0; k < low.size(); k++) {
                File out = new File(dir, lowNames[k]);
                written.add(out);
                copied = copyFile(low.get(k).file, out, copied, copyTotal, progress);
            }
            for (int k = 0; k < hd.size(); k++) {
                if (hd.get(k)[2] == 0) {
                    File out = new File(dir, hdNames[k]);
                    written.add(out);
                    copied = copyFile(hdSource.get(k).file, out, copied, copyTotal, progress);
                }
            }
            report(progress, 4, 1, 1, "");
            File sheet = new File(dir, base + ".gdi");
            written.add(sheet);
            try (OutputStream out = new FileOutputStream(sheet)) {
                StringBuilder text = new StringBuilder().append(lines.size()).append("\r\n");
                for (String line : lines) {
                    text.append(line).append("\r\n");
                }
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
            ok = true;
            return sheet;
        } finally {
            File[] left = work.listFiles();
            if (left != null) {
                for (File file : left) {
                    file.delete();
                }
            }
            work.delete();
            if (!ok) {
                for (File file : written) {
                    file.delete();
                }
            }
        }
    }

    private static String extensionFor(int ctrl, int sectorSize) {
        return ctrl == 0 ? "raw" : sectorSize == SECTOR ? "iso" : "bin";
    }

    private static void report(Progress progress, int stage, long done, long total, String detail) throws IOException {
        if (progress != null && !progress.onProgress(stage, done, total, detail)) {
            throw new IOException("취소했어요.");
        }
    }

    private static long sectors(long bytes) {
        return (bytes + SECTOR - 1) / SECTOR;
    }

    private static void addData(List<Extent> extents, long lba, byte[] data) {
        Extent extent = new Extent();
        extent.start = lba;
        extent.sectors = (data.length + SECTOR - 1) / SECTOR;
        extent.data = data;
        extents.add(extent);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }

    // A file's present contents (the disc's, the patch's or an xdelta's result).
    private static void copyNode(Disc disc, ZipFile zip, Node node, OutputStream out) throws IOException {
        if (node.file != null) {
            try (InputStream in = new FileInputStream(node.file)) {
                copy(in, out);
            }
            return;
        }
        if (node.entry != null) {
            try (InputStream in = zip.getInputStream(node.entry)) {
                copy(in, out);
            }
            return;
        }
        byte[] sector = new byte[SECTOR];
        long left = node.size;
        for (long lba = node.sourceLba; left > 0; lba++) {
            disc.read(lba, sector, 0);
            int n = (int) Math.min(SECTOR, left);
            out.write(sector, 0, n);
            left -= n;
        }
    }

    private static long copyFile(File from, File to, long done, long total, Progress progress) throws IOException {
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                report(progress, 4, done, Math.max(1, total), to.getName());
            }
        }
        return done;
    }

    // One data track, sector by sector from lba: the extents' data, zeros between them.
    private static long writeData(Disc disc, ZipFile zip, List<Extent> extents, long lba, long count, boolean raw, File out, long done, long total, Progress progress) throws IOException {
        byte[] sector = new byte[raw ? RAW : SECTOR];
        byte[] whole = new byte[SECTOR];
        int dataAt = raw ? 16 : 0;
        int index = 0;
        while (index < extents.size() && extents.get(index).start + extents.get(index).sectors <= lba) {
            index++;
        }
        InputStream stream = null;
        Extent open = null;
        long sourceLba = 0;
        try (OutputStream file = new BufferedOutputStream(new FileOutputStream(out), 1 << 20)) {
            for (long k = 0; k < count; k++) {
                long at = lba + k;
                while (index < extents.size() && extents.get(index).start + extents.get(index).sectors <= at) {
                    index++;
                }
                Extent extent = index < extents.size() && extents.get(index).start <= at ? extents.get(index) : null;
                java.util.Arrays.fill(sector, dataAt, dataAt + SECTOR, (byte) 0);
                if (extent != null) {
                    long offset = (at - extent.start) * SECTOR;
                    if (extent.data != null) {
                        int n = (int) Math.min(SECTOR, extent.data.length - offset);
                        if (n > 0) {
                            System.arraycopy(extent.data, (int) offset, sector, dataAt, n);
                        }
                    } else {
                        Node node = extent.node;
                        long left = node.size - offset;
                        int n = (int) Math.min(SECTOR, left);
                        if (node.file == null && node.entry == null) {
                            if (open != extent) {
                                open = extent;
                                sourceLba = node.sourceLba + (at - extent.start);
                            }
                            disc.read(sourceLba++, whole, 0);
                            System.arraycopy(whole, 0, sector, dataAt, n);
                        } else {
                            if (open != extent) {
                                if (stream != null) {
                                    stream.close();
                                }
                                stream = node.file != null ? new FileInputStream(node.file) : zip.getInputStream(node.entry);
                                open = extent;
                                long skip = offset;
                                while (skip > 0) {
                                    long s = stream.skip(skip);
                                    if (s <= 0) {
                                        throw new IOException("패치 파일을 읽지 못했어요.");
                                    }
                                    skip -= s;
                                }
                            }
                            int got = 0;
                            while (got < n) {
                                int r = stream.read(sector, dataAt + got, n - got);
                                if (r < 0) {
                                    throw new IOException("패치 파일이 손상됐어요.");
                                }
                                got += r;
                            }
                        }
                    }
                }
                if (raw) {
                    CdSector.mode1(sector, at);
                }
                file.write(sector);
                if ((k & 1023) == 0 || k + 1 == count) {
                    report(progress, 3, done + k + 1, Math.max(1, total), "");
                }
            }
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
        return done + count;
    }

    // ---- ISO9660 structures ----

    // ISO 9660 order: the name, then the extension, padded with spaces; then the version.
    private static int compareNames(Node a, Node b) {
        String[] x = splitName(a.name);
        String[] y = splitName(b.name);
        for (int k = 0; k < 3; k++) {
            int c = comparePadded(x[k], y[k]);
            if (c != 0) {
                return k == 2 ? -c : c;
            }
        }
        return 0;
    }

    private static String[] splitName(String name) {
        String version = "";
        int semi = name.indexOf(';');
        if (semi >= 0) {
            version = name.substring(semi + 1);
            name = name.substring(0, semi);
        }
        int dot = name.indexOf('.');
        return dot >= 0 ? new String[]{name.substring(0, dot), name.substring(dot + 1), version} : new String[]{name, "", version};
    }

    private static int comparePadded(String a, String b) {
        int length = Math.max(a.length(), b.length());
        for (int k = 0; k < length; k++) {
            int x = k < a.length() ? a.charAt(k) : ' ';
            int y = k < b.length() ? b.charAt(k) : ' ';
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

    private static int recordLength(int nameLength) {
        return 33 + nameLength + (nameLength % 2 == 0 ? 1 : 0);
    }

    private static int directoryLength(Node dir) {
        int at = 2 * recordLength(1);
        for (Node child : dir.children) {
            int length = recordLength(child.name.length());
            if (at % SECTOR + length > SECTOR) {
                at = (at / SECTOR + 1) * SECTOR;
            }
            at += length;
        }
        return (at + SECTOR - 1) / SECTOR * SECTOR;
    }

    private static void both16(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
        b[at + 2] = (byte) (value >> 8);
        b[at + 3] = (byte) value;
    }

    private static void both32(byte[] b, int at, long value) {
        for (int k = 0; k < 4; k++) {
            b[at + k] = (byte) (value >> (8 * k));
            b[at + 7 - k] = (byte) (value >> (8 * k));
        }
    }

    private static int record(byte[] b, int at, Node node, byte[] name) {
        int length = recordLength(name.length);
        java.util.Arrays.fill(b, at, at + length, (byte) 0);
        b[at] = (byte) length;
        both32(b, at + 2, node.size == 0 && !node.dir ? 0 : node.lba);
        both32(b, at + 10, node.dir ? node.dirBytes : node.size);
        System.arraycopy(node.date, 0, b, at + 18, 7);
        b[at + 25] = (byte) (node.dir ? (node.flags | 2) : (node.flags & ~2 & ~0x80));
        both16(b, at + 28, 1);
        b[at + 32] = (byte) name.length;
        System.arraycopy(name, 0, b, at + 33, name.length);
        return length;
    }

    private static byte[] directory(Node dir) {
        byte[] data = new byte[dir.dirBytes];
        int at = record(data, 0, dir, new byte[]{0});
        Node parent = dir.parent == null ? dir : dir.parent;
        at += record(data, at, parent, new byte[]{1});
        for (Node child : dir.children) {
            byte[] name = child.name.getBytes(StandardCharsets.ISO_8859_1);
            if (at % SECTOR + recordLength(name.length) > SECTOR) {
                at = (at / SECTOR + 1) * SECTOR;
            }
            at += record(data, at, child, name);
        }
        return data;
    }

    private static byte[] pathTable(List<Node> dirs, boolean bigEndian) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Node dir : dirs) {
            byte[] name = dir.parent == null ? new byte[]{0} : dir.name.getBytes(StandardCharsets.ISO_8859_1);
            out.write(name.length);
            out.write(0);
            long lba = dir.lba;
            int parent = dir.parent == null ? 1 : dir.parent.number;
            if (bigEndian) {
                out.write((int) (lba >> 24));
                out.write((int) (lba >> 16));
                out.write((int) (lba >> 8));
                out.write((int) lba);
                out.write(parent >> 8);
                out.write(parent);
            } else {
                out.write((int) lba);
                out.write((int) (lba >> 8));
                out.write((int) (lba >> 16));
                out.write((int) (lba >> 24));
                out.write(parent);
                out.write(parent >> 8);
            }
            out.write(name, 0, name.length);
            if (name.length % 2 == 1) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    // The source's primary volume descriptor with the new tables (other fields kept).
    private static byte[] volumeDescriptor(byte[] source, Node root, int pathBytes, long pathL, long pathM) {
        byte[] pvd = source.clone();
        both32(pvd, 80, GD_END - GD_START);
        both32(pvd, 132, pathBytes);
        for (int k = 0; k < 4; k++) {
            pvd[140 + k] = (byte) (pathL >> (8 * k));
            pvd[144 + k] = 0;
            pvd[148 + 3 - k] = (byte) (pathM >> (8 * k));
            pvd[152 + k] = 0;
        }
        record(pvd, 156, root, new byte[]{0});
        return pvd;
    }

    // IP.BIN's track table ("TOC1" at 0x100, an entry per high-density track from 0x104): each
    // entry is the track's position + 150 with its control (0x41 data, 0x01 music) on top.
    private static void updateToc(byte[] ip, List<long[]> tracks) {
        if (!"TOC1".equals(new String(ip, 0x100, 4, StandardCharsets.ISO_8859_1))) {
            return;
        }
        int old = 0;
        long previous = GD_START + 149;
        while (old < 97) {
            long entry = le32(ip, 0x104 + old * 4);
            long address = entry & 0xFFFFFF;
            long type = entry >>> 24;
            if ((type != 0x01 && type != 0x41) || address <= previous || address >= GD_END + 150) {
                break;
            }
            previous = address;
            old++;
        }
        int count = Math.min(97, Math.max(old, tracks.size()));
        for (int k = 0; k < count; k++) {
            long entry = 0xFFFFFFFFL;
            if (k < tracks.size()) {
                long[] t = tracks.get(k);
                entry = (t[0] + 150) | (long) ((t[2] << 4) | 1) << 24;
            }
            int at = 0x104 + k * 4;
            for (int b = 0; b < 4; b++) {
                ip[at + b] = (byte) (entry >> (8 * b));
            }
        }
    }

    private DcpPatcher() {
    }
}
