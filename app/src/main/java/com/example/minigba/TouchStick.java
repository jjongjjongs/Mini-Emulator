package com.example.minigba;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 화면 터치로 오른쪽 스틱 (0.0.401): a finger dragged on the game screen tilts the right stick
 * by how far it moved from where it touched down (like turning the view in a phone FPS); let
 * go, it centres. Per game: off, the screen's right half, or all of it; how far a drag goes;
 * up and down the other way. Nothing is drawn on the game's picture: the right stick on the
 * pad shows the tilt.
 */
final class TouchStick {
    interface Output {
        void stick(float x, float y);
    }

    static final int OFF = 0;
    static final int RIGHT_HALF = 1;
    static final int WHOLE = 2;
    static final float[] SENS = {0.6f, 0.8f, 1.0f, 1.4f};
    static final String[] SENS_NAMES = {"느리게", "조금 느리게", "보통", "빠르게"};
    static final String[] AREA_SHORT = {"꺼짐", "오른쪽 절반", "화면 전체"};

    private final float density;
    private final Output output;
    private int area;
    private float radius;
    private boolean invert;
    private int pointer = -1;
    private float downX;
    private float downY;
    // (0.0.402) 마우스식: the speed of the drag tilts the stick; 반응 곡선 보정.
    private boolean mouse;
    private float curve = 1f;
    private float speedFull;
    private float lastX;
    private float lastY;
    private long lastTime;
    private float smoothX;
    private float smoothY;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stop = this::stopTurn;

    private void stopTurn() {
        if (this.pointer >= 0 && this.mouse) {
            this.smoothX = 0;
            this.smoothY = 0;
            this.output.stick(0, 0);
        }
    }
    static final String[] MODE_NAMES = {"스틱식", "마우스식"};
    static final String[] CURVE_NAMES = {"그대로", "약하게", "강하게"};
    private static final float[] CURVE_POWER = {1f, 1.7f, 2.6f};

    TouchStick(Context context, Output output) {
        this.density = context.getResources().getDisplayMetrics().density;
        this.output = output;
    }

    static String key(String game) {
        return "touch_rstick:" + game;
    }

    static int area(SharedPreferences prefs, String game) {
        return Math.max(0, Math.min(2, prefs.getInt(key(game), OFF)));
    }

    static int sens(SharedPreferences prefs, String game) {
        return Math.max(0, Math.min(SENS.length - 1, prefs.getInt("touch_rstick_sens:" + game, 2)));
    }

    static boolean inverted(SharedPreferences prefs, String game) {
        return prefs.getBoolean("touch_rstick_inv:" + game, false);
    }

    static String summary(SharedPreferences prefs, String game) {
        int area = area(prefs, game);
        return area == OFF ? "꺼짐" : AREA_SHORT[area] + " · " + MODE_NAMES[mode(prefs, game)];
    }

    void configure(SharedPreferences prefs, String game) {
        this.area = area(prefs, game);
        // A drag of 64dp is full tilt at 보통.
        this.radius = 64 * this.density / SENS[sens(prefs, game)];
        this.invert = inverted(prefs, game);
        this.mouse = mode(prefs, game) == 1;
        this.curve = CURVE_POWER[curve(prefs, game)];
        // 마우스식: a drag of 900dp a second is full tilt at 보통.
        this.speedFull = 900 * this.density / SENS[sens(prefs, game)];
    }

    static int mode(SharedPreferences prefs, String game) {
        return Math.max(0, Math.min(1, prefs.getInt("touch_rstick_mode:" + game, 0)));
    }

    static int curve(SharedPreferences prefs, String game) {
        return Math.max(0, Math.min(2, prefs.getInt("touch_rstick_curve:" + game, 0)));
    }

    // 반응 곡선 보정: small tilts made bigger (magnitude^(1/p)), so a game's slow start is skipped.
    private void send(float x, float y) {
        float length = (float) Math.hypot(x, y);
        if (length > 1f) {
            x /= length;
            y /= length;
            length = 1f;
        }
        if (this.curve != 1f && length > 0.0001f) {
            float shaped = (float) Math.pow(length, 1.0 / this.curve);
            x = x / length * shaped;
            y = y / length * shaped;
        }
        this.output.stick(x, this.invert ? -y : y);
    }

    int area() {
        return this.area;
    }

    /** Whether a finger coming down here is the stick's (the rest stays the screen's own). */
    boolean claims(View view, MotionEvent event) {
        return this.area == WHOLE || (this.area == RIGHT_HALF && event.getX() >= view.getWidth() / 2f);
    }

