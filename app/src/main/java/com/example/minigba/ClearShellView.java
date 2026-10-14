package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.util.Random;

// Background of the game screen: a clear plastic case with the board showing
// through, a frosted plate under the controls and a bezel with screws around
// the game screen. The screen and control views are measured after layout, so
// the drawing follows wherever the layout puts them.
final class ClearShellView extends View {
    static final int TINT_WHITE = 0;
    static final int TINT_PURPLE = 1;
    static final int TINT_ORANGE = 2;
    static final int TINT_GRAY = 3;

    private static final int[][] TINTS = {
            // case colour, rim highlight, plate colour
            {Color.rgb(235, 238, 241), Color.argb(150, 255, 255, 255), Color.rgb(238, 240, 241)},
            {Color.rgb(226, 218, 244), Color.argb(150, 245, 240, 255), Color.rgb(239, 234, 249)},
            {Color.rgb(244, 229, 218), Color.argb(150, 255, 246, 238), Color.rgb(246, 238, 231)},
            {Color.rgb(229, 229, 236), Color.argb(150, 255, 255, 255), Color.rgb(239, 239, 244)},
    };
    private static final int TRACE_RED = Color.argb(150, 208, 80, 79);
    private static final int TRACE_SILVER = Color.argb(150, 154, 164, 171);

