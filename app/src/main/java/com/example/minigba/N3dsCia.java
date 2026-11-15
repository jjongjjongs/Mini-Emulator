package com.example.minigba;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 3DS CIA files and the titles Citra installs from them.
 *
 * A CIA holds a certificate chain, a ticket, a title metadata block (TMD) and the contents.
 * The TMD carries the title ID, whose upper half says what the title is: 00040000 a game,
 * 0004000E an update, 0004008C add-on content (DLC); the lower half names the game all three
 * belong to. Citra's installer (reached through the bridge) unpacks them into the user
 * directory's virtual SD card under Nintendo 3DS/<system id>/<sd id>/title/<high>/<low>/.
 */
final class N3dsCia {
    static final long HIGH_GAME = 0x00040000L;
    static final long HIGH_UPDATE = 0x0004000EL;
    static final long HIGH_DLC = 0x0004008CL;
    private static final String SYSTEM_ID = "00000000000000000000000000000000";

    /** What a CIA file holds, read from its TMD. */
    static final class Info {
        final File file;
        final String name;
        final long titleId;
        final int version;
        final long size;

        Info(File file, String name, long titleId, int version, long size) {
            this.file = file;
            this.name = name;
            this.titleId = titleId;
            this.version = version;
            this.size = size;
        }

        long high() {
            return (titleId >>> 32) & 0xFFFFFFFFL;
        }

        long low() {
            return titleId & 0xFFFFFFFFL;
        }

        boolean isGame() {
            return high() == HIGH_GAME;
        }

        boolean isUpdate() {
            return high() == HIGH_UPDATE;
        }

        boolean isDlc() {
            return high() == HIGH_DLC;
        }

        String kindName() {
            return isGame() ? "게임" : isUpdate() ? "업데이트" : isDlc() ? "DLC" : "기타 타이틀";
        }
    }

    /** An update or DLC title installed for a game. */
    static final class Installed {
        final File dir;
        final long titleId;
        final int version;
        final long size;

        Installed(File dir, long titleId, int version, long size) {
            this.dir = dir;
            this.titleId = titleId;
            this.version = version;
            this.size = size;
        }

        boolean isUpdate() {
            return ((titleId >>> 32) & 0xFFFFFFFFL) == HIGH_UPDATE;
        }
    }

    private N3dsCia() {
    }

    static String hex(long titleId) {
        return String.format(Locale.US, "%08X-%08X", (titleId >>> 32) & 0xFFFFFFFFL, titleId & 0xFFFFFFFFL);
    }

    static String versionName(int version) {
        // 3DS title versions pack major.minor.micro as 6.6.4 bits.
        return "v" + ((version >> 10) & 0x3F) + "." + ((version >> 4) & 0x3F) + "." + (version & 0xF);
    }

    // ---- CIA header ----

