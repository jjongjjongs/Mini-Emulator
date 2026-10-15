package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// Cheat codes, kept per game in files/cheats/<rom folder>/<rom name>.cht (RetroArch's format).
// The list starts from libretro-database's cheat file for the game, matched by name against
// assets/cheats.bin (cores/gen-cheats.py); the player's own codes are kept beside it.
final class Cheats {
    static final class Cheat {
        String desc;
        String code;
        boolean on;
        boolean custom;

        Cheat(String desc, String code, boolean on, boolean custom) {
            this.desc = desc;
            this.code = code;
            this.on = on;
            this.custom = custom;
        }
    }

    // A cheat file of the collection: folder, name and how many cheats it has.
    static final class Source {
        final String folder;
        final String name;
        final int count;

        Source(String folder, String name, int count) {
            this.folder = folder;
            this.name = name;
            this.count = count;
        }
    }

    private static final String BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/";
    private static Map<String, List<Source>> index;
    private static final Map<String, List<String>> titles = new HashMap<>();

    private Cheats() {
    }

    // Consoles whose engines in this app take cheats.
    static boolean supported(String mode) {
        switch (mode) {
            case "gba": case "snes": case "nes": case "md": case "sms": case "pce":
            case "nds": case "n64": case "psp": case "ps1": case "mcd":
                return true;
            default:
                return false;
        }
    }

    static File file(Context context, File rom) {
        File dir = rom.getParentFile();
        return new File(context.getFilesDir(), "cheats/" + (dir == null ? "roms" : dir.getName()) + "/" + rom.getName() + ".cht");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("cheats", 0);
    }

    private static String key(File rom) {
        File dir = rom.getParentFile();
        return (dir == null ? "" : dir.getName()) + "/" + rom.getName();
    }

    // "<folder>/<name>" of the collection file in use, "-" when none was found, null before
    // the collection was searched.
    static String source(Context context, File rom) {
        return prefs(context).getString(key(rom), null);
    }

    static void setSource(Context context, File rom, String source) {
        prefs(context).edit().putString(key(rom), source).apply();
    }

    static boolean warned(Context context, File rom) {
        return prefs(context).getBoolean("warned:" + key(rom), false);
    }

    static void setWarned(Context context, File rom) {
        prefs(context).edit().putBoolean("warned:" + key(rom), true).apply();
    }

