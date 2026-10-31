package com.example.minigba;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * "꾸미기 프리셋": the saved looks and the built-in ones, with 적용 · 공유(later) · ⋯ per row,
 * "지금 모습을 프리셋으로 저장" and its sheet. Sits over the game screen like the other
 * decoration windows so the buttons stay visible.
 */
final class PresetDialog {
    interface Listener {
        /** Saves the current look; returns the preset or throws with a reason. */
        Presets.Preset onSave(String id, String name, int scope, boolean[] items) throws java.io.IOException;

        /** Applies a preset to this console (the window closes first). */
        void onApply(Presets.Preset preset);

        /** "파일로 저장": writes the .mepreset into 다운로드/Mini Emulator. */
        void onShare(Presets.Preset preset);

        /** "파일에서 가져오기": opens the file picker (the window closes first). */
        void onImportFile();

        void onClosed();
    }

    private static final int ACCENT = Color.rgb(225, 29, 72);
    private static final int TEXT = Color.rgb(31, 41, 51);
    private static final int MUTED = Color.rgb(85, 95, 110);
    private static final int BORDER = Color.rgb(228, 231, 238);

    private final Activity activity;
    private final Listener listener;
    private final String consoleName;
    private final boolean landscape;
    private final Dialog dialog;
    private final ScrollView scroll;
    private final String[] currentSummary;

    PresetDialog(Activity activity, String consoleName, boolean landscape, String[] currentSummary, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.consoleName = consoleName;
        this.landscape = landscape;
        this.currentSummary = currentSummary;
        this.dialog = new Dialog(activity);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.scroll = new ScrollView(activity);
        this.scroll.setVerticalScrollBarEnabled(false);
        this.dialog.setContentView(this.scroll);
        this.dialog.setOnDismissListener(d -> listener.onClosed());
        showList();
    }

