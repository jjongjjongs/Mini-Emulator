package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

// On-screen analog stick. Reports axes in -32767..32767 and treats a short
// tap without movement as a stick click (L3/R3).
final class AnalogStickView extends View {
    interface Listener {
        void onStickMoved(int x, int y);

        void onStickClicked(boolean pressed);
    }

    private static final long CLICK_MAX_MS = 220;
    private static final float CLICK_MAX_TRAVEL = 0.18f;

    private final Listener listener;
    private final String label;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float knobX;
    private float knobY;
    private float maxTravel;
    private long downTime;

    AnalogStickView(Context context, String label, Listener listener) {
        super(context);
        this.label = label;
        this.listener = listener;
        basePaint.setColor(Color.rgb(190, 185, 175));
        knobPaint.setColor(Color.rgb(42, 43, 47));
        textPaint.setColor(Color.rgb(141, 143, 150));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float radius = Math.min(getWidth(), getHeight()) / 2.0f;
        float knobRadius = radius * 0.42f;
        canvas.drawCircle(cx, cy, radius, basePaint);
        canvas.drawCircle(cx + knobX, cy + knobY, knobRadius, knobPaint);
        textPaint.setTextSize(knobRadius * 0.55f);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(label, cx + knobX, cy + knobY - (metrics.ascent + metrics.descent) / 2.0f, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float radius = Math.min(getWidth(), getHeight()) / 2.0f;
        maxTravel = radius * 0.58f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = event.getEventTime();
                moveKnob(event.getX() - getWidth() / 2.0f, event.getY() - getHeight() / 2.0f);
                return true;
            case MotionEvent.ACTION_MOVE:
                moveKnob(event.getX() - getWidth() / 2.0f, event.getY() - getHeight() / 2.0f);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean click = event.getActionMasked() == MotionEvent.ACTION_UP
                        && event.getEventTime() - downTime <= CLICK_MAX_MS
                        && Math.hypot(knobX, knobY) <= maxTravel * CLICK_MAX_TRAVEL;
                moveKnob(0.0f, 0.0f);
                if (click) {
                    listener.onStickClicked(true);
                    postDelayed(() -> listener.onStickClicked(false), 90);
                }
                return true;
            default:
                return true;
        }
    }

    private void moveKnob(float dx, float dy) {
        float distance = (float) Math.hypot(dx, dy);
        if (distance > maxTravel && distance > 0.0f) {
            dx = dx / distance * maxTravel;
            dy = dy / distance * maxTravel;
        }
        knobX = dx;
        knobY = dy;
        int x = maxTravel > 0.0f ? Math.round(dx / maxTravel * 32767.0f) : 0;
        int y = maxTravel > 0.0f ? Math.round(dy / maxTravel * 32767.0f) : 0;
        listener.onStickMoved(x, y);
        invalidate();
    }
}
