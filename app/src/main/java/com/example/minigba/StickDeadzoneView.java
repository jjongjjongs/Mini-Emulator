package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/**
 * 건슈팅 › 데드존 (0.0.388): where the controller's stick is now (blue dot) against the dead
 * zone (red dashed circle), so the player can let go and see whether the stick rests inside it.
 */
final class StickDeadzoneView extends View {
    private final Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoneFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x;
    private float y;
    private float deadzone = 0.18f;

    StickDeadzoneView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        this.pad.setColor(Theme.bg(Color.rgb(241, 245, 249)));
        this.edge.setStyle(Paint.Style.STROKE);
        this.edge.setStrokeWidth(2f * density);
        this.edge.setColor(Theme.line(Color.rgb(203, 213, 225)));
        this.zone.setStyle(Paint.Style.STROKE);
        this.zone.setStrokeWidth(2f * density);
        this.zone.setColor(Color.rgb(220, 38, 38));
        this.zone.setPathEffect(new DashPathEffect(new float[]{5f * density, 4f * density}, 0));
        this.zoneFill.setColor(Color.argb(38, 220, 38, 38));
        this.dot.setColor(Color.rgb(37, 99, 235));
    }

    void setDeadzone(float deadzone) {
        this.deadzone = deadzone;
        invalidate();
    }

    void setStick(float x, float y) {
        if (x == this.x && y == this.y) {
            return;
        }
        this.x = x;
        this.y = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - this.edge.getStrokeWidth();
        canvas.drawCircle(cx, cy, r, this.pad);
        canvas.drawCircle(cx, cy, r, this.edge);
        canvas.drawCircle(cx, cy, r * this.deadzone, this.zoneFill);
        canvas.drawCircle(cx, cy, r * this.deadzone, this.zone);
        float density = getResources().getDisplayMetrics().density;
        canvas.drawCircle(cx + this.x * r, cy + this.y * r, 5f * density, this.dot);
    }
}
