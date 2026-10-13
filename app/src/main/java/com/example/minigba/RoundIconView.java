package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

// Coloured round button with a white line icon, used for the game screen's
// save/reset/rotate/menu buttons and the pause menu tiles. Icons are drawn on
// a 24-unit grid scaled to the circle.
final class RoundIconView extends View {
    static final int SAVE = 0;
    static final int RESET = 1;
    static final int ROTATE = 2;
    static final int MENU = 3;
    static final int LOAD = 4;
    static final int CARD = 5;
    static final int EXIT = 6;
    static final int PLAY = 7;

    static final int COLOR_SAVE = Color.rgb(47, 128, 237);
    static final int COLOR_RESET = Color.rgb(242, 153, 74);
    static final int COLOR_ROTATE = Color.rgb(39, 174, 96);
    static final int COLOR_MENU = Color.rgb(79, 85, 98);
    static final int COLOR_LOAD = Color.rgb(124, 92, 255);
    static final int COLOR_CARD = Color.rgb(14, 165, 164);
    static final int COLOR_EXIT = Color.rgb(229, 72, 77);

    private final int icon;
    private final int color;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean pressedLook;

    RoundIconView(Context context, int icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void setPressedLook(boolean pressed) {
        if (pressedLook != pressed) {
            pressedLook = pressed;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float radius = size / 2.0f - size * 0.04f;
        // A slightly darker rim below the circle gives it a pressable edge.
        fill.setColor(Color.argb(60, 0, 0, 0));
        canvas.drawCircle(cx, cy + size * 0.04f, radius, fill);
        fill.setColor(pressedLook ? darker(color) : color);
        canvas.drawCircle(cx, cy, radius, fill);

        float unit = size * 0.55f / 24.0f;
        canvas.save();
        canvas.translate(cx - 12 * unit, cy - 12 * unit);
        canvas.scale(unit, unit);
        line.setStrokeWidth(2.1f);
        drawIcon(canvas);
        canvas.restore();
    }

    private void drawIcon(Canvas canvas) {
        path.reset();
        switch (icon) {
            case SAVE:
            case LOAD:
                floppy();
                canvas.drawPath(path, line);
                path.reset();
                // Arrow into the disk for save, out of it for load.
                if (icon == SAVE) {
                    arrow(12, 11.5f, 12, 18.5f);
                } else {
                    arrow(12, 18.5f, 12, 11.5f);
                }
                canvas.drawPath(path, line);
                break;
            case CARD:
                path.addRoundRect(new RectF(4.5f, 3, 19.5f, 21), 2, 2, Path.Direction.CW);
                path.moveTo(8.5f, 3);
                path.lineTo(8.5f, 7.5f);
                path.moveTo(12, 3);
                path.lineTo(12, 7.5f);
                path.moveTo(15.5f, 3);
                path.lineTo(15.5f, 7.5f);
                path.moveTo(8.5f, 15);
                path.lineTo(15.5f, 15);
                canvas.drawPath(path, line);
                break;
            case RESET:
                path.addArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), 215, 285);
                canvas.drawPath(path, line);
                path.reset();
                path.moveTo(3.5f, 4.5f);
                path.lineTo(3.8f, 10);
                path.lineTo(9.2f, 9.4f);
                canvas.drawPath(path, line);
                break;
            case ROTATE:
                canvas.save();
                canvas.rotate(-35, 12, 11);
                path.addRoundRect(new RectF(7.5f, 3, 16.5f, 19), 2, 2, Path.Direction.CW);
                canvas.drawPath(path, line);
                canvas.restore();
                path.reset();
                path.moveTo(3, 15);
                path.quadTo(4, 20, 9, 21.5f);
                path.moveTo(6.6f, 22.2f);
                path.lineTo(9.3f, 21.6f);
                path.lineTo(8.6f, 18.9f);
                canvas.drawPath(path, line);
                break;
            case MENU:
                for (float y : new float[]{6.5f, 12, 17.5f}) {
                    path.moveTo(4.5f, y);
                    path.lineTo(19.5f, y);
                }
                line.setStrokeWidth(2.4f);
                canvas.drawPath(path, line);
                break;
            case EXIT:
                path.moveTo(14, 4);
                path.lineTo(18, 4);
                path.quadTo(20, 4, 20, 6);
                path.lineTo(20, 18);
                path.quadTo(20, 20, 18, 20);
                path.lineTo(14, 20);
                path.moveTo(15.5f, 12);
                path.lineTo(5, 12);
                path.moveTo(9, 8);
                path.lineTo(5, 12);
                path.lineTo(9, 16);
                canvas.drawPath(path, line);
                break;
            case PLAY:
            default:
                path.moveTo(8, 5);
                path.lineTo(19, 12);
                path.lineTo(8, 19);
                path.close();
                Paint solid = new Paint(line);
                solid.setStyle(Paint.Style.FILL_AND_STROKE);
                canvas.drawPath(path, solid);
                break;
        }
    }

    private void floppy() {
        path.moveTo(5, 3);
        path.lineTo(16, 3);
        path.lineTo(19.5f, 6.5f);
        path.lineTo(19.5f, 19);
        path.quadTo(19.5f, 21, 17.5f, 21);
        path.lineTo(6.5f, 21);
        path.quadTo(4.5f, 21, 4.5f, 19);
        path.lineTo(4.5f, 5);
        path.quadTo(4.5f, 3, 6.5f, 3);
        path.moveTo(8, 3);
        path.lineTo(8, 7.5f);
        path.lineTo(15, 7.5f);
        path.lineTo(15, 3);
    }

    private void arrow(float fromX, float fromY, float toX, float toY) {
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        float dir = toY > fromY ? -1 : 1;
        path.moveTo(toX - 3, toY + 3 * dir);
        path.lineTo(toX, toY);
        path.lineTo(toX + 3, toY + 3 * dir);
    }

    private static int darker(int color) {
        return Color.rgb(Color.red(color) * 4 / 5, Color.green(color) * 4 / 5, Color.blue(color) * 4 / 5);
    }
}
