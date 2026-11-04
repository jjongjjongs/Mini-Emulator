package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// DOS mode's on-screen controls: a row of the keys the game uses, a D-pad on the arrow keys,
// a mouse pad, big Ctrl / Alt / Shift / Enter buttons, the whole keyboard on demand and a
// mouse mode in which the game screen itself is the mouse. Keys go to DOSBox Pure as
// libretro key codes (NativeGba.dosKey); the mouse as movement and held buttons.
final class DosControls {
    interface Host {
        long handle();

        int dp(int value);

        // The views were built again (another page, a key taken out of the row): the layout
        // editor's list and the button style have to be taken again.
        default void rebuilt() {
        }
    }

    // A piece of the DOS controls the layout editor moves and "버튼 꾸미기" styles: its key in
    // the saved layout ("dos.dpad", "dos.btn.Ctrl", "dos.key.Esc" …), its size group.
    interface Item {
        String itemKey();

        int styleGroup();

        void applyStyle(ButtonStyle style, String key);
    }

    static final int PAGE_PAD = 0;
    static final int PAGE_MOUSE = 1;
    static final int PAGE_KEYBOARD = 2;

    private static final int SLATE = Color.rgb(55, 65, 81);
    private static final int TEAL = Color.rgb(15, 118, 110);
    private static final int VIOLET = Color.rgb(124, 58, 237);
    private static final int RED = Color.rgb(185, 28, 28);
    private static final int BLUE = Color.rgb(29, 78, 216);
    private static final int DARK = Color.rgb(31, 41, 55);

    private final Context context;
    private final Host host;
    private final boolean landscape;
    private final boolean directMouse;
    private final String[][] keyRow;
    private final String[][] buttons;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private int page;
    private int keyboardBack = PAGE_PAD;
    private boolean fRow;
    private int latched;      // one-shot Shift / Ctrl / Alt from the keyboard
    private int locked;       // held down until tapped again
    private final List<View> pageViews = new ArrayList<>();
    // Keys taken out of the row to stand on their own (lower case), for this screen direction.
    private final java.util.Set<String> pulled = new java.util.LinkedHashSet<>();
    private android.content.SharedPreferences prefs;

    void setPulledStore(android.content.SharedPreferences prefs) {
        this.prefs = prefs;
        this.pulled.clear();
        String saved = prefs.getString(pulledKey(), "");
        for (String k : saved.split(",")) {
            if (!k.trim().isEmpty()) pulled.add(k.trim().toLowerCase(Locale.ROOT));
        }
    }

    private String pulledKey() {
        return "dos_pulled_" + (landscape ? "L" : "P");
    }

    // Takes a key out of the row, or puts a taken-out one back. True when something changed.
    boolean togglePulled(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (!pulled.remove(k)) {
            pulled.add(k);
        }
        if (prefs != null) {
            prefs.edit().putString(pulledKey(), android.text.TextUtils.join(",", pulled)).apply();
        }
        show(page);
        return true;
    }