    boolean onTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            this.pointer = event.getPointerId(0);
            this.downX = this.lastX = event.getX();
            this.downY = this.lastY = event.getY();
            this.lastTime = event.getEventTime();
            this.smoothX = 0;
            this.smoothY = 0;
            return true;
        }
        int index = this.pointer < 0 ? -1 : event.findPointerIndex(this.pointer);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == this.pointer)) {
            this.pointer = -1;
            this.handler.removeCallbacks(this.stop);
            this.output.stick(0, 0);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && index >= 0) {
            float ex = event.getX(index);
            float ey = event.getY(index);
            if (!this.mouse) {
                send((ex - this.downX) / this.radius, (ey - this.downY) / this.radius);
                return true;
            }
            // 마우스식: the drag's speed, smoothed a little; a still finger stops the turn.
            long now = event.getEventTime();
            float dt = Math.max(4, now - this.lastTime) / 1000f;
            float vx = (ex - this.lastX) / dt / this.speedFull;
            float vy = (ey - this.lastY) / dt / this.speedFull;
            this.lastX = ex;
            this.lastY = ey;
            this.lastTime = now;
            this.smoothX = this.smoothX * 0.5f + vx * 0.5f;
            this.smoothY = this.smoothY * 0.5f + vy * 0.5f;
            send(this.smoothX, this.smoothY);
            this.handler.removeCallbacks(this.stop);
            this.handler.postDelayed(this.stop, 60);
        }
        return true;
    }

    // ---- The settings sheet (메뉴 › 조작 › 화면 터치로 오른쪽 스틱) ----

    /**
     * conflict: a line on what the screen's touch already does in this mode (Vita's front
     * touch, Wii U's GamePad screen), or null. onChanged runs after every change.
     */
    static Dialog showSheet(Context context, SharedPreferences prefs, String game, String gameTitle, String conflict, Runnable onChanged) {
        float d = context.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(d, 18), dp(d, 18), dp(d, 18), dp(d, 16));
        panel.setBackground(round(Color.WHITE, dp(d, 22)));
        TextView title = text(context, "화면 터치로 오른쪽 스틱", 17, Color.rgb(31, 41, 51), true);
        panel.addView(title);
        panel.addView(text(context, (gameTitle == null ? "" : gameTitle + " · ") + "이 게임에만 · 카메라 · 조준용", 12, Color.rgb(122, 130, 140), false));
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            body.removeAllViews();
            int area = area(prefs, game);
            body.addView(label(context, "켜기 · 터치 영역"));
            body.addView(segment(context, new String[]{"꺼짐", "오른쪽 절반", "화면 전체"}, area, choice -> {
                prefs.edit().putInt(key(game), choice).commit();
                fill[0].run();
                onChanged.run();
            }));
            body.addView(label(context, "움직이는 방식"));
            body.addView(segment(context, MODE_NAMES, mode(prefs, game), choice -> {
                prefs.edit().putInt("touch_rstick_mode:" + game, choice).commit();
                fill[0].run();
                onChanged.run();
            }));
            body.addView(hint(context, "스틱식: 민 거리만큼 기울고, 손을 멈춰도 계속 돌아요.\n마우스식: 미는 속도만큼 기울어요. 빨리 밀면 빨리 돌고, 멈추면 바로 멈춰요."));
            body.addView(label(context, "감도"));
            body.addView(segment(context, SENS_NAMES, sens(prefs, game), choice -> {
                prefs.edit().putInt("touch_rstick_sens:" + game, choice).commit();
                fill[0].run();
                onChanged.run();
            }));
            body.addView(label(context, "반응 곡선 보정"));
            body.addView(segment(context, CURVE_NAMES, curve(prefs, game), choice -> {
                prefs.edit().putInt("touch_rstick_curve:" + game, choice).commit();
                fill[0].run();
                onChanged.run();
            }));
            body.addView(hint(context, "게임의 \"살짝 기울이면 느리게\" 구간을 건너뛰어요. 강하게일수록 조금만 밀어도 바로 돌아요. 너무 휙휙 돌면 낮추세요."));
            body.addView(label(context, "위아래"));
            body.addView(segment(context, new String[]{"그대로", "반전 (비행기식)"}, inverted(prefs, game) ? 1 : 0, choice -> {
                prefs.edit().putBoolean("touch_rstick_inv:" + game, choice == 1).commit();
                fill[0].run();
                onChanged.run();
            }));
            TextView note = text(context, "게임 화면을 밀면 오른쪽 스틱이 미는 만큼 기울고, 손을 떼면 가운데로 돌아와요. 화면 오른쪽 스틱 · 게임패드 오른쪽 스틱도 그대로 같이 써요."
                    + (conflict != null ? "\n\n" + conflict : ""), 12, Color.rgb(17, 94, 89), false);
            note.setPadding(dp(d, 12), dp(d, 10), dp(d, 12), dp(d, 10));
            note.setBackground(round(Color.rgb(240, 253, 250), dp(d, 12)));
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.setMargins(0, dp(d, 12), 0, 0);
            body.addView(note, noteParams);
        };
        fill[0].run();
        TextView close = text(context, "닫고 게임에서 바로 밀어 보기", 14, Color.rgb(75, 85, 99), true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(Color.rgb(241, 242, 244), dp(d, 14)));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(d, 48));
        closeParams.setMargins(0, dp(d, 12), 0, 0);
        panel.addView(close, closeParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(d, 40), dp(d, 380)), -2);
        }
        dialog.show();
        return dialog;
    }

    private interface Pick {
        void picked(int index);
    }

    private static View segment(Context context, String[] names, int current, Pick pick) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(context);
        for (int k = 0; k < names.length; k++) {
            final int index = k;
            boolean on = k == current;
            TextView chip = text(context, names[k], 12.5f, on ? Color.WHITE : Color.rgb(75, 85, 99), true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setBackground(round(on ? Color.rgb(29, 78, 216) : Color.rgb(229, 231, 235), dp(d, 10)));
            chip.setOnClickListener(v -> pick.picked(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(d, 40), 1);
            params.setMargins(k == 0 ? 0 : dp(d, 5), 0, 0, 0);
            row.addView(chip, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, dp(d, 6), 0, 0);
        row.setLayoutParams(rowParams);
        return row;
    }

    private static TextView hint(Context context, String value) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView view = text(context, value, 11, Color.rgb(107, 114, 128), false);
        view.setPadding(dp(d, 2), dp(d, 6), 0, 0);
        return view;
    }

    private static TextView label(Context context, String value) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView view = text(context, value, 12, Color.rgb(75, 85, 99), true);
        view.setPadding(dp(d, 2), dp(d, 12), 0, 0);
        return view;
    }

    private static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Theme.text(color));
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Theme.bg(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }
}
