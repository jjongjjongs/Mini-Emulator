package com.example.minigba;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PS2, .cue / .bin 바꿔 넣기 (0.0.380): a disc dumped as raw CD sectors (2352 bytes, or 2448
 * with the subchannel after each) becomes a plain .iso of the 2048-byte user data, which both
 * PS2 engines read. A .bin that is already 2048-byte sectors is taken as it is.
 */
final class Ps2BinImport {
    static final int RAW = 2352;
    static final int RAW_SUB = 2448;
    static final int USER = 2048;

    private Ps2BinImport() {
    }

    /** How a .bin's sectors are laid out. */
    static final class Layout {
        final int sectorSize;  // 2352, 2448, or 2048 (already plain)
        final int mode;        // 1 or 2 (raw sectors), 0 (plain)

        Layout(int sectorSize, int mode) {
            this.sectorSize = sectorSize;
            this.mode = mode;
        }

        boolean plain() {
            return this.sectorSize == USER;
        }

        long sectors(long size) {
            return size / this.sectorSize;
        }

        long isoSize(long size) {
            return sectors(size) * USER;
        }

        String describe() {
            if (plain()) {
                return "게임 데이터 2048바이트 섹터 (이미 ISO와 같은 모양)";
            }
            return "CD 원본 섹터 " + this.sectorSize + "바이트 · Mode " + this.mode + " → 게임 데이터 2048바이트만 꺼냄";
        }
    }

    private static boolean syncAt(byte[] head, int at, int got) {
        if (at + 16 > got) {
            return false;
        }
        if (head[at] != 0 || head[at + 11] != 0) {
            return false;
        }
        for (int k = 1; k <= 10; k++) {
            if (head[at + k] != (byte) 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** The layout from the first two sectors and the size, or null when it is no disc image. */
    static Layout detect(byte[] head, int got, long size) {
        if (syncAt(head, 0, got)) {
            int mode = head[15] & 0xFF;
            if (mode != 1 && mode != 2) {
                return null;
            }
            if (size % RAW_SUB == 0 && syncAt(head, RAW_SUB, got) && !syncAt(head, RAW, got)) {
                return new Layout(RAW_SUB, mode);
            }
            if (size % RAW == 0) {
                return new Layout(RAW, mode);
            }
            return null;
        }
        if (size > 0 && size % USER == 0) {
            return new Layout(USER, 0);
        }
        return null;
    }

    /** Where the 2048 bytes start inside a raw sector of this mode (Mode 2 Form 1: after the subheader). */
    private static int dataOffset(int mode) {
        return mode == 2 ? 24 : 16;
    }

    interface Progress {
        /** Bytes of the .iso written so far; false stops. */
        boolean at(long written);
    }

    /** Copies the user data of every sector; a sector without the sync pattern stops it. */
    static void convert(InputStream in, OutputStream out, Layout layout, long size, Progress progress) throws IOException {
        long sectors = layout.sectors(size);
        int sectorSize = layout.sectorSize;
        int batch = 256;
        byte[] buffer = new byte[sectorSize * batch];
        byte[] data = new byte[USER * batch];
        long done = 0;
        while (done < sectors) {
            int want = (int) Math.min(batch, sectors - done);
            int need = want * sectorSize;
            int got = 0;
            while (got < need) {
                int n = in.read(buffer, got, need - got);
                if (n < 0) {
                    throw new IOException("파일이 중간에 끝났어요 (섹터 " + (done + got / sectorSize) + ")");
                }
                got += n;
            }
            if (layout.plain()) {
                out.write(buffer, 0, need);
            } else {
                int offset = dataOffset(layout.mode);
                for (int k = 0; k < want; k++) {
                    int at = k * sectorSize;
                    if (!syncAt(buffer, at, need)) {
                        throw new IOException("깨진 섹터가 있어요 (섹터 " + (done + k) + ")");
                    }
                    int mode = buffer[at + 15] & 0xFF;
                    System.arraycopy(buffer, at + (mode == 2 ? 24 : mode == 1 ? 16 : offset), data, k * USER, USER);
                }
                out.write(data, 0, want * USER);
            }
            done += want;
            if (progress != null && !progress.at(done * USER)) {
                throw new IOException("취소했어요");
            }
        }
    }

    /** A cue sheet's data track: the file it names and whether more tracks follow. */
    static final class Cue {
        final String file;
        final int tracks;
        final boolean dataFirst;

        Cue(String file, int tracks, boolean dataFirst) {
            this.file = file;
            this.tracks = tracks;
            this.dataFirst = dataFirst;
        }
    }

    private static final Pattern FILE = Pattern.compile("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK = Pattern.compile("^\\s*TRACK\\s+\\d+\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    static Cue parseCue(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.indexOf('�') >= 0) {
            text = new String(bytes, java.nio.charset.Charset.forName("MS949"));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String first = null;
        int tracks = 0;
        boolean dataFirst = false;
        for (String line : text.split("\r?\n|\r")) {
            Matcher file = FILE.matcher(line);
            if (file.find() && first == null) {
                first = file.group(1) != null ? file.group(1) : file.group(2);
            }
            Matcher track = TRACK.matcher(line);
            if (track.find()) {
                if (tracks == 0) {
                    dataFirst = !track.group(1).toUpperCase(Locale.ROOT).startsWith("AUDIO");
                }
                tracks++;
            }
        }
        if (first == null) {
            return null;
        }
        // Only the name: a path in the sheet means the folder it was made in.
        first = first.replace('\\', '/');
        first = first.substring(first.lastIndexOf('/') + 1);
        return new Cue(first, tracks, dataFirst);
    }
}
