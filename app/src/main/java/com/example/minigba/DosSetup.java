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
    static final String DEFAULT_KEYS = "Esc,Enter,Space,Y,N,F1,F2,F3,F10,Tab";
    static final String DEFAULT_BUTTONS = "Ctrl,Alt,Shift,Enter";

    private DosSetup() {
    }

    static final class Exe {
        String path;     // inside the zip, "/" separated
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
        try {
            return executables(zip, StandardCharsets.UTF_8);
        } catch (java.util.zip.ZipException e) {
            // Names in a DOS-era code page: read them byte for byte (only plain 8.3 names
            // can be started anyway).
            return executables(zip, StandardCharsets.ISO_8859_1);
        }
    }

    private static List<Exe> executables(File zip, java.nio.charset.Charset names) throws IOException {
        List<Exe> out = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip, names)) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".exe") || lower.endsWith(".com") || lower.endsWith(".bat"))) {
                    continue;
                }
                Exe exe = new Exe();
                exe.path = name;
                exe.size = e.getSize();
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

    // A DOS 8.3 name for every part of the path: the batch file can only name those.
    static boolean dosPath(String path) {
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            int dot = part.indexOf('.');
            String name = dot >= 0 ? part.substring(0, dot) : part;
            String ext = dot >= 0 ? part.substring(dot + 1) : "";
            if (name.isEmpty() || name.length() > 8 || ext.length() > 3 || ext.indexOf('.') >= 0) {
                return false;
            }
            for (char c : part.toCharArray()) {
                if (c <= ' ' || c > '~' || "\"*+,/:;<=>?[\\]|".indexOf(c) >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    static String batchFor(String exe, String helper) {
        StringBuilder bat = new StringBuilder("@ECHO OFF\r\n");
        if (helper != null && !helper.isEmpty()) {
            appendRun(bat, helper);
        }
        appendRun(bat, exe);
        return bat.toString();
    }

    private static void appendRun(StringBuilder bat, String path) {
        String dos = path.replace('/', '\\').toUpperCase(Locale.ROOT);
        int slash = dos.lastIndexOf('\\');
        bat.append("C:\r\n");
        bat.append("CD \\").append(slash > 0 ? dos.substring(0, slash) : "").append("\r\n");
        String file = slash >= 0 ? dos.substring(slash + 1) : dos;
        bat.append(file.endsWith(".BAT") ? "CALL " : "").append(file).append("\r\n");
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
    static String[][] coreOptions(SharedPreferences prefs, String saveBaseName) {
        int cpu = clamp(choice(prefs, "cpu", saveBaseName), CPU_VALUES.length);
        int sound = clamp(choice(prefs, "sound", saveBaseName), SOUND_NAMES.length);
        int memory = clamp(choice(prefs, "memory", saveBaseName), MEMORY_VALUES.length);
        int mouse = clamp(choice(prefs, "mouse", saveBaseName), MOUSE_NAMES.length);
        List<String[]> options = new ArrayList<>();
        options.add(new String[]{"dosbox_pure_cycles", CPU_VALUES[cpu]});
        options.add(new String[]{"dosbox_pure_memory_size", MEMORY_VALUES[memory]});
        options.add(new String[]{"dosbox_pure_sblaster_type", sound == 0 ? "sb16" : "none"});
        options.add(new String[]{"dosbox_pure_sblaster_adlib_mode", sound == 0 ? "auto" : sound == 1 ? "opl2" : "none"});
        options.add(new String[]{"dosbox_pure_mouse_input", mouse == 1 ? "direct" : "virtual"});
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
        byte[] data = batch.getBytes(StandardCharsets.US_ASCII);
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
                return batch.equals(new String(bytes.toByteArray(), StandardCharsets.US_ASCII));
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
        if (exe == null || MENU.equals(exe) || !isZip(game) || !dosPath(exe)) {
            return "";
        }
        String helper = helper(prefs, saveBaseName);
        if (!helper.isEmpty() && !dosPath(helper)) {
            helper = "";
        }
        String batch = batchFor(exe, helper);
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
}
