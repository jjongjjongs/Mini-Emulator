package com.example.minigba;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

// A picture with a frame on it: a fixed shape (width / height) or free (ratio 0). The frame
// moves with a finger, grows and shrinks with two fingers or its corner handles (and, when
// free, its edge handles change width and height apart); crop() gives what is inside it.
final class CropView extends View {
    interface Listener {
        // size: the frame's share of the largest it can be (0-1); width × height in picture pixels.
        void onFrameChanged(float size, int width, int height);
    }

    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int CORNER = 2;
    private static final int EDGE = 3;

    private final Bitmap bitmap;
    private float ratio;
    private final RectF shown = new RectF();
    private final RectF frame = new RectF();
    private final Paint dim = new Paint();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private final float density;
    private Listener listener;
    private int dragging = NONE;
    private int which;          // corner 0-3 (tl, tr, bl, br) or edge 0-3 (top, bottom, left, right)
    private float lastX;
    private float lastY;

    CropView(Context context, Bitmap bitmap) {
        super(context);
        this.bitmap = bitmap;
        this.density = context.getResources().getDisplayMetrics().density;
        this.dim.setColor(Color.argb(140, 0, 0, 0));
        this.line.setColor(Color.WHITE);
        this.line.setStyle(Paint.Style.STROKE);
        this.line.setStrokeWidth(2.5f * this.density);
        this.line.setPathEffect(new DashPathEffect(new float[]{18, 10}, 0));
        this.handle.setColor(Color.WHITE);
        this.handleRing.setColor(Color.rgb(251, 146, 60));
        this.handleRing.setStyle(Paint.Style.STROKE);
        this.handleRing.setStrokeWidth(2.5f * this.density);
        this.pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleBy(detector.getScaleFactor());
                return true;
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
        report();
    }

    // ratio: width / height, or 0 for free.
    void setRatio(float ratio) {
        this.ratio = ratio;
        layoutFrame(1.0f);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float scale = Math.min((float) w / this.bitmap.getWidth(), (float) h / this.bitmap.getHeight());
        float bw = this.bitmap.getWidth() * scale;
        float bh = this.bitmap.getHeight() * scale;
        this.shown.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
        layoutFrame(1.0f);
    }

    // The largest frame of the shape that fits.
    private float[] fullSize() {
        float w = this.shown.width();
        float h = this.shown.height();
        if (this.ratio > 0) {
            if (w / h > this.ratio) {
                w = h * this.ratio;
            } else {
                h = w / this.ratio;
            }
        }
        return new float[]{w, h};
    }

    // The frame at size (0-1) of the largest, centered on where it is now (or the picture).
    private void layoutFrame(float size) {
        if (this.shown.isEmpty()) {
            return;
        }
        float[] full = fullSize();
        float cx = this.frame.isEmpty() ? this.shown.centerX() : this.frame.centerX();
        float cy = this.frame.isEmpty() ? this.shown.centerY() : this.frame.centerY();
        float w = full[0] * size;
        float h = full[1] * size;
        if (this.ratio <= 0 && !this.frame.isEmpty() && size < 1.0f) {
            // Free: keep the frame's own shape.
            float shape = this.frame.width() / Math.max(1, this.frame.height());
            h = (float) Math.sqrt(full[0] * full[1] * size * size / shape);
            w = h * shape;
        }
        this.frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        keepInside();
        report();
    }

    // The slider: size from 0.15 to 1 of the largest frame.
    void setSize(float size) {
        layoutFrame(Math.max(0.15f, Math.min(1.0f, size)));
        invalidate();
    }

    private float minSide() {
        return 48 * this.density;
    }

    private void scaleBy(float factor) {
        float[] full = fullSize();
        float w = this.frame.width() * factor;
        float h = this.frame.height() * factor;
        float limit = Math.min(full[0] / Math.max(1, w), full[1] / Math.max(1, h));
        if (this.ratio <= 0) {
            limit = Math.min(this.shown.width() / Math.max(1, w), this.shown.height() / Math.max(1, h));
        }
        if (limit < 1) {
            w *= limit;
            h *= limit;
        }
        if (Math.min(w, h) < minSide()) {
            return;
        }
        float cx = this.frame.centerX();
        float cy = this.frame.centerY();
        this.frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        keepInside();
        report();
        invalidate();
    }

