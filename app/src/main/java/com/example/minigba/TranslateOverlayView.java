package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

// Korean text boxes drawn over the game screen, where the original text was.
final class TranslateOverlayView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private List<ScreenTranslator.Item> items = new ArrayList<>();
    private int sourceWidth = 1;
    private int sourceHeight = 1;
    private boolean dim;

    TranslateOverlayView(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        fill.setColor(Color.argb(235, 17, 24, 39));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.5f * density);
        border.setColor(RoundIconView.COLOR_TRANSLATE);
        label.setColor(Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // items in the pixels of a sourceWidth x sourceHeight frame; dim darkens the frame behind.
    void show(List<ScreenTranslator.Item> items, int sourceWidth, int sourceHeight, boolean dim) {
        this.items = items == null ? new ArrayList<>() : items;
        this.sourceWidth = Math.max(1, sourceWidth);
        this.sourceHeight = Math.max(1, sourceHeight);
        this.dim = dim;
        setVisibility(VISIBLE);
        invalidate();
    }

    void clear() {
        this.items = new ArrayList<>();
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (this.dim) {
            canvas.drawColor(Color.argb(64, 0, 0, 0));
        }
        float sx = getWidth() / (float) this.sourceWidth;
        float sy = getHeight() / (float) this.sourceHeight;
        float pad = 3 * density;
        float radius = 5 * density;
        for (ScreenTranslator.Item item : this.items) {
            RectF box = new RectF(item.box.left * sx - pad, item.box.top * sy - pad, item.box.right * sx + pad, item.box.bottom * sy + pad);
            box.left = Math.max(0, box.left);
            box.right = Math.min(getWidth(), Math.max(box.right, box.left + 40 * density));
            int width = Math.max(1, Math.round(box.width() - 2 * pad));
            // Largest size, up to 15 sp, that fits the original box; the box grows if 9 sp won't.
            float size = Math.min(15, Math.max(9, box.height() / density * 0.8f));
            StaticLayout layout;
            while (true) {
                label.setTextSize(size * density);
                layout = new StaticLayout(item.text, label, width, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0, false);
                if (layout.getHeight() + 2 * pad <= box.height() || size <= 9) {
                    break;
                }
                size -= 1;
            }
            box.bottom = Math.max(box.bottom, box.top + layout.getHeight() + 2 * pad);
            if (box.bottom > getHeight()) {
                box.offset(0, Math.max(-box.top, getHeight() - box.bottom));
            }
            canvas.drawRoundRect(box, radius, radius, fill);
            canvas.drawRoundRect(box, radius, radius, border);
            canvas.save();
            canvas.translate(box.left + pad, box.top + pad);
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
