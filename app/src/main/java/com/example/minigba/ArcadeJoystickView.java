package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class ArcadeJoystickView extends View {
    private int directionMask;
    private float knobX;
    private float knobY;
    private final Listener listener;
    private final Paint paint;

    interface Listener {
        void onDirectionMaskChanged(int i);
    }

    ArcadeJoystickView(Context context, Listener listener) {
        super(context);
        this.paint = new Paint(1);
        this.listener = listener;
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float min = Math.min(getWidth(), getHeight());
        float width = getWidth() / 2.0f;
        float height = getHeight() / 2.0f;
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(Color.rgb(96, 94, 91));
        float f = 0.45f * min;
        RectF rectF = new RectF(width - f, height - f, width + f, f + height);
        float f2 = 0.09f * min;
        canvas.drawRoundRect(rectF, f2, f2, this.paint);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeWidth(Math.max(2.0f, 0.018f * min));
        this.paint.setColor(Color.rgb(174, 171, 163));
        float f3 = 0.43f * min;
        RectF rectF2 = new RectF(width - f3, height - f3, width + f3, f3 + height);
        float f4 = 0.08f * min;
        canvas.drawRoundRect(rectF2, f4, f4, this.paint);
        this.paint.setStyle(Paint.Style.FILL);
        this.paint.setColor(Color.rgb(15, 16, 18));
        canvas.drawCircle(width, (0.025f * min) + height, 0.34f * min, this.paint);
        this.paint.setColor(Color.rgb(42, 43, 46));
        canvas.drawCircle(width, height, 0.3f * min, this.paint);
        float f5 = 0.13f * min;
        float f6 = width + (this.knobX * f5);
        float f7 = height + (this.knobY * f5);
        this.paint.setColor(Color.argb(145, 25, 12, 12));
        float f8 = 0.035f * min;
        canvas.drawCircle(f6, f7 + f8, 0.205f * min, this.paint);
        this.paint.setColor(this.directionMask == 0 ? Color.rgb(190, 25, 38) : Color.rgb(224, 35, 45));
        canvas.drawCircle(f6, f7, 0.195f * min, this.paint);
        this.paint.setColor(Color.argb(105, 255, 255, 255));
        canvas.drawOval(new RectF(f6 - (0.1f * min), f7 - (0.12f * min), f6 + (min * 0.045f), f7 - f8), this.paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        int i = 0;
        if (actionMasked == 1 || actionMasked == 3) {
            this.knobX = 0.0f;
            this.knobY = 0.0f;
            updateMask(0);
            invalidate();
            return true;
        }
        float x = motionEvent.getX() - (getWidth() / 2.0f);
        float y = motionEvent.getY() - (getHeight() / 2.0f);
        float max = Math.max(1.0f, Math.min(getWidth(), getHeight()) * 0.34f);
        float hypot = (float) Math.hypot(x, y);
        if (hypot > max) {
            x = (x * max) / hypot;
            y = (y * max) / hypot;
        }
        this.knobX = x / max;
        this.knobY = y / max;
        float f = max * 0.24f;
        if (Math.abs(x) > f) {
            i = x < 0.0f ? 32 : 16;
        }
        if (Math.abs(y) > f) {
            i |= y < 0.0f ? 64 : 128;
        }
        updateMask(i);
        invalidate();
        return true;
    }

    private void updateMask(int i) {
        if (this.directionMask == i) {
            return;
        }
        this.directionMask = i;
        this.listener.onDirectionMaskChanged(i);
    }
}
