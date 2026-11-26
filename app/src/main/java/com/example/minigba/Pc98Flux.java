package com.example.minigba;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// .hfe (HxC Floppy Emulator) and .scp (SuperCard Pro) floppy images to the sector images
// NP2kai reads (.d88, or .hdm when every track is the plain 2HD shape), 0.0.320.
//
// Both keep the disk's magnetic signal rather than its sectors: HFE the MFM bit cells of each
// track, SCP the time between flux changes over several revolutions. The sectors are found
// the way a floppy controller does (A1 A1 A1 sync marks, ID and data fields, CRC-CCITT);
// for SCP the flux times go through a simple PLL into bit cells first, and of the
// revolutions the first sector read with a good CRC wins. MFM only (PC-98 2HD / 2DD); FM
// tracks come out as unreadable.
final class Pc98Flux {
    static final int TRACK_OK = 1;
    static final int TRACK_ODD = 2;     // read, but not the disk's usual shape (copy protection?)
    static final int TRACK_ERROR = 3;   // a sector with a bad CRC, or no sectors
    static final int TRACK_EMPTY = 4;   // nothing on it (unused tracks past the end)

    interface Progress {
        void track(int index, int count, int state);
    }

    static final class Sector {
        int c, h, r, n;
        boolean deleted;
        boolean crcError;
        byte[] data;
    }

    static final class Info {
        String format;        // "HFE v1 · MFM", "SCP · 5회전"
        int cylinders;
        int heads;
        boolean highDensity;
        int rpm;
    }

    static final class Result {
        Info info;
        List<List<Sector>> tracks = new ArrayList<>();   // index cylinder * 2 + head
        int[] states;
        int ok, odd, errors, badSectors;
        int sectorSize;       // the usual sector size, and count per track
        int sectorsPerTrack;
        List<String> notes = new ArrayList<>();

        boolean plainHdm() {
            if (sectorSize != 1024 || sectorsPerTrack != 8) return false;
            int used = 0;
            for (List<Sector> t : tracks) {
                if (!t.isEmpty()) used++;
            }
            if (used != 154) return false;
            for (int i = 0; i < 154; i++) {
                List<Sector> t = tracks.get(i);
                if (t.size() != 8) return false;
                boolean[] seen = new boolean[9];
                for (Sector s : t) {
                    if (s.n != 3 || s.r < 1 || s.r > 8 || seen[s.r] || s.data == null || s.data.length != 1024) return false;
                    seen[s.r] = true;
                }
            }
            return true;
        }
    }

    private Pc98Flux() {
    }

    static boolean isFluxImage(String lowerName) {
        return lowerName.endsWith(".hfe") || lowerName.endsWith(".scp");
    }

    // ---- reading ----

