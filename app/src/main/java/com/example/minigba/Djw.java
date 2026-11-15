package com.example.minigba;

import java.io.IOException;

// xdelta3's DJW secondary compression (its "-S djw", the default of many patch tools): static
// Huffman codes, up to 8 tables chosen per run of bytes, with the code lengths themselves
// coded through move-to-front and run-length codes. A port of the decoder in xdelta3's
// xdelta3-djw.h (Apache-2.0). Every section is coded on its own.
final class Djw {
    private static final int ALPHABET = 256;
    private static final int MAX_CODELEN = 20;
    private static final int TOTAL_CODES = MAX_CODELEN + 2;
    private static final int EXTRA_12OFFSET = 7;
    private static final int MAX_GBCLEN = 7;
    private static final int MAX_CLCLEN = 15;
    private static final int[] CLEN_MTF = {0, 4, 5, 6, 7, 8, 9, 10, 3, 11, 2, 12, 13, 1, 14, 15, 16, 17, 18, 19, 20};

    private final byte[] in;
    private int pos;
    private final int end;
    private int curByte;
    private int curMask = 0x100;

    private Djw(byte[] in, int offset, int length) {
        this.in = in;
        this.pos = offset;
        this.end = offset + length;
    }

    // One decoding table: canonical codes, by length.
    private static final class Table {
        final int[] inorder;
        final long[] base = new long[TOTAL_CODES];
        final long[] limit = new long[TOTAL_CODES];
        int minLen;
        int maxLen;

        Table(int size) {
            inorder = new int[size];
        }
    }

    static byte[] decode(byte[] in, int offset, int length, int outSize) throws IOException {
        if (outSize <= 0) {
            throw corrupt();
        }
        return new Djw(in, offset, length).run(outSize);
    }

    private static IOException corrupt() {
        return new IOException("패치 파일의 압축(DJW)을 풀 수 없어요. 패치 파일이 손상됐을 수 있어요.");
    }

    private void nextByte() throws IOException {
        if (pos >= end) {
            throw corrupt();
        }
        curByte = in[pos++] & 0xFF;
        curMask = 1;
    }

    private int bits(int count) throws IOException {
        int value = 0;
        int mask = 1 << count;
        while (true) {
            if (curMask == 0x100) {
                nextByte();
            }
            mask >>= 1;
            if ((curByte & curMask) != 0) {
                value |= mask;
            }
            curMask <<= 1;
            if (mask == 1) {
                return value;
            }
        }
    }

    private int symbol(Table t, int maxSym) throws IOException {
        long code = 0;
        int length = 0;
        while (true) {
            if (curMask == 0x100) {
                nextByte();
            }
            if (length == t.maxLen) {
                throw corrupt();
            }
            length++;
            code <<= 1;
            if ((curByte & curMask) != 0) {
                code |= 1;
            }
            curMask <<= 1;
            if (length >= t.minLen && code <= t.limit[length]) {
                break;
            }
        }
        long base = t.base[length];
        if (base >= 0 && base <= code) {
            long offset = code - base;
            if (offset <= maxSym && offset < t.inorder.length) {
                return t.inorder[(int) offset];
            }
        }
        throw corrupt();
    }

    private static Table build(int size, int absMax, byte[] clen, int clenOffset) throws IOException {
        Table t = new Table(size);
        int[] count = new int[TOTAL_CODES];
        for (int i = 0; i < size; i++) {
            int l = clen[clenOffset + i];
            if (l < 0 || l > absMax) {
                throw corrupt();
            }
            count[l]++;
        }
        int min = 1;
        while (min <= absMax && count[min] == 0) {
            min++;
        }
        int max = absMax;
        while (max != 0 && count[max] == 0) {
            max--;
        }
        if (min > max) {
            throw corrupt();
        }
        long[] tmpBase = new long[TOTAL_CODES];
        t.base[min] = 0;
        t.limit[min] = count[min] - 1;
        for (int i = min + 1; i <= max; i++) {
            long lastLimit = (t.limit[i - 1] + 1) << 1;
            tmpBase[i] = tmpBase[i - 1] + count[i - 1];
            t.limit[i] = lastLimit + count[i] - 1;
            t.base[i] = lastLimit - tmpBase[i];
        }
        for (int i = 0; i < size; i++) {
            int l = clen[clenOffset + i];
            if (l != 0) {
                t.inorder[(int) tmpBase[l]++] = i;
            }
        }
        t.minLen = min;
        t.maxLen = max;
        return t;
    }

