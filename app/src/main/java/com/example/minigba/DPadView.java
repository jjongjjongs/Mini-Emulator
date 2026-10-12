package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

final class DPadView extends View {
    private final Paint activePaint;
    private final Paint arrowPaint;
    private final Paint centerPaint;
    private int directionMask;
    private final Paint highlightPaint;
    private final Listener listener;
    private final Paint paint;
    private final Paint shadowPaint;
    private final Paint shellPaint;
    private final Paint strokePaint;

    interface Listener {
        void onDirectionMaskChanged(int i);
    }

    DPadView(Context context, Listener listener) {
        super(context);
        Paint paint = new Paint(1);
        this.paint = paint;
        Paint paint2 = new Paint(1);
        this.activePaint = paint2;
        Paint paint3 = new Paint(1);
        this.strokePaint = paint3;
        Paint paint4 = new Paint(1);
        this.arrowPaint = paint4;
        Paint paint5 = new Paint(1);
        this.shadowPaint = paint5;
        Paint paint6 = new Paint(1);
        this.centerPaint = paint6;
        Paint paint7 = new Paint(1);
        this.shellPaint = paint7;
        Paint paint8 = new Paint(1);
        this.highlightPaint = paint8;
        this.listener = listener;
        paint.setColor(Color.rgb(34, 35, 38));
        paint.setStyle(Paint.Style.FILL);
        paint2.setColor(Color.rgb(58, 60, 64));
        paint2.setStyle(Paint.Style.FILL);
        paint3.setColor(Color.rgb(8, 9, 12));
        paint3.setStyle(Paint.Style.STROKE);
        paint3.setStrokeWidth(4.0f);
        paint4.setColor(Color.rgb(78, 80, 84));
        paint4.setStyle(Paint.Style.FILL);
        paint5.setColor(Color.argb(145, 30, 28, 24));
        paint5.setStyle(Paint.Style.FILL);
        paint6.setColor(Color.rgb(22, 23, 25));
        paint6.setStyle(Paint.Style.FILL);
        paint7.setColor(Color.rgb(10, 11, 14));
        paint7.setStyle(Paint.Style.FILL);
        paint8.setColor(Color.argb(80, 255, 255, 255));
        paint8.setStyle(Paint.Style.STROKE);
        paint8.setStrokeWidth(3.0f);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float min = Math.min(width, height);
        float f = min * 0.27f;
        float f2 = min * 0.06f;
        float f3 = width / 2.0f;
        float f4 = height / 2.0f;
        float f5 = f / 2.0f;
        float f6 = f3 - f5;
        drawSegment(canvas, f6, f2, f, f, 64);
        drawSegment(canvas, f6, (height - f) - f2, f, f, 128);
        float f7 = f4 - f5;
        drawSegment(canvas, f2, f7, f, f, 32);
        drawSegment(canvas, (width - f) - f2, f7, f, f, 16);
        canvas.drawCircle(f3, f4, min * 0.105f, this.centerPaint);
        float f8 = f2 + f5;
        drawArrow(canvas, f3, f8, 64);
        drawArrow(canvas, f3, (height - f2) - f5, 128);
        drawArrow(canvas, f8, f4, 32);
        drawArrow(canvas, (width - f2) - f5, f4, 16);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 1 || actionMasked == 3) {
            updateMask(0);
            return true;
        }
        int i = 0;
        for (int i2 = 0; i2 < motionEvent.getPointerCount(); i2++) {
            i |= maskForPoint(motionEvent.getX(i2), motionEvent.getY(i2));
        }
        updateMask(i);
        return true;
    }

    private int maskForPoint(float f, float f2) {
        int i;
        float width = f - (getWidth() / 2.0f);
        float height = f2 - (getHeight() / 2.0f);
        float abs = Math.abs(width);
        float abs2 = Math.abs(height);
        float min = Math.min(getWidth(), getHeight()) * 0.14f;
        float min2 = Math.min(getWidth(), getHeight()) * 0.17f;
        if (abs < min2 && abs2 < min2) {
            return 0;
        }
        if (abs >= abs2) {
            i = abs > min ? width < 0.0f ? 32 : 16 : 0;
            if (abs2 <= min || abs2 <= abs * 0.72f) {
                return i;
            }
            return i | (height >= 0.0f ? 128 : 64);
        }
        i = abs2 > min ? height < 0.0f ? 64 : 128 : 0;
        if (abs <= min || abs <= abs2 * 0.72f) {
            return i;
        }
        return i | (width >= 0.0f ? 16 : 32);
    }

    private void updateMask(int i) {
        if (this.directionMask == i) {
            return;
        }
        this.directionMask = i;
        this.listener.onDirectionMaskChanged(i);
        invalidate();
    }

    private void drawSegment(Canvas canvas, float f, float f2, float f3, float f4, int i) {
        Path path = new Path();
        float f5 = f2 + 7.0f;
        float f6 = f + f3;
        float f7 = f2 + f4;
        path.addRoundRect(f + 1.0f, f5, f6 - 1.0f, f7 + 5.0f, 18.0f, 18.0f, Path.Direction.CW);
        canvas.drawPath(path, this.shadowPaint);
        Path path2 = new Path();
        path2.addRoundRect(f, f2, f6, f7, 18.0f, 18.0f, Path.Direction.CW);
        canvas.drawPath(path2, this.shellPaint);
        Path path3 = new Path();
        path3.addRoundRect(f + 7.0f, f5, f6 - 7.0f, f7 - 7.0f, 13.0f, 13.0f, Path.Direction.CW);
        canvas.drawPath(path3, (this.directionMask & i) != 0 ? this.activePaint : this.paint);
        canvas.drawPath(path3, this.highlightPaint);
    }

    private void drawArrow(Canvas canvas, float f, float f2, int i) {
        float min = Math.min(getWidth(), getHeight()) * 0.08f;
        Path path = new Path();
        if (i == 64) {
            path.moveTo(f, f2 - min);
            float f3 = f2 + min;
            path.lineTo(f - min, f3);
            path.lineTo(f + min, f3);
        } else if (i == 128) {
            path.moveTo(f, f2 + min);
            float f4 = f2 - min;
            path.lineTo(f - min, f4);
            path.lineTo(f + min, f4);
        } else if (i == 32) {
            path.moveTo(f - min, f2);
            float f5 = f + min;
            path.lineTo(f5, f2 - min);
            path.lineTo(f5, f2 + min);
        } else if (i == 16) {
            path.moveTo(f + min, f2);
            float f6 = f - min;
            path.lineTo(f6, f2 - min);
            path.lineTo(f6, f2 + min);
        }
        path.close();
        canvas.drawPath(path, this.arrowPaint);
    }
}
