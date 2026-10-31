package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Wii U, landscape: where the GamePad picture sits over the TV picture. The core draws the
 * picture there (GameActivity hands it this view's rectangle); this view only outlines it
 * and passes touches on it to the game as the GamePad's touch screen. The layout editor
 * moves and resizes it like a button.
 */
final class WiiuPadWindowView extends View {
    interface TouchListener {
        /** x, y in this view's own (unscaled) coordinates. */
        void onTouch(WiiuPadWindowView view, float x, float y, boolean pressed);
    }

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final TouchListener listener;

    WiiuPadWindowView(Context context, TouchListener listener) {
        super(context);
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        this.outline.setStyle(Paint.Style.STROKE);
        this.outline.setStrokeWidth(1.5f * density);
        this.outline.setColor(Color.argb(150, 125, 211, 252));
        setContentDescription("게임패드 화면");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = this.outline.getStrokeWidth() / 2f;
        this.box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = 4f * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(this.box, radius, radius, this.outline);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        boolean pressed = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        float x = Math.max(0f, Math.min(getWidth(), event.getX()));
        float y = Math.max(0f, Math.min(getHeight(), event.getY()));
        this.listener.onTouch(this, x, y, pressed);
        return true;
    }
}
