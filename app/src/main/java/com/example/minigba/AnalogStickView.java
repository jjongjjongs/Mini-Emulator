package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

// PSP-style analog nub: a recessed ring with a grey cap that follows the thumb and
// reports how far it is pushed, each axis -1..1 (down and right positive).
final class AnalogStickView extends View {
    interface Listener {
        void onAnalogChanged(float x, float y);
    }

    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float knobX;
    private float knobY;
    // "스틱 감도": how far a finger travel of 36% of the pad counts as full tilt; 2 means half
    // that travel already tilts it fully.
    private float sensitivity = 1.0f;

    AnalogStickView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setFocusable(false);
    }

    // "버튼 꾸미기": the ring and cap take the theme's 방향키 colour.
    private ButtonStyle style;

    private String styleKey;

    void setStyle(ButtonStyle style, String key) {
        this.styleKey = key;
        this.style = style != null && style.theme != ButtonStyle.THEME_DEFAULT ? style : null;
        invalidate();
    }

    void setSensitivity(float value) {
        this.sensitivity = Math.max(0.25f, Math.min(4.0f, value));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;
        float ring = size * 0.48f;
        int themed = this.style != null ? this.style.colorFor(this.styleKey, ButtonStyle.ROLE_DPAD) : 0;
        boolean line = this.style != null && (this.style.outline() || this.style.neon());
        if (line) {
            paint.setShader(null);
            paint.setColor(this.style.neon() ? Color.argb(200, 18, 19, 24) : Color.argb(28, 255, 255, 255));
            canvas.drawCircle(cx, cy, ring, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2.0f, size * 0.025f));
            paint.setColor(themed);
            canvas.drawCircle(cx, cy, ring - paint.getStrokeWidth() / 2.0f, paint);
            paint.setStyle(Paint.Style.FILL);
        } else if (themed != 0) {
            int[] palette = ButtonStyle.palette(themed);
            paint.setShader(new RadialGradient(cx, cy, ring, new int[]{ButtonStyle.withAlpha(palette[2], 220), ButtonStyle.withAlpha(palette[2], 210), ButtonStyle.withAlpha(palette[1], 200)},
                    new float[]{0, 0.8f, 1}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, ring, paint);
        } else {
            paint.setShader(new RadialGradient(cx, cy, ring, new int[]{Color.argb(220, 30, 30, 36), Color.argb(210, 46, 46, 52), Color.argb(200, 96, 96, 104)},
                    new float[]{0, 0.8f, 1}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, ring, paint);
        }
        float travel = size * 0.2f;
        float kx = cx + knobX * travel;
        float ky = cy + knobY * travel;
        float cap = size * 0.3f;
        paint.setShader(new RadialGradient(kx, ky + cap * 0.5f, cap * 1.3f, Color.argb(120, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawCircle(kx, ky + cap * 0.3f, cap * 1.2f, paint);
        if (themed != 0) {
            int[] palette = ButtonStyle.palette(themed);
            paint.setShader(new RadialGradient(kx - cap * 0.25f, ky - cap * 0.3f, cap * 1.4f,
                    new int[]{palette[0], palette[1], palette[2]}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new RadialGradient(kx - cap * 0.25f, ky - cap * 0.3f, cap * 1.4f,
                    new int[]{Color.rgb(168, 168, 176), Color.rgb(96, 96, 104), Color.rgb(52, 52, 58)}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        }
        canvas.drawCircle(kx, ky, cap, paint);
        paint.setShader(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            update(0, 0);
            return true;
        }
        float max = Math.max(1.0f, Math.min(getWidth(), getHeight()) * 0.36f / this.sensitivity);
        float x = (event.getX() - getWidth() / 2.0f) / max;
        float y = (event.getY() - getHeight() / 2.0f) / max;
        float length = (float) Math.hypot(x, y);
        if (length > 1.0f) {
            x /= length;
            y /= length;
        }
        update(x, y);
        return true;
    }

    private void update(float x, float y) {
        if (x == knobX && y == knobY) {
            return;
        }
        knobX = x;
        knobY = y;
        listener.onAnalogChanged(x, y);
        invalidate();
    }
}
