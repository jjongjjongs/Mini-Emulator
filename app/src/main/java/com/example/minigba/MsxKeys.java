package com.example.minigba;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// MSX keys on the screen (blueMSX reads the keyboard state as libretro RETROK_ codes, through
// the same key store as DOS): the names below are the MSX keys and the codes the core maps
// them from. The rows of often used keys are TouchButtons (the layout editor and 버튼 꾸미기
// treat them like the pad's buttons); the full keyboard is a panel over the controls.
final class MsxKeys {
    interface Host {
        long handle();

        int dp(int value);

        // The full keyboard closed (its "패드로" key).
        void closeKeyboard();
    }

    private static final Map<String, Integer> CODES = new HashMap<>();

    static {
        String[][] named = {
                {"ESC", "27"}, {"TAB", "9"}, {"BS", "8"}, {"RETURN", "13"}, {"↵", "13"}, {"SPACE", "32"},
                {"STOP", "279"}, {"SELECT", "280"}, {"HOME", "278"}, {"INS", "277"}, {"DEL", "127"},
                {"CTRL", "306"}, {"SHIFT", "304"}, {"CAPS", "301"}, {"GRAPH", "308"},
                {"↑", "273"}, {"↓", "274"}, {"→", "275"}, {"←", "276"},
                {"-", "45"}, {"^", "61"}, {"\\", "92"}, {"@", "96"}, {"[", "91"}, {";", "59"}, {":", "39"}, {"]", "93"},
                {",", "44"}, {".", "46"}, {"/", "47"}, {"_", "95"},
        };
        for (String[] n : named) {
            CODES.put(n[0], Integer.parseInt(n[1]));
        }
        for (int i = 1; i <= 5; i++) {
            CODES.put("F" + i, 281 + i);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            CODES.put(String.valueOf(c), (int) Character.toLowerCase(c));
        }
        for (char c = '0'; c <= '9'; c++) {
            CODES.put(String.valueOf(c), (int) c);
        }
    }

    static int code(String key) {
        Integer value = CODES.get(key.toUpperCase(Locale.ROOT));
        if (value == null) value = CODES.get(key);
        return value == null ? 0 : value;
    }

    // The TouchButton bit of a key: its code moved clear of the pad's bits, so each key is
    // its own item for the layout editor and never mistaken for a face button.
    static int bitFor(int code) {
        return code << 16;
    }

    static int codeOfBit(int bit) {
        return bit >>> 16;
    }

    static void send(long handle, int code, boolean down) {
        if (handle != 0 && code != 0) {
            NativeGba.dosKey(handle, code, down, 0);
        }
    }

    // ---- the full keyboard ----

    private static final String[][] ROWS = {
            {"F1", "F2", "F3", "F4", "F5", "STOP", "SELECT", "HOME", "INS", "DEL"},
            {"ESC", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "^", "BS"},
            {"TAB", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "@", "[", "RETURN"},
            {"CTRL", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", ":", "]"},
            {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "_"},
            {"CAPS", "GRAPH", "SPACE", "←", "↑", "↓", "→", "패드로"},
    };
    private static final int KEY = Color.rgb(58, 61, 69);
    private static final int FUNCTION = Color.rgb(124, 45, 18);
    private static final int ACCENT = Color.rgb(190, 18, 60);

    private final Context context;
    private final Host host;
    // SHIFT / CTRL / GRAPH: a tap holds one for the next key, a long press until tapped again.
    private final Map<String, Integer> modifierState = new HashMap<>();
    private final Map<String, TextView> modifierViews = new HashMap<>();

