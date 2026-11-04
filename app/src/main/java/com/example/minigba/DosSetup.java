package com.example.minigba;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// DOS mode (DOSBox Pure): the game's start file, its settings, and the save it keeps.
//
// A DOS game is its folder in a zip, run as drive C: without unpacking. What the game writes
// goes to an overlay zip beside the saves (DOSBox Pure's "<game>.pure.zip"); here that name is
// a link to the game's usual <save name>.sav, so the app's save tools (backup, export, delete)
// treat it like any other game's save.
//
// The file picked to start is run through MINIRUN.BAT, which the app puts at the root of C:
// inside that overlay ("GAME.ZIP#MINIRUN.BAT" starts it): it changes into the file's folder,
// runs a Hangul driver first when one was picked, then the game.
final class DosSetup {
    static final String START_FILE = "MINIRUN.BAT";
    static final String MENU = "menu";

    static final String[] CPU_VALUES = {"auto", "1320", "4720", "26800", "77000", "max"};
    static final String[] CPU_NAMES = {"자동", "286", "386", "486", "펜티엄", "최대"};
    static final String[] SOUND_NAMES = {"Sound Blaster 16", "AdLib", "PC 스피커"};
    static final String[] MEMORY_VALUES = {"16", "32", "64"};
    static final String[] MEMORY_NAMES = {"16MB", "32MB", "64MB"};
    static final String[] CONTROL_NAMES = {"방향키 + 버튼", "마우스 패드", "키보드만"};
    static final String[] MOUSE_NAMES = {"밀어서 움직이기", "누른 곳으로 (일부 게임)"};
    static final String[] MIDI_NAMES = {"끔", "General MIDI", "MT-32", "SC-55"};
    // 3dfx Voodoo: drawn by the CPU (DOSBox Pure's "auto", which here never asks for a GPU
    // context), by the phone's GPU through OpenGL ES ("4"), or no Voodoo card at all.
    static final String[] VOODOO_NAMES = {"소프트웨어 (기본)", "하드웨어 (OpenGL ES)", "끔"};
    static final String DEFAULT_KEYS = "Esc,Enter,Space,Y,N,F1,F2,F3,F10,Tab";
    static final String DEFAULT_BUTTONS = "Ctrl,Alt,Shift,Enter";

    private DosSetup() {
    }

    static final class Exe {
        String path;     // inside the zip, "/" separated
        String dos;      // the same file as DOSBox Pure names it on C: ("PRIN990)\\PRINCE.EXE"), null if unknown
        long size;
        String note = "";
        boolean recommended;
        boolean helper;  // a Hangul driver or similar, run before the game
    }