    private void keepInside() {
        if (this.frame.width() > this.shown.width()) {
            float cx = this.frame.centerX();
            this.frame.left = cx - this.shown.width() / 2;
            this.frame.right = cx + this.shown.width() / 2;
        }
        if (this.frame.height() > this.shown.height()) {
            float cy = this.frame.centerY();
            this.frame.top = cy - this.shown.height() / 2;
            this.frame.bottom = cy + this.shown.height() / 2;
        }
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

    private void report() {
        if (this.listener == null || this.shown.isEmpty()) {
            return;
        }
        float[] full = fullSize();
        float size = (float) Math.sqrt((this.frame.width() * this.frame.height()) / Math.max(1, full[0] * full[1]));
        float scale = this.bitmap.getWidth() / this.shown.width();
        this.listener.onFrameChanged(Math.min(1, size), Math.round(this.frame.width() * scale), Math.round(this.frame.height() * scale));
    }

    private float[][] corners() {
        return new float[][]{{this.frame.left, this.frame.top}, {this.frame.right, this.frame.top}, {this.frame.left, this.frame.bottom}, {this.frame.right, this.frame.bottom}};
    }

    private float[][] edges() {
        return new float[][]{{this.frame.centerX(), this.frame.top}, {this.frame.centerX(), this.frame.bottom}, {this.frame.left, this.frame.centerY()}, {this.frame.right, this.frame.centerY()}};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawBitmap(this.bitmap, null, this.shown, this.picture);
        canvas.drawRect(this.shown.left, this.shown.top, this.shown.right, this.frame.top, this.dim);
        canvas.drawRect(this.shown.left, this.frame.bottom, this.shown.right, this.shown.bottom, this.dim);
        canvas.drawRect(this.shown.left, this.frame.top, this.frame.left, this.frame.bottom, this.dim);
        canvas.drawRect(this.frame.right, this.frame.top, this.shown.right, this.frame.bottom, this.dim);
        canvas.drawRect(this.frame, this.line);
        float r = 8 * this.density;
        for (float[] c : corners()) {
            canvas.drawCircle(c[0], c[1], r, this.handle);
            canvas.drawCircle(c[0], c[1], r, this.handleRing);
        }
        if (this.ratio <= 0) {
            float w = 10 * this.density;
            float h = 4 * this.density;
            float[][] e = edges();
            for (int k = 0; k < 4; k++) {
                RectF bar = k < 2 ? new RectF(e[k][0] - w, e[k][1] - h, e[k][0] + w, e[k][1] + h) : new RectF(e[k][0] - h, e[k][1] - w, e[k][0] + h, e[k][1] + w);
                canvas.drawRoundRect(bar, h, h, this.handle);
                canvas.drawRoundRect(bar, h, h, this.handleRing);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        this.pinch.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                this.lastX = x;
                this.lastY = y;
                float reach = 24 * this.density;
                this.dragging = NONE;
                float[][] c = corners();
                for (int k = 0; k < 4; k++) {
                    if (Math.hypot(x - c[k][0], y - c[k][1]) < reach) {
                        this.dragging = CORNER;
                        this.which = k;
                    }
                }
                if (this.dragging == NONE && this.ratio <= 0) {
                    float[][] e = edges();
                    for (int k = 0; k < 4; k++) {
                        if (Math.hypot(x - e[k][0], y - e[k][1]) < reach) {
                            this.dragging = EDGE;
                            this.which = k;
                        }
                    }
                }
                if (this.dragging == NONE) {
                    this.dragging = MOVE;
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                this.dragging = NONE;  // two fingers: the pinch takes over
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1 || this.dragging == NONE) {
                    return true;
                }
                if (this.dragging == MOVE) {
                    this.frame.offset(x - this.lastX, y - this.lastY);
                    keepInside();
                } else if (this.dragging == CORNER) {
                    dragCorner(x, y);
                } else {
                    dragEdge(x, y);
                }
                this.lastX = x;
                this.lastY = y;
                report();
                invalidate();
                return true;
            default:
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    this.dragging = NONE;
                }
                return true;
        }
    }

    // The corner follows the finger, the opposite corner stays; a fixed shape keeps its ratio.
    private void dragCorner(float x, float y) {
        float ax = this.which == 0 || this.which == 2 ? this.frame.right : this.frame.left;
        float ay = this.which == 0 || this.which == 1 ? this.frame.bottom : this.frame.top;
        x = Math.max(this.shown.left, Math.min(this.shown.right, x));
        y = Math.max(this.shown.top, Math.min(this.shown.bottom, y));
        float w = Math.max(minSide(), Math.abs(x - ax));
        float h = Math.max(minSide(), Math.abs(y - ay));
        if (this.ratio > 0) {
            if (w / h > this.ratio) {
                h = w / this.ratio;
            } else {
                w = h * this.ratio;
            }
            // Within the picture from the fixed corner.
            float maxW = this.which == 0 || this.which == 2 ? ax - this.shown.left : this.shown.right - ax;
            float maxH = this.which == 0 || this.which == 1 ? ay - this.shown.top : this.shown.bottom - ay;
            float fit = Math.min(1, Math.min(maxW / w, maxH / h));
            w *= fit;
            h *= fit;
        }
        float left = this.which == 0 || this.which == 2 ? ax - w : ax;
        float top = this.which == 0 || this.which == 1 ? ay - h : ay;
        this.frame.set(left, top, left + w, top + h);
        keepInside();
    }

    private void dragEdge(float x, float y) {
        float min = minSide();
        switch (this.which) {
            case 0:
                this.frame.top = Math.max(this.shown.top, Math.min(this.frame.bottom - min, y));
                break;
            case 1:
                this.frame.bottom = Math.min(this.shown.bottom, Math.max(this.frame.top + min, y));
                break;
            case 2:
                this.frame.left = Math.max(this.shown.left, Math.min(this.frame.right - min, x));
                break;
            default:
                this.frame.right = Math.min(this.shown.right, Math.max(this.frame.left + min, x));
                break;
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
