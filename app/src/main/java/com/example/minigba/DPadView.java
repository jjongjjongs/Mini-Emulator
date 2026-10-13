package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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

    // Smoky see-through cross: a soft shadow, tinted plastic with a shine along each arm
    // and a thin bright rim. A pressed arm darkens.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float min = Math.min(width, height);
        float cx = width / 2.0f;
        float cy = height / 2.0f;
        float arm = min * 0.32f;
        float reach = min * 0.46f;
        float corner = arm * 0.22f;
        float depth = min * 0.03f;
        Path cross = crossPath(cx, cy - depth / 2.0f, arm, reach, corner);

        canvas.save();
        canvas.translate(0, depth * 1.6f);
        this.shadowPaint.setColor(Color.argb(45, 0, 0, 0));
        canvas.drawPath(cross, this.shadowPaint);
        canvas.restore();
        canvas.save();
        canvas.translate(0, depth * 0.7f);
        this.shellPaint.setColor(Color.argb(40, 0, 0, 0));
        canvas.drawPath(cross, this.shellPaint);
        canvas.restore();

        this.paint.setShader(new LinearGradient(cx - reach, 0, cx + reach, 0,
                new int[]{Color.argb(217, 60, 60, 70), Color.argb(230, 20, 20, 26), Color.argb(217, 60, 60, 70)}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(cross, this.paint);
        this.paint.setShader(new LinearGradient(0, cy - reach, 0, cy + reach,
                new int[]{Color.argb(60, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(40, 120, 120, 140)}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(cross, this.paint);
        this.paint.setShader(null);

        // Pressed arms darken.
        for (int bit : new int[]{64, 128, 32, 16}) {
            if ((this.directionMask & bit) != 0) {
                canvas.save();
                canvas.clipPath(cross);
                this.activePaint.setColor(Color.argb(100, 0, 0, 0));
                canvas.drawRect(armRect(bit, cx, cy, arm, reach), this.activePaint);
                canvas.restore();
            }
        }
        this.highlightPaint.setStyle(Paint.Style.STROKE);
        this.highlightPaint.setStrokeWidth(Math.max(1.5f, min * 0.008f));
        this.highlightPaint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawPath(cross, this.highlightPaint);

        // Centre dimple.
        this.centerPaint.setShader(new RadialGradient(cx, cy - depth / 2.0f, arm * 0.36f,
                new int[]{Color.argb(220, 10, 10, 12), Color.argb(200, 60, 60, 68)}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy - depth / 2.0f, arm * 0.34f, this.centerPaint);
        this.centerPaint.setShader(null);

        float tip = reach - arm * 0.42f;
        this.arrowPaint.setColor(Color.argb(95, 255, 255, 255));
        drawArrow(canvas, cx, cy - tip, 64);
        drawArrow(canvas, cx, cy + tip, 128);
        drawArrow(canvas, cx - tip, cy, 32);
        drawArrow(canvas, cx + tip, cy, 16);
    }

    private static Path crossPath(float cx, float cy, float arm, float reach, float corner) {
        Path vertical = new Path();
        vertical.addRoundRect(new RectF(cx - arm / 2.0f, cy - reach, cx + arm / 2.0f, cy + reach), corner, corner, Path.Direction.CW);
        Path horizontal = new Path();
        horizontal.addRoundRect(new RectF(cx - reach, cy - arm / 2.0f, cx + reach, cy + arm / 2.0f), corner, corner, Path.Direction.CW);
        vertical.op(horizontal, Path.Op.UNION);
        return vertical;
    }

    private static RectF armRect(int bit, float cx, float cy, float arm, float reach) {
        float half = arm / 2.0f;
        switch (bit) {
            case 64:
                return new RectF(cx - half, cy - reach, cx + half, cy - half);
            case 128:
                return new RectF(cx - half, cy + half, cx + half, cy + reach);
            case 32:
                return new RectF(cx - reach, cy - half, cx - half, cy + half);
            default:
                return new RectF(cx + half, cy - half, cx + reach, cy + half);
        }
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
