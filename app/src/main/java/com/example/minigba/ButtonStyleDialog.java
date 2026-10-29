package com.example.minigba;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * "버튼 꾸미기": the colour theme, shape, letters, sizes and press effects of the on-screen
 * buttons. The window sits over the game screen (top in portrait, middle in landscape) and
 * leaves the buttons visible, and every change is applied to them at once through the listener.
 */
final class ButtonStyleDialog {
    interface Listener {
        /** A setting changed; draw the buttons with this style now. */
        void onChange(ButtonStyle style);

        /** "완료": keep this style, for the game alone when perGame. */
        void onDone(ButtonStyle style, boolean perGame);

        /** "가로/세로에도 적용": save the same style for the other screen direction. */
        void onCopyToOther(ButtonStyle style, boolean perGame);

        /** "초기화": back to the built-in look (and drop the game's own style when perGame). */
        void onReset(boolean perGame);

        /** The window went away, by any button or the back key. */
        void onClosed();
    }

    private static final int ACCENT = Color.rgb(225, 29, 72);
    private static final int ACCENT_SOFT = Color.rgb(255, 241, 243);
    private static final int ACCENT_TEXT = Color.rgb(159, 18, 57);
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(85, 95, 110);
    private static final int CHIP_BORDER = Color.rgb(214, 218, 226);
    private static final int[] SWATCH_THEMES = {
            0,
            Color.rgb(227, 38, 58), Color.rgb(248, 179, 26), Color.rgb(34, 179, 90), Color.rgb(47, 111, 224),
    };

    private final Activity activity;
    private final Listener listener;
    private final ButtonStyle style;
    private final boolean landscape;
    private boolean perGame;
    private final Dialog dialog;
    private final LinearLayout panel;
    private final TextView[] themeChips = new TextView[ButtonStyle.THEME_NAMES.length];
    private final TextView[] shapeChips = new TextView[ButtonStyle.SHAPE_NAMES.length];
    private final TextView[] labelChips = new TextView[ButtonStyle.LABEL_NAMES.length];
    private final TextView[] customChips = new TextView[3];
    private LinearLayout customRow;
    private boolean done;

