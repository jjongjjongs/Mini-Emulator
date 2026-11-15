package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

// A small on/off switch drawn like the mockups; the row around it handles the taps.
final class ToggleView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int onColor;
    private boolean on;

    ToggleView(Context context, int onColor) {
        super(context);
        this.onColor = onColor;
        setClickable(false);
    }

    void setOn(boolean value) {
        if (on != value) {
            on = value;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        paint.setColor(on ? onColor : Color.rgb(203, 210, 217));
        canvas.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, paint);
        float pad = h * 0.12f;
        float r = h / 2 - pad;
        paint.setColor(Color.WHITE);
        canvas.drawCircle(on ? w - pad - r : pad + r, h / 2, r, paint);
    }
}
