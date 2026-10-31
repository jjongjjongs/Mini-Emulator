package com.example.minigba;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.List;

// The picture of one console's pad for "게임패드 버튼 설정", traced from photographs of the real
// pads. Everything is laid out in a 560 x 330 design space; the view scales it to fit. The
// sheet draws the body and its decorations (screens, sticks, grilles) and says where each
// mappable button sits.
final class PadSheet {
    static final float W = 560;
    static final float H = 330;

    static final int KIND_CIRCLE = 0;
    static final int KIND_PILL = 1;
    static final int KIND_ARM = 2;

    static final int TAG_BELOW = 0;
    static final int TAG_RIGHT = 1;
    static final int TAG_LEFT = 2;
    static final int TAG_ABOVE = 3;

    static final class Spot {
        float x;
        float y;
        float r;
        float w;
        float h;
        float rotation;
        int kind = KIND_CIRCLE;
        int tag = TAG_BELOW;
        int fill;
        int edge;
        int ink;
        float textSize;
        String glyph;

        Spot at(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }

        Spot circle(float r, int fill, int edge, int ink) {
            this.kind = KIND_CIRCLE;
            this.r = r;
            this.fill = fill;
            this.edge = edge;
            this.ink = ink;
            this.textSize = Math.max(7, r * 0.72f);
            return this;
        }

        Spot pill(float w, float h, float rotation, int fill, int edge, int ink) {
            this.kind = KIND_PILL;
            this.w = w;
            this.h = h;
            this.rotation = rotation;
            this.r = Math.max(w, h) / 2;
            this.fill = fill;
            this.edge = edge;
            this.ink = ink;
            this.textSize = Math.min(9, h * 0.62f);
            return this;
        }

        Spot arm(float side, int fill, int edge, int ink) {
            this.kind = KIND_ARM;
            this.w = side;
            this.h = side;
            this.r = side / 2;
            this.fill = fill;
            this.edge = edge;
            this.ink = ink;
            this.textSize = side * 0.5f;
            return this;
        }

        Spot tag(int direction) {
            this.tag = direction;
            return this;
        }

        Spot size(float textSize) {
            this.textSize = textSize;
            return this;
        }

        Spot glyph(String text) {
            this.glyph = text;
            return this;
        }
    }

    private static final int DARK_BTN = Color.rgb(27, 30, 37);
    private static final int DARK_EDGE = Color.rgb(90, 96, 110);
    private static final int LIGHT_INK = Color.rgb(229, 231, 235);
    private static final int MUTED_INK = Color.rgb(142, 149, 163);
    private static final int GREY_BTN = Color.rgb(61, 64, 73);
    private static final int GREY_EDGE = Color.rgb(38, 40, 48);
    private static final int WHITE_BTN = Color.rgb(214, 216, 222);
    private static final int WHITE_EDGE = Color.rgb(166, 170, 181);
    private static final int BLUE = Color.rgb(44, 95, 209);
    private static final int RED = Color.rgb(209, 45, 45);
    private static final int GREEN = Color.rgb(43, 154, 75);
    private static final int YELLOW = Color.rgb(240, 196, 25);
    private static final int PURPLE = Color.rgb(143, 123, 214);
    private static final int ORANGE = Color.rgb(232, 118, 45);
    private static final int INK_DARK = Color.rgb(51, 51, 51);

    final String style;
    private final boolean rightNub;
    private final boolean shoulders;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();

    // style: ps, sfc, n64, md, ss, dc, gba, fc, arcade. targets: the console's buttons, which
    // decide the variants (PSP has no second nub, the Game Boy no shoulders).
    PadSheet(String style, List<PadMapping.Target> targets) {
        this.style = style;
        boolean nub = false;
        boolean shoulder = false;
        for (PadMapping.Target target : targets) {
            if (target.group == PadMapping.GROUP_STICK_RIGHT) nub = true;
            if (target.group == PadMapping.GROUP_SHOULDER_LEFT) shoulder = true;
        }
        this.rightNub = nub;
        this.shoulders = shoulder;
        this.stroke.setStyle(Paint.Style.STROKE);
        this.text.setTypeface(Typeface.DEFAULT_BOLD);
        this.text.setTextAlign(Paint.Align.CENTER);
        prepare(targets);
        buildBody();
    }

    // Chips with the controller input: light text on dark, whatever the pad's colour.
    int chipBackground() {
        return Color.rgb(16, 18, 24);
    }

