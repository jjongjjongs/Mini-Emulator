package com.example.minigba;

// A raw CD-ROM Mode 1 sector (2352 bytes) around 2048 bytes of data at offset 16: the sync
// pattern, the address, the EDC checksum and the P/Q error correction codes. The tables and
// the layout follow the CD-ROM standard (ECMA-130).
final class CdSector {
    private static final byte[] ECC_F = new byte[256];
    private static final byte[] ECC_B = new byte[256];
    private static final int[] EDC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int j = (i << 1) ^ ((i & 0x80) != 0 ? 0x11D : 0);
            ECC_F[i] = (byte) j;
            ECC_B[i ^ j] = (byte) i;
            int edc = i;
            for (int k = 0; k < 8; k++) {
                edc = (edc >>> 1) ^ ((edc & 1) != 0 ? 0xD8018001 : 0);
            }
            EDC[i] = edc;
        }
    }

    private CdSector() {
    }

    static final int SIZE = 2352;
    // Minutes past 99 (GD-ROM's high-density area): "hex tens" (100 -> 0xA0, as mode1 writes
    // them) or the BCD of minutes mod 100.
    static final int MINUTES_HEX_TENS = 0;
    static final int MINUTES_MOD_100 = 1;

    // An empty Mode 1 sector at lba (the pregap a Redump track starts with, 0.0.308).
    static byte[] emptyMode1(int lba, int minuteStyle) {
        byte[] sector = new byte[SIZE];
        mode1(sector, lba);
        if (minuteStyle == MINUTES_MOD_100) {
            int minutes = (lba + 150) / (60 * 75);
            if (minutes > 99) {
                mode1(sector, lba - 100 * 60 * 75);   // same seconds and frame, minutes mod 100
            }
        }
        return sector;
    }

    // Fills in everything but the data of sector (2352 bytes) for disc position lba.
    static void mode1(byte[] sector, long lba) {
        sector[0] = 0;
        for (int k = 1; k < 11; k++) {
            sector[k] = (byte) 0xFF;
        }
        sector[11] = 0;
        long address = lba + 150;
        sector[12] = bcd((int) (address / (60 * 75)));
        sector[13] = bcd((int) (address / 75 % 60));
        sector[14] = bcd((int) (address % 75));
        sector[15] = 1;
        int edc = 0;
        for (int k = 0; k < 0x810; k++) {
            edc = (edc >>> 8) ^ EDC[(edc ^ sector[k]) & 0xFF];
        }
        sector[0x810] = (byte) edc;
        sector[0x811] = (byte) (edc >>> 8);
        sector[0x812] = (byte) (edc >>> 16);
        sector[0x813] = (byte) (edc >>> 24);
        for (int k = 0x814; k < 0x81C; k++) {
            sector[k] = 0;
        }
        block(sector, 86, 24, 2, 86, 0x81C);
        block(sector, 52, 43, 86, 88, 0x8C8);
    }

    private static byte bcd(int value) {
        return (byte) ((value / 10) << 4 | (value % 10));
    }

    // One set of Reed-Solomon codes over the bytes from 12 (P: 86 columns of 24, Q: 52
    // diagonals of 43), written at dest.
    private static void block(byte[] sector, int majorCount, int minorCount, int majorMult, int minorInc, int dest) {
        int size = majorCount * minorCount;
        for (int major = 0; major < majorCount; major++) {
            int index = (major >> 1) * majorMult + (major & 1);
            int a = 0;
            int b = 0;
            for (int minor = 0; minor < minorCount; minor++) {
                int temp = sector[12 + index] & 0xFF;
                index += minorInc;
                if (index >= size) {
                    index -= size;
                }
                a ^= temp;
                b ^= temp;
                a = ECC_F[a] & 0xFF;
            }
            a = ECC_B[(ECC_F[a] & 0xFF) ^ b] & 0xFF;
            sector[dest + major] = (byte) a;
            sector[dest + major + majorCount] = (byte) (a ^ b);
        }
    }
}