    // The taken-out keys that this game's row has, each a key of its own at the top right.
    private void addPulledKeys() {
        int dp = host.dp(1);
        int n = 0;
        for (String[] key : keyRow) {
            if (!pulled.contains(key[0].toLowerCase(Locale.ROOT))) continue;
            DosKey view = keyButton(key[0], key[1], SLATE, 12f);
            view.itemKey = "dos.key." + key[0];
            view.group = ButtonStyle.GROUP_SHOULDER;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, (landscape ? 30 : 34) * dp, Gravity.TOP | Gravity.END);
            params.setMargins(0, (landscape ? 60 : 8) * dp + (landscape ? n * 38 * dp : 0), (landscape ? 180 : 12) * dp + (landscape ? 0 : n * 62 * dp), 0);
            add(view, params);
            n++;
        }
    }

    DosControls(Context context, Host host, boolean landscape, String keys, String bigButtons, int startPage, boolean directMouse) {
        this.context = context;
        this.host = host;
        this.landscape = landscape;
        this.directMouse = directMouse;
        this.keyRow = parse(keys, DosSetup.DEFAULT_KEYS);
        this.buttons = parse(bigButtons, DosSetup.DEFAULT_BUTTONS);
        this.page = startPage == 1 ? PAGE_MOUSE : startPage == 2 ? PAGE_KEYBOARD : PAGE_PAD;
    }

    // "Esc,Enter,Ctrl=공격" -> {{"Esc","Esc"},{"Enter","Enter"},{"Ctrl","공격"}}: keys we know.
    static String[][] parse(String text, String fallback) {
        List<String[]> out = new ArrayList<>();
        for (String part : (text == null ? fallback : text).split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            String key = (eq > 0 ? item.substring(0, eq) : item).trim();
            String label = eq > 0 ? item.substring(eq + 1).trim() : key;
            if (code(key) != 0) {
                out.add(new String[]{key, label.isEmpty() ? key : label});
            }
        }
        if (out.isEmpty() && fallback != null && text != null) {
            return parse(fallback, null);
        }
        return out.toArray(new String[0][]);
    }

    // ---- key codes (libretro RETROK_*) ----

    private static final Map<String, Integer> CODES = new HashMap<>();

    static {
        String[][] named = {
                {"esc", "27"}, {"enter", "13"}, {"return", "13"}, {"space", "32"}, {"tab", "9"}, {"⌫", "8"}, {"backspace", "8"}, {"bs", "8"},
                {"up", "273"}, {"↑", "273"}, {"down", "274"}, {"↓", "274"}, {"right", "275"}, {"→", "275"}, {"left", "276"}, {"←", "276"},
                {"ins", "277"}, {"insert", "277"}, {"home", "278"}, {"end", "279"}, {"pgup", "280"}, {"pgdn", "281"}, {"del", "127"}, {"delete", "127"},
                {"shift", "304"}, {"rshift", "303"}, {"ctrl", "306"}, {"rctrl", "305"}, {"alt", "308"}, {"ralt", "307"}, {"caps", "301"},
                {";", "59"}, {"'", "39"}, {",", "44"}, {".", "46"}, {"/", "47"}, {"-", "45"}, {"=", "61"}, {"[", "91"}, {"]", "93"}, {"\\", "92"}, {"`", "96"},
                {"pause", "19"}, {"numlock", "300"}, {"scroll", "302"},
                {"kp0", "256"}, {"kp1", "257"}, {"kp2", "258"}, {"kp3", "259"}, {"kp4", "260"}, {"kp5", "261"}, {"kp6", "262"}, {"kp7", "263"}, {"kp8", "264"}, {"kp9", "265"},
                {"kp+", "270"}, {"kp-", "269"}, {"kp*", "268"}, {"kp/", "267"}, {"kpenter", "271"},
        };
        for (String[] n : named) {
            CODES.put(n[0], Integer.parseInt(n[1]));
        }
        for (int i = 1; i <= 15; i++) {
            CODES.put("f" + i, 281 + i);
        }
        for (char c = 'a'; c <= 'z'; c++) {
            CODES.put(String.valueOf(c), (int) c);
        }
        for (char c = '0'; c <= '9'; c++) {
            CODES.put(String.valueOf(c), (int) c);
        }
    }

    static int code(String name) {
        Integer value = CODES.get(name.toLowerCase(Locale.ROOT));
        return value == null ? 0 : value;
    }

    // A key of a physical keyboard, as a libretro key code; 0 when it has none.
    static int fromAndroid(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return 'a' + (keyCode - KeyEvent.KEYCODE_A);
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) return '0' + (keyCode - KeyEvent.KEYCODE_0);
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return 282 + (keyCode - KeyEvent.KEYCODE_F1);
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) return 256 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE: return 27;
            case KeyEvent.KEYCODE_ENTER: return 13;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 271;
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_TAB: return 9;
            case KeyEvent.KEYCODE_DEL: return 8;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 127;
            case KeyEvent.KEYCODE_DPAD_UP: return 273;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 274;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 275;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 276;
            case KeyEvent.KEYCODE_INSERT: return 277;
            case KeyEvent.KEYCODE_MOVE_HOME: return 278;
            case KeyEvent.KEYCODE_MOVE_END: return 279;
            case KeyEvent.KEYCODE_PAGE_UP: return 280;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 281;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 304;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 303;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 306;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 305;
            case KeyEvent.KEYCODE_ALT_LEFT: return 308;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 307;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 301;
            case KeyEvent.KEYCODE_SEMICOLON: return 59;
            case KeyEvent.KEYCODE_APOSTROPHE: return 39;
            case KeyEvent.KEYCODE_COMMA: return 44;
            case KeyEvent.KEYCODE_PERIOD: return 46;
            case KeyEvent.KEYCODE_SLASH: return 47;
            case KeyEvent.KEYCODE_MINUS: return 45;
            case KeyEvent.KEYCODE_EQUALS: return 61;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 91;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 93;
            case KeyEvent.KEYCODE_BACKSLASH: return 92;
            case KeyEvent.KEYCODE_GRAVE: return 96;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 270;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 269;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 268;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return 267;
            default: return 0;
        }
    }

    private void send(int code, boolean down) {
        long handle = host.handle();
        if (handle != 0 && code != 0) {
            NativeGba.dosKey(handle, code, down, code < 128 ? code : 0);
        }
    }

    void physicalKey(int code, boolean down) {
        send(code, down);
    }

    // Lets go of everything (the game is paused or the controls rebuilt).
    void releaseAll() {
        long handle = host.handle();
        if (handle == 0) {
            return;
        }
        for (int code : new int[]{273, 274, 275, 276, 304, 306, 308}) {
            NativeGba.dosKey(handle, code, false, 0);
        }
        NativeGba.dosMouseButtons(handle, 0);
        mouseButtons = 0;
        latched = 0;
        locked = 0;
    }

    // ---- mouse ----

    private int mouseButtons;
    private float remainderX;
    private float remainderY;

    private void setMouseButtons(int buttons) {
        this.mouseButtons = buttons;
        long handle = host.handle();
        if (handle != 0) {
            NativeGba.dosMouseButtons(handle, buttons);
        }
    }

    private void click(int bit) {
        setMouseButtons(mouseButtons | bit);
        handler.postDelayed(() -> setMouseButtons(mouseButtons & ~bit), 70);
    }

    private void moveMouse(float dx, float dy) {
        long handle = host.handle();
        if (handle == 0) {
            return;
        }
        remainderX += dx;
        remainderY += dy;
        int mx = (int) remainderX;
        int my = (int) remainderY;
        remainderX -= mx;
        remainderY -= my;
        if (mx != 0 || my != 0) {
            NativeGba.dosMouseMove(handle, mx, my);
        }
    }

    // Touch as a laptop touchpad: slide = move, tap = left click, two-finger tap = right
    // click, hold still then slide = drag with the left button down. With absolute set
    // (direct mouse on the game screen) the cursor goes where the finger is.
    private final class Touchpad implements View.OnTouchListener {
        private final boolean absolute;
        private float lastX;
        private float lastY;
        private float travel;
        private long downTime;
        private int maxFingers;
        private boolean dragging;
        private final Runnable startDrag = () -> {
            if (travel < host.dp(8)) {
                dragging = true;
                setMouseButtons(mouseButtons | 1);
                Haptics.tap();
            }
        };

        Touchpad(boolean absolute) {
            this.absolute = absolute;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int width = Math.max(1, view.getWidth());
            int height = Math.max(1, view.getHeight());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    travel = 0;
                    downTime = event.getEventTime();
                    maxFingers = 1;
                    dragging = false;
                    if (absolute) {
                        pointer(view, event);
                    } else {
                        handler.postDelayed(startDrag, 450);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    maxFingers = Math.max(maxFingers, event.getPointerCount());
                    handler.removeCallbacks(startDrag);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (event.getPointerCount() > 1) {
                        return true;
                    }
                    float x = event.getX();
                    float y = event.getY();
                    float dx = x - lastX;
                    float dy = y - lastY;
                    travel += Math.abs(dx) + Math.abs(dy);
                    lastX = x;
                    lastY = y;
                    if (absolute) {
                        pointer(view, event);
                    } else {
                        // Across the pad is about the width of a 640-wide DOS screen.
                        float scale = 640.0f / Math.max(width, height * 4 / 3);
                        moveMouse(dx * scale * 1.4f, dy * scale * 1.4f);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    handler.removeCallbacks(startDrag);
                    boolean tap = event.getActionMasked() == MotionEvent.ACTION_UP && travel < host.dp(10)
                            && event.getEventTime() - downTime < 300;
                    if (dragging) {
                        setMouseButtons(mouseButtons & ~1);
                    } else if (tap) {
                        click(maxFingers >= 2 ? 2 : 1);
                        Haptics.tap();
                    }
                    dragging = false;
                    if (absolute) {
                        long handle = host.handle();
                        if (handle != 0) {
                            NativeGba.setPointer(handle, Math.round((Math.max(0f, Math.min(1f, event.getX() / width)) * 2f - 1f) * 32767f),
                                    Math.round((Math.max(0f, Math.min(1f, event.getY() / height)) * 2f - 1f) * 32767f), false);
                        }
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        private void pointer(View view, MotionEvent event) {
            long handle = host.handle();
            if (handle == 0) {
                return;
            }
            float fx = Math.max(0f, Math.min(1f, event.getX() / Math.max(1, view.getWidth())));
            float fy = Math.max(0f, Math.min(1f, event.getY() / Math.max(1, view.getHeight())));
            NativeGba.setPointer(handle, Math.round((fx * 2f - 1f) * 32767f), Math.round((fy * 2f - 1f) * 32767f), true);
        }
    }

    private Touchpad screenPad;

    // The game screen's touch while the mouse mode is on; false lets the screen ignore it.
    boolean screenTouch(View screen, MotionEvent event) {
        if (page != PAGE_MOUSE) {
            return false;
        }
        if (screenPad == null) {
            screenPad = new Touchpad(directMouse);
        }
        return screenPad.onTouch(screen, event);
    }

    // ---- views ----

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // A key or button: its own colour and size, restyled by "버튼 꾸미기".
    final class DosKey extends TextView implements Item {
        final int baseColor;
        final float baseSize;
        String itemKey;
        int group = ButtonStyle.GROUP_SHOULDER;
        int role = ButtonStyle.ROLE_OTHER;
        String keyName;

        DosKey(String text, int color, float size) {
            super(context);
            this.baseColor = color;
            this.baseSize = size;
            setText(text);
            setTextColor(Color.WHITE);
            setTextSize(size);
            setTypeface(Typeface.DEFAULT_BOLD);
            setGravity(Gravity.CENTER);
            setSingleLine(true);
            setBackground(rounded(color, host.dp(9)));
            setPadding(host.dp(8), 0, host.dp(8), 0);
        }

        @Override
        public String itemKey() {
            return itemKey;
        }

        @Override
        public int styleGroup() {
            return group;
        }

        @Override
        public void applyStyle(ButtonStyle style, String key) {
            styleKey(this, style, key, role);
        }
    }

    private void styleKey(DosKey view, ButtonStyle style, String key, int role) {
        boolean themed = style != null && style.themed();
        if (themed && style.theme == ButtonStyle.THEME_RETRO && role != ButtonStyle.ROLE_A) {
            role = ButtonStyle.ROLE_PILL;  // the first Mini GBA: one plum button (Ctrl), grey pills
        }
        int radius = !themed || style.shape == ButtonStyle.SHAPE_ROUNDED ? host.dp(9) : style.shape == ButtonStyle.SHAPE_SQUARE ? host.dp(2) : host.dp(40);
        int body = themed ? style.colorFor(key, role) : 0;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radius);
        int textColor = Color.WHITE;
        if (themed && style.outline()) {
            bg.setColor(Color.argb(40, 255, 255, 255));
            bg.setStroke(host.dp(2), body != 0 ? body : Color.WHITE);
        } else if (themed && style.neon()) {
            int glow = body != 0 ? body : Color.rgb(43, 240, 255);
            bg.setColor(Color.argb(215, 8, 20, 40));
            bg.setStroke(host.dp(2), glow);
            textColor = glow;
        } else {
            int fill = body != 0 ? body : view.baseColor;
            bg.setColor(fill);
            textColor = body != 0 ? ButtonStyle.labelOn(fill, style) : Color.WHITE;
        }
        view.setBackground(bg);
        boolean hide = themed && style.label == ButtonStyle.LABEL_HIDE;
        view.setTextColor(hide ? Color.TRANSPARENT : textColor);
        view.setTextSize(themed && style.label == ButtonStyle.LABEL_SMALL ? view.baseSize * 0.8f : view.baseSize);
    }

    private DosKey label(String text, int color, float size) {
        return new DosKey(text, color, size);
    }

    // A key held while touched.
    private DosKey keyButton(String key, String text, int color, float size) {
        final int code = code(key);
        final DosKey view = label(text, color, size);
        view.keyName = key;
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    Haptics.tap();
                    send(code, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    send(code, false);
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    private DosKey actionButton(String text, int color, float size, Runnable action) {
        DosKey view = label(text, color, size);
        view.setOnClickListener(v -> {
            Haptics.tap();
            action.run();
        });
        return view;
    }

    // A mouse button held while touched (bit: 1 left, 2 right); wheel bits are taps.
    private DosKey mouseButton(String text, int color, int bit) {
        DosKey view = label(text, color, 12f);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    Haptics.tap();
                    setMouseButtons(mouseButtons | bit);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    if (bit >= 8) {
                        handler.postDelayed(() -> setMouseButtons(mouseButtons & ~bit), 50);
                    } else {
                        setMouseButtons(mouseButtons & ~bit);
                    }
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    private int buttonColor(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("ctrl") ? RED : lower.contains("alt") ? BLUE : SLATE;
    }

    // The row of keys: moved and sized as one; a key can be taken out of it (pulled).
    final class KeyStrip extends HorizontalScrollView implements Item {
        final String itemKey;

        KeyStrip(String itemKey) {
            super(context);
            this.itemKey = itemKey;
        }

        @Override
        public String itemKey() {
            return itemKey;
        }

        @Override
        public int styleGroup() {
            return ButtonStyle.GROUP_SHOULDER;
        }

        @Override
        public void applyStyle(ButtonStyle style, String key) {
            if (getChildCount() == 0) return;
            LinearLayout row = (LinearLayout) getChildAt(0);
            for (int k = 0; k < row.getChildCount(); k++) {
                View child = row.getChildAt(k);
                if (child instanceof DosKey) {
                    DosKey dk = (DosKey) child;
                    styleKey(dk, style, dk.keyName != null ? "dos.key." + dk.keyName : key, ButtonStyle.ROLE_PILL);
                }
            }
        }

        // The key under a point of the window, or null (the toggles aren't keys).
        String keyAt(float windowX, float windowY) {
            if (getChildCount() == 0) return null;
            LinearLayout row = (LinearLayout) getChildAt(0);
            int[] at = new int[2];
            for (int k = 0; k < row.getChildCount(); k++) {
                View child = row.getChildAt(k);
                child.getLocationOnScreen(at);
                float w = child.getWidth() * getScaleX();
                float h = child.getHeight() * getScaleY();
                if (child instanceof DosKey && ((DosKey) child).keyName != null
                        && windowX >= at[0] && windowX <= at[0] + w && windowY >= at[1] && windowY <= at[1] + h) {
                    return ((DosKey) child).keyName;
                }
            }
            return null;
        }
    }

    private HorizontalScrollView keyStrip(String[][] keys, boolean toggles, int height) {
        return keyStrip(keys, toggles, height, null);
    }

    private HorizontalScrollView keyStrip(String[][] keys, boolean toggles, int height, String itemKey) {
        HorizontalScrollView scroll = itemKey != null ? new KeyStrip(itemKey) : new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String[] key : keys) {
            if (itemKey != null && pulled.contains(key[0].toLowerCase(Locale.ROOT))) {
                continue;  // placed on its own
            }
            addStrip(row, keyButton(key[0], key[1], SLATE, 12f), height);
        }
        if (toggles) {
            addStrip(row, actionButton("⌨ 전체 키보드", TEAL, 12f, () -> show(PAGE_KEYBOARD)), height);
            addStrip(row, page == PAGE_MOUSE
                    ? actionButton("🎮 패드로", VIOLET, 12f, () -> show(PAGE_PAD))
                    : actionButton("🖱 마우스 모드", VIOLET, 12f, () -> show(PAGE_MOUSE)), height);
        }
        scroll.addView(row);
        return scroll;
    }

    private void addStrip(LinearLayout row, View view, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, height);
        params.setMargins(0, 0, host.dp(5), 0);
        row.addView(view, params);
    }

    final class MousePad extends LinearLayout implements Item {
        MousePad() {
            super(context);
        }

        @Override
        public String itemKey() {
            return "dos.mouse";
        }

        @Override
        public int styleGroup() {
            return ButtonStyle.GROUP_NONE;
        }

        @Override
        public void applyStyle(ButtonStyle style, String key) {
            boolean themed = style != null && style.themed();
            int c = themed ? style.colorFor(key, ButtonStyle.ROLE_OTHER) : 0;
            GradientDrawable bg = rounded(themed && (style.neon() || style.outline()) ? Color.argb(200, 8, 20, 40) : c != 0 ? ButtonStyle.darker(c, 0.55f) : DARK, host.dp(16));
            bg.setStroke(host.dp(2), c != 0 ? c : Color.rgb(71, 85, 105), host.dp(6), host.dp(4));
            setBackground(bg);
        }
    }

    private View mousePad(boolean hint) {
        LinearLayout pad = new MousePad();
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(DARK, host.dp(16));
        bg.setStroke(host.dp(2), Color.rgb(71, 85, 105), host.dp(6), host.dp(4));
        pad.setBackground(bg);
        TextView title = new TextView(context);
        title.setText("🖱 마우스 패드");
        title.setTextColor(Color.rgb(229, 231, 235));
        title.setTextSize(12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        pad.addView(title);
        if (hint) {
            TextView sub = new TextView(context);
            sub.setText("밀어서 커서 · 톡 = 클릭\n두 손가락 톡 = 오른쪽 클릭");
            sub.setTextColor(Color.rgb(156, 163, 175));
            sub.setTextSize(10f);
            sub.setGravity(Gravity.CENTER);
            pad.addView(sub);
        }
        pad.setOnTouchListener(new Touchpad(false));
        return pad;
    }

    // The arrow keys as a round pad (eight ways).
    private final class ArrowPad extends View implements Item {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int held;  // bits: 1 up, 2 down, 4 left, 8 right
        private int ring = DARK;
        private int rim = SLATE;
        private int mark = Color.rgb(156, 163, 175);

        @Override
        public String itemKey() {
            return "dos.dpad";
        }

        @Override
        public int styleGroup() {
            return ButtonStyle.GROUP_DPAD;
        }

        @Override
        public void applyStyle(ButtonStyle style, String key) {
            boolean themed = style != null && style.themed();
            int c = themed ? style.colorFor(key, ButtonStyle.ROLE_DPAD) : 0;
            if (c == 0) {
                ring = DARK;
                rim = SLATE;
                mark = Color.rgb(156, 163, 175);
            } else if (style.neon() || style.outline()) {
                ring = Color.argb(200, 8, 20, 40);
                rim = c;
                mark = c;
            } else {
                ring = c;
                rim = ButtonStyle.darker(c, 0.25f);
                mark = ButtonStyle.isLight(c) ? Color.rgb(55, 65, 81) : Color.rgb(229, 231, 235);
            }
            invalidate();
        }

        ArrowPad() {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f - host.dp(2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ring);
            canvas.drawCircle(w / 2f, h / 2f, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(host.dp(2));
            paint.setColor(rim);
            canvas.drawCircle(w / 2f, h / 2f, r, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(75, 85, 99));
            canvas.drawCircle(w / 2f, h / 2f, r * 0.42f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(host.dp(15));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            String[] arrows = {"↑", "↓", "←", "→"};
            float[][] spots = {{w / 2f, h / 2f - r * 0.72f}, {w / 2f, h / 2f + r * 0.72f}, {w / 2f - r * 0.72f, h / 2f}, {w / 2f + r * 0.72f, h / 2f}};
            for (int i = 0; i < 4; i++) {
                paint.setColor((held & (1 << i)) != 0 ? Color.WHITE : mark);
                canvas.drawText(arrows[i], spots[i][0], spots[i][1] + host.dp(5), paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int bits = 0;
            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
                float dx = event.getX() - getWidth() / 2f;
                float dy = event.getY() - getHeight() / 2f;
                float dead = Math.min(getWidth(), getHeight()) * 0.12f;
                if (Math.hypot(dx, dy) > dead) {
                    double angle = Math.toDegrees(Math.atan2(-dy, dx));
                    if (angle < 0) angle += 360;
                    if (angle >= 22.5 && angle < 157.5) bits |= 1;
                    if (angle >= 202.5 && angle < 337.5) bits |= 2;
                    if (angle >= 112.5 && angle < 247.5) bits |= 4;
                    if (angle < 67.5 || angle >= 292.5) bits |= 8;
                }
            }
            if (bits != held) {
                int[] codes = {273, 274, 276, 275};
                for (int i = 0; i < 4; i++) {
                    boolean was = (held & (1 << i)) != 0;
                    boolean now = (bits & (1 << i)) != 0;
                    if (was != now) {
                        send(codes[i], now);
                    }
                }
                if (bits != 0 && held == 0) {
                    Haptics.tap();
                }
                held = bits;
                invalidate();
            }
            return true;
        }
    }

    // ---- the whole keyboard ----

    private static final String[][] KEYBOARD = {
            {"Esc", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫"},
            {"Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "Enter"},
            {"Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'"},
            {"Shift", "Z", "X", "C", "V", "B", "N", "M", ",", ".", "/", "↑"},
            {"Ctrl", "Alt", "F1~F12", "-", "Space", "=", "←", "↓", "→", "닫기"},
    };
    private static final String[] F_ROW = {"F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"};

    private View keyboard(int keyHeight) {
        LinearLayout board = new LinearLayout(context);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setPadding(host.dp(5), host.dp(5), host.dp(5), host.dp(3));
        board.setBackground(rounded(Color.rgb(11, 18, 32), host.dp(14)));
        for (int r = 0; r < KEYBOARD.length; r++) {
            String[] keys = r == 0 && fRow ? F_ROW : KEYBOARD[r];
            LinearLayout row = new LinearLayout(context);
            for (final String key : keys) {
                float weight = key.equals("Space") ? 3f : key.length() > 2 ? 1.6f : 1f;
                TextView view;
                if (key.equals("닫기")) {
                    view = actionButton(key, TEAL, 11f, () -> show(keyboardBack));
                } else if (key.equals("F1~F12")) {
                    view = actionButton(fRow ? "1~0" : key, Color.rgb(51, 65, 85), 11f, () -> {
                        fRow = !fRow;
                        show(PAGE_KEYBOARD);
                    });
                } else if (key.equals("Shift") || key.equals("Ctrl") || key.equals("Alt")) {
                    view = modifierKey(key);
                } else {
                    view = boardKey(key);
                }
                view.setPadding(0, 0, 0, 0);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, keyHeight, weight);
                params.setMargins(host.dp(2), 0, host.dp(2), 0);
                row.addView(view, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, 0, 0, host.dp(4));
            board.addView(row, rowParams);
        }
        return board;
    }

    private int modifierBit(String key) {
        return key.equals("Shift") ? 1 : key.equals("Ctrl") ? 2 : 4;
    }

    // Tap: holds for the next key. Long press: stays down until tapped again.
    private TextView modifierKey(final String key) {
        final int bit = modifierBit(key);
        final int code = code(key);
        boolean on = (latched & bit) != 0 || (locked & bit) != 0;
        final TextView view = label((locked & bit) != 0 ? key + " 🔒" : key, on ? TEAL : Color.rgb(51, 65, 85), 11f);
        view.setOnClickListener(v -> {
            Haptics.tap();
            if ((locked & bit) != 0) {
                locked &= ~bit;
                send(code, false);
            } else if ((latched & bit) != 0) {
                latched &= ~bit;
                send(code, false);
            } else {
                latched |= bit;
                send(code, true);
            }
            show(PAGE_KEYBOARD);
        });
        view.setOnLongClickListener(v -> {
            Haptics.tap();
            if ((latched & bit) == 0 && (locked & bit) == 0) {
                send(code, true);
            }
            latched &= ~bit;
            locked |= bit;
            show(PAGE_KEYBOARD);
            return true;
        });
        return view;
    }

    private TextView boardKey(final String key) {
        final int code = code(key);
        final TextView view = label(key, key.length() > 1 ? Color.rgb(51, 65, 85) : DARK, key.length() > 2 ? 10.5f : 13f);
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    Haptics.tap();
                    send(code, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    send(code, false);
                    if (latched != 0) {
                        // One-shot modifiers let go after the key they were for.
                        for (String m : new String[]{"Shift", "Ctrl", "Alt"}) {
                            if ((latched & modifierBit(m)) != 0) {
                                send(code(m), false);
                            }
                        }
                        latched = 0;
                        handler.post(() -> show(PAGE_KEYBOARD));
                    }
                    return true;
                default:
                    return true;
            }
        });
        return view;
    }

    // ---- layout ----

    void attach(FrameLayout frame) {
        this.frame = frame;
        show(page);
    }

    int page() {
        return page;
    }

    void show(int newPage) {
        if (newPage == PAGE_KEYBOARD && page != PAGE_KEYBOARD) {
            keyboardBack = page;
        }
        if (newPage != PAGE_KEYBOARD && page == PAGE_KEYBOARD) {
            // Modifiers left down on the keyboard come up with it.
            for (String m : new String[]{"Shift", "Ctrl", "Alt"}) {
                if (((latched | locked) & modifierBit(m)) != 0) {
                    send(code(m), false);
                }
            }
            latched = 0;
            locked = 0;
        }
        page = newPage;
        if (frame == null) {
            return;
        }
        for (View v : pageViews) {
            frame.removeView(v);
        }
        pageViews.clear();
        if (landscape) {
            buildLandscape();
        } else {
            buildPortrait();
        }
        if (page != PAGE_KEYBOARD) {
            addPulledKeys();
        }
        host.rebuilt();
    }

    // A button the layout editor and "버튼 꾸미기" treat as its own.
    private DosKey item(DosKey view, String itemKey, int group, int role) {
        view.itemKey = itemKey;
        view.group = group;
        view.role = role;
        return view;
    }

    private static final int[] ROLES = {ButtonStyle.ROLE_A, ButtonStyle.ROLE_B, ButtonStyle.ROLE_X, ButtonStyle.ROLE_Y};

    private void add(View view, FrameLayout.LayoutParams params) {
        frame.addView(view, params);
        pageViews.add(view);
    }

    private void buildPortrait() {
        int dp = host.dp(1);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp);
        if (page == PAGE_KEYBOARD) {
            TextView note = new TextView(context);
            note.setText("⌨ 전체 키보드 · Shift/Ctrl/Alt는 톡 = 다음 키에, 길게 = 고정");
            note.setTextColor(Color.rgb(203, 213, 225));
            note.setTextSize(11f);
            note.setPadding(10 * dp, 7 * dp, 10 * dp, 7 * dp);
            note.setBackground(rounded(DARK, 10 * dp));
            column.addView(note, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(-1, -2);
            boardParams.setMargins(0, 8 * dp, 0, 0);
            column.addView(keyboard(38 * dp), boardParams);
            add(column, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        // Two rows of keys; the toggles at the end of the second.
        int half = (keyRow.length + 1) / 2;
        String[][] first = new String[half][];
        String[][] second = new String[keyRow.length - half][];
        System.arraycopy(keyRow, 0, first, 0, half);
        System.arraycopy(keyRow, half, second, 0, second.length);
        column.addView(keyStrip(first, false, 32 * dp, "dos.keys1"), new LinearLayout.LayoutParams(-1, 32 * dp));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, 32 * dp);
        secondParams.setMargins(0, 5 * dp, 0, 0);
        column.addView(keyStrip(second, true, 32 * dp, "dos.keys2"), secondParams);
        LinearLayout middle = new LinearLayout(context);
        middle.setGravity(Gravity.CENTER_VERTICAL);
        if (page == PAGE_PAD) {
            ArrowPad pad = new ArrowPad();
            middle.addView(pad, new LinearLayout.LayoutParams(128 * dp, 128 * dp));
            LinearLayout.LayoutParams mouseParams = new LinearLayout.LayoutParams(0, 128 * dp, 1f);
            mouseParams.setMargins(16 * dp, 0, 0, 0);
            middle.addView(mousePad(true), mouseParams);
        } else {
            LinearLayout left = new LinearLayout(context);
            left.setOrientation(LinearLayout.VERTICAL);
            for (String key : new String[]{"Esc", "Enter", "Space"}) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 38 * dp);
                p.setMargins(0, 0, 0, 6 * dp);
                left.addView(item(keyButton(key, key, SLATE, 12f), "dos.mm." + key, ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_OTHER), p);
            }
            middle.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView hint = new TextView(context);
            hint.setText("🖱 마우스 모드\n게임 화면을\n밀어서 커서 · 톡 = 클릭\n길게 = 끌기\n두 손가락 = 오른쪽");
            hint.setTextColor(Color.rgb(221, 214, 254));
            hint.setTextSize(10.5f);
            hint.setGravity(Gravity.CENTER);
            hint.setBackground(rounded(Color.argb(200, 76, 29, 149), 12 * dp));
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(0, 126 * dp, 1.1f);
            hintParams.setMargins(8 * dp, 0, 8 * dp, 6 * dp);
            middle.addView(hint, hintParams);
            LinearLayout right = new LinearLayout(context);
            right.setOrientation(LinearLayout.VERTICAL);
            Object[][] mouse = {{"왼쪽 클릭", RED, 1}, {"오른쪽 클릭", BLUE, 2}, {"휠 ↑", SLATE, 8}, {"휠 ↓", SLATE, 16}};
            for (Object[] m : mouse) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 28 * dp);
                p.setMargins(0, 0, 0, 5 * dp);
                right.addView(item(mouseButton((String) m[0], (Integer) m[1], (Integer) m[2]), "dos.mm." + m[0], ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_OTHER), p);
            }
            middle.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(-1, -2);
        middleParams.setMargins(4 * dp, 12 * dp, 4 * dp, 0);
        column.addView(middle, middleParams);
        if (page == PAGE_PAD) {
            LinearLayout big = new LinearLayout(context);
            int bi = 0;
            for (String[] b : buttons) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 44 * dp, 1f);
                p.setMargins(3 * dp, 0, 3 * dp, 0);
                big.addView(item(keyButton(b[0], b[1], buttonColor(b[0]), 13f), "dos.btn." + b[0], ButtonStyle.GROUP_ACTION, ROLES[bi++ % 4]), p);
            }
            LinearLayout.LayoutParams bigParams = new LinearLayout.LayoutParams(-1, -2);
            bigParams.setMargins(4 * dp, 14 * dp, 4 * dp, 0);
            column.addView(big, bigParams);
        }
        add(column, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildLandscape() {
        int dp = host.dp(1);
        if (page == PAGE_KEYBOARD) {
            View board = keyboard(34 * dp);
            board.setAlpha(0.94f);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            params.setMargins(110 * dp, 0, 110 * dp, 2 * dp);
            add(board, params);
            return;
        }
        // The key row under the screen.
        HorizontalScrollView strip = keyStrip(keyRow, true, 30 * dp, "dos.keys");
        FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(-1, 30 * dp, Gravity.BOTTOM);
        stripParams.setMargins(150 * dp, 0, 150 * dp, 6 * dp);
        add(strip, stripParams);
        if (page == PAGE_PAD) {
            ArrowPad pad = new ArrowPad();
            FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(128 * dp, 128 * dp, Gravity.START | Gravity.CENTER_VERTICAL);
            padParams.setMargins(14 * dp, 30 * dp, 0, 0);
            add(pad, padParams);
            // Big buttons two by two on the right, the mouse pad under them.
            LinearLayout grid = new LinearLayout(context);
            grid.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < buttons.length; r += 2) {
                LinearLayout row = new LinearLayout(context);
                for (int c = r; c < Math.min(buttons.length, r + 2); c++) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(62 * dp, 40 * dp);
                    p.setMargins(c == r ? 0 : 6 * dp, 0, 0, 0);
                    row.addView(item(keyButton(buttons[c][0], buttons[c][1], buttonColor(buttons[c][0]), 12f), "dos.btn." + buttons[c][0], ButtonStyle.GROUP_ACTION, ROLES[c % 4]), p);
                }
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-2, -2);
                rowParams.setMargins(0, 0, 0, 6 * dp);
                grid.addView(row, rowParams);
            }
            FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            gridParams.setMargins(0, 100 * dp, 12 * dp, 0);
            add(grid, gridParams);
            FrameLayout.LayoutParams mouseParams = new FrameLayout.LayoutParams(130 * dp, 92 * dp, Gravity.BOTTOM | Gravity.END);
            mouseParams.setMargins(0, 0, 12 * dp, 8 * dp);
            add(mousePad(false), mouseParams);
            return;
        }
        // Mouse mode: the screen is the mouse; keys on the left, mouse buttons on the right.
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        for (String key : new String[]{"Esc", "Enter", "Space"}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(118 * dp, 36 * dp);
            p.setMargins(0, 0, 0, 6 * dp);
            left.addView(item(keyButton(key, key, SLATE, 12f), "dos.mm." + key, ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_OTHER), p);
        }
        LinearLayout.LayoutParams kb = new LinearLayout.LayoutParams(118 * dp, 36 * dp);
        kb.setMargins(0, 10 * dp, 0, 6 * dp);
        left.addView(item(actionButton("⌨ 키보드", TEAL, 12f, () -> show(PAGE_KEYBOARD)), "dos.mm.keyboard", ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_PILL), kb);
        left.addView(item(actionButton("🎮 패드로", VIOLET, 12f, () -> show(PAGE_PAD)), "dos.mm.pad", ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_PILL), new LinearLayout.LayoutParams(118 * dp, 36 * dp));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        leftParams.setMargins(14 * dp, 64 * dp, 0, 0);
        add(left, leftParams);
        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        Object[][] mouse = {{"왼쪽 클릭", RED, 1}, {"오른쪽 클릭", BLUE, 2}, {"휠 ↑", SLATE, 8}, {"휠 ↓", SLATE, 16}};
        for (Object[] m : mouse) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(118 * dp, 36 * dp);
            p.setMargins(0, 0, 0, 6 * dp);
            right.addView(item(mouseButton((String) m[0], (Integer) m[1], (Integer) m[2]), "dos.mm." + m[0], ButtonStyle.GROUP_ACTION, ButtonStyle.ROLE_OTHER), p);
        }
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rightParams.setMargins(0, 100 * dp, 14 * dp, 0);
        add(right, rightParams);
    }
}