    void show(Rect screen) {
        Window window = this.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.util.DisplayMetrics metrics = this.activity.getResources().getDisplayMetrics();
            if (screen != null && screen.width() > dp(200) && screen.height() > dp(120)) {
                int width = Math.min(screen.width(), dp(440));
                int height = Math.min(Math.max(screen.height(), dp(260)), metrics.heightPixels - screen.top - dp(8));
                window.setGravity(Gravity.TOP | Gravity.START);
                WindowManager.LayoutParams params = window.getAttributes();
                params.x = screen.left + (screen.width() - width) / 2;
                params.y = screen.top;
                window.setAttributes(params);
                window.setLayout(width, height);
            } else {
                window.setGravity(Gravity.CENTER);
                window.setLayout(Math.min(metrics.widthPixels - dp(40), dp(430)), Math.min(metrics.heightPixels - dp(40), dp(460)));
            }
        }
        this.dialog.show();
    }

    void dismiss() {
        this.dialog.dismiss();
    }

    // ---- list ----

    private void showList() {
        LinearLayout panel = panel();
        List<Presets.Preset> mine = Presets.list(this.activity);
        panel.addView(title("꾸미기 프리셋", this.consoleName + " · " + (this.landscape ? "가로" : "세로") + " · 저장된 것 " + countWithoutUndo(mine) + " · 기본 제공 3"));

        if (!mine.isEmpty()) {
            panel.addView(section("내 프리셋"));
            for (Presets.Preset preset : mine) {
                panel.addView(row(preset));
            }
        }
        panel.addView(section("기본 제공"));
        for (Presets.Preset preset : Presets.builtIn()) {
            panel.addView(row(preset));
        }

        TextView save = button("＋ 지금 모습을 프리셋으로 저장", ACCENT, Color.WHITE);
        save.setOnClickListener(view -> showSave(null));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(42));
        saveParams.setMargins(0, dp(10), 0, 0);
        panel.addView(save, saveParams);
        TextView imports = button("📂 파일에서 가져오기 (다운로드 › Mini Emulator)", Color.rgb(241, 243, 247), TEXT);
        imports.setOnClickListener(view -> {
            this.dialog.dismiss();
            this.listener.onImportFile();
        });
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, dp(38));
        importParams.setMargins(0, dp(6), 0, 0);
        panel.addView(imports, importParams);
        TextView close = button("닫기", Color.rgb(241, 243, 247), TEXT);
        close.setOnClickListener(view -> this.dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(38));
        closeParams.setMargins(0, dp(6), 0, 0);
        panel.addView(close, closeParams);
        setPanel(panel);
    }

    private static int countWithoutUndo(List<Presets.Preset> list) {
        int n = 0;
        for (Presets.Preset preset : list) {
            if (!Presets.UNDO_ID.equals(preset.id)) n++;
        }
        return n;
    }

    private View row(Presets.Preset preset) {
        boolean undo = Presets.UNDO_ID.equals(preset.id);
        LinearLayout row = new LinearLayout(this.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(undo ? Color.rgb(255, 251, 235) : Color.WHITE);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), undo ? Color.rgb(252, 211, 77) : BORDER);
        row.setBackground(background);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rowParams);

        row.addView(new Thumb(this.activity, preset.previewColors()), new LinearLayout.LayoutParams(dp(34), dp(60)));

        LinearLayout info = new LinearLayout(this.activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this.activity);
        name.setText((preset.favorite ? "★ " : "") + (undo ? "되돌리기용 · 적용 전 모습" : preset.name));
        name.setTextSize(12.5f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(TEXT);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);
        TextView summary = new TextView(this.activity);
        summary.setText(preset.builtin ? preset.modeName : preset.summary());
        summary.setTextSize(10.0f);
        summary.setTextColor(MUTED);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(summary);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        infoParams.setMargins(dp(8), 0, dp(6), 0);
        row.addView(info, infoParams);

        LinearLayout actions = new LinearLayout(this.activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        TextView apply = smallButton(undo ? "되돌리기" : "적용");
        apply.setOnClickListener(view -> {
            this.dialog.dismiss();
            this.listener.onApply(preset);
        });
        actions.addView(apply, new LinearLayout.LayoutParams(dp(58), dp(26)));
        if (!preset.builtin && !undo) {
            TextView share = smallButton("파일로 저장");
            share.setOnClickListener(view -> this.listener.onShare(preset));
            LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(dp(58), dp(26));
            shareParams.setMargins(0, dp(4), 0, 0);
            actions.addView(share, shareParams);
            TextView more = smallButton("⋯");
            more.setOnClickListener(view -> showMenu(preset));
            actions.addView(more, shareParams);
        }
        row.addView(actions);
        return row;
    }

    // ---- ⋯ menu ----

    private void showMenu(Presets.Preset preset) {
        LinearLayout panel = panel();
        panel.addView(title(preset.name, preset.summary()));
        panel.addView(menuRow("적용 (" + this.consoleName + " " + (this.landscape ? "가로" : "세로") + "에)", false, view -> {
            this.dialog.dismiss();
            this.listener.onApply(preset);
        }));
        panel.addView(menuRow("파일로 저장 (다운로드 › Mini Emulator)", false, view -> this.listener.onShare(preset)));
        panel.addView(menuRow("이름 바꾸기", false, view -> showRename(preset)));
        panel.addView(menuRow("지금 모습으로 덮어쓰기", false, view -> showSave(preset)));
        panel.addView(menuRow(preset.favorite ? "☆ 즐겨찾기 해제" : "★ 즐겨찾기 (목록 맨 위로)", false, view -> {
            preset.favorite = !preset.favorite;
            try {
                Presets.write(this.activity, preset);
            } catch (java.io.IOException e) {
                Toast.makeText(this.activity, "저장하지 못했어요: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            showList();
        }));
        panel.addView(menuRow("삭제", true, view -> {
            Presets.delete(this.activity, preset.id);
            Toast.makeText(this.activity, "\"" + preset.name + "\"을 지웠어요.", Toast.LENGTH_SHORT).show();
            showList();
        }));
        TextView back = button("뒤로", Color.rgb(241, 243, 247), TEXT);
        back.setOnClickListener(view -> showList());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, dp(38));
        backParams.setMargins(0, dp(10), 0, 0);
        panel.addView(back, backParams);
        setPanel(panel);
    }

    private void showRename(Presets.Preset preset) {
        LinearLayout panel = panel();
        panel.addView(title("이름 바꾸기", preset.name));
        EditText input = input(preset.name);
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this.activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button("취소", Color.rgb(241, 243, 247), TEXT);
        cancel.setOnClickListener(view -> showMenu(preset));
        TextView ok = button("바꾸기", ACCENT, Color.WHITE);
        ok.setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this.activity, "이름을 적어 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            preset.name = name;
            try {
                Presets.write(this.activity, preset);
            } catch (java.io.IOException e) {
                Toast.makeText(this.activity, "저장하지 못했어요: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            showList();
        });
        actions.addView(cancel, buttonParams(false));
        actions.addView(ok, buttonParams(true));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(42));
        actionsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(actions, actionsParams);
        setPanel(panel);
    }

    // ---- save ----

    private void showSave(Presets.Preset overwrite) {
        LinearLayout panel = panel();
        panel.addView(title(overwrite != null ? "지금 모습으로 덮어쓰기" : "프리셋으로 저장",
                "지금 " + this.consoleName + " " + (this.landscape ? "가로" : "세로") + " 화면의 모습을 담아요"));
        panel.addView(section("이름"));
        EditText input = input(overwrite != null ? overwrite.name : this.consoleName + " 꾸미기 " + new java.text.SimpleDateFormat("M/d", java.util.Locale.KOREA).format(new java.util.Date()));
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(section("담을 것"));
        final boolean[] items = {true, true, true, true, true, false};
        final TextView[] checks = new TextView[items.length];
        for (int i = 0; i < items.length; i++) {
            final int item = i;
            TextView check = check(Presets.ITEM_NAMES[i], this.currentSummary != null && i < this.currentSummary.length ? this.currentSummary[i] : "", items[i]);
            check.setOnClickListener(view -> {
                items[item] = !items[item];
                setChecked(check, items[item]);
            });
            checks[i] = check;
            panel.addView(check);
        }
        panel.addView(section("범위"));
        final int[] scope = {Presets.SCOPE_ONE};
        LinearLayout chips = new LinearLayout(this.activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] scopeNames = {this.consoleName + " " + (this.landscape ? "가로" : "세로") + "만", "세로 + 가로", "모든 게임기 공통"};
        final TextView[] chipViews = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int value = i;
            TextView chip = chip(scopeNames[i]);
            chip.setOnClickListener(view -> {
                scope[0] = value;
                for (int k = 0; k < 3; k++) setChipSelected(chipViews[k], k == value);
            });
            chipViews[i] = chip;
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
            chipParams.setMargins(dp(2), 0, dp(2), 0);
            chips.addView(chip, chipParams);
        }
        setChipSelected(chipViews[0], true);
        panel.addView(chips);
        TextView hint = new TextView(this.activity);
        hint.setText("\"모든 게임기 공통\"은 케이스 · 사진 · 버튼 색처럼 기종과 무관한 것만 담고 배치는 빼요.");
        hint.setTextSize(10.0f);
        hint.setTextColor(MUTED);
        hint.setPadding(dp(2), dp(4), 0, 0);
        panel.addView(hint);

        LinearLayout actions = new LinearLayout(this.activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button("취소", Color.rgb(241, 243, 247), TEXT);
        cancel.setOnClickListener(view -> showList());
        TextView ok = button("저장", ACCENT, Color.WHITE);
        ok.setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this.activity, "이름을 적어 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean any = false;
            for (boolean item : items) any |= item;
            if (!any) {
                Toast.makeText(this.activity, "담을 것을 하나는 골라 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                Presets.Preset saved = this.listener.onSave(overwrite != null ? overwrite.id : Presets.newId(), name, scope[0], items);
                if (overwrite != null && overwrite.favorite) {
                    saved.favorite = true;
                    Presets.write(this.activity, saved);
                }
                Toast.makeText(this.activity, "\"" + name + "\"으로 저장했어요.", Toast.LENGTH_SHORT).show();
                showList();
            } catch (java.io.IOException e) {
                Toast.makeText(this.activity, "저장하지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(cancel, buttonParams(false));
        actions.addView(ok, buttonParams(true));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(42));
        actionsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(actions, actionsParams);
        setPanel(panel);
    }

    // ---- import preview ----

    /** Shows a preset just read from a file: what it holds, where to apply, keep or apply now. */
    void showImport(Presets.Preset preset) {
        LinearLayout panel = panel();
        String from = ("all".equals(preset.mode) ? "모든 게임기용" : preset.modeName + "에서 저장");
        panel.addView(title("프리셋 가져오기 · " + preset.name, from + " · " + preset.summary()));
        LinearLayout body = new LinearLayout(this.activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(8), 0, 0);
        body.addView(new Thumb(this.activity, preset.previewColors()), new LinearLayout.LayoutParams(dp(44), dp(78)));
        LinearLayout right = new LinearLayout(this.activity);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView holds = new TextView(this.activity);
        holds.setText("담긴 것");
        holds.setTextSize(11.5f);
        holds.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        holds.setTextColor(MUTED);
        right.addView(holds);
        org.json.JSONObject any = preset.data.optJSONObject("P");
        if (any == null || any.length() == 0) any = preset.data.optJSONObject("L");
        String[] lines = {
                preset.has(Presets.ITEM_SHELL) ? "배경 케이스" : null,
                any != null && any.has("photo") ? "사진 배경" : null,
                any != null && any.has("alpha") ? "버튼 투명도" : null,
                any != null && any.has("buttons") ? "버튼 꾸미기" : null,
                any != null && any.has("layout") ? "버튼 배치" : null,
                preset.has(Presets.ITEM_FILTER) ? "화면 필터" : null,
        };
        for (String line : lines) {
            if (line == null) continue;
            TextView item = new TextView(this.activity);
            item.setText("✓ " + line);
            item.setTextSize(12.0f);
            item.setTextColor(TEXT);
            right.addView(item);
        }
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        rightParams.setMargins(dp(10), 0, 0, 0);
        body.addView(right, rightParams);
        panel.addView(body);
        TextView where = new TextView(this.activity);
        where.setText("적용할 곳: " + this.consoleName + " " + (preset.hasOrientation(false) && preset.hasOrientation(true) ? "세로 · 가로"
                : preset.hasOrientation(true) ? "가로" : "세로") + "\n지금 모습은 \"되돌리기용\"으로 보관돼요."
                + (!"all".equals(preset.mode) && !preset.modeName.equals(this.consoleName) ? "\n기종이 달라 버튼 배치는 빼고 적용해요." : ""));
        where.setTextSize(10.5f);
        where.setTextColor(MUTED);
        where.setPadding(0, dp(8), 0, 0);
        panel.addView(where);
        LinearLayout actions = new LinearLayout(this.activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView keep = button("목록에만 넣기", Color.rgb(241, 243, 247), TEXT);
        keep.setOnClickListener(view -> {
            Toast.makeText(this.activity, "\"" + preset.name + "\"을 프리셋 목록에 넣었어요.", Toast.LENGTH_SHORT).show();
            showList();
        });
        TextView apply = button("바로 적용", ACCENT, Color.WHITE);
        apply.setOnClickListener(view -> {
            this.dialog.dismiss();
            this.listener.onApply(preset);
        });
        actions.addView(keep, buttonParams(false));
        actions.addView(apply, buttonParams(true));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(42));
        actionsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(actions, actionsParams);
        setPanel(panel);
    }

    // ---- widgets ----

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this.activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        panel.setBackground(background);
        return panel;
    }

    private void setPanel(View panel) {
        this.scroll.removeAllViews();
        this.scroll.addView(panel);
        this.scroll.scrollTo(0, 0);
    }

    private View title(String text, String sub) {
        LinearLayout box = new LinearLayout(this.activity);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this.activity);
        title.setText(text);
        title.setTextSize(16.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        box.addView(title);
        TextView subtitle = new TextView(this.activity);
        subtitle.setText(sub);
        subtitle.setTextSize(11.0f);
        subtitle.setTextColor(MUTED);
        box.addView(subtitle);
        return box;
    }

    private TextView section(String text) {
        TextView view = new TextView(this.activity);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(MUTED);
        view.setPadding(0, dp(9), 0, dp(5));
        return view;
    }

    private TextView menuRow(String text, boolean danger, View.OnClickListener onClick) {
        TextView row = new TextView(this.activity);
        row.setText(text);
        row.setTextSize(13.0f);
        row.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.setTextColor(danger ? Color.rgb(185, 28, 28) : TEXT);
        row.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(241, 243, 247));
        background.setCornerRadius(dp(12));
        row.setBackground(background);
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(42));
        params.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    private EditText input(String text) {
        EditText input = new EditText(this.activity);
        input.setText(text);
        input.setSelection(text.length());
        input.setTextSize(13.5f);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), Color.rgb(214, 218, 226));
        input.setBackground(background);
        return input;
    }

    private TextView check(String name, String detail, boolean on) {
        TextView view = new TextView(this.activity);
        view.setTextSize(12.0f);
        view.setTextColor(TEXT);
        view.setPadding(dp(4), dp(5), dp(4), dp(5));
        view.setTag(new String[]{name, detail});
        setChecked(view, on);
        return view;
    }

    private void setChecked(TextView view, boolean on) {
        String[] parts = (String[]) view.getTag();
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder((on ? "☑ " : "☐ ") + parts[0]);
        if (parts[1] != null && !parts[1].isEmpty()) {
            int start = text.length();
            text.append("   " + parts[1]);
            text.setSpan(new android.text.style.ForegroundColorSpan(MUTED), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setSpan(new android.text.style.ForegroundColorSpan(on ? ACCENT : Color.rgb(160, 166, 178)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }

    private TextView chip(String text) {
        TextView chip = new TextView(this.activity);
        chip.setText(text);
        chip.setTextSize(11.0f);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        setChipSelected(chip, false);
        return chip;
    }

    private void setChipSelected(TextView chip, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(15));
        background.setColor(selected ? Color.rgb(255, 241, 243) : Color.WHITE);
        background.setStroke(dp(selected ? 2 : 1), selected ? ACCENT : Color.rgb(214, 218, 226));
        chip.setBackground(background);
        chip.setTextColor(selected ? Color.rgb(159, 18, 57) : TEXT);
        chip.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    }

    private TextView button(String text, int background, int textColor) {
        TextView button = new TextView(this.activity);
        button.setText(text);
        button.setTextSize(13.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(12));
        button.setBackground(drawable);
        return button;
    }

    private TextView smallButton(String text) {
        TextView button = button(text, Color.rgb(241, 243, 247), TEXT);
        button.setTextSize(11.0f);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(boolean gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
        params.setMargins(gap ? dp(8) : 0, 0, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * this.activity.getResources().getDisplayMetrics().density);
    }

    /** A tiny phone: case tint, dark screen, a cross and two round buttons in the preset's colours. */
    static final class Thumb extends View {
        private final int[] colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Thumb(Context context, int[] colors) {
            super(context);
            this.colors = colors;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setColor(colors[0]);
            canvas.drawRoundRect(new RectF(0, 0, w, h), w * 0.18f, w * 0.18f, paint);
            paint.setColor(Color.rgb(13, 15, 20));
            canvas.drawRect(w * 0.12f, h * 0.1f, w * 0.88f, h * 0.4f, paint);
            paint.setColor(colors[1]);
            float cx = w * 0.3f;
            float cy = h * 0.65f;
            float arm = w * 0.1f;
            canvas.drawRect(cx - arm / 2, cy - arm * 1.5f, cx + arm / 2, cy + arm * 1.5f, paint);
            canvas.drawRect(cx - arm * 1.5f, cy - arm / 2, cx + arm * 1.5f, cy + arm / 2, paint);
            paint.setColor(colors[2]);
            canvas.drawCircle(w * 0.78f, h * 0.58f, w * 0.1f, paint);
            paint.setColor(colors[3]);
            canvas.drawCircle(w * 0.62f, h * 0.72f, w * 0.1f, paint);
        }
    }
}
