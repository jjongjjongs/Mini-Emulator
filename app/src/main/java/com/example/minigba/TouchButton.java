package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

final class TouchButton extends View {
    static final int STYLE_ACTION = 3;
    static final int STYLE_ARCADE_BLUE = 5;
    static final int STYLE_ARCADE_RED = 4;
    static final int STYLE_DARK = 1;
    static final int STYLE_LIGHT = 0;
    static final int STYLE_SHOULDER = 2;
    static final int STYLE_ARCADE_YELLOW = 6;
    static final int STYLE_ARCADE_GREEN = 7;
    static final int STYLE_SNES_BLUE = 8;
    private final int button;
    private final Paint fill;
    private final Paint highlight;
    private final String label;
    private final Listener listener;
    private boolean pressed;
    private final Paint shadow;
    private final Paint stroke;
    private final int style;
    private final Paint text;

    interface Listener {
        void onButtonChanged(int i, boolean z);
    }

    TouchButton(Context context, String str, int i, Listener listener) {
        this(context, str, i, listener, 0);
    }

    TouchButton(Context context, String str, int i, Listener listener, int i2) {
        super(context);
        Paint paint = new Paint(1);
        this.fill = paint;
        Paint paint2 = new Paint(1);
        this.stroke = paint2;
        Paint paint3 = new Paint(1);
        this.shadow = paint3;
        Paint paint4 = new Paint(1);
        this.highlight = paint4;
        Paint paint5 = new Paint(1);
        this.text = paint5;
        this.label = str;
        this.button = i;
        this.listener = listener;
        this.style = i2;
        paint.setStyle(Paint.Style.FILL);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeWidth(4.0f);
        paint3.setStyle(Paint.Style.FILL);
        paint4.setStyle(Paint.Style.STROKE);
        paint4.setStrokeWidth(3.0f);
        paint5.setTextAlign(Paint.Align.CENTER);
        paint5.setFakeBoldText(true);
        setFocusable(false);
    }

