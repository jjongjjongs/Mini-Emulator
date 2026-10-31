package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 꾸미기 프리셋: the look of one console's screen (case style, photo background, button
 * transparency, button style, button layout and screen filter) bundled under a name.
 *
 * Each preset is a folder files/presets/&lt;id&gt;/ with preset.json and the photo files it
 * needs (the layer sources and the rendered backgrounds, so applying is a copy). Built-in
 * presets live in code and have no folder. "undo" is the special preset taken right before
 * a preset is applied, so the previous look can be brought back.
 */
final class Presets {
    static final String UNDO_ID = "undo";
    static final int FORMAT = 1;

    /** What a preset may hold; the save sheet lets each be left out. */
    static final int ITEM_SHELL = 0;
    static final int ITEM_PHOTO = 1;
    static final int ITEM_ALPHA = 2;
    static final int ITEM_BUTTONS = 3;
    static final int ITEM_LAYOUT = 4;
    static final int ITEM_FILTER = 5;
    static final String[] ITEM_NAMES = {"배경 케이스", "사진 배경 · 어둡게 · 흐림", "버튼 투명도", "버튼 꾸미기", "버튼 배치", "화면 필터"};

    /** Scope of a save: this console's current orientation, both, or the console-free parts. */
    static final int SCOPE_ONE = 0;
    static final int SCOPE_BOTH = 1;
    static final int SCOPE_COMMON = 2;

    static final class Preset {
        String id;
        String name;
        long created;
        boolean builtin;
        boolean favorite;
        /** The console it was taken from, or "all" for a console-free one. */
        String mode = "all";
        String modeName = "";
        JSONObject data = new JSONObject();

        boolean has(int item) {
            return data.has(itemKey(item));
        }

        /** The orientations it carries anything for. */
        boolean hasOrientation(boolean landscape) {
            JSONObject orient = data.optJSONObject(landscape ? "L" : "P");
            return orient != null && orient.length() > 0;
        }

        String summary() {
            List<String> parts = new ArrayList<>();
            JSONObject p = data.optJSONObject("P");
            JSONObject l = data.optJSONObject("L");
            JSONObject any = p != null && p.length() > 0 ? p : l;
            if (has(ITEM_SHELL)) {
                int style = data.optInt(itemKey(ITEM_SHELL), 0);
                parts.add(style >= 0 && style < ClearShellView.STYLE_NAMES.length ? ClearShellView.STYLE_NAMES[style] : "케이스");
            }
            if (any != null && any.has("photo")) {
                JSONObject photo = any.optJSONObject("photo");
                int count = photo != null ? photo.optJSONArray("layers") != null ? photo.optJSONArray("layers").length() : 0 : 0;
                parts.add("사진 " + count + "장");
            }
            if (any != null && any.has("buttons")) {
                ButtonStyle style = ButtonStyle.parse(any.optString("buttons"));
                String theme = ButtonStyle.THEME_NAMES[style.theme].replace(" (케이스 색)", "");
                parts.add(theme + (style.sizeAll != 100 || style.gap != 100 ? " · 크기" : ""));
            }
            if (any != null && any.has("layout")) {
                parts.add("바꾼 배치");
            }
            if (has(ITEM_FILTER)) {
                parts.add("필터");
            }
            String orient = p != null && p.length() > 0 && l != null && l.length() > 0 ? "세로 · 가로" : l != null && l.length() > 0 ? "가로" : "세로";
            String where = "all".equals(mode) ? "모든 게임기" : modeName + " " + orient;
            return where + (parts.isEmpty() ? "" : " · " + String.join(" · ", parts));
        }

