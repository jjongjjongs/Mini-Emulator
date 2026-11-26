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
            case "nds": case "n64": case "psp": case "ps1": case "mcd": case "ss":
            case "ws": case "ngp": case "dc": case "naomi":
            case "dos": case "arcade": case "neogeo": case "ps2": case "gc": case "wiiu": case "3ds": case "msx":
                return true;
            default:
                return false;
        }
    }

    // Consoles whose engine has no cheat call of its own: the app writes the values into
    // the game's memory itself ("address cheats", see addressCode), and codes are typed as
    // an address, a value and a size.
    static boolean addressMode(String mode) {
        return "dc".equals(mode) || "naomi".equals(mode) || "dos".equals(mode) || "arcade".equals(mode) || "neogeo".equals(mode);
    }

    // Consoles with no usable cheat files in the collection: WonderSwan's are all ROM
    // patches its engine can't take, and there are none for Naomi · Atomiswave.
    static boolean hasCollection(String mode) {
        return !"ws".equals(mode) && !"msx".equals(mode) && !"naomi".equals(mode) && !"ps2".equals(mode) && !"gc".equals(mode);
    }

    // ---- address cheats: "@ADDR:VALUE:BYTES[:TYPE[:REPEAT:ADDRESS_STEP:VALUE_STEP[:B]]]" ----
    // (address in hex, the rest in decimal), applied by the bridge after every frame on the
    // engine's system RAM, the way RetroArch applies its own ("handler 1") cheats.
    static String addressCode(long address, long value, int bytes) {
        return "@" + Long.toHexString(address).toUpperCase(Locale.US) + ":" + value + ":" + bytes;
    }

    // "주소 1EC4B4 · 값 2432" for address cheats; other codes as typed.
    static String displayCode(String code) {
        if (code.startsWith(PACK)) {
            return "Cemu 그래픽 팩 · " + code.substring(PACK.length()).replaceAll(".*/Cheats/", "");
        }
        StringBuilder sb = new StringBuilder();
        for (String part : code.split("\\+")) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            String[] f = part.startsWith("@") ? part.substring(1).split(":") : null;
            if (f != null && f.length >= 3) {
                sb.append("주소 ").append(f[0]).append(" · 값 ").append(f[1]);
                if (f.length >= 7 && !"1".equals(f[4])) {
                    sb.append(" ×").append(f[4]);
                }
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
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
        Map<Integer, Map<String, String>> extra = new HashMap<>();
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
            if (ADDRESS_FIELDS.contains(field)) {
                Map<String, String> fields = extra.get(index);
                if (fields == null) {
                    fields = new HashMap<>();
                    extra.put(index, fields);
                }
                fields.put(field, value.trim());
                continue;
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
        for (Map.Entry<Integer, Cheat> entry : byIndex.entrySet()) {
            Cheat cheat = entry.getValue();
            if (cheat.code.isEmpty() && extra.containsKey(entry.getKey())) {
                cheat.code = fromRetroArch(extra.get(entry.getKey()));
            }
            // Headings in the collection have a description and no code; "??" marks a
            // value the player was meant to fill in.
            if (!cheat.code.isEmpty() && !cheat.code.contains("??")) {
                if (cheat.desc.isEmpty()) {
                    cheat.desc = "치트 " + (list.size() + 1);
                }
                list.add(cheat);
            }
        }
        return list;
    }

    private static final java.util.Set<String> ADDRESS_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "handler", "address", "value", "memory_search_size", "cheat_type", "big_endian",
            "repeat_count", "repeat_add_to_value", "repeat_add_to_address"));

    // A RetroArch address cheat (handler 1) as an address code, or "" when it isn't one
    // the bridge can apply (bit-sized values, rumble-only entries).
    private static String fromRetroArch(Map<String, String> f) {
        if (!"1".equals(f.get("handler"))) {
            return "";
        }
        int bytes;
        switch (f.getOrDefault("memory_search_size", "3")) {
            case "3": bytes = 1; break;
            case "4": bytes = 2; break;
            case "5": bytes = 4; break;
            default: return "";
        }
        try {
            long address = Long.parseLong(f.getOrDefault("address", ""));
            long value = Long.parseLong(f.getOrDefault("value", "0"));
            int type = Integer.parseInt(f.getOrDefault("cheat_type", "1"));
            if (address < 0 || type < 1 || type > 7) {
                return "";
            }
            int repeat = Integer.parseInt(f.getOrDefault("repeat_count", "1"));
            long addressStep = Long.parseLong(f.getOrDefault("repeat_add_to_address", "1"));
            long valueStep = Long.parseLong(f.getOrDefault("repeat_add_to_value", "0"));
            boolean big = "true".equalsIgnoreCase(f.get("big_endian"));
            String code = addressCode(address, value & 0xFFFFFFFFL, bytes) + ":" + type;
            if (repeat > 1 || big) {
                code += ":" + Math.max(1, repeat) + ":" + addressStep + ":" + valueStep;
            }
            return big ? code + ":B" : code;
        } catch (NumberFormatException e) {
            return "";
        }
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
    // Address codes go one per part. Neo Geo Pocket's engine has no cheat call, so its
    // "AAAA:VV" codes (offsets into its 16 KB of RAM) become one-byte address codes.
    static String[] enabledCodes(List<Cheat> cheats, String mode) {
        List<String> codes = new ArrayList<>();
        for (Cheat cheat : cheats) {
            if (!cheat.on || cheat.code.isEmpty()) {
                continue;
            }
            if ("gc".equals(mode) || "wiiu".equals(mode) || "3ds".equals(mode)) {
                continue;  // Dolphin, Cemu and Citra read their codes from files when the game starts
            }
            if ("ps2".equals(mode)) {
                // Plain writes go to the EE's RAM every frame; the rest are in a .pnach.
                List<String> writes = ps2Writes(cheat.code);
                if (writes != null) {
                    codes.addAll(writes);
                }
                continue;
            }
            if (!cheat.code.startsWith("@") && !"ngp".equals(mode)) {
                codes.add(cheat.code);
                continue;
            }
            for (String part : cheat.code.split("\\+")) {
                String trimmed = part.trim();
                java.util.regex.Matcher m = RAM_BYTE.matcher(trimmed);
                if ("ngp".equals(mode) && m.matches()) {
                    codes.add(addressCode(Long.parseLong(m.group(1), 16), Long.parseLong(m.group(2), 16), 1));
                } else if (!trimmed.isEmpty()) {
                    codes.add(trimmed);
                }
            }
        }
        return codes.toArray(new String[0]);
    }

    static int countOn(List<Cheat> cheats) {
        int on = 0;
        for (Cheat cheat : cheats) {
            if (cheat.on && !cheat.code.isEmpty()) {
                on++;
            }
        }
        return on;
    }

    private static final java.util.regex.Pattern RAM_BYTE = java.util.regex.Pattern.compile("([0-9A-Fa-f]{1,5}):([0-9A-Fa-f]{2})");

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
        if (!hasCollection(mode)) {
            return list;
        }
        // DOSBox Pure's and FBNeo's files (RetroArch address cheats on their memory maps).
        if ("dos".equals(mode)) {
            list.add("DOS");
            return list;
        }
        if ("arcade".equals(mode) || "neogeo".equals(mode)) {
            list.add(FBNEO_FOLDER);
            return list;
        }
        if ("wiiu".equals(mode)) {
            list.add(WIIU_FOLDER);
            return list;
        }
        if ("3ds".equals(mode)) {
            list.add(N3DS_FOLDER);
            return list;
        }
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
        if (WIIU_FOLDER.equals(folder) || N3DS_FOLDER.equals(folder)) {
            loadTitleIndexes(context);
            return new ArrayList<>(WIIU_FOLDER.equals(folder) ? wiiuSources.values() : n3dsSources.values());
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

    static final String FBNEO_FOLDER = "FBNeo - Arcade Games";

    // The collection file that fits this ROM best, or null.
    static Source match(Context context, File rom, String mode, String displayName) {
        if ("wiiu".equals(mode) || "3ds".equals(mode)) {
            loadTitleIndexes(context);
            String tid = titleIdOf(rom, mode);
            if (tid == null) {
                return null;
            }
            String group = ("wiiu".equals(mode) ? wiiuByTitle : n3dsByTitle).get(tid);
            return group == null ? null : ("wiiu".equals(mode) ? wiiuSources : n3dsSources).get(group);
        }
        String base = rom.getName().replaceFirst("\\.[^.]+$", "");
        for (String folder : folders(mode, rom.getName().toLowerCase(Locale.US))) {
            List<Source> sources = sources(context, folder);
            if (FBNEO_FOLDER.equals(folder)) {
                // Arcade sets by their short name only ("sf2ce"): a near name is another board.
                for (Source source : sources) {
                    if (source.name.equalsIgnoreCase(base)) {
                        return source;
                    }
                }
                continue;
            }
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
        if (WIIU_FOLDER.equals(source.folder)) {
            return wiiuPackCheats(source.name);
        }
        if (N3DS_FOLDER.equals(source.folder)) {
            String path = n3dsPaths.get(source.name);
            if (path == null) {
                throw new IOException("no file");
            }
            StringBuilder url = new StringBuilder(N3DS_BASE);
            for (String part : path.split("/")) {
                url.append('/').append(encode(part));
            }
            return parseGateway(fetch(url.toString()));
        }
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

    // ---- PS2 (PCSX2): pnach lines or raw codes ----
    // "patch=1,EE,2031D8E4,extended,000F423F" or "2031D8E4 000F423F" (decrypted RAW). The
    // extended codes 0 / 1 / 2 (write 8 / 16 / 32 bits) and the byte / short / word patches
    // the app applies itself on the EE's 32 MB of RAM; anything else (conditions, pointers,
    // copies) goes whole to PCSX2 as a .pnach, read when the game starts.

    private static final java.util.regex.Pattern PNACH = java.util.regex.Pattern.compile(
            "(?i)patch\\s*=\\s*(\\d+)\\s*,\\s*(EE|IOP)\\s*,\\s*(?:0x)?([0-9A-F]{1,8})\\s*,\\s*(byte|short|word|double|extended|beshort|beword|bedouble)\\s*,\\s*(?:0x)?([0-9A-F]{1,16})");
    private static final java.util.regex.Pattern RAW_PS2 = java.util.regex.Pattern.compile("(?i)([0-9A-F]{8})\\s+([0-9A-F]{8})");

    // {address, type, value, place, cpu} of each line; null when a line isn't a PS2 code.
    static List<String[]> ps2Lines(String code) {
        List<String[]> lines = new ArrayList<>();
        for (String part : code.split("\\+")) {
            String t = part.trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("[") || t.toLowerCase(Locale.ROOT).startsWith("comment") || t.toLowerCase(Locale.ROOT).startsWith("author")
                    || t.toLowerCase(Locale.ROOT).startsWith("description") || t.toLowerCase(Locale.ROOT).startsWith("gametitle")) {
                continue;
            }
            java.util.regex.Matcher m = PNACH.matcher(t);
            if (m.matches()) {
                lines.add(new String[]{m.group(3).toUpperCase(Locale.ROOT), m.group(4).toLowerCase(Locale.ROOT), m.group(5).toUpperCase(Locale.ROOT),
                        m.group(1), m.group(2).toUpperCase(Locale.ROOT)});
                continue;
            }
            m = RAW_PS2.matcher(t);
            if (m.matches()) {
                lines.add(new String[]{m.group(1).toUpperCase(Locale.ROOT), "extended", m.group(2).toUpperCase(Locale.ROOT), "1", "EE"});
                continue;
            }
            return null;
        }
        return lines.isEmpty() ? null : lines;
    }

    // The cheat's lines as address codes when all are plain writes, else null.
    static List<String> ps2Writes(String code) {
        List<String[]> lines = ps2Lines(code);
        if (lines == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String[] l : lines) {
            // Only EE writes applied all the time; once-at-boot (place 0) and IOP lines go to PCSX2.
            if (!"1".equals(l[3]) || !"EE".equals(l[4]) || l[2].length() > 8) {
                return null;
            }
            long address = Long.parseLong(l[0], 16);
            long value = Long.parseLong(l[2], 16);
            int bytes;
            switch (l[1]) {
                case "byte": bytes = 1; break;
                case "short": bytes = 2; break;
                case "word": bytes = 4; break;
                default: {
                    int kind = (int) (address >>> 28);
                    if (kind > 2) {
                        return null;
                    }
                    bytes = kind == 0 ? 1 : kind == 1 ? 2 : 4;
                }
            }
            long at = address & 0x01FFFFFFL;
            long mask = bytes == 4 ? 0xFFFFFFFFL : (1L << (8 * bytes)) - 1;
            out.add(addressCode(at, value & mask, bytes));
        }
        return out;
    }

    // The .pnach for the cheats that are on and need PCSX2's own patch engine; "" when none.
    static String ps2Pnach(List<Cheat> cheats) {
        StringBuilder sb = new StringBuilder();
        for (Cheat cheat : cheats) {
            if (!cheat.on || ps2Writes(cheat.code) != null) {
                continue;
            }
            List<String[]> lines = ps2Lines(cheat.code);
            if (lines == null) {
                continue;
            }
            sb.append("// ").append(cheat.desc.replace('\n', ' ')).append('\n');
            for (String[] l : lines) {
                sb.append("patch=").append(l[3]).append(',').append(l[4]).append(',').append(l[0]).append(',').append(l[1]).append(',').append(l[2]).append('\n');
            }
        }
        return sb.length() == 0 ? "" : "gametitle=Mini Emulator cheats\n" + sb;
    }

    // What the editor shows under the code box.
    static String ps2Describe(String code) {
        List<String[]> lines = ps2Lines(code);
        if (lines == null) {
            return "✗ PS2 코드로 읽지 못했어요 (pnach의 patch=… 줄이나 \"XXXXXXXX YYYYYYYY\" 형식). 암호화된 코드는 풀린 코드로 넣어 주세요.";
        }
        return ps2Writes(code) != null ? "✓ 알아봄: " + lines.size() + "줄 · 값 쓰기 → 바로 켜고 끄기"
                : "✓ 알아봄: " + lines.size() + "줄 · PCSX2가 직접 적용하는 코드(조건 · 포인터 · 시작 때 한 번 · IOP)가 있어 다시 시작하면 적용";
    }

    // ---- GameCube / Wii (Dolphin): Gecko and Action Replay ----
    // Stored as "GK:" or "AR:" then the lines; Dolphin reads them from the game's settings
    // file when the game starts.

    static final String GECKO = "GK:";
    static final String ACTION_REPLAY = "AR:";
    private static final java.util.regex.Pattern AR_ENCRYPTED = java.util.regex.Pattern.compile("(?i)[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{5}");
    private static final java.util.regex.Pattern HEX_PAIR = java.util.regex.Pattern.compile("(?i)[0-9A-F]{8} [0-9A-F]{8}");

    // 0 auto, 1 Gecko, 2 Action Replay.
    static String dolphinCode(String code, int kind, boolean wii) {
        String body = dolphinBody(code);
        if (kind == 1) return GECKO + body;
        if (kind == 2) return ACTION_REPLAY + body;
        for (String line : body.split("\\+")) {
            if (AR_ENCRYPTED.matcher(line.trim()).matches()) {
                return ACTION_REPLAY + body;
            }
        }
        if (!wii) {
            // GameCube: Gecko when it uses Gecko-only code types (C0 / C2 / F2 …), else AR.
            for (String line : body.split("\\+")) {
                String t = line.trim().toUpperCase(Locale.ROOT);
                if (t.length() >= 2 && (t.startsWith("C0") || t.startsWith("C2") || t.startsWith("C3") || t.startsWith("F2") || t.startsWith("F4") || t.startsWith("E0"))) {
                    return GECKO + body;
                }
            }
            return ACTION_REPLAY + body;
        }
        return GECKO + body;
    }

    static String dolphinBody(String code) {
        String body = code;
        if (body.startsWith(GECKO) || body.startsWith(ACTION_REPLAY)) {
            body = body.substring(3);
        }
        return body;
    }

    static int dolphinKind(String code) {
        return code.startsWith(ACTION_REPLAY) ? 2 : code.startsWith(GECKO) ? 1 : 0;
    }

    static String dolphinDescribe(String code, int kind, boolean wii) {
        String body = dolphinBody(code);
        int count = 0;
        for (String line : body.split("\\+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!HEX_PAIR.matcher(t).matches() && !AR_ENCRYPTED.matcher(t).matches()) {
                return "✗ 줄 " + (count + 1) + "이 코드 형식이 아니에요 (\"XXXXXXXX YYYYYYYY\" 또는 암호화된 AR \"XXXX-XXXX-XXXXX\").";
            }
            count++;
        }
        if (count == 0) return "";
        String made = dolphinCode(body, kind, wii);
        return "✓ 알아봄: " + (made.startsWith(GECKO) ? "Gecko" : "액션리플레이") + " " + count + "줄 (" + (wii ? "Wii" : "게임큐브") + ") · 다시 시작하면 적용";
    }

    // The [ActionReplay] / [Gecko] sections for the codes that are on.
    static String dolphinIni(List<Cheat> cheats) {
        StringBuilder ar = new StringBuilder();
        StringBuilder arOn = new StringBuilder();
        StringBuilder gk = new StringBuilder();
        StringBuilder gkOn = new StringBuilder();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Cheat cheat : cheats) {
            if (!cheat.on || cheat.code.isEmpty()) continue;
            String name = cheat.desc.replace('\n', ' ').replace('[', '(').replace(']', ')').trim();
            if (name.isEmpty()) name = "Cheat";
            String unique = name;
            for (int n = 2; !names.add(unique); n++) unique = name + " " + n;
            boolean gecko = !cheat.code.startsWith(ACTION_REPLAY);
            StringBuilder body = gecko ? gk : ar;
            body.append('$').append(unique).append('\n');
            for (String line : dolphinBody(cheat.code).split("\\+")) {
                if (!line.trim().isEmpty()) body.append(line.trim().toUpperCase(Locale.ROOT)).append('\n');
            }
            (gecko ? gkOn : arOn).append('$').append(unique).append('\n');
        }
        StringBuilder out = new StringBuilder();
        if (ar.length() > 0) out.append("[ActionReplay]\n").append(ar).append("[ActionReplay_Enabled]\n").append(arOn);
        if (gk.length() > 0) out.append("[Gecko]\n").append(gk).append("[Gecko_Enabled]\n").append(gkOn);
        return out.toString();
    }

    // ---- .pnach files ----
    // PCSX2's cheat / patch files: each [Name] (PCSX2 2.x, "[Cheats/Infinite HP]" too) becomes
    // a cheat of its own; lines before any [Name] (older files) one cheat named after the file.
    // Plain lists of codes (0.0.374): a name line ("#Name", "[Name]" or any text) and the code
    // lines under it ("8007F5FC 0005", "8007F5FC0005"), as PS1 code sites and ePSXe / DuckStation
    // style files give them. Codes of one name are joined with "+", as in a .cht file.
    static List<Cheat> parsePlain(String text) {
        List<Cheat> list = new ArrayList<>();
        String name = null;
        StringBuilder code = new StringBuilder();
        java.util.regex.Pattern codeLine = java.util.regex.Pattern.compile("^([0-9A-Fa-f]{8})[ :-]?([0-9A-Fa-f]{4,8})$");
        for (String raw : (text + "\n#").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = codeLine.matcher(line);
            if (m.matches()) {
                if (code.length() > 0) code.append('+');
                code.append(m.group(1).toUpperCase(java.util.Locale.US)).append(' ').append(m.group(2).toUpperCase(java.util.Locale.US));
                continue;
            }
            if (code.length() > 0) {
                list.add(new Cheat(name == null || name.isEmpty() ? "치트 " + (list.size() + 1) : name, code.toString(), false, true));
                code.setLength(0);
            }
            String label = line;
            if (label.startsWith("#")) label = label.substring(1).trim();
            if (label.startsWith("[") && label.endsWith("]")) label = label.substring(1, label.length() - 1).trim();
            name = label;
        }
        return list;
    }

    // (0.0.407) PSP: a CWCheat file as PPSSPP keeps it (<게임 ID>.ini, cheat.db): "_C0 이름" (or _C1,
    // on) starts a cheat, its "_L 0x… 0x…" lines are the code; _S / _G name the game.
    static boolean looksCwCheat(String text) {
        return java.util.regex.Pattern.compile("(?m)^\\s*_C[01]").matcher(text).find()
                && java.util.regex.Pattern.compile("(?m)^\\s*_L\\s").matcher(text).find();
    }

    static List<Cheat> parseCwCheat(String text) {
        List<Cheat> list = new ArrayList<>();
        String name = null;
        StringBuilder code = new StringBuilder();
        java.util.regex.Pattern codeLine = java.util.regex.Pattern.compile("^_L\\s+(0x[0-9A-Fa-f]{8})\\s+(0x[0-9A-Fa-f]{8})");
        for (String raw : (text.replace("\r", "") + "\n_C0 ").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("_C")) {
                if (name != null && code.length() > 0) {
                    list.add(new Cheat(name.isEmpty() ? "치트 " + (list.size() + 1) : name, code.toString(), false, true));
                }
                code.setLength(0);
                name = line.length() > 3 ? line.substring(3).trim() : "";
                continue;
            }
            java.util.regex.Matcher m = codeLine.matcher(line);
            if (name != null && m.find()) {
                if (code.length() > 0) code.append('+');
                code.append("_L ").append(m.group(1)).append(' ').append(m.group(2));
            }
        }
        return list;
    }

    static List<Cheat> parsePnach(String text, String fileName) {
        List<Cheat> out = new ArrayList<>();
        String name = null;
        StringBuilder code = new StringBuilder();
        String fallback = fileName == null ? "pnach" : fileName.replaceFirst("(?i)\\.pnach$", "");
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            int comment = line.indexOf("//");
            if (comment >= 0) {
                line = line.substring(0, comment).trim();
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                addPnachCheat(out, name != null ? name : fallback, code);
                name = line.substring(1, line.length() - 1).trim();
                int slash = name.lastIndexOf('/');
                if (slash >= 0 && slash < name.length() - 1) {
                    name = name.substring(slash + 1).trim();
                }
                code.setLength(0);
                continue;
            }
            if (PNACH.matcher(line).matches()) {
                if (code.length() > 0) code.append('+');
                code.append(line.replaceAll("\\s+", ""));
            }
        }
        addPnachCheat(out, name != null ? name : fallback, code);
        return out;
    }

    private static void addPnachCheat(List<Cheat> out, String name, StringBuilder code) {
        if (code.length() == 0) {
            return;
        }
        out.add(new Cheat(name.isEmpty() ? "치트 " + (out.size() + 1) : name, code.toString(), false, true));
    }

    // A pnach section that changes the game rather than cheating (widescreen, interlacing, fps).
    static boolean isPnachPatch(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("widescreen") || lower.contains("16:9") || lower.contains("16x9") || lower.contains("interlac")
                || lower.contains("60 fps") || lower.contains("60fps") || lower.contains("ntsc") || lower.contains("pal ") || lower.contains("aspect");
    }

    // ---- Wii U: Cemu's official cheat graphic packs (CC0) ----
    // assets/wiiu_cheats.tsv (cores/gen-cheat-indexes.py): title IDs, name, description,
    // folder, files. A pack is a cheat whose code is "PACK:<folder>"; when on, its files are
    // fetched into <system>/Cemu/customGraphicPacks/MiniCheats and turned on for Cemu (read
    // when the game starts).
    // ---- 3DS: the CTRPF-AR-CHEAT-CODES collection, Gateway codes for Citra's own cheat engine ----
    // assets/n3ds_cheats.tsv: title ID and file; written for Citra as cheats/<title ID>.txt.

    static final String WIIU_FOLDER = "Wii U 치트 팩";
    static final String N3DS_FOLDER = "3DS 치트 모음";
    static final String PACK = "PACK:";
    static final String WIIU_BASE = "https://raw.githubusercontent.com/cemu-project/cemu_graphic_packs/efa14afb2f6b25080c19878bb6c2ea722d78655b";
    static final String N3DS_BASE = "https://raw.githubusercontent.com/iSharingan/CTRPF-AR-CHEAT-CODES/a931ae0f4e9f0165d15b1b652e5d1511430d8196";

    static final class Pack {
        String ids;
        String name;
        String desc;
        String folder;
        String[] files;
    }

    private static Map<String, Source> wiiuSources;
    private static final Map<String, String> wiiuByTitle = new HashMap<>();
    private static final Map<String, List<Pack>> wiiuPacks = new HashMap<>();
    private static final Map<String, Pack> packByFolder = new HashMap<>();
    private static Map<String, Source> n3dsSources;
    private static final Map<String, String> n3dsByTitle = new HashMap<>();
    private static final Map<String, String> n3dsPaths = new HashMap<>();

    private static synchronized void loadTitleIndexes(Context context) {
        if (wiiuSources != null) {
            return;
        }
        wiiuSources = new java.util.TreeMap<>();
        n3dsSources = new java.util.TreeMap<>();
        for (String line : assetLines(context, "wiiu_cheats.tsv")) {
            String[] f = line.split("\t", -1);
            if (f.length < 5) continue;
            Pack pack = new Pack();
            pack.ids = f[0];
            pack.name = f[1];
            pack.desc = f[2];
            pack.folder = f[3];
            pack.files = f[4].split(",");
            // The game: the pack folder's game part ("src/BreathOfTheWild/Cheats/X" -> BreathOfTheWild).
            String[] parts = pack.folder.split("/");
            String game = parts.length >= 2 ? parts[1] : pack.folder;
            wiiuPacks.computeIfAbsent(game, k -> new ArrayList<>()).add(pack);
            packByFolder.put(pack.folder, pack);
            for (String id : pack.ids.split(",")) {
                if (!id.isEmpty()) wiiuByTitle.put(id.toUpperCase(Locale.ROOT), game);
            }
        }
        for (Map.Entry<String, List<Pack>> e : wiiuPacks.entrySet()) {
            wiiuSources.put(e.getKey(), new Source(WIIU_FOLDER, e.getKey(), e.getValue().size()));
        }
        for (String line : assetLines(context, "n3ds_cheats.tsv")) {
            String[] f = line.split("\t", -1);
            if (f.length < 2) continue;
            String[] parts = f[1].split("/");
            String game = parts.length >= 3 ? parts[parts.length - 2] : f[0];
            if (n3dsSources.containsKey(game)) game = game + " " + f[0];
            n3dsByTitle.put(f[0].toUpperCase(Locale.ROOT), game);
            n3dsPaths.put(game, f[1]);
            n3dsSources.put(game, new Source(N3DS_FOLDER, game, 0));
        }
    }

    private static List<String> assetLines(Context context, String name) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#") && !line.trim().isEmpty()) lines.add(line);
            }
        } catch (IOException e) {
            // No index in this build.
        }
        return lines;
    }

    // The game's title ID as 16 hex digits: a 3DS file's own, a Wii U .wua's.
    static String titleIdOf(File rom, String mode) {
        long tid = "3ds".equals(mode) ? N3dsCia.titleIdOf(rom)
                : rom.getName().toLowerCase(Locale.ROOT).endsWith(".wua") ? OverlayPatches.wuaTitleId(rom) : 0;
        return tid == 0 ? null : String.format(Locale.US, "%016X", tid);
    }

    private static List<Cheat> wiiuPackCheats(String game) {
        List<Cheat> list = new ArrayList<>();
        List<Pack> packs = wiiuPacks.get(game);
        if (packs == null) return list;
        for (Pack pack : packs) {
            String desc = pack.name + (pack.desc.isEmpty() ? "" : " — " + pack.desc);
            list.add(new Cheat(desc, PACK + pack.folder, false, false));
        }
        return list;
    }

    static Pack pack(Context context, String code) {
        loadTitleIndexes(context);
        return code.startsWith(PACK) ? packByFolder.get(code.substring(PACK.length())) : null;
    }

    static byte[] fetchBytes(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0 && out.size() < 16 * 1024 * 1024) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String fetch(String address) throws IOException {
        return new String(fetchBytes(address), StandardCharsets.UTF_8);
    }

    // Puts the Wii U cheat packs that are on into <cemu>/customGraphicPacks/MiniCheats, fetching
    // what isn't there yet, and writes the options that turn them on. Throws when a pack
    // couldn't be fetched.
    static void syncWiiuPacks(Context context, File cemuDir, List<Cheat> cheats) throws IOException {
        File base = new File(new File(cemuDir, "customGraphicPacks"), "MiniCheats");
        File store = new File(context.getFilesDir(), "cheats/wiiu_packs");
        StringBuilder options = new StringBuilder();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Cheat cheat : cheats) {
            Pack pack = cheat.on ? pack(context, cheat.code) : null;
            if (pack == null) continue;
            String id = pack.folder.replaceAll("[^A-Za-z0-9]+", "_");
            File cached = new File(store, id);
            boolean presets = false;
            for (String name : pack.files) {
                File file = new File(cached, name);
                if (!file.isFile()) {
                    cached.mkdirs();
                    StringBuilder url = new StringBuilder(WIIU_BASE);
                    for (String part : (pack.folder + "/" + name).split("/")) url.append('/').append(encode(part));
                    byte[] data = fetchBytes(url.toString());
                    File tmp = new File(file.getPath() + ".tmp");
                    try (OutputStream out = new FileOutputStream(tmp)) {
                        out.write(data);
                    }
                    if (!tmp.renameTo(file)) throw new IOException("저장하지 못했어요");
                }
                if (name.equals("rules.txt")) {
                    presets = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("[Preset]");
                }
            }
            File dest = new File(base, id);
            deleteTree(dest);
            dest.mkdirs();
            for (String name : pack.files) {
                copyFile(new File(cached, name), new File(dest, name));
            }
            wanted.add(id);
            options.append(OverlayPatches.cemuPackKey("MiniCheats/" + id, presets ? "\u001eenabled" : "")).append("=enabled\n");
        }
        File[] old = base.listFiles();
        for (File f : old == null ? new File[0] : old) {
            if (f.isDirectory() && !wanted.contains(f.getName())) deleteTree(f);
        }
        base.mkdirs();
        File opts = new File(base, "options.txt");
        if (options.length() == 0) {
            opts.delete();
        } else {
            try (OutputStream out = new FileOutputStream(opts)) {
                out.write(options.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    // A Gateway / Action Replay file: [Name] then "XXXXXXXX YYYYYYYY" lines; notes are skipped.
    static List<Cheat> parseGateway(String text) {
        List<Cheat> out = new ArrayList<>();
        String name = null;
        StringBuilder code = new StringBuilder();
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (name != null && code.length() > 0) out.add(new Cheat(name, code.toString(), false, false));
                name = line.substring(1, line.length() - 1).trim();
                code.setLength(0);
            } else if (name != null && HEX_PAIR.matcher(line.replaceAll("\\s+", " ")).matches()) {
                if (code.length() > 0) code.append('+');
                code.append(line.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));
            }
        }
        if (name != null && code.length() > 0) out.add(new Cheat(name, code.toString(), false, false));
        return out;
    }

    // Citra's cheats/<title ID>.txt: every cheat, the ones that are on marked.
    static String citraCheatFile(List<Cheat> cheats) {
        StringBuilder sb = new StringBuilder();
        for (Cheat cheat : cheats) {
            if (cheat.code.isEmpty()) continue;
            sb.append('[').append(cheat.desc.replace('\n', ' ').replace('[', '(').replace(']', ')')).append("]\n");
            if (cheat.on) sb.append("*citra_enabled\n");
            for (String line : cheat.code.split("\\+")) {
                if (!line.trim().isEmpty()) sb.append(line.trim()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String n3dsDescribe(String code) {
        int n = 0;
        for (String line : code.split("\\+")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (!HEX_PAIR.matcher(t).matches()) {
                return "✗ 줄 " + (n + 1) + "이 Gateway 코드 형식이 아니에요 (\"XXXXXXXX YYYYYYYY\").";
            }
            n++;
        }
        return n == 0 ? "" : "✓ 알아봄: Gateway " + n + "줄 · 다시 시작하면 적용";
    }
}