    /** Reads the title ID and version from a CIA's TMD; throws when the file is not a CIA. */
    static Info parse(File file, String shownName) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[0x20];
            in.readFully(header);
            long headerSize = le32(header, 0);
            long certSize = le32(header, 0x08);
            long ticketSize = le32(header, 0x0C);
            long tmdSize = le32(header, 0x10);
            if (headerSize != 0x2020 || tmdSize < 0x200 || tmdSize > 0x100000) {
                throw new IOException("CIA 파일이 아니에요");
            }
            long certOffset = align(headerSize);
            long ticketOffset = certOffset + align(certSize);
            long tmdOffset = ticketOffset + align(ticketSize);
            if (tmdOffset + tmdSize > in.length()) {
                throw new IOException("CIA 파일이 잘려 있어요");
            }
            byte[] tmd = new byte[(int) tmdSize];
            in.seek(tmdOffset);
            in.readFully(tmd);
            int body = signedBodyOffset(be32(tmd, 0));
            if (body < 0 || body + 0xA0 > tmd.length) {
                throw new IOException("TMD 서명 형식을 모르겠어요");
            }
            long titleId = be64(tmd, body + 0x4C);
            int version = be16(tmd, body + 0x9C);
            return new Info(file, shownName, titleId, version, in.length());
        }
    }

    // Title metadata (TMD) and tickets start with a 4-byte signature type, the signature
    // and padding up to a 0x40 boundary; the body follows.
    private static int signedBodyOffset(long signatureType) {
        if (signatureType == 0x10000L || signatureType == 0x10003L) return 4 + 0x200 + 0x3C;
        if (signatureType == 0x10001L || signatureType == 0x10004L) return 4 + 0x100 + 0x3C;
        if (signatureType == 0x10002L || signatureType == 0x10005L) return 4 + 0x3C + 0x40;
        return -1;
    }

    private static long align(long value) {
        return (value + 0x3F) & ~0x3FL;
    }

    // ---- Game files ----

    /** The program ID of a .3ds/.cci (first NCSD partition) or a bare NCCH (.cxi/.app); 0 when unreadable. */
    static long titleIdOf(File rom) {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            if (in.length() < 0x200) {
                return 0;
            }
            byte[] head = new byte[0x200];
            in.readFully(head);
            long ncch = -1;
            String magic = new String(head, 0x100, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (magic.equals("NCSD")) {
                ncch = le32(head, 0x120) * 0x200L;
            } else if (magic.equals("NCCH")) {
                ncch = 0;
            }
            if (ncch < 0 || ncch + 0x200 > in.length()) {
                return 0;
            }
            byte[] header = head;
            if (ncch != 0) {
                header = new byte[0x200];
                in.seek(ncch);
                in.readFully(header);
                if (!new String(header, 0x100, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("NCCH")) {
                    return 0;
                }
            }
            return le64(header, 0x118);
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- Installed titles ----

    /** The virtual SD card's title folder (the Citra user directory holds sdmc/). */
    static File titleRoot(File userDir) {
        return new File(new File(new File(new File(new File(userDir, "sdmc"), "Nintendo 3DS"), SYSTEM_ID), SYSTEM_ID), "title");
    }

    static File titleDir(File userDir, long titleId) {
        return new File(new File(titleRoot(userDir), String.format(Locale.US, "%08x", (titleId >>> 32) & 0xFFFFFFFFL)),
                String.format(Locale.US, "%08x", titleId & 0xFFFFFFFFL));
    }

    /** The executable content of an installed title (its lowest-numbered .app), or null. */
    static File contentApp(File titleDir) {
        File[] apps = new File(titleDir, "content").listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".app"));
        if (apps == null || apps.length == 0) {
            return null;
        }
        Arrays.sort(apps, Comparator.comparing(File::getName));
        return apps[0];
    }

    /** The version from the title's newest TMD, or -1. */
    static int installedVersion(File titleDir) {
        File[] tmds = new File(titleDir, "content").listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".tmd"));
        if (tmds == null || tmds.length == 0) {
            return -1;
        }
        Arrays.sort(tmds, Comparator.comparing(File::getName));
        File tmd = tmds[tmds.length - 1];
        try (RandomAccessFile in = new RandomAccessFile(tmd, "r")) {
            byte[] data = new byte[(int) Math.min(in.length(), 0x400)];
            in.readFully(data);
            int body = signedBodyOffset(be32(data, 0));
            if (body < 0 || body + 0x9E > data.length) {
                return -1;
            }
            return be16(data, body + 0x9C);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Updates and DLC installed for the game whose title ID has this lower half. */
    static List<Installed> installedFor(File userDir, long titleLow) {
        List<Installed> found = new ArrayList<>();
        for (long high : new long[]{HIGH_UPDATE, HIGH_DLC}) {
            long tid = (high << 32) | (titleLow & 0xFFFFFFFFL);
            File dir = titleDir(userDir, tid);
            if (new File(dir, "content").isDirectory()) {
                found.add(new Installed(dir, tid, installedVersion(dir), sizeOf(dir)));
            }
        }
        return found;
    }

    /** Every update and DLC title in the virtual SD, whatever game it belongs to. */
    static List<Installed> installedExtras(File userDir) {
        List<Installed> found = new ArrayList<>();
        for (long high : new long[]{HIGH_UPDATE, HIGH_DLC}) {
            File[] lows = new File(titleRoot(userDir), String.format(Locale.US, "%08x", high)).listFiles(File::isDirectory);
            if (lows == null) continue;
            Arrays.sort(lows, Comparator.comparing(File::getName));
            for (File low : lows) {
                try {
                    long tid = (high << 32) | Long.parseLong(low.getName(), 16);
                    found.add(new Installed(low, tid, installedVersion(low), sizeOf(low)));
                } catch (NumberFormatException ignored) {
                    // Not a title folder.
                }
            }
        }
        return found;
    }

    /** Folders of games (00040000 titles) installed from CIAs that have an executable. */
    static List<File> installedGames(File userDir) {
        List<File> games = new ArrayList<>();
        File[] lows = new File(titleRoot(userDir), "00040000").listFiles(File::isDirectory);
        if (lows != null) {
            Arrays.sort(lows, Comparator.comparing(File::getName));
            for (File low : lows) {
                if (contentApp(low) != null) {
                    games.add(low);
                }
            }
        }
        return games;
    }

    static long sizeOf(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ---- bytes ----

    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }

    private static long le64(byte[] data, int offset) {
        return le32(data, offset) | le32(data, offset + 4) << 32;
    }

    private static long be32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) << 24 | (data[offset + 1] & 0xFFL) << 16 | (data[offset + 2] & 0xFFL) << 8 | (data[offset + 3] & 0xFFL);
    }

    private static long be64(byte[] data, int offset) {
        return be32(data, offset) << 32 | be32(data, offset + 4);
    }

    private static int be16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }
}
