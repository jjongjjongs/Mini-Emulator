package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

// Game covers for the ROM list: box art from the libretro-thumbnails collection, matched by
// the ROM's name against assets/covers.bin (cores/gen-covers.py), or a picture the player
// picked. Saved small under files/covers/<rom folder>/<rom name>.jpg.
final class Covers {
    interface Listener {
        void onCover(File rom, File cover);
    }

    private static final String BASE = "https://raw.githubusercontent.com/libretro-thumbnails/";
    private static final int SIZE = 256;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<String> pending = new HashSet<>();
    private static Map<String, List<String>> index;
    private static final Map<String, List<String>> titles = new HashMap<>();

    private Covers() {
    }

    static File file(Context context, File rom) {
        File dir = rom.getParentFile();
        return new File(context.getFilesDir(), "covers/" + (dir == null ? "roms" : dir.getName()) + "/" + rom.getName() + ".jpg");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("covers", 0);
    }

    private static String key(File rom) {
        File dir = rom.getParentFile();
        return (dir == null ? "" : dir.getName()) + "/" + rom.getName();
    }

    // Whether the collection was already searched for this game (found or not).
    static boolean searched(Context context, File rom) {
        return prefs(context).contains(key(rom));
    }

    static void forget(Context context, File rom) {
        prefs(context).edit().remove(key(rom)).apply();
        File cover = file(context, rom);
        if (cover.exists() && !cover.delete()) {
            cover.deleteOnExit();
        }
    }

    // No cover, and don't look for one again until asked.
    static void clear(Context context, File rom) {
        forget(context, rom);
        prefs(context).edit().putString(key(rom), "-").apply();
    }

    static void move(Context context, File from, File to) {
        File cover = file(context, from);
        if (cover.exists()) {
            File target = file(context, to);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            cover.renameTo(target);
        }
        SharedPreferences prefs = prefs(context);
        String found = prefs.getString(key(from), null);
        SharedPreferences.Editor editor = prefs.edit().remove(key(from));
        if (found != null) {
            editor.putString(key(to), found);
        }
        editor.apply();
    }

    // Repositories to look in for a ROM of this mode, most likely first.
    static List<String> repos(String mode, String lowerName) {
        List<String> list = new ArrayList<>();
        switch (mode) {
            case "gba":
                if (lowerName.endsWith(".gbc")) {
                    list.add("Nintendo_-_Game_Boy_Color");
                    list.add("Nintendo_-_Game_Boy");
                } else if (lowerName.endsWith(".gb")) {
                    list.add("Nintendo_-_Game_Boy");
                    list.add("Nintendo_-_Game_Boy_Color");
                } else {
                    list.add("Nintendo_-_Game_Boy_Advance");
                }
                break;
            case "nds": list.add("Nintendo_-_Nintendo_DS"); break;
            case "snes": list.add("Nintendo_-_Super_Nintendo_Entertainment_System"); break;
            case "nes": list.add("Nintendo_-_Nintendo_Entertainment_System"); break;
            case "md": list.add("Sega_-_Mega_Drive_-_Genesis"); break;
            case "sms":
                if (lowerName.endsWith(".gg")) {
                    list.add("Sega_-_Game_Gear");
                } else {
                    list.add("Sega_-_Master_System_-_Mark_III");
                }
                break;
            case "pce":
                if (lowerName.endsWith(".pce")) {
                    list.add("NEC_-_PC_Engine_-_TurboGrafx_16");
                } else {
                    list.add("NEC_-_PC_Engine_CD_-_TurboGrafx-CD");
                    list.add("NEC_-_PC_Engine_-_TurboGrafx_16");
                }
                break;
            case "n64": list.add("Nintendo_-_Nintendo_64"); break;
            case "psp": list.add("Sony_-_PlayStation_Portable"); break;
            case "ps1": list.add("Sony_-_PlayStation"); break;
            case "ps2": list.add("Sony_-_PlayStation_2"); break;
            case "dc": list.add("Sega_-_Dreamcast"); break;
            default: break;
        }
        return list;
    }

    // Looks the game up once in the background; the listener runs on the main thread when
    // a cover was saved.
    static void fetch(Context context, File rom, String mode, String displayName, Listener listener) {
        Context app = context.getApplicationContext();
        String key = key(rom);
        synchronized (pending) {
            if (!pending.add(key)) {
                return;
            }
        }
        worker.execute(() -> {
            try {
                String found = find(app, rom, mode, displayName);
                prefs(app).edit().putString(key, found == null ? "-" : found).apply();
                if (found != null) {
                    File cover = file(app, rom);
                    main.post(() -> listener.onCover(rom, cover));
                }
            } catch (IOException e) {
                // Offline or the server is down: try again the next time the list is shown.
            } finally {
                synchronized (pending) {
                    pending.remove(key);
                }
            }
        });
    }

