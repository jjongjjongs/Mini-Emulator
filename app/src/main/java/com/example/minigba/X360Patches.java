package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// (0.0.414) Xbox 360 게임 패치: the engine's own .patch.toml files (memory patches applied when
// the game runs: 60fps, 해상도, 한글 글꼴 고침 …). The ones that come with the engine
// (assets/x360/game-patches) and the player's own (<x360>/patches-added) are listed per game
// (its title ID), each [[patch]] turned on or off here; the engine reads <x360>/patches,
// written from these with is_enabled set as chosen. The game file is not changed.
final class X360Patches {
    static final String ASSET_DIR = "x360/game-patches";
    private static final Pattern NAME = Pattern.compile("^[A-Fa-f0-9]{8}.*\\.patch\\.toml$");
    private static final Pattern TITLE_ID = Pattern.compile("(?m)^\\s*title_id\\s*=\\s*\"([0-9A-Fa-f]{8})\"");
    private static final Pattern KEY = Pattern.compile("^\\s*(name|desc|author|is_enabled)\\s*=\\s*(.*?)\\s*(#.*)?$");

    static final class Patch {
        String file;          // the .patch.toml it is in
        boolean added;        // the player's own file
        int index;            // its [[patch]] number in the file
        String name = "";
        String desc = "";
        String author = "";
        boolean defaultOn;
        String version = "";  // "TU1" and the like, from the file name
    }

    static File patchesDir(Context context) {
        return new File(X360Activity.rootDir(context), "patches");
    }

    static File addedDir(Context context) {
        return new File(X360Activity.rootDir(context), "patches-added");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("x360_patches", Context.MODE_PRIVATE);
    }

    static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    static String titleIdOf(String text) {
        Matcher m = TITLE_ID.matcher(text);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }

