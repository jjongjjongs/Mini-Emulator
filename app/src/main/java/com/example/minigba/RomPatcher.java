package com.example.minigba;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

// Applies a patch (translations and fixes are shared this way) to a ROM or disc image and
// writes the result to a new file: IPS, UPS, BPS, PPF, xdelta (VCDIFF, plain or with
// xdelta3's DJW or LZMA secondary compression), APS (GBA and N64), RUP and BDF (bsdiff). The patch, the image and the result are all
// memory-mapped, in 1 GB parts with 64-bit offsets, so a 4 GB PS2 disc needs no heap.
// Errors are IOExceptions with a message for the player.
final class RomPatcher {
    static final int IPS = 1;
    static final int UPS = 2;
    static final int BPS = 3;
    static final int XDELTA = 4;
    static final int PPF = 5;
    static final int APS_GBA = 6;
    static final int APS_N64 = 7;
    static final int RUP = 8;
    static final int BDF = 9;

    // What a patch is and whether it was made for this image.
    static final class Check {
        int format;
        // 1 it was made for this image, 0 it can't tell (IPS, PPF without a check block, and
        // xdelta until applied), -1 a different one.
        int sourceMatches;
        // Bytes to skip at the start of the ROM (a SNES copier header the patch doesn't have).
        long skip;
    }

    private RomPatcher() {
    }

    static String formatName(int format) {
        switch (format) {
            case IPS: return "IPS";
            case UPS: return "UPS";
            case BPS: return "BPS";
            case XDELTA: return "xdelta";
            case PPF: return "PPF";
            case APS_GBA: return "APS (GBA)";
            case APS_N64: return "APS (N64)";
            case RUP: return "RUP";
            case BDF: return "BDF";
            default: return "?";
        }
    }

    static int detect(Big p) throws IOException {
        if (p.startsWith("PATCH")) return IPS;
        if (p.startsWith("UPS1")) return UPS;
        if (p.startsWith("BPS1")) return BPS;
        if (p.startsWith("PPF10") || p.startsWith("PPF20") || p.startsWith("PPF30")) return PPF;
        if (p.size >= 4 && p.u8(0) == 0xD6 && p.u8(1) == 0xC3 && p.u8(2) == 0xC4) return XDELTA;
        if (p.startsWith("APS10")) return APS_N64;
        if (p.startsWith("APS1")) return APS_GBA;
        if (p.startsWith("NINJA2")) return RUP;
        if (p.startsWith("BSDIFF40")) return BDF;
        throw new IOException("패치 파일이 아니에요. IPS · UPS · BPS · PPF · xdelta · APS · RUP · BDF 파일을 골라 주세요.");
    }

    static Check check(Big p, File rom) throws IOException {
        Check result = new Check();
        result.format = detect(p);
        if (result.format == BPS || result.format == UPS) {
            if (p.size < 16) {
                throw new IOException("패치 파일이 손상됐어요.");
            }
            if (p.crc(0, p.size - 4) != p.u32le(p.size - 4)) {
                throw new IOException("패치 파일이 손상됐어요 (검사값이 맞지 않아요).");
            }
            long expected = p.u32le(p.size - 12);
            long length = rom.length();
            try (Big image = Big.map(rom, 0, length, false)) {
                if (image.crc(0, length) == expected) {
                    result.sourceMatches = 1;
                } else if (length % 1024 == 512 && image.crc(512, length - 512) == expected) {
                    result.sourceMatches = 1;
                    result.skip = 512;
                } else {
                    result.sourceMatches = -1;
                }
            }
        } else if (result.format == PPF) {
            result.sourceMatches = checkPpf(p, rom);
        } else if (result.format == APS_GBA) {
            result.sourceMatches = checkApsGba(p, rom);
        } else if (result.format == APS_N64) {
            result.sourceMatches = checkApsN64(p, rom);
        } else if (result.format == RUP) {
            checkRup(p, rom, result);
        } else if (result.format == XDELTA) {
            // A SNES ROM with a copier header, patched without one: the patch reads exactly
            // the ROM without its first 512 bytes.
            long length = rom.length();
            if (length % 1024 == 512 && xdeltaSourceEnd(p) == length - 512) {
                result.skip = 512;
            }
        } else if (result.format == IPS) {
            result.skip = snesIpsSkip(p, rom);
        }
        return result;
    }

