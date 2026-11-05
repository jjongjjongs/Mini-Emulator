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
    // The first Mini GBA (0.0.2 – 0.0.3): warm grey body, black band, green POWER, speaker.
    static final int STYLE_RETRO = 7;
    // Space: pictures drawn ahead of time (assets/space), decoration kept to the corners.
    static final int STYLE_SPACE = 8;
    // Materials (walnut, leather, ink wash) and retro (CRT TV, 8-bit pixels): pictures in assets/skin.
    static final int STYLE_MATERIAL = 9;
    static final int STYLE_VINTAGE = 10;
    // The clear case (STYLE_CLEAR) is no longer offered (0.0.270): a stored 0 reads as
    // 실물 플라스틱 (see normalize), and the choice list leaves it out (CHOICES).
    static final String[] STYLE_NAMES = {"실물 플라스틱", "실물 플라스틱", "다크 매트", "파스텔 글라스", "메탈 · 카본", "네온 나이트", "내 사진 · 그림", "원조 (Mini GBA)", "우주", "재질", "레트로"};
    static final String[] STYLE_NOTES = {"게임기 원래 색의 무광 플라스틱", "게임기 원래 색의 무광 플라스틱", "어두운 무광 · 게임 화면이 돋보여요",
            "몸체 전체 한 장의 유리 · 프로스트 · 오로라 · 젤리", "몸체 전체 카본 + 알루미늄 테 · 화면 테두리에 포인트 색", "검정 몸체 + 가장자리 · 화면 테두리만 빛남 · 엣지 · 사인 · 레드블루 · 언더", "갤러리에서 고른 사진을 게임기 몸체로 · 위치 · 확대 · 어둡게 조절",
            "GBA만 있던 첫 버전의 회베이지 몸체 · POWER 불 · 스피커 · 원조 버튼 색",
            "실사 느낌의 우주 · 링 행성 · 성운 · 어스라이즈 · 딥 필드",
            "실제 재질 느낌 · 월넛 우드 · 가죽 · 수묵화",
            "80 · 90년대 느낌 · 브라운관 TV · 8비트 픽셀"};
    // Styles shown in the "배경" list, in order.
    static final int[] CHOICES = {STYLE_PLASTIC, STYLE_DARK, STYLE_PASTEL, STYLE_METAL, STYLE_NEON, STYLE_SPACE, STYLE_MATERIAL, STYLE_VINTAGE, STYLE_PHOTO, STYLE_RETRO};

    static int normalize(int style) {
        return style > STYLE_CLEAR && style < STYLE_NAMES.length ? style : STYLE_PLASTIC;
    }

    // Kinds of 파스텔 글라스.
    static final int GLASS_FROST = 0;
    static final int GLASS_AURORA = 1;
    static final int GLASS_JELLY = 2;
    static final String[] GLASS_NAMES = {"프로스트", "오로라", "젤리"};
    static final String[] GLASS_NOTES = {"파스텔 빛 번짐 + 젖빛 유리 · 모드마다 색이 달라요", "무지갯빛 홀로그램 + 비눗방울", "모드 색 젤리 + 도트 · 큰 광택 줄"};

    // Kinds of 네온 나이트: the light stays on the edges and round the screen.
    static final int NEON_EDGE = 0;
    static final int NEON_SIGN = 1;
    static final int NEON_DUO = 2;
    static final int NEON_UNDER = 3;
    static final String[] NEON_NAMES = {"엣지 글로우", "네온 사인", "레드 · 블루", "언더글로우"};

    // Kinds of 우주, and their pictures (assets/space/<file>_p.jpg / _l.jpg).
    static final String[] SPACE_NAMES = {"링 행성", "성운", "어스라이즈", "딥 필드"};
    private static final String[] SPACE_FILES = {"ring", "nebula", "earthrise", "deep"};
    // The line round the screen in each kind.
    private static final int[] SPACE_LINES = {Color.rgb(254, 243, 199), Color.rgb(233, 213, 255), Color.rgb(226, 232, 240), Color.rgb(255, 240, 215)};

    static final String[] MATERIAL_NAMES = {"월넛 우드", "가죽", "수묵화"};
    static final String[] VINTAGE_NAMES = {"브라운관 TV", "8비트 픽셀"};
    // Skins for 버튼 꾸미기 "배경 맞춤" (ButtonStyle.THEME_SKIN): walnut, leather, ink, CRT, pixel.
    static final int SKIN_WOOD = 0;
    static final int SKIN_LEATHER = 1;
    static final int SKIN_INK = 2;
    static final int SKIN_CRT = 3;
    static final int SKIN_PIXEL = 4;
    private static final String[] SKIN_FILES = {"wood_%s.jpg", "leather_%s.jpg", "ink_%s.jpg", "crt_%s.jpg", "pixel_%s.png"};

    static int skinOf(int style, int material, int vintage) {
        if (style == STYLE_MATERIAL) return Math.max(0, Math.min(2, material));
        if (style == STYLE_VINTAGE) return vintage == 1 ? SKIN_PIXEL : SKIN_CRT;
        return -1;
    }

    // 포인트 색 of the metal · carbon case: the line around the game screen.
    static final String[] TRIM_NAMES = {"빨강", "파랑", "금색", "없음"};
    static final String[] TRIM_NOTES = {"화면 테두리에 빨간 선", "화면 테두리에 파란 선", "화면 테두리에 금색 선", "검은 선과 은색 테만"};
    private static final int[] TRIM_COLORS = {Color.rgb(239, 68, 68), Color.rgb(59, 130, 246), Color.rgb(212, 175, 55), 0};

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
        this.style = style >= 0 && style < STYLE_NAMES.length ? style : STYLE_PLASTIC;
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

    private int trim = TRIM_COLORS[0];
    private Shader weave;
    private Shader dots;
    private int glass;
    private int neon;
    private int space;
    private int skin = -1;
    // One picture kept decoded between redraws (the case is rebuilt on every change).
    private static String spaceKey;
    private static android.graphics.Bitmap spaceBitmap;

    void setSkin(int skin) {
        this.skin = skin;
        invalidate();
    }

    void setSpace(int kind) {
        this.space = kind >= 0 && kind < SPACE_NAMES.length ? kind : 0;
        invalidate();
    }

    private android.graphics.Bitmap spacePicture() {
        return picture("space/" + SPACE_FILES[space] + (landscape ? "_l" : "_p") + ".jpg");
    }

    private android.graphics.Bitmap picture(String key) {
        if (key.equals(spaceKey) && spaceBitmap != null && !spaceBitmap.isRecycled()) {
            return spaceBitmap;
        }
        try (java.io.InputStream in = getContext().getAssets().open(key)) {
            android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeStream(in);
            if (decoded != null) {
                spaceKey = key;
                spaceBitmap = decoded;
            }
            return decoded;
        } catch (java.io.IOException | OutOfMemoryError e) {
            return null;
        }
    }

    void setNeon(int kind) {
        this.neon = kind >= 0 && kind < NEON_NAMES.length ? kind : NEON_EDGE;
        invalidate();
    }

    void setGlass(int kind) {
        this.glass = kind >= 0 && kind < GLASS_NAMES.length ? kind : GLASS_FROST;
        invalidate();
    }

    // Metal · carbon and pastel glass have no name strip under the screen.
    static boolean hasNameStrip(int style) {
        return style != STYLE_METAL && style != STYLE_PASTEL && style != STYLE_NEON && style != STYLE_SPACE
                && style != STYLE_MATERIAL && style != STYLE_VINTAGE;
    }

    void setTrim(int index) {
        this.trim = TRIM_COLORS[index >= 0 && index < TRIM_COLORS.length ? index : 0];
        invalidate();
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
        // A screen the player moved or resized in the layout editor: its location already
        // includes the offset and scale, so the size takes the scale too.
        return new RectF(left, top, left + view.getWidth() * view.getScaleX(), top + view.getHeight() * view.getScaleY());
    }

    // Whether text drawn straight on this background (the labels under the top icons)
    // should be light.
    static boolean darkBackground(int style, int body) {
        switch (style) {
            case STYLE_DARK:
            case STYLE_NEON:
            case STYLE_PHOTO:
            case STYLE_METAL:
            case STYLE_SPACE:
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
    // Landscape frames are drawn at about half the portrait thickness (0.0.276), with no
    // name strip, so the screen keeps as much of the height as it can.
    private float frameScale() {
        return landscape && !thinBezel ? 0.55f : 1f;
    }

    // How far the frame reaches past the screen on each side, when it is the same all round.
    private float frameEdge() {
        if (thinBezel) {
            return dp(5);
        }
        float base = style == STYLE_PASTEL || style == STYLE_MATERIAL || style == STYLE_VINTAGE ? 10
                : style == STYLE_NEON || style == STYLE_SPACE ? 6 : style == STYLE_METAL ? 8 : 10;
        return dp(base) * frameScale();
    }

    private RectF frameAround(RectF screen) {
        if (thinBezel || !hasNameStrip(style) || landscape) {
            // A surround of the same width all round; no name strip under the screen.
            float edge = frameEdge();
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
            case STYLE_SPACE:
                drawSpace(canvas, width, height, frame);
                break;
            case STYLE_MATERIAL:
            case STYLE_VINTAGE:
                drawSkin(canvas, width, height, frame);
                break;
            case STYLE_RETRO:
                drawRetro(canvas, width, height, plate, frame, screen);
                break;
            default:
                drawNeon(canvas, width, height, frame);
                break;
        }
        if (frame != null && label != null && !thinBezel && !compactBezel && hasNameStrip(style) && !landscape) {
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

    // The first Mini GBA: the colours its code drew with.
    private void drawRetro(Canvas canvas, float width, float height, RectF plate, RectF frame, RectF screen) {
        paint.setShader(new LinearGradient(0, 0, 0, height, Color.rgb(226, 223, 215), Color.rgb(199, 195, 185), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        if (frame != null) {
            // The screen's dark navy surround.
            paint.setColor(Color.rgb(18, 22, 32));
            canvas.drawRoundRect(frame, dp(4), dp(4), paint);
            text.setColor(Color.rgb(154, 160, 180));
        }
        // The black band: along the very top in landscape. In portrait it sat across the
        // L / R buttons of today's layout, so it is left out there.
        if (landscape) {
            paint.setColor(Color.rgb(31, 31, 35));
            canvas.drawRect(0, 0, width, dp(4), paint);
        }
        // POWER: bottom right in landscape; bottom left in portrait (clear of L / R and the speaker).
        float ledX = landscape ? width - dp(64) : dp(22);
        float ledY = height - dp(landscape ? 16 : 20);
        paint.setColor(Color.argb(90, 25, 238, 86));
        canvas.drawCircle(ledX, ledY, dp(7), paint);
        paint.setColor(Color.rgb(25, 238, 86));
        canvas.drawCircle(ledX, ledY, dp(4), paint);
        android.graphics.Paint label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        label.setColor(Color.argb(150, 48, 46, 112));
        label.setTextSize(dp(10));
        label.setFakeBoldText(true);
        canvas.drawText("POWER", ledX + dp(9), ledY + dp(3.5f), label);
        if (!landscape) {
            drawGrille(canvas, width, height, Color.rgb(72, 69, 64), 5, 8);
        }
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
        // No plate behind the controls: the photo is the case.
        if (frame != null) {
            paint.setColor(Color.argb(235, 0, 0, 0));
            canvas.drawRoundRect(frame, frameRadius(), frameRadius(), paint);
            strokeRoundRect(canvas, frame, frameRadius(), dp(1), Color.argb(60, 255, 255, 255));
            text.setColor(Color.rgb(150, 156, 168));
        }
    }

    // Pastel glass: the whole body is one sheet of glass (no plate under the controls),
    // in three kinds; the screen sits in a glass surround with a thin coloured line.
    private void drawPastel(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        int line;
        if (glass == GLASS_AURORA) {
            paint.setShader(new android.graphics.SweepGradient(width * 0.4f, height * 0.45f,
                    new int[]{Color.rgb(251, 207, 232), Color.rgb(221, 214, 254), Color.rgb(186, 230, 253), Color.rgb(187, 247, 208),
                            Color.rgb(254, 243, 199), Color.rgb(254, 202, 202), Color.rgb(251, 207, 232)}, null));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(new LinearGradient(0, 0, 0, height, new int[]{Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(64, 255, 255, 255)},
                    new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            // Faint holographic stripes.
            paint.setColor(Color.argb(30, 255, 255, 255));
            paint.setStrokeWidth(dp(2));
            for (float d = -height; d < width; d += dp(24)) {
                canvas.drawLine(d, height, d + height * 0.58f, 0, paint);
            }
            bubble(canvas, dp(28), height - dp(30), dp(18));
            bubble(canvas, width - dp(80), height - dp(40), dp(11));
            line = Color.rgb(196, 181, 253);
        } else if (glass == GLASS_JELLY) {
            paint.setShader(new LinearGradient(0, 0, 0, height, mix(accent, Color.WHITE, 0.78f), mix(accent, Color.WHITE, 0.5f), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            float glow = Math.max(width, height) * 0.6f;
            paint.setShader(new RadialGradient(width / 2, -height * 0.1f, glow, Color.argb(190, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            if (dots == null) {
                int cell = Math.max(6, Math.round(dp(14)));
                android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(cell, cell, android.graphics.Bitmap.Config.ARGB_8888);
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(Color.argb(90, 255, 255, 255));
                new Canvas(tile).drawCircle(cell / 2f, cell / 2f, dp(1.6f), dot);
                dots = new android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            }
            paint.setShader(dots);
            canvas.drawRect(0, 0, width, height, paint);
            // One big gloss band.
            paint.setShader(new LinearGradient(0, 0, width, height * 0.3f,
                    new int[]{Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    new float[]{0.05f, 0.2f, 0.36f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            line = Color.WHITE;
        } else {
            // Frost: soft blooms of the mode's colour and its neighbours under frosted glass.
            canvas.drawColor(mix(accent, Color.WHITE, 0.9f));
            float big = Math.max(width, height);
            int[] blooms = {mix(hue(accent, 60, -1, -1), Color.WHITE, 0.6f), mix(accent, Color.WHITE, 0.55f),
                    mix(hue(accent, -50, -1, -1), Color.WHITE, 0.62f), mix(hue(accent, 120, -1, -1), Color.WHITE, 0.65f)};
            float[][] at = {{0.15f, 0.1f}, {0.95f, 0.3f}, {0.2f, 0.9f}, {0.9f, 0.95f}};
            for (int k = 0; k < 4; k++) {
                float cx = width * at[k][0];
                float cy = height * at[k][1];
                paint.setShader(new RadialGradient(cx, cy, big * 0.45f, blooms[k], withAlpha(blooms[k], 0), Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, width, height, paint);
            }
            paint.setShader(new LinearGradient(0, 0, width * 0.4f, height, Color.argb(76, 255, 255, 255), Color.argb(26, 255, 255, 255), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(new LinearGradient(0, 0, width, height * 0.6f,
                    new int[]{Color.argb(0, 255, 255, 255), Color.argb(96, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(56, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    new float[]{0.22f, 0.3f, 0.38f, 0.62f, 0.67f, 0.72f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            line = mix(accent, Color.WHITE, 0.35f);
        }
        // Glass rim round the whole case.
        RectF rim = new RectF(dp(1), dp(1), width - dp(1), height - dp(1));
        strokeRoundRect(canvas, rim, dp(22), dp(2), Color.argb(240, 255, 255, 255));
        strokeRoundRect(canvas, new RectF(dp(3.5f), dp(3.5f), width - dp(3.5f), height - dp(3.5f)), dp(20), dp(3), Color.argb(90, 255, 255, 255));
        if (frame != null) {
            float radius = dp(14);
            paint.setColor(Color.argb(40, 90, 80, 150));
            canvas.drawRoundRect(new RectF(frame.left, frame.top + dp(4), frame.right, frame.bottom + dp(6)), radius, radius, paint);
            paint.setShader(new LinearGradient(frame.left, frame.top, frame.right, frame.bottom,
                    Color.argb(glass == GLASS_JELLY ? 120 : 165, 255, 255, 255), Color.argb(glass == GLASS_JELLY ? 100 : 80, 255, 255, 255), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(frame, radius, radius, paint);
            paint.setShader(null);
            strokeRoundRect(canvas, frame, radius, dp(glass == GLASS_JELLY ? 2 : 1), Color.argb(235, 255, 255, 255));
            // White, then the coloured line, just outside the picture.
            float e = frameEdge();
            float out = dp(3) * frameScale();
            RectF edge = new RectF(frame.left + e - out, frame.top + e - out, frame.right - e + out, frame.bottom - e + out);
            strokeRoundRect(canvas, edge, dp(6), dp(2), line);
            RectF white = new RectF(edge.left + dp(2), edge.top + dp(2), edge.right - dp(2), edge.bottom - dp(2));
            strokeRoundRect(canvas, white, dp(5), dp(2), Color.WHITE);
            paint.setColor(Color.rgb(17, 17, 20));
            canvas.drawRect(frame.left + e, frame.top + e, frame.right - e, frame.bottom - e, paint);
        }
    }

    // Space: the picture covers the case (cropped, centred); the screen sits in black
    // with a thin light line and a soft glow.
    private void drawSpace(Canvas canvas, float width, float height, RectF frame) {
        canvas.drawColor(Color.rgb(3, 3, 8));
        android.graphics.Bitmap picture = spacePicture();
        if (picture != null) {
            canvas.drawBitmap(picture, fitPicture(picture, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        if (frame != null) {
            float radius = dp(7);
            int line = SPACE_LINES[space];
            for (int i = 3; i >= 1; i--) {
                strokeRoundRect(canvas, frame, radius, dp(1.5f + i * 4), withAlpha(line, 14));
            }
            paint.setColor(Color.BLACK);
            canvas.drawRoundRect(frame, radius, radius, paint);
            strokeRoundRect(canvas, frame, radius, dp(1.5f), withAlpha(line, 170));
        }
    }

    // Materials and retro: the picture covers the case; the screen's surround is drawn
    // here to fit whatever size the layout gives the screen.
    private void drawSkin(Canvas canvas, float width, float height, RectF frame) {
        int kind = Math.max(0, Math.min(SKIN_FILES.length - 1, skin));
        canvas.drawColor(Color.rgb(40, 28, 20));
        android.graphics.Bitmap picture = picture("skin/" + String.format(java.util.Locale.ROOT, SKIN_FILES[kind], landscape ? "l" : "p"));
        if (picture != null) {
            android.graphics.Matrix matrix = fitPicture(picture, width, height);
            // Pixel art stays crisp.
            canvas.drawBitmap(picture, matrix, kind == SKIN_PIXEL ? null : new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        if (frame == null) {
            return;
        }
        float e = frameEdge();
        RectF screen = new RectF(frame.left + e, frame.top + e, frame.right - e, frame.bottom - e);
        // Margins and corners below shrink together in landscape, keeping the corners covered.
        final float k = frameScale();
        switch (kind) {
            case SKIN_WOOD: {
                // A dark recess cut into the wood.
                RectF recess = inset(screen, -dp(6) * k);
                paint.setColor(Color.argb(90, 0, 0, 0));
                canvas.drawRoundRect(inset(recess, -dp(2) * k), dp(12) * k, dp(12) * k, paint);
                paint.setColor(Color.rgb(11, 7, 5));
                canvas.drawRoundRect(recess, dp(10) * k, dp(10) * k, paint);
                strokeRoundRect(canvas, inset(recess, -dp(2.5f) * k), dp(12) * k, dp(1) * k, Color.argb(34, 255, 220, 170));
                break;
            }
            case SKIN_LEATHER: {
                RectF recess = inset(screen, -dp(6) * k);
                paint.setColor(Color.rgb(10, 7, 6));
                canvas.drawRoundRect(recess, dp(10) * k, dp(10) * k, paint);
                Paint stitch = new Paint(Paint.ANTI_ALIAS_FLAG);
                stitch.setStyle(Paint.Style.STROKE);
                stitch.setStrokeWidth(dp(1.5f) * k);
                stitch.setStrokeCap(Paint.Cap.ROUND);
                stitch.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(5) * k, dp(4) * k}, 0));
                stitch.setColor(Color.argb(110, 0, 0, 0));
                RectF seam = inset(screen, -dp(10) * k);
                canvas.drawRoundRect(new RectF(seam.left, seam.top + dp(1.2f) * k, seam.right, seam.bottom + dp(1.2f) * k), dp(13) * k, dp(13) * k, stitch);
                stitch.setColor(Color.argb(217, 232, 196, 140));
                canvas.drawRoundRect(seam, dp(13) * k, dp(13) * k, stitch);
                break;
            }
            case SKIN_INK: {
                RectF edge = inset(screen, -dp(4) * k);
                paint.setColor(Color.rgb(17, 17, 17));
                canvas.drawRoundRect(edge, dp(6) * k, dp(6) * k, paint);
                strokeRoundRect(canvas, inset(edge, -dp(1) * k), dp(7) * k, dp(2) * k, Color.argb(217, 30, 28, 26));
                strokeRoundRect(canvas, inset(edge, -dp(3) * k), dp(8) * k, dp(2) * k, Color.rgb(239, 230, 212));
                strokeRoundRect(canvas, inset(edge, -dp(4.5f) * k), dp(9) * k, dp(1) * k, Color.argb(90, 30, 28, 26));
                break;
            }
            case SKIN_CRT: {
                // Beige plastic bezel and the rounded tube inside it.
                RectF bezel = inset(screen, -dp(10) * k);
                paint.setColor(Color.argb(110, 0, 0, 0));
                canvas.drawRoundRect(new RectF(bezel.left, bezel.top + dp(3) * k, bezel.right, bezel.bottom + dp(4) * k), dp(16) * k, dp(16) * k, paint);
                paint.setShader(new LinearGradient(0, bezel.top, 0, bezel.bottom, Color.rgb(217, 210, 195), Color.rgb(184, 176, 160), Shader.TileMode.CLAMP));
                canvas.drawRoundRect(bezel, dp(16) * k, dp(16) * k, paint);
                paint.setShader(null);
                strokeRoundRect(canvas, inset(bezel, dp(0.75f) * k), dp(16) * k, dp(1.5f) * k, Color.argb(150, 255, 255, 255));
                // The picture is square-cornered, so the tube's corners stay small enough to
                // cover it (a corner radius over about 3.4 × the margin would let it show).
                RectF tube = inset(screen, -dp(6) * k);
                paint.setColor(Color.rgb(13, 15, 14));
                canvas.drawRoundRect(tube, dp(14) * k, dp(14) * k, paint);
                strokeRoundRect(canvas, tube, dp(14) * k, dp(2) * k, Color.rgb(143, 135, 120));
                break;
            }
            default: {
                // Pixel frame: dark, peach, dark, square.
                paint.setColor(Color.rgb(12, 8, 22));
                canvas.drawRect(inset(screen, -dp(2) * k), paint);
                float step = dp(3) * k;
                int[] rings = {Color.rgb(26, 19, 48), Color.rgb(244, 162, 122), Color.rgb(26, 19, 48)};
                for (int i = 0; i < rings.length; i++) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(step);
                    paint.setColor(rings[i]);
                    canvas.drawRect(inset(screen, -dp(2) * k - step * (i + 0.5f)), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                break;
            }
        }
    }

    // Stretched to the case when its shape is close to the picture's, so the decorations at
    // the edges (screws, stitching, dials, the moon's ground, a planet) all stay in view; a
    // case of a very different shape is covered instead, keeping the bottom edge.
    private static android.graphics.Matrix fitPicture(android.graphics.Bitmap picture, float width, float height) {
        float sx = width / picture.getWidth();
        float sy = height / picture.getHeight();
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        if (Math.max(sx, sy) / Math.min(sx, sy) <= 1.15f) {
            matrix.postScale(sx, sy);
        } else {
            float scale = Math.max(sx, sy);
            matrix.postScale(scale, scale);
            matrix.postTranslate((width - picture.getWidth() * scale) / 2f, height - picture.getHeight() * scale);
        }
        return matrix;
    }

    private static RectF inset(RectF rect, float by) {
        return new RectF(rect.left + by, rect.top + by, rect.right - by, rect.bottom - by);
    }

    // A soap bubble: a clear disc with a bright corner.
    private void bubble(Canvas canvas, float x, float y, float r) {
        paint.setShader(new RadialGradient(x - r * 0.4f, y - r * 0.4f, r * 1.4f,
                new int[]{Color.argb(240, 255, 255, 255), Color.argb(50, 255, 255, 255), Color.argb(64, 196, 181, 253)}, new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
        strokeRoundRect(canvas, new RectF(x - r, y - r, x + r, y + r), r, dp(1), Color.argb(200, 255, 255, 255));
    }

    // Carbon fibre over the whole body, an aluminium rim round the edge, a thin brushed
    // aluminium surround at the screen with the 포인트 색 line, hex screws and a speaker.
    private void drawMetal(Canvas canvas, float width, float height, RectF plate, RectF frame) {
        paint.setShader(new LinearGradient(0, 0, 0, height, Color.rgb(34, 36, 40), Color.rgb(20, 21, 24), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        // Twill weave: short diagonal tiles alternating direction, drawn once as a tile.
        if (weave == null) {
            int cell = Math.max(4, Math.round(dp(6)));
            android.graphics.Bitmap tile = android.graphics.Bitmap.createBitmap(cell * 2, cell * 2, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas t = new Canvas(tile);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStrokeWidth(dp(2.2f));
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    float x = c * cell;
                    float y = r * cell;
                    t.save();
                    t.clipRect(x, y, x + cell, y + cell);
                    if (((r + c) & 1) == 0) {
                        line.setColor(Color.argb(46, 255, 255, 255));
                        t.drawLine(x - 1, y + cell + 1, x + cell + 1, y - 1, line);
                    } else {
                        line.setColor(Color.argb(70, 0, 0, 0));
                        t.drawLine(x - 1, y - 1, x + cell + 1, y + cell + 1, line);
                    }
                    t.restore();
                }
            }
            weave = new android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        paint.setShader(weave);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        // A soft sheen across the weave.
        paint.setShader(new LinearGradient(0, 0, width, height,
                new int[]{Color.argb(0, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                new float[]{0.2f, 0.45f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        // Aluminium rim round the whole case.
        RectF rim = new RectF(dp(2.5f), dp(2.5f), width - dp(2.5f), height - dp(2.5f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setShader(brushed(0, 0, width, height));
        canvas.drawRoundRect(rim, dp(20), dp(20), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        strokeRoundRect(canvas, new RectF(dp(5), dp(5), width - dp(5), height - dp(5)), dp(18), dp(1), Color.argb(120, 0, 0, 0));
        float m = dp(15);
        hexScrew(canvas, m, m);
        hexScrew(canvas, width - m, m);
        hexScrew(canvas, m, height - m);
        hexScrew(canvas, width - m, height - m);
        // Speaker holes in the lower-right corner.
        paint.setColor(Color.argb(200, 8, 8, 10));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 6; c++) {
                canvas.drawCircle(width - dp(36) - c * dp(7) + (r & 1) * dp(3.5f), height - dp(16) - r * dp(7), dp(1.8f), paint);
            }
        }
        if (frame != null) {
            float radius = dp(10);
            paint.setColor(Color.argb(110, 0, 0, 0));
            canvas.drawRoundRect(new RectF(frame.left, frame.top + dp(3), frame.right, frame.bottom + dp(5)), radius, radius, paint);
            paint.setShader(brushed(frame.left, frame.top, frame.right, frame.bottom));
            canvas.drawRoundRect(frame, radius, radius, paint);
            paint.setShader(null);
            // Brushed grain.
            canvas.save();
            Path clip = new Path();
            clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setStrokeWidth(dp(0.5f));
            Random grain = new Random(7);
            for (float y = frame.top; y < frame.bottom; y += dp(1.5f)) {
                paint.setColor(grain.nextBoolean() ? Color.argb(28, 255, 255, 255) : Color.argb(18, 0, 0, 0));
                canvas.drawLine(frame.left, y, frame.right, y, paint);
            }
            canvas.restore();
            strokeRoundRect(canvas, frame, radius, dp(1), Color.argb(150, 255, 255, 255));
            // The screen's black edge and, outside it, the 포인트 색 line.
            float e = frameEdge() * 0.62f;
            RectF edge = new RectF(frame.left + e, frame.top + e, frame.right - e, frame.bottom - e);
            if (trim != 0) {
                strokeRoundRect(canvas, edge, dp(4), dp(3), trim);
            }
            RectF black = new RectF(edge.left + dp(1.5f), edge.top + dp(1.5f), edge.right - dp(1.5f), edge.bottom - dp(1.5f));
            paint.setColor(Color.rgb(12, 12, 14));
            canvas.drawRoundRect(black, dp(3), dp(3), paint);
        }
    }

    private Shader brushed(float left, float top, float right, float bottom) {
        return new LinearGradient(left, top, right, bottom,
                new int[]{Color.rgb(176, 182, 189), Color.rgb(236, 239, 242), Color.rgb(160, 167, 175), Color.rgb(222, 226, 230), Color.rgb(184, 190, 197)},
                new float[]{0, 0.3f, 0.55f, 0.8f, 1}, Shader.TileMode.CLAMP);
    }

    // A recessed hex socket screw.
    private void hexScrew(Canvas canvas, float x, float y) {
        float r = dp(5.5f);
        paint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawCircle(x, y + dp(0.8f), r + dp(1), paint);
        paint.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 1.5f,
                new int[]{Color.rgb(240, 242, 244), Color.rgb(176, 183, 190), Color.rgb(120, 128, 136)}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
        Path hex = new Path();
        float h = r * 0.48f;
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 3 * i;
            float px = x + (float) Math.cos(a) * h;
            float py = y + (float) Math.sin(a) * h;
            if (i == 0) {
                hex.moveTo(px, py);
            } else {
                hex.lineTo(px, py);
            }
        }
        hex.close();
        paint.setColor(Color.rgb(40, 43, 47));
        canvas.drawPath(hex, paint);
    }

    // Neon night: a near-black body; light only along the edges and round the screen, so
    // nothing busy sits behind the controls.
    private void drawNeon(Canvas canvas, float width, float height, RectF frame) {
        paint.setShader(new RadialGradient(width / 2, 0, Math.max(width, height),
                Color.rgb(21, 23, 34), Color.rgb(8, 9, 13), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        int screenLine;
        if (neon == NEON_SIGN) {
            // A matt wall with faint joints, and a glass tube with a white-hot core.
            canvas.drawColor(Color.rgb(21, 22, 26));
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(7, 255, 255, 255));
            for (float y = dp(26); y < height; y += dp(26)) {
                canvas.drawLine(0, y, width, y, paint);
            }
            for (float x = dp(52); x < width; x += dp(52)) {
                canvas.drawLine(x, 0, x, height, paint);
            }
            int pink = Color.rgb(244, 114, 182);
            float glow = Math.max(width, height) * 0.5f;
            paint.setShader(new RadialGradient(width / 2, height * 0.3f, glow, withAlpha(pink, 26), withAlpha(pink, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            RectF tube = new RectF(dp(14), dp(14), width - dp(14), height - dp(14));
            neonTube(canvas, tube, dp(22), pink);
            paint.setColor(Color.rgb(63, 63, 70));
            for (float x : new float[]{dp(60), width - dp(70)}) {
                canvas.drawRoundRect(new RectF(x, dp(11), x + dp(10), dp(17)), dp(2), dp(2), paint);
                canvas.drawRoundRect(new RectF(x, height - dp(17), x + dp(10), height - dp(11)), dp(2), dp(2), paint);
            }
            screenLine = Color.rgb(56, 189, 248);
        } else if (neon == NEON_DUO) {
            int red = Color.rgb(239, 68, 68);
            int blue = Color.rgb(59, 130, 246);
            paint.setShader(new LinearGradient(0, 0, width, height,
                    new int[]{withAlpha(red, 56), withAlpha(red, 0), withAlpha(blue, 0), withAlpha(blue, 62)}, new float[]{0, 0.25f, 0.75f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            Shader rimShader = new LinearGradient(0, 0, width, height,
                    new int[]{red, withAlpha(red, 0), withAlpha(blue, 0), blue}, new float[]{0, 0.4f, 0.6f, 1}, Shader.TileMode.CLAMP);
            paint.setShader(rimShader);
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 3; i >= 0; i--) {
                paint.setStrokeWidth(dp(2 + i * 4));
                paint.setAlpha(i == 0 ? 255 : 30);
                canvas.drawRoundRect(new RectF(0, 0, width, height), dp(22), dp(22), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setAlpha(255);
            screenLine = 0;
        } else if (neon == NEON_UNDER) {
            int purple = Color.rgb(168, 85, 247);
            float reach = landscape ? dp(200) : dp(260);
            paint.setShader(new RadialGradient(width / 2, height + dp(40), Math.max(width * 0.6f, reach),
                    new int[]{withAlpha(purple, 115), Color.argb(38, 236, 72, 153), Color.argb(0, 0, 0, 0)}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 4; i >= 0; i--) {
                paint.setStrokeWidth(dp(3 + i * 5));
                paint.setColor(i == 0 ? Color.rgb(233, 213, 255) : withAlpha(i > 2 ? Color.rgb(236, 72, 153) : purple, 26));
                canvas.drawLine(dp(30), height - dp(3), width - dp(30), height - dp(3), paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
            screenLine = Color.rgb(192, 132, 252);
        } else {
            // Edge glow in the mode's colour, inside the body's edge.
            int edge = hue(accent, 0, 0.75f, 1f);
            for (int i = 6; i >= 1; i--) {
                float inset = dp(i * 2.5f);
                strokeRoundRect(canvas, new RectF(inset, inset, width - inset, height - inset), dp(22), dp(3), withAlpha(edge, 10 + (6 - i) * 6));
            }
            strokeRoundRect(canvas, new RectF(dp(1), dp(1), width - dp(1), height - dp(1)), dp(22), dp(1.5f), edge);
            screenLine = edge;
        }
        if (frame != null) {
            float radius = dp(7);
            paint.setColor(Color.BLACK);
            canvas.drawRoundRect(frame, radius, radius, paint);
            RectF line = new RectF(frame.left + dp(1.5f), frame.top + dp(1.5f), frame.right - dp(1.5f), frame.bottom - dp(1.5f));
            if (screenLine != 0) {
                neonTube(canvas, line, radius, screenLine);
            } else {
                // Red · blue: red light from the upper left, blue from the lower right.
                paint.setStyle(Paint.Style.STROKE);
                paint.setShader(new LinearGradient(frame.left, frame.top, frame.right, frame.bottom,
                        Color.rgb(239, 68, 68), Color.rgb(59, 130, 246), Shader.TileMode.CLAMP));
                for (int i = 3; i >= 0; i--) {
                    paint.setStrokeWidth(dp(1.5f + i * 3));
                    paint.setAlpha(i == 0 ? 255 : 34);
                    canvas.drawRoundRect(line, radius, radius, paint);
                }
                paint.setShader(null);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    // A neon tube: wide faint halo, the coloured glass, and a white-hot core.
    private void neonTube(Canvas canvas, RectF rect, float radius, int color) {
        neonStroke(canvas, rect, radius, color);
        strokeRoundRect(canvas, rect, radius, dp(0.8f), Color.argb(200, 255, 255, 255));
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
