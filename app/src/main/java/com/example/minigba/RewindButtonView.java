package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

// The 되감기 button: the game runs backwards while it is held. Drawn like the speed button.
final class RewindButtonView extends View {
    interface Listener {
        void onHeld(boolean held);
    }

    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean held;

    RewindButtonView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4.0f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        setFocusable(false);
        setContentDescription("되감기");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float radius = h * 0.4f;
        fill.setColor(Color.argb(130, 58, 54, 48));
        canvas.drawRoundRect(new RectF(4, 8, w - 4, h - 2), radius, radius, fill);
        RectF face = new RectF(4, held ? 7 : 4, w - 4, h - (held ? 4 : 7));
        fill.setColor(held ? Color.rgb(91, 33, 182) : Color.rgb(76, 29, 149));
        canvas.drawRoundRect(face, radius, radius, fill);
        stroke.setColor(Color.rgb(39, 38, 40));
        canvas.drawRoundRect(face, radius, radius, stroke);
        // Two triangles pointing left, then the label.
        float cy = face.centerY();
        float size = h * 0.17f;
        float x = w * 0.2f;
        fill.setColor(Color.rgb(221, 214, 254));
        path.reset();
        path.moveTo(x - size, cy);
        path.lineTo(x + size * 0.2f, cy - size);
        path.lineTo(x + size * 0.2f, cy + size);
        path.close();
        path.moveTo(x + size * 0.1f, cy);
        path.lineTo(x + size * 1.3f, cy - size);
        path.lineTo(x + size * 1.3f, cy + size);
        path.close();
        canvas.drawPath(path, fill);
        text.setColor(Color.rgb(237, 233, 254));
        text.setTextSize(h * 0.3f);
        Paint.FontMetrics metrics = text.getFontMetrics();
        canvas.drawText("되감기", w * 0.62f, cy - (metrics.ascent + metrics.descent) / 2.0f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            setHeld(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setHeld(false);
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        setHeld(false);
        super.onDetachedFromWindow();
    }

    private void setHeld(boolean value) {
        if (held == value) {
            return;
        }
        held = value;
        if (value) {
            Haptics.tap();
        }
        listener.onHeld(value);
        invalidate();
    }
}
