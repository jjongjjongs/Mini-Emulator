package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * "화면 모양": lies over the game picture and paints its corners in the frame's colour so
 * the picture looks rounded (a SurfaceView cannot be clipped), plus an optional thin line
 * around it. Touches pass through.
 */
final class ScreenMaskView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private boolean border;

    ScreenMaskView(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(18, 22, 32));
        line.setStyle(Paint.Style.STROKE);
        line.setColor(Color.argb(170, 0, 0, 0));
        setClickable(false);
        setFocusable(false);
    }

    void setShape(float radiusPx, boolean borderLine, int frameColor) {
        this.radius = radiusPx;
        this.border = borderLine;
        fill.setColor(frameColor);
        line.setStrokeWidth(Math.max(1.5f, getResources().getDisplayMetrics().density * 1.2f));
        setVisibility(radiusPx > 0 || borderLine ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (radius > 0) {
            // Everything outside the rounded rectangle, inside the view: the four corners.
            Path outside = new Path();
            outside.addRect(0, 0, w, h, Path.Direction.CW);
            Path inside = new Path();
            inside.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CCW);
            outside.op(inside, Path.Op.DIFFERENCE);
            canvas.drawPath(outside, fill);
        }
        if (border) {
            float half = line.getStrokeWidth() / 2f;
            canvas.drawRoundRect(new RectF(half, half, w - half, h - half), Math.max(0, radius - half), Math.max(0, radius - half), line);
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }
}
