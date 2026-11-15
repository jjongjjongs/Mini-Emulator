package com.example.minigba;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The MS-DOS (FAT12 / FAT16) file system inside a PC-98 disk image, for 한글패치 덮어쓰기
// (0.0.318): Korean patches for PC-98 games are mostly "copy these files over the game's",
// so the files are found by name in the image and written over, in place.
//
// Images: raw floppies (.hdm .xdf .dup .2hd .tfd), .fdi and .hdi (a header, then the sectors),
// .nhd (T98-Next), and .d88 / .88d / .d98 / .98d (sectors with their own headers, in any order).
// Hard disks: the first MS-DOS partition of the PC-98 partition table. A disk that is not
// MS-DOS (many games use their own format) is reported as such.
final class Pc98Fat implements AutoCloseable {
    static final Charset SJIS = Charset.forName("MS932");

    static final class Entry {
        String path;          // "DIR/FILE.EXT"
        long entryOffset;     // the 32-byte directory entry, as a position in the volume
        int firstCluster;
        long size;
    }

    private final RandomAccessFile file;
    // The volume as one run of bytes: the file offset of each physical sector (null: flat).
    private long flatBase;
    private long[] sectorOffsets;
    private int physicalSize;
    private long volumeBase;      // byte position of the volume (partition) in the flat view

    private int bytesPerSector;
    private int sectorsPerCluster;
    private int reservedSectors;
    private int fatCount;
    private int rootEntries;
    private long sectorsPerFat;
    private long totalSectors;
    private boolean fat16;
    private long clusterCount;
    private long rootDirStart;
    private long dataStart;
    private int[] fat;            // entries of the first FAT

    private Pc98Fat(RandomAccessFile file) {
        this.file = file;
    }

