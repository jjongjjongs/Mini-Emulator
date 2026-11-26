package com.example.minigba;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

// 화면 테마 (라이트 · 다크): the app's screens are drawn with light colors written in place; in
// dark mode those colors are turned into their dark counterparts where they are set (text,
// backgrounds, lines), so every screen follows without a second set of layouts. Accent colors
// (buttons, console colors, covers) stay; light mode is left exactly as it was.
final class Theme {
    static final String PREF = "app_theme";   // system / light / dark
    private static volatile boolean dark;

    private static final int PAGE = Color.rgb(15, 17, 21);
    private static final int CARD = Color.rgb(27, 30, 36);
    private static final int CHIP = Color.rgb(37, 42, 50);
    private static final int LINE = Color.rgb(55, 62, 72);

    private Theme() {
    }

    static String choice(Context context) {
        return MainActivity.sharedSettings(context).getString(PREF, "system");
    }

    // Reads the choice (and the phone's own dark mode for "system"); true when it changed.
    static boolean update(Context context) {
        String choice = choice(context);
        boolean now = "dark".equals(choice) || ("system".equals(choice)
                && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        boolean changed = now != dark;
        dark = now;
        return changed;
    }

    static boolean isDark() {
        return dark;
    }

    private static float lum(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    private static int spread(int c) {
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int mix(int a, int b, float t) {
        return Color.argb(Color.alpha(a), Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // A text color: dark greys become light greys, dark colored text gets lighter; white and
    // light text (on colored buttons and headers) stays.
    static int text(int c) {
        if (!dark || Color.alpha(c) == 0 || lum(c) > 0.62f) {
            return c;
        }
        if (spread(c) < 40) {
            float l = 1.0f - lum(c) * 0.85f;
            int v = Math.round(255 * Math.min(0.92f, l));
            return Color.argb(Color.alpha(c), v, Math.min(255, v + 2), Math.min(255, v + 6));
        }
        return mix(c, Color.WHITE, 0.5f);
    }

    // A background: white becomes the card color, light greys the chip (or page) color, light
    // tints a dark tint of the same hue; strong colors stay.
    static int bg(int c) {
        if (!dark || Color.alpha(c) == 0 || lum(c) < 0.78f) {
            return c;
        }
        int r = Color.red(c);
        int g = Color.green(c);
        int b = Color.blue(c);
        if (r == 255 && g == 255 && b == 255) {
            return Color.argb(Color.alpha(c), Color.red(CARD), Color.green(CARD), Color.blue(CARD));
        }
        if (spread(c) > 90) {
            return c;
        }
        if (spread(c) < 14) {
            // The page backgrounds are a little blue; inner fields are plain grey.
            boolean page = b - r >= 8 && lum(c) < 0.95f;
            int to = page ? PAGE : CHIP;
            return Color.argb(Color.alpha(c), Color.red(to), Color.green(to), Color.blue(to));
        }
        int min = Math.min(r, Math.min(g, b));
        return Color.argb(Color.alpha(c), Math.min(255, 26 + (r - min) * 3), Math.min(255, 30 + (g - min) * 3), Math.min(255, 36 + (b - min) * 3));
    }

    static int line(int c) {
        if (!dark || lum(c) < 0.7f) {
            return c;
        }
        return spread(c) < 30 ? LINE : bg(c);
    }

    static int page() {
        return dark ? PAGE : Color.rgb(238, 242, 247);
    }
}
