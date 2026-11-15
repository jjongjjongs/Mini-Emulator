package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class SpeedButtonView extends View {
    private final Paint fill;
    private final Paint icon;
    private final Listener listener;
    // 50, 100 or 200 percent; each tap goes 1x -> 2x -> 0.5x -> 1x.
    private int percent;
    private boolean pressed;
    private final Paint stroke;
    private final Paint text;

    interface Listener {
        void onChanged(int i);
    }

    SpeedButtonView(Context context, Listener listener) {
        super(context);
        this.fill = new Paint(1);
        Paint paint = new Paint(1);
        this.stroke = paint;
        Paint paint2 = new Paint(1);
        this.icon = paint2;
        Paint paint3 = new Paint(1);
        this.text = paint3;
        this.percent = 100;
        this.listener = listener;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.0f);
        paint2.setStyle(Paint.Style.FILL);
        paint3.setTextAlign(Paint.Align.CENTER);
        paint3.setFakeBoldText(true);
        setFocusable(false);
    }

    void setPercent(int percent) {
        this.percent = percent >= 200 ? 200 : percent <= 50 ? 50 : 100;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int i;
        int i2;
        int i3;
        int i4;
        int i5;
        int i6;
        int i7;
        int i8;
        int i9;
        super.onDraw(canvas);
        RectF rectF = new RectF(4.0f, 8.0f, getWidth() - 4.0f, getHeight() - 2.0f);
        this.fill.setStyle(Paint.Style.FILL);
        this.fill.setColor(Color.argb(130, 58, 54, 48));
        canvas.drawRoundRect(rectF, getHeight() * 0.4f, getHeight() * 0.4f, this.fill);
        RectF rectF2 = new RectF(4.0f, 4.0f, getWidth() - 4.0f, getHeight() - 7.0f);
        this.fill.setStyle(Paint.Style.FILL);
        boolean z = this.percent > 100;
        boolean slow = this.percent < 100;
        Paint paint = this.fill;
        if (slow) {
            i = 61;
            i2 = 82;
            i3 = 32;
        } else if (z) {
            i = 37;
            i2 = 68;
            i3 = 82;
        } else {
            i = 67;
            i2 = 64;
            i3 = 69;
        }
        paint.setColor(Color.rgb(i3, i, i2));
        this.stroke.setColor(Color.rgb(39, 38, 40));
        canvas.drawRoundRect(rectF2, getHeight() * 0.4f, getHeight() * 0.4f, this.fill);
        canvas.drawRoundRect(rectF2, getHeight() * 0.4f, getHeight() * 0.4f, this.stroke);
        this.icon.setColor(slow ? Color.rgb(94, 200, 242) : z ? Color.rgb(222, 76, 154) : Color.rgb(218, 215, 207));
        float height = (getHeight() / 2.0f) - 1.0f;
        if (slow) {
            drawSlow(canvas, getWidth() * 0.24f, height, getHeight() * 0.28f);
        } else {
            drawFastForward(canvas, getWidth() * 0.24f, height, getHeight() * 0.28f);
        }
        Paint paint2 = this.fill;
        if (slow) {
            i4 = 127;
            i5 = 184;
            i6 = 31;
        } else if (z) {
            i4 = 25;
            i5 = 103;
            i6 = 166;
        } else {
            i4 = 211;
            i5 = 204;
            i6 = 214;
        }
        paint2.setColor(Color.rgb(i6, i4, i5));
        float width = getWidth() * (slow ? 0.62f : 0.67f);
        float knob = getHeight() * (slow ? 0.19f : 0.23f);
        canvas.drawCircle(width, height, knob, this.fill);
        Paint paint3 = this.stroke;
        if (slow) {
            i7 = 64;
            i8 = 94;
            i9 = 19;
        } else if (z) {
            i7 = 21;
            i8 = 55;
            i9 = 78;
        } else {
            i7 = 108;
            i8 = 102;
            i9 = 112;
        }
        paint3.setColor(Color.rgb(i9, i7, i8));
        canvas.drawCircle(width, height, knob, this.stroke);
        this.text.setTextSize(getHeight() * (slow ? 0.23f : 0.27f));
        this.text.setColor(slow ? Color.rgb(217, 242, 255) : z ? Color.rgb(255, 225, 241) : Color.rgb(218, 215, 207));
        Paint.FontMetrics fontMetrics = this.text.getFontMetrics();
        String label = slow ? "0.5x" : z ? "2x" : "1x";
        canvas.drawText(label, getWidth() * (slow ? 0.81f : 0.83f), height - ((fontMetrics.ascent + fontMetrics.descent) / 2.0f), this.text);
    }

    // A bar and a single play triangle: slow motion.
    private void drawSlow(Canvas canvas, float f, float f2, float f3) {
        canvas.drawRoundRect(new RectF(f - (0.72f * f3), f2 - f3, f - (0.40f * f3), f2 + f3), f3 * 0.12f, f3 * 0.12f, this.icon);
        Path path = new Path();
        path.moveTo(f - (0.15f * f3), f2 - f3);
        path.lineTo(f - (0.15f * f3), f2 + f3);
        path.lineTo(f + (f3 * 1.0f), f2);
        path.close();
        canvas.drawPath(path, this.icon);
    }

    private void drawFastForward(Canvas canvas, float f, float f2, float f3) {
        Path path = new Path();
        float f4 = f - (0.72f * f3);
        float f5 = f2 - f3;
        path.moveTo(f4, f5);
        float f6 = f2 + f3;
        path.lineTo(f4, f6);
        path.lineTo((0.24f * f3) + f, f2);
        path.close();
        canvas.drawPath(path, this.icon);
        Path path2 = new Path();
        float f7 = (0.12f * f3) + f;
        path2.moveTo(f7, f5);
        path2.lineTo(f7, f6);
        path2.lineTo(f + (f3 * 1.08f), f2);
        path2.close();
        canvas.drawPath(path2, this.icon);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            this.pressed = true;
            invalidate();
            return true;
        }
        if (actionMasked != 1) {
            if (actionMasked == 3) {
                this.pressed = false;
                invalidate();
            }
            return true;
        }
        this.pressed = false;
        int next = this.percent == 100 ? 200 : this.percent == 200 ? 50 : 100;
        this.percent = next;
        this.listener.onChanged(next);
        invalidate();
        return true;
    }
}