    ButtonStyleDialog(Activity activity, ButtonStyle initial, boolean perGame, boolean landscape, String consoleName, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.style = initial.copy();
        this.perGame = perGame;
        this.landscape = landscape;
        this.dialog = new Dialog(activity);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(activity);
        this.panel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        panel.setBackground(background);

        TextView title = new TextView(activity);
        title.setText("버튼 꾸미기");
        title.setTextSize(16.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        panel.addView(title);
        TextView sub = new TextView(activity);
        sub.setText(consoleName + " · " + (landscape ? "가로" : "세로") + " · 바꾸면 바로 적용돼요");
        sub.setTextSize(11.5f);
        sub.setTextColor(MUTED);
        panel.addView(sub);

        // ---- 색 테마 ----
        panel.addView(section("색 테마"));
        LinearLayout themeRows = new LinearLayout(activity);
        themeRows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(themeRows);
        LinearLayout row = null;
        for (int i = 0; i < ButtonStyle.THEME_NAMES.length; i++) {
            if (i % 3 == 0) {
                row = chipRow();
                themeRows.addView(row);
            }
            final int theme = i;
            TextView chip = chip(themeText(i), view -> {
                this.style.theme = theme;
                refreshChips();
                changed();
            });
            this.themeChips[i] = chip;
            row.addView(chip, chipParams());
        }
        this.customRow = chipRow();
        String[] customNames = {"방향키", "A · B · X · Y", "L · R · 시작"};
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            TextView chip = chip(customNames[i], view -> pickColor(slot, customNames[slot]));
            this.customChips[i] = chip;
            this.customRow.addView(chip, chipParams());
        }
        panel.addView(this.customRow);

        // ---- 모양 · 글자 ----
        panel.addView(section("모양 · 글자"));
        LinearLayout shapeRow = chipRow();
        for (int i = 0; i < ButtonStyle.SHAPE_NAMES.length; i++) {
            final int shape = i;
            TextView chip = chip(ButtonStyle.SHAPE_NAMES[i], view -> {
                this.style.shape = shape;
                refreshChips();
                changed();
            });
            this.shapeChips[i] = chip;
            shapeRow.addView(chip, chipParams());
        }
        panel.addView(shapeRow);
        LinearLayout labelRow = chipRow();
        for (int i = 0; i < ButtonStyle.LABEL_NAMES.length; i++) {
            final int mode = i;
            TextView chip = chip(ButtonStyle.LABEL_NAMES[i], view -> {
                this.style.label = mode;
                refreshChips();
                changed();
            });
            this.labelChips[i] = chip;
            labelRow.addView(chip, chipParams());
        }
        panel.addView(labelRow);

        // ---- 크기 ----
        panel.addView(section("크기"));
        panel.addView(slider("전체", this.style.sizeAll, value -> this.style.sizeAll = value));
        for (int i = 0; i < ButtonStyle.GROUP_NAMES.length; i++) {
            final int group = i;
            panel.addView(slider(ButtonStyle.GROUP_NAMES[i], this.style.sizes[i], value -> this.style.sizes[group] = value));
        }

        // ---- 누를 때 ----
        panel.addView(section("누를 때"));
        panel.addView(toggle("눌린 버튼 밝게 빛남 (네온)", this.style.glow, on -> {
            this.style.glow = on;
            changed();
        }));
        panel.addView(toggle("살짝 커졌다 돌아오기", this.style.bounce, on -> {
            this.style.bounce = on;
            changed();
        }));
        panel.addView(toggle("이 게임에만 적용", this.perGame, on -> this.perGame = on));

        // ---- actions ----
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(44));
        actionsParams.setMargins(0, dp(14), 0, 0);
        TextView reset = button("초기화", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        reset.setOnClickListener(view -> {
            this.done = true;
            listener.onReset(this.perGame);
            this.dialog.dismiss();
        });
        TextView copy = button(landscape ? "세로에도 적용" : "가로에도 적용", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        copy.setOnClickListener(view -> {
            listener.onCopyToOther(this.style.copy(), this.perGame);
            android.widget.Toast.makeText(activity, (landscape ? "세로" : "가로") + " 화면에도 같은 꾸미기를 저장했어요.", android.widget.Toast.LENGTH_SHORT).show();
        });
        TextView finish = button("완료", ACCENT, Color.WHITE);
        finish.setOnClickListener(view -> {
            this.done = true;
            listener.onDone(this.style.copy(), this.perGame);
            this.dialog.dismiss();
        });
        actions.addView(reset, buttonParams(1.0f, false));
        actions.addView(copy, buttonParams(1.3f, true));
        actions.addView(finish, buttonParams(1.0f, true));
        panel.addView(actions, actionsParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        this.dialog.setContentView(scroll);
        this.dialog.setOnDismissListener(d -> {
            if (!this.done) {
                // Backed out: the buttons go back to what was saved.
                listener.onChange(initial.copy());
            }
            listener.onClosed();
        });
        refreshChips();
    }

    /**
     * Opens the window over the game screen (its frame on the window, when known) so the
     * buttons below or beside it stay in view while they change; otherwise over the top.
     */
    void show(android.graphics.Rect screen) {
        Window window = this.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            // The buttons stay visible and undimmed, so the change shows.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.util.DisplayMetrics metrics = this.activity.getResources().getDisplayMetrics();
            int minHeight = dp(230);
            if (screen != null && screen.width() > dp(200) && screen.height() > dp(120)) {
                // Fill the screen's frame, growing downward a little when it is very short.
                int width = Math.min(screen.width(), dp(440));
                int height = Math.min(Math.max(screen.height(), minHeight), metrics.heightPixels - screen.top - dp(8));
                window.setGravity(Gravity.TOP | Gravity.START);
                WindowManager.LayoutParams params = window.getAttributes();
                params.x = screen.left + (screen.width() - width) / 2;
                params.y = screen.top;
                window.setAttributes(params);
                window.setLayout(width, height);
            } else if (this.landscape) {
                window.setGravity(Gravity.CENTER);
                window.setLayout(Math.min(metrics.widthPixels - dp(40), dp(430)), Math.min(metrics.heightPixels - dp(24), dp(360)));
            } else {
                window.setGravity(Gravity.TOP);
                WindowManager.LayoutParams params = window.getAttributes();
                params.y = dp(28);
                window.setAttributes(params);
                window.setLayout(Math.min(metrics.widthPixels - dp(24), dp(400)), Math.min((int) (metrics.heightPixels * 0.42f), dp(420)));
            }
        }
        this.dialog.show();
    }

    private void changed() {
        this.listener.onChange(this.style.copy());
    }

    // ---- custom colours ----

    private void pickColor(int slot, String name) {
        Dialog picker = new Dialog(this.activity);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this.activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        box.setBackground(background);
        TextView title = new TextView(this.activity);
        title.setText(name + " 색");
        title.setTextSize(15.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        box.addView(title);

        View preview = new View(this.activity);
        GradientDrawable swatch = new GradientDrawable();
        swatch.setCornerRadius(dp(12));
        swatch.setStroke(dp(1), CHIP_BORDER);
        preview.setBackground(swatch);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(44));
        previewParams.setMargins(0, dp(10), 0, dp(4));
        box.addView(preview, previewParams);

        float[] hsv = new float[3];
        Color.colorToHSV(this.style.custom[slot], hsv);
        final int[] current = {this.style.custom[slot]};
        Runnable update = () -> {
            current[0] = Color.HSVToColor(hsv);
            swatch.setColor(current[0]);
            this.style.custom[slot] = current[0];
            refreshChips();
            changed();
        };
        update.run();
        box.addView(hsvSlider("색상", 360, Math.round(hsv[0]), value -> {
            hsv[0] = value;
            update.run();
        }));
        box.addView(hsvSlider("채도", 100, Math.round(hsv[1] * 100), value -> {
            hsv[1] = value / 100.0f;
            update.run();
        }));
        box.addView(hsvSlider("밝기", 100, Math.round(hsv[2] * 100), value -> {
            hsv[2] = value / 100.0f;
            update.run();
        }));
        TextView ok = button("이 색으로", ACCENT, Color.WHITE);
        ok.setOnClickListener(view -> picker.dismiss());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(-1, dp(44));
        okParams.setMargins(0, dp(12), 0, 0);
        box.addView(ok, okParams);
        picker.setContentView(box);
        Window window = picker.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(Math.min(this.activity.getResources().getDisplayMetrics().widthPixels - dp(40), dp(360)), -2);
        }
        picker.show();
    }

