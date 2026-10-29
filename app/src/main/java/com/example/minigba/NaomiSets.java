package com.example.minigba;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The Naomi / Naomi 2 / Atomiswave sets Flycast knows (assets/naomi.bin, built by
 * cores/gen-naomi.py from the engine's own list): which board a ZIP is for, which GD-ROM
 * disc image it needs beside it, how many buttons the game uses and its Korean title.
 */
final class NaomiSets {
    static final class Set {
        final String name;
        final String parent;
        final String description;
        /** "naomi", "naomi2" or "aw". */
        final String system;
        /** The GD-ROM disc image's base name ("gdl-0010"), or null for cartridge games. */
        final String gd;
        final int buttons;
        final boolean vertical;
        final String korean;

        Set(String name, String parent, String description, String system, String gd, int buttons, boolean vertical, String korean) {
            this.name = name;
            this.parent = parent;
            this.description = description;
            this.system = system;
            this.gd = gd;
            this.buttons = buttons;
            this.vertical = vertical;
            this.korean = korean;
        }

        String systemName() {
            switch (system) {
                case "naomi2": return "Naomi 2";
                case "aw": return "Atomiswave";
                default: return "Naomi";
            }
        }

        String title() {
            return korean != null ? korean : description;
        }

        /** The BIOS ZIP this board needs in system/dc. */
        String biosName() {
            return "aw".equals(system) ? "awbios.zip" : "naomi2".equals(system) ? "naomi2.zip" : "naomi.zip";
        }
    }

    private static Map<String, Set> sets;

    private NaomiSets() {
    }

    static Set lookup(Context context, String setName) {
        return load(context).get(setName.toLowerCase(Locale.US));
    }

    /** The set a ROM ZIP in the Naomi folder is, by its file name, or null. */
    static Set forFile(Context context, File file) {
        String name = file.getName().toLowerCase(Locale.US);
        if (!name.endsWith(".zip")) {
            return null;
        }
        return lookup(context, name.substring(0, name.length() - 4));
    }

    /** The disc image a GD-ROM set needs, beside its ZIP; null for cartridge games. */
    static File discFor(File zip, Set set) {
        if (set == null || set.gd == null) {
            return null;
        }
        File dir = zip.getParentFile();
        for (String extension : new String[]{".chd", ".gdi", ".cdi"}) {
            File disc = new File(dir, set.gd + extension);
            if (disc.isFile()) {
                return disc;
            }
        }
        return new File(dir, set.gd + ".chd");
    }

    /** The number of buttons to show: the player's choice for the game, else the set's. */
    static int buttonsFor(Context context, String saveBaseName, Set set) {
        int chosen = MainActivity.sharedSettings(context).getInt("naomi:buttons:" + saveBaseName, 0);
        if (chosen >= 1 && chosen <= 6) {
            return chosen;
        }
        return set != null ? set.buttons : 6;
    }

    private static synchronized Map<String, Set> load(Context context) {
        if (sets == null) {
            Map<String, Set> map = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(context.getAssets().open("naomi.bin")), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length < 8) continue;
                    int buttons;
                    try {
                        buttons = Integer.parseInt(f[5]);
                    } catch (NumberFormatException e) {
                        buttons = 4;
                    }
                    map.put(f[0], new Set(f[0], "-".equals(f[1]) ? null : f[1], f[2], f[3], "-".equals(f[4]) ? null : f[4],
                            buttons, "1".equals(f[6]), "-".equals(f[7]) ? null : f[7]));
                }
            } catch (IOException e) {
                // Without the table, ZIPs are shown by name and run with six buttons.
            }
            sets = map;
        }
        return sets;
    }
}
