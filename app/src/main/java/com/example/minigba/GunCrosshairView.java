package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * 건슈팅 (0.0.373): the crosshair over the game picture where the light gun points. It lies
 * over the whole screen frame and draws at a point inside the picture's view (the
 * SurfaceView), whose box GameActivity hands over with the aim; it never takes touches, so
 * they reach the picture underneath.
 */
final class GunCrosshairView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x;
    private float y;
    private boolean shown;

    GunCrosshairView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        this.ring.setStyle(Paint.Style.STROKE);
        this.ring.setStrokeWidth(2.2f * density);
        this.ring.setColor(Color.rgb(248, 113, 113));
        this.shade.setStyle(Paint.Style.STROKE);
        this.shade.setStrokeWidth(4.2f * density);
        this.shade.setColor(Color.argb(140, 0, 0, 0));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The aim in this view's coordinates, or hidden. */
    void setAim(boolean shown, float x, float y) {
        if (shown == this.shown && x == this.x && y == this.y) {
            return;
        }
        this.shown = shown;
        this.x = x;
        this.y = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!this.shown) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float r = 14f * density;
        float arm = 22f * density;
        for (Paint paint : new Paint[]{this.shade, this.ring}) {
            canvas.drawCircle(this.x, this.y, r, paint);
            canvas.drawLine(this.x - arm, this.y, this.x - r * 0.45f, this.y, paint);
            canvas.drawLine(this.x + r * 0.45f, this.y, this.x + arm, this.y, paint);
            canvas.drawLine(this.x, this.y - arm, this.x, this.y - r * 0.45f, paint);
            canvas.drawLine(this.x, this.y + r * 0.45f, this.x, this.y + arm, paint);
        }
    }
}
