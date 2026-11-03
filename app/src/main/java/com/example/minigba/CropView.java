package com.example.minigba;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

// A picture with a frame of a chosen shape on it (width / height, 0 for the whole picture):
// the frame is as big as fits and is moved with a finger; crop() gives what is inside it.
final class CropView extends View {
    private final Bitmap bitmap;
    private float ratio;
    private final RectF shown = new RectF();
    private final RectF frame = new RectF();
    private final Paint dim = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float lastX;
    private float lastY;

    CropView(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        this.dim.setColor(Color.argb(140, 0, 0, 0));
        this.line.setColor(Color.WHITE);
        this.line.setStyle(Paint.Style.STROKE);
        this.line.setStrokeWidth(3 * context.getResources().getDisplayMetrics().density);
        this.line.setPathEffect(new DashPathEffect(new float[]{18, 10}, 0));
    }

    void setRatio(float ratio) {
        this.ratio = ratio;
        layoutFrame(true);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float scale = Math.min((float) w / this.bitmap.getWidth(), (float) h / this.bitmap.getHeight());
        float bw = this.bitmap.getWidth() * scale;
        float bh = this.bitmap.getHeight() * scale;
        this.shown.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        layoutFrame(true);
    }

    private void layoutFrame(boolean center) {
        if (this.shown.isEmpty()) {
            return;
        }
        float w = this.shown.width();
        float h = this.shown.height();
        if (this.ratio > 0) {
            if (w / h > this.ratio) {
                w = h * this.ratio;
            } else {
                h = w / this.ratio;
            }
        }
        float cx = center ? this.shown.centerX() : this.frame.centerX();
        float cy = center ? this.shown.centerY() : this.frame.centerY();
        this.frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        keepInside();
    }

    private void keepInside() {
        float dx = 0;
        float dy = 0;
        if (this.frame.left < this.shown.left) {
            dx = this.shown.left - this.frame.left;
        } else if (this.frame.right > this.shown.right) {
            dx = this.shown.right - this.frame.right;
        }
        if (this.frame.top < this.shown.top) {
            dy = this.shown.top - this.frame.top;
        } else if (this.frame.bottom > this.shown.bottom) {
            dy = this.shown.bottom - this.frame.bottom;
        }
        this.frame.offset(dx, dy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawBitmap(this.bitmap, null, this.shown, this.picture);
        canvas.drawRect(this.shown.left, this.shown.top, this.shown.right, this.frame.top, this.dim);
        canvas.drawRect(this.shown.left, this.frame.bottom, this.shown.right, this.shown.bottom, this.dim);
        canvas.drawRect(this.shown.left, this.frame.top, this.frame.left, this.frame.bottom, this.dim);
        canvas.drawRect(this.frame.right, this.frame.top, this.shown.right, this.frame.bottom, this.dim);
        canvas.drawRect(this.frame, this.line);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                this.lastX = event.getX();
                this.lastY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                this.frame.offset(event.getX() - this.lastX, event.getY() - this.lastY);
                keepInside();
                this.lastX = event.getX();
                this.lastY = event.getY();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    Bitmap crop() {
        float scale = this.bitmap.getWidth() / this.shown.width();
        int x = Math.max(0, Math.round((this.frame.left - this.shown.left) * scale));
        int y = Math.max(0, Math.round((this.frame.top - this.shown.top) * scale));
        int w = Math.min(this.bitmap.getWidth() - x, Math.round(this.frame.width() * scale));
        int h = Math.min(this.bitmap.getHeight() - y, Math.round(this.frame.height() * scale));
        if (w <= 0 || h <= 0) {
            return this.bitmap;
        }
        return Bitmap.createBitmap(this.bitmap, x, y, w, h);
    }
}