    static void apply(Big patch, File rom, Check check, File out) throws IOException {
        try {
            long sourceLength = rom.length() - check.skip;
            // IPS, UPS and PPF change bytes of a copy; start from a straight copy of the image.
            if (check.format == IPS || check.format == UPS || check.format == PPF) {
                copyFile(rom, check.skip, sourceLength, out);
            }
            try (Big src = Big.map(rom, check.skip, sourceLength, false)) {
                switch (check.format) {
                    case IPS:
                        applyIps(patch, src, out);
                        break;
                    case UPS:
                        applyUps(patch, src, out);
                        break;
                    case BPS:
                        applyBps(patch, src, out);
                        break;
                    case PPF:
                        applyPpf(patch, src, out);
                        break;
                    case APS_GBA:
                        applyApsGba(patch, src, out);
                        break;
                    case APS_N64:
                        applyApsN64(patch, src, out);
                        break;
                    case RUP:
                        applyRup(patch, src, check, out);
                        break;
                    case BDF:
                        applyBdf(patch, src, out);
                        break;
                    default:
                        applyXdelta(patch, src, out);
                        break;
                }
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        } catch (RuntimeException e) {
            out.delete();
            throw new IOException("패치를 적용하지 못했어요. 패치 파일이 손상됐거나 이 롬용이 아니에요.");
        }
    }

    // ---- IPS ----

    private static boolean ipsEnd(Big p, long pos) {
        return p.u24be(pos) == 0x454F46 && (pos + 3 == p.size || pos + 6 == p.size);
    }

    private static void applyIps(Big p, Big src, File out) throws IOException {
        // First pass: how long the result is.
        long size = src.size;
        long pos = 5;
        while (true) {
            if (pos + 3 > p.size) throw new IOException("패치 파일이 손상됐어요.");
            if (ipsEnd(p, pos)) {
                pos += 3;
                break;
            }
            long offset = p.u24be(pos);
            int length = p.u16be(pos + 3);
            pos += 5;
            if (length == 0) {
                length = p.u16be(pos);
                pos += 3;
            } else {
                pos += length;
            }
            size = Math.max(size, offset + length);
        }
        if (pos + 3 <= p.size) {
            size = p.u24be(pos);  // Truncation to this length.
        }
        try (Big dst = Big.map(out, 0, size, true)) {
            pos = 5;
            while (!ipsEnd(p, pos)) {
                long offset = p.u24be(pos);
                int length = p.u16be(pos + 3);
                pos += 5;
                if (length == 0) {
                    length = p.u16be(pos);
                    byte value = p.get(pos + 2);
                    pos += 3;
                    for (int k = 0; k < length && offset + k < size; k++) dst.put(offset + k, value);
                } else {
                    for (int k = 0; k < length && offset + k < size; k++) dst.put(offset + k, p.get(pos + k));
                    pos += length;
                }
            }
            dst.force();
        }
    }

    // ---- UPS ----

    private static void applyUps(Big p, Big src, File out) throws IOException {
        long[] at = {4};
        long inSize = varint(p, at);
        long outSize = varint(p, at);
        if (inSize != src.size) {
            throw new IOException("이 패치는 크기가 다른 롬용이에요.");
        }
        try (Big dst = Big.map(out, 0, outSize, true)) {
            long target = 0;
            long end = p.size - 12;
            while (at[0] < end) {
                target += varint(p, at);
                while (at[0] < end) {
                    byte x = p.get(at[0]++);
                    if (target < outSize) {
                        dst.put(target, (byte) (dst.get(target) ^ x));
                    }
                    target++;
                    if (x == 0) break;
                }
            }
            dst.force();
            if (dst.crc(0, outSize) != p.u32le(p.size - 8)) {
                throw new IOException("패치한 결과가 맞지 않아요. 이 롬용 패치가 아니에요.");
            }
        }
    }

    // ---- BPS ----

    private static void applyBps(Big p, Big src, File out) throws IOException {
        long[] at = {4};
        varint(p, at);  // Source size.
        long targetSize = varint(p, at);
        long metadata = varint(p, at);
        at[0] += metadata;
        byte[] scratch = new byte[1 << 16];
        try (Big dst = Big.map(out, 0, targetSize, true)) {
            long output = 0;
            long sourceRelative = 0;
            long targetRelative = 0;
            long end = p.size - 12;
            while (at[0] < end) {
                long data = varint(p, at);
                int command = (int) (data & 3);
                long length = (data >> 2) + 1;
                if (command == 0) {
                    Big.copy(src, output, dst, output, length, scratch);
                    output += length;
                } else if (command == 1) {
                    Big.copy(p, at[0], dst, output, length, scratch);
                    at[0] += length;
                    output += length;
                } else if (command == 2) {
                    long offset = varint(p, at);
                    sourceRelative += ((offset & 1) != 0 ? -1 : 1) * (offset >> 1);
                    Big.copy(src, sourceRelative, dst, output, length, scratch);
                    output += length;
                    sourceRelative += length;
                } else {
                    long offset = varint(p, at);
                    targetRelative += ((offset & 1) != 0 ? -1 : 1) * (offset >> 1);
                    if (targetRelative + length <= output) {
                        Big.copy(dst, targetRelative, dst, output, length, scratch);
                        output += length;
                        targetRelative += length;
                    } else {
                        // Byte by byte: the copy overlaps what it is writing (a repeated run).
                        for (long k = 0; k < length; k++) dst.put(output++, dst.get(targetRelative++));
                    }
                }
            }
            dst.force();
            if (dst.crc(0, targetSize) != p.u32le(p.size - 8)) {
                throw new IOException("패치한 결과가 맞지 않아요. 이 롬용 패치가 아니에요.");
            }
        }
    }

    // ---- PPF (PlayStation patches, versions 1 to 3) ----

    private static int ppfVersion(Big p) {
        return p.u8(3) - '0';
    }

    // Where the records start, and where they end (before an attached FILE_ID.DIZ).
    private static long[] ppfRecords(Big p) {
        int version = ppfVersion(p);
        long start;
        if (version == 1) {
            start = 56;
        } else if (version == 2) {
            start = 1084;
        } else {
            start = p.u8(57) != 0 ? 60 + 1024 : 60;
        }
        long end = p.size;
        long diz = p.lastIndexOf("@BEGIN_FILE_ID.DIZ", Math.max(start, p.size - 70000));
        if (diz >= start) {
            end = diz;
        }
        return new long[]{start, end};
    }

    // The 1024 bytes PPF 2 and 3 keep from the original image (at 0x9320 of a BIN, 0x80A0 of a
    // GI image) to tell whether a disc is the one the patch was made from.
    private static int checkPpf(Big p, File rom) throws IOException {
        int version = ppfVersion(p);
        long blockAt;
        long romAt;
        if (version == 2) {
            if (p.u32le(56) != rom.length()) {
                return -1;
            }
            blockAt = 60;
            romAt = 0x9320;
        } else if (version == 3 && p.u8(57) != 0) {
            blockAt = 60;
            romAt = p.u8(56) == 1 ? 0x80A0 : 0x9320;
        } else {
            return 0;
        }
        if (rom.length() < romAt + 1024) {
            return -1;
        }
        byte[] expected = new byte[1024];
        p.read(blockAt, expected, 0, 1024);
        byte[] actual = new byte[1024];
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            in.seek(romAt);
            in.readFully(actual);
        }
        return java.util.Arrays.equals(expected, actual) ? 1 : -1;
    }

