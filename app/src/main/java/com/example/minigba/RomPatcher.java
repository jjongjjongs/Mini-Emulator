package com.example.minigba;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

// Applies a patch (translations and fixes are shared this way) to a ROM or disc image and
// writes the result to a new file: IPS, UPS, BPS, PPF and xdelta (VCDIFF, plain or with
// xdelta3's LZMA secondary compression). The patch, the image and the result are all
// memory-mapped, in 1 GB parts with 64-bit offsets, so a 4 GB PS2 disc needs no heap.
// Errors are IOExceptions with a message for the player.
final class RomPatcher {
    static final int IPS = 1;
    static final int UPS = 2;
    static final int BPS = 3;
    static final int XDELTA = 4;
    static final int PPF = 5;

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
            default: return "?";
        }
    }

    static int detect(Big p) throws IOException {
        if (p.startsWith("PATCH")) return IPS;
        if (p.startsWith("UPS1")) return UPS;
        if (p.startsWith("BPS1")) return BPS;
        if (p.startsWith("PPF10") || p.startsWith("PPF20") || p.startsWith("PPF30")) return PPF;
        if (p.size >= 4 && p.u8(0) == 0xD6 && p.u8(1) == 0xC3 && p.u8(2) == 0xC4) return XDELTA;
        throw new IOException("패치 파일이 아니에요. IPS · UPS · BPS · PPF · xdelta 파일을 골라 주세요.");
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

    // xdelta3's id for LZMA secondary compression; DJW (1) and FGK (16) are not supported.
    private static final int SECONDARY_LZMA = 2;

    private static void applyXdelta(Big p, Big src, File out) throws IOException {
        int indicator = p.u8(4);
        long[] at = {5};
        boolean lzma = false;
        if ((indicator & 0x01) != 0) {
            int compressor = p.u8(at[0]++);
            if (compressor != SECONDARY_LZMA) {
                throw new IOException("이 xdelta 패치는 지원하지 않는 압축(" + (compressor == 1 ? "DJW" : compressor == 16 ? "FGK" : "#" + compressor)
                        + ")을 써요. 압축 없이 또는 LZMA로 만든 패치가 필요해요.");
            }
            lzma = true;
        }
        if ((indicator & 0x02) != 0) {
            throw new IOException("이 xdelta 패치는 지원하지 않는 형식이에요 (사용자 코드 표).");
        }
        if ((indicator & 0x04) != 0) {
            long length = vcdInt(p, at);
            at[0] += length;
        }
        long firstWindow = at[0];
        // First pass: the total size of the result.
        long total = 0;
        while (at[0] < p.size) {
            int win = p.u8(at[0]++);
            if ((win & 0x03) != 0) {
                vcdInt(p, at);
                vcdInt(p, at);
            }
            vcdInt(p, at);  // Length of the delta encoding (whether it counts the checksum varies).
            total += vcdInt(p, at);
            at[0]++;  // Delta indicator.
            long sections = vcdInt(p, at) + vcdInt(p, at) + vcdInt(p, at);
            if ((win & 0x04) != 0) {
                at[0] += 4;
            }
            at[0] += sections;
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
                if (delta != 0 && !lzma) {
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
                byte[] data = section(p, at[0], dataLength, (delta & 0x01) != 0);
                byte[] inst = section(p, at[0] + dataLength, instLength, (delta & 0x02) != 0);
                byte[] addr = section(p, at[0] + dataLength + instLength, addrLength, (delta & 0x04) != 0);
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

    // One section of a window, unpacked when xdelta compressed it with LZMA: the unpacked size
    // as a VCDIFF number, then an .xz stream.
    private static byte[] section(Big p, long offset, long length, boolean compressed) throws IOException {
        if (length > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
        byte[] raw = new byte[(int) length];
        p.read(offset, raw, 0, raw.length);
        if (!compressed) {
            return raw;
        }
        int[] at = {0};
        long size = vcdInt(raw, at);
        if (size > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
        byte[] result = new byte[(int) size];
        try (org.tukaani.xz.XZInputStream in = new org.tukaani.xz.XZInputStream(
                new java.io.ByteArrayInputStream(raw, at[0], raw.length - at[0]))) {
            int done = 0;
            while (done < result.length) {
                int n = in.read(result, done, result.length - done);
                if (n < 0) throw new IOException("패치 파일이 손상됐어요.");
                done += n;
            }
        }
        return result;
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
