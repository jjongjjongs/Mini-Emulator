package com.example.minigba;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Path;
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
    static final int STYLE_PURPLE = 9;
    static final int STYLE_PINK = 10;
    private final int button;
    private final Paint fill;
    private final Paint highlight;
    private final String label;

    String label() {
        return this.label;
    }
    private final Listener listener;
    private boolean pressed;
    private final Paint shadow;
    private final Paint stroke;
    private final int style;
    private final Paint text;
    // Colour for the label of round buttons; 0 keeps white (PSP symbols are coloured).
    private int labelColor;
    // "버튼 꾸미기": null keeps the built-in look.
    private ButtonStyle look;
    // Press bounce: the drawn size relative to the view, animated around a press.
    private float pulse = 1.0f;
    private android.animation.ValueAnimator pulseAnimator;

    // The layout editor's id of this control ("TouchButton.<bit>#n"), for its own colour.
    private String styleKey;

    boolean isShoulder() {
        return this.style == STYLE_SHOULDER;
    }

    void setStyle(ButtonStyle style, String key) {
        this.styleKey = key;
        this.look = style != null && style.themed() ? style : null;
        // Glows are blurred, which only a software layer draws.
        setLayerType(this.look != null && this.look.neon() && this.look.glow ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_NONE, null);
        invalidate();
    }

    boolean isKeycap() {
        android.view.ViewGroup.LayoutParams params = getLayoutParams();
        float w = params != null && params.width > 0 ? params.width : getWidth();
        float h = params != null && params.height > 0 ? params.height : getHeight();
        return Math.max(w, h) > Math.min(w, h) * 1.25f;
    }

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
        if (this.pulse != 1.0f) {
            canvas.scale(this.pulse, this.pulse, width / 2.0f, height / 2.0f);
        }
        boolean keycap = Math.max(width, height) > Math.min(width, height) * 1.25f;
        ButtonStyle look = this.look;
        if (look != null && (look.outline() || look.neon())) {
            drawLine(canvas, width, height, keycap, look);
        } else if (keycap) {
            drawKeycap(canvas, width, height);
        } else {
            int themed = look != null ? look.colorFor(this.styleKey, ButtonStyle.roleOf(this.label)) : 0;
            drawRound(canvas, width, height, themed != 0 ? ButtonStyle.palette(themed) : palette(),
                    themed != 0 ? (ButtonStyle.isLight(themed) ? Color.rgb(40, 44, 52) : Color.WHITE) : (this.labelColor != 0 ? this.labelColor : Color.WHITE));
        }
    }

    private int shape() {
        return this.look != null ? this.look.shape : ButtonStyle.SHAPE_CIRCLE;
    }

    // The round button's outline: a circle, or a square with softened or barely rounded corners.
    private Path shapePath(float cx, float cy, float radius) {
        Path path = new Path();
        int shape = shape();
        if (shape == ButtonStyle.SHAPE_CIRCLE) {
            path.addCircle(cx, cy, radius, Path.Direction.CW);
        } else {
            float half = radius * 0.94f;
            float corner = half * (shape == ButtonStyle.SHAPE_ROUNDED ? 0.42f : 0.16f);
            path.addRoundRect(new RectF(cx - half, cy - half, cx + half, cy + half), corner, corner, Path.Direction.CW);
        }
        return path;
    }

    // "윤곽선만" and "네온": a thin ring in the theme colour on a clear (or near-black) body;
    // pressing fills it with the colour, and neon lets it glow.
    private void drawLine(Canvas canvas, float width, float height, boolean keycap, ButtonStyle style) {
        int color = style.colorFor(this.styleKey, keycap ? ButtonStyle.ROLE_PILL : ButtonStyle.roleOf(this.label));
        boolean pressed = looksPressed();
        float stroke = Math.max(2.0f, Math.min(width, height) * (keycap ? 0.07f : 0.06f));
        Path path;
        float cx = width / 2.0f;
        float cy = height / 2.0f;
        float labelSize;
        if (keycap) {
            RectF face = new RectF(3.0f + stroke, 2.0f + stroke, width - 3.0f - stroke, height - 5.0f - stroke);
            float radius = (face.bottom - face.top) * (style.shape == ButtonStyle.SHAPE_SQUARE ? 0.2f : 0.5f);
            path = new Path();
            path.addRoundRect(face, radius, radius, Path.Direction.CW);
            cy = face.centerY();
            labelSize = (face.bottom - face.top) * 0.9f;
        } else {
            float radius = Math.min(width, height) * 0.42f - stroke;
            path = shapePath(cx, cy, radius);
            labelSize = radius * 0.86f;
        }
        if (style.neon()) {
            // Near-black body so the glow reads at night.
            this.fill.setShader(null);
            this.fill.setColor(pressed ? ButtonStyle.withAlpha(color, 230) : Color.argb(200, 18, 19, 24));
            canvas.drawPath(path, this.fill);
            if (style.glow) {
                this.stroke.setColor(ButtonStyle.withAlpha(color, pressed ? 220 : 150));
                this.stroke.setStrokeWidth(stroke * (pressed ? 3.0f : 2.2f));
                this.stroke.setMaskFilter(new BlurMaskFilter(stroke * (pressed ? 4.0f : 2.5f), BlurMaskFilter.Blur.NORMAL));
                canvas.drawPath(path, this.stroke);
                this.stroke.setMaskFilter(null);
            }
            this.stroke.setColor(pressed ? Color.WHITE : color);
            this.stroke.setStrokeWidth(stroke * 0.8f);
            canvas.drawPath(path, this.stroke);
            drawLabel(canvas, cx, cy, labelSize, pressed ? Color.WHITE : color);
        } else {
            this.fill.setShader(null);
            this.fill.setColor(pressed ? Color.argb(160, 255, 255, 255) : Color.argb(28, 255, 255, 255));
            canvas.drawPath(path, this.fill);
            this.stroke.setColor(color);
            this.stroke.setStrokeWidth(stroke);
            canvas.drawPath(path, this.stroke);
            drawLabel(canvas, cx, cy, labelSize, pressed ? Color.rgb(40, 44, 52) : color);
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
            case STYLE_PURPLE:
                // WonderSwan Y pad.
                return new int[]{Color.rgb(167, 139, 250), Color.rgb(124, 58, 237), Color.rgb(76, 29, 149)};
            case STYLE_PINK:
                // DS MIC button.
                return new int[]{Color.rgb(244, 114, 182), Color.rgb(219, 39, 119), Color.rgb(131, 24, 67)};
            default:
                // GBA A/B and anything else round: smoky black.
                return new int[]{Color.rgb(58, 58, 63), Color.rgb(20, 20, 22), Color.rgb(0, 0, 0)};
        }
    }

    private void drawRound(Canvas canvas, float width, float height, int[] palette, int labelColor) {
        float cx = width / 2.0f;
        float radius = Math.min(width, height) * 0.42f;
        float press = looksPressed() ? radius * 0.05f : 0.0f;
        float cy = height / 2.0f - radius * 0.04f + press;

        // Coloured glow the plastic throws on the case, plus a tight contact shadow.
        float glowY = cy + radius * (looksPressed() ? 0.06f : 0.14f);
        this.shadow.setShader(new RadialGradient(cx, glowY, radius * 1.22f,
                withAlpha(palette[2], looksPressed() ? 70 : 110), withAlpha(palette[2], 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, glowY, radius * 1.22f, this.shadow);
        this.shadow.setShader(null);
        this.shadow.setColor(Color.argb(60, 0, 0, 0));
        Path body = shapePath(cx, cy, radius);
        canvas.save();
        canvas.translate(0, Math.max(1.0f, radius * 0.03f));
        canvas.drawPath(body, this.shadow);
        canvas.restore();

        // See-through body, brighter below the centre.
        this.fill.setShader(new RadialGradient(cx, cy + radius * 0.2f, radius * 1.1f,
                new int[]{withAlpha(palette[0], 204), withAlpha(palette[1], 179), withAlpha(palette[2], 204)},
                new float[]{0.0f, 0.7f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawPath(body, this.fill);
        // Light pooling at the bottom inside the plastic.
        this.fill.setShader(new RadialGradient(cx, cy + radius * 0.8f, radius * 0.75f,
                withAlpha(lighter(palette[0]), 150), withAlpha(palette[0], 0), Shader.TileMode.CLAMP));
        canvas.drawPath(body, this.fill);
        this.fill.setShader(null);

        // Thin bright rim.
        float rim = Math.max(1.5f, radius * 0.06f);
        this.highlight.setShader(null);
        this.highlight.setStyle(Paint.Style.STROKE);
        this.highlight.setStrokeWidth(rim);
        this.highlight.setColor(Color.argb(90, 255, 255, 255));
        if (shape() == ButtonStyle.SHAPE_CIRCLE) {
            canvas.drawCircle(cx, cy, radius - rim / 2.0f, this.highlight);
        } else {
            canvas.drawPath(shapePath(cx, cy, radius - rim / 2.0f), this.highlight);
        }

        // Small tilted reflection in the upper left.
        this.highlight.setStyle(Paint.Style.FILL);
        this.highlight.setColor(Color.argb(looksPressed() ? 140 : 190, 255, 255, 255));
        canvas.save();
        canvas.clipPath(body);
        canvas.rotate(-25.0f, cx - radius * 0.28f, cy - radius * 0.58f);
        canvas.drawOval(new RectF(cx - radius * 0.64f, cy - radius * 0.78f, cx + radius * 0.08f, cy - radius * 0.38f), this.highlight);
        canvas.restore();
        drawLabel(canvas, cx, cy, radius * 0.86f, labelColor);
    }

    // Wide keys (L/R, SELECT/START, coin and the like): smoky see-through plastic.
    private void drawKeycap(Canvas canvas, float width, float height) {
        float press = looksPressed() ? 2.0f : 0.0f;
        float left = 3.0f;
        float right = width - 3.0f;
        float top = 2.0f + press;
        float bottom = height - 7.0f + press;
        boolean shoulder = this.style == STYLE_SHOULDER;
        float radius = (bottom - top) * (shoulder ? 0.32f : 0.5f);
        RectF face = new RectF(left, top, right, bottom);

        // Soft shadow underneath.
        float drop = looksPressed() ? 2.0f : 4.0f;
        this.shadow.setShader(null);
        this.shadow.setColor(Color.argb(looksPressed() ? 26 : 38, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left - 1.0f, top + drop, right + 1.0f, bottom + drop + 1.0f), radius, radius, this.shadow);
        this.shadow.setColor(Color.argb(looksPressed() ? 34 : 50, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top + drop * 0.5f, right, bottom + drop * 0.5f), radius, radius, this.shadow);

        int themed = this.look != null ? this.look.colorFor(this.styleKey, ButtonStyle.ROLE_PILL) : 0;
        if (themed != 0) {
            int[] palette = ButtonStyle.palette(themed);
            this.fill.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                    withAlpha(palette[0], looksPressed() ? 235 : 225), withAlpha(palette[1], looksPressed() ? 245 : 235), Shader.TileMode.CLAMP));
        } else {
            this.fill.setShader(new LinearGradient(0, face.top, 0, face.bottom,
                    Color.argb(looksPressed() ? 205 : 190, 70, 70, 80), Color.argb(looksPressed() ? 230 : 217, 30, 30, 38), Shader.TileMode.CLAMP));
        }
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
        drawLabel(canvas, width / 2.0f, face.centerY(), (face.bottom - face.top) * 0.9f,
                themed != 0 && ButtonStyle.isLight(themed) ? Color.rgb(40, 44, 52) : Color.WHITE);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int lighter(int color) {
        return Color.rgb((Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2);
    }

    private void drawLabel(Canvas canvas, float x, float centerY, float size, int color) {
        int mode = this.look != null ? this.look.label : ButtonStyle.LABEL_SHOW;
        if (mode == ButtonStyle.LABEL_HIDE) {
            return;
        }
        boolean wide = Math.max(getWidth(), getHeight()) > Math.min(getWidth(), getHeight()) * 1.25f;
        float textSize = wide ? Math.min(size * 0.5f, getWidth() / Math.max(3.0f, this.label.length() + 1.5f)) : Math.max(16.0f, size * 0.7f);
        this.text.setTextSize(mode == ButtonStyle.LABEL_SMALL ? textSize * 0.62f : textSize);
        Paint.FontMetrics metrics = this.text.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2.0f;
        boolean darkLabel = Color.red(color) + Color.green(color) + Color.blue(color) < 300;
        this.text.setColor(darkLabel ? Color.argb(110, 255, 255, 255) : Color.argb(110, 0, 0, 0));
        canvas.drawText(this.label, x, baseline + (darkLabel ? 1.0f : 1.5f), this.text);
        this.text.setColor(color);
        canvas.drawText(this.label, x, baseline, this.text);
    }

    void setLabelColor(int color) {
        this.labelColor = color;
        invalidate();
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

    int bit() {
        return this.button;
    }

    // Held by a combo zone between two buttons (the finger is not on this view itself).
    private int comboHolds;

    void setComboHeld(boolean held) {
        this.comboHolds = Math.max(0, this.comboHolds + (held ? 1 : -1));
        invalidate();
    }

    // "살짝 커졌다 돌아오기": a quick swell and settle, drawn without touching the view's scale.
    private void bounce() {
        if (this.pulseAnimator != null) {
            this.pulseAnimator.cancel();
        }
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(1.0f, 1.14f, 1.0f);
        animator.setDuration(220);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            this.pulse = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
        this.pulseAnimator = animator;
    }

    private boolean looksPressed() {
        return this.pressed || this.comboHolds > 0;
    }

    private void setPressedState(boolean z) {
        if (this.pressed == z) {
            return;
        }
        this.pressed = z;
        if (z) {
            Haptics.tap();
            if (this.look != null && this.look.bounce) {
                bounce();
            }
        }
        this.listener.onButtonChanged(this.button, z);
        invalidate();
    }
}
