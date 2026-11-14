package com.example.minigba;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

// A small sample picture with a screen filter on it, for the filter chooser (0.0.360): the
// same lines the game screen gets (ScreenFilterView) over a little scene of the app's own.
final class FilterPreview extends View {
    private static Bitmap sample;
    private static Bitmap green;
    private static Bitmap smooth;

    private final Paint paint = new Paint();
    private int filter;
    private int strength = 70;
    private int curve = 50;
    private int bright = 60;

    FilterPreview(Context context, int filter) {
        super(context);
        this.filter = filter;
    }

    void setLook(int strength, int curve, int bright) {
        this.strength = strength;
        this.curve = curve;
        this.bright = bright;
        invalidate();
    }

    // A 64x48 scene: sky, sun, clouds, ground, trees and a little figure.
    private static Bitmap sample() {
        if (sample != null) {
            return sample;
        }
        int w = 64;
        int h = 48;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = y < 33 ? Color.rgb(92, 148, 252) : y == 33 ? Color.rgb(56, 120, 40) : Color.rgb(80, 168, 64);
                int dx = x - 51;
                int dy = y - 9;
                if (dx * dx + dy * dy <= 25) c = Color.rgb(252, 224, 80);
                if (y >= 8 && y <= 9 && (x % 8) >= 2 && (x % 8) <= 5 && x < 44) c = Color.WHITE;
                px[y * w + x] = c;
            }
        }
        int[][] trees = {{8, 10}, {40, 14}, {52, 8}};
        for (int[] t : trees) {
            for (int y = 34 - t[1]; y < 34; y++) for (int x = t[0]; x <= t[0] + 6; x++) px[y * w + x] = Color.rgb(200, 120, 64);
            for (int y = 30 - t[1]; y < 34 - t[1]; y++) for (int x = t[0] - 2; x <= t[0] + 8; x++) if (x >= 0 && x < w) px[y * w + x] = Color.rgb(48, 160, 72);
        }
        for (int y = 20; y < 34; y++) {
            for (int x = 25; x <= 32; x++) {
                px[y * w + x] = y < 22 ? Color.rgb(200, 40, 40) : y < 28 ? (x >= 26 && x <= 31 ? Color.rgb(248, 200, 160) : px[y * w + x]) : (x >= 26 && x <= 31 ? Color.rgb(40, 80, 200) : px[y * w + x]);
            }
        }
        px[24 * w + 27] = Color.BLACK;
        px[24 * w + 30] = Color.BLACK;
        sample = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        return sample;
    }

    private static Bitmap green() {
        if (green != null) {
            return green;
        }
        Bitmap s = sample();
        int[] px = new int[s.getWidth() * s.getHeight()];
        s.getPixels(px, 0, s.getWidth(), 0, 0, s.getWidth(), s.getHeight());
        int[] shades = {Color.rgb(15, 56, 15), Color.rgb(48, 98, 48), Color.rgb(139, 172, 15), Color.rgb(155, 188, 15)};
        for (int i = 0; i < px.length; i++) {
            int l = (Color.red(px[i]) * 299 + Color.green(px[i]) * 587 + Color.blue(px[i]) * 114) / 1000;
            px[i] = shades[l < 64 ? 0 : l < 128 ? 1 : l < 192 ? 2 : 3];
        }
        green = Bitmap.createBitmap(px, s.getWidth(), s.getHeight(), Bitmap.Config.ARGB_8888);
        return green;
    }

    // Scale2x (EPX), as the presenter does it.
    private static Bitmap smooth() {
        if (smooth != null) {
            return smooth;
        }
        Bitmap s = sample();
        int w = s.getWidth();
        int h = s.getHeight();
        int[] in = new int[w * h];
        s.getPixels(in, 0, w, 0, 0, w, h);
        int[] out = new int[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = in[y * w + x];
                int a = in[Math.max(0, y - 1) * w + x];
                int d = in[Math.min(h - 1, y + 1) * w + x];
                int c = in[y * w + Math.max(0, x - 1)];
                int b = in[y * w + Math.min(w - 1, x + 1)];
                int o = y * 2 * w * 2 + x * 2;
                out[o] = c == a && c != d && a != b ? a : p;
                out[o + 1] = a == b && a != c && b != d ? b : p;
                out[o + w * 2] = d == c && d != b && c != a ? c : p;
                out[o + w * 2 + 1] = b == d && b != a && d != c ? d : p;
            }
        }
        smooth = Bitmap.createBitmap(out, w * 2, h * 2, Bitmap.Config.ARGB_8888);
        return smooth;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = 7 * getResources().getDisplayMetrics().density;
        // the picture's place: 4:3, centred
        float pw = Math.min(w, h * 4.0f / 3.0f);
        float ph = pw * 3.0f / 4.0f;
        float left = (w - pw) / 2;
        float top = (h - ph) / 2;
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawColor(Color.BLACK);
        Bitmap picture = this.filter == 8 ? green() : this.filter == 9 ? smooth() : sample();
        this.paint.reset();
        this.paint.setFilterBitmap(this.filter == 0 || this.filter == 4);
        ColorMatrix m = new ColorMatrix();
        if (this.filter == 5 || this.filter == 6) {
            float gain = 1.0f + 0.45f * this.bright / 100.0f;
            m.setScale(gain, gain, gain, 1);
            this.paint.setColorFilter(new ColorMatrixColorFilter(m));
        } else if (this.filter == 7) {
            m.setSaturation(1.0f - 0.25f * this.strength / 100.0f);
            this.paint.setColorFilter(new ColorMatrixColorFilter(m));
        }
        canvas.drawBitmap(picture, new Rect(0, 0, picture.getWidth(), picture.getHeight()), new RectF(left, top, left + pw, top + ph), this.paint);
        this.paint.reset();
        this.paint.setAntiAlias(true);
        ScreenFilterView.draw(canvas, this.paint, left, top, pw, ph, this.filter, 32, 24, this.strength, this.curve,
                getResources().getDisplayMetrics().density);
        canvas.restore();
    }
}