    // Downloads the best cover; returns its "<repository>/<name>" or null when there is none.
    private static String find(Context context, File rom, String mode, String displayName) throws IOException {
        String base = rom.getName().replaceFirst("\\.[^.]+$", "");
        boolean arcade = "arcade".equals(mode) || "neogeo".equals(mode);
        if (arcade) {
            // Arcade covers are named after the set's DAT description.
            for (String description : GameTitles.descriptions(context, base)) {
                for (String repo : new String[]{"FBNeo_-_Arcade_Games", "MAME"}) {
                    if (download(context, rom, repo, description)) {
                        return repo + "/" + description;
                    }
                }
            }
            return null;
        }
        for (String repo : repos(mode, rom.getName().toLowerCase(Locale.US))) {
            List<String> names = names(context, repo);
            String match = CoverMatcher.match(base, names, titles(repo, names));
            if (match == null && displayName != null && !displayName.equals(base)) {
                match = CoverMatcher.match(displayName, names, titles(repo, names));
            }
            if (match != null && download(context, rom, repo, match)) {
                return repo + "/" + match;
            }
        }
        return null;
    }

    private static synchronized List<String> names(Context context, String repo) {
        if (index == null) {
            Map<String, List<String>> map = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(context.getAssets().open("covers.bin")), StandardCharsets.UTF_8))) {
                List<String> current = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) {
                        current = new ArrayList<>();
                        map.put(line.substring(1), current);
                    } else if (current != null && !line.isEmpty()) {
                        current.add(line);
                    }
                }
            } catch (IOException e) {
                // No index: no covers, the list works as before.
            }
            index = map;
        }
        List<String> names = index.get(repo);
        return names == null ? new ArrayList<>() : names;
    }

    private static synchronized List<String> titles(String repo, List<String> names) {
        List<String> list = titles.get(repo);
        if (list == null) {
            list = new ArrayList<>(names.size());
            for (String name : names) {
                list.add(CoverMatcher.title(name));
            }
            titles.put(repo, list);
        }
        return list;
    }

    private static boolean download(Context context, File rom, String repo, String name) throws IOException {
        byte[] data = get(BASE + repo + "/master/Named_Boxarts/" + encode(CoverMatcher.fileName(name)) + ".png");
        // Some entries are links whose content is the path of the real picture.
        if (data != null && data.length < 512 && !isImage(data)) {
            String target = new String(data, StandardCharsets.UTF_8).trim();
            target = target.substring(target.lastIndexOf('/') + 1);
            data = target.endsWith(".png") ? get(BASE + repo + "/master/Named_Boxarts/" + encode(target.substring(0, target.length() - 4)) + ".png") : null;
        }
        if (data == null || !isImage(data)) {
            return false;
        }
        Bitmap bitmap = decode(data);
        return bitmap != null && save(context, rom, bitmap);
    }

    private static boolean isImage(byte[] data) {
        return data.length > 8 && ((data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                || (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8);
    }

    private static String encode(String text) throws IOException {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
    }

    // The body of a 200 response, null for 404; other failures throw so they are retried.
    private static byte[] get(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        try {
            int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > 8 * 1024 * 1024) {
                        return null;
                    }
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    // Decodes at about the list's size so big pictures don't use much memory.
    static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.min(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= SIZE) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    static boolean save(Context context, File rom, Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = Math.min(1.0f, (float) SIZE * 2 / Math.max(width, height));
        Bitmap small = scale < 1.0f ? Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true) : bitmap;
        File cover = file(context, rom);
        File dir = cover.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(cover.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            if (!small.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                return false;
            }
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(cover);
    }

    // A picture the player chose (from the gallery or files) as this game's cover.
    static void setCustom(Context context, File rom, android.net.Uri uri, Runnable onDone, Runnable onFail) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            boolean ok = false;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) > 0 && out.size() < 32 * 1024 * 1024) {
                        out.write(buffer, 0, read);
                    }
                    Bitmap bitmap = decode(out.toByteArray());
                    ok = bitmap != null && save(app, rom, bitmap);
                }
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            if (ok) {
                prefs(app).edit().putString(key(rom), "custom").apply();
            }
            main.post(ok ? onDone : onFail);
        });
    }

    // Forgets the result and searches again; reports a message when no cover came of it.
    static void research(Context context, File rom, String mode, String displayName, Listener listener,
                         java.util.function.Consumer<String> onNone) {
        forget(context, rom);
        Context app = context.getApplicationContext();
        fetch(app, rom, mode, displayName, listener);
        worker.execute(() -> {
            String result = prefs(app).getString(key(rom), null);
            if (result == null) {
                main.post(() -> onNone.accept("표지를 받지 못했어요. 인터넷 연결을 확인해 주세요."));
            } else if ("-".equals(result)) {
                main.post(() -> onNone.accept("표지 모음에서 이 게임을 찾지 못했어요. 길게 눌러 사진을 직접 고를 수 있어요."));
            }
        });
    }
}