    private void buildBody() {
        this.body.reset();
        switch (this.style) {
            case "ps":
                this.body.addRoundRect(new RectF(40, 56, 520, 250), 26, 26, Path.Direction.CW);
                break;
            case "sfc":
                this.body.addRoundRect(new RectF(74, 104, 486, 236), 66, 66, Path.Direction.CW);
                break;
            case "n64":
                svg("M160 60 Q280 44 400 60 Q452 66 460 116 L466 150 Q470 176 448 190 L440 196 L462 262 Q470 296 440 302 Q412 306 400 278 L372 204 Q362 186 340 182 Q320 180 310 200 L300 288 Q296 314 280 314 Q264 314 260 288 L250 200 Q240 180 220 182 Q198 186 188 204 L160 278 Q148 306 120 302 Q90 296 98 262 L120 196 L112 190 Q90 176 94 150 L100 116 Q108 66 160 60 Z");
                break;
            case "md":
                svg("M150 82 Q280 60 410 82 Q470 92 486 150 L500 196 Q512 250 462 262 Q426 268 404 240 L388 220 Q372 206 350 208 L210 208 Q188 206 172 220 L156 240 Q134 268 98 262 Q48 250 60 196 L74 150 Q90 92 150 82 Z");
                break;
            case "ss":
                svg("M140 104 Q280 84 420 104 Q480 112 496 170 L506 206 Q516 256 466 266 Q432 272 410 246 L392 224 Q376 210 352 212 L208 212 Q184 210 168 224 L150 246 Q128 272 94 266 Q44 256 54 206 L64 170 Q80 112 140 104 Z");
                break;
            case "dc":
                svg("M130 40 H430 Q486 40 492 96 L494 230 Q494 276 462 300 Q430 322 400 300 Q372 280 352 230 L318 190 Q300 174 288 200 L280 214 L272 200 Q260 174 242 190 L208 230 Q188 280 160 300 Q130 322 98 300 Q66 276 66 230 L68 96 Q74 40 130 40 Z");
                break;
            case "gba":
                svg("M120 60 H440 Q500 60 500 120 V196 Q500 312 388 312 Q330 312 280 302 Q230 312 172 312 Q60 312 60 196 V120 Q60 60 120 60 Z");
                break;
            case "fc":
                this.body.addRoundRect(new RectF(70, 86, 490, 250), 10, 10, Path.Direction.CW);
                break;
            case "gc":
                this.body.addRoundRect(new RectF(52, 66, 508, 280), 100, 100, Path.Direction.CW);
                break;
            case "wiiu":
                this.body.addRoundRect(new RectF(36, 66, 524, 286), 48, 48, Path.Direction.CW);
                break;
            case "wiinc":
                // The nunchuk on the left, the remote standing on the right.
                this.body.addRoundRect(new RectF(70, 50, 170, 300), 50, 50, Path.Direction.CW);
                this.body.addRoundRect(new RectF(330, 14, 430, 322), 46, 46, Path.Direction.CW);
                break;
            case "wiisw":
                this.body.addRoundRect(new RectF(40, 104, 520, 236), 60, 60, Path.Direction.CW);
                break;
            case "wiicc":
                this.body.addRoundRect(new RectF(56, 70, 504, 284), 92, 92, Path.Direction.CW);
                break;
            default:
                this.body.addRoundRect(new RectF(40, 70, 520, 270), 14, 14, Path.Direction.CW);
                break;
        }
    }