        /** The A and B colours and the case tint, for the little preview. */
        int[] previewColors() {
            JSONObject any = data.optJSONObject("P");
            if (any == null || any.length() == 0) any = data.optJSONObject("L");
            ButtonStyle style = any != null && any.has("buttons") ? ButtonStyle.parse(any.optString("buttons")) : new ButtonStyle();
            int a = style.theme == ButtonStyle.THEME_DEFAULT ? Color.rgb(227, 38, 58) : style.color(ButtonStyle.ROLE_A);
            int b = style.theme == ButtonStyle.THEME_DEFAULT ? Color.rgb(24, 182, 204) : style.color(ButtonStyle.ROLE_B);
            int d = style.theme == ButtonStyle.THEME_DEFAULT ? Color.rgb(58, 58, 63) : style.color(ButtonStyle.ROLE_DPAD);
            int shell = data.optInt(itemKey(ITEM_SHELL), -1);
            int tint;
            switch (shell) {
                case ClearShellView.STYLE_DARK: case ClearShellView.STYLE_NEON: tint = Color.rgb(27, 29, 35); break;
                case ClearShellView.STYLE_PASTEL: tint = Color.rgb(236, 226, 246); break;
                case ClearShellView.STYLE_METAL: tint = Color.rgb(96, 100, 110); break;
                case ClearShellView.STYLE_PLASTIC: tint = Color.rgb(150, 140, 200); break;
                case ClearShellView.STYLE_PHOTO: tint = Color.rgb(120, 70, 110); break;
                default: tint = Color.rgb(205, 210, 220);
            }
            return new int[]{tint, d, a, b};
        }
    }

    private Presets() {
    }

    static String itemKey(int item) {
        switch (item) {
            case ITEM_SHELL: return "shell";
            case ITEM_FILTER: return "filter";
            default: return "orient";
        }
    }

    // ---- storage ----

    static File dir(Context context) {
        return new File(context.getFilesDir(), "presets");
    }

    static File dir(Context context, String id) {
        return new File(dir(context), id);
    }

    /** Saved presets, favourites first then newest first; the undo one last if present. */
    static List<Preset> list(Context context) {
        List<Preset> out = new ArrayList<>();
        File[] dirs = dir(context).listFiles(File::isDirectory);
        Preset undo = null;
        if (dirs != null) {
            for (File folder : dirs) {
                Preset preset = read(folder);
                if (preset == null) continue;
                if (UNDO_ID.equals(preset.id)) {
                    undo = preset;
                } else {
                    out.add(preset);
                }
            }
        }
        Collections.sort(out, (x, y) -> x.favorite != y.favorite ? (x.favorite ? -1 : 1) : Long.compare(y.created, x.created));
        if (undo != null) out.add(undo);
        return out;
    }