    private static void applyPpf(Big p, Big src, File out) throws IOException {
        int version = ppfVersion(p);
        boolean undo = version == 3 && p.u8(58) != 0;
        long[] range = ppfRecords(p);
        long size = src.size;
        for (int pass = 0; pass < 2; pass++) {
            Big dst = pass == 1 ? Big.map(out, 0, size, true) : null;
            try {
                long pos = range[0];
                while (pos < range[1]) {
                    long offset;
                    if (version == 3) {
                        offset = p.u32le(pos) | (p.u32le(pos + 4) << 32);
                        pos += 8;
                    } else {
                        offset = p.u32le(pos);
                        pos += 4;
                    }
                    int length = p.u8(pos++);
                    if (pass == 0) {
                        size = Math.max(size, offset + length);
                    } else {
                        for (int k = 0; k < length; k++) dst.put(offset + k, p.get(pos + k));
                    }
                    pos += length;
                    if (undo) {
                        pos += length;
                    }
                }
                if (dst != null) {
                    dst.force();
                }
            } finally {
                if (dst != null) {
                    dst.close();
                }
            }
        }
    }

    // ---- xdelta (VCDIFF, RFC 3284) ----

    private static final int NOOP = 0;
    private static final int ADD = 1;
    private static final int RUN = 2;
    private static final int COPY = 3;
    // The default code table: {type1, size1, mode1, type2, size2, mode2} per opcode.
    private static final int[][] CODE_TABLE = buildCodeTable();

    private static int[][] buildCodeTable() {
        int[][] table = new int[256][];
        int i = 0;
        table[i++] = new int[]{RUN, 0, 0, NOOP, 0, 0};
        for (int size = 0; size <= 17; size++) table[i++] = new int[]{ADD, size, 0, NOOP, 0, 0};
        for (int mode = 0; mode <= 8; mode++) {
            table[i++] = new int[]{COPY, 0, mode, NOOP, 0, 0};
            for (int size = 4; size <= 18; size++) table[i++] = new int[]{COPY, size, mode, NOOP, 0, 0};
        }
        for (int mode = 0; mode <= 5; mode++) {
            for (int add = 1; add <= 4; add++) {
                for (int size = 4; size <= 6; size++) table[i++] = new int[]{ADD, add, 0, COPY, size, mode};
            }
        }
        for (int mode = 6; mode <= 8; mode++) {
            for (int add = 1; add <= 4; add++) table[i++] = new int[]{ADD, add, 0, COPY, 4, mode};
        }
        for (int mode = 0; mode <= 8; mode++) table[i++] = new int[]{COPY, 4, mode, ADD, 1, 0};
        return table;
    }

    // xdelta3's ids for secondary compression: DJW (1, Djw.java) and LZMA (2); FGK (16) is not supported.
    private static final int SECONDARY_LZMA = 2;
    private static final int SECONDARY_DJW = 1;

