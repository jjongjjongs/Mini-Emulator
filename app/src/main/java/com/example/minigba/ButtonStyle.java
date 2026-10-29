package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * How the on-screen buttons look: a colour theme, the shape of the round buttons, whether
 * they carry their letter, how big each group is and what happens when one is pressed.
 *
 * Saved per console (or per game when "이 게임에만" is on) and per screen direction under
 * "btnstyle:&lt;scope&gt;:&lt;P|L&gt;" as one comma list, so a missing entry means the defaults.
 */
final class ButtonStyle {
    static final int THEME_DEFAULT = 0;
    static final int THEME_PRIMARY = 1;
    static final int THEME_WHITE = 2;
    static final int THEME_BLACK = 3;
    static final int THEME_OUTLINE = 4;
    static final int THEME_NEON = 5;
    static final int THEME_PASTEL = 6;
    static final int THEME_CUSTOM = 7;
    static final String[] THEME_NAMES = {"기본 (케이스 색)", "원색 4색", "화이트", "블랙", "윤곽선만", "네온", "파스텔", "내 색 고르기"};

    static final int SHAPE_CIRCLE = 0;
    static final int SHAPE_ROUNDED = 1;
    static final int SHAPE_SQUARE = 2;
    static final String[] SHAPE_NAMES = {"● 원", "▢ 둥근 네모", "■ 네모"};

    static final int LABEL_SHOW = 0;
    static final int LABEL_HIDE = 1;
    static final int LABEL_SMALL = 2;
    static final String[] LABEL_NAMES = {"글자 보임", "글자 숨김", "글자 작게"};

    /** Button groups whose size is set separately. */
    static final int GROUP_NONE = -1;
    static final int GROUP_DPAD = 0;
    static final int GROUP_ACTION = 1;
    static final int GROUP_STICK = 2;
    static final int GROUP_SHOULDER = 3;
    static final String[] GROUP_NAMES = {"방향키", "A · B (X · Y)", "스틱", "L · R · 시작"};

    /** Roles a colour theme gives a colour to. */
    static final int ROLE_DPAD = 0;
    static final int ROLE_A = 1;
    static final int ROLE_B = 2;
    static final int ROLE_X = 3;
    static final int ROLE_Y = 4;
    static final int ROLE_OTHER = 5;
    static final int ROLE_PILL = 6;

    static final int MIN_SIZE = 50;
    static final int MAX_SIZE = 150;

    int theme = THEME_DEFAULT;
    int shape = SHAPE_CIRCLE;
    int label = LABEL_SHOW;
    /** Percent: everything, then one per group. */
    int sizeAll = 100;
    final int[] sizes = {100, 100, 100, 100};
    boolean glow = true;
    boolean bounce = false;
    /** "내 색 고르기": 방향키, A·B·X·Y, L·R·시작. */
    final int[] custom = {Color.rgb(255, 255, 255), Color.rgb(255, 122, 0), Color.rgb(107, 77, 230)};

    ButtonStyle copy() {
        ButtonStyle out = new ButtonStyle();
        out.theme = theme;
        out.shape = shape;
        out.label = label;
        out.sizeAll = sizeAll;
        System.arraycopy(sizes, 0, out.sizes, 0, sizes.length);
        out.glow = glow;
        out.bounce = bounce;
        System.arraycopy(custom, 0, out.custom, 0, custom.length);
        return out;
    }

    boolean isDefault() {
        return serialize().equals(new ButtonStyle().serialize());
    }

    /** Whether the drawing differs from the built-in look (sizes alone do not count). */
    boolean themed() {
        return theme != THEME_DEFAULT || shape != SHAPE_CIRCLE || label != LABEL_SHOW || bounce || !glow;
    }

    /** The scale a control in this group gets, 1 for the groups that have none. */
    float scale(int group) {
        if (group < 0) {
            return 1.0f;
        }
        return sizeAll / 100.0f * sizes[group] / 100.0f;
    }

    // ---- colours ----

    boolean outline() {
        return theme == THEME_OUTLINE;
    }

    boolean neon() {
        return theme == THEME_NEON;
    }

