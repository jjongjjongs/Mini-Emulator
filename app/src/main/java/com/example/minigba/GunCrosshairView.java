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
    // (0.0.387) 2P's aim, drawn blue.
    private float x2;
    private float y2;
    private boolean shown2;
    private final Paint ring2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    // (0.0.386) A white frame just outside the picture, for light guns that find the screen
    // by its edge (Sinden); null when off.
    private android.graphics.RectF border;
    private final Paint white = new Paint();

    GunCrosshairView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        this.ring.setStyle(Paint.Style.STROKE);
        this.ring.setStrokeWidth(2.2f * density);
        this.ring.setColor(Color.rgb(248, 113, 113));
        this.ring2.setStyle(Paint.Style.STROKE);
        this.ring2.setStrokeWidth(2.2f * density);
        this.ring2.setColor(Color.rgb(96, 165, 250));
        this.shade.setStyle(Paint.Style.STROKE);
        this.shade.setStrokeWidth(4.2f * density);
        this.shade.setColor(Color.argb(140, 0, 0, 0));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setBorder(android.graphics.RectF picture) {
        if (java.util.Objects.equals(picture, this.border)) {
            return;
        }
        this.border = picture == null ? null : new android.graphics.RectF(picture);
        invalidate();
    }

    void setAim2(boolean shown, float x, float y) {
        if (shown == this.shown2 && x == this.x2 && y == this.y2) {
            return;
        }
        this.shown2 = shown;
        this.x2 = x;
        this.y2 = y;
        invalidate();
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
        if (this.border != null) {
            float w = Math.max(6f, Math.min(this.border.width(), this.border.height()) * 0.03f);
            this.white.setColor(Color.WHITE);
            this.white.setStyle(Paint.Style.FILL);
            android.graphics.RectF b = this.border;
            canvas.drawRect(b.left - w, b.top - w, b.right + w, b.top, this.white);
            canvas.drawRect(b.left - w, b.bottom, b.right + w, b.bottom + w, this.white);
            canvas.drawRect(b.left - w, b.top, b.left, b.bottom, this.white);
            canvas.drawRect(b.right, b.top, b.right + w, b.bottom, this.white);
        }
        if (this.shown) {
            drawAim(canvas, this.x, this.y, this.ring);
        }
        if (this.shown2) {
            drawAim(canvas, this.x2, this.y2, this.ring2);
        }
    }

    private void drawAim(Canvas canvas, float x, float y, Paint color) {
        float density = getResources().getDisplayMetrics().density;
        float r = 14f * density;
        float arm = 22f * density;
        for (Paint paint : new Paint[]{this.shade, color}) {
            canvas.drawCircle(x, y, r, paint);
            canvas.drawLine(x - arm, y, x - r * 0.45f, y, paint);
            canvas.drawLine(x + r * 0.45f, y, x + arm, y, paint);
            canvas.drawLine(x, y - arm, x, y - r * 0.45f, paint);
            canvas.drawLine(x, y + r * 0.45f, x, y + arm, paint);
        }
    }
}