    // The size of what an xdelta patch makes (the sum of its windows), or -1.
    static long xdeltaTargetSize(Big p) {
        try {
            int indicator = p.u8(4);
            long[] at = {5};
            if ((indicator & 0x01) != 0) {
                at[0]++;
            }
            if ((indicator & 0x02) != 0) {
                return -1;
            }
            if ((indicator & 0x04) != 0) {
                long length = vcdInt(p, at);
                at[0] += length;
            }
            long total = 0;
            while (at[0] < p.size) {
                int win = p.u8(at[0]++);
                if ((win & 0x03) != 0) {
                    vcdInt(p, at);
                    vcdInt(p, at);
                }
                vcdInt(p, at);
                total += vcdInt(p, at);
                at[0]++;
                long sections = vcdInt(p, at) + vcdInt(p, at) + vcdInt(p, at);
                if ((win & 0x04) != 0) {
                    at[0] += 4;
                }
                at[0] += sections;
            }
            return total;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // How far into the original an xdelta patch reads (the end of its furthest source window),
    // or -1 when it reads none or cannot be parsed: a track shorter than this is not its original.
    static long xdeltaSourceEnd(Big p) {
        try {
            int indicator = p.u8(4);
            long[] at = {5};
            if ((indicator & 0x01) != 0) {
                at[0]++;
            }
            if ((indicator & 0x02) != 0) {
                return -1;
            }
            if ((indicator & 0x04) != 0) {
                long length = vcdInt(p, at);
                at[0] += length;
            }
            long end = -1;
            while (at[0] < p.size) {
                int win = p.u8(at[0]++);
                if ((win & 0x03) != 0) {
                    long segLen = vcdInt(p, at);
                    long segPos = vcdInt(p, at);
                    if ((win & 0x01) != 0) {
                        end = Math.max(end, segPos + segLen);
                    }
                }
                vcdInt(p, at);
                vcdInt(p, at);
                at[0]++;
                long sections = vcdInt(p, at) + vcdInt(p, at) + vcdInt(p, at);
                if ((win & 0x04) != 0) {
                    at[0] += 4;
                }
                at[0] += sections;
            }
            return end;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void applyXdelta(Big p, Big src, File out) throws IOException {
        int indicator = p.u8(4);
        long[] at = {5};
        boolean lzma = false;
        boolean djw = false;
        if ((indicator & 0x01) != 0) {
            int compressor = p.u8(at[0]++);
            if (compressor == SECONDARY_LZMA) {
                lzma = true;
            } else if (compressor == SECONDARY_DJW) {
                djw = true;
            } else {
                throw new IOException("이 xdelta 패치는 지원하지 않는 압축(" + (compressor == 16 ? "FGK" : "#" + compressor)
                        + ")을 써요. 압축 없이, DJW 또는 LZMA로 만든 패치가 필요해요.");
            }
        }
        if ((indicator & 0x02) != 0) {
            throw new IOException("이 xdelta 패치는 지원하지 않는 형식이에요 (사용자 코드 표).");
        }
        if ((indicator & 0x04) != 0) {
            long length = vcdInt(p, at);
            at[0] += length;
        }
        long firstWindow = at[0];
        List<List<long[]>> parts = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            parts.add(new ArrayList<>());
        }
        // First pass: the total size of the result (and where the compressed sections are).
        long total = 0;
        while (at[0] < p.size) {
            int win = p.u8(at[0]++);
            if ((win & 0x03) != 0) {
                vcdInt(p, at);
                vcdInt(p, at);
            }
            vcdInt(p, at);  // Length of the delta encoding (whether it counts the checksum varies).
            total += vcdInt(p, at);
            int delta = p.u8(at[0]++);
            long[] lengths = {vcdInt(p, at), vcdInt(p, at), vcdInt(p, at)};
            if ((win & 0x04) != 0) {
                at[0] += 4;
            }
            long start = at[0];
            for (int k = 0; k < 3; k++) {
                if (lzma && (delta & (1 << k)) != 0 && lengths[k] > 0) {
                    long[] payload = {start};
                    vcdInt(p, payload);  // The unpacked size; the rest is the stream's next part.
                    parts.get(k).add(new long[]{payload[0], start + lengths[k] - payload[0]});
                }
                start += lengths[k];
            }
            at[0] = start;
        }
        // xdelta3 compresses each kind of section (data, instructions, addresses) as one .xz
        // stream running through all the windows, flushed at the end of every window, so each
        // kind gets one decoder over its parts in order.
        java.io.InputStream[] decoders = new java.io.InputStream[3];
        if (lzma) {
            for (int k = 0; k < 3; k++) {
                if (!parts.get(k).isEmpty()) {
                    try {
                        decoders[k] = new org.tukaani.xz.SingleXZInputStream(new java.io.BufferedInputStream(new Parts(p, parts.get(k)), 1 << 16));
                    } catch (org.tukaani.xz.XZIOException e) {
                        throw new IOException("패치 파일의 압축(LZMA)을 풀 수 없어요. 패치 파일이 손상됐을 수 있어요.", e);
                    }
                }
            }
        }
        try (Big dst = Big.map(out, 0, total, true)) {
            at[0] = firstWindow;
            long output = 0;
            int[] near = new int[4];
            int[] same = new int[3 * 256];
            byte[] scratch = new byte[1 << 16];
            while (at[0] < p.size) {
                int win = p.u8(at[0]++);
                long segLen = 0;
                long segPos = 0;
                boolean fromTarget = (win & 0x02) != 0;
                if ((win & 0x03) != 0) {
                    segLen = vcdInt(p, at);
                    segPos = vcdInt(p, at);
                }
                vcdInt(p, at);  // Length of the delta encoding.
                long windowLength = vcdInt(p, at);
                int delta = p.u8(at[0]++);
                if (delta != 0 && !lzma && !djw) {
                    throw new IOException("패치 파일이 손상됐어요.");
                }
                long dataLength = vcdInt(p, at);
                long instLength = vcdInt(p, at);
                long addrLength = vcdInt(p, at);
                long adler = -1;
                if ((win & 0x04) != 0) {
                    adler = p.u32be(at[0]);
                    at[0] += 4;
                }
                byte[] data = section(p, at[0], dataLength, (delta & 0x01) != 0, decoders[0], djw);
                byte[] inst = section(p, at[0] + dataLength, instLength, (delta & 0x02) != 0, decoders[1], djw);
                byte[] addr = section(p, at[0] + dataLength + instLength, addrLength, (delta & 0x04) != 0, decoders[2], djw);
                at[0] += dataLength + instLength + addrLength;
                int[] d = {0};
                int[] i = {0};
                int[] a = {0};
                long windowStart = output;
                long windowEnd = windowStart + windowLength;
                java.util.Arrays.fill(near, 0);
                java.util.Arrays.fill(same, 0);
                int nextSlot = 0;
                while (i[0] < inst.length) {
                    int[] code = CODE_TABLE[inst[i[0]++] & 0xFF];
                    for (int half = 0; half < 2; half++) {
                        int type = code[half * 3];
                        if (type == NOOP) continue;
                        int size = code[half * 3 + 1];
                        if (size == 0) size = (int) vcdInt(inst, i);
                        if (output + size > windowEnd) throw new IOException("패치 파일이 손상됐어요.");
                        if (type == ADD) {
                            dst.write(output, data, d[0], size);
                            d[0] += size;
                            output += size;
                        } else if (type == RUN) {
                            byte value = data[d[0]++];
                            for (int k = 0; k < size; k++) dst.put(output++, value);
                        } else {
                            int mode = code[half * 3 + 2];
                            int here = (int) (segLen + (output - windowStart));
                            int address;
                            if (mode == 0) {
                                address = (int) vcdInt(addr, a);
                            } else if (mode == 1) {
                                address = here - (int) vcdInt(addr, a);
                            } else if (mode < 6) {
                                address = near[mode - 2] + (int) vcdInt(addr, a);
                            } else {
                                address = same[(mode - 6) * 256 + (addr[a[0]++] & 0xFF)];
                            }
                            near[nextSlot] = address;
                            nextSlot = (nextSlot + 1) % 4;
                            same[address % (3 * 256)] = address;
                            long targetFrom = windowStart + address - segLen;
                            if (address + (long) size <= segLen) {
                                // All from the source segment: in one go.
                                Big.copy(fromTarget ? dst : src, segPos + address, dst, output, size, scratch);
                                output += size;
                                continue;
                            }
                            if (address >= segLen && targetFrom + size <= output) {
                                // From earlier in this window, not overlapping what it writes.
                                Big.copy(dst, targetFrom, dst, output, size, scratch);
                                output += size;
                                continue;
                            }
                            for (int k = 0; k < size; k++) {
                                long from = address + k;
                                byte value;
                                if (from < segLen) {
                                    value = fromTarget ? dst.get(segPos + from) : src.get(segPos + from);
                                } else {
                                    // Byte by byte: it may copy what this same copy just wrote.
                                    value = dst.get(windowStart + from - segLen);
                                }
                                dst.put(output++, value);
                            }
                        }
                    }
                }
                if (adler >= 0 && dst.adler32(windowStart, windowLength) != adler) {
                    throw new IOException("원본이 이 패치와 맞지 않아요. 패치 설명에 적힌 원본 롬(디스크)이 필요해요.");
                }
                output = windowEnd;
            }
            dst.force();
        }
    }

    // One section of a window. When xdelta compressed it with LZMA it holds the unpacked size
    // as a VCDIFF number and then the next part of that kind's stream (read by decoder).
    private static byte[] section(Big p, long offset, long length, boolean compressed, java.io.InputStream decoder, boolean djw) throws IOException {
        if (length > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
        if (!compressed || length == 0) {
            byte[] raw = new byte[(int) length];
            p.read(offset, raw, 0, raw.length);
            return raw;
        }
        if (djw) {
            // DJW: each section on its own; the unpacked size, then the Huffman coded bytes.
            byte[] raw = new byte[(int) length];
            p.read(offset, raw, 0, raw.length);
            int[] at = {0};
            long size = vcdInt(raw, at);
            if (size > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
            return Djw.decode(raw, at[0], raw.length - at[0], (int) size);
        }
        if (decoder == null) throw new IOException("패치 파일이 손상됐어요.");
        long size = vcdInt(p, new long[]{offset});
        if (size > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
        byte[] result = new byte[(int) size];
        int done = 0;
        try {
            while (done < result.length) {
                int n = decoder.read(result, done, result.length - done);
                if (n < 0) throw new IOException("패치 파일이 손상됐어요.");
                done += n;
            }
        } catch (org.tukaani.xz.XZIOException e) {
            throw new IOException("패치 파일의 압축(LZMA)을 풀 수 없어요. 패치 파일이 손상됐을 수 있어요.", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("패치 압축을 푸는 데 메모리가 모자라요. 다른 앱을 닫고 다시 해 주세요.");
        }
        return result;
    }

    // The parts of one compressed stream, spread over the patch, read one after another.
    private static final class Parts extends java.io.InputStream {
        private final Big p;
        private final List<long[]> parts;
        private int index;
        private long done;

        Parts(Big p, List<long[]> parts) {
            this.p = p;
            this.parts = parts;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] into, int at, int length) {
            while (index < parts.size() && done >= parts.get(index)[1]) {
                index++;
                done = 0;
            }
            if (index >= parts.size()) {
                return -1;
            }
            long[] part = parts.get(index);
            int n = (int) Math.min(length, part[1] - done);
            p.read(part[0] + done, into, at, n);
            done += n;
            return n;
        }
    }

    // ---- APS (GBA): XOR records of 64 KB blocks, each with CRC16s of the block before and after ----

    private static final int APS_GBA_BLOCK = 0x10000;

    private static int crc16(byte[] data, int at, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[at + i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static int checkApsGba(Big p, File rom) throws IOException {
        if (p.size < 12 || (p.size - 12) % (8 + APS_GBA_BLOCK) != 0) throw new IOException("패치 파일이 손상됐어요.");
        if (p.u32le(4) != rom.length()) return -1;
        try (Big src = Big.map(rom, 0, rom.length(), false)) {
            byte[] block = new byte[APS_GBA_BLOCK];
            for (long at = 12; at < p.size; at += 8 + APS_GBA_BLOCK) {
                long offset = p.u32le(at);
                java.util.Arrays.fill(block, (byte) 0);
                int n = (int) Math.max(0, Math.min(APS_GBA_BLOCK, src.size - offset));
                if (n > 0) src.read(offset, block, 0, n);
                if (crc16(block, 0, APS_GBA_BLOCK) != (p.u8(at + 4) | p.u8(at + 5) << 8)) return -1;
            }
        }
        return 1;
    }

    private static void applyApsGba(Big p, Big src, File out) throws IOException {
        long target = p.u32le(8);
        try (Big dst = Big.map(out, 0, target, true)) {
            copyInto(src, dst);
            byte[] block = new byte[APS_GBA_BLOCK];
            byte[] xor = new byte[APS_GBA_BLOCK];
            for (long at = 12; at + 8 + APS_GBA_BLOCK <= p.size; at += 8 + APS_GBA_BLOCK) {
                long offset = p.u32le(at);
                int targetCrc = p.u8(at + 6) | p.u8(at + 7) << 8;
                java.util.Arrays.fill(block, (byte) 0);
                int n = (int) Math.max(0, Math.min(APS_GBA_BLOCK, src.size - offset));
                if (n > 0) src.read(offset, block, 0, n);
                p.read(at + 8, xor, 0, APS_GBA_BLOCK);
                for (int k = 0; k < APS_GBA_BLOCK; k++) block[k] ^= xor[k];
                if (crc16(block, 0, APS_GBA_BLOCK) != targetCrc) {
                    throw new IOException("원본이 이 패치와 맞지 않아요. 패치 설명에 적힌 원본 롬(디스크)이 필요해요.");
                }
                int m = (int) Math.max(0, Math.min(APS_GBA_BLOCK, target - offset));
                if (m > 0) dst.write(offset, block, 0, m);
            }
            dst.force();
        }
    }

    // ---- APS (N64): a header naming the cartridge, then plain and run records ----

    private static int checkApsN64(Big p, File rom) throws IOException {
        if (p.size < 61) throw new IOException("패치 파일이 손상됐어요.");
        if (p.u8(5) != 1) return 0;  // Not N64 specific: nothing to check.
        try (Big src = Big.map(rom, 0, rom.length(), false)) {
            if (src.size < 0x40) return -1;
            for (int k = 0; k < 3; k++) {
                if (src.get(0x3C + k) != p.get(58 + k)) return -1;
            }
            for (int k = 0; k < 8; k++) {
                if (src.get(0x10 + k) != p.get(61 + k)) return -1;
            }
        }
        return 1;
    }

    private static void applyApsN64(Big p, Big src, File out) throws IOException {
        long at = p.u8(5) == 1 ? 57 + 1 + 3 + 8 + 5 : 57;
        long target = p.u32le(at);
        at += 4;
        try (Big dst = Big.map(out, 0, target, true)) {
            copyInto(src, dst);
            while (at < p.size) {
                long offset = p.u32le(at);
                int length = p.u8(at + 4);
                at += 5;
                if (length == 0) {
                    byte value = p.get(at);
                    int count = p.u8(at + 1);
                    at += 2;
                    for (int k = 0; k < count; k++) dst.put(offset + k, value);
                } else {
                    for (int k = 0; k < length; k++) dst.put(offset + k, p.get(at + k));
                    at += length;
                }
            }
            dst.force();
        }
    }

    // ---- RUP (NINJA 2.0): per file, MD5s of the original and the result, then XOR records ----

    private static final class RupFile {
        long sourceSize;
        long targetSize;
        byte[] sourceMd5 = new byte[16];
        byte[] targetMd5 = new byte[16];
        int overflow;            // 'A' (append) or 'M' (cut), 0 none
        long overflowAt;
        long overflowLength;
        final List<long[]> records = new ArrayList<>();  // {offset, patch position, length}
    }

    private static long rupNumber(Big p, long[] at) {
        int count = p.u8(at[0]++);
        long value = 0;
        for (int i = 0; i < count; i++) value |= (long) p.u8(at[0]++) << (8 * i);
        return value;
    }

    private static List<RupFile> parseRup(Big p) throws IOException {
        List<RupFile> files = new ArrayList<>();
        long[] at = {0x800};
        RupFile current = null;
        while (at[0] < p.size) {
            int command = p.u8(at[0]++);
            if (command == 0) break;
            if (command == 1) {
                current = new RupFile();
                files.add(current);
                long nameLength = rupNumber(p, at);
                at[0] += nameLength;
                at[0]++;  // ROM type.
                current.sourceSize = rupNumber(p, at);
                current.targetSize = rupNumber(p, at);
                p.read(at[0], current.sourceMd5, 0, 16);
                p.read(at[0] + 16, current.targetMd5, 0, 16);
                at[0] += 32;
                if (current.sourceSize != current.targetSize) {
                    current.overflow = p.u8(at[0]++);
                    current.overflowLength = rupNumber(p, at);
                    current.overflowAt = at[0];
                    at[0] += current.overflowLength;
                }
            } else if (command == 2 && current != null) {
                long offset = rupNumber(p, at);
                long length = rupNumber(p, at);
                current.records.add(new long[]{offset, at[0], length});
                at[0] += length;
            } else {
                throw new IOException("패치 파일이 손상됐어요.");
            }
        }
        if (files.isEmpty()) throw new IOException("패치 파일이 손상됐어요.");
        return files;
    }

    private static byte[] md5(Big b, long offset, long length) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] chunk = new byte[1 << 20];
            while (length > 0) {
                int n = (int) Math.min(chunk.length, length);
                b.read(offset, chunk, 0, n);
                md.update(chunk, 0, n);
                offset += n;
                length -= n;
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // Which file of the patch this ROM is (with or without a SNES / NES header): Check.skip.
    private static void checkRup(Big p, File rom, Check result) throws IOException {
        List<RupFile> files = parseRup(p);
        long length = rom.length();
        try (Big src = Big.map(rom, 0, length, false)) {
            long[] skips = {0, 512, 16};
            for (long skip : skips) {
                if (skip > 0 && (skip == 512 ? length % 1024 != 512 : !src.startsWith("NES\u001A"))) continue;
                byte[] sum = md5(src, skip, length - skip);
                for (RupFile file : files) {
                    if (java.util.Arrays.equals(sum, file.sourceMd5)) {
                        result.sourceMatches = 1;
                        result.skip = skip;
                        return;
                    }
                }
            }
        }
        result.sourceMatches = -1;
    }

    private static void applyRup(Big p, Big src, Check check, File out) throws IOException {
        byte[] sum = md5(src, 0, src.size);
        RupFile file = null;
        for (RupFile f : parseRup(p)) {
            if (java.util.Arrays.equals(sum, f.sourceMd5)) file = f;
        }
        if (file == null) throw new IOException("원본이 이 패치와 맞지 않아요. 패치 설명에 적힌 원본 롬(디스크)이 필요해요.");
        try (Big dst = Big.map(out, 0, file.targetSize, true)) {
            copyInto(src, dst);
            for (long[] record : file.records) {
                for (long k = 0; k < record[2]; k++) {
                    long pos = record[0] + k;
                    if (pos >= dst.size) break;
                    int old = pos < src.size ? src.u8(pos) : 0;
                    dst.put(pos, (byte) (old ^ p.u8(record[1] + k)));
                }
            }
            if (file.overflow == 'A') {
                for (long k = 0; k < file.overflowLength && file.sourceSize + k < dst.size; k++) {
                    dst.put(file.sourceSize + k, (byte) (p.u8(file.overflowAt + k) ^ 0xFF));
                }
            }
            if (!java.util.Arrays.equals(md5(dst, 0, dst.size), file.targetMd5)) {
                throw new IOException("패치한 결과가 패치에 적힌 것과 달라요. 원본이 이 패치와 맞지 않을 수 있어요.");
            }
            dst.force();
        }
    }

    // ---- BDF (bsdiff 4): bzip2 compressed control, difference and extra blocks ----

    private static long offtin(Big p, long at) {
        long value = 0;
        for (int k = 6; k >= 0; k--) value = (value << 8) | p.u8(at + k);
        value |= (long) (p.u8(at + 7) & 0x7F) << 56;
        return (p.u8(at + 7) & 0x80) != 0 ? -value : value;
    }

    private static long offtin(byte[] b) {
        long value = 0;
        for (int k = 6; k >= 0; k--) value = (value << 8) | (b[k] & 0xFF);
        value |= (long) (b[7] & 0x7F) << 56;
        return (b[7] & 0x80) != 0 ? -value : value;
    }

    // Part of a patch as a stream.
    private static java.io.InputStream slice(Big p, long offset, long length) {
        return new java.io.InputStream() {
            long at = offset;
            final long end = offset + length;

            @Override
            public int read() {
                return at < end ? p.u8(at++) : -1;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                if (at >= end) return -1;
                int n = (int) Math.min(len, end - at);
                p.read(at, into, off, n);
                at += n;
                return n;
            }
        };
    }

    private static void readFully(java.io.InputStream in, byte[] into, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(into, done, length - done);
            if (n < 0) throw new IOException("패치 파일이 손상됐어요.");
            done += n;
        }
    }

    private static void applyBdf(Big p, Big src, File out) throws IOException {
        if (p.size < 32) throw new IOException("패치 파일이 손상됐어요.");
        long controlLength = offtin(p, 8);
        long diffLength = offtin(p, 16);
        long target = offtin(p, 24);
        if (controlLength < 0 || diffLength < 0 || target < 0 || 32 + controlLength + diffLength > p.size) {
            throw new IOException("패치 파일이 손상됐어요.");
        }
        try (Big dst = Big.map(out, 0, target, true);
             java.io.InputStream control = new Bzip2(new java.io.BufferedInputStream(slice(p, 32, controlLength), 1 << 16));
             java.io.InputStream diff = new Bzip2(new java.io.BufferedInputStream(slice(p, 32 + controlLength, diffLength), 1 << 16));
             java.io.InputStream extra = new Bzip2(new java.io.BufferedInputStream(slice(p, 32 + controlLength + diffLength,
                     p.size - 32 - controlLength - diffLength), 1 << 16))) {
            byte[] triple = new byte[24];
            byte[] eight = new byte[8];
            byte[] chunk = new byte[1 << 16];
            long newPos = 0;
            long oldPos = 0;
            while (newPos < target) {
                readFully(control, triple, 24);
                System.arraycopy(triple, 0, eight, 0, 8);
                long add = offtin(eight);
                System.arraycopy(triple, 8, eight, 0, 8);
                long copy = offtin(eight);
                System.arraycopy(triple, 16, eight, 0, 8);
                long seek = offtin(eight);
                if (add < 0 || copy < 0 || newPos + add + copy > target) throw new IOException("패치 파일이 손상됐어요.");
                for (long done = 0; done < add; ) {
                    int n = (int) Math.min(chunk.length, add - done);
                    readFully(diff, chunk, n);
                    for (int k = 0; k < n; k++) {
                        long o = oldPos + done + k;
                        if (o >= 0 && o < src.size) chunk[k] += src.get(o);
                    }
                    dst.write(newPos + done, chunk, 0, n);
                    done += n;
                }
                newPos += add;
                oldPos += add;
                for (long done = 0; done < copy; ) {
                    int n = (int) Math.min(chunk.length, copy - done);
                    readFully(extra, chunk, n);
                    dst.write(newPos + done, chunk, 0, n);
                    done += n;
                }
                newPos += copy;
                oldPos += seek;
            }
            dst.force();
        }
    }

    // The start of a result: the original as far as both go.
    private static void copyInto(Big src, Big dst) {
        byte[] chunk = new byte[1 << 20];
        long length = Math.min(src.size, dst.size);
        for (long at = 0; at < length; ) {
            int n = (int) Math.min(chunk.length, length - at);
            src.read(at, chunk, 0, n);
            dst.write(at, chunk, 0, n);
            at += n;
        }
    }

    // ---- SNES copier headers and IPS ----

    // For an IPS patch on a SNES ROM with a 512-byte copier header: 512 when the patch was made
    // for the ROM without it (the result then has a valid internal checksum only that way),
    // else 0 (applied as is, as before).
    private static long snesIpsSkip(Big p, File rom) {
        long length = rom.length();
        if (length % 1024 != 512 || length > 16L * 1024 * 1024 + 512) return 0;
        try {
            byte[] image = new byte[(int) length];
            try (RandomAccessFile file = new RandomAccessFile(rom, "r")) {
                file.readFully(image);
            }
            byte[] asIs = image.clone();
            byte[] body = java.util.Arrays.copyOfRange(image, 512, image.length);
            if (!ipsInMemory(p, asIs) || !ipsInMemory(p, body)) return 0;
            boolean bodyOk = snesChecksumOk(body, 0);
            boolean asIsOk = snesChecksumOk(asIs, 512);
            return bodyOk && !asIsOk ? 512 : 0;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return 0;
        }
    }

    // Applies an IPS patch to a ROM in memory, staying in its size; false if it reaches past.
    private static boolean ipsInMemory(Big p, byte[] rom) {
        long pos = 5;
        while (pos + 3 <= p.size && !ipsEnd(p, pos)) {
            long offset = p.u24be(pos);
            int size = (p.u8(pos + 3) << 8) | p.u8(pos + 4);
            pos += 5;
            if (size == 0) {
                int run = (p.u8(pos) << 8) | p.u8(pos + 1);
                byte value = p.get(pos + 2);
                pos += 3;
                if (offset + run > rom.length) return false;
                for (int k = 0; k < run; k++) rom[(int) offset + k] = value;
            } else {
                if (offset + size > rom.length) return false;
                p.read(pos, rom, (int) offset, size);
                pos += size;
            }
        }
        return true;
    }

    // Whether the SNES internal header (LoROM or HiROM) holds a checksum that matches the ROM.
    private static boolean snesChecksumOk(byte[] image, int start) {
        int length = image.length - start;
        if (length < 0x8000) return false;
        int sum = 0;
        int power = Integer.highestOneBit(length);
        for (int i = 0; i < power; i++) sum += image[start + i] & 0xFF;
        if (power < length) {
            int rest = length - power;
            int restSum = 0;
            for (int i = power; i < length; i++) restSum += image[start + i] & 0xFF;
            sum += restSum * (power / rest);
        }
        sum &= 0xFFFF;
        int[] headers = {0x7FC0, 0xFFC0};
        for (int h : headers) {
            if (h + 0x20 > length) continue;
            int complement = (image[start + h + 0x1C] & 0xFF) | (image[start + h + 0x1D] & 0xFF) << 8;
            int checksum = (image[start + h + 0x1E] & 0xFF) | (image[start + h + 0x1F] & 0xFF) << 8;
            if ((complement ^ checksum) == 0xFFFF && checksum == sum) return true;
        }
        return false;
    }

    // ---- Numbers in patches ----

    // UPS and BPS numbers: 7 bits a byte, the last byte has the top bit set.
    private static long varint(Big p, long[] at) {
        long data = 0;
        long shift = 1;
        while (true) {
            int x = p.u8(at[0]++);
            data += (x & 0x7F) * shift;
            if ((x & 0x80) != 0) break;
            shift <<= 7;
            data += shift;
        }
        return data;
    }

    // VCDIFF numbers: 7 bits a byte, most significant first, the top bit set on all but the last.
    private static long vcdInt(Big p, long[] at) {
        long value = 0;
        while (true) {
            int x = p.u8(at[0]++);
            value = (value << 7) | (x & 0x7F);
            if ((x & 0x80) == 0) break;
        }
        return value;
    }

    private static long vcdInt(byte[] p, int[] at) {
        long value = 0;
        while (true) {
            int x = p[at[0]++] & 0xFF;
            value = (value << 7) | (x & 0x7F);
            if ((x & 0x80) == 0) break;
        }
        return value;
    }

    private static void copyFile(File from, long offset, long length, File to) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(from, "r"); RandomAccessFile out = new RandomAccessFile(to, "rw")) {
            out.setLength(0);
            FileChannel source = in.getChannel();
            FileChannel target = out.getChannel();
            long done = 0;
            while (done < length) {
                long n = source.transferTo(offset + done, length - done, target);
                if (n <= 0) throw new IOException("롬을 복사하지 못했어요.");
                done += n;
            }
        }
    }

    // A file mapped into memory in 1 GB parts, read and written by 64-bit offset.
    static final class Big implements Closeable {
        private static final int SHIFT = 30;
        private static final long PART = 1L << SHIFT;
        private static final long MASK = PART - 1;
        final long size;
        private final MappedByteBuffer[] parts;

        private Big(MappedByteBuffer[] parts, long size) {
            this.parts = parts;
            this.size = size;
        }

        // write sets the file to size first (growing or cutting it).
        static Big map(File file, long offset, long size, boolean write) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file, write ? "rw" : "r")) {
                if (write && raf.length() != offset + size) {
                    raf.setLength(offset + size);
                }
                FileChannel channel = raf.getChannel();
                int count = (int) ((size + PART - 1) >>> SHIFT);
                MappedByteBuffer[] parts = new MappedByteBuffer[Math.max(count, 0)];
                for (int k = 0; k < count; k++) {
                    long length = Math.min(PART, size - ((long) k << SHIFT));
                    parts[k] = channel.map(write ? FileChannel.MapMode.READ_WRITE : FileChannel.MapMode.READ_ONLY,
                            offset + ((long) k << SHIFT), length);
                }
                return new Big(parts, size);
            }
        }

        byte get(long i) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException();
            return parts[(int) (i >>> SHIFT)].get((int) (i & MASK));
        }

        int u8(long i) {
            return get(i) & 0xFF;
        }

        void put(long i, byte value) {
            if (i < 0 || i >= size) throw new IndexOutOfBoundsException();
            parts[(int) (i >>> SHIFT)].put((int) (i & MASK), value);
        }

        void read(long offset, byte[] into, int at, int length) {
            if (offset < 0 || offset + length > size) throw new IndexOutOfBoundsException();
            while (length > 0) {
                java.nio.ByteBuffer part = parts[(int) (offset >>> SHIFT)].duplicate();
                int inPart = (int) (offset & MASK);
                int n = Math.min(length, part.capacity() - inPart);
                part.position(inPart);
                part.get(into, at, n);
                offset += n;
                at += n;
                length -= n;
            }
        }

        void write(long offset, byte[] from, int at, int length) {
            if (offset < 0 || offset + length > size) throw new IndexOutOfBoundsException();
            while (length > 0) {
                java.nio.ByteBuffer part = parts[(int) (offset >>> SHIFT)].duplicate();
                int inPart = (int) (offset & MASK);
                int n = Math.min(length, part.capacity() - inPart);
                part.position(inPart);
                part.put(from, at, n);
                offset += n;
                at += n;
                length -= n;
            }
        }

        static void copy(Big from, long fromOffset, Big to, long toOffset, long length, byte[] scratch) {
            while (length > 0) {
                int n = (int) Math.min(scratch.length, length);
                from.read(fromOffset, scratch, 0, n);
                to.write(toOffset, scratch, 0, n);
                fromOffset += n;
                toOffset += n;
                length -= n;
            }
        }

        long crc(long offset, long length) {
            CRC32 crc = new CRC32();
            byte[] chunk = new byte[1 << 20];
            while (length > 0) {
                int n = (int) Math.min(chunk.length, length);
                read(offset, chunk, 0, n);
                crc.update(chunk, 0, n);
                offset += n;
                length -= n;
            }
            return crc.getValue();
        }

        long adler32(long offset, long length) {
            Adler32 adler = new Adler32();
            byte[] chunk = new byte[1 << 20];
            while (length > 0) {
                int n = (int) Math.min(chunk.length, length);
                read(offset, chunk, 0, n);
                adler.update(chunk, 0, n);
                offset += n;
                length -= n;
            }
            return adler.getValue();
        }

        boolean startsWith(String magic) {
            if (size < magic.length()) return false;
            for (int i = 0; i < magic.length(); i++) {
                if (get(i) != (byte) magic.charAt(i)) return false;
            }
            return true;
        }

        // The last place text starts at or after from, or -1.
        long lastIndexOf(String text, long from) {
            for (long i = size - text.length(); i >= from; i--) {
                boolean match = true;
                for (int k = 0; k < text.length() && match; k++) {
                    match = get(i + k) == (byte) text.charAt(k);
                }
                if (match) return i;
            }
            return -1;
        }

        long u32le(long at) {
            return (long) u8(at) | ((long) u8(at + 1) << 8) | ((long) u8(at + 2) << 16) | ((long) u8(at + 3) << 24);
        }

        long u32be(long at) {
            return ((long) u8(at) << 24) | ((long) u8(at + 1) << 16) | ((long) u8(at + 2) << 8) | (long) u8(at + 3);
        }

        int u24be(long at) {
            return (u8(at) << 16) | (u8(at + 1) << 8) | u8(at + 2);
        }

        int u16be(long at) {
            return (u8(at) << 8) | u8(at + 1);
        }

        void force() {
            for (MappedByteBuffer part : parts) {
                part.force();
            }
        }

        @Override
        public void close() {
            // Mapped memory is released when the buffers are collected.
        }
    }
}