    /** The body colour for a role, or 0 when the theme keeps the built-in colour. */
    int color(int role) {
        switch (theme) {
            case THEME_PRIMARY:
                switch (role) {
                    case ROLE_A: return Color.rgb(227, 38, 58);
                    case ROLE_B: return Color.rgb(248, 179, 26);
                    case ROLE_X: return Color.rgb(47, 111, 224);
                    case ROLE_Y: return Color.rgb(34, 179, 90);
                    case ROLE_OTHER: return Color.rgb(58, 58, 63);
                    default: return Color.rgb(42, 44, 51);
                }
            case THEME_WHITE:
                return role == ROLE_DPAD ? Color.rgb(228, 230, 236) : Color.rgb(242, 243, 247);
            case THEME_BLACK:
                return role == ROLE_DPAD ? Color.rgb(26, 27, 31) : Color.rgb(16, 17, 20);
            case THEME_OUTLINE:
                return Color.argb(217, 255, 255, 255);
            case THEME_NEON:
                switch (role) {
                    case ROLE_A: return Color.rgb(255, 43, 214);
                    case ROLE_B: return Color.rgb(43, 240, 255);
                    case ROLE_X: return Color.rgb(182, 255, 43);
                    case ROLE_Y: return Color.rgb(255, 170, 43);
                    case ROLE_OTHER: return Color.rgb(43, 240, 255);
                    case ROLE_DPAD: return Color.rgb(182, 255, 43);
                    default: return Color.rgb(43, 240, 255);
                }
            case THEME_PASTEL:
                switch (role) {
                    case ROLE_A: return Color.rgb(255, 198, 211);
                    case ROLE_B: return Color.rgb(255, 241, 184);
                    case ROLE_X: return Color.rgb(198, 227, 255);
                    case ROLE_Y: return Color.rgb(205, 245, 212);
                    case ROLE_OTHER: return Color.rgb(226, 214, 255);
                    case ROLE_DPAD: return Color.rgb(205, 245, 212);
                    default: return Color.rgb(198, 227, 255);
                }
            case THEME_CUSTOM:
                return role == ROLE_DPAD ? custom[0] : role == ROLE_PILL ? custom[2] : custom[1];
            default:
                return 0;
        }
    }

    /** The role a round button's letter stands for. */
    static int roleOf(String label) {
        if (label == null) return ROLE_OTHER;
        switch (label) {
            case "A": case "○": return ROLE_A;
            case "B": case "×": return ROLE_B;
            case "X": case "△": return ROLE_X;
            case "Y": case "□": return ROLE_Y;
            default: return ROLE_OTHER;
        }
    }

    static boolean isLight(int color) {
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 > 150 * 1000;
    }

    static int lighter(int color, float amount) {
        return Color.rgb(mix(Color.red(color), 255, amount), mix(Color.green(color), 255, amount), mix(Color.blue(color), 255, amount));
    }

    static int darker(int color, float amount) {
        return Color.rgb(mix(Color.red(color), 0, amount), mix(Color.green(color), 0, amount), mix(Color.blue(color), 0, amount));
    }

    private static int mix(int from, int to, float amount) {
        return Math.round(from + (to - from) * amount);
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Light, middle and edge colour for a see-through plastic body of this colour. */
    static int[] palette(int color) {
        return isLight(color)
                ? new int[]{lighter(color, 0.35f), color, darker(color, 0.22f)}
                : new int[]{lighter(color, 0.12f), darker(color, 0.25f), darker(color, 0.55f)};
    }

    // ---- storage ----

    static String gameScope(String saveBaseName) {
        return "game:" + saveBaseName;
    }

    private static String key(String scope, boolean landscape) {
        return "btnstyle:" + scope + ":" + (landscape ? "L" : "P");
    }

    static boolean exists(Context context, String scope, boolean landscape) {
        return MainActivity.sharedSettings(context).contains(key(scope, landscape));
    }

    /** The style in force: the game's own when it has one, else the console's, else defaults. */
    static ButtonStyle load(Context context, String mode, String saveBaseName, boolean landscape) {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        String saved = saveBaseName != null ? prefs.getString(key(gameScope(saveBaseName), landscape), null) : null;
        if (saved == null) {
            saved = prefs.getString(key(mode, landscape), null);
        }
        return parse(saved);
    }

    static void save(Context context, String scope, boolean landscape, ButtonStyle style) {
        MainActivity.sharedSettings(context).edit().putString(key(scope, landscape), style.serialize()).apply();
    }

    static void clear(Context context, String scope, boolean landscape) {
        MainActivity.sharedSettings(context).edit().remove(key(scope, landscape)).apply();
    }

    String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(theme).append(',').append(shape).append(',').append(label).append(',').append(sizeAll);
        for (int size : sizes) sb.append(',').append(size);
        sb.append(',').append(glow ? 1 : 0).append(',').append(bounce ? 1 : 0);
        for (int color : custom) sb.append(',').append(color);
        return sb.toString();
    }

    static ButtonStyle parse(String text) {
        ButtonStyle style = new ButtonStyle();
        if (text == null || text.isEmpty()) {
            return style;
        }
        try {
            String[] parts = text.split(",");
            int k = 0;
            style.theme = clamp(Integer.parseInt(parts[k++]), 0, THEME_NAMES.length - 1);
            style.shape = clamp(Integer.parseInt(parts[k++]), 0, SHAPE_NAMES.length - 1);
            style.label = clamp(Integer.parseInt(parts[k++]), 0, LABEL_NAMES.length - 1);
            style.sizeAll = clamp(Integer.parseInt(parts[k++]), MIN_SIZE, MAX_SIZE);
            for (int i = 0; i < style.sizes.length; i++) {
                style.sizes[i] = clamp(Integer.parseInt(parts[k++]), MIN_SIZE, MAX_SIZE);
            }
            style.glow = !"0".equals(parts[k++]);
            style.bounce = "1".equals(parts[k++]);
            for (int i = 0; i < style.custom.length; i++) {
                style.custom[i] = Integer.parseInt(parts[k++]) | 0xFF000000;
            }
        } catch (RuntimeException ignored) {
            // A damaged or older entry keeps whatever was read before it broke.
        }
        return style;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
