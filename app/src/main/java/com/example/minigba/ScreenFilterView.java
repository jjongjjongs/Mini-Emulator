package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

// Drawn over the game screen: scanlines (2) or an LCD grid (3), one line per game line.
// It covers its parent and draws only inside the target view's bounds; touches pass through.
final class ScreenFilterView extends View {
    private final View target;
    private final Paint paint = new Paint();
    private int filter;
    private int gameWidth;
    private int gameHeight;

    ScreenFilterView(Context context, View target) {
        super(context);
        this.target = target;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        target.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> invalidate());
    }

    void setFilter(int value) {
        if (this.filter != value) {
            this.filter = value;
            invalidate();
        }
    }

    void setGameSize(int width, int height) {
        if (this.gameWidth != width || this.gameHeight != height) {
            this.gameWidth = width;
            this.gameHeight = height;
            invalidate();
        }
    }

    // High-resolution 3D output keeps the look of the game's own lines, not every rendered row.
    private static int lines(int count, int limit) {
        int result = count;
        for (int divisor = 2; result > limit && divisor <= 8; divisor++) {
            result = count / divisor;
        }
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (this.filter < 2 || this.gameWidth <= 0 || this.gameHeight <= 0 || this.target.getVisibility() != VISIBLE) {
            return;
        }
        float left = this.target.getLeft();
        float top = this.target.getTop();
        float width = this.target.getWidth();
        float height = this.target.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int rows = lines(this.gameHeight, 300);
        float rowHeight = height / rows;
        if (rowHeight < 2.0f) {
            return;
        }
        if (this.filter == 2) {
            paint.setColor(Color.argb(95, 0, 0, 0));
            for (int y = 0; y < rows; y++) {
                float lineTop = top + y * rowHeight;
                canvas.drawRect(left, lineTop + rowHeight * 0.55f, left + width, lineTop + rowHeight, paint);
            }
            return;
        }
        int columns = lines(this.gameWidth, 400);
        float columnWidth = width / columns;
        float gapX = Math.max(1.0f, columnWidth * 0.18f);
        float gapY = Math.max(1.0f, rowHeight * 0.18f);
        paint.setColor(Color.argb(70, 0, 0, 0));
        for (int y = 0; y < rows; y++) {
            float lineTop = top + (y + 1) * rowHeight - gapY;
            canvas.drawRect(left, lineTop, left + width, lineTop + gapY, paint);
        }
        if (columnWidth >= 2.0f) {
            for (int x = 0; x < columns; x++) {
                float lineLeft = left + (x + 1) * columnWidth - gapX;
                canvas.drawRect(lineLeft, top, lineLeft + gapX, top + height, paint);
            }
        }
    }
}