    // The file system in this image, or an IOException that says why there is none.
    static Pc98Fat open(File image, boolean write) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(image, write ? "rw" : "r");
        Pc98Fat fs = new Pc98Fat(raf);
        try {
            fs.layout(image.getName().toLowerCase(Locale.ROOT));
            fs.mount();
            return fs;
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e instanceof IOException ? (IOException) e : new IOException("디스크 형식을 읽지 못했어요");
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    // ---- the image format ----

    private void layout(String name) throws IOException {
        if (name.endsWith(".d88") || name.endsWith(".88d") || name.endsWith(".d98") || name.endsWith(".98d")) {
            layoutD88();
            return;
        }
        if (name.endsWith(".fdi")) {
            flatBase = le32(readFile(8, 4), 0);
            volumeBase = 0;
            return;
        }
        if (name.endsWith(".hdi")) {
            byte[] h = readFile(0, 32);
            flatBase = le32(h, 8);
            physicalSize = (int) le32(h, 16);
            hardDisk((int) le32(h, 20), (int) le32(h, 24));
            return;
        }
        if (name.endsWith(".nhd")) {
            byte[] h = readFile(0, 0x120);
            if (!new String(h, 0, 11, SJIS).startsWith("T98HDDIMAGE")) throw new IOException("NHD 형식이 아니에요");
            flatBase = le32(h, 0x110);
            physicalSize = le16(h, 0x11C);
            hardDisk(le16(h, 0x11A), (int) le32(h, 0x118));
            return;
        }
        if (name.endsWith(".hdm") || name.endsWith(".xdf") || name.endsWith(".dup") || name.endsWith(".2hd") || name.endsWith(".tfd")) {
            flatBase = 0;
            volumeBase = 0;
            return;
        }
        throw new IOException("이 형식(" + name.substring(Math.max(0, name.lastIndexOf('.'))) + ")은 아직 안의 파일을 바꿀 수 없어요");
    }

    // A PC-98 hard disk: sector 1 holds up to 16 partitions of 32 bytes; the first MS-DOS one.
    private void hardDisk(int sectors, int heads) throws IOException {
        if (physicalSize <= 0 || sectors <= 0 || heads <= 0) throw new IOException("하드디스크 정보가 이상해요");
        byte[] table = readFlat(physicalSize, Math.min(512, physicalSize * 2));
        for (int i = 0; i + 32 <= table.length; i += 32) {
            int mid = table[i] & 0xFF;
            int sid = table[i + 1] & 0xFF;
            if (mid == 0 && sid == 0) continue;
            // MS-DOS partitions: system id 0x81 (FAT12), 0xA1 (FAT16), 0x91, 0xE1 … (low nibble 1)
            if ((sid & 0x0F) != 0x01) continue;
            int sector = table[i + 8] & 0xFF;
            int head = table[i + 9] & 0xFF;
            int cylinder = le16(table, i + 10);
            volumeBase = ((long) cylinder * heads + head) * sectors * physicalSize + (long) sector * physicalSize;
            return;
        }
        throw new IOException("하드디스크에 MS-DOS 영역이 없어요 (아직 포맷 전이거나 다른 형식)");
    }

    private void layoutD88() throws IOException {
        byte[] header = readFile(0, 0x2B0);
        int tracks = 164;
        List<long[]> found = new ArrayList<>();   // {cylinder, head, record, offset, size}
        int size = 0;
        for (int t = 0; t < tracks; t++) {
            long at = le32(header, 0x20 + t * 4);
            if (at == 0 || at >= file.length()) continue;
            byte[] first = readFile(at, 16);
            int count = le16(first, 4);
            long pos = at;
            for (int s = 0; s < count && s < 64; s++) {
                byte[] h = readFile(pos, 16);
                int length = le16(h, 0x0E);
                found.add(new long[]{h[0] & 0xFF, h[1] & 0xFF, h[2] & 0xFF, pos + 16, length});
                if (size == 0) size = length;
                pos += 16 + length;
            }
        }
        if (found.isEmpty() || size <= 0) throw new IOException("D88 디스크에 데이터가 없어요 (포맷 전)");
        int perTrack = 0;
        for (long[] f : found) perTrack = (int) Math.max(perTrack, f[2]);
        int heads = 2;
        int maxCylinder = 0;
        for (long[] f : found) maxCylinder = (int) Math.max(maxCylinder, f[0]);
        long[] offsets = new long[(maxCylinder + 1) * heads * perTrack];
        java.util.Arrays.fill(offsets, -1);
        for (long[] f : found) {
            if (f[4] != size || f[2] < 1 || f[1] >= heads) continue;
            int index = (int) ((f[0] * heads + f[1]) * perTrack + (f[2] - 1));
            if (index < offsets.length) offsets[index] = f[3];
        }
        sectorOffsets = offsets;
        physicalSize = size;
        volumeBase = 0;
    }

    private byte[] readFile(long at, int length) throws IOException {
        byte[] out = new byte[length];
        file.seek(at);
        file.readFully(out);
        return out;
    }

    // Bytes of the volume's flat view (sectors in order).
    private byte[] readFlat(long at, int length) throws IOException {
        if (sectorOffsets == null) return readFile(flatBase + at, length);
        byte[] out = new byte[length];
        int done = 0;
        while (done < length) {
            long pos = at + done;
            int sector = (int) (pos / physicalSize);
            int inside = (int) (pos % physicalSize);
            int n = Math.min(length - done, physicalSize - inside);
            if (sector >= sectorOffsets.length || sectorOffsets[sector] < 0) throw new IOException("디스크에 없는 섹터를 읽으려 했어요");
            file.seek(sectorOffsets[sector] + inside);
            file.readFully(out, done, n);
            done += n;
        }
        return out;
    }

    private void writeFlat(long at, byte[] data, int offset, int length) throws IOException {
        if (sectorOffsets == null) {
            file.seek(flatBase + at);
            file.write(data, offset, length);
            return;
        }
        int done = 0;
        while (done < length) {
            long pos = at + done;
            int sector = (int) (pos / physicalSize);
            int inside = (int) (pos % physicalSize);
            int n = Math.min(length - done, physicalSize - inside);
            if (sector >= sectorOffsets.length || sectorOffsets[sector] < 0) throw new IOException("디스크에 없는 섹터에 쓰려 했어요");
            file.seek(sectorOffsets[sector] + inside);
            file.write(data, offset + done, n);
            done += n;
        }
    }

    private byte[] readVolume(long at, int length) throws IOException {
        return readFlat(volumeBase + at, length);
    }

    private void writeVolume(long at, byte[] data, int offset, int length) throws IOException {
        writeFlat(volumeBase + at, data, offset, length);
    }

    // ---- FAT ----

    private void mount() throws IOException {
        byte[] boot = readVolume(0, 64);
        bytesPerSector = le16(boot, 0x0B);
        sectorsPerCluster = boot[0x0D] & 0xFF;
        reservedSectors = le16(boot, 0x0E);
        fatCount = boot[0x10] & 0xFF;
        rootEntries = le16(boot, 0x11);
        totalSectors = le16(boot, 0x13);
        if (totalSectors == 0) totalSectors = le32(boot, 0x20);
        sectorsPerFat = le16(boot, 0x16);
        boolean ok = (bytesPerSector == 256 || bytesPerSector == 512 || bytesPerSector == 1024 || bytesPerSector == 2048)
                && sectorsPerCluster > 0 && Integer.bitCount(sectorsPerCluster) == 1 && reservedSectors > 0
                && fatCount >= 1 && fatCount <= 2 && rootEntries > 0 && sectorsPerFat > 0 && totalSectors > 0;
        if (!ok) throw new IOException("MS-DOS 디스크가 아니에요 (게임 자체 형식이라 파일 단위로 바꿀 수 없어요)");
        long rootSectors = ((long) rootEntries * 32 + bytesPerSector - 1) / bytesPerSector;
        rootDirStart = (reservedSectors + (long) fatCount * sectorsPerFat) * bytesPerSector;
        dataStart = rootDirStart + rootSectors * bytesPerSector;
        long dataSectors = totalSectors - (reservedSectors + fatCount * sectorsPerFat + rootSectors);
        clusterCount = dataSectors / sectorsPerCluster;
        if (clusterCount <= 0) throw new IOException("MS-DOS 디스크 정보가 이상해요");
        fat16 = clusterCount >= 4085;
        byte[] raw = readVolume((long) reservedSectors * bytesPerSector, (int) (sectorsPerFat * bytesPerSector));
        fat = new int[(int) clusterCount + 2];
        for (int c = 0; c < fat.length; c++) {
            if (fat16) {
                fat[c] = 2 * c + 1 < raw.length ? le16(raw, 2 * c) : 0xFFFF;
            } else {
                int at = c * 3 / 2;
                if (at + 1 >= raw.length) {
                    fat[c] = 0xFFF;
                    continue;
                }
                int v = (raw[at] & 0xFF) | ((raw[at + 1] & 0xFF) << 8);
                fat[c] = (c & 1) == 0 ? v & 0xFFF : v >> 4;
            }
        }
    }

    private int clusterBytes() {
        return sectorsPerCluster * bytesPerSector;
    }

    private long clusterOffset(int cluster) {
        return dataStart + (long) (cluster - 2) * clusterBytes();
    }

    private boolean isEnd(int value) {
        return fat16 ? value >= 0xFFF8 : value >= 0xFF8;
    }

    private List<Integer> chain(int first) throws IOException {
        List<Integer> out = new ArrayList<>();
        int c = first;
        while (c >= 2 && c < fat.length && !isEnd(c)) {
            out.add(c);
            if (out.size() > fat.length) throw new IOException("FAT 연결이 꼬여 있어요");
            int next = fat[c];
            if (isEnd(next) || next < 2) break;
            c = next;
        }
        return out;
    }

    // Every file, with its path from the root.
    List<Entry> files() throws IOException {
        List<Entry> out = new ArrayList<>();
        walk(rootDirStart, rootEntries * 32, 0, "", out, 0);
        return out;
    }

    private void walk(long start, int length, int cluster, String prefix, List<Entry> out, int depth) throws IOException {
        if (depth > 16) return;
        List<long[]> runs = new ArrayList<>();   // {position, length}
        if (cluster == 0) {
            runs.add(new long[]{start, length});
        } else {
            for (int c : chain(cluster)) runs.add(new long[]{clusterOffset(c), clusterBytes()});
        }
        for (long[] run : runs) {
            byte[] dir = readVolume(run[0], (int) run[1]);
            for (int i = 0; i + 32 <= dir.length; i += 32) {
                int first = dir[i] & 0xFF;
                if (first == 0) return;
                if (first == 0xE5) continue;
                int attr = dir[i + 11] & 0xFF;
                if ((attr & 0x08) != 0 || attr == 0x0F) continue;   // volume label, long name
                String name = entryName(dir, i);
                if (name.equals(".") || name.equals("..")) continue;
                int firstCluster = le16(dir, i + 26);
                if ((attr & 0x10) != 0) {
                    walk(0, 0, firstCluster, prefix + name + "/", out, depth + 1);
                    continue;
                }
                Entry e = new Entry();
                e.path = prefix + name;
                e.entryOffset = run[0] + i;
                e.firstCluster = firstCluster;
                e.size = le32(dir, i + 28);
                out.add(e);
            }
        }
    }

    private static String entryName(byte[] dir, int i) {
        byte[] raw = java.util.Arrays.copyOfRange(dir, i, i + 11);
        if ((raw[0] & 0xFF) == 0x05) raw[0] = (byte) 0xE5;
        String base = new String(raw, 0, 8, SJIS).trim();
        String ext = new String(raw, 8, 3, SJIS).trim();
        return ext.isEmpty() ? base : base + "." + ext;
    }

    // Writes data over the file (its clusters reused, more taken from free space or the
    // extra given back), and its size.
    void replace(Entry e, byte[] data) throws IOException {
        int per = clusterBytes();
        int need = (data.length + per - 1) / per;
        List<Integer> have = e.firstCluster >= 2 ? chain(e.firstCluster) : new ArrayList<>();
        List<Integer> use = new ArrayList<>(have.subList(0, Math.min(need, have.size())));
        List<Integer> freed = new ArrayList<>(have.subList(use.size(), have.size()));
        for (int c : freed) fat[c] = 0;
        for (int c = 2; use.size() < need && c < fat.length; c++) {
            if (fat[c] == 0 && !use.contains(c)) use.add(c);
        }
        if (use.size() < need) {
            throw new IOException(e.path + ": 디스크에 빈 공간이 모자라요 (" + (need - have.size()) * per / 1024 + "KB 더 필요)");
        }
        for (int k = 0; k < use.size(); k++) {
            fat[use.get(k)] = k + 1 < use.size() ? use.get(k + 1) : (fat16 ? 0xFFFF : 0xFFF);
        }
        byte[] cluster = new byte[per];
        for (int k = 0; k < use.size(); k++) {
            java.util.Arrays.fill(cluster, (byte) 0);
            int n = Math.min(per, data.length - k * per);
            System.arraycopy(data, k * per, cluster, 0, n);
            writeVolume(clusterOffset(use.get(k)), cluster, 0, per);
        }
        writeFat();
        byte[] entry = readVolume(e.entryOffset, 32);
        int first = use.isEmpty() ? 0 : use.get(0);
        entry[26] = (byte) first;
        entry[27] = (byte) (first >> 8);
        entry[28] = (byte) data.length;
        entry[29] = (byte) (data.length >> 8);
        entry[30] = (byte) (data.length >> 16);
        entry[31] = (byte) (data.length >> 24);
        writeVolume(e.entryOffset, entry, 0, 32);
        e.firstCluster = first;
        e.size = data.length;
    }

    private void writeFat() throws IOException {
        byte[] raw = readVolume((long) reservedSectors * bytesPerSector, (int) (sectorsPerFat * bytesPerSector));
        for (int c = 2; c < fat.length; c++) {
            if (fat16) {
                if (2 * c + 1 >= raw.length) break;
                raw[2 * c] = (byte) fat[c];
                raw[2 * c + 1] = (byte) (fat[c] >> 8);
            } else {
                int at = c * 3 / 2;
                if (at + 1 >= raw.length) break;
                int v = (raw[at] & 0xFF) | ((raw[at + 1] & 0xFF) << 8);
                v = (c & 1) == 0 ? (v & 0xF000) | (fat[c] & 0xFFF) : (v & 0x000F) | ((fat[c] & 0xFFF) << 4);
                raw[at] = (byte) v;
                raw[at + 1] = (byte) (v >> 8);
            }
        }
        for (int k = 0; k < fatCount; k++) {
            writeVolume((reservedSectors + k * sectorsPerFat) * bytesPerSector, raw, 0, raw.length);
        }
    }

    long freeBytes() {
        long free = 0;
        for (int c = 2; c < fat.length; c++) {
            if (fat[c] == 0) free++;
        }
        return free * clusterBytes();
    }

    // ---- matching patch files to the image ----

    // The file of the image a patch file goes over: the same path from the root, else the
    // only file of that name anywhere; null when none or more than one.
    static Entry match(List<Entry> files, String patchPath) {
        String want = patchPath.replace('\\', '/').toUpperCase(Locale.ROOT);
        while (want.startsWith("/")) want = want.substring(1);
        String name = want.substring(want.lastIndexOf('/') + 1);
        Entry single = null;
        int count = 0;
        // The path, or its last parts (patches often keep a folder of their own on top).
        for (Entry e : files) {
            String p = e.path.toUpperCase(Locale.ROOT);
            if (want.equals(p) || want.endsWith("/" + p)) return e;
        }
        for (Entry e : files) {
            String p = e.path.toUpperCase(Locale.ROOT);
            if (p.equals(name) || p.endsWith("/" + name)) {
                single = e;
                count++;
            }
        }
        return count == 1 ? single : null;
    }

    static Map<String, Entry> byPath(List<Entry> files) {
        Map<String, Entry> out = new HashMap<>();
        for (Entry e : files) out.put(e.path, e);
        return out;
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }
}