    static Preset read(File folder) {
        File file = new File(folder, "preset.json");
        if (!file.isFile()) return null;
        try {
            JSONObject json = new JSONObject(readText(file));
            Preset preset = new Preset();
            preset.id = folder.getName();
            preset.name = json.optString("name", folder.getName());
            preset.created = json.optLong("created", file.lastModified());
            preset.favorite = json.optBoolean("favorite", false);
            preset.mode = json.optString("mode", "all");
            preset.modeName = json.optString("modeName", "");
            preset.data = json.optJSONObject("data") != null ? json.getJSONObject("data") : new JSONObject();
            return preset;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static void write(Context context, Preset preset) throws IOException {
        File folder = dir(context, preset.id);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("프리셋 폴더를 만들 수 없어요");
        }
        try {
            JSONObject json = new JSONObject();
            json.put("format", FORMAT);
            json.put("app", "Mini Emulator");
            json.put("name", preset.name);
            json.put("created", preset.created);
            json.put("favorite", preset.favorite);
            json.put("mode", preset.mode);
            json.put("modeName", preset.modeName);
            json.put("data", preset.data);
            writeText(new File(folder, "preset.json"), json.toString(2));
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    static void delete(Context context, String id) {
        N3dsCia.deleteTree(dir(context, id));
    }

    static String newId() {
        return "p" + Long.toString(System.currentTimeMillis(), 36);
    }

    // ---- capture ----

    /**
     * Takes the current look of a console. items says what to include; orientations which
     * screen directions (both when scope is SCOPE_BOTH); SCOPE_COMMON leaves the layout out
     * and marks the preset console-free.
     */
    static Preset capture(Context context, String id, String name, String mode, String modeName, String layoutConsole,
                          boolean landscapeNow, int scope, boolean[] items) throws IOException {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        Preset preset = new Preset();
        preset.id = id;
        preset.name = name;
        preset.created = System.currentTimeMillis();
        preset.mode = scope == SCOPE_COMMON ? "all" : mode;
        preset.modeName = modeName;
        File folder = dir(context, id);
        if (folder.isDirectory()) {
            N3dsCia.deleteTree(folder);
        }
        if (!folder.mkdirs()) {
            throw new IOException("프리셋 폴더를 만들 수 없어요");
        }
        try {
            JSONObject data = new JSONObject();
            if (items[ITEM_SHELL]) {
                data.put("shell", prefs.getInt("shell_style", ClearShellView.STYLE_CLEAR));
            }
            if (items[ITEM_FILTER]) {
                data.put("filter", prefs.getInt("screen_filter", 0));
            }
            boolean[] orientations = scope == SCOPE_ONE ? new boolean[]{landscapeNow} : new boolean[]{false, true};
            for (boolean landscape : orientations) {
                JSONObject orient = new JSONObject();
                String bgScope = PhotoBackground.scopeInForce(context, mode);
                if (items[ITEM_PHOTO]) {
                    File rendered = PhotoBackground.renderedFile(context, bgScope, landscape);
                    if (rendered.isFile()) {
                        JSONObject photo = new JSONObject();
                        photo.put("dim", prefs.getInt("bg:" + bgScope + ":dim", 0));
                        photo.put("blur", prefs.getInt("bg:" + bgScope + ":blur", 0));
                        String renderedName = "bg_" + PhotoBackground.orient(landscape) + ".jpg";
                        PhotoBackground.copy(rendered, new File(folder, renderedName));
                        photo.put("rendered", renderedName);
                        JSONArray layers = new JSONArray();
                        int count = prefs.getInt("bg:" + bgScope + ":" + PhotoBackground.orient(landscape) + ":count", 0);
                        for (int i = 0; i < Math.min(count, PhotoBackground.MAX_LAYERS); i++) {
                            File source = PhotoBackground.source(context, bgScope, landscape, i);
                            if (!source.isFile()) continue;
                            String sourceName = "src_" + PhotoBackground.orient(landscape) + "_" + i + ".jpg";
                            PhotoBackground.copy(source, new File(folder, sourceName));
                            JSONObject layer = new JSONObject();
                            layer.put("file", sourceName);
                            layer.put("zoom", prefs.getFloat(PhotoBackground.layerKey(bgScope, landscape, i, "zoom"), 1.0f));
                            layer.put("ox", prefs.getFloat(PhotoBackground.layerKey(bgScope, landscape, i, "ox"), 0));
                            layer.put("oy", prefs.getFloat(PhotoBackground.layerKey(bgScope, landscape, i, "oy"), 0));
                            layer.put("rot", prefs.getFloat(PhotoBackground.layerKey(bgScope, landscape, i, "rot"), 0));
                            layer.put("shape", prefs.getInt(PhotoBackground.layerKey(bgScope, landscape, i, "shape"), 0));
                            layers.put(layer);
                        }
                        photo.put("layers", layers);
                        orient.put("photo", photo);
                    }
                }
                if (items[ITEM_ALPHA]) {
                    orient.put("alpha", prefs.getInt("bg:" + bgScope + ":btn", PhotoBackground.DEFAULT_BUTTON_ALPHA));
                }
                if (items[ITEM_BUTTONS]) {
                    orient.put("buttons", ButtonStyle.load(context, mode, null, landscape).serialize());
                }
                if (items[ITEM_LAYOUT] && scope != SCOPE_COMMON) {
                    JSONObject layout = new JSONObject();
                    String prefix = "layout:" + layoutConsole + ":" + (landscape ? "L" : "P") + ":";
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String) {
                            layout.put(entry.getKey().substring(prefix.length()), entry.getValue());
                        }
                    }
                    if (layout.length() > 0) {
                        orient.put("layout", layout);
                    }
                }
                data.put(landscape ? "L" : "P", orient);
            }
            preset.data = data;
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        write(context, preset);
        return preset;
    }

    // ---- apply ----

    /**
     * Writes a preset's settings for a console. Orientations the preset lacks are left as
     * they are. Returns what was skipped (a layout from another console), or null.
     */
    static String apply(Context context, Preset preset, String mode, String layoutConsole, boolean[] orientations) throws IOException {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        return apply(context, preset, mode, layoutConsole, orientations, shortSide, longSide);
    }

    static String apply(Context context, Preset preset, String mode, String layoutConsole, boolean[] orientations, int shortSide, int longSide) throws IOException {
        SharedPreferences.Editor edit = MainActivity.sharedSettings(context).edit();
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        String skipped = null;
        File folder = dir(context, preset.id);
        try {
            JSONObject data = preset.data;
            if (data.has("shell")) {
                edit.putInt("shell_style", data.getInt("shell"));
            }
            if (data.has("filter")) {
                edit.putInt("screen_filter", data.getInt("filter"));
            }
            for (int o = 0; o < 2; o++) {
                boolean landscape = o == 1;
                if (!orientations[o]) continue;
                JSONObject orient = data.optJSONObject(landscape ? "L" : "P");
                if (orient == null || orient.length() == 0) continue;
                String bgScope = mode;
                String orientName = PhotoBackground.orient(landscape);
                if (orient.has("photo")) {
                    JSONObject photo = orient.getJSONObject("photo");
                    File rendered = new File(folder, photo.optString("rendered", ""));
                    if (rendered.isFile()) {
                        PhotoBackground.copy(rendered, PhotoBackground.renderedFile(context, bgScope, landscape));
                        edit.putInt("bg:" + bgScope + ":dim", photo.optInt("dim", 0)).putInt("bg:" + bgScope + ":blur", photo.optInt("blur", 0));
                        JSONArray layers = photo.optJSONArray("layers");
                        int count = 0;
                        if (layers != null) {
                            for (int i = 0; i < layers.length(); i++) {
                                JSONObject layer = layers.getJSONObject(i);
                                File source = new File(folder, layer.optString("file", ""));
                                if (!source.isFile()) continue;
                                PhotoBackground.copy(source, PhotoBackground.source(context, bgScope, landscape, count));
                                edit.putFloat(PhotoBackground.layerKey(bgScope, landscape, count, "zoom"), (float) layer.optDouble("zoom", 1.0))
                                        .putFloat(PhotoBackground.layerKey(bgScope, landscape, count, "ox"), (float) layer.optDouble("ox", 0))
                                        .putFloat(PhotoBackground.layerKey(bgScope, landscape, count, "oy"), (float) layer.optDouble("oy", 0))
                                        .putFloat(PhotoBackground.layerKey(bgScope, landscape, count, "rot"), (float) layer.optDouble("rot", 0))
                                        .putInt(PhotoBackground.layerKey(bgScope, landscape, count, "shape"), layer.optInt("shape", 0));
                                count++;
                            }
                        }
                        for (int i = count; i < PhotoBackground.MAX_LAYERS; i++) {
                            //noinspection ResultOfMethodCallIgnored
                            PhotoBackground.source(context, bgScope, landscape, i).delete();
                        }
                        edit.putInt("bg:" + bgScope + ":" + orientName + ":count", count);
                        edit.commit();
                        // A rendered file from another phone (or an older edit) may not fit this
                        // screen, so the background is drawn again from the sources when there are any.
                        if (count > 0) {
                            List<PhotoBackground.Layer> loaded = PhotoBackground.loadLayers(context, bgScope, landscape);
                            if (!loaded.isEmpty()) {
                                int w = landscape ? longSide : shortSide;
                                int h = landscape ? shortSide : longSide;
                                try {
                                    android.graphics.Bitmap drawn = PhotoBackground.render(loaded, w, h, photo.optInt("dim", 0), photo.optInt("blur", 0));
                                    PhotoBackground.save(drawn, PhotoBackground.renderedFile(context, bgScope, landscape));
                                    drawn.recycle();
                                } catch (OutOfMemoryError e) {
                                    // The copied rendering stays.
                                }
                                for (PhotoBackground.Layer layer : loaded) {
                                    if (layer.bitmap != null) layer.bitmap.recycle();
                                }
                            }
                        }
                    }
                } else if (data.has("shell") && data.getInt("shell") != ClearShellView.STYLE_PHOTO) {
                    // A preset without a photo that sets another case: nothing to clear, the case decides.
                }
                if (orient.has("alpha")) {
                    edit.putInt("bg:" + bgScope + ":btn", orient.getInt("alpha"));
                }
                if (orient.has("buttons")) {
                    edit.putString("btnstyle:" + mode + ":" + (landscape ? "L" : "P"), orient.getString("buttons"));
                }
                if (orient.has("layout")) {
                    if ("all".equals(preset.mode) || preset.mode.equals(mode)) {
                        JSONObject layout = orient.getJSONObject("layout");
                        String prefix = "layout:" + layoutConsole + ":" + (landscape ? "L" : "P") + ":";
                        // Clears this orientation's old layout first.
                        for (String key : new ArrayList<>(prefs.getAll().keySet())) {
                            if (key.startsWith(prefix)) edit.remove(key);
                        }
                        for (java.util.Iterator<String> keys = layout.keys(); keys.hasNext(); ) {
                            String key = keys.next();
                            edit.putString(prefix + key, layout.getString(key));
                        }
                    } else {
                        skipped = "기종이 달라 버튼 배치는 뺐어요";
                    }
                }
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        edit.commit();
        return skipped;
    }

    // ---- share files (.mepreset = zip of the folder) ----

    static final String EXTENSION = ".mepreset";
    private static final long MAX_IMPORT = 64L * 1024 * 1024;

    static String fileName(Preset preset) {
        String base = preset.name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return (base.isEmpty() ? "preset" : base) + EXTENSION;
    }

    static void exportZip(Context context, Preset preset, java.io.OutputStream out) throws IOException {
        File folder = dir(context, preset.id);
        File[] files = folder.listFiles(File::isFile);
        if (files == null || !new File(folder, "preset.json").isFile()) {
            throw new IOException("프리셋 파일이 없어요");
        }
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(out))) {
            for (File file : files) {
                zip.putNextEntry(new java.util.zip.ZipEntry(file.getName()));
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
        }
    }

    /** Unpacks a shared preset into a new folder and reads it; throws when it is not one. */
    static Preset importZip(Context context, java.io.InputStream in) throws IOException {
        String id = newId();
        File folder = dir(context, id);
        if (!folder.mkdirs()) {
            throw new IOException("프리셋 폴더를 만들 수 없어요");
        }
        long total = 0;
        boolean sawJson = false;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                // Only flat, plainly named files: preset.json and the jpg photos.
                if (entry.isDirectory() || name.contains("/") || name.contains("\\") || name.startsWith(".")
                        || !(name.equals("preset.json") || name.matches("[A-Za-z0-9_\\-]+\\.jpg"))) {
                    zip.closeEntry();
                    continue;
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(folder, name))) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_IMPORT) {
                            throw new IOException("파일이 너무 커요 (64 MB 넘음)");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                sawJson |= name.equals("preset.json");
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            N3dsCia.deleteTree(folder);
            throw e instanceof IOException ? (IOException) e : new IOException("압축을 풀지 못했어요: " + e.getMessage());
        }
        if (!sawJson) {
            N3dsCia.deleteTree(folder);
            throw new IOException("Mini Emulator 프리셋 파일(.mepreset)이 아니에요");
        }
        Preset preset = read(folder);
        if (preset == null) {
            N3dsCia.deleteTree(folder);
            throw new IOException("프리셋 내용을 읽을 수 없어요");
        }
        if (preset.favorite) {
            preset.favorite = false;
        }
        preset.created = System.currentTimeMillis();
        write(context, preset);
        return preset;
    }

    /**
     * Writes the preset as a file others can open: into 다운로드/Mini Emulator on Android 10
     * and later (shareable through its content URI), else into the app's external files.
     */
    static android.net.Uri exportFile(Context context, Preset preset) throws IOException {
        String name = fileName(preset);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER + "/");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("다운로드 폴더에 파일을 만들 수 없어요");
            }
            try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("파일을 열 수 없어요");
                exportZip(context, preset, out);
            }
            values.clear();
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        }
        File folder = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
            throw new IOException("외부 저장 폴더를 쓸 수 없어요");
        }
        File file = new File(folder, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            exportZip(context, preset, out);
        }
        return android.net.Uri.fromFile(file);
    }

    /** The share sheet for an exported preset file; null when the file is not shareable here. */
    static android.content.Intent shareIntent(android.net.Uri uri, Preset preset) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("application/octet-stream");
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, "Mini Emulator 꾸미기 프리셋 · " + preset.name);
        send.putExtra(android.content.Intent.EXTRA_TEXT, "Mini Emulator 꾸미기 프리셋 \"" + preset.name + "\" (" + fileName(preset) + ")\n파일을 열거나 앱의 꾸미기 프리셋 › 파일에서 가져오기로 적용하세요.");
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return android.content.Intent.createChooser(send, "프리셋 보내기");
    }

    // ---- built-in ----

    static List<Preset> builtIn() {
        List<Preset> out = new ArrayList<>();
        out.add(builtIn("b_purple", "아톰 퍼플", "GBA 한정판 색 · 투명 케이스 · 보라 버튼",
                ClearShellView.STYLE_CLEAR, custom(Color.rgb(132, 110, 206), Color.rgb(124, 58, 237), Color.rgb(76, 29, 149))));
        out.add(builtIn("b_snes", "클래식 SNES", "실물 플라스틱 · 원색 4색 버튼",
                ClearShellView.STYLE_PLASTIC, theme(ButtonStyle.THEME_PRIMARY)));
        out.add(builtIn("b_neon", "네온 야간", "네온 나이트 케이스 · 네온 버튼 · 눌림 빛남",
                ClearShellView.STYLE_NEON, theme(ButtonStyle.THEME_NEON)));
        return out;
    }

    private static ButtonStyle theme(int theme) {
        ButtonStyle style = new ButtonStyle();
        style.theme = theme;
        return style;
    }

    private static ButtonStyle custom(int dpad, int action, int pill) {
        ButtonStyle style = new ButtonStyle();
        style.theme = ButtonStyle.THEME_CUSTOM;
        style.custom[0] = dpad;
        style.custom[1] = action;
        style.custom[2] = pill;
        return style;
    }

    private static Preset builtIn(String id, String name, String note, int shell, ButtonStyle style) {
        Preset preset = new Preset();
        preset.id = id;
        preset.name = name;
        preset.builtin = true;
        preset.mode = "all";
        preset.modeName = note;
        try {
            JSONObject data = new JSONObject();
            data.put("shell", shell);
            for (String orient : new String[]{"P", "L"}) {
                JSONObject o = new JSONObject();
                o.put("buttons", style.serialize());
                data.put(orient, o);
            }
            preset.data = data;
        } catch (JSONException ignored) {
            // Constant input.
        }
        return preset;
    }

    // ---- files ----

    private static String readText(File file) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