    private View hsvSlider(String name, int max, int value, IntConsumer onValue) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this.activity);
        label.setText(name);
        label.setTextSize(12.5f);
        label.setTextColor(TEXT);
        row.addView(label, new LinearLayout.LayoutParams(dp(40), -2));
        SeekBar bar = new SeekBar(this.activity);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) onValue.accept(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return row;
    }

    // ---- widgets ----

    private interface IntConsumer {
        void accept(int value);
    }

    private interface BoolConsumer {
        void accept(boolean value);
    }

    private TextView section(String text) {
        TextView view = new TextView(this.activity);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(MUTED);
        view.setPadding(0, dp(9), 0, dp(4));
        return view;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(this.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private TextView chip(CharSequence text, View.OnClickListener onClick) {
        TextView chip = new TextView(this.activity);
        chip.setText(text);
        chip.setTextSize(11.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(4), 0, dp(4), 0);
        chip.setOnClickListener(onClick);
        setChipSelected(chip, false);
        return chip;
    }

    private void setChipSelected(TextView chip, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(17));
        background.setColor(selected ? ACCENT_SOFT : Color.WHITE);
        background.setStroke(dp(selected ? 2 : 1), selected ? ACCENT : CHIP_BORDER);
        chip.setBackground(background);
        chip.setTextColor(selected ? ACCENT_TEXT : TEXT);
        chip.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    }

    // The theme's name with a few dots in its colours in front.
    private CharSequence themeText(int theme) {
        ButtonStyle probe = new ButtonStyle();
        probe.theme = theme;
        System.arraycopy(this.style.custom, 0, probe.custom, 0, probe.custom.length);
        int[] colors;
        switch (theme) {
            case ButtonStyle.THEME_DEFAULT:
                colors = new int[]{Color.rgb(58, 58, 63), Color.rgb(227, 38, 58), Color.rgb(24, 182, 204)};
                break;
            case ButtonStyle.THEME_CUSTOM:
                colors = new int[]{probe.custom[0], probe.custom[1], probe.custom[2]};
                break;
            default:
                colors = new int[]{probe.color(ButtonStyle.ROLE_A), probe.color(ButtonStyle.ROLE_B), probe.color(ButtonStyle.ROLE_X), probe.color(ButtonStyle.ROLE_Y)};
                if (theme == ButtonStyle.THEME_WHITE || theme == ButtonStyle.THEME_BLACK || theme == ButtonStyle.THEME_OUTLINE) {
                    colors = new int[]{probe.color(ButtonStyle.ROLE_A), probe.color(ButtonStyle.ROLE_DPAD)};
                }
        }
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int color : colors) {
            int start = text.length();
            text.append("●");
            int shown = color == 0 ? Color.GRAY : ButtonStyle.isLight(color) ? ButtonStyle.darker(color, 0.18f) : color;
            text.setSpan(new ForegroundColorSpan(shown), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(" ").append(ButtonStyle.THEME_NAMES[theme]);
        return text;
    }

    private void refreshChips() {
        for (int i = 0; i < this.themeChips.length; i++) {
            this.themeChips[i].setText(themeText(i));
            setChipSelected(this.themeChips[i], this.style.theme == i);
        }
        for (int i = 0; i < this.shapeChips.length; i++) {
            setChipSelected(this.shapeChips[i], this.style.shape == i);
        }
        for (int i = 0; i < this.labelChips.length; i++) {
            setChipSelected(this.labelChips[i], this.style.label == i);
        }
        this.customRow.setVisibility(this.style.theme == ButtonStyle.THEME_CUSTOM ? View.VISIBLE : View.GONE);
        String[] names = {"방향키", "A · B · X · Y", "L · R · 시작"};
        for (int i = 0; i < 3; i++) {
            SpannableStringBuilder text = new SpannableStringBuilder("● ");
            int color = this.style.custom[i];
            text.setSpan(new ForegroundColorSpan(ButtonStyle.isLight(color) ? ButtonStyle.darker(color, 0.18f) : color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(names[i]);
            this.customChips[i].setText(text);
        }
    }

    private View slider(String name, int value, IntConsumer onValue) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this.activity);
        label.setText(name);
        label.setTextSize(12.5f);
        label.setTextColor(TEXT);
        row.addView(label, new LinearLayout.LayoutParams(dp(84), -2));
        TextView shown = new TextView(this.activity);
        shown.setText(value + "%");
        shown.setTextSize(12.0f);
        shown.setTextColor(MUTED);
        shown.setGravity(Gravity.END);
        SeekBar bar = new SeekBar(this.activity);
        bar.setMax(ButtonStyle.MAX_SIZE - ButtonStyle.MIN_SIZE);
        bar.setProgress(value - ButtonStyle.MIN_SIZE);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = progress + ButtonStyle.MIN_SIZE;
                // Snaps to 100 so the default is easy to find again.
                if (Math.abs(percent - 100) <= 3) percent = 100;
                shown.setText(percent + "%");
                if (fromUser) {
                    onValue.accept(percent);
                    changed();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(shown, new LinearLayout.LayoutParams(dp(44), -2));
        return row;
    }

    private View toggle(String name, boolean on, BoolConsumer onValue) {
        LinearLayout row = new LinearLayout(this.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView label = new TextView(this.activity);
        label.setText(name);
        label.setTextSize(12.5f);
        label.setTextColor(TEXT);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        Switch sw = new Switch(this.activity);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((view, checked) -> onValue.accept(checked));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView button(String text, int background, int textColor) {
        TextView button = new TextView(this.activity);
        button.setText(text);
        button.setTextSize(13.5f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(12));
        button.setBackground(drawable);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(float weight, boolean gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(gap ? dp(8) : 0, 0, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * this.activity.getResources().getDisplayMetrics().density);
    }
}