    MsxKeys(Context context, Host host) {
        this.context = context;
        this.host = host;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    View keyboard(int keyHeightDp) {
        int dp = host.dp(1);
        LinearLayout board = new LinearLayout(context);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setPadding(5 * dp, 5 * dp, 5 * dp, 3 * dp);
        GradientDrawable background = rounded(Color.rgb(21, 22, 26), 14 * dp);
        background.setStroke(2 * dp, ACCENT);
        board.setBackground(background);
        board.setClickable(true);
        TextView title = new TextView(context);
        title.setText("MSX 키보드 · SHIFT · CTRL · GRAPH는 톡 = 다음 키에, 길게 = 고정");
        title.setTextColor(Color.rgb(254, 205, 211));
        title.setTextSize(10.5f);
        title.setPadding(4 * dp, 0, 0, 4 * dp);
        board.addView(title);
        for (String[] keys : ROWS) {
            LinearLayout row = new LinearLayout(context);
            for (String key : keys) {
                float weight = key.equals("SPACE") ? 4f : key.length() > 3 ? 1.7f : key.length() > 1 && !key.startsWith("F") ? 1.3f : 1f;
                TextView view = key.equals("패드로") ? closeKey(key)
                        : key.equals("SHIFT") || key.equals("CTRL") || key.equals("GRAPH") ? modifierKey(key)
                        : boardKey(key, key.startsWith("F") && key.length() == 2 || key.equals("STOP") || key.equals("SELECT")
                        || key.equals("HOME") || key.equals("INS") || key.equals("DEL") ? FUNCTION
                        : key.equals("RETURN") || key.equals("SPACE") ? ACCENT : KEY);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, keyHeightDp * dp, weight);
                params.setMargins(dp * 3 / 2, 0, dp * 3 / 2, 0);
                row.addView(view, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, 4 * dp);
            board.addView(row, rowParams);
        }
        return board;
    }

    private TextView keyView(String text, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(text.length() > 4 ? 9f : 11f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(0, 0, 0, 0);
        view.setBackground(rounded(color, host.dp(6)));
        return view;
    }

    private TextView boardKey(String key, int color) {
        final int code = code(key);
        TextView view = keyView(key, color);
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.6f);
                send(host.handle(), code, true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                send(host.handle(), code, false);
                releaseOneShotModifiers();
            }
            return true;
        });
        return view;
    }

    // 0 up, 1 held for the next key, 2 locked.
    private TextView modifierKey(final String key) {
        final int code = code(key);
        final TextView view = keyView(key, KEY);
        modifierViews.put(key, view);
        final Runnable[] lock = new Runnable[1];
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            int state = modifierState.containsKey(key) ? modifierState.get(key) : 0;
            if (action == MotionEvent.ACTION_DOWN) {
                lock[0] = () -> {
                    modifierState.put(key, 2);
                    send(host.handle(), code, true);
                    paintModifier(key);
                    lock[0] = null;
                };
                v.postDelayed(lock[0], 450);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (lock[0] != null) {
                    v.removeCallbacks(lock[0]);
                    lock[0] = null;
                    if (action == MotionEvent.ACTION_UP) {
                        if (state == 0) {
                            modifierState.put(key, 1);
                            send(host.handle(), code, true);
                        } else {
                            modifierState.put(key, 0);
                            send(host.handle(), code, false);
                        }
                        paintModifier(key);
                    }
                }
            }
            return true;
        });
        return view;
    }

    private void paintModifier(String key) {
        TextView view = modifierViews.get(key);
        if (view == null) return;
        int state = modifierState.containsKey(key) ? modifierState.get(key) : 0;
        view.setBackground(rounded(state == 0 ? KEY : state == 1 ? Color.rgb(37, 99, 235) : ACCENT, host.dp(6)));
    }

    private void releaseOneShotModifiers() {
        for (Map.Entry<String, Integer> entry : modifierState.entrySet()) {
            if (entry.getValue() == 1) {
                entry.setValue(0);
                send(host.handle(), code(entry.getKey()), false);
                paintModifier(entry.getKey());
            }
        }
    }

    // Lets go of every key the keyboard holds (it closes, or the game pauses).
    void releaseAll() {
        for (Map.Entry<String, Integer> entry : modifierState.entrySet()) {
            if (entry.getValue() != 0) {
                entry.setValue(0);
                send(host.handle(), code(entry.getKey()), false);
                paintModifier(entry.getKey());
            }
        }
    }

    private TextView closeKey(String text) {
        TextView view = keyView(text, Color.rgb(15, 118, 110));
        view.setOnClickListener(v -> {
            releaseAll();
            host.closeKeyboard();
        });
        return view;
    }
}
