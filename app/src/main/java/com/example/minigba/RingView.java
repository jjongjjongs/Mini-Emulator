package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

// Ring chart for the ROM check: green for good ROMs, amber for ones to check, grey for
// duplicates, with the good count in the middle.
final class RingView extends View {
    static final int GREEN = Color.rgb(34, 179, 90);
    static final int AMBER = Color.rgb(245, 158, 11);
    static final int GREY = Color.rgb(154, 161, 170);

    private final int[] values;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String big = "";
    private String small = "";

    RingView(Context context, int good, int warn, int duplicates) {
        super(context);
        this.values = new int[]{good, warn, duplicates};
        ring.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setCenter(String big, String small) {
        this.big = big;
        this.small = small;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float stroke = size * 0.12f;
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        RectF box = new RectF(cx - size / 2 + stroke / 2, cy - size / 2 + stroke / 2, cx + size / 2 - stroke / 2, cy + size / 2 - stroke / 2);
        ring.setStrokeWidth(stroke);
        int total = values[0] + values[1] + values[2];
        ring.setColor(Color.rgb(229, 231, 235));
        canvas.drawArc(box, 0, 360, false, ring);
        int[] colors = {GREEN, AMBER, GREY};
        float start = -90;
        for (int i = 0; i < values.length && total > 0; i++) {
            float sweep = 360.0f * values[i] / total;
            if (sweep > 0) {
                ring.setColor(colors[i]);
                canvas.drawArc(box, start, sweep, false, ring);
            }
            start += sweep;
        }
        text.setColor(Color.rgb(31, 41, 51));
        text.setFakeBoldText(true);
        text.setTextSize(size * 0.24f);
        canvas.drawText(big, cx, cy + size * 0.04f, text);
        text.setFakeBoldText(false);
        text.setColor(Color.rgb(122, 130, 140));
        text.setTextSize(size * 0.11f);
        canvas.drawText(small, cx, cy + size * 0.2f, text);
    }
}