    // Clear jelly look: tinted see-through plastic with light pooling at the bottom, a thin
    // bright rim, a small reflection and a coloured glow under it. Pressing sinks it a little.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (Math.max(width, height) > Math.min(width, height) * 1.25f) {
            drawKeycap(canvas, width, height);
        } else {
            drawRound(canvas, width, height, palette());
        }
    }

    // light, middle and edge colour of the plastic
    private int[] palette() {
        switch (this.style) {
            case STYLE_ARCADE_RED:
                return new int[]{Color.rgb(227, 38, 58), Color.rgb(163, 16, 31), Color.rgb(110, 8, 20)};
            case STYLE_ARCADE_BLUE:
                return new int[]{Color.rgb(24, 182, 204), Color.rgb(11, 127, 145), Color.rgb(6, 81, 92)};
            case STYLE_ARCADE_YELLOW:
                return new int[]{Color.rgb(248, 179, 26), Color.rgb(201, 133, 10), Color.rgb(135, 87, 3)};
            case STYLE_ARCADE_GREEN:
                return new int[]{Color.rgb(34, 179, 90), Color.rgb(19, 128, 60), Color.rgb(10, 83, 38)};
            case STYLE_SNES_BLUE:
                return new int[]{Color.rgb(47, 111, 224), Color.rgb(27, 79, 174), Color.rgb(15, 47, 110)};
            default:
                // GBA A/B and anything else round: smoky black.
                return new int[]{Color.rgb(58, 58, 63), Color.rgb(20, 20, 22), Color.rgb(0, 0, 0)};
        }
    }

    private void drawRound(Canvas canvas, float width, float height, int[] palette) {
        float cx = width / 2.0f;
        float radius = Math.min(width, height) * 0.42f;
        float press = this.pressed ? radius * 0.05f : 0.0f;
        float cy = height / 2.0f - radius * 0.04f + press;

        // Coloured glow the plastic throws on the case, plus a tight contact shadow.
        float glowY = cy + radius * (this.pressed ? 0.06f : 0.14f);
        this.shadow.setShader(new RadialGradient(cx, glowY, radius * 1.22f,
                withAlpha(palette[2], this.pressed ? 70 : 110), withAlpha(palette[2], 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, glowY, radius * 1.22f, this.shadow);
        this.shadow.setShader(null);
        this.shadow.setColor(Color.argb(60, 0, 0, 0));
        canvas.drawCircle(cx, cy + Math.max(1.0f, radius * 0.03f), radius, this.shadow);

        // See-through body, brighter below the centre.
        this.fill.setShader(new RadialGradient(cx, cy + radius * 0.2f, radius * 1.1f,
                new int[]{withAlpha(palette[0], 204), withAlpha(palette[1], 179), withAlpha(palette[2], 204)},
                new float[]{0.0f, 0.7f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, this.fill);
        // Light pooling at the bottom inside the plastic.
        this.fill.setShader(new RadialGradient(cx, cy + radius * 0.8f, radius * 0.75f,
                withAlpha(lighter(palette[0]), 150), withAlpha(palette[0], 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, this.fill);
        this.fill.setShader(null);

        // Thin bright rim.
        float rim = Math.max(1.5f, radius * 0.06f);
        this.highlight.setShader(null);
        this.highlight.setStyle(Paint.Style.STROKE);
        this.highlight.setStrokeWidth(rim);
        this.highlight.setColor(Color.argb(90, 255, 255, 255));
        canvas.drawCircle(cx, cy, radius - rim / 2.0f, this.highlight);

        // Small tilted reflection in the upper left.
        this.highlight.setStyle(Paint.Style.FILL);
        this.highlight.setColor(Color.argb(this.pressed ? 140 : 190, 255, 255, 255));
        canvas.save();
        canvas.rotate(-25.0f, cx - radius * 0.28f, cy - radius * 0.58f);
        canvas.drawOval(new RectF(cx - radius * 0.64f, cy - radius * 0.78f, cx + radius * 0.08f, cy - radius * 0.38f), this.highlight);
        canvas.restore();
        drawLabel(canvas, cx, cy, radius * 0.86f, Color.WHITE);
    }

    // Wide keys (L/R, SELECT/START, coin and the like): smoky see-through plastic.
    private void drawKeycap(Canvas canvas, float width, float height) {
        float press = this.pressed ? 2.0f : 0.0f;
        float left = 3.0f;
        float right = width - 3.0f;
        float top = 2.0f + press;
        float bottom = height - 7.0f + press;
        boolean shoulder = this.style == STYLE_SHOULDER;
        float radius = (bottom - top) * (shoulder ? 0.32f : 0.5f);
        RectF face = new RectF(left, top, right, bottom);

        // Soft shadow underneath.
        float drop = this.pressed ? 2.0f : 4.0f;
        this.shadow.setShader(null);
        this.shadow.setColor(Color.argb(this.pressed ? 26 : 38, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left - 1.0f, top + drop, right + 1.0f, bottom + drop + 1.0f), radius, radius, this.shadow);
        this.shadow.setColor(Color.argb(this.pressed ? 34 : 50, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top + drop * 0.5f, right, bottom + drop * 0.5f), radius, radius, this.shadow);

        this.fill.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                Color.argb(this.pressed ? 205 : 190, 70, 70, 80), Color.argb(this.pressed ? 230 : 217, 30, 30, 38), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius, radius, this.fill);
        // Faint sheen on the upper half.
        this.fill.setShader(new LinearGradient(0, face.top, 0, face.centerY(),
                Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(face.left + 2.0f, face.top + 2.0f, face.right - 2.0f, face.centerY()), radius, radius, this.fill);
        this.fill.setShader(null);
        // Bright rim.
        this.highlight.setShader(null);
        this.highlight.setStyle(Paint.Style.STROKE);
        this.highlight.setStrokeWidth(1.6f);
        this.highlight.setColor(Color.argb(80, 255, 255, 255));
        canvas.drawRoundRect(new RectF(face.left + 0.8f, face.top + 0.8f, face.right - 0.8f, face.bottom - 0.8f), radius, radius, this.highlight);
        drawLabel(canvas, width / 2.0f, face.centerY(), (face.bottom - face.top) * 0.9f, Color.WHITE);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lighter(int color) {
        return Color.rgb((Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2);
    }

    private void drawLabel(Canvas canvas, float x, float centerY, float size, int color) {
        boolean wide = Math.max(getWidth(), getHeight()) > Math.min(getWidth(), getHeight()) * 1.25f;
        this.text.setTextSize(wide ? Math.min(size * 0.5f, getWidth() / Math.max(3.0f, this.label.length() + 1.5f)) : Math.max(16.0f, size * 0.7f));
        Paint.FontMetrics metrics = this.text.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2.0f;
        boolean darkLabel = Color.red(color) + Color.green(color) + Color.blue(color) < 300;
        this.text.setColor(darkLabel ? Color.argb(110, 255, 255, 255) : Color.argb(110, 0, 0, 0));
        canvas.drawText(this.label, x, baseline + (darkLabel ? 1.0f : 1.5f), this.text);
        this.text.setColor(color);
        canvas.drawText(this.label, x, baseline, this.text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0 || actionMasked == 5) {
            setPressedState(true);
            return true;
        }
        if (actionMasked != 1 && actionMasked != 6 && actionMasked != 3) {
            return true;
        }
        setPressedState(false);
        return true;
    }

    private void setPressedState(boolean z) {
        if (this.pressed == z) {
            return;
        }
        this.pressed = z;
        this.listener.onButtonChanged(this.button, z);
        invalidate();
    }
}
