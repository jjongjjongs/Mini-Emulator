package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.util.Random;

// Background of the game screen: a clear plastic case with the board showing
// through, a frosted plate under the controls and a bezel with screws around
// the game screen. The screen and control views are measured after layout, so
// the drawing follows wherever the layout puts them.
final class ClearShellView extends View {
    static final int TINT_WHITE = 0;
    static final int TINT_PURPLE = 1;
    static final int TINT_ORANGE = 2;
    static final int TINT_GRAY = 3;
    static final int TINT_CREAM = 4;
    static final int TINT_SMOKE = 5;
    static final int TINT_AQUA = 6;

    // Background styles the player picks in the game settings ("배경").
    static final int STYLE_CLEAR = 0;
    static final int STYLE_PLASTIC = 1;
    static final int STYLE_DARK = 2;
    static final int STYLE_PASTEL = 3;
    static final int STYLE_METAL = 4;
    static final int STYLE_NEON = 5;
    static final int STYLE_PHOTO = 6;
    static final String[] STYLE_NAMES = {"투명 케이스", "실물 플라스틱", "다크 매트", "파스텔 글라스", "메탈 · 카본", "네온 나이트", "내 사진 · 그림"};
    static final String[] STYLE_NOTES = {"지금 디자인 · 속이 비치는 플라스틱", "게임기 원래 색의 무광 플라스틱", "어두운 무광 · 게임 화면이 돋보여요",
            "부드러운 그라데이션 + 반투명 유리판", "은색 금속 + 카본 무늬", "빛나는 네온 테두리 · 오락실 분위기", "갤러리에서 고른 사진을 게임기 몸체로 · 위치 · 확대 · 어둡게 조절"};

    private static final int[][] TINTS = {
            // case colour, rim highlight, plate colour
            {Color.rgb(235, 238, 241), Color.argb(150, 255, 255, 255), Color.rgb(238, 240, 241)},
            {Color.rgb(226, 218, 244), Color.argb(150, 245, 240, 255), Color.rgb(239, 234, 249)},
            {Color.rgb(244, 229, 218), Color.argb(150, 255, 246, 238), Color.rgb(246, 238, 231)},
            {Color.rgb(229, 229, 236), Color.argb(150, 255, 255, 255), Color.rgb(239, 239, 244)},
            {Color.rgb(244, 236, 222), Color.argb(150, 255, 250, 240), Color.rgb(247, 240, 228)},
            {Color.rgb(206, 210, 217), Color.argb(130, 240, 242, 246), Color.rgb(227, 230, 235)},
            {Color.rgb(170, 198, 232), Color.argb(150, 225, 238, 252), Color.rgb(214, 230, 248)},
    };
    private static final int TRACE_RED = Color.argb(150, 208, 80, 79);
    private static final int TRACE_SILVER = Color.argb(150, 154, 164, 171);

