package com.example.minigba;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

// Applies a ROM patch (translations and fixes are shared this way) to a ROM file and writes
// the result to a new file. IPS, UPS, BPS and xdelta (VCDIFF without secondary compression).
// The ROM and the result are memory-mapped, so a 512 MB DS game doesn't have to fit in the
// Java heap; the patch itself is read whole. Errors are IOExceptions with a message for the
// player.
final class RomPatcher {
    static final int IPS = 1;
    static final int UPS = 2;
    static final int BPS = 3;
    static final int XDELTA = 4;

    // What a patch is and whether it was made for this ROM.
    static final class Check {
        int format;
        // 1 it was made for this ROM, 0 it can't tell (IPS, and xdelta until applied), -1 a
        // different ROM.
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
            default: return "?";
        }
    }

    static int detect(byte[] patch) throws IOException {
        if (startsWith(patch, "PATCH")) return IPS;
        if (startsWith(patch, "UPS1")) return UPS;
        if (startsWith(patch, "BPS1")) return BPS;
        if (patch.length >= 4 && (patch[0] & 0xFF) == 0xD6 && (patch[1] & 0xFF) == 0xC3 && (patch[2] & 0xFF) == 0xC4) return XDELTA;
        throw new IOException("패치 파일이 아니에요. IPS · UPS · BPS · xdelta 파일을 골라 주세요.");
    }

    static Check check(byte[] patch, File rom) throws IOException {
        Check result = new Check();
        result.format = detect(patch);
        if (result.format == BPS || result.format == UPS) {
            if (patch.length < 16) {
                throw new IOException("패치 파일이 손상됐어요.");
            }
            if (crc(patch, 0, patch.length - 4) != u32le(patch, patch.length - 4)) {
                throw new IOException("패치 파일이 손상됐어요 (검사값이 맞지 않아요).");
            }
            long expected = u32le(patch, patch.length - 12);
            long length = rom.length();
            if (crc(rom, 0, length) == expected) {
                result.sourceMatches = 1;
            } else if (length % 1024 == 512 && crc(rom, 512, length - 512) == expected) {
                result.sourceMatches = 1;
                result.skip = 512;
            } else {
                result.sourceMatches = -1;
            }
        }
        return result;
    }

    static void apply(byte[] patch, File rom, Check check, File out) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r"); FileChannel source = in.getChannel()) {
            long sourceLength = source.size() - check.skip;
            if (sourceLength > Integer.MAX_VALUE) {
                throw new IOException("2GB보다 큰 롬은 패치할 수 없어요.");
            }
            MappedByteBuffer src = source.map(FileChannel.MapMode.READ_ONLY, check.skip, sourceLength);
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
                default:
                    applyXdelta(patch, src, out);
                    break;
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

    private static void applyIps(byte[] p, ByteBuffer src, File out) throws IOException {
        // First pass: how long the result is.
        long size = src.capacity();
        int pos = 5;
        while (true) {
            if (pos + 3 > p.length) throw new IOException("패치 파일이 손상됐어요.");
            int offset = u24be(p, pos);
            if (offset == 0x454F46 && pos + 3 <= p.length && (pos + 3 == p.length || pos + 6 == p.length)) {
                pos += 3;
                break;
            }
            int length = u16be(p, pos + 3);
            pos += 5;
            if (length == 0) {
                length = u16be(p, pos);
                pos += 3;
            } else {
                pos += length;
            }
            size = Math.max(size, (long) offset + length);
        }
        if (pos + 3 <= p.length) {
            size = u24be(p, pos);  // Truncation to this length.
        }
        ByteBuffer dst = createOutput(out, size);
        copy(src, 0, dst, 0, (int) Math.min(size, src.capacity()));
        pos = 5;
        while (true) {
            int offset = u24be(p, pos);
            if (offset == 0x454F46 && (pos + 3 == p.length || pos + 6 == p.length)) {
                break;
            }
            int length = u16be(p, pos + 3);
            pos += 5;
            if (length == 0) {
                length = u16be(p, pos);
                byte value = p[pos + 2];
                pos += 3;
                for (int k = 0; k < length && offset + k < size; k++) dst.put(offset + k, value);
            } else {
                for (int k = 0; k < length && offset + k < size; k++) dst.put(offset + k, p[pos + k]);
                pos += length;
            }
        }
        force(dst);
    }

    // ---- UPS ----

    private static void applyUps(byte[] p, ByteBuffer src, File out) throws IOException {
        int[] at = {4};
        long inSize = varint(p, at);
        long outSize = varint(p, at);
        if (inSize != src.capacity()) {
            throw new IOException("이 패치는 크기가 다른 롬용이에요.");
        }
        ByteBuffer dst = createOutput(out, outSize);
        copy(src, 0, dst, 0, (int) Math.min(outSize, src.capacity()));
        long target = 0;
        int end = p.length - 12;
        while (at[0] < end) {
            target += varint(p, at);
            while (at[0] < end) {
                byte x = p[at[0]++];
                if (target < outSize) {
                    dst.put((int) target, (byte) (dst.get((int) target) ^ x));
                }
                target++;
                if (x == 0) break;
            }
        }
        force(dst);
        if (crc(dst, outSize) != u32le(p, p.length - 8)) {
            throw new IOException("패치한 결과가 맞지 않아요. 이 롬용 패치가 아니에요.");
        }
    }

    // ---- BPS ----

    private static void applyBps(byte[] p, ByteBuffer src, File out) throws IOException {
        int[] at = {4};
        varint(p, at);  // Source size.
        long targetSize = varint(p, at);
        long metadata = varint(p, at);
        at[0] += (int) metadata;
        ByteBuffer dst = createOutput(out, targetSize);
        int output = 0;
        long sourceRelative = 0;
        long targetRelative = 0;
        int end = p.length - 12;
        while (at[0] < end) {
            long data = varint(p, at);
            int command = (int) (data & 3);
            int length = (int) ((data >> 2) + 1);
            if (command == 0) {
                copy(src, output, dst, output, length);
                output += length;
            } else if (command == 1) {
                for (int k = 0; k < length; k++) dst.put(output++, p[at[0]++]);
            } else if (command == 2) {
                long offset = varint(p, at);
                sourceRelative += ((offset & 1) != 0 ? -1 : 1) * (offset >> 1);
                copy(src, (int) sourceRelative, dst, output, length);
                output += length;
                sourceRelative += length;
            } else {
                long offset = varint(p, at);
                targetRelative += ((offset & 1) != 0 ? -1 : 1) * (offset >> 1);
                // Byte by byte: the copy may overlap what it is writing (a repeated run).
                for (int k = 0; k < length; k++) dst.put(output++, dst.get((int) targetRelative++));
            }
        }
        force(dst);
        if (crc(dst, targetSize) != u32le(p, p.length - 8)) {
            throw new IOException("패치한 결과가 맞지 않아요. 이 롬용 패치가 아니에요.");
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

    private static void applyXdelta(byte[] p, ByteBuffer src, File out) throws IOException {
        int indicator = p[4] & 0xFF;
        int[] at = {5};
        boolean lzma = false;
        if ((indicator & 0x01) != 0) {
            int compressor = p[at[0]++] & 0xFF;
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
            at[0] += (int) length;
        }
        int firstWindow = at[0];
        // First pass: the total size of the result.
        long total = 0;
        while (at[0] < p.length) {
            int win = p[at[0]++] & 0xFF;
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
            at[0] += (int) sections;
        }
        ByteBuffer dst = createOutput(out, total);
        at[0] = firstWindow;
        int output = 0;
        int[] near = new int[4];
        int[] same = new int[3 * 256];
        while (at[0] < p.length) {
            int win = p[at[0]++] & 0xFF;
            long segmentLength = 0;
            long segmentPosition = 0;
            boolean fromTarget = (win & 0x02) != 0;
            if ((win & 0x03) != 0) {
                segmentLength = vcdInt(p, at);
                segmentPosition = vcdInt(p, at);
            }
            vcdInt(p, at);  // Length of the delta encoding.
            int windowLength = (int) vcdInt(p, at);
            int delta = p[at[0]++] & 0xFF;
            if (delta != 0 && !lzma) {
                throw new IOException("패치 파일이 손상됐어요.");
            }
            int dataLength = (int) vcdInt(p, at);
            int instLength = (int) vcdInt(p, at);
            int addrLength = (int) vcdInt(p, at);
            long adler = -1;
            if ((win & 0x04) != 0) {
                adler = ((p[at[0]] & 0xFFL) << 24) | ((p[at[0] + 1] & 0xFFL) << 16) | ((p[at[0] + 2] & 0xFFL) << 8) | (p[at[0] + 3] & 0xFFL);
                at[0] += 4;
            }
            byte[] data = section(p, at[0], dataLength, (delta & 0x01) != 0);
            byte[] inst = section(p, at[0] + dataLength, instLength, (delta & 0x02) != 0);
            byte[] addr = section(p, at[0] + dataLength + instLength, addrLength, (delta & 0x04) != 0);
            at[0] += dataLength + instLength + addrLength;
            int[] d = {0};
            int[] i = {0};
            int[] a = {0};
            int windowStart = output;
            int windowEnd = windowStart + windowLength;
            java.util.Arrays.fill(near, 0);
            java.util.Arrays.fill(same, 0);
            int nextSlot = 0;
            int segLen = (int) segmentLength;
            int segPos = (int) segmentPosition;
            while (i[0] < inst.length) {
                int[] code = CODE_TABLE[inst[i[0]++] & 0xFF];
                for (int half = 0; half < 2; half++) {
                    int type = code[half * 3];
                    if (type == NOOP) continue;
                    int size = code[half * 3 + 1];
                    if (size == 0) size = (int) vcdInt(inst, i);
                    if (output + size > windowEnd) throw new IOException("패치 파일이 손상됐어요.");
                    if (type == ADD) {
                        for (int k = 0; k < size; k++) dst.put(output++, data[d[0]++]);
                    } else if (type == RUN) {
                        byte value = data[d[0]++];
                        for (int k = 0; k < size; k++) dst.put(output++, value);
                    } else {
                        int mode = code[half * 3 + 2];
                        int here = segLen + (output - windowStart);
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
                        for (int k = 0; k < size; k++) {
                            int from = address + k;
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
            if (adler >= 0 && adler32(dst, windowStart, windowLength) != adler) {
                throw new IOException("원본 롬이 이 패치와 맞지 않아요. 패치 설명에 적힌 원본 롬이 필요해요.");
            }
            output = windowEnd;
        }
        force(dst);
    }

    // One section of a window, unpacked when xdelta compressed it with LZMA: the unpacked size
    // as a VCDIFF number, then an .xz stream.
    private static byte[] section(byte[] p, int offset, int length, boolean compressed) throws IOException {
        if (!compressed) {
            byte[] plain = new byte[length];
            System.arraycopy(p, offset, plain, 0, length);
            return plain;
        }
        int[] at = {offset};
        long size = vcdInt(p, at);
        if (size > Integer.MAX_VALUE) throw new IOException("패치 파일이 손상됐어요.");
        byte[] result = new byte[(int) size];
        try (org.tukaani.xz.XZInputStream in = new org.tukaani.xz.XZInputStream(
                new java.io.ByteArrayInputStream(p, at[0], offset + length - at[0]))) {
            int done = 0;
            while (done < result.length) {
                int n = in.read(result, done, result.length - done);
                if (n < 0) throw new IOException("패치 파일이 손상됐어요.");
                done += n;
            }
        }
        return result;
    }

    // ---- Helpers ----

    private static ByteBuffer createOutput(File out, long size) throws IOException {
        if (size > Integer.MAX_VALUE) {
            throw new IOException("결과가 2GB보다 커서 만들 수 없어요.");
        }
        try (RandomAccessFile file = new RandomAccessFile(out, "rw")) {
            file.setLength(size);
            return file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
        }
    }

    private static void force(ByteBuffer buffer) {
        if (buffer instanceof MappedByteBuffer) {
            ((MappedByteBuffer) buffer).force();
        }
    }

    private static void copy(ByteBuffer from, int fromOffset, ByteBuffer to, int toOffset, int length) {
        byte[] chunk = new byte[Math.min(length, 1 << 20)];
        int done = 0;
        while (done < length) {
            int n = Math.min(chunk.length, length - done);
            ByteBuffer a = from.duplicate();
            a.position(fromOffset + done);
            a.get(chunk, 0, n);
            ByteBuffer b = to.duplicate();
            b.position(toOffset + done);
            b.put(chunk, 0, n);
            done += n;
        }
    }

    private static long crc(ByteBuffer buffer, long length) {
        CRC32 crc = new CRC32();
        byte[] chunk = new byte[1 << 20];
        long done = 0;
        while (done < length) {
            int n = (int) Math.min(chunk.length, length - done);
            ByteBuffer view = buffer.duplicate();
            view.position((int) done);
            view.get(chunk, 0, n);
            crc.update(chunk, 0, n);
            done += n;
        }
        return crc.getValue();
    }

    private static long crc(File file, long skip, long length) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream in = new FileInputStream(file)) {
            long skipped = 0;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] chunk = new byte[1 << 20];
            long left = length;
            int n;
            while (left > 0 && (n = in.read(chunk, 0, (int) Math.min(chunk.length, left))) > 0) {
                crc.update(chunk, 0, n);
                left -= n;
            }
        }
        return crc.getValue();
    }

    private static long crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }

    private static long adler32(ByteBuffer buffer, int offset, int length) {
        Adler32 adler = new Adler32();
        byte[] chunk = new byte[Math.min(Math.max(length, 1), 1 << 20)];
        int done = 0;
        while (done < length) {
            int n = Math.min(chunk.length, length - done);
            ByteBuffer view = buffer.duplicate();
            view.position(offset + done);
            view.get(chunk, 0, n);
            adler.update(chunk, 0, n);
            done += n;
        }
        return adler.getValue();
    }

    // UPS and BPS numbers: 7 bits a byte, the last byte has the top bit set.
    private static long varint(byte[] p, int[] at) {
        long data = 0;
        long shift = 1;
        while (true) {
            int x = p[at[0]++] & 0xFF;
            data += (x & 0x7F) * shift;
            if ((x & 0x80) != 0) break;
            shift <<= 7;
            data += shift;
        }
        return data;
    }

    // VCDIFF numbers: 7 bits a byte, most significant first, the top bit set on all but the last.
    private static long vcdInt(byte[] p, int[] at) {
        long value = 0;
        while (true) {
            int x = p[at[0]++] & 0xFF;
            value = (value << 7) | (x & 0x7F);
            if ((x & 0x80) == 0) break;
        }
        return value;
    }

    private static boolean startsWith(byte[] bytes, String magic) {
        if (bytes.length < magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) {
            if (bytes[i] != (byte) magic.charAt(i)) return false;
        }
        return true;
    }

    private static long u32le(byte[] p, int at) {
        return (p[at] & 0xFFL) | ((p[at + 1] & 0xFFL) << 8) | ((p[at + 2] & 0xFFL) << 16) | ((p[at + 3] & 0xFFL) << 24);
    }

    private static int u24be(byte[] p, int at) {
        return ((p[at] & 0xFF) << 16) | ((p[at + 1] & 0xFF) << 8) | (p[at + 2] & 0xFF);
    }

    private static int u16be(byte[] p, int at) {
        return ((p[at] & 0xFF) << 8) | (p[at + 1] & 0xFF);
    }
}
