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

    static File source(Context context, String scope, boolean landscape) {
        return new File(dir(context), "src_" + scope + "_" + orient(landscape) + ".jpg");
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

    /**
     * The matrix that places the source in a width x height area: covering it at zoom 1,
     * centred, then zoomed about the centre and moved by (offsetX, offsetY) pixels.
     */
    static Matrix placement(Bitmap source, int width, int height, float zoom, float offsetX, float offsetY) {
        float scale = coverScale(source, width, height) * zoom;
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postTranslate((width - source.getWidth() * scale) / 2f + offsetX, (height - source.getHeight() * scale) / 2f + offsetY);
        return matrix;
    }

    /** Draws the source through the placement, darkened by dim percent and blurred by blur percent. */
    static Bitmap render(Bitmap source, int width, int height, float zoom, float offsetX, float offsetY, int dim, int blur) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(source, placement(source, width, height, zoom, offsetX, offsetY), paint);
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