    private final int tint;
    private final String label;
    private final boolean landscape;
    private View screenView;
    private View plateView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] origin = new int[2];
    private final int[] where = new int[2];
    private final float density;

    ClearShellView(Context context, int tint, String label, boolean landscape) {
        super(context);
        this.tint = tint;
        this.label = label;
        this.landscape = landscape;
        this.density = context.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setColor(Color.rgb(93, 101, 108));
        text.setLetterSpacing(0.22f);
    }

    // The bezel is drawn around screen, the frosted plate behind controls.
    void track(View screen, View controls) {
        this.screenView = screen;
        this.plateView = controls;
        OnLayoutChangeListener redraw = (v, l, t, r, b, ol, ot, or, ob) -> invalidate();
        screen.addOnLayoutChangeListener(redraw);
        controls.addOnLayoutChangeListener(redraw);
    }

    private float dp(float value) {
        return value * density;
    }

    private RectF boundsOf(View view) {
        getLocationInWindow(origin);
        view.getLocationInWindow(where);
        float left = where[0] - origin[0];
        float top = where[1] - origin[1];
        return new RectF(left, top, left + view.getWidth(), top + view.getHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        int[] colors = TINTS[Math.max(0, Math.min(TINTS.length - 1, tint))];

        // The case itself: light catching the plastic diagonally.
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{colors[0], Color.WHITE, colors[0]}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        RectF plate = plateView == null ? null : boundsOf(plateView);
        if (plate != null) {
            if (landscape) {
                plate.set(plate.left + dp(8), plate.top + dp(56), plate.right - dp(8), plate.bottom - dp(8));
            } else {
                plate.set(plate.left + dp(14), plate.top + dp(2), plate.right - dp(14), plate.bottom - dp(14));
            }
            drawBoard(canvas, plate);
        }
        drawSideWiring(canvas, width, height);
        if (plate != null && plate.width() > 0 && plate.height() > 0) {
            float radius = dp(22);
            paint.setShader(new LinearGradient(0, plate.top, 0, plate.bottom,
                    withAlpha(colors[2], 208), Color.argb(200, 251, 251, 251), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.argb(240, 255, 255, 255));
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // Clear rim around the whole case.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(colors[1]);
        float inset = dp(4);
        canvas.drawRoundRect(new RectF(inset, inset, width - inset, height - inset), dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);

        if (screenView != null && screenView.getWidth() > 0) {
            drawBezel(canvas, boundsOf(screenView));
        }
        if (plate != null) {
            drawScrew(canvas, plate.left + dp(18), plate.bottom - dp(16));
            drawScrew(canvas, plate.right - dp(18), plate.bottom - dp(16));
        }
    }

    // Traces and chips of the board behind the plate; a fixed seed keeps them still.
    private void drawBoard(Canvas canvas, RectF plate) {
        paint.setColor(Color.argb(46, 170, 190, 180));
        canvas.drawRoundRect(new RectF(plate.left + dp(6), plate.top - dp(10), plate.right - dp(6), plate.bottom - dp(6)), dp(16), dp(16), paint);
        Random random = new Random(7);
        for (int i = 0; i < 16; i++) {
            boolean horizontal = i % 3 != 0;
            float x = plate.left + dp(14) + random.nextFloat() * Math.max(1, plate.width() - dp(104));
            float y = plate.top + random.nextFloat() * Math.max(1, plate.height() - dp(20));
            float length = dp(horizontal ? 40 + random.nextInt(80) : 30 + random.nextInt(60));
            paint.setColor(i % 2 == 0 ? TRACE_SILVER : TRACE_RED);
            if (horizontal) {
                canvas.drawRect(x, y, x + length, y + dp(2), paint);
            } else {
                canvas.drawRect(x, y, x + dp(2), y + length, paint);
            }
        }
        for (int i = 0; i < 6; i++) {
            float x = plate.left + dp(12) + random.nextFloat() * Math.max(1, plate.width() - dp(62));
            float y = plate.top + random.nextFloat() * Math.max(1, plate.height() - dp(30));
            float w = dp(18 + random.nextInt(22));
            float h = dp(8 + random.nextInt(8));
            paint.setShader(new LinearGradient(0, y, 0, y + h, Color.argb(140, 154, 161, 167), Color.argb(140, 125, 133, 139), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(x, y, x + w, y + h), dp(2), dp(2), paint);
            paint.setShader(null);
        }
    }

    private void drawSideWiring(Canvas canvas, float width, float height) {
        for (float x : new float[]{dp(9), width - dp(11)}) {
            paint.setColor(TRACE_SILVER);
            canvas.drawRect(x, height * 0.35f, x + dp(2), height * 0.75f, paint);
            paint.setColor(TRACE_RED);
            canvas.drawRect(x + dp(3), height * 0.45f, x + dp(5), height * 0.7f, paint);
        }
    }

    // Grey bezel around the game screen, with a rounder lower-right corner and an engraved name.
    private void drawBezel(Canvas canvas, RectF screen) {
        float side = dp(landscape ? 10 : 14);
        float top = dp(landscape ? 10 : 16);
        float bottom = dp(landscape ? 26 : 34);
        RectF bezel = new RectF(screen.left - side, screen.top - top, screen.right + side, screen.bottom + bottom);
        float small = dp(14);
        float large = dp(34);
        Path path = new Path();
        path.addRoundRect(bezel, new float[]{small, small, small, small, large, large, small, small}, Path.Direction.CW);
        // Drop shadow first, then the bezel itself.
        canvas.save();
        canvas.translate(0, dp(2));
        paint.setColor(Color.argb(38, 0, 0, 0));
        canvas.drawPath(path, paint);
        canvas.restore();
        paint.setShader(new LinearGradient(0, bezel.top, 0, bezel.bottom, Color.rgb(195, 200, 204), Color.rgb(174, 181, 186), Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(128, 255, 255, 255));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (label != null) {
            text.setTextSize(dp(landscape ? 8 : 9));
            canvas.drawText(label, bezel.centerX(), screen.bottom + bottom * 0.62f, text);
        }
        drawScrew(canvas, bezel.left - dp(10), bezel.top + dp(10));
        drawScrew(canvas, bezel.right + dp(10), bezel.top + dp(10));
        drawScrew(canvas, bezel.left - dp(10), bezel.bottom - dp(10));
        drawScrew(canvas, bezel.right + dp(10), bezel.bottom - dp(10));
    }

    private void drawScrew(Canvas canvas, float x, float y) {
        float r = dp(7);
        if (x - r < 0 || x + r > getWidth()) {
            return;
        }
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(x, y, r + dp(2), paint);
        paint.setShader(new RadialGradient(x - r * 0.25f, y - r * 0.3f, r * 1.4f,
                new int[]{Color.WHITE, Color.rgb(201, 207, 212), Color.rgb(154, 163, 170)}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
        paint.setColor(Color.rgb(138, 147, 154));
        paint.setStrokeWidth(dp(1.6f));
        float k = r * 0.5f;
        canvas.drawLine(x - k, y - k, x + k, y + k, paint);
        canvas.drawLine(x - k, y + k, x + k, y - k, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
