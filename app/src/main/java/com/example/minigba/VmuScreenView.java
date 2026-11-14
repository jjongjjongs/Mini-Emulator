package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Dreamcast, VMU 화면 (0.0.367): the 48x32 screen of the memory card in controller port A,
 * drawn by the app outside the game's picture. GameActivity hands it the dots from the
 * engine a few times a second. It is a layout item (moved and resized in 버튼 · 화면 배치
 * 편집); while it has nothing to show it draws nothing, but stays in the layout so the
 * editor still finds it, as an outline.
 */
final class VmuScreenView extends View {
    static final int W = 48;
    static final int H = 32;

    // Clear LCD, lit dot: 원래 VMU (the grey-green LCD), 흰 바탕, 반전.
    private static final int[][] COLORS = {
            {Color.rgb(170, 196, 160), Color.rgb(30, 46, 38)},
            {Color.rgb(248, 248, 246), Color.rgb(24, 24, 27)},
            {Color.rgb(24, 32, 30), Color.rgb(170, 230, 180)},
    };

    private final byte[] dots = new byte[W * H];
    private final Paint fill = new Paint();
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int lit;
    private boolean always;
    private boolean editing;
    private int colors;

    VmuScreenView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        this.frame.setStyle(Paint.Style.STROKE);
        this.frame.setStrokeWidth(2f * density);
        this.label.setTextAlign(Paint.Align.CENTER);
        this.label.setTextSize(11f * density);
        this.label.setFakeBoldText(true);
        setContentDescription("VMU 화면");
    }

    /** Shown even while the game has drawn nothing (늘), or only once it has (그릴 때만). */
    void setAlways(boolean always) {
        this.always = always;
        invalidate();
    }

    void setColors(int colors) {
        this.colors = colors >= 0 && colors < COLORS.length ? colors : 0;
        invalidate();
    }

    /** In the layout editor it is outlined and labelled, whatever the game shows. */
    void setEditing(boolean editing) {
        this.editing = editing;
        invalidate();
    }

    /** New dots from the engine (1 lit); redrawn only when they changed. */
    void setDots(byte[] next, int litCount) {
        if (litCount == this.lit && java.util.Arrays.equals(next, this.dots)) {
            return;
        }
        System.arraycopy(next, 0, this.dots, 0, this.dots.length);
        this.lit = litCount;
        invalidate();
    }

    /** Whether anything is drawn right now (for the "그릴 때만" choice). */
    boolean showing() {
        return this.editing || this.always || this.lit > 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!showing() || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        // The editor can stretch the view on one axis; the LCD keeps its 3:2 shape inside.
        float sx = getScaleX() == 0 ? 1f : getScaleX();
        float sy = getScaleY() == 0 ? 1f : getScaleY();
        float shownW = getWidth() * sx;
        float shownH = getHeight() * sy;
        float lcdW = Math.min(shownW, shownH * W / H);
        float lcdH = lcdW * H / W;
        float left = (shownW - lcdW) / 2f / sx;
        float top = (shownH - lcdH) / 2f / sy;
        float width = lcdW / sx;
        float height = lcdH / sy;
        int[] pair = COLORS[this.colors];
        float density = getResources().getDisplayMetrics().density;
        float radius = 4f * density;
        this.box.set(left, top, left + width, top + height);
        this.fill.setColor(pair[0]);
        canvas.drawRoundRect(this.box, radius / sx, radius / sy, this.fill);
        float inset = Math.min(width, height) * 0.06f;
        float cellW = (width - inset * 2) / W;
        float cellH = (height - inset * 2) / H;
        this.fill.setColor(pair[1]);
        for (int y = 0; y < H; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                if (this.dots[row + x] != 0) {
                    float dx = left + inset + x * cellW;
                    float dy = top + inset + y * cellH;
                    canvas.drawRect(dx, dy, dx + cellW, dy + cellH, this.fill);
                }
            }
        }
        if (this.editing) {
            this.frame.setColor(Color.rgb(34, 211, 238));
            this.frame.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0));
            canvas.drawRoundRect(this.box, radius / sx, radius / sy, this.frame);
            if (this.lit == 0) {
                this.label.setColor(pair[1]);
                canvas.save();
                canvas.scale(1f / sx, 1f / sy, left + width / 2f, top + height / 2f);
                canvas.drawText("VMU", left + width / 2f, top + height / 2f + this.label.getTextSize() / 3f, this.label);
                canvas.restore();
            }
        } else {
            this.frame.setPathEffect(null);
            this.frame.setColor(Color.argb(160, 156, 163, 175));
            canvas.drawRoundRect(this.box, radius / sx, radius / sy, this.frame);
        }
    }
}