    static boolean isZip(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".dosz");
    }

    // The programs in the game's zip, the likely start file marked.
    static List<Exe> executables(File zip) throws IOException {
        List<Exe> out = new ArrayList<>();
        for (ZipName e : dosNames(zip)) {
            if (e.directory) {
                continue;
            }
            String name = e.path;
            String lower = name.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".exe") || lower.endsWith(".com") || lower.endsWith(".bat"))) {
                continue;
            }
            Exe exe = new Exe();
            exe.path = name;
            exe.dos = e.dos;
            exe.size = e.size;
            String base = baseName(lower);
            if (isHelperName(base)) {
                exe.helper = true;
                exe.note = "한글 출력기 · 게임 전에 함께 실행";
            } else if (base.startsWith("setup") || base.startsWith("setsound") || base.startsWith("sound") || base.startsWith("config") || base.startsWith("setcfg")) {
                exe.note = "설정 (사운드 카드 · 그래픽)";
            } else if (base.startsWith("install") || base.startsWith("uninst") || base.equals("inst")) {
                exe.note = "설치 (이미 설치된 상태면 필요 없음)";
            }
            out.add(exe);
        }
        Exe best = null;
        int bestScore = Integer.MIN_VALUE;
        String gameName = baseName(zip.getName().toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9]", "");
        for (Exe exe : out) {
            int score = score(exe, gameName);
            if (score > bestScore) {
                bestScore = score;
                best = exe;
            }
        }
        if (best != null) {
            best.recommended = true;
            if (best.note.isEmpty()) {
                best.note = "게임 시작 · 추천 (이름 · 크기로 고름)";
            }
        }
        // Recommended first, then helpers, then the rest by name.
        java.util.Collections.sort(out, (a, b) -> {
            if (a.recommended != b.recommended) return a.recommended ? -1 : 1;
            if (a.note.isEmpty() != b.note.isEmpty()) return a.note.isEmpty() ? 1 : -1;
            return a.path.compareToIgnoreCase(b.path);
        });
        return out;
    }

    private static int score(Exe exe, String gameName) {
        String lower = exe.path.toLowerCase(Locale.ROOT);
        String base = baseName(lower);
        int score = 0;
        if (exe.helper || !exe.note.isEmpty()) score -= 1000;
        if (base.contains("uninst") || base.contains("readme") || base.contains("help") || base.contains("patch") || base.contains("crack")) score -= 500;
        if (!gameName.isEmpty() && (gameName.startsWith(base) || base.startsWith(gameName.substring(0, Math.min(gameName.length(), 4))))) score += 120;
        if (base.equals("game") || base.equals("start") || base.equals("play") || base.equals("run") || base.equals("go") || base.equals("main")) score += 100;
        if (lower.endsWith(".bat")) score += 40;
        if (lower.endsWith(".exe")) score += 20;
        // Fewer folders deep, and bigger programs, are more likely the game itself.
        int depth = 0;
        for (char c : lower.toCharArray()) {
            if (c == '/') depth++;
        }
        score -= depth * 30;
        score += (int) Math.min(80, exe.size / (16 * 1024));
        return score;
    }

    static boolean isHelperName(String base) {
        return base.startsWith("hangul") || base.startsWith("han") && base.length() <= 8 && !base.startsWith("hand")
                || base.startsWith("hgl") || base.startsWith("hanme") || base.startsWith("2hg") || base.equals("hg") || base.startsWith("hanip");
    }

    private static String baseName(String lowerPath) {
        String name = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---- DOS names ----
    // DOSBox Pure mounts the zip as C: and names every file and folder in 8.3 form itself:
    // a name that already fits stays; a longer one becomes its first 4 and last 4 characters
    // (upper case, characters DOS can't hold turned to '-'), the extension cut to 3; a name
    // already taken in the folder gets a character counted up. Done here the same way, in
    // the order of the zip's central directory, with the names' own bytes (a Korean name's
    // bytes are kept as they are, as the engine does), so the start file can name anything.

    static final class ZipName {
        String path;       // shown: decoded, "/" separated
        String dos;        // DOS path without "C:\", bytes as ISO-8859-1 chars
        long size;
        boolean directory;
    }

    private static final int[] VALID_CHAR_BITS = {0, 0, 0, 0, 250, 43, 255, 3, 255, 255, 255, 199, 1, 0, 0, 232, 1, 192, 5, 254, 224, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255};

    private static boolean validChar(int b) {
        return (VALID_CHAR_BITS[b / 8] & (1 << (b % 8))) != 0;
    }

    private static int toUpperAndFilter(int b) {
        if (validChar(b)) return b;
        if (b >= 'a' && b <= 'z') return b & 0x5F;
        return b < 0x80 ? '-' : b;  // every byte from 0x80 is valid, so never reached
    }

    // DBP_Make8dot3FileName; returns the name and whether it was changed.
    static String make8dot3(byte[] src, int from, int to, boolean[] changed) {
        int sDot = to - 1;
        while (sDot > from && src[sDot] != '.') sDot--;
        boolean hasDot = to > from && src[sDot] == '.';
        int baseLen = (hasDot ? sDot : to) - from;
        int extLen = hasDot ? to - 1 - sDot : 0;
        if (baseLen <= 8 && extLen <= 3) {
            boolean ok = true;
            for (int p = from; p < to; p++) {
                if (!validChar(src[p] & 0xFF) && p != sDot) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                changed[0] = false;
                return new String(src, from, to - from, StandardCharsets.ISO_8859_1);
            }
        }
        StringBuilder out = new StringBuilder();
        int baseLeft = baseLen > 8 ? 4 : baseLen;
        int baseRight = baseLen > 8 ? 4 : 0;
        for (int k = 0; k < baseLeft; k++) out.append((char) toUpperAndFilter(src[from + k] & 0xFF));
        for (int k = 0; k < baseRight; k++) out.append((char) toUpperAndFilter(src[from + baseLen - baseRight + k] & 0xFF));
        if (baseLen == 0) out.append('-');
        if (extLen > 0) {
            out.append('.');
            for (int k = 0; k < Math.min(3, extLen); k++) out.append((char) toUpperAndFilter(src[sDot + 1 + k] & 0xFF));
        }
        changed[0] = true;
        return out.toString();
    }

    // Zip_Directory::IncrementName; null when it can't.
    private static String incrementName(String name) {
        char[] c = name.toCharArray();
        int dot = name.indexOf('.');
        int baseLen = dot >= 0 ? dot : c.length;
        int j = baseLen > 8 ? 4 : baseLen / 2;
        if (baseLen >= 1 && j < c.length && c[j] != 0 && c[j] < '~') c[j]++;
        else if (baseLen >= 3 && j + 1 < c.length && c[j + 1] < '~') c[j + 1]++;
        else if (baseLen >= 5 && j + 2 < c.length && c[j + 2] < '~') c[j + 2]++;
        else return null;
        return new String(c);
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (u16(b, o) | (long) u16(b, o + 2) << 16) & 0xFFFFFFFFL;
    }

    private static String display(byte[] raw, int from, int len, boolean utf8Flag) {
        if (utf8Flag) {
            return new String(raw, from, len, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw, from, len)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            try {
                return new String(raw, from, len, java.nio.charset.Charset.forName("MS949"));
            } catch (RuntimeException e2) {
                return new String(raw, from, len, StandardCharsets.ISO_8859_1);
            }
        }
    }

    // Every file and folder of the zip with the DOS name DOSBox Pure gives it.
    static List<ZipName> dosNames(File zip) throws IOException {
        byte[] cd;
        int total;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(zip, "r")) {
            long length = file.length();
            int tail = (int) Math.min(length, 65557);
            byte[] end = new byte[tail];
            file.seek(length - tail);
            file.readFully(end);
            int eocd = -1;
            for (int p = tail - 22; p >= 0; p--) {
                if (end[p] == 'P' && end[p + 1] == 'K' && end[p + 2] == 5 && end[p + 3] == 6) {
                    eocd = p;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("zip이 아니에요");
            }
            total = u16(end, eocd + 10);
            long size = u32(end, eocd + 12);
            long offset = u32(end, eocd + 16);
            if (total == 0xFFFF || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL || size > (64 << 20) || offset + size > length) {
                throw new IOException("4GB가 넘는 zip(zip64)은 DOS 엔진이 읽지 못해요");
            }
            cd = new byte[(int) size];
            file.seek(offset);
            file.readFully(cd);
        }
        List<ZipName> out = new ArrayList<>();
        java.util.Map<String, java.util.Set<String>> taken = new java.util.HashMap<>();   // DOS folder -> names in it
        java.util.Map<String, String> folders = new java.util.HashMap<>();               // DOS path of a folder kept as is
        java.util.Map<String, String> longFolders = new java.util.HashMap<>();           // long path -> DOS path, for shortened folders
        java.util.Set<String> listed = new java.util.HashSet<>();
        int p = 0;
        for (int i = 0; i < total && p + 46 <= cd.length; i++) {
            if (!(cd[p] == 'P' && cd[p + 1] == 'K' && cd[p + 2] == 1 && cd[p + 3] == 2)) {
                break;
            }
            int flags = u16(cd, p + 8);
            long comp = u32(cd, p + 20);
            long decomp = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28);
            int extraLen = u16(cd, p + 30);
            int commentLen = u16(cd, p + 32);
            long attr = u32(cd, p + 38);
            int name = p + 46;
            p = name + nameLen + extraLen + commentLen;
            if (nameLen == 0 || name + nameLen > cd.length) {
                continue;
            }
            int method = u16(cd, name - 46 + 10);
            if ((method == 0 && decomp != comp) || (decomp != 0 && comp == 0)) {
                continue;
            }
            int nameEnd = name + nameLen;
            boolean isDir = cd[nameEnd - 1] == '/' || cd[nameEnd - 1] == '\\' || (attr & 0x10) != 0;
            String dosPath = "";
            String parentKey = "";
            int nDir = name;
            for (int n = name; n <= nameEnd; n++) {
                if (n != nameEnd && cd[n] != '/' && cd[n] != '\\') continue;
                if (n == nDir) {
                    nDir++;
                    continue;
                }
                boolean[] changed = new boolean[1];
                String dos = make8dot3(cd, nDir, n, changed);
                java.util.Set<String> names = taken.computeIfAbsent(parentKey, k -> new java.util.HashSet<>());
                if (n == nameEnd && !isDir) {
                    while (dos != null && names.contains(dos)) dos = incrementName(dos);
                    if (dos == null) break;
                    names.add(dos);
                    String full = dosPath + dos;
                    ZipName z = new ZipName();
                    z.path = display(cd, name, nameLen, (flags & 0x800) != 0).replace('\\', '/');
                    z.dos = full;
                    z.size = decomp;
                    out.add(z);
                    break;
                }
                String longKey = new String(cd, name, n - name, StandardCharsets.ISO_8859_1);
                String existing = changed[0] ? longFolders.get(longKey) : folders.get(dosPath + dos);
                if (existing == null) {
                    while (dos != null && names.contains(dos)) dos = incrementName(dos);
                    if (dos == null) break;
                    names.add(dos);
                    existing = dosPath + dos;
                    folders.put(existing, existing);
                    if (changed[0]) longFolders.put(longKey, existing);
                }
                if (listed.add(existing)) {
                    ZipName z = new ZipName();
                    z.path = display(cd, name, n - name, (flags & 0x800) != 0).replace('\\', '/');
                    z.dos = existing;
                    z.directory = true;
                    out.add(z);
                }
                if (n + 1 >= nameEnd) break;
                dosPath = existing + "\\";
                parentKey = existing;
                nDir = n + 1;
            }
        }
        return out;
    }

    // The DOS path of a file in the zip, by its shown path.
    static String dosNameOf(File zip, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            for (ZipName z : dosNames(zip)) {
                if (!z.directory && z.path.equals(path)) {
                    return z.dos;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // DOS paths go into the batch file as they are; '%' means a variable there.
    private static String batchSafe(String dos) {
        return dos.replace("%", "%%");
    }

    static String batchFor(String exeDos, String helperDos) {
        StringBuilder bat = new StringBuilder("@ECHO OFF\r\n");
        if (helperDos != null && !helperDos.isEmpty()) {
            appendRun(bat, helperDos);
        }
        appendRun(bat, exeDos);
        return bat.toString();
    }

    private static void appendRun(StringBuilder bat, String dos) {
        int slash = dos.lastIndexOf('\\');
        bat.append("C:\r\n");
        bat.append("CD \\").append(batchSafe(slash > 0 ? dos.substring(0, slash) : "")).append("\r\n");
        String file = slash >= 0 ? dos.substring(slash + 1) : dos;
        bat.append(file.toUpperCase(Locale.ROOT).endsWith(".BAT") ? "CALL " : "").append(batchSafe(file)).append("\r\n");
    }

    // ---- per-game settings ----

    static String key(String field, String saveBaseName) {
        return "dos:" + field + ":" + saveBaseName;
    }

    static String start(SharedPreferences prefs, String saveBaseName) {
        return prefs.getString(key("exe", saveBaseName), null);
    }

    static String helper(SharedPreferences prefs, String saveBaseName) {
        return prefs.getString(key("helper", saveBaseName), "");
    }

    static int choice(SharedPreferences prefs, String field, String saveBaseName) {
        return prefs.getInt(key(field, saveBaseName), 0);
    }

    static String keyRow(SharedPreferences prefs, String saveBaseName) {
        return prefs.getString(key("keys", saveBaseName), DEFAULT_KEYS);
    }

    static String buttonRow(SharedPreferences prefs, String saveBaseName) {
        return prefs.getString(key("buttons", saveBaseName), DEFAULT_BUTTONS);
    }

    static String summary(SharedPreferences prefs, String saveBaseName) {
        return "CPU " + CPU_NAMES[clamp(choice(prefs, "cpu", saveBaseName), CPU_NAMES.length)] + " · "
                + SOUND_NAMES[clamp(choice(prefs, "sound", saveBaseName), SOUND_NAMES.length)].replace("Sound Blaster 16", "SB16");
    }

    static int clamp(int value, int count) {
        return value < 0 || value >= count ? 0 : value;
    }

    // The core options for the game, as keys and values.
    static String[][] coreOptions(SharedPreferences prefs, String saveBaseName, File systemDir) {
        int cpu = clamp(choice(prefs, "cpu", saveBaseName), CPU_VALUES.length);
        int sound = clamp(choice(prefs, "sound", saveBaseName), SOUND_NAMES.length);
        int memory = clamp(choice(prefs, "memory", saveBaseName), MEMORY_VALUES.length);
        int mouse = clamp(choice(prefs, "mouse", saveBaseName), MOUSE_NAMES.length);
        List<String[]> options = new ArrayList<>();
        options.add(new String[]{"dosbox_pure_cycles", prefs.getString(key("cycles", saveBaseName), CPU_VALUES[cpu])});
        options.add(new String[]{"dosbox_pure_memory_size", MEMORY_VALUES[memory]});
        options.add(new String[]{"dosbox_pure_sblaster_type", sound == 0 ? prefs.getString(key("sbtype", saveBaseName), "sb16") : "none"});
        options.add(new String[]{"dosbox_pure_gus", prefs.getBoolean(key("gus", saveBaseName), false) ? "true" : "false"});
        options.add(new String[]{"dosbox_pure_sblaster_adlib_mode", sound == 0 ? "auto" : sound == 1 ? "opl2" : "none"});
        options.add(new String[]{"dosbox_pure_mouse_input", mouse == 1 ? "direct" : "virtual"});
        options.add(new String[]{"dosbox_pure_midi", midiOption(systemDir, midiChoice(prefs, saveBaseName))});
        int voodoo = clamp(choice(prefs, "voodoo", saveBaseName), VOODOO_NAMES.length);
        options.add(new String[]{"dosbox_pure_voodoo", voodoo == 2 ? "off" : "8mb"});
        options.add(new String[]{"dosbox_pure_voodoo_perf", voodoo == 1 ? "4" : "auto"});
        // The app's own quick saves and rewind go through the core's save states.
        options.add(new String[]{"dosbox_pure_savestate", "on"});
        String[][] out = new String[2][options.size()];
        for (int i = 0; i < options.size(); i++) {
            out[0][i] = options.get(i)[0];
            out[1][i] = options.get(i)[1];
        }
        return out;
    }

    // ---- the overlay (save) ----

    static String contentName(File game) {
        String name = game.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // Links DOSBox Pure's save name to the game's .sav; a save the core made itself before
    // (a plain file) becomes the .sav when there is none yet.
    static void linkSave(File saveDir, File game, String saveBaseName) {
        File link = new File(saveDir, contentName(game) + ".pure.zip");
        File save = new File(saveDir, saveBaseName + ".sav");
        try {
            boolean symlink = isSymlink(link);
            if (symlink) {
                if (save.getAbsolutePath().equals(android.system.Os.readlink(link.getAbsolutePath()))) {
                    return;
                }
                link.delete();
            } else if (link.isFile()) {
                if (!save.exists()) {
                    if (!link.renameTo(save)) {
                        return;
                    }
                } else if (link.lastModified() > save.lastModified()) {
                    save.delete();
                    if (!link.renameTo(save)) {
                        return;
                    }
                } else {
                    link.delete();
                }
            }
            android.system.Os.symlink(save.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception e) {
            // Without the link the core keeps its own save name; the game still saves.
        }
    }

    private static boolean isSymlink(File file) {
        try {
            return android.system.Os.lstat(file.getAbsolutePath()) != null
                    && android.system.OsConstants.S_ISLNK(android.system.Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    // Puts MINIRUN.BAT into the save overlay (a zip), keeping everything else in it.
    static void writeStartFile(File save, String batch) throws IOException {
        byte[] data = batch.getBytes(StandardCharsets.ISO_8859_1);
        File temp = new File(save.getParentFile(), save.getName() + ".tmp");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp), StandardCharsets.ISO_8859_1)) {
            if (save.isFile() && save.length() > 0) {
                try (ZipFile old = new ZipFile(save, StandardCharsets.ISO_8859_1)) {
                    Enumeration<? extends ZipEntry> entries = old.entries();
                    byte[] buffer = new byte[1 << 16];
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.getName().equalsIgnoreCase(START_FILE)) {
                            continue;
                        }
                        ZipEntry copy = new ZipEntry(e.getName());
                        copy.setTime(e.getTime());
                        if (e.getExtra() != null) {
                            copy.setExtra(e.getExtra());
                        }
                        out.putNextEntry(copy);
                        if (!e.isDirectory()) {
                            try (InputStream in = old.getInputStream(e)) {
                                int n;
                                while ((n = in.read(buffer)) > 0) {
                                    out.write(buffer, 0, n);
                                }
                            }
                        }
                        out.closeEntry();
                    }
                } catch (java.util.zip.ZipException e) {
                    // Not a zip (an older save format): leave it alone.
                    temp.delete();
                    throw new IOException("세이브 파일 형식을 몰라서 시작 파일을 넣지 못했어요");
                }
            }
            ZipEntry entry = new ZipEntry(START_FILE);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(data);
            out.closeEntry();
        }
        if (!temp.renameTo(save)) {
            temp.delete();
            throw new IOException("세이브 파일에 시작 파일을 쓰지 못했어요");
        }
    }

    // Whether the overlay already starts with this batch file.
    static boolean hasStartFile(File save, String batch) {
        if (!save.isFile()) {
            return false;
        }
        try (ZipFile zip = new ZipFile(save, StandardCharsets.ISO_8859_1)) {
            ZipEntry e = zip.getEntry(START_FILE);
            if (e == null) {
                return false;
            }
            try (InputStream in = zip.getInputStream(e)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, n);
                }
                return batch.equals(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1));
            }
        } catch (IOException e) {
            return false;
        }
    }

    // Before the game starts: links the save, writes the start file. Returns the file for
    // the core to start, or "" for its own start menu.
    static String prepare(SharedPreferences prefs, File saveDir, File game, String saveBaseName) {
        saveDir.mkdirs();
        linkSave(saveDir, game, saveBaseName);
        String exe = start(prefs, saveBaseName);
        if (exe == null || MENU.equals(exe) || !isZip(game)) {
            return "";
        }
        String batch;
        if (CONF.equals(exe)) {
            String autoexec = prefs.getString(key("autoexec", saveBaseName), null);
            if (autoexec == null || autoexec.trim().isEmpty()) {
                return "";
            }
            batch = "@ECHO OFF\r\n" + autoexec.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n";
        } else {
            String exeDos = dosNameOf(game, exe);
            if (exeDos == null) {
                return "";
            }
            String helperDos = dosNameOf(game, helper(prefs, saveBaseName));
            batch = batchFor(exeDos, helperDos);
        }
        File save = new File(saveDir, saveBaseName + ".sav");
        if (!hasStartFile(save, batch)) {
            try {
                writeStartFile(save, batch);
            } catch (IOException e) {
                return "";
            }
        }
        return START_FILE;
    }

    // ---- dosbox.conf (eXoDOS-style or hand-made) ----
    // CPU speed, sound card, memory, Gravis and the [autoexec] start lines. The mount lines go
    // (the engine has the zip on C: already, and CD images in it mounted); a "cd" to a long
    // folder name gets the DOS name the engine gives it.

    static final String CONF = "conf";
    private static final int[] CORE_CYCLES = {315, 1320, 2750, 4720, 7800, 13400, 26800, 77000, 200000, 500000, 1000000};

    static final class Conf {
        String cycles;          // a dosbox_pure_cycles value
        int cpuChoice = -1;     // nearest CPU_VALUES chip
        String sbtype;          // dosbox_pure_sblaster_type value
        int sound = -1;
        int memory = -1;
        boolean gus;
        String midi;
        int midiChoice = -1;
        final List<String> autoexec = new ArrayList<>();
        final List<String[]> rows = new ArrayList<>();   // {conf line, what it became, applied?}
    }

    static Conf parseConf(String text, File zip, File systemDir) {
        Conf conf = new Conf();
        java.util.Map<String, String> folders = new java.util.HashMap<>();
        if (zip != null) {
            try {
                for (ZipName z : dosNames(zip)) {
                    if (z.directory) {
                        folders.put(z.path.toLowerCase(Locale.ROOT), z.dos);
                    }
                }
            } catch (IOException e) {
                // No translations.
            }
        }
        String section = "";
        String oplmode = null;
        String cwd = "";
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            if (section.equals("autoexec")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (line.isEmpty() || lower.startsWith("mount ") || lower.startsWith("imgmount ") || lower.equals("exit") || lower.startsWith("@echo off") || lower.startsWith("echo off")) {
                    continue;
                }
                if (lower.startsWith("cd ") || lower.startsWith("cd\\") || lower.startsWith("chdir ")) {
                    String arg = line.substring(lower.startsWith("chdir ") ? 6 : 2).trim().replace('/', '\\');
                    boolean absolute = arg.startsWith("\\");
                    String path = (absolute ? "" : cwd) + arg.replaceAll("^\\\\+", "").replace("\"", "");
                    String dos = folders.get(path.replace('\\', '/').toLowerCase(Locale.ROOT));
                    cwd = path.isEmpty() ? "" : path + "\\";
                    if (dos != null) {
                        line = "CD \\" + dos;
                        cwd = path + "\\";
                    }
                }
                conf.autoexec.add(line);
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (section.equals("cpu") && k.equals("cycles")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(v);
                if (v.startsWith("max")) {
                    conf.cycles = "max";
                    conf.cpuChoice = 5;
                } else if (m.find() && !v.startsWith("auto")) {
                    int n = Integer.parseInt(m.group(1));
                    int best = CORE_CYCLES[0];
                    for (int c : CORE_CYCLES) {
                        if (Math.abs(c - n) < Math.abs(best - n)) best = c;
                    }
                    conf.cycles = String.valueOf(best);
                    conf.cpuChoice = best <= 2750 ? 1 : best <= 7800 ? 2 : best <= 26800 ? 3 : 4;
                } else {
                    conf.cycles = "auto";
                    conf.cpuChoice = 0;
                }
                conf.rows.add(new String[]{"cycles=" + v, "CPU 속도: " + CPU_NAMES[conf.cpuChoice] + ("auto".equals(conf.cycles) || "max".equals(conf.cycles) ? "" : " (" + conf.cycles + ")"), "적용"});
            } else if (section.equals("dosbox") && k.equals("memsize")) {
                try {
                    int mb = Integer.parseInt(v.replaceAll("[^0-9]", ""));
                    conf.memory = mb <= 16 ? 0 : mb <= 32 ? 1 : 2;
                    conf.rows.add(new String[]{"memsize=" + v, "메모리: " + MEMORY_NAMES[conf.memory] + (mb < 16 ? " (최소값)" : ""), "적용"});
                } catch (NumberFormatException e) {
                    // Ignored.
                }
            } else if (section.equals("sblaster") && k.equals("sbtype")) {
                if (v.equals("none")) {
                    conf.sound = 2;
                } else if (v.matches("sb16|sbpro2|sbpro1|sb2|sb1|gb")) {
                    conf.sound = 0;
                    conf.sbtype = v;
                }
                if (conf.sound >= 0) {
                    conf.rows.add(new String[]{"sbtype=" + v, "사운드: " + (conf.sound == 0 ? "Sound Blaster " + v.replace("sbpro", "Pro ").replace("sb", "").toUpperCase(Locale.ROOT) : "PC 스피커"), "적용"});
                }
            } else if (section.equals("sblaster") && k.equals("oplmode")) {
                oplmode = v;
            } else if (section.equals("gus") && k.equals("gus")) {
                conf.gus = v.equals("true");
                if (conf.gus) conf.rows.add(new String[]{"gus=true", "Gravis Ultrasound 켜기", "적용"});
            } else if (section.equals("midi") && k.equals("mpu401") && v.equals("none") && conf.midiChoice < 0) {
                conf.midiChoice = 0;
                conf.rows.add(new String[]{"mpu401=none", "MIDI: 끔", "적용"});
            } else if (section.equals("midi") && k.equals("mididevice") && !v.equals("default") && !v.equals("none")) {
                conf.midi = v;
                if (v.equals("mt32")) {
                    conf.midiChoice = 2;
                    boolean ok = systemDir != null && hasMt32(systemDir);
                    conf.rows.add(new String[]{k + "=" + v, ok ? "MIDI: MT-32" : "MIDI: MT-32 ROM 없음 → General MIDI로", ok ? "적용" : "대신 적용"});
                } else if (v.contains("sc55") || v.contains("canvas")) {
                    conf.midiChoice = 3;
                    boolean ok = systemDir != null && hasSc55(systemDir);
                    conf.rows.add(new String[]{k + "=" + v, ok ? "MIDI: SC-55" : "MIDI: SC-55 ROM 없음 → General MIDI로", ok ? "적용" : "대신 적용"});
                } else {
                    conf.midiChoice = 1;
                    conf.rows.add(new String[]{k + "=" + v, "MIDI: General MIDI (사운드폰트)", "적용"});
                }
            }
        }
        if (conf.sound == 2 && oplmode != null && !oplmode.equals("none")) {
            conf.sound = 1;
            conf.rows.add(new String[]{"oplmode=" + oplmode, "사운드: AdLib", "적용"});
        }
        if (!conf.autoexec.isEmpty()) {
            conf.rows.add(new String[]{"[autoexec]", "실행 순서 " + conf.autoexec.size() + "줄", "적용"});
        }
        return conf;
    }

    static void applyConf(SharedPreferences prefs, String saveBaseName, Conf conf, String confName) {
        SharedPreferences.Editor edit = prefs.edit();
        if (conf.cycles != null) {
            edit.putString(key("cycles", saveBaseName), conf.cycles).putInt(key("cpu", saveBaseName), conf.cpuChoice);
        }
        if (conf.memory >= 0) edit.putInt(key("memory", saveBaseName), conf.memory);
        if (conf.sound >= 0) edit.putInt(key("sound", saveBaseName), conf.sound);
        if (conf.sbtype != null) edit.putString(key("sbtype", saveBaseName), conf.sbtype);
        edit.putBoolean(key("gus", saveBaseName), conf.gus);
        if (conf.midiChoice >= 0) edit.putInt(key("midi", saveBaseName), conf.midiChoice);
        if (!conf.autoexec.isEmpty()) {
            edit.putString(key("autoexec", saveBaseName), android.text.TextUtils.join("\n", conf.autoexec))
                    .putString(key("exe", saveBaseName), CONF);
        }
        edit.putString(key("conf", saveBaseName), confName + " · " + new java.text.SimpleDateFormat("M월 d일", Locale.KOREA).format(new java.util.Date()));
        edit.apply();
    }

    static void clearConf(SharedPreferences prefs, String saveBaseName) {
        SharedPreferences.Editor edit = prefs.edit()
                .remove(key("autoexec", saveBaseName)).remove(key("conf", saveBaseName)).remove(key("cycles", saveBaseName))
                .remove(key("sbtype", saveBaseName)).remove(key("gus", saveBaseName));
        if (CONF.equals(start(prefs, saveBaseName))) {
            edit.remove(key("exe", saveBaseName));
        }
        edit.apply();
    }

    // ---- MIDI music ----
    // DOSBox Pure plays MIDI through a SoundFont (its TinySoundFont player), Roland MT-32 ROMs
    // (Munt) or Roland SC-55 ROMs, each a file under the system folder that its
    // dosbox_pure_midi option names. The app ships a General MIDI SoundFont (TimGM6mb); the
    // Roland ROMs are the owner's own, put in from the MIDI sheet.

    static final String MIDI_DIR = "dosmidi";
    static final String DEFAULT_SF2 = "TimGM6mb.sf2";
    static final String USER_SF2 = "user.sf2";

    static File midiDir(File systemDir) {
        return new File(systemDir, MIDI_DIR);
    }

    static File defaultSoundfont(File systemDir) {
        return new File(midiDir(systemDir), DEFAULT_SF2);
    }

    static File userSoundfont(File systemDir) {
        return new File(midiDir(systemDir), USER_SF2);
    }

    static File mt32Control(File systemDir) {
        return new File(new File(midiDir(systemDir), "mt32"), "MT32_CONTROL.ROM");
    }

    static File mt32Pcm(File systemDir) {
        return new File(new File(midiDir(systemDir), "mt32"), "MT32_PCM.ROM");
    }

    static File sc55Dir(File systemDir) {
        return new File(midiDir(systemDir), "sc55");
    }

    static boolean hasMt32(File systemDir) {
        return mt32Control(systemDir).isFile() && mt32Pcm(systemDir).isFile();
    }

    static boolean hasSc55(File systemDir) {
        return new File(sc55Dir(systemDir), "ROM1.BIN").isFile() && new File(sc55Dir(systemDir), "ROM2.BIN").isFile();
    }

    // 0 off, 1 General MIDI (the default), 2 MT-32, 3 SC-55.
    static int midiChoice(SharedPreferences prefs, String saveBaseName) {
        return clamp(prefs.getInt(key("midi", saveBaseName), 1), MIDI_NAMES.length);
    }

    // The option value: a path under the system folder; MT-32 / SC-55 without their ROMs
    // fall back to the SoundFont.
    static String midiOption(File systemDir, int choice) {
        if (systemDir == null || choice == 0) {
            return "disabled";
        }
        if (choice == 2 && hasMt32(systemDir)) {
            return MIDI_DIR + "/mt32/MT32_CONTROL.ROM";
        }
        if (choice == 3 && hasSc55(systemDir)) {
            return MIDI_DIR + "/sc55/ROM1.BIN";
        }
        if (userSoundfont(systemDir).isFile()) {
            return MIDI_DIR + "/" + USER_SF2;
        }
        if (defaultSoundfont(systemDir).isFile()) {
            return MIDI_DIR + "/" + DEFAULT_SF2;
        }
        return "disabled";
    }

    // Copies the shipped SoundFont out of the APK once (the engine reads files, not assets).
    static void ensureSoundfont(android.content.Context context, File systemDir) {
        File target = defaultSoundfont(systemDir);
        if (target.isFile() && target.length() > 0) {
            return;
        }
        target.getParentFile().mkdirs();
        File temp = new File(target.getPath() + ".tmp");
        try (InputStream in = context.getAssets().open("dos/" + DEFAULT_SF2);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
        }
    }

    // ---- text from DOS-era files ----
    // conf files and DOS names are kept as their bytes (ISO-8859-1 chars here); for the screen
    // they're read as UTF-8 or the old Korean code page (CP949), whichever fits; a name the
    // engine cut in the middle of a character shows the cut as '·'.
    static String shown(String bytesAsChars) {
        if (bytesAsChars == null) {
            return "";
        }
        byte[] bytes = bytesAsChars.getBytes(StandardCharsets.ISO_8859_1);
        boolean high = false;
        for (byte b : bytes) {
            if (b < 0) {
                high = true;
                break;
            }
        }
        if (!high) {
            return bytesAsChars;
        }
        String utf8 = strict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        try {
            String korean = strict(bytes, java.nio.charset.Charset.forName("MS949"));
            if (korean != null) {
                return korean;
            }
        } catch (RuntimeException e) {
            // No such code page here.
        }
        String loose = new String(bytes, StandardCharsets.UTF_8);
        return loose.replaceAll("�+", "·");
    }

    private static String strict(byte[] bytes, java.nio.charset.Charset charset) {
        try {
            return charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }
}