    // Values coded as move-to-front positions with RUN_0 / RUN_1 repeat codes; with skip,
    // a value whose counterpart skip earlier is 0 stays 0 without being coded.
    private void mtf12(Table t, int[] mtf, int count, int skip, byte[] values) throws IOException {
        int n = 0;
        long rep = 0;
        int next = 0;
        int shift = 0;
        while (n < count) {
            if (skip != 0 && n >= skip && values[n - skip] == 0) {
                values[n++] = 0;
                continue;
            }
            if (rep != 0) {
                values[n++] = (byte) mtf[0];
                rep--;
                continue;
            }
            if (next != 0) {
                int value = mtf[next];
                System.arraycopy(mtf, 0, mtf, 1, next);
                mtf[0] = value;
                values[n++] = (byte) value;
                next = 0;
                continue;
            }
            int s = symbol(t, TOTAL_CODES);
            if (s <= 1) {
                if (shift > 40) {
                    throw corrupt();
                }
                rep = (long) (s + 1) << shift;
                shift++;
            } else {
                next = s - 1;
                if (next >= mtf.length) {
                    throw corrupt();
                }
                shift = 0;
            }
        }
        if (rep != 0) {
            throw corrupt();
        }
    }

    private byte[] run(int outSize) throws IOException {
        int groups = bits(3) + 1;
        int sectorSize = groups > 1 ? (bits(5) + 1) * 5 : outSize;
        int sectors = 1 + (outSize - 1) / sectorSize;
        // The code lengths of the code lengths.
        int numCodes = bits(4) + EXTRA_12OFFSET;
        byte[] clClen = new byte[TOTAL_CODES];
        for (int i = 0; i < numCodes; i++) {
            clClen[i] = (byte) bits(4);
        }
        Table clTable = build(TOTAL_CODES, MAX_CLCLEN, clClen, 0);
        int[] clMtf = new int[TOTAL_CODES];
        System.arraycopy(CLEN_MTF, 0, clMtf, 0, CLEN_MTF.length);
        byte[] clen = new byte[groups * ALPHABET];
        mtf12(clTable, clMtf, groups * ALPHABET, ALPHABET, clen);
        Table[] tables = new Table[groups];
        for (int g = 0; g < groups; g++) {
            tables[g] = build(ALPHABET, MAX_CODELEN, clen, g * ALPHABET);
        }
        byte[] selector = null;
        if (groups > 1) {
            byte[] selClen = new byte[groups + 1];
            int[] selMtf = new int[groups + 2];
            for (int g = 0; g < groups + 1; g++) {
                selClen[g] = (byte) bits(3);
                selMtf[g] = g;
            }
            Table selTable = build(groups + 1, MAX_GBCLEN, selClen, 0);
            selector = new byte[sectors];
            mtf12(selTable, selMtf, sectors, 0, selector);
        }
        byte[] out = new byte[outSize];
        int o = 0;
        for (int c = 0; c < sectors; c++) {
            Table t = tables[0];
            if (selector != null) {
                int g = selector[c] & 0xFF;
                if (g >= groups) {
                    throw corrupt();
                }
                t = tables[g];
            }
            int n = Math.min(sectorSize, outSize - o);
            for (int k = 0; k < n; k++) {
                out[o++] = (byte) symbol(t, ALPHABET);
            }
        }
        return out;
    }
}
