package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

final class ControlPanelView extends View {
    private final Paint paint;
    private final Paint speaker;
    private final Paint stroke;
    private final Paint text;

    ControlPanelView(Context context) {
        super(context);
        this.paint = new Paint(1);
        Paint paint = new Paint(1);
        this.stroke = paint;
        Paint paint2 = new Paint(1);
        this.text = paint2;
        Paint paint3 = new Paint(1);
        this.speaker = paint3;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.0f);
        paint.setColor(Color.argb(105, 116, 111, 104));
        paint2.setColor(Color.argb(150, 48, 46, 112));
        paint2.setTextSize(22.0f);
        paint2.setFakeBoldText(true);
        paint3.setColor(Color.rgb(72, 69, 64));
        paint3.setStrokeWidth(7.0f);
        paint3.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        this.paint.setShader(new LinearGradient(0.0f, 0.0f, 0.0f, height, Color.rgb(226, 223, 215), Color.rgb(199, 195, 185), Shader.TileMode.CLAMP));
        this.paint.setStyle(Paint.Style.FILL);
        float f = height + 36.0f;
        canvas.drawRect(0.0f, 0.0f, width, f, this.paint);
        this.paint.setShader(null);
        canvas.drawRect(0.0f, 0.0f, width, f, this.stroke);
        this.paint.setColor(Color.rgb(31, 31, 35));
        canvas.drawRect(0.0f, 0.0f, width, 10.0f, this.paint);
        this.paint.setColor(Color.rgb(25, 238, 86));
        canvas.drawCircle(width - 94.0f, 40.0f, 8.0f, this.paint);
        canvas.drawText("POWER", width - 76.0f, 48.0f, this.text);
        float f2 = width - 120.0f;
        float f3 = height - 128.0f;
        for (int i = 0; i < 5; i++) {
            float f4 = f3 + (i * 15.0f);
            canvas.drawLine(f2, f4, width - 38.0f, f4 - 20.0f, this.speaker);
        }
    }
}
