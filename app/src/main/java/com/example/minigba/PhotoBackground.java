package com.example.minigba;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The player's own photo as the console's body ("내 사진 · 그림" background).
 *
 * Files live in files/bg/. For a scope ("all", or a mode id such as "gba") and an
 * orientation ("port" / "land") there is the source photo (src_<scope>_<orient>.jpg, the
 * picked image shrunk to 2048px) and the rendered background (<scope>_<orient>.jpg): the
 * source cropped to the screen with the chosen zoom and offset, darkened and blurred, so
 * the game only draws one bitmap. Zoom/offset, dim, blur and the button opacity are in
 * the settings under "bg:<scope>:...".
 */
final class PhotoBackground {
    static final int MAX_SOURCE = 2048;
    static final int DEFAULT_DIM = 40;
    static final int DEFAULT_BLUR = 0;
    static final int DEFAULT_BUTTON_ALPHA = 0;

    private PhotoBackground() {
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "bg");
    }

    static String orient(boolean landscape) {
        return landscape ? "land" : "port";
    }

    static final int MAX_LAYERS = 6;

    /** The photo of one layer (0 is the base that covers the screen; the rest sit on it). */
    static File source(Context context, String scope, boolean landscape, int layer) {
        return new File(dir(context), "src_" + scope + "_" + orient(landscape) + "_" + layer + ".jpg");
    }

    /** One photo's placement: zoom and rotation about its centre, offset from the screen centre. */
    static final int SHAPE_NONE = 0;
    static final int SHAPE_ROUNDED = 1;
    static final int SHAPE_CIRCLE = 2;
    static final int SHAPE_POLAROID = 3;
    static final int SHAPE_SHADOW = 4;
    static final int SHAPE_FADE = 5;
    static final String[] SHAPE_NAMES = {"없음", "둥근", "원", "폴라로이드", "그림자", "가장자리 흐림"};

    static final class Layer {
        File file;
        Bitmap bitmap;
        float zoom = 1.0f;
        float offsetX;
        float offsetY;
        float rotation;
        int shape = SHAPE_NONE;
        // The photo with its edges faded out (SHAPE_FADE), made when first drawn.
        Bitmap faded;

        Layer(File file) {
            this.file = file;
        }
    }

    static String layerKey(String scope, boolean landscape, int layer, String what) {
        return "bg:" + scope + ":" + orient(landscape) + ":L" + layer + ":" + what;
    }

    /** The layers saved for a scope and orientation (bitmaps loaded), oldest format moved over. */
    static java.util.List<Layer> loadLayers(Context context, String scope, boolean landscape) {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        String base = "bg:" + scope + ":" + orient(landscape);
        java.util.List<Layer> layers = new java.util.ArrayList<>();
        int count = prefs.getInt(base + ":count", -1);
        if (count < 0) {
            // 0.0.177 kept one photo as src_<scope>_<orient>.jpg with its placement under :zoom/:ox/:oy.
            File old = new File(dir(context), "src_" + scope + "_" + orient(landscape) + ".jpg");
            if (old.isFile() && old.renameTo(source(context, scope, landscape, 0))) {
                prefs.edit().putInt(base + ":count", 1)
                        .putFloat(layerKey(scope, landscape, 0, "zoom"), prefs.getFloat(base + ":zoom", 1.0f))
                        .putFloat(layerKey(scope, landscape, 0, "ox"), prefs.getFloat(base + ":ox", 0))
                        .putFloat(layerKey(scope, landscape, 0, "oy"), prefs.getFloat(base + ":oy", 0)).apply();
                count = 1;
            } else {
                count = 0;
            }
        }
        for (int i = 0; i < Math.min(count, MAX_LAYERS); i++) {
            File file = source(context, scope, landscape, i);
            Bitmap bitmap = load(file, 0, 0);
            if (bitmap == null) continue;
            Layer layer = new Layer(file);
            layer.bitmap = bitmap;
            layer.zoom = prefs.getFloat(layerKey(scope, landscape, i, "zoom"), 1.0f);
            layer.offsetX = prefs.getFloat(layerKey(scope, landscape, i, "ox"), 0);
            layer.offsetY = prefs.getFloat(layerKey(scope, landscape, i, "oy"), 0);
            layer.rotation = prefs.getFloat(layerKey(scope, landscape, i, "rot"), 0);
            layer.shape = prefs.getInt(layerKey(scope, landscape, i, "shape"), SHAPE_NONE);
            layers.add(layer);
        }
        return layers;
    }

    /** Writes the layers' files (renumbered in order) and placements. */
    static void saveLayers(Context context, String scope, boolean landscape, java.util.List<Layer> layers) throws IOException {
        SharedPreferences.Editor edit = MainActivity.sharedSettings(context).edit();
        String base = "bg:" + scope + ":" + orient(landscape);
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            File target = source(context, scope, landscape, i);
            if (!target.equals(layer.file)) {
                if (layer.bitmap != null) {
                    save(layer.bitmap, target);
                } else if (layer.file != null && layer.file.isFile()) {
                    copy(layer.file, target);
                }
                layer.file = target;
            }
            edit.putFloat(layerKey(scope, landscape, i, "zoom"), layer.zoom).putFloat(layerKey(scope, landscape, i, "ox"), layer.offsetX)
                    .putFloat(layerKey(scope, landscape, i, "oy"), layer.offsetY).putFloat(layerKey(scope, landscape, i, "rot"), layer.rotation)
                    .putInt(layerKey(scope, landscape, i, "shape"), layer.shape);
        }
        for (int i = layers.size(); i < MAX_LAYERS; i++) {
            //noinspection ResultOfMethodCallIgnored
            source(context, scope, landscape, i).delete();
        }
        edit.putInt(base + ":count", layers.size()).commit();
    }

    static void copy(File from, File to) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("배경 폴더를 만들 수 없어요");
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    static File renderedFile(Context context, String scope, boolean landscape) {
        return new File(dir(context), scope + "_" + orient(landscape) + ".jpg");
    }

    /** The background for a mode: the mode's own, else the one for all consoles; null when none. */
    static File rendered(Context context, String mode, boolean landscape) {
        File own = mode == null ? null : renderedFile(context, mode, landscape);
        if (own != null && own.isFile()) {
            return own;
        }
        File all = renderedFile(context, "all", landscape);
        return all.isFile() ? all : null;
    }

    /** The scope whose settings apply to a mode: its own when it has a background of its own. */
    static String scopeInForce(Context context, String mode) {
        if (mode != null && (renderedFile(context, mode, false).isFile() || renderedFile(context, mode, true).isFile())) {
            return mode;
        }
        return "all";
    }

    static int setting(Context context, String scope, String key, int fallback) {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        return prefs.getInt("bg:" + scope + ":" + key, fallback);
    }

    static float setting(Context context, String scope, String key, float fallback) {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        return prefs.getFloat("bg:" + scope + ":" + key, fallback);
    }

    /** 0-90: how much the buttons let the photo through for this mode. */
    static int buttonAlpha(Context context, String mode) {
        return Math.max(0, Math.min(90, setting(context, scopeInForce(context, mode), "btn", DEFAULT_BUTTON_ALPHA)));
    }

    // ---- images ----

    /** Decodes the picked image no larger than MAX_SOURCE on its long side. */
    static Bitmap decodePicked(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("사진을 열 수 없어요");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("그림 파일이 아니에요");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_SOURCE * 2) {
            options.inSampleSize *= 2;
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("사진을 열 수 없어요");
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) {
            throw new IOException("그림을 읽지 못했어요");
        }
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > MAX_SOURCE) {
            float k = MAX_SOURCE / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * k)), Math.max(1, Math.round(bitmap.getHeight() * k)), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        return bitmap;
    }

    static Bitmap load(File file, int reqWidth, int reqHeight) {
        if (file == null || !file.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        if (reqWidth > 0 && reqHeight > 0) {
            while (bounds.outWidth / (options.inSampleSize * 2) >= reqWidth && bounds.outHeight / (options.inSampleSize * 2) >= reqHeight) {
                options.inSampleSize *= 2;
            }
        }
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    static void save(Bitmap bitmap, File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("배경 폴더를 만들 수 없어요");
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                throw new IOException("그림을 저장하지 못했어요");
            }
        }
    }

    /** The scale that makes the source cover a width x height area. */
    static float coverScale(Bitmap source, int width, int height) {
        return Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
    }

    /** The scale at which a layer added on top first appears: half the screen's width. */
    static float topScale(Bitmap source, int width, int height) {
        return Math.min(width * 0.5f / source.getWidth(), height * 0.5f / source.getHeight());
    }

    /**
     * The matrix that places a layer in a width x height area: the base covers it at zoom
     * 1 and the others start at half size; zoomed and turned about the photo's centre,
     * then moved by (offsetX, offsetY) from the area's centre.
     */
    static Matrix placement(Bitmap source, int width, int height, boolean base, float zoom, float offsetX, float offsetY, float rotation) {
        float scale = (base ? coverScale(source, width, height) : topScale(source, width, height)) * zoom;
        Matrix matrix = new Matrix();
        matrix.postTranslate(-source.getWidth() / 2f, -source.getHeight() / 2f);
        matrix.postScale(scale, scale);
        matrix.postRotate(rotation);
        matrix.postTranslate(width / 2f + offsetX, height / 2f + offsetY);
        return matrix;
    }

    /** Draws the layers in order (the base's blurred stretch fills what they leave), then blurs and darkens. */
    static Bitmap render(java.util.List<Layer> layers, int width, int height, int dim, int blur) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (!layers.isEmpty() && layers.get(0).bitmap != null) {
            // Behind everything: the base photo stretched over the screen and blurred, so
            // the parts no photo covers are not plain black.
            Bitmap backdrop = render(layers.get(0).bitmap, width, height);
            canvas.drawBitmap(backdrop, 0, 0, paint);
            backdrop.recycle();
        }
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            if (layer.bitmap == null) continue;
            drawLayer(canvas, layer, placement(layer.bitmap, width, height, i == 0, layer.zoom, layer.offsetX, layer.offsetY, layer.rotation), paint);
        }
        if (blur > 0) {
            Bitmap blurred = blur(out, blur);
            if (blurred != out) {
                out.recycle();
                out = blurred;
                canvas = new Canvas(out);
            }
        }
        if (dim > 0) {
            canvas.drawColor(Color.argb(Math.round(255 * Math.min(90, dim) / 100f), 0, 0, 0));
        }
        return out;
    }

    /** Draws one photo through its matrix in its shape (frame, shadow, rounded or faded edges). */
    static void drawLayer(Canvas canvas, Layer layer, Matrix matrix, Paint paint) {
        Bitmap bitmap = layer.bitmap;
        float w = bitmap.getWidth();
        float h = bitmap.getHeight();
        canvas.save();
        canvas.concat(matrix);
        switch (layer.shape) {
            case SHAPE_ROUNDED: {
                float radius = Math.min(w, h) * 0.08f;
                android.graphics.Path path = new android.graphics.Path();
                path.addRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, android.graphics.Path.Direction.CW);
                canvas.clipPath(path);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                break;
            }
            case SHAPE_CIRCLE: {
                float d = Math.min(w, h);
                android.graphics.Path path = new android.graphics.Path();
                path.addOval(new android.graphics.RectF((w - d) / 2, (h - d) / 2, (w + d) / 2, (h + d) / 2), android.graphics.Path.Direction.CW);
                canvas.clipPath(path);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                break;
            }
            case SHAPE_POLAROID: {
                float m = Math.min(w, h) * 0.06f;
                softShadow(canvas, -m, -m, w + m, h + m * 4.2f, m * 1.2f);
                Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
                white.setColor(Color.rgb(250, 250, 248));
                canvas.drawRect(-m, -m, w + m, h + m * 4.2f, white);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                break;
            }
            case SHAPE_SHADOW:
                softShadow(canvas, 0, 0, w, h, Math.min(w, h) * 0.08f);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                break;
            case SHAPE_FADE: {
                if (layer.faded == null) {
                    layer.faded = fadeEdges(bitmap);
                }
                canvas.drawBitmap(layer.faded, 0, 0, paint);
                break;
            }
            default:
                canvas.drawBitmap(bitmap, 0, 0, paint);
                break;
        }
        canvas.restore();
    }

    /** A soft shadow under a rectangle: rings of growing size and fading alpha (works on any canvas). */
    private static void softShadow(Canvas canvas, float left, float top, float right, float bottom, float spread) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        int rings = 10;
        for (int i = rings; i >= 1; i--) {
            float grow = spread * i / rings;
            shadow.setColor(Color.argb(Math.round(26f * (rings - i + 1) / rings), 0, 0, 0));
            canvas.drawRoundRect(new android.graphics.RectF(left - grow, top - grow + spread * 0.6f, right + grow, bottom + grow + spread * 0.6f), grow, grow, shadow);
        }
    }

    /** The photo with its edges faded to transparent (an elliptical falloff). */
    private static Bitmap fadeEdges(Bitmap bitmap) {
        Bitmap out = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        float w = bitmap.getWidth();
        float h = bitmap.getHeight();
        android.graphics.RadialGradient gradient = new android.graphics.RadialGradient(w / 2, h / 2, Math.max(w, h) * 0.5f,
                new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT}, new float[]{0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP);
        Matrix stretch = new Matrix();
        stretch.setScale(w >= h ? 1 : w / h, h >= w ? 1 : h / w, w / 2, h / 2);
        gradient.setLocalMatrix(stretch);
        mask.setShader(gradient);
        mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        canvas.drawRect(0, 0, w, h, mask);
        return out;
    }

    /** The base photo covering the area, heavily blurred and darkened: the backdrop. */
    private static Bitmap render(Bitmap base, int width, int height) {
        int w = Math.max(1, width / 4);
        int h = Math.max(1, height / 4);
        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        float scale = coverScale(base, w, h);
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate((w - base.getWidth() * scale) / 2f, (h - base.getHeight() * scale) / 2f);
        canvas.drawBitmap(base, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
        Bitmap blurred = blur(small, 80);
        if (blurred != small) small.recycle();
        Bitmap full = Bitmap.createScaledBitmap(blurred, width, height, true);
        if (full != blurred) blurred.recycle();
        new Canvas(full).drawColor(Color.argb(110, 0, 0, 0));
        return full;
    }

    /** A cheap blur: shrink the picture and stretch it back with filtering, more for more blur. */
    static Bitmap blur(Bitmap bitmap, int percent) {
        int steps = Math.max(1, Math.min(6, Math.round(percent / 16f)));
        int factor = 1 << steps;
        int w = Math.max(1, bitmap.getWidth() / factor);
        int h = Math.max(1, bitmap.getHeight() / factor);
        Bitmap small = Bitmap.createScaledBitmap(bitmap, w, h, true);
        // Two more passes at the small size smooth the blocky edges of the shrink.
        for (int i = 0; i < 2; i++) {
            Bitmap smaller = Bitmap.createScaledBitmap(small, Math.max(1, w / 2), Math.max(1, h / 2), true);
            Bitmap back = Bitmap.createScaledBitmap(smaller, w, h, true);
            smaller.recycle();
            if (back != small) small.recycle();
            small = back;
        }
        Bitmap result = Bitmap.createScaledBitmap(small, bitmap.getWidth(), bitmap.getHeight(), true);
        if (result != small) small.recycle();
        return result;
    }
}