    // A small reader for the absolute M / L / H / V / Q / Z path data the sheets are traced in.
    private void svg(String data) {
        String[] tokens = data.replace(",", " ").replaceAll("([MLHVQZ])", " $1 ").trim().split("\\s+");
        float x = 0;
        float y = 0;
        char command = 'M';
        int k = 0;
        while (k < tokens.length) {
            String token = tokens[k];
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                command = token.charAt(0);
                k++;
                if (command == 'Z') {
                    this.body.close();
                }
                continue;
            }
            switch (command) {
                case 'M':
                    x = Float.parseFloat(tokens[k]);
                    y = Float.parseFloat(tokens[k + 1]);
                    this.body.moveTo(x, y);
                    k += 2;
                    command = 'L';
                    break;
                case 'L':
                    x = Float.parseFloat(tokens[k]);
                    y = Float.parseFloat(tokens[k + 1]);
                    this.body.lineTo(x, y);
                    k += 2;
                    break;
                case 'H':
                    x = Float.parseFloat(tokens[k]);
                    this.body.lineTo(x, y);
                    k += 1;
                    break;
                case 'V':
                    y = Float.parseFloat(tokens[k]);
                    this.body.lineTo(x, y);
                    k += 1;
                    break;
                case 'Q': {
                    float cx = Float.parseFloat(tokens[k]);
                    float cy = Float.parseFloat(tokens[k + 1]);
                    x = Float.parseFloat(tokens[k + 2]);
                    y = Float.parseFloat(tokens[k + 3]);
                    this.body.quadTo(cx, cy, x, y);
                    k += 4;
                    break;
                }
                default:
                    k++;
            }
        }
    }

    // ---- Drawing the body and its decorations, in design units ----

    void drawBody(Canvas canvas) {
        int top;
        int bottom;
        int edge;
        switch (this.style) {
            case "sfc": top = Color.rgb(236, 238, 242); bottom = Color.rgb(194, 198, 207); edge = Color.rgb(244, 245, 248); break;
            case "n64": top = Color.rgb(201, 204, 212); bottom = Color.rgb(143, 148, 160); edge = Color.rgb(223, 226, 232); break;
            case "md": top = Color.rgb(59, 63, 76); bottom = Color.rgb(26, 28, 35); edge = Color.rgb(85, 91, 104); break;
            case "ss":
            case "dc": top = Color.rgb(246, 246, 248); bottom = Color.rgb(207, 210, 218); edge = Color.rgb(229, 231, 236); break;
            case "gba": top = Color.rgb(93, 99, 201); bottom = Color.rgb(58, 63, 155); edge = Color.rgb(138, 147, 230); break;
            case "fc": top = Color.rgb(200, 48, 46); bottom = Color.rgb(142, 29, 28); edge = Color.rgb(224, 81, 79); break;
            case "arcade": top = Color.rgb(43, 47, 58); bottom = Color.rgb(21, 23, 29); edge = Color.rgb(74, 80, 94); break;
            case "gc": top = Color.rgb(98, 86, 196); bottom = Color.rgb(58, 47, 140); edge = Color.rgb(134, 122, 222); break;
            case "wiiu":
            case "wiinc":
            case "wiisw":
            case "wiicc": top = Color.rgb(246, 247, 249); bottom = Color.rgb(203, 207, 215); edge = Color.rgb(229, 231, 236); break;
            default: top = Color.rgb(58, 62, 72); bottom = Color.rgb(20, 22, 27); edge = Color.rgb(85, 91, 104); break;
        }
        // Shadow under the body.
        this.fill.setShader(null);
        this.fill.setColor(Color.argb(110, 0, 0, 0));
        canvas.save();
        canvas.translate(0, 6);
        canvas.drawPath(this.body, this.fill);
        canvas.restore();
        if (this.style.equals("ps")) {
            // The silver rim of the PSP.
            this.fill.setShader(new LinearGradient(0, 48, 0, 258, Color.rgb(230, 232, 238), Color.rgb(158, 163, 176), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(34, 48, 526, 258), 30, 30, this.fill);
        }
        this.fill.setShader(new LinearGradient(0, 40, 0, 320, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawPath(this.body, this.fill);
        this.fill.setShader(null);
        this.stroke.setColor(edge);
        this.stroke.setStrokeWidth(1.5f);
        canvas.drawPath(this.body, this.stroke);

        switch (this.style) {
            case "ps":
                rect(canvas, 170, 80, 220, 124, 4, Color.rgb(10, 11, 14));
                rect(canvas, 178, 86, 204, 112, 0, Color.rgb(22, 27, 38));
                nub(canvas, 101, 200, 17, true);
                if (this.rightNub) {
                    nub(canvas, 440, 200, 17, true);
                }
                label(canvas, 280, 258, "MINI EMULATOR", 7, Color.rgb(85, 91, 104), 3);
                break;
            case "sfc": {
                // The darker plate under the face buttons.
                Path plate = new Path();
                plate.moveTo(384, 118); plate.lineTo(450, 128); plate.quadTo(480, 136, 476, 166); plate.lineTo(470, 210);
                plate.quadTo(462, 236, 436, 236); plate.lineTo(378, 228); plate.quadTo(350, 220, 348, 198); plate.lineTo(354, 156);
                plate.quadTo(358, 124, 384, 118); plate.close();
                this.fill.setColor(Color.rgb(138, 142, 153));
                canvas.drawPath(plate, this.fill);
                label(canvas, 290, 128, "Nintendo · SUPER FAMICOM", 7, Color.rgb(138, 142, 153), 0);
                break;
            }
            case "n64":
                nub(canvas, 280, 204, 30, false);
                break;
            case "md":
                well(canvas, 150, 158, 44, Color.rgb(18, 20, 25), Color.rgb(58, 63, 76));
                label(canvas, 280, 120, "SEGA", 9, Color.rgb(74, 79, 91), 2);
                break;
            case "ss":
                well(canvas, 150, 176, 40, Color.rgb(214, 216, 222), Color.rgb(183, 187, 198));
                label(canvas, 280, 128, "SEGA", 11, Color.rgb(46, 58, 140), 1);
                break;
            case "dc":
                rect(canvas, 236, 50, 88, 70, 6, Color.rgb(214, 216, 222));
                rect(canvas, 246, 58, 68, 54, 3, Color.rgb(26, 21, 16));
                rect(canvas, 250, 62, 60, 46, 0, Color.rgb(224, 90, 43));
                label(canvas, 280, 90, "VMU", 8, Color.rgb(255, 224, 138), 0);
                stick(canvas, 132, 118, 34);
                stick(canvas, 424, 226, 34);
                this.fill.setColor(Color.rgb(200, 204, 212));
                Path mark = new Path();
                mark.moveTo(270, 196); mark.lineTo(280, 182); mark.lineTo(290, 196); mark.close();
                canvas.drawPath(mark, this.fill);
                break;
            case "gba":
                rect(canvas, 162, 72, 236, 214, 18, Color.rgb(21, 22, 28));
                rect(canvas, 180, 92, 200, 140, 0, Color.rgb(30, 37, 56));
                label(canvas, 280, 166, "GAME BOY", 12, Color.rgb(47, 58, 85), 0);
                label(canvas, 280, 266, "GAME BOY ADVANCE", 7, Color.rgb(138, 147, 230), 0);
                this.stroke.setColor(Color.rgb(58, 63, 155));
                this.stroke.setStrokeWidth(3);
                this.stroke.setStrokeCap(Paint.Cap.ROUND);
                for (int k = 0; k < 4; k++) {
                    canvas.drawLine(430 + k * 2, 238 + k * 10, 480 + k * 2, 228 + k * 10, this.stroke);
                }
                this.stroke.setStrokeCap(Paint.Cap.BUTT);
                break;
            case "fc":
                rect(canvas, 84, 98, 392, 104, 4, Color.rgb(236, 219, 170));
                rect(canvas, 300, 98, 176, 104, 0, Color.rgb(200, 48, 46));
                this.stroke.setColor(Color.rgb(240, 223, 176));
                this.stroke.setStrokeWidth(2);
                canvas.drawLine(84, 214, 476, 214, this.stroke);
                this.stroke.setStrokeWidth(1);
                canvas.drawLine(84, 220, 476, 220, this.stroke);
                label(canvas, 104, 118, "Ⅰ", 12, Color.rgb(142, 29, 28), 0);
                break;
            case "gc":
                // The grey main stick and the yellow C stick (sticks are not remapped here).
                nub(canvas, 140, 146, 30, false);
                well(canvas, 440, 226, 21, Color.rgb(46, 38, 112), Color.rgb(82, 70, 170));
                this.fill.setColor(YELLOW);
                canvas.drawCircle(440, 226, 13, this.fill);
                label(canvas, 440, 229, "C", 8, INK_DARK, 0);
                break;
            case "wiiu":
                rect(canvas, 168, 92, 224, 150, 6, Color.rgb(17, 19, 24));
                rect(canvas, 178, 100, 204, 132, 0, Color.rgb(30, 37, 56));
                label(canvas, 280, 172, "GAMEPAD", 10, Color.rgb(60, 72, 100), 2);
                break;
            case "wiinc":
                nub(canvas, 120, 176, 30, true);
                this.stroke.setColor(Color.rgb(203, 207, 215));
                this.stroke.setStrokeWidth(2);
                canvas.drawLine(170, 176, 330, 176, this.stroke);
                label(canvas, 120, 292, "NUNCHUK", 7, Color.rgb(160, 165, 176), 1);
                label(canvas, 380, 316, "Wii", 9, Color.rgb(160, 165, 176), 0);
                break;
            case "wiisw":
                label(canvas, 490, 174, "Wii", 9, Color.rgb(160, 165, 176), 0);
                break;
            case "wiicc":
                nub(canvas, 216, 228, 26, true);
                nub(canvas, 344, 228, 26, true);
                break;
            case "arcade":
                rect(canvas, 52, 82, 456, 176, 8, Color.rgb(29, 32, 41));
                this.stroke.setColor(Color.argb(60, 42, 95, 214));
                this.stroke.setStrokeWidth(2);
                canvas.drawCircle(150, 176, 70, this.stroke);
                canvas.drawCircle(150, 176, 50, this.stroke);
                well(canvas, 150, 176, 24, Color.rgb(15, 17, 22), Color.rgb(60, 65, 76));
                this.fill.setColor(Color.rgb(42, 45, 54));
                canvas.drawRoundRect(new RectF(146, 124, 154, 176), 3, 3, this.fill);
                this.fill.setShader(new RadialGradient(144, 116, 20, Color.rgb(85, 91, 105), Color.rgb(13, 15, 19), Shader.TileMode.CLAMP));
                canvas.drawCircle(150, 122, 17, this.fill);
                this.fill.setShader(null);
                // The two unused buttons of an eight-button panel.
                well(canvas, 444, 146, 18, Color.rgb(29, 32, 41), Color.rgb(48, 52, 63));
                well(canvas, 444, 196, 18, Color.rgb(29, 32, 41), Color.rgb(48, 52, 63));
                break;
            default:
                break;
        }
    }

    private void rect(Canvas canvas, float x, float y, float w, float h, float radius, int color) {
        this.fill.setColor(color);
        canvas.drawRoundRect(new RectF(x, y, x + w, y + h), radius, radius, this.fill);
    }

    private void well(Canvas canvas, float x, float y, float r, int color, int edge) {
        this.fill.setColor(color);
        canvas.drawCircle(x, y, r, this.fill);
        this.stroke.setColor(edge);
        this.stroke.setStrokeWidth(1.2f);
        canvas.drawCircle(x, y, r, this.stroke);
    }

    // An analog nub (PSP) or stick (N64): picture only.
    private void nub(Canvas canvas, float x, float y, float r, boolean dark) {
        well(canvas, x, y, r, dark ? Color.rgb(15, 17, 22) : Color.rgb(124, 129, 141), dark ? Color.rgb(60, 65, 76) : Color.rgb(95, 100, 112));
        this.fill.setShader(new RadialGradient(x - r * 0.15f, y - r * 0.2f, r, dark ? Color.rgb(90, 96, 110) : Color.rgb(201, 204, 212), dark ? Color.rgb(32, 36, 44) : Color.rgb(158, 163, 175), Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 0.66f, this.fill);
        this.fill.setShader(null);
    }

    // The cream stick of the Dreamcast.
    private void stick(Canvas canvas, float x, float y, float r) {
        well(canvas, x, y, r, Color.rgb(201, 204, 212), Color.rgb(174, 178, 188));
        this.fill.setShader(new RadialGradient(x - r * 0.15f, y - r * 0.2f, r, Color.rgb(243, 238, 228), Color.rgb(207, 199, 182), Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r * 0.7f, this.fill);
        this.fill.setShader(null);
        this.fill.setColor(Color.rgb(216, 208, 191));
        canvas.drawCircle(x, y, r * 0.3f, this.fill);
    }

    private void label(Canvas canvas, float x, float y, String value, float size, int color, float spacing) {
        this.text.setColor(color);
        this.text.setTextSize(size);
        this.text.setLetterSpacing(spacing / Math.max(1, size));
        canvas.drawText(value, x, y, this.text);
        this.text.setLetterSpacing(0);
    }

    // ---- Where each button sits ----

    Spot spot(PadMapping.Target target) {
        Spot spot = new Spot();
        switch (target.group) {
            case PadMapping.GROUP_DPAD: return dpad(target, spot);
            case PadMapping.GROUP_FACE: return face(target, spot);
            case PadMapping.GROUP_C: return cButton(target, spot);
            case PadMapping.GROUP_SHOULDER_LEFT:
            case PadMapping.GROUP_SHOULDER_RIGHT: return shoulder(target, spot);
            case PadMapping.GROUP_MIDDLE: return middle(target, spot);
            case PadMapping.GROUP_STICK_LEFT:
                if (this.style.equals("wiiu")) return spot.at(98, 132).circle(20, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_RIGHT);
                return spot.at(200, 228).circle(11, DARK_BTN, DARK_EDGE, LIGHT_INK);
            case PadMapping.GROUP_STICK_RIGHT:
                if (this.style.equals("wiiu")) return spot.at(462, 236).circle(18, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_LEFT);
                return spot.at(360, 228).circle(11, DARK_BTN, DARK_EDGE, LIGHT_INK);
            default: return spot.at(280, 165).circle(12, DARK_BTN, DARK_EDGE, LIGHT_INK);
        }
    }

    private Spot dpad(PadMapping.Target target, Spot spot) {
        float cx;
        float cy;
        float size;
        int fillColor;
        int edgeColor;
        int ink;
        switch (this.style) {
            case "ps": cx = 101; cy = 131; size = 70; fillColor = DARK_BTN; edgeColor = Color.rgb(74, 79, 91); ink = MUTED_INK; break;
            case "sfc": cx = 153; cy = 181; size = 78; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "n64": cx = 149; cy = 129; size = 74; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "md": cx = 150; cy = 158; size = 72; fillColor = DARK_BTN; edgeColor = Color.rgb(74, 79, 91); ink = MUTED_INK; break;
            case "ss": cx = 150; cy = 176; size = 66; fillColor = WHITE_BTN; edgeColor = WHITE_EDGE; ink = Color.rgb(74, 79, 91); break;
            case "dc": cx = 150; cy = 204; size = 64; fillColor = WHITE_BTN; edgeColor = WHITE_EDGE; ink = Color.rgb(74, 79, 91); break;
            case "gba": cx = 100; cy = 154; size = 76; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "fc": cx = 141; cy = 147; size = 74; fillColor = DARK_BTN; edgeColor = Color.rgb(74, 79, 91); ink = MUTED_INK; break;
            case "gc": cx = 200; cy = 222; size = 62; fillColor = Color.rgb(46, 38, 112); edgeColor = Color.rgb(82, 70, 170); ink = Color.rgb(196, 190, 240); break;
            case "wiiu": cx = 98; cy = 216; size = 66; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "wiinc": cx = 380; cy = 78; size = 66; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "wiisw": cx = 112; cy = 170; size = 70; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            case "wiicc": cx = 140; cy = 156; size = 66; fillColor = GREY_BTN; edgeColor = GREY_EDGE; ink = Color.rgb(185, 190, 201); break;
            default: cx = 150; cy = 176; size = 76; fillColor = Color.rgb(42, 45, 54); edgeColor = Color.rgb(74, 80, 94); ink = MUTED_INK; break;
        }
        float d = size * 0.33f;
        float dx = target.bit == PadMapping.DPAD_LEFT ? -1 : target.bit == PadMapping.DPAD_RIGHT ? 1 : 0;
        float dy = target.bit == PadMapping.DPAD_UP ? -1 : target.bit == PadMapping.DPAD_DOWN ? 1 : 0;
        spot.at(cx + dx * d, cy + dy * d).arm(size * 0.3f, fillColor, edgeColor, ink);
        spot.tag(target.bit == PadMapping.DPAD_UP ? TAG_ABOVE : target.bit == PadMapping.DPAD_DOWN ? TAG_BELOW : target.bit == PadMapping.DPAD_LEFT ? TAG_LEFT : TAG_RIGHT);
        return spot;
    }

    private Spot face(PadMapping.Target target, Spot spot) {
        String l = target.label;
        switch (this.style) {
            case "ps": {
                int ink = l.equals("△") ? Color.rgb(62, 207, 142) : l.equals("○") ? Color.rgb(240, 80, 110) : l.equals("×") ? Color.rgb(96, 165, 250) : Color.rgb(232, 121, 249);
                if (l.equals("△")) return spot.at(457, 104).circle(13, DARK_BTN, DARK_EDGE, ink).tag(TAG_RIGHT);
                if (l.equals("○")) return spot.at(487, 132).circle(13, DARK_BTN, DARK_EDGE, ink).tag(TAG_BELOW);
                if (l.equals("□")) return spot.at(427, 132).circle(13, DARK_BTN, DARK_EDGE, ink).tag(TAG_LEFT);
                return spot.at(457, 160).circle(13, DARK_BTN, DARK_EDGE, ink).tag(TAG_BELOW);
            }
            case "sfc":
                if (l.equals("X")) return spot.at(420, 146).circle(16, BLUE, BLUE, Color.WHITE).tag(TAG_RIGHT);
                if (l.equals("A")) return spot.at(458, 172).circle(16, RED, RED, Color.WHITE).tag(TAG_RIGHT);
                if (l.equals("Y")) return spot.at(382, 172).circle(16, GREEN, GREEN, Color.WHITE).tag(TAG_LEFT);
                return spot.at(420, 206).circle(16, YELLOW, YELLOW, INK_DARK).tag(TAG_BELOW);
            case "n64":
                if (l.equals("A")) return spot.at(372, 140).circle(14, BLUE, BLUE, Color.WHITE).tag(TAG_BELOW);
                return spot.at(340, 112).circle(13, GREEN, GREEN, Color.WHITE).tag(TAG_LEFT);
            case "md": {
                int edge = Color.rgb(138, 143, 155);
                int fillColor = Color.rgb(42, 45, 54);
                int ink = Color.rgb(209, 213, 219);
                switch (l) {
                    case "A": return spot.at(368, 178).circle(15, fillColor, edge, ink).tag(TAG_BELOW);
                    case "B": return spot.at(408, 170).circle(15, fillColor, edge, ink).tag(TAG_BELOW);
                    case "C": return spot.at(448, 162).circle(15, fillColor, edge, ink).tag(TAG_BELOW);
                    case "X": return spot.at(372, 136).circle(11, fillColor, edge, ink).tag(TAG_ABOVE);
                    case "Y": return spot.at(408, 128).circle(11, fillColor, edge, ink).tag(TAG_ABOVE);
                    default: return spot.at(444, 120).circle(11, fillColor, edge, ink).tag(TAG_ABOVE);
                }
            }
            case "ss":
                switch (l) {
                    case "A": return spot.at(372, 186).circle(15, GREEN, GREEN, Color.WHITE).tag(TAG_BELOW);
                    case "B": return spot.at(412, 176).circle(15, YELLOW, YELLOW, INK_DARK).tag(TAG_BELOW);
                    case "C": return spot.at(452, 166).circle(15, BLUE, BLUE, Color.WHITE).tag(TAG_BELOW);
                    case "X": return spot.at(378, 154).circle(11, PURPLE, PURPLE, Color.WHITE).tag(TAG_LEFT);
                    case "Y": return spot.at(414, 144).circle(11, PURPLE, PURPLE, Color.WHITE).tag(TAG_ABOVE);
                    default: return spot.at(450, 134).circle(11, PURPLE, PURPLE, Color.WHITE).tag(TAG_RIGHT);
                }
            case "dc":
                switch (l) {
                    case "Y": return spot.at(400, 98).circle(14, YELLOW, YELLOW, INK_DARK).tag(TAG_RIGHT);
                    case "X": return spot.at(368, 128).circle(14, GREEN, GREEN, Color.WHITE).tag(TAG_LEFT);
                    case "B": return spot.at(432, 128).circle(14, BLUE, BLUE, Color.WHITE).tag(TAG_RIGHT);
                    default: return spot.at(400, 158).circle(14, RED, RED, Color.WHITE).tag(TAG_BELOW);
                }
            case "gba": {
                int fillColor = Color.rgb(201, 203, 217);
                int edge = Color.rgb(138, 147, 230);
                int ink = Color.rgb(58, 63, 155);
                switch (l) {
                    case "X": return spot.at(462, 134).circle(14, fillColor, edge, ink).tag(TAG_ABOVE);
                    case "A": return spot.at(hasFour ? 488 : 478, 162).circle(hasFour ? 14 : 18, fillColor, edge, ink).tag(TAG_RIGHT);
                    case "Y": return spot.at(436, 162).circle(14, fillColor, edge, ink).tag(TAG_LEFT);
                    default: return spot.at(hasFour ? 462 : 434, hasFour ? 190 : 184).circle(hasFour ? 14 : 18, fillColor, edge, ink).tag(TAG_BELOW);
                }
            }
            case "gc":
                switch (l) {
                    case "A": return spot.at(400, 172).circle(22, Color.rgb(16, 185, 129), Color.rgb(5, 150, 105), Color.WHITE).tag(TAG_RIGHT);
                    case "B": return spot.at(350, 206).circle(13, Color.rgb(239, 68, 68), Color.rgb(185, 28, 28), Color.WHITE).tag(TAG_BELOW);
                    case "X": return spot.at(452, 150).circle(13, Color.rgb(209, 213, 219), Color.rgb(156, 163, 175), INK_DARK).tag(TAG_RIGHT);
                    default: return spot.at(394, 124).circle(13, Color.rgb(209, 213, 219), Color.rgb(156, 163, 175), INK_DARK).tag(TAG_LEFT);
                }
            case "wiiu":
                switch (l) {
                    case "X": return spot.at(458, 124).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_ABOVE);
                    case "A": return spot.at(486, 152).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_BELOW);
                    case "Y": return spot.at(430, 152).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_LEFT);
                    default: return spot.at(458, 180).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_BELOW);
                }
            case "wiinc":
                switch (l) {
                    case "A": return spot.at(380, 146).circle(18, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_LEFT);
                    case "B": return spot.at(420, 146).pill(10, 34, 0, Color.rgb(160, 165, 176), Color.rgb(130, 135, 146), INK_DARK).size(7).tag(TAG_RIGHT);
                    case "C": return spot.at(120, 100).pill(40, 12, 0, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_RIGHT);
                    case "Z": return spot.at(120, 76).pill(46, 14, 0, Color.rgb(160, 165, 176), Color.rgb(130, 135, 146), INK_DARK).tag(TAG_RIGHT);
                    case "1": return spot.at(380, 238).circle(13, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_RIGHT);
                    default: return spot.at(380, 272).circle(13, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_RIGHT);
                }
            case "wiisw":
                switch (l) {
                    case "1": return spot.at(392, 170).circle(18, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_BELOW);
                    case "2": return spot.at(452, 170).circle(18, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_BELOW);
                    case "A": return spot.at(326, 146).circle(11, WHITE_BTN, WHITE_EDGE, INK_DARK).tag(TAG_ABOVE);
                    default: return spot.at(326, 206).pill(34, 10, 0, Color.rgb(160, 165, 176), Color.rgb(130, 135, 146), INK_DARK).size(7).tag(TAG_BELOW);
                }
            case "wiicc":
                switch (l) {
                    case "x": return spot.at(420, 128).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_ABOVE);
                    case "a": return spot.at(448, 156).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_RIGHT);
                    case "y": return spot.at(392, 156).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_LEFT);
                    default: return spot.at(420, 184).circle(13, DARK_BTN, DARK_EDGE, LIGHT_INK).tag(TAG_BELOW);
                }
            case "fc": {
                int ink = Color.rgb(209, 213, 219);
                // Right button first in the list (A / 2 / I), left second (B / 1 / II).
                if (faceIndex(target) == 0) return spot.at(440, 176).circle(18, Color.rgb(21, 22, 27), Color.rgb(58, 61, 70), ink).tag(TAG_BELOW);
                return spot.at(384, 176).circle(18, Color.rgb(21, 22, 27), Color.rgb(58, 61, 70), ink).tag(TAG_BELOW);
            }
            default:
                switch (l) {
                    case "A": return spot.at(300, 196).circle(18, RED, RED, Color.WHITE).tag(TAG_BELOW);
                    case "B": return spot.at(348, 184).circle(18, YELLOW, YELLOW, INK_DARK).tag(TAG_BELOW);
                    case "C": return spot.at(396, 184).circle(18, GREEN, GREEN, Color.WHITE).tag(TAG_BELOW);
                    case "D": return spot.at(300, 146).circle(18, BLUE, BLUE, Color.WHITE).tag(TAG_ABOVE);
                    case "E": return spot.at(348, 134).circle(18, PURPLE, PURPLE, Color.WHITE).tag(TAG_ABOVE);
                    default: return spot.at(396, 134).circle(18, ORANGE, ORANGE, Color.WHITE).tag(TAG_ABOVE);
                }
        }
    }

    private boolean hasFour;
    private int faceCount;
    private final java.util.List<PadMapping.Target> faces = new java.util.ArrayList<>();

    // Called once with the full list so two- and four-button variants place themselves.
    void prepare(List<PadMapping.Target> targets) {
        this.faces.clear();
        for (PadMapping.Target target : targets) {
            if (target.group == PadMapping.GROUP_FACE) {
                this.faces.add(target);
            }
        }
        this.faceCount = this.faces.size();
        this.hasFour = this.faceCount >= 4;
    }

    private int faceIndex(PadMapping.Target target) {
        return this.faces.indexOf(target);
    }

    private Spot cButton(PadMapping.Target target, Spot spot) {
        String l = target.label;
        if (l.contains("▲")) return spot.at(418, 94).circle(10, YELLOW, YELLOW, INK_DARK).glyph("▲").tag(TAG_LEFT);
        if (l.contains("▶")) return spot.at(442, 116).circle(11, YELLOW, YELLOW, INK_DARK).glyph("▶").tag(TAG_RIGHT);
        if (l.contains("▼")) return spot.at(418, 140).circle(11, YELLOW, YELLOW, INK_DARK).glyph("▼").tag(TAG_BELOW);
        return spot.at(394, 116).circle(11, YELLOW, YELLOW, INK_DARK).glyph("◀").tag(TAG_LEFT);
    }

    private Spot shoulder(PadMapping.Target target, Spot spot) {
        boolean left = target.group == PadMapping.GROUP_SHOULDER_LEFT;
        String l = target.label;
        switch (this.style) {
            case "ps": {
                boolean outer = l.equals("L2") || l.equals("R2");
                float x = left ? (outer ? 78 : 128) : (outer ? 482 : 432);
                if (!this.rightNub) {
                    x = left ? 100 : 460;
                }
                return spot.at(x, 72).pill(44, 16, 0, Color.rgb(31, 34, 40), DARK_EDGE, LIGHT_INK);
            }
            case "sfc": return spot.at(left ? 150 : 410, 120).pill(60, 16, 0, Color.rgb(184, 188, 198), Color.rgb(143, 148, 160), INK_DARK);
            case "n64":
                if (l.equals("Z")) return spot.at(280, 264).pill(44, 16, 0, Color.rgb(174, 179, 191), Color.rgb(143, 148, 160), INK_DARK);
                return spot.at(left ? 180 : 380, 77).pill(60, 15, 0, Color.rgb(174, 179, 191), Color.rgb(143, 148, 160), INK_DARK);
            case "ss": return spot.at(left ? 132 : 400, left ? 119 : 115).pill(56, 15, 0, Color.rgb(217, 74, 122), Color.rgb(176, 58, 98), Color.WHITE);
            case "dc": return spot.at(left ? 112 : 448, 59).pill(56, 15, 0, WHITE_BTN, WHITE_EDGE, INK_DARK);
            case "gba": return spot.at(left ? 119 : 441, 77).pill(58, 15, 0, Color.rgb(74, 79, 159), Color.rgb(125, 132, 216), LIGHT_INK);
            case "gc":
                if (l.equals("Z")) return spot.at(440, 104).pill(40, 12, 0, Color.rgb(139, 92, 246), Color.rgb(109, 40, 217), Color.WHITE).tag(TAG_RIGHT);
                return spot.at(left ? 170 : 380, 86).pill(72, 15, 0, Color.rgb(124, 112, 214), Color.rgb(90, 78, 180), Color.WHITE);
            case "wiiu":
                if (l.startsWith("Z")) return spot.at(left ? 92 : 468, 80).pill(48, 13, 0, Color.rgb(203, 213, 225), Color.rgb(148, 163, 184), INK_DARK).tag(TAG_ABOVE);
                return spot.at(left ? 146 : 414, 82).pill(38, 10, 0, Color.rgb(226, 232, 240), Color.rgb(148, 163, 184), INK_DARK).tag(TAG_ABOVE);
            case "wiinc":
                // Shake the remote (right) or the nunchuk (left).
                if (left) return spot.at(120, 270).pill(58, 13, 0, Color.rgb(253, 186, 116), Color.rgb(234, 88, 12), INK_DARK).size(6.5f).glyph("흔들기").tag(TAG_RIGHT);
                return spot.at(380, 302).pill(58, 13, 0, Color.rgb(253, 186, 116), Color.rgb(234, 88, 12), INK_DARK).size(6.5f).tag(TAG_RIGHT);
            case "wiisw": return spot.at(240, 210).pill(58, 13, 0, Color.rgb(253, 186, 116), Color.rgb(234, 88, 12), INK_DARK).size(6.5f).tag(TAG_BELOW);
            case "wiicc":
                if (l.startsWith("Z")) return spot.at(left ? 212 : 348, 90).pill(40, 11, 0, Color.rgb(226, 232, 240), Color.rgb(148, 163, 184), INK_DARK).tag(TAG_ABOVE);
                return spot.at(left ? 150 : 410, 88).pill(58, 14, 0, Color.rgb(203, 213, 225), Color.rgb(148, 163, 184), INK_DARK).tag(TAG_ABOVE);
            default: return spot.at(left ? 120 : 440, 70).pill(56, 15, 0, DARK_BTN, DARK_EDGE, LIGHT_INK);
        }
    }

    private Spot middle(PadMapping.Target target, Spot spot) {
        String l = target.label;
        boolean start = l.equals("START") || l.equals("RUN") || l.equals("OPTION");
        switch (this.style) {
            case "ps": return spot.at(start ? 306 : 254, 228).pill(36, 13, 0, DARK_BTN, DARK_EDGE, MUTED_INK);
            case "sfc": return spot.at(start ? 316 : 262, 176).pill(36, 12, -25, Color.rgb(74, 78, 89), Color.rgb(74, 78, 89), LIGHT_INK);
            case "n64": return spot.at(280, 118).circle(11, RED, RED, Color.WHITE).size(5.5f).tag(TAG_BELOW);
            case "md": return spot.at(start ? 302 : 262, 176).pill(32, 10, -30, start ? BLUE : DARK_BTN, Color.rgb(106, 113, 128), LIGHT_INK).size(5.5f);
            case "ss": return spot.at(280, 201).pill(32, 11, 0, Color.rgb(224, 69, 123), Color.rgb(176, 58, 98), Color.WHITE).size(6);
            case "dc": return spot.at(336, 166).circle(9, RED, Color.rgb(158, 31, 31), Color.WHITE).size(4.5f).tag(TAG_BELOW);
            case "gba": return spot.at(start ? 118 : 108, start ? 224 : 250).pill(36, 11, -25, Color.rgb(158, 163, 207), Color.rgb(138, 147, 230), Color.rgb(30, 33, 90)).size(6).tag(TAG_RIGHT);
            case "fc": return spot.at(start ? 278 : 226, 176).pill(40, 13, 0, Color.rgb(142, 29, 28), Color.rgb(107, 20, 19), Color.rgb(240, 223, 176));
            case "gc": return spot.at(280, 150).pill(30, 11, 0, Color.rgb(46, 38, 112), Color.rgb(82, 70, 170), Color.rgb(196, 190, 240)).size(5.5f);
            case "wiiu":
                if (l.equals("−")) return spot.at(140, 266).pill(22, 10, 0, Color.rgb(156, 163, 175), Color.rgb(107, 114, 128), INK_DARK).tag(TAG_RIGHT);
                return spot.at(420, 266).pill(22, 10, 0, Color.rgb(156, 163, 175), Color.rgb(107, 114, 128), INK_DARK).tag(TAG_LEFT);
            case "wiinc":
            case "wiisw":
            case "wiicc": {
                float y = this.style.equals("wiinc") ? 196 : this.style.equals("wiisw") ? 150 : 148;
                float cx = this.style.equals("wiinc") ? 380 : this.style.equals("wiisw") ? 240 : 280;
                if (l.equals("HOME")) return spot.at(cx, y).circle(9, Color.rgb(96, 165, 250), Color.rgb(59, 130, 246), Color.WHITE).glyph("⌂").tag(TAG_BELOW);
                boolean minus = l.equals("−");
                return spot.at(cx + (minus ? -28 : 28), y).pill(18, 9, 0, Color.rgb(156, 163, 175), Color.rgb(107, 114, 128), INK_DARK).tag(minus ? TAG_LEFT : TAG_RIGHT);
            }
            default: return spot.at(start ? 307 : 253, 78).pill(34, 10, 0, Color.rgb(15, 17, 22), Color.rgb(74, 80, 94), MUTED_INK);
        }
    }
}
