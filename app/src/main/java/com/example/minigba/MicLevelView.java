package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The small bars beside "듣는 중": how loud the microphone input is right now. */
final class MicLevelView extends View {
    private static final float[] SHAPE = {0.35f, 0.55f, 0.8f, 1f, 0.7f, 0.5f, 0.85f, 1f, 0.6f, 0.4f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float level;
    private int frame;

    MicLevelView(Context context) {
        super(context);
        this.paint.setColor(Color.rgb(251, 207, 232));
    }

    void setLevel(float value) {
        this.level = Math.max(0f, Math.min(1f, value));
        this.frame++;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = SHAPE.length;
        float w = getWidth();
        float h = getHeight();
        float step = w / n;
        float bw = step * 0.55f;
        for (int k = 0; k < n; k++) {
            // A little movement between bars so a steady sound still looks alive.
            float wobble = 0.85f + 0.15f * (float) Math.sin((this.frame + k * 3) * 0.6);
            float bh = Math.max(h * 0.18f, h * this.level * SHAPE[k] * wobble);
            float x = k * step + (step - bw) / 2f;
            this.bar.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f);
            canvas.drawRoundRect(this.bar, bw / 2f, bw / 2f, this.paint);
        }
    }
}
