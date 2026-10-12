package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class TopIconButtonView extends View {
    static final int ICON_MENU = 2;
    static final int ICON_RESET = 1;
    static final int ICON_ROTATE = 3;
    static final int ICON_SAVE = 0;
    private final Paint fill;
    private final Paint icon;
    private final int iconType;
    private boolean pressed;
    private final Paint stroke;

    TopIconButtonView(Context context, int i) {
        super(context);
        Paint paint = new Paint(1);
        this.fill = paint;
        Paint paint2 = new Paint(1);
        this.stroke = paint2;
        Paint paint3 = new Paint(1);
        this.icon = paint3;
        this.iconType = i;
        paint.setStyle(Paint.Style.FILL);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeWidth(3.0f);
        paint2.setStrokeCap(Paint.Cap.ROUND);
        paint2.setStrokeJoin(Paint.Join.ROUND);
        paint3.setStyle(Paint.Style.STROKE);
        paint3.setStrokeWidth(4.0f);
        paint3.setStrokeCap(Paint.Cap.ROUND);
        paint3.setStrokeJoin(Paint.Join.ROUND);
        setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int i;
        int i2;
        int i3;
        super.onDraw(canvas);
        float min = Math.min(getWidth(), getHeight()) * 0.76f;
        float width = getWidth() / 2.0f;
        float height = getHeight() / 2.0f;
        float f = min / 2.0f;
        RectF rectF = new RectF(width - f, height - f, width + f, height + f);
        Paint paint = this.fill;
        if (this.pressed) {
            i = 192;
            i2 = 184;
            i3 = 196;
        } else {
            i = 219;
            i2 = 211;
            i3 = 222;
        }
        paint.setColor(Color.rgb(i3, i, i2));
        this.stroke.setColor(Color.argb(155, 112, 108, 102));
        float f2 = min * 0.28f;
        canvas.drawRoundRect(rectF, f2, f2, this.fill);
        canvas.drawRoundRect(rectF, f2, f2, this.stroke);
        this.icon.setColor(Color.rgb(42, 41, 101));
        int i4 = this.iconType;
        if (i4 == 0) {
            drawSave(canvas, rectF);
            return;
        }
        if (i4 == 1) {
            drawReset(canvas, rectF);
        } else if (i4 == 2) {
            drawMenu(canvas, rectF);
        } else {
            drawRotate(canvas, rectF);
        }
    }

    private void drawSave(Canvas canvas, RectF rectF) {
        float width = rectF.left + (rectF.width() * 0.25f);
        float height = rectF.top + (rectF.height() * 0.2f);
        float width2 = rectF.right - (rectF.width() * 0.25f);
        float height2 = rectF.bottom - (rectF.height() * 0.18f);
        canvas.drawRoundRect(new RectF(width, height, width2, height2), 4.0f, 4.0f, this.icon);
        canvas.drawLine(width + (rectF.width() * 0.12f), height, width2 - (rectF.width() * 0.1f), height, this.icon);
        canvas.drawLine((rectF.width() * 0.13f) + width, height2, width2 - (rectF.width() * 0.13f), height2, this.icon);
        canvas.drawLine(width + (rectF.width() * 0.12f), height + (rectF.height() * 0.2f), width2 - (rectF.width() * 0.12f), height + (rectF.height() * 0.2f), this.icon);
        canvas.drawLine(width2 - (rectF.width() * 0.14f), height, width2 - (rectF.width() * 0.14f), height + (rectF.height() * 0.18f), this.icon);
    }

    private void drawReset(Canvas canvas, RectF rectF) {
        canvas.drawArc(new RectF(rectF.left + (rectF.width() * 0.26f), rectF.top + (rectF.height() * 0.24f), rectF.right - (rectF.width() * 0.23f), rectF.bottom - (rectF.height() * 0.24f)), 35.0f, 290.0f, false, this.icon);
        Path path = new Path();
        float width = rectF.left + (rectF.width() * 0.65f);
        float height = rectF.top + (rectF.height() * 0.24f);
        path.moveTo(width, height);
        path.lineTo((rectF.width() * 0.14f) + width, height - (rectF.height() * 0.02f));
        path.lineTo(width + (rectF.width() * 0.04f), height + (rectF.height() * 0.13f));
        canvas.drawPath(path, this.icon);
    }

    private void drawMenu(Canvas canvas, RectF rectF) {
        float width = rectF.left + (rectF.width() * 0.25f);
        float width2 = rectF.right - (rectF.width() * 0.25f);
        float height = rectF.top + (rectF.height() * 0.33f);
        float height2 = rectF.height() * 0.17f;
        canvas.drawLine(width, height, width2, height, this.icon);
        float f = height + height2;
        canvas.drawLine(width, f, width2, f, this.icon);
        float f2 = height + (height2 * 2.0f);
        canvas.drawLine(width, f2, width2, f2, this.icon);
    }

    private void drawRotate(Canvas canvas, RectF rectF) {
        canvas.drawRoundRect(new RectF(rectF.left + (rectF.width() * 0.28f), rectF.top + (rectF.height() * 0.3f), rectF.right - (rectF.width() * 0.28f), rectF.bottom - (rectF.height() * 0.2f)), rectF.width() * 0.08f, rectF.width() * 0.08f, this.icon);
        canvas.drawArc(new RectF(rectF.left + (rectF.width() * 0.17f), rectF.top + (rectF.height() * 0.13f), rectF.right - (rectF.width() * 0.15f), rectF.bottom - (rectF.height() * 0.13f)), 195.0f, 185.0f, false, this.icon);
        Path path = new Path();
        float width = rectF.right - (rectF.width() * 0.16f);
        float height = rectF.top + (rectF.height() * 0.42f);
        path.moveTo(width, height);
        path.lineTo(width - (rectF.width() * 0.15f), height - (rectF.height() * 0.02f));
        path.lineTo(width - (rectF.width() * 0.04f), height + (rectF.height() * 0.12f));
        canvas.drawPath(path, this.icon);
    }

    @Override
    public boolean onTouchEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            this.pressed = true;
            invalidate();
            return true;
        }
        if (actionMasked == 1) {
            this.pressed = false;
            invalidate();
            performClick();
            return true;
        }
        if (actionMasked == 3) {
            this.pressed = false;
            invalidate();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
