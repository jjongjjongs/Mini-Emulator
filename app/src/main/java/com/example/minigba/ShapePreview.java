package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

// (0.0.410) 화면 모양's live picture on a landscape screen: the frame, and in it the game's
// picture at the chosen aspect, scale mode, corner rounding and border, redrawn on each change.
final class ShapePreview extends View {
    float aspect = 4f / 3f;
    boolean pixelExact;
    int cornerDp;
    boolean border;
    String label = "";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    ShapePreview(Context context) {
        super(context);
    }

    void set(float aspect, boolean pixelExact, int cornerDp, boolean border, String label) {
        this.aspect = aspect > 0 ? aspect : 4f / 3f;
        this.pixelExact = pixelExact;
        this.cornerDp = cornerDp;
        this.border = border;
        this.label = label == null ? "" : label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(58, 59, 63));
        box.set(0, 0, w, h);
        canvas.drawRoundRect(box, 16 * d, 16 * d, paint);
        float pad = 14 * d;
        float fw = w - 2 * pad;
        float fh = h - 2 * pad - 18 * d;
        float pw = Math.min(fw, fh * aspect);
        float ph = pw / aspect;
        if (pixelExact) {
            pw *= 0.86f;
            ph *= 0.86f;
        }
        float left = (w - pw) / 2f;
        float top = pad + (fh - ph) / 2f;
        box.set(left, top, left + pw, top + ph);
        float r = cornerDp * d * 0.75f;
        paint.setShader(new LinearGradient(0, top, 0, top + ph, new int[]{Color.rgb(96, 165, 250), Color.rgb(96, 165, 250), Color.rgb(74, 222, 128), Color.rgb(74, 222, 128)},
                new float[]{0f, 0.62f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, r, r, paint);
        paint.setShader(null);
        if (border) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(Color.rgb(17, 17, 17));
            canvas.drawRoundRect(box, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(Color.rgb(209, 213, 219));
        paint.setTextSize(11 * d);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, w / 2f, h - 10 * d, paint);
    }
}
