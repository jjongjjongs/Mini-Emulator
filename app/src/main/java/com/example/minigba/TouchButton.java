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

    // Glossy 3D look: a darker side (the button's thickness) under a lit top face with a
    // reflection, and a soft floor shadow. Pressing pushes the face down into the side.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int[] palette = palette();
        float width = getWidth();
        float height = getHeight();
        if (Math.max(width, height) > Math.min(width, height) * 1.25f) {
            drawKeycap(canvas, width, height, palette);
        } else {
            drawRound(canvas, width, height, palette);
        }
    }

    // top colour, middle colour, side colour, label colour
    private int[] palette() {
        switch (this.style) {
            case STYLE_ACTION:
                return new int[]{Color.rgb(78, 78, 84), Color.rgb(26, 26, 29), Color.rgb(4, 4, 6), Color.rgb(230, 230, 234)};
            case STYLE_ARCADE_RED:
                return new int[]{Color.rgb(240, 64, 82), Color.rgb(163, 16, 31), Color.rgb(110, 8, 20), Color.WHITE};
            case STYLE_ARCADE_BLUE:
                return new int[]{Color.rgb(46, 200, 220), Color.rgb(11, 127, 145), Color.rgb(6, 81, 92), Color.WHITE};
            case STYLE_ARCADE_YELLOW:
                return new int[]{Color.rgb(252, 196, 70), Color.rgb(201, 133, 10), Color.rgb(135, 87, 3), Color.rgb(91, 58, 0)};
            case STYLE_ARCADE_GREEN:
                return new int[]{Color.rgb(56, 196, 112), Color.rgb(19, 128, 60), Color.rgb(10, 83, 38), Color.WHITE};
            case STYLE_DARK:
                return new int[]{Color.rgb(74, 74, 80), Color.rgb(35, 35, 39), Color.rgb(14, 14, 16), Color.rgb(232, 232, 236)};
            default:
                // Light keys and shoulder buttons: cream keycaps.
                return new int[]{Color.rgb(246, 244, 238), Color.rgb(217, 213, 204), Color.rgb(163, 159, 150), Color.rgb(44, 43, 107)};
        }
    }

    private void drawRound(Canvas canvas, float width, float height, int[] palette) {
        float cx = width / 2.0f;
        float radius = Math.min(width, height) * 0.42f;
        float depth = radius * 0.16f;
        float press = this.pressed ? depth * 0.75f : 0.0f;
        float baseY = height / 2.0f - depth / 2.0f;
        float faceY = baseY + press;

        // Floor shadow.
        this.shadow.setShader(new RadialGradient(cx, baseY + depth + radius * 0.12f, radius * 1.2f,
                Color.argb(this.pressed ? 70 : 105, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, baseY + depth + radius * 0.12f, radius * 1.2f, this.shadow);
        this.shadow.setShader(null);
        // Side of the button.
        this.fill.setShader(null);
        this.fill.setColor(palette[2]);
        canvas.drawCircle(cx, baseY + depth, radius, this.fill);
        // Top face, lit from above.
        this.fill.setShader(new RadialGradient(cx, faceY - radius * 0.35f, radius * 1.45f,
                new int[]{palette[0], palette[1], palette[2]}, new float[]{0.0f, 0.68f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, faceY, radius, this.fill);
        this.fill.setShader(new LinearGradient(0, faceY, 0, faceY + radius,
                Color.argb(0, 0, 0, 0), Color.argb(55, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, faceY, radius, this.fill);
        this.fill.setShader(null);
        // Reflection on the upper half.
        RectF gloss = new RectF(cx - radius * 0.62f, faceY - radius * 0.9f, cx + radius * 0.62f, faceY - radius * 0.26f);
        this.highlight.setStyle(Paint.Style.FILL);
        this.highlight.setShader(new LinearGradient(0, gloss.top, 0, gloss.bottom,
                Color.argb(this.pressed ? 120 : 185, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawOval(gloss, this.highlight);
        this.highlight.setShader(null);
        drawLabel(canvas, cx, faceY, radius * 0.86f, palette[3]);
    }

    private void drawKeycap(Canvas canvas, float width, float height, int[] palette) {
        float depth = Math.max(3.0f, height * 0.12f);
        float press = this.pressed ? depth * 0.7f : 0.0f;
        float left = 3.0f;
        float right = width - 3.0f;
        float top = 2.0f;
        float bottom = height - depth - 5.0f;
        boolean shoulder = this.style == STYLE_SHOULDER;
        float radius = (bottom - top) * (shoulder ? 0.32f : 0.5f);

        // Floor shadow in two soft layers.
        this.shadow.setShader(null);
        this.shadow.setColor(Color.argb(this.pressed ? 26 : 40, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top + depth + 3.0f, right, bottom + depth + 4.0f), radius, radius, this.shadow);
        this.shadow.setColor(Color.argb(this.pressed ? 18 : 28, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left - 1.0f, top + depth + 5.0f, right + 1.0f, bottom + depth + 5.0f), radius, radius, this.shadow);
        // Side.
        this.fill.setShader(null);
        this.fill.setColor(palette[2]);
        canvas.drawRoundRect(new RectF(left, top + depth, right, bottom + depth), radius, radius, this.fill);
        // Face.
        RectF face = new RectF(left, top + press, right, bottom + press);
        this.fill.setShader(new LinearGradient(0, face.top, 0, face.bottom, palette[0], palette[1], Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, radius, radius, this.fill);
        this.fill.setShader(null);
        // Thin light edge along the top.
        this.highlight.setStyle(Paint.Style.STROKE);
        this.highlight.setStrokeWidth(1.6f);
        this.highlight.setShader(new LinearGradient(0, face.top, 0, face.centerY(),
                Color.argb(200, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(face.left + 1.0f, face.top + 1.0f, face.right - 1.0f, face.bottom - 1.0f), radius, radius, this.highlight);
        this.highlight.setShader(null);
        drawLabel(canvas, width / 2.0f, face.centerY(), (face.bottom - face.top) * 0.9f, palette[3]);
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