    private final int tint;
    private final int style;
    // The mode's colour (dark/pastel/neon styles) and the console's body colour (plastic).
    private final int accent;
    private final int body;
    private final String label;
    private final boolean landscape;
    private View screenView;
    private boolean thinBezel;
    private boolean compactBezel;
    private boolean labelHidden;
    private View plateView;
    // The player's photo (STYLE_PHOTO), already cropped, darkened and blurred for this orientation.
    private android.graphics.Bitmap photo;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] origin = new int[2];
    private final int[] where = new int[2];
    private final float density;

    ClearShellView(Context context, int tint, String label, boolean landscape) {
        this(context, tint, label, landscape, STYLE_CLEAR, Color.GRAY, Color.GRAY);
    }

    ClearShellView(Context context, int tint, String label, boolean landscape, int style, int accent, int body) {
        super(context);
        this.tint = tint;
        this.style = style >= 0 && style < STYLE_NAMES.length ? style : STYLE_CLEAR;
        this.accent = accent;
        this.body = body;
        this.label = label;
        this.landscape = landscape;
        this.density = context.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setColor(Color.rgb(93, 101, 108));
        text.setLetterSpacing(0.22f);
    }

    void setPhoto(android.graphics.Bitmap bitmap) {
        this.photo = bitmap;
        invalidate();
    }

    // Full-screen style: a thin glass rim around the screen instead of the grey bezel.
    void setThinBezel(boolean thin) {
        this.thinBezel = thin;
        invalidate();
    }

    // Grey bezel as wide below the screen as at its sides, without the engraved name
    // (PS1/PS2 in landscape, whose 4:3 screen takes that height instead).
    // The performance readout sits where the engraved name goes.
    void setLabelHidden(boolean hidden) {
        this.labelHidden = hidden;
        invalidate();
    }

    void setCompactBezel(boolean compact) {
        this.compactBezel = compact;
        invalidate();
    }

    // The bezel is drawn around screen, the frosted plate behind controls.
    void track(View screen, View controls) {
        this.screenView = screen;
        this.plateView = controls;
        OnLayoutChangeListener redraw = (v, l, t, r, b, ol, ot, or, ob) -> invalidate();
        if (screen != null) {
            // No screen means no bezel (full-screen style).
            screen.addOnLayoutChangeListener(redraw);
        }
        controls.addOnLayoutChangeListener(redraw);
    }

    private float dp(float value) {
        return value * density;
    }

    private RectF boundsOf(View view) {
        getLocationInWindow(origin);
        view.getLocationInWindow(where);
        float left = where[0] - origin[0];
        float top = where[1] - origin[1];
        return new RectF(left, top, left + view.getWidth(), top + view.getHeight());
    }

    // Whether text drawn straight on this background (the labels under the top icons)
    // should be light.
    static boolean darkBackground(int style, int body) {
        switch (style) {
            case STYLE_DARK:
            case STYLE_NEON:
            case STYLE_PHOTO:
                return true;
            case STYLE_PLASTIC:
                return luminance(body) < 0.5f;
            default:
                return false;
        }
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (style != STYLE_CLEAR) {
            drawStyled(canvas);
            return;
        }
        float width = getWidth();
        float height = getHeight();
        int[] colors = TINTS[Math.max(0, Math.min(TINTS.length - 1, tint))];

        // The case itself: light catching the plastic diagonally.
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{colors[0], Color.WHITE, colors[0]}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        RectF plate = plateView == null ? null : boundsOf(plateView);
        if (plate != null) {
            if (landscape) {
                plate.set(plate.left + dp(8), plate.top + dp(8), plate.right - dp(8), plate.bottom - dp(8));
            } else {
                plate.set(plate.left + dp(14), plate.top + dp(2), plate.right - dp(14), plate.bottom - dp(14));
            }
            drawBoard(canvas, plate);
        }
        drawSideWiring(canvas, width, height);
        if (plate != null && plate.width() > 0 && plate.height() > 0) {
            float radius = dp(22);
            paint.setShader(new LinearGradient(0, plate.top, 0, plate.bottom,
                    withAlpha(colors[2], 208), Color.argb(200, 251, 251, 251), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.argb(240, 255, 255, 255));
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // Clear rim around the whole case.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(colors[1]);
        float inset = dp(4);
        canvas.drawRoundRect(new RectF(inset, inset, width - inset, height - inset), dp(18), dp(18), paint);
        paint.setStyle(Paint.Style.FILL);

        if (screenView != null && screenView.getWidth() > 0) {
            if (thinBezel) {
                drawGlassRim(canvas, boundsOf(screenView));
            } else {
                drawBezel(canvas, boundsOf(screenView));
            }
        }
        if (plate != null) {
            drawScrew(canvas, plate.left + dp(18), plate.bottom - dp(16));
            drawScrew(canvas, plate.right - dp(18), plate.bottom - dp(16));
        }
    }

    // Traces and chips of the board behind the plate; a fixed seed keeps them still.
    private void drawBoard(Canvas canvas, RectF plate) {
        paint.setColor(Color.argb(46, 170, 190, 180));
        canvas.drawRoundRect(new RectF(plate.left + dp(6), plate.top - dp(10), plate.right - dp(6), plate.bottom - dp(6)), dp(16), dp(16), paint);
        Random random = new Random(7);
        for (int i = 0; i < 16; i++) {
            boolean horizontal = i % 3 != 0;
            float x = plate.left + dp(14) + random.nextFloat() * Math.max(1, plate.width() - dp(104));
            float y = plate.top + random.nextFloat() * Math.max(1, plate.height() - dp(20));
            float length = dp(horizontal ? 40 + random.nextInt(80) : 30 + random.nextInt(60));
            paint.setColor(i % 2 == 0 ? TRACE_SILVER : TRACE_RED);
            if (horizontal) {
                canvas.drawRect(x, y, x + length, y + dp(2), paint);
            } else {
                canvas.drawRect(x, y, x + dp(2), y + length, paint);
            }
        }
        for (int i = 0; i < 6; i++) {
            float x = plate.left + dp(12) + random.nextFloat() * Math.max(1, plate.width() - dp(62));
            float y = plate.top + random.nextFloat() * Math.max(1, plate.height() - dp(30));
            float w = dp(18 + random.nextInt(22));
            float h = dp(8 + random.nextInt(8));
            paint.setShader(new LinearGradient(0, y, 0, y + h, Color.argb(140, 154, 161, 167), Color.argb(140, 125, 133, 139), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(x, y, x + w, y + h), dp(2), dp(2), paint);
            paint.setShader(null);
        }
    }

    private void drawSideWiring(Canvas canvas, float width, float height) {
        for (float x : new float[]{dp(9), width - dp(11)}) {
            paint.setColor(TRACE_SILVER);
            canvas.drawRect(x, height * 0.35f, x + dp(2), height * 0.75f, paint);
            paint.setColor(TRACE_RED);
            canvas.drawRect(x + dp(3), height * 0.45f, x + dp(5), height * 0.7f, paint);
        }
    }

    // Grey bezel around the game screen, nearly square at the corners, with an engraved name.
    private void drawBezel(Canvas canvas, RectF screen) {
        float side = dp(landscape ? 10 : 14);
        float top = dp(landscape ? 10 : 16);
        float bottom = compactBezel ? side : dp(landscape ? 26 : 34);
        RectF bezel = new RectF(screen.left - side, screen.top - top, screen.right + side, screen.bottom + bottom);
        float small = frameRadius();
        float large = frameRadius();
        Path path = new Path();
        path.addRoundRect(bezel, new float[]{small, small, small, small, large, large, small, small}, Path.Direction.CW);
        // Drop shadow first, then the bezel itself.
        canvas.save();
        canvas.translate(0, dp(2));
        paint.setColor(Color.argb(38, 0, 0, 0));
        canvas.drawPath(path, paint);
        canvas.restore();
        paint.setShader(new LinearGradient(0, bezel.top, 0, bezel.bottom, Color.rgb(195, 200, 204), Color.rgb(174, 181, 186), Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(128, 255, 255, 255));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (label != null && !compactBezel && !labelHidden) {
            text.setTextSize(dp(landscape ? 8 : 9));
            canvas.drawText(label, bezel.centerX(), screen.bottom + bottom * 0.62f, text);
        }
        drawScrew(canvas, bezel.left - dp(10), bezel.top + dp(10));
        drawScrew(canvas, bezel.right + dp(10), bezel.top + dp(10));
        drawScrew(canvas, bezel.left - dp(10), bezel.bottom - dp(10));
        drawScrew(canvas, bezel.right + dp(10), bezel.bottom - dp(10));
    }

    // A see-through glass edge a few dp wide with a soft shadow, rounded at the corners.
    private void drawGlassRim(Canvas canvas, RectF screen) {
        float edge = dp(5);
        RectF rim = new RectF(screen.left - edge, screen.top - edge, screen.right + edge, screen.bottom + edge);
        float radius = frameRadius();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(36, 0, 0, 0));
        canvas.drawRoundRect(new RectF(rim.left, rim.top + dp(3), rim.right, rim.bottom + dp(4)), radius, radius, paint);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawRoundRect(rim, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.argb(150, 150, 160, 170));
        canvas.drawRoundRect(rim, radius, radius, paint);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(220, 255, 255, 255));
        float inner = dp(1.5f);
        canvas.drawRoundRect(new RectF(rim.left + inner, rim.top + inner, rim.right - inner, rim.bottom - inner), radius - inner, radius - inner, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawScrew(Canvas canvas, float x, float y) {
        float r = dp(7);
        if (x - r < 0 || x + r > getWidth()) {
            return;
        }
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(x, y, r + dp(2), paint);
        paint.setShader(new RadialGradient(x - r * 0.25f, y - r * 0.3f, r * 1.4f,
                new int[]{Color.WHITE, Color.rgb(201, 207, 212), Color.rgb(154, 163, 170)}, new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
        paint.setColor(Color.rgb(138, 147, 154));
        paint.setStrokeWidth(dp(1.6f));
        float k = r * 0.5f;
        canvas.drawLine(x - k, y - k, x + k, y + k, paint);
        canvas.drawLine(x - k, y + k, x + k, y - k, paint);
    }

    // ---- The other background styles ----

    private RectF plateBounds() {
        if (plateView == null) {
            return null;
        }
        RectF plate = boundsOf(plateView);
        if (landscape) {
            plate.set(plate.left + dp(8), plate.top + dp(8), plate.right - dp(8), plate.bottom - dp(8));
        } else {
            plate.set(plate.left + dp(14), plate.top + dp(2), plate.right - dp(14), plate.bottom - dp(14));
        }
        return plate.width() > 0 && plate.height() > 0 ? plate : null;
    }

    // Corners of the frame around the game screen: nearly square, like the picture inside.
    private float frameRadius() {
        return dp(thinBezel ? 3 : 4);
    }

    // The frame around the game screen, the same size as the clear case's bezel.
    private RectF frameAround(RectF screen) {
        if (thinBezel) {
            float edge = dp(5);
            return new RectF(screen.left - edge, screen.top - edge, screen.right + edge, screen.bottom + edge);
        }
        float side = dp(landscape ? 10 : 14);
        float top = dp(landscape ? 10 : 16);
        float bottom = compactBezel ? side : dp(landscape ? 26 : 34);
        return new RectF(screen.left - side, screen.top - top, screen.right + side, screen.bottom + bottom);
    }

    private void drawStyled(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        RectF plate = plateBounds();
        RectF screen = screenView != null && screenView.getWidth() > 0 ? boundsOf(screenView) : null;
        RectF frame = screen == null ? null : frameAround(screen);
        switch (style) {
            case STYLE_PLASTIC:
                drawPlastic(canvas, width, height, frame);
                break;
            case STYLE_DARK:
                drawDark(canvas, width, height, plate, frame);
                break;
            case STYLE_PASTEL:
                drawPastel(canvas, width, height, plate, frame);
                break;
            case STYLE_METAL:
                drawMetal(canvas, width, height, plate, frame);
                break;
            case STYLE_PHOTO:
                drawPhoto(canvas, width, height, plate, frame);
                break;
            default:
                drawNeon(canvas, width, height, frame);
                break;
        }
        if (frame != null && label != null && !thinBezel && !compactBezel) {
            text.setTextSize(dp(landscape ? 8 : 9));
            canvas.drawText(label, frame.centerX(), screen.bottom + (frame.bottom - screen.bottom) * 0.62f, text);
        }
    }

    private void fillFrame(Canvas canvas, RectF frame, int top, int bottom, float radius, float lowerRight) {
        Path path = new Path();
        path.addRoundRect(frame, new float[]{radius, radius, radius, radius, lowerRight, lowerRight, radius, radius}, Path.Direction.CW);
        paint.setShader(new LinearGradient(0, frame.top, 0, frame.bottom, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);
    }

    private void strokeRoundRect(Canvas canvas, RectF rect, float radius, float stroke, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // Slanted slots of a speaker in the lower-right corner.
    private void drawGrille(Canvas canvas, float width, float height, int color, int count, float gap) {
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(4));
        for (int i = 0; i < count; i++) {
            float x = width - dp(28) - (count - 1 - i) * dp(gap);
            canvas.drawLine(x, height - dp(18), x - dp(14), height - dp(44), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private static int mix(int color, int other, float amount) {
        return Color.rgb(Math.round(Color.red(color) + (Color.red(other) - Color.red(color)) * amount),
                Math.round(Color.green(color) + (Color.green(other) - Color.green(color)) * amount),
                Math.round(Color.blue(color) + (Color.blue(other) - Color.blue(color)) * amount));
    }

    private static int hue(int color, float shift, float saturation, float value) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + shift + 360f) % 360f;
        if (saturation >= 0) {
            hsv[1] = saturation;
        }
        if (value >= 0) {
            hsv[2] = value;
        }
        return Color.HSVToColor(hsv);
    }

    private void drawPlastic(Canvas canvas, float width, float height, RectF frame) {
        paint.setShader(new RadialGradient(width * 0.3f, height * 0.2f, Math.max(width, height),
                new int[]{mix(body, Color.WHITE, 0.12f), body, mix(body, Color.BLACK, 0.25f)}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        RectF edge = new RectF(dp(3), dp(3), width - dp(3), height - dp(3));
        strokeRoundRect(canvas, edge, dp(20), dp(2), Color.argb(56, 255, 255, 255));
        drawGrille(canvas, width, height, Color.argb(80, 0, 0, 0), 6, 9);
        if (frame != null) {
            boolean light = luminance(body) >= 0.5f;
            fillFrame(canvas, frame, light ? Color.rgb(110, 114, 124) : Color.rgb(44, 47, 58), light ? Color.rgb(92, 96, 106) : Color.rgb(27, 29, 36), frameRadius(), frameRadius());
            text.setColor(light ? Color.rgb(222, 225, 232) : Color.rgb(154, 160, 180));
        }
    }

    private void drawDark(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        paint.setShader(new LinearGradient(0, 0, 0, height, Color.rgb(28, 31, 38), Color.rgb(17, 19, 24), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        if (plate != null) {
            paint.setColor(Color.rgb(24, 27, 33));
            canvas.drawRoundRect(plate, dp(24), dp(24), paint);
            strokeRoundRect(canvas, plate, dp(24), dp(1), Color.argb(14, 255, 255, 255));
        }
        paint.setColor(accent);
        canvas.drawRect(0, 0, width, dp(3), paint);
        if (frame != null) {
            paint.setColor(Color.BLACK);
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            strokeRoundRect(canvas, frame, frameRadius(), dp(1), Color.argb(22, 255, 255, 255));
            text.setColor(Color.rgb(93, 100, 112));
        }
    }

    // The photo fills the case; the bezel and a faint plate behind the controls keep the
    // dark style's shapes so the buttons read against any picture.
    private void drawPhoto(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        if (photo != null && !photo.isRecycled()) {
            float scale = Math.max(width / photo.getWidth(), height / photo.getHeight());
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postScale(scale, scale);
            matrix.postTranslate((width - photo.getWidth() * scale) / 2f, (height - photo.getHeight() * scale) / 2f);
            Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(photo, matrix, bitmapPaint);
        } else {
            paint.setShader(new LinearGradient(0, 0, 0, height, Color.rgb(28, 31, 38), Color.rgb(17, 19, 24), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
        }
        if (plate != null) {
            paint.setColor(Color.argb(70, 10, 12, 16));
            canvas.drawRoundRect(plate, dp(24), dp(24), paint);
            strokeRoundRect(canvas, plate, dp(24), dp(1), Color.argb(40, 255, 255, 255));
        }
        if (frame != null) {
            paint.setColor(Color.argb(235, 0, 0, 0));
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            strokeRoundRect(canvas, frame, frameRadius(), dp(1), Color.argb(60, 255, 255, 255));
            text.setColor(Color.rgb(150, 156, 168));
        }
    }

    private void drawPastel(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        int a = mix(accent, Color.WHITE, 0.6f);
        int b = mix(hue(accent, 45, -1, -1), Color.WHITE, 0.62f);
        int c = mix(hue(accent, -40, -1, -1), Color.WHITE, 0.72f);
        paint.setShader(new LinearGradient(0, 0, width * 0.5f, height, new int[]{a, b, c}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        float glow = Math.min(width, height) * 0.55f;
        paint.setShader(new RadialGradient(width * 0.1f, height * 0.7f, glow, Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        canvas.drawCircle(width * 0.1f, height * 0.7f, glow, paint);
        paint.setShader(null);
        if (plate != null) {
            paint.setColor(Color.argb(97, 255, 255, 255));
            canvas.drawRoundRect(plate, dp(26), dp(26), paint);
            strokeRoundRect(canvas, plate, dp(26), dp(1.5f), Color.argb(190, 255, 255, 255));
        }
        if (frame != null) {
            paint.setColor(Color.argb(140, 255, 255, 255));
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            strokeRoundRect(canvas, frame, frameRadius(), dp(1.5f), Color.argb(230, 255, 255, 255));
            text.setColor(mix(accent, Color.rgb(60, 56, 80), 0.6f));
        }
    }

    private void drawMetal(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{Color.rgb(201, 206, 212), Color.rgb(238, 241, 244), Color.rgb(184, 190, 197), Color.rgb(223, 227, 231)},
                new float[]{0, 0.4f, 0.7f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        // Brushed grain.
        paint.setColor(Color.argb(22, 255, 255, 255));
        for (float x = 0; x < width; x += dp(2)) {
            canvas.drawRect(x, 0, x + dp(0.6f), height, paint);
        }
        if (plate != null) {
            float radius = dp(22);
            Path clip = new Path();
            clip.addRoundRect(plate, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            paint.setColor(Color.rgb(30, 32, 35));
            canvas.drawRect(plate, paint);
            // Carbon weave: diagonal bands.
            paint.setColor(Color.rgb(42, 44, 48));
            paint.setStrokeWidth(dp(4));
            float span = plate.width() + plate.height();
            for (float d = -plate.height(); d < span; d += dp(8)) {
                canvas.drawLine(plate.left + d, plate.bottom, plate.left + d + plate.height(), plate.top, paint);
            }
            canvas.restore();
            strokeRoundRect(canvas, plate, radius, dp(1.5f), Color.argb(30, 255, 255, 255));
            for (float x : new float[]{plate.left + dp(18), plate.right - dp(18)}) {
                float y = plate.bottom - dp(16);
                paint.setShader(new RadialGradient(x - dp(1.5f), y - dp(2), dp(7), Color.rgb(244, 246, 248), Color.rgb(141, 149, 156), Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, dp(6), paint);
                paint.setShader(null);
            }
        }
        if (frame != null) {
            paint.setColor(Color.rgb(21, 23, 26));
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            strokeRoundRect(canvas, frame, frameRadius(), dp(2), Color.rgb(154, 162, 169));
            text.setColor(Color.rgb(142, 150, 157));
        }
    }

    private void drawNeon(Canvas canvas, float width, float height, RectF frame) {
        int edge = hue(accent, 0, 0.75f, 1f);
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        // Purple screen frame, or pink when the mode itself is purple.
        int glow = hsv[0] >= 240 && hsv[0] <= 300 ? Color.rgb(236, 72, 153) : Color.rgb(168, 85, 247);
        paint.setShader(new RadialGradient(width / 2, height, Math.max(width, height) * 0.9f,
                Color.rgb(42, 18, 80), Color.rgb(12, 10, 29), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        // A grid floor running into the distance along the bottom.
        float floor = height * (landscape ? 0.35f : 0.22f);
        float horizon = height - floor;
        paint.setStrokeWidth(dp(1));
        paint.setColor(withAlpha(glow, 80));
        for (int i = 1; i <= 7; i++) {
            float t = i / 7f;
            float y = horizon + floor * t * t;
            canvas.drawLine(0, y, width, y, paint);
        }
        for (int i = -8; i <= 8; i++) {
            float x = width / 2 + i * dp(34);
            canvas.drawLine(width / 2 + i * dp(10), horizon, x, height, paint);
        }
        RectF rim = new RectF(dp(4), dp(4), width - dp(4), height - dp(4));
        neonStroke(canvas, rim, dp(20), edge);
        if (frame != null) {
            paint.setColor(Color.rgb(7, 6, 15));
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            neonStroke(canvas, frame, frameRadius(), glow);
            text.setColor(mix(glow, Color.WHITE, 0.55f));
        }
    }

    // A bright line with a soft glow, drawn as widening faint strokes.
    private void neonStroke(Canvas canvas, RectF rect, float radius, int color) {
        for (int i = 4; i >= 1; i--) {
            strokeRoundRect(canvas, rect, radius, dp(2 + i * 3), withAlpha(color, 18));
        }
        strokeRoundRect(canvas, rect, radius, dp(2), color);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