    // ---- the .cht format: cheatN_desc / cheatN_code / cheatN_enable lines ----
    static List<Cheat> parse(String text) {
        Map<Integer, Cheat> byIndex = new java.util.TreeMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("cheat")) {
                continue;
            }
            int underscore = line.indexOf('_');
            int eq = line.indexOf('=');
            if (underscore < 6 || eq < underscore) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(line.substring(5, underscore));
            } catch (NumberFormatException e) {
                continue;
            }
            String field = line.substring(underscore + 1, eq).trim();
            String value = line.substring(eq + 1).trim();
            // Descriptions can hold unescaped quotes: take from the first quote to the last.
            int first = value.indexOf('"');
            int last = value.lastIndexOf('"');
            if (first >= 0 && last > first) {
                value = value.substring(first + 1, last);
            }
            Cheat cheat = byIndex.get(index);
            if (cheat == null) {
                cheat = new Cheat("", "", false, false);
                byIndex.put(index, cheat);
            }
            switch (field) {
                case "desc": cheat.desc = value; break;
                case "code": cheat.code = value.trim(); break;
                case "enable": cheat.on = value.equalsIgnoreCase("true"); break;
                case "custom": cheat.custom = value.equalsIgnoreCase("true"); break;
                default: break;
            }
        }
        List<Cheat> list = new ArrayList<>();
        for (Cheat cheat : byIndex.values()) {
            // Headings in the collection have a description and no code.
            if (!cheat.code.isEmpty()) {
                if (cheat.desc.isEmpty()) {
                    cheat.desc = "치트 " + (list.size() + 1);
                }
                list.add(cheat);
            }
        }
        return list;
    }

    static String write(List<Cheat> cheats) {
        StringBuilder sb = new StringBuilder("cheats = ").append(cheats.size()).append("\n\n");
        for (int k = 0; k < cheats.size(); k++) {
            Cheat cheat = cheats.get(k);
            sb.append("cheat").append(k).append("_desc = \"").append(cheat.desc.replace("\n", " ")).append("\"\n");
            sb.append("cheat").append(k).append("_code = \"").append(cheat.code.replace("\"", "")).append("\"\n");
            sb.append("cheat").append(k).append("_enable = ").append(cheat.on).append("\n");
            if (cheat.custom) {
                sb.append("cheat").append(k).append("_custom = true\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static List<Cheat> load(Context context, File rom) {
        File file = file(context, rom);
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        try {
            return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static boolean save(Context context, File rom, List<Cheat> cheats) {
        File file = file(context, rom);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(write(cheats).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(file);
    }

    // The codes to hand the engine: the ones that are on, one string each ("+" between lines).
    static String[] enabledCodes(List<Cheat> cheats) {
        List<String> codes = new ArrayList<>();
        for (Cheat cheat : cheats) {
            if (cheat.on && !cheat.code.isEmpty()) {
                codes.add(cheat.code);
            }
        }
        return codes.toArray(new String[0]);
    }

    // Typed codes: one per line, spaces kept inside a line ("82025D7C 423F").
    static String codeFromInput(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("[\\r\\n+]+")) {
            String trimmed = line.trim().replaceAll("\\s+", " ");
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    static String codeForInput(String code) {
        return code.replace("+", "\n");
    }

    // Cheats that make the others work ("Master Code", "(M)", "Must Be On").
    static boolean isMaster(Cheat cheat) {
        String lower = cheat.desc.toLowerCase(Locale.US);
        return lower.contains("master code") || lower.contains("must be on") || lower.equals("(m)") || lower.startsWith("(m) ");
    }

    // ---- the collection ----
    static List<String> folders(String mode, String lowerName) {
        List<String> list = new ArrayList<>();
        for (String repo : Covers.repos(mode, lowerName)) {
            list.add(repo.replace('_', ' '));
        }
        return list;
    }

    static synchronized List<Source> sources(Context context, String folder) {
        if (index == null) {
            Map<String, List<Source>> map = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(context.getAssets().open("cheats.bin")), StandardCharsets.UTF_8))) {
                List<Source> current = null;
                String currentFolder = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) {
                        currentFolder = line.substring(1);
                        current = new ArrayList<>();
                        map.put(currentFolder, current);
                    } else if (current != null) {
                        int tab = line.lastIndexOf('\t');
                        if (tab > 0) {
                            int count;
                            try {
                                count = Integer.parseInt(line.substring(tab + 1));
                            } catch (NumberFormatException e) {
                                count = 0;
                            }
                            current.add(new Source(currentFolder, line.substring(0, tab), count));
                        }
                    }
                }
            } catch (IOException e) {
                // No index: cheats can still be typed in or loaded from a file.
            }
            index = map;
        }
        List<Source> list = index.get(folder);
        return list == null ? new ArrayList<>() : list;
    }

    private static synchronized List<String> titles(String folder, List<Source> sources) {
        List<String> list = titles.get(folder);
        if (list == null) {
            list = new ArrayList<>(sources.size());
            for (Source source : sources) {
                list.add(CoverMatcher.title(source.name));
            }
            titles.put(folder, list);
        }
        return list;
    }

    // The collection file that fits this ROM best, or null.
    static Source match(Context context, File rom, String mode, String displayName) {
        String base = rom.getName().replaceFirst("\\.[^.]+$", "");
        for (String folder : folders(mode, rom.getName().toLowerCase(Locale.US))) {
            List<Source> sources = sources(context, folder);
            List<String> names = new ArrayList<>(sources.size());
            for (Source source : sources) {
                names.add(source.name);
            }
            String found = CoverMatcher.match(base, names, titles(folder, sources));
            if (found == null && displayName != null && !displayName.equals(base)) {
                found = CoverMatcher.match(displayName, names, titles(folder, sources));
            }
            if (found != null) {
                return sources.get(names.indexOf(found));
            }
        }
        return null;
    }

    // Downloads a collection file and returns its cheats (all off).
    static List<Cheat> download(Source source) throws IOException {
        String address = BASE + encode(source.folder) + "/" + encode(source.name) + ".cht";
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < 4 * 1024 * 1024) {
                    out.write(buffer, 0, read);
                }
                List<Cheat> cheats = parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
                for (Cheat cheat : cheats) {
                    cheat.on = false;
                    cheat.custom = false;
                }
                return cheats;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    }

    // The collection's cheats in place of the old collection ones; the player's own stay
    // (first), and a cheat that was on stays on when the new file has the same code.
    static List<Cheat> merge(List<Cheat> old, List<Cheat> fresh) {
        java.util.Set<String> onCodes = new java.util.HashSet<>();
        List<Cheat> result = new ArrayList<>();
        for (Cheat cheat : old) {
            if (cheat.custom) {
                result.add(cheat);
            } else if (cheat.on) {
                onCodes.add(cheat.code);
            }
        }
        for (Cheat cheat : fresh) {
            cheat.on = onCodes.contains(cheat.code);
            result.add(cheat);
        }
        return result;
    }
}