    // Every file for this title: the engine's, then the player's.
    static List<String[]> files(Context context, String titleId) {
        List<String[]> out = new ArrayList<>();  // {name, "asset" | "added"}
        String prefix = titleId.toUpperCase(Locale.ROOT);
        try {
            String[] names = context.getAssets().list(ASSET_DIR);
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String name : names) {
                    if (name.toUpperCase(Locale.ROOT).startsWith(prefix) && name.endsWith(".patch.toml")) out.add(new String[]{name, "asset"});
                }
            }
        } catch (IOException ignored) {
        }
        String[] added = addedDir(context).list();
        if (added != null) {
            java.util.Arrays.sort(added);
            for (String name : added) {
                if (name.toUpperCase(Locale.ROOT).startsWith(prefix) && name.endsWith(".patch.toml")) out.add(new String[]{name, "added"});
            }
        }
        return out;
    }

    static String read(Context context, String name, boolean added) throws IOException {
        try (InputStream in = added ? new FileInputStream(new File(addedDir(context), name)) : context.getAssets().open(ASSET_DIR + "/" + name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static List<Patch> parse(String text, String file, boolean added) {
        List<Patch> out = new ArrayList<>();
        Patch current = null;
        boolean inSub = false;
        String version = "";
        Matcher v = Pattern.compile("\\(([^)]*)\\)\\.patch\\.toml$").matcher(file);
        if (v.find()) version = v.group(1);
        for (String line : text.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[[patch]]")) {
                current = new Patch();
                current.file = file;
                current.added = added;
                current.index = out.size();
                current.version = version;
                out.add(current);
                inSub = false;
                continue;
            }
            if (trimmed.startsWith("[")) {
                inSub = true;
                continue;
            }
            if (current == null || inSub) continue;
            Matcher m = KEY.matcher(line);
            if (!m.matches()) continue;
            String value = m.group(2);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            switch (m.group(1)) {
                case "name": current.name = value; break;
                case "desc": current.desc = value; break;
                case "author": current.author = value; break;
                default: current.defaultOn = "true".equals(value); break;
            }
        }
        return out;
    }

    private static String key(Patch patch) {
        return (patch.added ? "added/" : "asset/") + patch.file + "#" + patch.index;
    }

    static boolean isOn(Context context, Patch patch) {
        return prefs(context).getBoolean(key(patch), patch.defaultOn);
    }

    static void set(Context context, Patch patch, boolean on) {
        prefs(context).edit().putBoolean(key(patch), on).commit();
    }

    // The file as the engine is to read it: each [[patch]]'s is_enabled as chosen.
    interface Choice {
        boolean on(Patch patch);
    }

    static String withChoices(Context context, String text, String file, boolean added) {
        return withChoices(text, file, added, p -> isOn(context, p));
    }

    static String withChoices(String text, String file, boolean added, Choice choice) {
        List<Patch> patches = parse(text, file, added);
        StringBuilder out = new StringBuilder();
        int index = -1;
        boolean inSub = false;
        boolean done = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[[patch]]")) {
                index++;
                inSub = false;
                done = false;
            } else if (trimmed.startsWith("[")) {
                if (index >= 0 && !inSub && !done && index < patches.size()) {
                    // no is_enabled line: put one in
                    out.append("    is_enabled = ").append(choice.on(patches.get(index))).append('\n');
                    done = true;
                }
                inSub = true;
            } else if (index >= 0 && !inSub && !done && index < patches.size()) {
                Matcher m = KEY.matcher(line);
                if (m.matches() && "is_enabled".equals(m.group(1))) {
                    int at = line.indexOf("is_enabled");
                    out.append(line, 0, at).append("is_enabled = ").append(choice.on(patches.get(index))).append('\n');
                    done = true;
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        if (out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    // Writes <x360>/patches again: every file one of whose patches is on (the engine's patch
    // files are off by default, so only those the player touched are looked at, and all of
    // the player's own).
    static void sync(Context context) {
        File dir = patchesDir(context);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().endsWith(".patch.toml")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        java.util.Set<String> files = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, ?> e : prefs(context).getAll().entrySet()) {
            String k = e.getKey();
            int hash = k.lastIndexOf('#');
            if (hash > 0) files.add(k.substring(0, hash));
        }
        String[] added = addedDir(context).list();
        if (added != null) {
            for (String name : added) {
                if (name.endsWith(".patch.toml")) files.add("added/" + name);
            }
        }
        for (String f : files) {
            boolean isAdded = f.startsWith("added/");
            String name = f.substring(f.indexOf('/') + 1);
            try {
                String text = read(context, name, isAdded);
                boolean any = false;
                for (Patch p : parse(text, name, isAdded)) any |= isOn(context, p);
                if (!any) continue;
                String outName = isAdded ? name.replace(".patch.toml", " (내 패치).patch.toml") : name;
                try (FileOutputStream out = new FileOutputStream(new File(dir, outName))) {
                    out.write(withChoices(context, text, name, isAdded).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // a file gone: left out
            }
        }
    }

    // A picked .patch.toml into <x360>/patches-added, named the way the engine looks for
    // ("<title ID> - …"). Returns its title ID.
    static String add(Context context, File picked, String shownName) throws IOException {
        byte[] data;
        try (InputStream in = new FileInputStream(picked)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > 4 << 20) throw new IOException("패치 파일이 너무 커요.");
            }
            data = bytes.toByteArray();
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.contains("[[patch]]")) throw new IOException("Xenia 게임 패치(.patch.toml)가 아니에요 ([[patch]]가 없어요).");
        String id = titleIdOf(text);
        if (id == null && shownName != null && validName(shownName)) id = shownName.substring(0, 8).toUpperCase(Locale.ROOT);
        if (id == null) throw new IOException("패치 파일에 title_id가 없어서 어느 게임 것인지 알 수 없어요.");
        String name = shownName == null ? "patch.patch.toml" : shownName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.endsWith(".patch.toml")) name = name.replaceAll("\\.toml$", "") + ".patch.toml";
        if (!name.toUpperCase(Locale.ROOT).startsWith(id)) name = id + " - " + name;
        File dir = addedDir(context);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
        return id;
    }
}
