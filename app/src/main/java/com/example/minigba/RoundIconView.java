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
    static final int STAR = 8;
    static final int EDIT = 9;
    static final int GEAR = 10;
    static final int EXPORT = 11;
    static final int IMPORT = 12;
    static final int ERASE = 13;
    static final int TRASH = 14;
    static final int MULTI = 15;
    static final int FOLDER = 16;
    static final int GLOBE = 17;
    static final int HD = 18;
    static final int COMBO = 19;
    static final int FILTER = 20;
    static final int VIBRATE = 21;

    static final int COLOR_SAVE = Color.rgb(47, 128, 237);
    static final int COLOR_RESET = Color.rgb(242, 153, 74);
    static final int COLOR_ROTATE = Color.rgb(39, 174, 96);
    static final int COLOR_MENU = Color.rgb(79, 85, 98);
    static final int COLOR_LOAD = Color.rgb(124, 92, 255);
    static final int COLOR_CARD = Color.rgb(14, 165, 164);
    static final int COLOR_EXIT = Color.rgb(229, 72, 77);
    static final int COLOR_STAR = Color.rgb(245, 166, 35);
    static final int COLOR_EDIT = Color.rgb(91, 108, 240);
    static final int COLOR_GEAR = Color.rgb(245, 158, 11);
    static final int COLOR_GLOBE = Color.rgb(99, 102, 241);
    static final int COLOR_HD = Color.rgb(14, 165, 233);
    static final int COLOR_COMBO = Color.rgb(245, 158, 11);
    static final int COLOR_FILTER = Color.rgb(14, 165, 233);
    static final int COLOR_VIBRATE = Color.rgb(139, 92, 246);

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
            case STAR:
                path.moveTo(12, 3.5f);
                path.lineTo(14.6f, 8.8f);
                path.lineTo(20.5f, 9.7f);
                path.lineTo(16.2f, 13.8f);
                path.lineTo(17.2f, 19.6f);
                path.lineTo(12, 16.9f);
                path.lineTo(6.8f, 19.6f);
                path.lineTo(7.8f, 13.8f);
                path.lineTo(3.5f, 9.7f);
                path.lineTo(9.4f, 8.8f);
                path.close();
                canvas.drawPath(path, line);
                break;
            case EDIT:
                path.moveTo(4, 20);
                path.lineTo(8, 20);
                path.lineTo(19, 9);
                path.lineTo(15, 5);
                path.lineTo(4, 16);
                path.close();
                path.moveTo(13.5f, 6.5f);
                path.lineTo(17.5f, 10.5f);
                canvas.drawPath(path, line);
                break;
            case GEAR:
                path.addCircle(12, 12, 3.2f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI / 4 * i;
                    float cos = (float) Math.cos(angle);
                    float sin = (float) Math.sin(angle);
                    path.moveTo(12 + cos * 6.6f, 12 + sin * 6.6f);
                    path.lineTo(12 + cos * 9.2f, 12 + sin * 9.2f);
                }
                canvas.drawPath(path, line);
                break;
            case EXPORT:
            case IMPORT:
                if (icon == EXPORT) {
                    path.moveTo(12, 15);
                    path.lineTo(12, 4);
                    path.moveTo(7.5f, 8.5f);
                    path.lineTo(12, 4);
                    path.lineTo(16.5f, 8.5f);
                } else {
                    path.moveTo(12, 4);
                    path.lineTo(12, 15);
                    path.moveTo(7.5f, 10.5f);
                    path.lineTo(12, 15);
                    path.lineTo(16.5f, 10.5f);
                }
                path.moveTo(4, 15);
                path.lineTo(4, 18);
                path.quadTo(4, 20, 6, 20);
                path.lineTo(18, 20);
                path.quadTo(20, 20, 20, 18);
                path.lineTo(20, 15);
                canvas.drawPath(path, line);
                break;
            case ERASE:
                floppy();
                path.moveTo(9, 12);
                path.lineTo(15, 18);
                path.moveTo(15, 12);
                path.lineTo(9, 18);
                canvas.drawPath(path, line);
                break;
            case TRASH:
                path.moveTo(4, 7);
                path.lineTo(20, 7);
                path.moveTo(9, 7);
                path.lineTo(9, 4);
                path.lineTo(15, 4);
                path.lineTo(15, 7);
                path.moveTo(6, 7);
                path.lineTo(7, 20);
                path.lineTo(17, 20);
                path.lineTo(18, 7);
                path.moveTo(10, 11);
                path.lineTo(10, 17);
                path.moveTo(14, 11);
                path.lineTo(14, 17);
                canvas.drawPath(path, line);
                break;
            case MULTI:
                for (float y : new float[]{6, 12, 18}) {
                    path.moveTo(4, y);
                    path.lineTo(5.5f, y + 1.5f);
                    path.lineTo(8, y - 1);
                    path.moveTo(11, y + 0.5f);
                    path.lineTo(20, y + 0.5f);
                }
                canvas.drawPath(path, line);
                break;
            case FOLDER:
                path.moveTo(3.5f, 7);
                path.quadTo(3.5f, 5, 5.5f, 5);
                path.lineTo(9.5f, 5);
                path.lineTo(11.5f, 7.5f);
                path.lineTo(18.5f, 7.5f);
                path.quadTo(20.5f, 7.5f, 20.5f, 9.5f);
                path.lineTo(20.5f, 17.5f);
                path.quadTo(20.5f, 19.5f, 18.5f, 19.5f);
                path.lineTo(5.5f, 19.5f);
                path.quadTo(3.5f, 19.5f, 3.5f, 17.5f);
                path.close();
                canvas.drawPath(path, line);
                break;
            case GLOBE:
                canvas.drawCircle(12, 12, 8.5f, line);
                path.moveTo(3.5f, 12);
                path.lineTo(20.5f, 12);
                path.moveTo(12, 3.5f);
                path.cubicTo(15.2f, 6.5f, 15.2f, 17.5f, 12, 20.5f);
                path.moveTo(12, 3.5f);
                path.cubicTo(8.8f, 6.5f, 8.8f, 17.5f, 12, 20.5f);
                canvas.drawPath(path, line);
                break;
            case COMBO:
                canvas.drawCircle(8.5f, 12, 5.5f, line);
                canvas.drawCircle(15.5f, 12, 5.5f, line);
                break;
            case FILTER:
                // A screen with scanlines across it.
                path.addRoundRect(new RectF(3, 4.5f, 21, 19.5f), 3, 3, Path.Direction.CW);
                path.moveTo(6.5f, 9.5f);
                path.lineTo(17.5f, 9.5f);
                path.moveTo(6.5f, 14.5f);
                path.lineTo(17.5f, 14.5f);
                canvas.drawPath(path, line);
                break;
            case VIBRATE:
                // A phone with buzz marks on both sides.
                path.addRoundRect(new RectF(8, 3.5f, 16, 20.5f), 2, 2, Path.Direction.CW);
                path.moveTo(4.5f, 8.5f);
                path.lineTo(4.5f, 15.5f);
                path.moveTo(19.5f, 8.5f);
                path.lineTo(19.5f, 15.5f);
                canvas.drawPath(path, line);
                break;
            case HD:
                path.addRoundRect(new RectF(2.5f, 5, 21.5f, 19), 3, 3, Path.Direction.CW);
                canvas.drawPath(path, line);
                Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
                text.setColor(line.getColor());
                text.setTextSize(8.5f);
                text.setFakeBoldText(true);
                text.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("HD", 12, 15.1f, text);
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