    static Info probe(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[32];
            in.readFully(h);
            String magic = new String(h, 0, 8, StandardCharsets.ISO_8859_1);
            Info info = new Info();
            if (magic.equals("HXCPICFE")) {
                info.cylinders = h[9] & 0xFF;
                info.heads = h[10] & 0xFF;
                int encoding = h[11] & 0xFF;
                int rate = le16(h, 12);
                info.rpm = le16(h, 14);
                info.highDensity = rate >= 400;
                info.format = "HFE v1 · " + (encoding == 0 ? "MFM" : encoding == 2 ? "FM" : "인코딩 " + encoding) + " · " + rate + "kbps";
                return info;
            }
            if (magic.equals("HXCHFEV3")) {
                throw new IOException("HFE v3 형식은 아직 못 읽어요 (HxC 프로그램에서 HFE v1으로 저장하면 돼요)");
            }
            if (magic.startsWith("SCP")) {
                int revolutions = h[5] & 0xFF;
                int end = h[7] & 0xFF;
                info.cylinders = end / 2 + 1;
                info.heads = (h[10] & 0xFF) == 0 ? 2 : 1;
                info.format = "SCP · " + revolutions + "회전";
                info.rpm = 0;
                info.highDensity = true;   // decided from the flux when reading
                return info;
            }
            throw new IOException(".hfe · .scp 형식이 아니에요");
        }
    }

    static Result read(File file, Progress progress) throws IOException {
        Info info = probe(file);
        Result result = new Result();
        result.info = info;
        if (info.format.startsWith("HFE")) {
            readHfe(file, result, progress);
        } else {
            readScp(file, result, progress);
        }
        summarize(result);
        return result;
    }

    private static void readHfe(File file, Result result, Progress progress) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[512];
            in.readFully(h);
            int tracks = h[9] & 0xFF;
            int heads = Math.max(1, Math.min(2, h[10] & 0xFF));
            int listAt = le16(h, 18) * 512;
            byte[] list = new byte[tracks * 4];
            in.seek(listAt);
            in.readFully(list);
            int count = tracks * 2;
            result.states = new int[count];
            for (int i = 0; i < count; i++) result.tracks.add(new ArrayList<>());
            for (int t = 0; t < tracks; t++) {
                int offset = le16(list, t * 4) * 512;
                int length = le16(list, t * 4 + 2);
                byte[] raw = new byte[(length + 511) / 512 * 512];
                in.seek(offset);
                in.readFully(raw, 0, (int) Math.min(raw.length, in.length() - offset));
                for (int side = 0; side < heads; side++) {
                    // 512-byte blocks: 256 bytes of side 0, then 256 of side 1; bits LSB first.
                    int perSide = length / 2;
                    boolean[] cells = new boolean[perSide * 8];
                    for (int k = 0; k < perSide; k++) {
                        int b = raw[(k / 256) * 512 + side * 256 + (k % 256)] & 0xFF;
                        for (int bit = 0; bit < 8; bit++) cells[k * 8 + bit] = ((b >> bit) & 1) != 0;
                    }
                    int index = t * 2 + side;
                    List<Sector> sectors = new ArrayList<>();
                    // The track twice over, so a sector across the index hole is read whole.
                    merge(sectors, decodeMfm(concat(cells, cells)), true);
                    result.tracks.set(index, sectors);
                    result.states[index] = trackState(sectors);
                    if (progress != null) progress.track(index, count, result.states[index]);
                }
            }
            result.info.cylinders = tracks;
            result.info.heads = heads;
        }
    }

    private static void readScp(File file, Result result, Progress progress) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[16];
            in.readFully(h);
            int revolutions = Math.max(1, h[5] & 0xFF);
            int start = h[6] & 0xFF;
            int end = h[7] & 0xFF;
            int cellWidth = h[9] & 0xFF;
            double tick = 25e-9 * ((h[11] & 0xFF) + 1);
            if (cellWidth != 0 && cellWidth != 16) throw new IOException("이 SCP 비트 폭(" + cellWidth + ")은 아직 못 읽어요");
            byte[] table = new byte[168 * 4];
            in.seek(16);
            in.readFully(table);
            int count = Math.min(168, end + 1);
            result.states = new int[count];
            for (int i = 0; i < count; i++) result.tracks.add(new ArrayList<>());
            boolean decidedDensity = false;
            for (int t = start; t < count; t++) {
                long at = le32(table, t * 4);
                if (at == 0) {
                    result.states[t] = TRACK_EMPTY;
                    if (progress != null) progress.track(t, count, TRACK_EMPTY);
                    continue;
                }
                byte[] th = new byte[4 + revolutions * 12];
                in.seek(at);
                in.readFully(th);
                if (th[0] != 'T' || th[1] != 'R' || th[2] != 'K') throw new IOException("SCP 트랙 " + t + "이 이상해요");
                List<Sector> sectors = new ArrayList<>();
                List<double[]> revs = new ArrayList<>();
                for (int r = 0; r < revolutions; r++) {
                    long cells = le32(th, 4 + r * 12 + 4);
                    long offset = le32(th, 4 + r * 12 + 8);
                    if (cells <= 0 || cells > 4_000_000) continue;
                    byte[] raw = new byte[(int) cells * 2];
                    in.seek(at + offset);
                    in.readFully(raw);
                    double[] times = new double[(int) cells];
                    int n = 0;
                    long carry = 0;
                    for (int k = 0; k < cells; k++) {
                        int v = ((raw[k * 2] & 0xFF) << 8) | (raw[k * 2 + 1] & 0xFF);
                        if (v == 0) {
                            carry += 65536;
                            continue;
                        }
                        times[n++] = (carry + v) * tick;
                        carry = 0;
                    }
                    revs.add(java.util.Arrays.copyOf(times, n));
                }
                if (!decidedDensity && !revs.isEmpty() && revs.get(0).length > 1000) {
                    double cell = shortestPeak(revs.get(0)) / 2;
                    result.info.highDensity = cell < 1.5e-6;
                    decidedDensity = true;
                }
                double nominal = result.info.highDensity ? 1e-6 : 2e-6;
                for (int r = 0; r < revs.size(); r++) {
                    // This revolution and the next, so a sector across the index is read whole.
                    double[] a = revs.get(r);
                    double[] b = r + 1 < revs.size() ? revs.get(r + 1) : new double[0];
                    double[] both = new double[a.length + b.length];
                    System.arraycopy(a, 0, both, 0, a.length);
                    System.arraycopy(b, 0, both, a.length, b.length);
                    merge(sectors, decodeMfm(fluxToCells(both, nominal)), r == 0);
                }
                result.tracks.set(t, sectors);
                result.states[t] = sectors.isEmpty() && revs.isEmpty() ? TRACK_EMPTY : trackState(sectors);
                if (progress != null) progress.track(t, count, result.states[t]);
            }
            result.info.cylinders = (count + 1) / 2;
            result.info.format = result.info.format + " · " + (result.info.highDensity ? "고밀도(2HD)" : "배밀도(2DD/2D)");
        }
    }

    // The flux interval that comes most often among the short ones (2 cells in MFM).
    private static double shortestPeak(double[] times) {
        int[] bins = new int[400];   // 25 ns bins up to 10 µs
        for (double t : times) {
            int b = (int) (t / 25e-9);
            if (b >= 0 && b < bins.length) bins[b]++;
        }
        int best = 0;
        for (int b = 20; b < bins.length; b++) {
            if (bins[b] > bins[best]) best = b;
        }
        // The first big peak (2 cells) rather than the largest overall.
        for (int b = 20; b < best; b++) {
            if (bins[b] > bins[best] / 3 && bins[b] >= bins[Math.max(0, b - 1)] && bins[b] >= bins[b + 1]) return b * 25e-9;
        }
        return best * 25e-9;
    }

    // Flux times to MFM bit cells with a simple PLL around the nominal cell time.
    private static boolean[] fluxToCells(double[] times, double nominal) {
        boolean[] out = new boolean[times.length * 5];
        int n = 0;
        double cell = nominal;
        for (double t : times) {
            int cells = (int) Math.round(t / cell);
            if (cells < 1) cells = 1;
            if (cells > 8) cells = 8;
            for (int k = 1; k < cells && n < out.length; k++) out[n++] = false;
            if (n < out.length) out[n++] = true;
            double seen = t / cells;
            cell += (seen - cell) * 0.05;
            if (cell < nominal * 0.85) cell = nominal * 0.85;
            if (cell > nominal * 1.15) cell = nominal * 1.15;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static boolean[] concat(boolean[] a, boolean[] b) {
        boolean[] out = new boolean[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- MFM ----

    private static List<Sector> decodeMfm(boolean[] cells) {
        List<Sector> out = new ArrayList<>();
        int shift = 0;
        Sector pending = null;
        for (int i = 0; i < cells.length; i++) {
            shift = ((shift << 1) | (cells[i] ? 1 : 0)) & 0xFFFF;
            if (shift != 0x4489) continue;
            // Three sync words in a row, then the mark.
            int at = i + 1;
            int syncs = 1;
            while (syncs < 3 && at + 16 <= cells.length && word(cells, at) == 0x4489) {
                at += 16;
                syncs++;
            }
            if (syncs < 3) continue;
            int mark = readByte(cells, at);
            if (mark < 0) break;
            at += 16;
            if (mark == 0xFE) {
                int[] id = new int[6];
                for (int k = 0; k < 6; k++) {
                    id[k] = readByte(cells, at + k * 16);
                    if (id[k] < 0) return out;
                }
                int crc = crc(new int[]{0xA1, 0xA1, 0xA1, 0xFE, id[0], id[1], id[2], id[3]}, null);
                if (crc == ((id[4] << 8) | id[5])) {
                    pending = new Sector();
                    pending.c = id[0];
                    pending.h = id[1];
                    pending.r = id[2];
                    pending.n = id[3];
                } else {
                    pending = null;
                }
                i = at + 6 * 16 - 1;
                shift = 0;
            } else if ((mark == 0xFB || mark == 0xF8) && pending != null) {
                int size = 128 << Math.min(pending.n, 7);
                if (at + (size + 2) * 16 > cells.length) break;
                byte[] data = new byte[size];
                for (int k = 0; k < size; k++) data[k] = (byte) readByte(cells, at + k * 16);
                int hi = readByte(cells, at + size * 16);
                int lo = readByte(cells, at + size * 16 + 16);
                int crc = crc(new int[]{0xA1, 0xA1, 0xA1, mark}, data);
                pending.data = data;
                pending.deleted = mark == 0xF8;
                pending.crcError = crc != ((hi << 8) | lo);
                out.add(pending);
                pending = null;
                i = at + (size + 2) * 16 - 1;
                shift = 0;
            }
        }
        return out;
    }

    private static int word(boolean[] cells, int at) {
        int v = 0;
        for (int k = 0; k < 16; k++) v = (v << 1) | (cells[at + k] ? 1 : 0);
        return v;
    }

    // One byte from 16 cells (clock, data, clock, data …): the data bits.
    private static int readByte(boolean[] cells, int at) {
        if (at + 16 > cells.length) return -1;
        int v = 0;
        for (int k = 0; k < 8; k++) v = (v << 1) | (cells[at + k * 2 + 1] ? 1 : 0);
        return v;
    }

    private static int crc(int[] head, byte[] data) {
        int crc = 0xFFFF;
        for (int b : head) crc = crcByte(crc, b);
        if (data != null) {
            for (byte b : data) crc = crcByte(crc, b & 0xFF);
        }
        return crc;
    }

    private static int crcByte(int crc, int b) {
        crc ^= b << 8;
        for (int k = 0; k < 8; k++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        return crc;
    }

    // Sectors read again (a later revolution, the track's second pass): a good copy replaces
    // a bad one; order is the first pass's.
    private static void merge(List<Sector> into, List<Sector> found, boolean keepOrder) {
        Map<String, Integer> at = new HashMap<>();
        for (int k = 0; k < into.size(); k++) at.put(key(into.get(k)), k);
        for (Sector s : found) {
            Integer k = at.get(key(s));
            if (k == null) {
                at.put(key(s), into.size());
                into.add(s);
            } else if (into.get(k).crcError && !s.crcError) {
                into.set(k, s);
            }
        }
    }

    private static String key(Sector s) {
        return s.c + "/" + s.h + "/" + s.r + "/" + s.n;
    }

    private static int trackState(List<Sector> sectors) {
        if (sectors.isEmpty()) return TRACK_ERROR;
        for (Sector s : sectors) {
            if (s.crcError) return TRACK_ERROR;
        }
        return TRACK_OK;
    }

    // The usual track shape, and which tracks are not like it.
    private static void summarize(Result result) {
        Map<String, Integer> shapes = new LinkedHashMap<>();
        for (List<Sector> t : result.tracks) {
            if (t.isEmpty()) continue;
            String shape = t.size() + "x" + (128 << Math.min(t.get(0).n, 7));
            shapes.merge(shape, 1, Integer::sum);
        }
        String usual = null;
        for (Map.Entry<String, Integer> e : shapes.entrySet()) {
            if (usual == null || e.getValue() > shapes.get(usual)) usual = e.getKey();
        }
        if (usual != null) {
            result.sectorsPerTrack = Integer.parseInt(usual.substring(0, usual.indexOf('x')));
            result.sectorSize = Integer.parseInt(usual.substring(usual.indexOf('x') + 1));
        }
        for (int i = 0; i < result.tracks.size(); i++) {
            List<Sector> t = result.tracks.get(i);
            if (result.states[i] == TRACK_EMPTY || (t.isEmpty() && i >= 154)) {
                result.states[i] = TRACK_EMPTY;
                continue;
            }
            int bad = 0;
            boolean odd = false;
            java.util.Set<Integer> numbers = new java.util.HashSet<>();
            for (Sector s : t) {
                if (s.crcError) bad++;
                if ((128 << Math.min(s.n, 7)) != result.sectorSize || !numbers.add(s.r) || s.deleted || s.c != i / 2) odd = true;
            }
            if (t.size() != result.sectorsPerTrack) odd = true;
            result.badSectors += bad;
            if (bad > 0 || t.isEmpty()) {
                result.states[i] = TRACK_ERROR;
                result.errors++;
                result.notes.add(t.isEmpty() ? "트랙 " + i + ": 섹터를 못 찾음" : "트랙 " + i + ": 읽기 오류 섹터 " + bad + "개");
            } else if (odd) {
                result.states[i] = TRACK_ODD;
                result.odd++;
                result.notes.add("트랙 " + i + ": 섹터 " + t.size() + "개 · 모양이 다름");
            } else {
                result.states[i] = TRACK_OK;
                result.ok++;
            }
        }
    }

    // ---- writing ----

    static void writeD88(Result result, File out, String title) throws IOException {
        int tracks = Math.min(164, result.tracks.size());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long[] offsets = new long[164];
        for (int i = 0; i < tracks; i++) {
            List<Sector> t = result.tracks.get(i);
            if (t.isEmpty()) continue;
            offsets[i] = 0x2B0 + body.size();
            for (Sector s : t) {
                byte[] data = s.data == null ? new byte[0] : s.data;
                byte[] h = new byte[16];
                h[0] = (byte) s.c;
                h[1] = (byte) s.h;
                h[2] = (byte) s.r;
                h[3] = (byte) s.n;
                h[4] = (byte) t.size();
                h[5] = (byte) (t.size() >> 8);
                h[6] = 0x00;                                 // double density (MFM)
                h[7] = (byte) (s.deleted ? 0x10 : 0x00);
                h[8] = (byte) (s.crcError ? 0xB0 : 0x00);   // data CRC error
                h[14] = (byte) data.length;
                h[15] = (byte) (data.length >> 8);
                body.write(h);
                body.write(data);
            }
        }
        byte[] header = new byte[0x2B0];
        byte[] name = title.getBytes(Pc98Fat.SJIS);
        System.arraycopy(name, 0, header, 0, Math.min(16, name.length));
        header[0x1B] = (byte) (result.info.highDensity ? 0x20 : 0x10);
        long size = 0x2B0 + body.size();
        for (int k = 0; k < 4; k++) header[0x1C + k] = (byte) (size >> (8 * k));
        for (int i = 0; i < 164; i++) {
            for (int k = 0; k < 4; k++) header[0x20 + i * 4 + k] = (byte) (offsets[i] >> (8 * k));
        }
        try (FileOutputStream o = new FileOutputStream(out)) {
            o.write(header);
            body.writeTo(o);
        }
    }

    static void writeHdm(Result result, File out) throws IOException {
        if (!result.plainHdm()) throw new IOException("보통 2HD 디스크 모양이 아니라 .hdm으로는 못 담아요 (.d88로)");
        try (FileOutputStream o = new FileOutputStream(out)) {
            for (int i = 0; i < 154; i++) {
                byte[][] byR = new byte[9][];
                for (Sector s : result.tracks.get(i)) byR[s.r] = s.data;
                for (int r = 1; r <= 8; r++) o.write(byR[r]);
            }
        }
    }

    static String summaryLine(Result r) {
        return String.format(Locale.ROOT, "정상 %d · 특이 %d · 오류 %d (섹터 %d)", r.ok, r.odd, r.errors, r.badSectors);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xFFL) | ((b[i + 1] & 0xFFL) << 8) | ((b[i + 2] & 0xFFL) << 16) | ((b[i + 3] & 0xFFL) << 24);
    }
}
