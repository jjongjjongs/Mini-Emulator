package com.example.minigba;

import java.io.IOException;
import java.io.InputStream;

// A bzip2 decompressor (for BDF / bsdiff patches), block by block as it is read: Huffman
// coded move-to-front symbols, the Burrows-Wheeler transform undone, then the runs of four
// expanded. Written from the bzip2 format as bzip2 1.0 makes it; randomised blocks (made
// only by bzip2 0.9.0) are not supported. Concatenated streams are read one after another.
final class Bzip2 extends InputStream {
    private static final int[] CRC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            }
            CRC[i] = c;
        }
    }

    private final InputStream in;
    private long bitBuffer;
    private int bitCount;
    private int blockSize100k;
    private boolean ended;

    // The current block, after the transform: bytes to hand out, with the runs of four.
    private int[] tt = new int[0];
    private byte[] ll = new byte[0];
    private int remaining;
    private int tPos;
    private int last = -1;
    private int sameCount;
    private int repeat;
    private int repeatByte;
    private int blockCrc;
    private int expectedCrc;
    private boolean inBlock;

    Bzip2(InputStream in) throws IOException {
        this.in = in;
        startStream(true);
    }

    private static IOException corrupt() {
        return new IOException("패치 파일의 압축(bzip2)을 풀 수 없어요. 패치 파일이 손상됐을 수 있어요.");
    }

    private int bits(int n) throws IOException {
        while (bitCount < n) {
            int b = in.read();
            if (b < 0) {
                throw corrupt();
            }
            bitBuffer = (bitBuffer << 8) | b;
            bitCount += 8;
        }
        bitCount -= n;
        return (int) ((bitBuffer >>> bitCount) & ((1L << n) - 1));
    }

    private boolean bit() throws IOException {
        return bits(1) == 1;
    }

    // "BZh" and the block size; false at the end of the input (when another stream may follow).
    private boolean startStream(boolean first) throws IOException {
        bitCount = 0;
        int b = in.read();
        if (b < 0 && !first) {
            return false;
        }
        if (b != 'B' || in.read() != 'Z' || in.read() != 'h') {
            throw corrupt();
        }
        int level = in.read() - '0';
        if (level < 1 || level > 9) {
            throw corrupt();
        }
        blockSize100k = level;
        return true;
    }

    // Reads the next block; false at the end.
    private boolean nextBlock() throws IOException {
        while (true) {
            long magic = ((long) bits(24) << 24) | bits(24);
            if (magic == 0x177245385090L) {
                bits(32);  // The stream's combined CRC; each block's own is checked.
                if (!startStream(false)) {
                    return false;
                }
                continue;
            }
            if (magic != 0x314159265359L) {
                throw corrupt();
            }
            break;
        }
        expectedCrc = bits(32);
        if (bit()) {
            throw new IOException("이 bzip2 압축(오래된 randomised 방식)은 지원하지 않아요.");
        }
        int origPtr = bits(24);
        // The bytes in use.
        boolean[] inUse = new boolean[256];
        int ranges = bits(16);
        int used = 0;
        byte[] seqToUnseq = new byte[256];
        for (int i = 0; i < 16; i++) {
            if ((ranges & (0x8000 >>> i)) != 0) {
                int set = bits(16);
                for (int j = 0; j < 16; j++) {
                    if ((set & (0x8000 >>> j)) != 0) {
                        inUse[i * 16 + j] = true;
                    }
                }
            }
        }
        for (int i = 0; i < 256; i++) {
            if (inUse[i]) {
                seqToUnseq[used++] = (byte) i;
            }
        }
        if (used == 0) {
            throw corrupt();
        }
        int alphaSize = used + 2;
        int groups = bits(3);
        if (groups < 2 || groups > 6) {
            throw corrupt();
        }
        int selectors = bits(15);
        if (selectors < 1) {
            throw corrupt();
        }
        int[] mtfGroups = new int[groups];
        for (int i = 0; i < groups; i++) {
            mtfGroups[i] = i;
        }
        byte[] selector = new byte[selectors];
        for (int i = 0; i < selectors; i++) {
            int j = 0;
            while (bit()) {
                j++;
                if (j >= groups) {
                    throw corrupt();
                }
            }
            int v = mtfGroups[j];
            System.arraycopy(mtfGroups, 0, mtfGroups, 1, j);
            mtfGroups[0] = v;
            selector[i] = (byte) v;
        }
        // The code lengths of each table, as deltas.
        int[][] limit = new int[groups][22];
        int[][] firstCode = new int[groups][22];
        int[][] firstIndex = new int[groups][22];
        int[][] perm = new int[groups][alphaSize];
        int[] minLen = new int[groups];
        int[] maxLen = new int[groups];
        for (int t = 0; t < groups; t++) {
            int[] len = new int[alphaSize];
            int curr = bits(5);
            for (int i = 0; i < alphaSize; i++) {
                while (true) {
                    if (curr < 1 || curr > 20) {
                        throw corrupt();
                    }
                    if (!bit()) {
                        break;
                    }
                    curr += bit() ? -1 : 1;
                }
                len[i] = curr;
            }
            int min = 32;
            int max = 0;
            for (int l : len) {
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
            minLen[t] = min;
            maxLen[t] = max;
            int p = 0;
            for (int l = min; l <= max; l++) {
                for (int i = 0; i < alphaSize; i++) {
                    if (len[i] == l) {
                        perm[t][p++] = i;
                    }
                }
            }
            int code = 0;
            int index = 0;
            for (int l = min; l <= max; l++) {
                int count = 0;
                for (int i = 0; i < alphaSize; i++) {
                    if (len[i] == l) {
                        count++;
                    }
                }
                firstCode[t][l] = code;
                firstIndex[t][l] = index;
                limit[t][l] = code + count - 1;
                code = (code + count) << 1;
                index += count;
            }
        }
        // The move-to-front symbols, with RUNA / RUNB runs, to the block's bytes.
        int capacity = blockSize100k * 100000;
        if (ll.length < capacity) {
            ll = new byte[capacity];
            tt = new int[capacity];
        }
        int[] counts = new int[256];
        int[] mtf = new int[256];
        for (int i = 0; i < 256; i++) {
            mtf[i] = i;
        }
        int n = 0;
        int group = -1;
        int groupLeft = 0;
        int run = 0;
        int runWeight = 1;
        int eob = alphaSize - 1;
        while (true) {
            if (groupLeft == 0) {
                group++;
                if (group >= selectors) {
                    throw corrupt();
                }
                groupLeft = 50;
            }
            groupLeft--;
            int t = selector[group];
            int l = minLen[t];
            int v = bits(l);
            while (v > limit[t][l] || limit[t][l] < firstCode[t][l]) {
                l++;
                if (l > maxLen[t]) {
                    throw corrupt();
                }
                v = (v << 1) | bits(1);
            }
            int sym = perm[t][firstIndex[t][l] + v - firstCode[t][l]];
            if (sym <= 1) {
                run += (sym + 1) * runWeight;
                runWeight <<= 1;
                if (run > capacity) {
                    throw corrupt();
                }
                continue;
            }
            if (run > 0) {
                byte b = seqToUnseq[mtf[0]];
                if (n + run > capacity) {
                    throw corrupt();
                }
                counts[b & 0xFF] += run;
                for (; run > 0; run--) {
                    ll[n++] = b;
                }
                runWeight = 1;
            }
            if (sym == eob) {
                break;
            }
            int k = sym - 1;
            int value = mtf[k];
            System.arraycopy(mtf, 0, mtf, 1, k);
            mtf[0] = value;
            if (n >= capacity) {
                throw corrupt();
            }
            byte b = seqToUnseq[value];
            counts[b & 0xFF]++;
            ll[n++] = b;
        }
        if (origPtr >= n) {
            throw corrupt();
        }
        // The transform undone: where each byte goes next.
        int[] cftab = new int[256];
        int sum = 0;
        for (int i = 0; i < 256; i++) {
            cftab[i] = sum;
            sum += counts[i];
        }
        for (int i = 0; i < n; i++) {
            tt[cftab[ll[i] & 0xFF]++] = i;
        }
        tPos = tt[origPtr];
        remaining = n;
        last = -1;
        sameCount = 0;
        repeat = 0;
        blockCrc = 0xFFFFFFFF;
        inBlock = true;
        return true;
    }

    private void emit(int b) {
        blockCrc = (blockCrc << 8) ^ CRC[((blockCrc >>> 24) ^ b) & 0xFF];
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] out, int at, int length) throws IOException {
        int done = 0;
        while (done < length) {
            if (repeat > 0) {
                out[at + done++] = (byte) repeatByte;
                emit(repeatByte);
                repeat--;
                continue;
            }
            if (remaining == 0) {
                if (inBlock) {
                    // A block finished: its CRC.
                    inBlock = false;
                    if (~blockCrc != expectedCrc) {
                        throw new IOException("패치 파일의 압축(bzip2) 검사값이 맞지 않아요. 패치 파일이 손상됐을 수 있어요.");
                    }
                }
                if (done > 0) {
                    return done;
                }
                if (ended || !nextBlock()) {
                    ended = true;
                    return -1;
                }
                continue;
            }
            int b = ll[tPos] & 0xFF;
            tPos = tt[tPos];
            remaining--;
            if (sameCount == 4) {
                // The byte after four the same: how many more of them.
                repeat = b;
                repeatByte = last;
                sameCount = 0;
                last = -1;
                continue;
            }
            if (b == last) {
                sameCount++;
            } else {
                last = b;
                sameCount = 1;
            }
            out[at + done++] = (byte) b;
            emit(b);
        }
        return done;
    }
}
