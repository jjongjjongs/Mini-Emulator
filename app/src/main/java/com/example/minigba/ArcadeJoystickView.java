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

    // Glossy 3D stick: a rounded base plate with a recessed hole and a shiny red ball
    // that leans toward the pushed direction.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float min = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        this.paint.setStyle(Paint.Style.FILL);

        float half = 0.45f * min;
        float corner = 0.1f * min;
        RectF base = new RectF(cx - half, cy - half, cx + half, cy + half);
        this.paint.setShader(null);
        this.paint.setColor(Color.argb(60, 0, 0, 0));
        canvas.drawRoundRect(new RectF(base.left, base.top + min * 0.04f, base.right, base.bottom + min * 0.04f), corner, corner, this.paint);
        this.paint.setShader(new LinearGradient(0, base.top, 0, base.bottom, Color.rgb(112, 112, 110), Color.rgb(70, 70, 68), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, corner, corner, this.paint);
        this.paint.setShader(null);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeWidth(Math.max(1.5f, 0.012f * min));
        this.paint.setShader(new LinearGradient(0, base.top, 0, base.bottom, Color.argb(120, 255, 255, 255), Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, corner, corner, this.paint);
        this.paint.setShader(null);
        this.paint.setStyle(Paint.Style.FILL);

        // Recessed hole.
        this.paint.setShader(new RadialGradient(cx, cy - min * 0.03f, 0.34f * min,
                new int[]{Color.rgb(8, 8, 10), Color.rgb(26, 26, 30), Color.rgb(48, 48, 54)}, new float[]{0, 0.8f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, 0.33f * min, this.paint);
        this.paint.setShader(null);

        float travel = 0.13f * min;
        float bx = cx + this.knobX * travel;
        float by = cy + this.knobY * travel - min * 0.02f;
        float ball = 0.215f * min;
        this.paint.setShader(new RadialGradient(bx, by + ball * 0.55f, ball * 1.2f,
                Color.argb(140, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(bx, by + ball * 0.55f, ball * 1.2f, this.paint);
        boolean pushed = this.directionMask != 0;
        this.paint.setShader(new RadialGradient(bx - ball * 0.25f, by - ball * 0.35f, ball * 1.4f,
                new int[]{Color.rgb(255, 150, 150), pushed ? Color.rgb(240, 50, 64) : Color.rgb(227, 38, 58), Color.rgb(122, 11, 21)},
                new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(bx, by, ball, this.paint);
        this.paint.setShader(new LinearGradient(0, by - ball * 0.85f, 0, by - ball * 0.2f,
                Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawOval(new RectF(bx - ball * 0.55f, by - ball * 0.85f, bx + ball * 0.4f, by - ball * 0.2f), this.paint);
        this.paint.setShader(null);
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
