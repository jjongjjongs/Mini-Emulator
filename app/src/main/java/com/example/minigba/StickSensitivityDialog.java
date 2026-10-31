package com.example.minigba;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

// "스틱 감도": a dark picker for one stick's sensitivity, 0.5x to 2x in steps of 0.05: the value
// with -/+ beside it, a ruler to drag, chips for the usual values, and 1x로 / 취소 / 적용.
final class StickSensitivityDialog {
    interface Listener {
        // thisGame: the value is for this game only; otherwise it is the default for all.
        void onApplied(float value, boolean thisGame);
    }

    static final float MIN = 0.5f;
    static final float MAX = 2.0f;
    private static final float STEP = 0.05f;
    private static final float[] CHIPS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final int ACCENT = Color.rgb(77, 208, 225);

    private final Context context;
    private final float density;
    private float value;
    private boolean thisGame = true;
    private final String gameName;
    private final float allValue;
    private TextView valueView;
    private TextView scopeNote;
    private final TextView[] scopes = new TextView[2];
    private RulerView ruler;
    private final TextView[] chips = new TextView[CHIPS.length];

    private StickSensitivityDialog(Context context, float value, float allValue, String gameName) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.value = clamp(value);
        this.allValue = clamp(allValue);
        this.gameName = gameName;
    }

    // current: the value in force for this game; allValue: the default for all games.
    static void show(Context context, String title, float current, float allValue, String gameName, Listener listener) {
        new StickSensitivityDialog(context, current, allValue, gameName).open(title, listener);
    }

    static float clamp(float value) {
        float snapped = Math.round(value / STEP) * STEP;
        return Math.max(MIN, Math.min(MAX, snapped));
    }

    static String format(float value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "x";
    }

    private int dp(float value) {
        return Math.round(value * this.density);
    }

    private void open(String title, Listener listener) {
        final Dialog dialog = new Dialog(this.context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this.context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20, 23, 28));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);

        panel.addView(text(title, 16, Color.WHITE, true));

        LinearLayout valueRow = new LinearLayout(this.context);
        valueRow.setGravity(Gravity.CENTER);
        TextView minus = roundButton("−");
        minus.setOnClickListener(v -> set(this.value - STEP));
        valueRow.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        this.valueView = text(format(this.value), 40, ACCENT, true);
        this.valueView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(dp(150), -2);
        valueParams.setMargins(dp(10), 0, dp(10), 0);
        valueRow.addView(this.valueView, valueParams);
        TextView plus = roundButton("+");
        plus.setOnClickListener(v -> set(this.value + STEP));
        valueRow.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, dp(8), 0, 0);
        panel.addView(valueRow, rowParams);

        // Where the value goes: this game only (the default), or every game.
        LinearLayout scopeRow = new LinearLayout(this.context);
        GradientDrawable scopeFrame = new GradientDrawable();
        scopeFrame.setCornerRadius(dp(20));
        scopeFrame.setStroke(dp(1.5f), Color.rgb(75, 85, 99));
        scopeRow.setBackground(scopeFrame);
        String[] scopeNames = {"이 게임만", "모든 게임"};
        for (int k = 0; k < 2; k++) {
            final boolean forThisGame = k == 0;
            TextView option = text(scopeNames[k], 13, Color.WHITE, true);
            option.setGravity(Gravity.CENTER);
            option.setOnClickListener(v -> {
                this.thisGame = forThisGame;
                refresh();
            });
            this.scopes[k] = option;
            scopeRow.addView(option, new LinearLayout.LayoutParams(0, dp(36), 1));
        }
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(-1, -2);
        scopeParams.setMargins(0, dp(10), 0, 0);
        panel.addView(scopeRow, scopeParams);
        this.scopeNote = text("", 11.5f, Color.rgb(156, 163, 175), false);
        this.scopeNote.setGravity(Gravity.CENTER);
        this.scopeNote.setPadding(0, dp(6), 0, 0);
        panel.addView(this.scopeNote);

        this.ruler = new RulerView(this.context);
        LinearLayout.LayoutParams rulerParams = new LinearLayout.LayoutParams(-1, dp(66));
        rulerParams.setMargins(0, dp(10), 0, 0);
        panel.addView(this.ruler, rulerParams);

        LinearLayout chipRow = new LinearLayout(this.context);
        for (int k = 0; k < CHIPS.length; k++) {
            final float chipValue = CHIPS[k];
            TextView chip = text(format(chipValue), 13, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> set(chipValue));
            this.chips[k] = chip;
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            chipParams.setMargins(k == 0 ? 0 : dp(3), 0, k == CHIPS.length - 1 ? 0 : dp(3), 0);
            chipRow.addView(chip, chipParams);
        }
        LinearLayout.LayoutParams chipsParams = new LinearLayout.LayoutParams(-1, -2);
        chipsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(chipRow, chipsParams);

        TextView note = text("줄자를 옆으로 밀거나 −/+ 로 0.05씩 맞출 수 있어요. 숫자가 클수록 조금만 밀어도 끝까지 기울어요. 1x가 원래 감도예요.",
                12, Color.rgb(156, 163, 175), false);
        note.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(0, dp(12), 0, 0);
        panel.addView(note, noteParams);

        LinearLayout buttons = new LinearLayout(this.context);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        TextView reset = pill("1x로", Color.TRANSPARENT, Color.WHITE, true);
        reset.setOnClickListener(v -> set(1.0f));
        buttons.addView(reset, new LinearLayout.LayoutParams(-2, dp(44)));
        buttons.addView(new View(this.context), new LinearLayout.LayoutParams(0, 1, 1));
        TextView cancel = pill("취소", Color.rgb(31, 41, 55), Color.WHITE, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(-2, dp(44)));
        TextView apply = pill("적용", ACCENT, Color.rgb(11, 26, 30), false);
        apply.setOnClickListener(v -> {
            dialog.dismiss();
            listener.onApplied(this.value, this.thisGame);
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(-2, dp(44));
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this.context);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(this.context.getResources().getDisplayMetrics().widthPixels - dp(28), dp(400)), -2);
        }
        refresh();
        dialog.show();
    }

    private void set(float value) {
        this.value = clamp(value);
        refresh();
    }

    private void refresh() {
        this.valueView.setText(format(this.value));
        for (int k = 0; k < 2; k++) {
            boolean on = (k == 0) == this.thisGame;
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(20));
            shape.setColor(on ? ACCENT : Color.TRANSPARENT);
            this.scopes[k].setBackground(shape);
            this.scopes[k].setTextColor(on ? Color.rgb(11, 26, 30) : Color.rgb(229, 231, 235));
        }
        this.scopeNote.setText(this.thisGame
                ? this.gameName + "에만 적용 · 다른 게임은 모든 게임 값(" + format(this.allValue) + ")을 써요"
                : "모든 게임의 기본값이 돼요 · 이 게임의 개별 값은 지워져요");
        this.ruler.invalidate();
        for (int k = 0; k < CHIPS.length; k++) {
            boolean on = Math.abs(CHIPS[k] - this.value) < STEP / 2;
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(20));
            shape.setColor(on ? ACCENT : Color.TRANSPARENT);
            shape.setStroke(dp(1.5f), on ? ACCENT : Color.rgb(75, 85, 99));
            this.chips[k].setBackground(shape);
            this.chips[k].setTextColor(on ? Color.rgb(11, 26, 30) : Color.rgb(229, 231, 235));
        }
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this.context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView roundButton(String label) {
        TextView view = text(label, 22, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.TRANSPARENT);
        shape.setStroke(dp(1.5f), Color.rgb(107, 114, 128));
        view.setBackground(shape);
        return view;
    }

    private TextView pill(String label, int fill, int color, boolean outlined) {
        TextView view = text(label, 14, color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        shape.setColor(fill);
        if (outlined) {
            shape.setStroke(dp(1.5f), Color.rgb(75, 85, 99));
        }
        view.setBackground(shape);
        return view;
    }

    // Ticks every 0.05 from 0.5x to 2x, taller at the halves, with the value marked; dragging
    // or tapping along it sets the value.
    private final class RulerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RulerView(Context context) {
            super(context);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
        }

        private float inset() {
            return dp(14);
        }

        private float xFor(float v) {
            return inset() + (v - MIN) / (MAX - MIN) * (getWidth() - 2 * inset());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float base = dp(30);
            int steps = Math.round((MAX - MIN) / STEP);
            for (int i = 0; i <= steps; i++) {
                float v = MIN + i * STEP;
                boolean major = i % 10 == 0;
                boolean half = i % 5 == 0;
                boolean current = Math.abs(v - StickSensitivityDialog.this.value) < STEP / 2;
                paint.setColor(current ? ACCENT : major ? Color.rgb(156, 163, 175) : Color.rgb(75, 85, 99));
                paint.setStrokeWidth(dp(current ? 3 : 1.5f));
                float height = dp(major ? 18 : half ? 12 : 8);
                float x = xFor(v);
                canvas.drawLine(x, base - height, x, base, paint);
                if (major) {
                    paint.setStrokeWidth(0);
                    paint.setTextSize(dp(12));
                    paint.setColor(current ? ACCENT : Color.rgb(156, 163, 175));
                    canvas.drawText(format(v), x, base + dp(18), paint);
                }
            }
            // The marker: a small triangle pointing down at the value.
            float x = xFor(StickSensitivityDialog.this.value);
            android.graphics.Path marker = new android.graphics.Path();
            marker.moveTo(x - dp(6), base - dp(28));
            marker.lineTo(x + dp(6), base - dp(28));
            marker.lineTo(x, base - dp(20));
            marker.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ACCENT);
            canvas.drawPath(marker, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float fraction = (event.getX() - inset()) / Math.max(1, getWidth() - 2 * inset());
                set(MIN + Math.max(0, Math.min(1, fraction)) * (MAX - MIN));
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        }
    }
}
