package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class TouchButton extends View {
    static final int STYLE_ACTION = 3;
    static final int STYLE_ARCADE_BLUE = 5;
    static final int STYLE_ARCADE_RED = 4;
    static final int STYLE_DARK = 1;
    static final int STYLE_LIGHT = 0;
    static final int STYLE_SHOULDER = 2;
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

    @Override
    protected void onDraw(Canvas canvas) {
        int i;
        int rgb;
        RectF rectF;
        super.onDraw(canvas);
        float min = Math.min(getWidth(), getHeight()) * 0.48f;
        int i2 = this.style;
        boolean z = i2 == 1;
        boolean z2 = i2 == 2;
        boolean z3 = i2 == 3;
        boolean z4 = i2 == 4;
        boolean z5 = i2 == 5;
        if (z4 || z5) {
            if (z4) {
                this.fill.setColor(this.pressed ? Color.rgb(242, 31, 49) : Color.rgb(207, 15, 34));
                this.stroke.setColor(Color.rgb(116, 8, 22));
                this.text.setColor(Color.rgb(91, 4, 18));
                this.shadow.setColor(Color.argb(165, 76, 4, 13));
                this.highlight.setColor(Color.argb(120, 255, 165, 172));
            } else {
                Paint paint = this.fill;
                if (this.pressed) {
                    rgb = Color.rgb(18, 213, 226);
                    i = 0;
                } else {
                    i = 0;
                    rgb = Color.rgb(0, 174, 194);
                }
                paint.setColor(rgb);
                this.stroke.setColor(Color.rgb(i, 83, 96));
                this.text.setColor(Color.rgb(i, 65, 75));
                this.shadow.setColor(Color.argb(165, i, 59, 68));
                this.highlight.setColor(Color.argb(125, 172, 255, 255));
            }
        } else if (z3) {
            this.fill.setColor(this.pressed ? Color.rgb(178, 37, 117) : Color.rgb(151, 21, 94));
            this.stroke.setColor(Color.rgb(78, 21, 55));
            this.highlight.setColor(Color.argb(120, 255, 184, 226));
            this.text.setColor(Color.rgb(255, 215, 238));
            this.shadow.setColor(Color.argb(150, 70, 16, 50));
        } else if (z2) {
            this.fill.setColor(this.pressed ? Color.rgb(225, 222, 214) : Color.rgb(203, 199, 190));
            this.stroke.setColor(Color.rgb(120, 116, 108));
            this.highlight.setColor(Color.argb(135, 255, 255, 255));
            this.text.setColor(Color.rgb(44, 43, 107));
            this.shadow.setColor(Color.argb(130, 86, 82, 74));
        } else {
            this.fill.setColor(this.pressed ? Color.rgb(190, 187, 181) : Color.rgb(214, 211, 204));
            this.stroke.setColor(z ? Color.rgb(34, 34, 38) : Color.rgb(126, 122, 116));
            this.highlight.setColor(Color.argb(145, 255, 255, 255));
            this.text.setColor(z ? Color.rgb(210, 207, 200) : Color.rgb(42, 41, 101));
            this.shadow.setColor(Color.argb(z ? 145 : 125, 70, 66, 60));
        }
        if (Math.max(getWidth(), getHeight()) > Math.min(getWidth(), getHeight()) * 1.25f) {
            float min2 = Math.min(getWidth(), getHeight()) * (z2 ? 0.52f : 0.46f);
            canvas.drawRoundRect(new RectF(3.0f, z2 ? 6.0f : 7.0f, getWidth() - 3.0f, getHeight() - 1.0f), min2, min2, this.shadow);
            Paint paint2 = new Paint(1);
            paint2.setStyle(Paint.Style.FILL);
            paint2.setColor(this.stroke.getColor());
            canvas.drawRoundRect(new RectF(3.0f, 2.0f, getWidth() - 3.0f, getHeight() - (z2 ? 4.0f : 5.0f)), min2, min2, paint2);
            if (z2) {
                rectF = new RectF(9.0f, 7.0f, getWidth() - 9.0f, getHeight() - 11.0f);
            } else {
                rectF = new RectF(10.0f, 8.0f, getWidth() - 10.0f, getHeight() - 12.0f);
            }
            float f = min2 * (z2 ? 0.86f : 0.7f);
            canvas.drawRoundRect(rectF, f, f, this.fill);
            if (!z2) {
                canvas.drawRoundRect(rectF, f, f, this.highlight);
            }
        } else {
            float width = getWidth() / 2.0f;
            float height = getHeight() / 2.0f;
            canvas.drawCircle(width, 5.0f + height, min, this.shadow);
            Paint paint3 = new Paint(1);
            paint3.setStyle(Paint.Style.FILL);
            paint3.setColor(this.stroke.getColor());
            canvas.drawCircle(width, height, min, paint3);
            canvas.drawCircle(width, height - 1.0f, 0.89f * min, this.fill);
            if (!z3 && !z4 && !z5) {
                canvas.drawCircle(width - (0.12f * min), height - (0.18f * min), min * 0.62f, this.highlight);
            } else if (z4 || z5) {
                paintArcadeHighlight(canvas, width, height, min);
            }
        }
        this.text.setTextSize(Math.max(18.0f, Math.min(getWidth(), getHeight()) * 0.38f));
        Paint.FontMetrics fontMetrics = this.text.getFontMetrics();
        canvas.drawText(this.label, getWidth() / 2.0f, (getHeight() / 2.0f) - ((fontMetrics.ascent + fontMetrics.descent) / 2.0f), this.text);
    }

    private void paintArcadeHighlight(Canvas canvas, float f, float f2, float f3) {
        this.highlight.setStyle(Paint.Style.FILL);
        canvas.drawOval(new RectF(f - (0.5f * f3), f2 - (0.56f * f3), f + (0.14f * f3), f2 - (f3 * 0.18f)), this.highlight);
        this.highlight.setStyle(Paint.Style.STROKE);
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
