package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

// Drawn over the game screen, one line per game line: scanlines (2), an LCD grid (3), and
// the presets of 0.0.360 — CRT (5: scanlines and an aperture grille), curved CRT (6: the
// same, rounded corners and darker edges), handheld LCD (7: a fine grid), dot matrix (8:
// the Game Boy's dots). It covers its parent and draws only inside the target view's
// bounds; touches pass through. What the frame itself needs (brightness, colours, Scale2x)
// is done by the presenter (screenfx).
final class ScreenFilterView extends View {
    static final int SCANLINES = 2;
    static final int LCD_GRID = 3;
    static final int CRT = 5;
    static final int CRT_CURVED = 6;
    static final int HANDHELD = 7;
    static final int DOT_MATRIX = 8;

    private final View target;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int filter;
    private int gameWidth;
    private int gameHeight;
    private int strength = 70;
    private int curve = 50;

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

    void setLook(int strength, int curve) {
        if (this.strength != strength || this.curve != curve) {
            this.strength = strength;
            this.curve = curve;
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

    // Whether the filter draws lines that follow the game's own pixels.
    static boolean drawsLines(int filter) {
        return filter == SCANLINES || filter == LCD_GRID || filter == CRT || filter == CRT_CURVED || filter == HANDHELD || filter == DOT_MATRIX;
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
        if (!drawsLines(this.filter) || this.target.getVisibility() != VISIBLE) {
            return;
        }
        draw(canvas, this.paint, this.target.getLeft(), this.target.getTop(), this.target.getWidth(), this.target.getHeight(),
                this.filter, this.gameWidth, this.gameHeight, this.strength, this.curve, getResources().getDisplayMetrics().density);
    }

    // The filter over a picture at left, top (width x height) of a game gameWidth x gameHeight.
    static void draw(Canvas canvas, Paint paint, float left, float top, float width, float height, int filter,
                     int gameWidth, int gameHeight, int strength, int curve, float density) {
        if (width <= 0 || height <= 0 || gameWidth <= 0 || gameHeight <= 0) {
            return;
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        float k = Math.max(0, Math.min(100, strength)) / 100.0f;
        int rows = lines(gameHeight, 300);
        int columns = lines(gameWidth, 400);
        float rowHeight = height / rows;
        float columnWidth = width / columns;
        switch (filter) {
            case SCANLINES: {
                if (rowHeight < 2.0f) {
                    return;
                }
                paint.setColor(Color.argb(95, 0, 0, 0));
                for (int y = 0; y < rows; y++) {
                    float lineTop = top + y * rowHeight;
                    canvas.drawRect(left, lineTop + rowHeight * 0.55f, left + width, lineTop + rowHeight, paint);
                }
                return;
            }
            case LCD_GRID:
                grid(canvas, paint, left, top, width, height, rows, columns, 0.18f, Color.argb(70, 0, 0, 0));
                return;
            case HANDHELD:
                grid(canvas, paint, left, top, width, height, rows, columns, 0.14f, Color.argb(Math.round(25 + 70 * k), 0, 0, 0));
                return;
            case DOT_MATRIX:
                grid(canvas, paint, left, top, width, height, rows, columns, 0.22f, Color.argb(Math.round(60 + 140 * k), 15, 40, 15));
                return;
            case CRT:
            case CRT_CURVED: {
                if (rowHeight >= 2.0f) {
                    // scanlines: the lower part of each game line darker
                    paint.setColor(Color.argb(Math.round(40 + 120 * k), 0, 0, 0));
                    for (int y = 0; y < rows; y++) {
                        float lineTop = top + y * rowHeight;
                        canvas.drawRect(left, lineTop + rowHeight * 0.58f, left + width, lineTop + rowHeight, paint);
                    }
                }
                // aperture grille: thin red, green and blue stripes, 3 screen pixels a set
                float stripe = Math.max(1.0f, density);
                int alpha = Math.round(10 + 34 * k);
                int[] tints = {Color.argb(alpha, 255, 40, 40), Color.argb(alpha, 40, 255, 40), Color.argb(alpha, 60, 60, 255)};
                int n = 0;
                for (float x = left; x < left + width; x += stripe, n++) {
                    paint.setColor(tints[n % 3]);
                    canvas.drawRect(x, top, Math.min(left + width, x + stripe), top + height, paint);
                }
                if (filter == CRT_CURVED) {
                    float c = Math.max(0, Math.min(100, curve)) / 100.0f;
                    // darker towards the edges
                    float radius = (float) Math.hypot(width, height) / 2.0f;
                    paint.setShader(new RadialGradient(left + width / 2, top + height / 2, radius,
                            new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(Math.round(60 + 150 * c), 0, 0, 0)},
                            new float[]{0.0f, 0.55f, 1.0f}, Shader.TileMode.CLAMP));
                    canvas.drawRect(left, top, left + width, top + height, paint);
                    paint.setShader(null);
                    // the tube's rounded corners
                    float corner = Math.min(width, height) * (0.04f + 0.12f * c);
                    Path outside = new Path();
                    outside.setFillType(Path.FillType.EVEN_ODD);
                    outside.addRect(left, top, left + width, top + height, Path.Direction.CW);
                    outside.addRoundRect(new RectF(left, top, left + width, top + height), corner, corner, Path.Direction.CW);
                    paint.setColor(Color.BLACK);
                    canvas.drawPath(outside, paint);
                }
                return;
            }
            default:
                break;
        }
    }

    private static void grid(Canvas canvas, Paint paint, float left, float top, float width, float height, int rows, int columns,
                             float part, int color) {
        float rowHeight = height / rows;
        float columnWidth = width / columns;
        if (rowHeight < 2.0f) {
            return;
        }
        float gapX = Math.max(1.0f, columnWidth * part);
        float gapY = Math.max(1.0f, rowHeight * part);
        paint.setColor(color);
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
