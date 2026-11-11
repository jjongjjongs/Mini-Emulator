package com.example.minigba;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// (0.0.415) Original Xbox games on HakuX's core (libhakuxcore.so on SDL3; cores/fetch-hakux.sh,
// GPL-3.0-or-later). The screen, layout editor and menu follow X360Activity's (this file is made
// from it); the core draws to SDL's surface and plays its own sound, the pad goes to it as
// player 1 (NativeCoreActivity.nativeSetOverlayPad). It runs in its own process (":xbox", see
// the manifest): the core runs once per process.
public final class XboxActivity extends com.rfandango.haku_x.NativeCoreActivity {
    static final String EXTRA_ACTION = "com.example.minigba.XBOX_ACTION";
    static final String EXTRA_PATH = "com.example.minigba.XBOX_PATH";
    static final String EXTRA_TITLE = "com.example.minigba.XBOX_TITLE";

    private static final int INDIGO = Color.rgb(16, 124, 16);

    private final Handler main = new Handler(Looper.getMainLooper());
    private int touchDirections;
    private SurfaceView surfaceView;
    private boolean bootStarted;
    private volatile boolean leaving;
    private String gamePath;
    private TextView statusView;
    private boolean statusHidden;

    private boolean landscape() {
        return MainActivity.sharedSettings(this).getBoolean("xbox_landscape", false);
    }

    // ---- 화면 비율 · 배치 (0.0.392) ----
    private static boolean aspect43(Context context) {
        return MainActivity.sharedSettings(context).getBoolean("xbox_aspect43", true);
    }

    private FrameLayout screenFrame;
    private FrameLayout screenHolder;
    private int screenBaseW;
    private int screenBaseH;
    private ClearShellView shellView;
    private final java.util.List<View> layoutControls = new java.util.ArrayList<>();
    private final java.util.List<String> layoutIds = new java.util.ArrayList<>();
    private final java.util.List<float[]> layoutBase = new java.util.ArrayList<>();
    private final java.util.List<View> layoutGuards = new java.util.ArrayList<>();
    private LayoutEditorView layoutEditor;
    private View contentRoot;

    private int shellStyle() {
        return MainActivity.sharedSettings(this).getInt("shell_style", ClearShellView.STYLE_CLEAR);
    }

    // (0.0.416) The buttons gathered (portrait: in one block in the middle of the space under
    // the screen; landscape: in the middle of each side column), scaled down on a small screen.
    // A layout the player edited before keeps the old places (its edits are moves from them).
    private boolean legacyLayout;
    private float padScale = 1f;
    private int landCol = 178;
    private static final int PAD_BLOCK_DP = 384;

    private boolean legacyLayoutFor() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        return !prefs.getString(layoutKey(), "").isEmpty() && !prefs.getBoolean(layoutKey() + ":v2", false);
    }

    private int u(float value) {
        return Math.round(value * this.padScale);
    }

    private int statusBarDp() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        float density = getResources().getDisplayMetrics().density;
        return id > 0 ? Math.round(getResources().getDimensionPixelSize(id) / density) : 24;
    }

    private void buildGameScreen() {
        this.layoutEditor = null;
        this.legacyLayout = legacyLayoutFor();
        setRequestedOrientation(landscape() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        int style = shellStyle();
        int body = Color.rgb(24, 26, 34);
        ClearShellView shell = new ClearShellView(this, ClearShellView.TINT_SMOKE, "MINI EMULATOR · XBOX", wide, style, INDIGO, body);
        if (style == ClearShellView.STYLE_PHOTO) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            shell.setPhoto(PhotoBackground.load(PhotoBackground.rendered(this, "xbox", wide), metrics.widthPixels, metrics.heightPixels));
        }
        this.shellView = shell;
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        int labelColor = ClearShellView.darkBackground(style, body) ? Color.rgb(230, 232, 238) : Color.rgb(58, 63, 75);

        FrameLayout screen = new FrameLayout(this);
        // SDL's surface is under this frame (installScreen), which stays see-through.
        // 화면 터치로 오른쪽 스틱 (0.0.401).
        final boolean[] mine = {false};
        screen.setOnTouchListener((view, event) -> {
            TouchStick stick = this.touchStick;
            if (stick == null || stick.area() == TouchStick.OFF || this.layoutEditor != null) return false;
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) mine[0] = stick.claims(view, event);
            return mine[0] && stick.onTouch(event);
        });
        this.statusView = text("초대 Xbox 엔진을 시작하는 중…\n처음에는 셰이더를 준비하느라 오래 걸릴 수 있어요.", 13, Color.rgb(205, 214, 244), false);
        this.statusView.setGravity(Gravity.CENTER);
        this.statusView.setVisibility(this.statusHidden ? View.GONE : View.VISIBLE);
        screen.addView(this.statusView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // The screen's frame sits in a holder of its unedited size, so a bigger or moved
        // picture (버튼 · 화면 배치 편집) moves nothing else.
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        this.screenFrame = screen;
        this.screenHolder = holder;
        LinearLayout menu = iconButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", labelColor, v -> showPauseMenu());
        this.layoutGuards.clear();
        this.layoutGuards.add(menu);
        boolean four = aspect43(this);

        if (wide) {
            // Landscape: the screen between two control columns, SELECT/PS/START below it.
            FrameLayout controls = new FrameLayout(this);
            controls.setClipChildren(false);
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int heightDp = Math.round(metrics.heightPixels / metrics.density);
            int widthDp = Math.round(metrics.widthPixels / metrics.density);
            int[] fit = landscapeScreen(widthDp, heightDp - statusBarDp(), four);
            int screenW = fit[0];
            int screenH = fit[1];
            this.landCol = (widthDp - screenW) / 2;
            this.padScale = this.legacyLayout ? 1f : Math.min(1f, (heightDp - statusBarDp()) / 384f);
            FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(dp(screenW), dp(screenH), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            screenParams.topMargin = dp(fit[2]);
            controls.addView(holder, screenParams);
            this.screenBaseW = dp(screenW);
            this.screenBaseH = dp(screenH);
            buildPadLandscape(controls);
            // (0.0.393) As the other consoles: 저장 · 재시작 top left, 회전 · 메뉴 top right.
            LinearLayout left = new LinearLayout(this);
            left.addView(cornerButton(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "저장", labelColor, v -> onSaveClicked(), null), new LinearLayout.LayoutParams(dp(48), -2));
            left.addView(cornerButton(RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", labelColor, v -> onResetClicked(), null), new LinearLayout.LayoutParams(dp(48), -2));
            FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            leftParams.setMargins(dp(6), dp(6), 0, 0);
            controls.addView(left, leftParams);
            LinearLayout right = new LinearLayout(this);
            right.addView(cornerButton(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "회전", labelColor, v -> toggleOrientation(), null), new LinearLayout.LayoutParams(dp(48), -2));
            right.addView(cornerButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", labelColor, v -> showPauseMenu(), null), new LinearLayout.LayoutParams(dp(48), -2));
            FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            rightParams.setMargins(0, dp(6), dp(6), 0);
            controls.addView(right, rightParams);
            this.layoutGuards.clear();
            this.layoutGuards.add(left);
            this.layoutGuards.add(right);
            root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
            holder.addView(screen, new FrameLayout.LayoutParams(this.screenBaseW, this.screenBaseH, Gravity.CENTER));
            shell.track(screen, controls);
            installScreen(root);
            finishScreen(root, controls);
            return;
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        // (0.0.393) The top as the other consoles': 저장 · 재시작 · 화면 회전 · 메뉴 in one thin
        // row, the game's name under it. The old row (menu button and name) stays as an
        // invisible spacer, so the screen and the buttons keep their places.
        FrameLayout top = new FrameLayout(this);
        LinearLayout spacer = new LinearLayout(this);
        spacer.setGravity(Gravity.CENTER_VERTICAL);
        spacer.setPadding(dp(12), dp(8), dp(12), dp(4));
        spacer.addView(menu);
        spacer.setVisibility(View.INVISIBLE);
        top.addView(spacer, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pills = new LinearLayout(this);
        pills.setPadding(dp(8), dp(4), dp(8), dp(4));
        String[] names = {"저장", "재시작", "화면 회전", "메뉴"};
        int[] icons = {RoundIconView.SAVE, RoundIconView.RESET, RoundIconView.ROTATE, RoundIconView.MENU};
        int[] colors = {RoundIconView.COLOR_SAVE, RoundIconView.COLOR_RESET, RoundIconView.COLOR_ROTATE, RoundIconView.COLOR_MENU};
        View.OnClickListener[] actions = {v -> onSaveClicked(), v -> onResetClicked(), v -> toggleOrientation(), v -> showPauseMenu()};
        for (int k = 0; k < names.length; k++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            params.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
            LinearLayout pill = pillButton(icons[k], colors[k], names[k], labelColor, actions[k]);
            pills.addView(pill, params);
        }
        bar.addView(pills, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView title = text(getIntent().getStringExtra(EXTRA_TITLE), 11.5f, labelColor, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(title, new LinearLayout.LayoutParams(-1, -2));
        top.addView(bar, new FrameLayout.LayoutParams(-1, -2));
        column.addView(top, new LinearLayout.LayoutParams(-1, -2));
        this.layoutGuards.clear();
        this.layoutGuards.add(top);

        // 16:9 screen, the width of the phone with a small margin (4:3: as wide, taller; before
        // 0.0.416 and for an edited layout: as tall, narrower), kept short enough for the buttons.
        android.util.DisplayMetrics pm = getResources().getDisplayMetrics();
        int width = pm.widthPixels - dp(16);
        int height = width * 9 / 16;
        if (four) {
            if (this.legacyLayout) {
                width = height * 4 / 3;
            } else {
                height = width * 3 / 4;
            }
        }
        if (!this.legacyLayout) {
            this.padScale = Math.min(1f, pm.widthPixels / pm.density / 412f);
            int room = pm.heightPixels - dp(statusBarDp()) - dp(64) - dp(24) - dp(u(PAD_BLOCK_DP)) - dp(8);
            if (height > room && room > dp(120)) {
                height = room;
                width = four ? height * 4 / 3 : height * 16 / 9;
            }
        }
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(width, height);
        screenParams.gravity = Gravity.CENTER_HORIZONTAL;
        screenParams.setMargins(0, dp(10), 0, dp(this.legacyLayout ? 30 : 14));
        column.addView(holder, screenParams);
        this.screenBaseW = width;
        this.screenBaseH = height;
        holder.addView(screen, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));

        FrameLayout pad = new FrameLayout(this);
        pad.setClipChildren(false);
        buildPad(pad);
        column.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        shell.track(screen, pad);
        installScreen(root);
        finishScreen(root, pad);
    }

    // Saved per screen direction (the original Xbox has one layout of its own, like each console).
    private String layoutKey() {
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        return "layout:xbox:" + (wide ? "L" : "P") + ":frame";
    }

    private java.util.Map<String, float[]> loadLayoutEdits() {
        java.util.Map<String, float[]> map = new java.util.HashMap<>();
        for (String entry : MainActivity.sharedSettings(this).getString(layoutKey(), "").split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String[] values = entry.substring(eq + 1).split(",");
            if (values.length != 3 && values.length != 4) continue;
            try {
                float sx = Float.parseFloat(values[2]);
                float sy = values.length == 4 ? Float.parseFloat(values[3]) : sx;
                map.put(entry.substring(0, eq), new float[]{Float.parseFloat(values[0]), Float.parseFloat(values[1]), sx, sy});
            } catch (NumberFormatException ignored) {
                // A damaged entry leaves that control where it was.
            }
        }
        return map;
    }

    private void saveLayoutEdits(float[][] result) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < result.length && k < this.layoutIds.size(); k++) {
            float[] edit = result[k];
            if (Math.abs(edit[0]) < 0.5f && Math.abs(edit[1]) < 0.5f && Math.abs(edit[2] - 1) < 0.01f && Math.abs(edit[3] - 1) < 0.01f) {
                continue;
            }
            sb.append(this.layoutIds.get(k)).append('=').append(Math.round(edit[0])).append(',').append(Math.round(edit[1])).append(',')
                    .append(Math.round(edit[2] * 100) / 100.0f).append(',').append(Math.round(edit[3] * 100) / 100.0f).append(';');
        }
        MainActivity.sharedSettings(this).edit().putString(layoutKey(), sb.toString()).putBoolean(layoutKey() + ":v2", !this.legacyLayout).commit();
    }

    private static final String SCREEN_ID = "screen#0";

    // Names the controls just built (kind, button bit, n-th of the same), applies the saved
    // moves, the photo background's button see-through and the turbo marks.
    private void finishScreen(View root, android.view.ViewGroup controls) {
        this.contentRoot = root;
        touchStick();
        this.layoutControls.clear();
        this.layoutIds.clear();
        this.layoutBase.clear();
        this.layoutControls.add(this.screenFrame);
        this.layoutIds.add(SCREEN_ID);
        this.layoutBase.add(new float[]{0, 0});
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(controls);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof TouchButton || view instanceof DPadView || view instanceof AnalogStickView) {
                String kind = view.getClass().getSimpleName() + (view instanceof TouchButton ? "." + ((TouchButton) view).bit() : "");
                int count = seen.containsKey(kind) ? seen.get(kind) : 0;
                seen.put(kind, count + 1);
                this.layoutControls.add(view);
                this.layoutIds.add(kind + "#" + count);
                this.layoutBase.add(new float[]{view.getTranslationX(), view.getTranslationY()});
            } else if (view instanceof android.view.ViewGroup && view != this.screenHolder) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int k = 0; k < group.getChildCount(); k++) {
                    queue.add(group.getChildAt(k));
                }
            }
        }
        java.util.Map<String, float[]> edits = loadLayoutEdits();
        for (int k = 0; k < this.layoutControls.size(); k++) {
            float[] edit = edits.get(this.layoutIds.get(k));
            if (edit != null) {
                applyLayoutEdit(k, edit[0], edit[1], edit[2], edit[3], true);
            }
        }
        float alpha = shellStyle() == ClearShellView.STYLE_PHOTO ? 1.0f - PhotoBackground.buttonAlpha(this, "xbox") / 100f : 1.0f;
        for (int k = 1; k < this.layoutControls.size(); k++) {
            this.layoutControls.get(k).setAlpha(alpha);
        }
        markTurboButtons();
        refreshPadMode();
    }

    // bake: the screen's size is laid out (the game draws at it); otherwise only scaled, as the
    // editor measures the frame by its unscaled size times the scale.
    private void applyLayoutEdit(int index, float dxDp, float dyDp, float scaleX, float scaleY, boolean bake) {
        View view = this.layoutControls.get(index);
        float[] base = this.layoutBase.get(index);
        float density = getResources().getDisplayMetrics().density;
        view.setTranslationX(base[0] + dxDp * density);
        view.setTranslationY(base[1] + dyDp * density);
        if (view == this.screenFrame) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            int w = bake ? Math.round(this.screenBaseW * scaleX) : this.screenBaseW;
            int h = bake ? Math.round(this.screenBaseH * scaleY) : this.screenBaseH;
            if (params.width != w || params.height != h) {
                params.width = w;
                params.height = h;
                view.setLayoutParams(params);
            }
            view.setScaleX(bake ? 1f : scaleX);
            view.setScaleY(bake ? 1f : scaleY);
            if (this.shellView != null) {
                this.shellView.invalidate();
            }
            return;
        }
        view.setScaleX(scaleX);
        view.setScaleY(scaleY);
    }

    // ---- 화면 터치로 오른쪽 스틱 (0.0.401): a drag on the game screen tilts the right stick. ----
    private AnalogStickView rightStick;
    private TouchStick touchStick;

    private AnalogStickView rightStickView() {
        this.rightStick = new AnalogStickView(this, (x, y) -> onStick(false, x, y));
        return this.rightStick;
    }

    private TouchStick touchStick() {
        if (this.touchStick == null) {
            this.touchStick = new TouchStick(this, (x, y) -> {
                this.rightXf = Math.max(-1f, Math.min(1f, x));
                this.rightYf = Math.max(-1f, Math.min(1f, y));
                if (this.rightStick != null) this.rightStick.showKnob(x, y);
                pushPad();
            });
        }
        this.touchStick.configure(MainActivity.sharedSettings(this), this.gamePath);
        return this.touchStick;
    }

    private void startLayoutEditor() {
        if (this.layoutControls.isEmpty() || this.layoutEditor != null || this.contentRoot == null) {
            return;
        }
        enginePause();
        java.util.Map<String, float[]> saved = loadLayoutEdits();
        float[][] edits = new float[this.layoutControls.size()][];
        for (int k = 0; k < edits.length; k++) {
            float[] edit = saved.get(this.layoutIds.get(k));
            edits[k] = edit != null ? edit.clone() : new float[]{0, 0, 1, 1};
        }
        // The frame back to its unedited size, scaled: what the editor measures.
        applyLayoutEdit(0, edits[0][0], edits[0][1], edits[0][2], edits[0][3], false);
        for (int k = 1; k < this.layoutControls.size(); k++) {
            this.layoutControls.get(k).setAlpha(1f);
        }
        LayoutEditorView editor = new LayoutEditorView(this, new java.util.ArrayList<>(this.layoutControls), edits, 0, new LayoutEditorView.Listener() {
            @Override
            public void apply(int index, float dxDp, float dyDp, float scaleX, float scaleY) {
                applyLayoutEdit(index, dxDp, dyDp, scaleX, scaleY, false);
            }

            @Override
            public void onSave(float[][] result) {
                saveLayoutEdits(result);
                finishLayoutEditor("버튼 · 화면 배치를 저장했어요.");
            }

            @Override
            public void onCancel() {
                finishLayoutEditor(null);
            }
        });
        editor.setGuards(new java.util.ArrayList<>(this.layoutGuards));
        addContentView(editor, new FrameLayout.LayoutParams(-1, -1));
        this.layoutEditor = editor;
    }

    private void finishLayoutEditor(String message) {
        LayoutEditorView editor = this.layoutEditor;
        this.layoutEditor = null;
        if (editor != null && editor.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) editor.getParent()).removeView(editor);
        }
        // Built again: the game's picture takes the frame's new size.
        buildGameScreen();
        if (message != null) {
            toast(message);
        }
        if (!this.leaving) {
            engineResume();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (engineReady && this.gamePath != null && !this.leaving) {
            // A new SurfaceView: the old surface goes away and the engine gets the new one.
            buildGameScreen();
        }
    }

    private void addAt(FrameLayout parent, View view, int sideDp, int edgeDp, int widthDp, int heightDp, int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp), gravity);
        boolean right = (gravity & Gravity.END) == Gravity.END && (gravity & Gravity.START) != Gravity.START;
        boolean bottom = (gravity & Gravity.BOTTOM) == Gravity.BOTTOM;
        params.setMargins(right ? 0 : dp(sideDp), bottom ? 0 : dp(edgeDp), right ? dp(sideDp) : 0, bottom ? dp(edgeDp) : 0);
        parent.addView(view, params);
    }

    @Override
    public void onBackPressed() {
        showExitDialog();
    }

    // (0.0.392) Five tabs, as the other consoles' menus: 기본 · 성능 · 화면 · 조작 · 게임기.
    private static final String[] TABS = {"기본", "성능", "화면", "조작", "게임기"};
    private int menuTab;

    // (0.0.393) The same card as the other consoles' pause menu (GameActivity.showPauseDialog):
    // tabs in a grey strip (down the left side in landscape), a content area of one height
    // for every tab, 계속하기 at the bottom. A row's sheet opens over the menu, which stays.
    private Dialog pauseDialog;
    private volatile boolean stateBusy;
    private Runnable menuRefresh;
    private boolean menuKeepPaused;

    // After a sheet over the menu closes: the menu shows the new values; with no menu, the
    // game goes on.
    private void resumeGame() {
        // The menu is closing to save, load or restart: it stays paused for that.
        if (this.leaving || this.menuKeepPaused || this.stateBusy) return;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            Runnable refresh = this.menuRefresh;
            if (refresh != null) main.post(refresh);
            return;
        }
        if (this.bootStarted && engineReady && this.layoutEditor == null) {
            engineResume();
        }
    }

    private void closeMenu(boolean keepPaused) {
        if (this.pauseDialog != null) {
            this.menuKeepPaused = keepPaused;
            this.pauseDialog.dismiss();
        }
    }

    private void showPauseMenu() {
        if (!engineReady) {
            showExitDialog();
            return;
        }
        if (this.pauseDialog != null) return;
        enginePause();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(16), dp(14), dp(14));
        card.setBackground(roundRect(Color.WHITE, 24));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);
        header.addView(text("일시정지", 17, Color.rgb(31, 41, 51), true), new LinearLayout.LayoutParams(-2, -2));
        TextView game = text(getIntent().getStringExtra(EXTRA_TITLE), 12, Color.rgb(122, 130, 140), false);
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        game.setGravity(Gravity.END);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(0, -2, 1);
        gameParams.setMargins(dp(12), 0, 0, 0);
        header.addView(game, gameParams);
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabBar.setBackground(roundRect(Color.rgb(241, 243, 245), 14));
        final TextView[] tabViews = new TextView[TABS.length];
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        final Choice close = choice -> closeMenu(choice == 1);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            for (int k = 0; k < tabViews.length; k++) {
                boolean on = k == this.menuTab;
                tabViews[k].setTextColor(on ? Color.rgb(15, 118, 110) : Color.rgb(107, 114, 128));
                tabViews[k].setBackground(on ? roundRect(Color.WHITE, 10) : null);
                tabViews[k].setElevation(on ? dp(1) : 0);
            }
            body.removeAllViews();
            switch (this.menuTab) {
                case 1: fillPerformanceTab(body, close); break;
                case 2: fillScreenTab(body, close); break;
                case 3: fillControlsTab(body, close); break;
                case 4: fillSystemTab(body, close); break;
                default: fillBasicTab(body, close, () -> {
                    this.menuTab = 1;
                    fill[0].run();
                    scroll.scrollTo(0, 0);
                }); break;
            }
        };
        for (int k = 0; k < TABS.length; k++) {
            final int index = k;
            TextView tab = text(TABS[k], 12.5f, Color.rgb(107, 114, 128), true);
            tab.setGravity(Gravity.CENTER);
            tab.setOnClickListener(v -> {
                this.menuTab = index;
                fill[0].run();
                scroll.scrollTo(0, 0);
            });
            tabViews[k] = tab;
            LinearLayout.LayoutParams tabParams = wide ? new LinearLayout.LayoutParams(-1, dp(42)) : new LinearLayout.LayoutParams(0, dp(34), 1);
            if (wide && k > 0) tabParams.setMargins(0, dp(4), 0, 0);
            tabBar.addView(tab, tabParams);
        }
        this.menuRefresh = () -> {
            int y = scroll.getScrollY();
            fill[0].run();
            scroll.post(() -> scroll.scrollTo(0, y));
        };
        TextView resume = text("▶  계속하기", 16, Color.WHITE, true);
        resume.setGravity(Gravity.CENTER);
        resume.setBackground(roundRect(Color.rgb(22, 185, 207), 14));
        resume.setOnClickListener(v -> dialog.dismiss());
        int screenH = Math.round(metrics.heightPixels / metrics.density);
        int contentDp = wide ? Math.max(160, screenH - 150) : Math.max(220, Math.min(380, screenH - 260));
        int width = wide ? Math.min(metrics.widthPixels - dp(40), dp(600)) : Math.min(metrics.widthPixels - dp(40), dp(380));
        if (wide) {
            card.addView(tabBar, new LinearLayout.LayoutParams(dp(84), -2));
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(contentDp));
            scrollParams.setMargins(0, dp(10), 0, 0);
            right.addView(scroll, scrollParams);
            LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, dp(46));
            resumeParams.setMargins(0, dp(10), 0, 0);
            right.addView(resume, resumeParams);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1);
            rightParams.setMargins(dp(12), 0, 0, 0);
            card.addView(right, rightParams);
        } else {
            card.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
            barParams.setMargins(0, dp(10), 0, 0);
            card.addView(tabBar, barParams);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(contentDp));
            scrollParams.setMargins(0, dp(10), 0, 0);
            card.addView(scroll, scrollParams);
            LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, dp(50));
            resumeParams.setMargins(0, dp(12), 0, 0);
            card.addView(resume, resumeParams);
        }
        fill[0].run();
        dialog.setContentView(card);
        this.pauseDialog = dialog;
        this.menuKeepPaused = false;
        dialog.setOnDismissListener(d -> {
            this.pauseDialog = null;
            this.menuRefresh = null;
            boolean keep = this.menuKeepPaused;
            this.menuKeepPaused = false;
            if (!keep) resumeGame();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    private static String onOff(boolean on) {
        return on ? "켜짐" : "꺼짐";
    }

    private LinearLayout.LayoutParams tile4Params(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(column == 0 ? 0 : dp(3), 0, column == 3 ? 0 : dp(3), 0);
        return params;
    }

    // A pill on the case (GameActivity.createPillButton): a small round icon and its name.
    private LinearLayout pillButton(int icon, int color, String label, int labelColor, View.OnClickListener onClick) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER);
        boolean lightLabel = Color.red(labelColor) + Color.green(labelColor) + Color.blue(labelColor) > 384;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(lightLabel ? Color.argb(46, 0, 0, 0) : Color.argb(110, 255, 255, 255));
        bg.setStroke(Math.max(1, dp(1)), lightLabel ? Color.argb(40, 255, 255, 255) : Color.argb(40, 0, 0, 0));
        pill.setBackground(bg);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        pill.addView(iconView, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = text(label, 11.5f, labelColor, true);
        text.setShadowLayer(dp(2), 0, dp(1), lightLabel ? Color.argb(150, 0, 0, 0) : Color.argb(160, 255, 255, 255));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(dp(5), 0, 0, 0);
        pill.addView(text, textParams);
        pill.setClickable(true);
        pill.setContentDescription(label);
        pill.setOnClickListener(onClick);
        pill.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                iconView.setPressedLook(true);
                view.setAlpha(0.75f);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                iconView.setPressedLook(false);
                view.setAlpha(1f);
            }
            return false;
        });
        return pill;
    }

    // Landscape corner button (GameActivity.createIconButton 34 / 10).
    private LinearLayout cornerButton(int icon, int color, String label, int labelColor, View.OnClickListener onClick, View.OnLongClickListener onLong) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView text = text(label, 10, labelColor, true);
        text.setSingleLine(true);
        box.addView(text);
        box.setClickable(true);
        box.setContentDescription(label);
        box.setOnClickListener(onClick);
        if (onLong != null) box.setOnLongClickListener(onLong);
        return box;
    }

    private void toggleOrientation() {
        MainActivity.sharedSettings(this).edit().putBoolean("xbox_landscape", !landscape()).commit();
        buildGameScreen();
    }

    private void fillScreenTab(LinearLayout body, Choice close) {
        body.addView(menuRow(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "화면 방향", landscape() ? "가로" : "세로", v -> {
            closeMenu(false);
            toggleOrientation();
        }), rowParams(0));
        boolean four = aspect43(this);
        body.addView(menuRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화면 비율", four ? "4:3" : "16:9", v -> {
            showChoices(RoundIconView.HD, RoundIconView.COLOR_HD, "화면 비율", new String[]{"16:9 (와이드)", "4:3"},
                    new String[]{"초대 Xbox를 와이드 TV에 연결한 것처럼 (기본)", "초대 Xbox를 4:3 TV에 연결한 것처럼 · 게임이 4:3 화면을 그려요"}, four ? 1 : 0,
                    "모든 초대 Xbox 게임에 같이 적용돼요. 게임 화면 틀도 그 비율로 바뀌어요. 게임에 따라 다시 켜야 바뀌어요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("xbox_aspect43", choice == 1).commit();
                toast("재시작하면 게임에도 적용돼요");
                buildGameScreen();
            });
        }), rowParams(8));
        int style = shellStyle();
        body.addView(menuRow(RoundIconView.PALETTE, RoundIconView.COLOR_PALETTE, "배경", ClearShellView.STYLE_NAMES[Math.max(0, Math.min(ClearShellView.STYLE_NAMES.length - 1, style))], v -> {
            showChoices(RoundIconView.PALETTE, RoundIconView.COLOR_PALETTE, "배경", ClearShellView.STYLE_NAMES, ClearShellView.STYLE_NOTES, style,
                    "모든 게임에 같이 적용돼요. \"내 사진\"은 아래 \"사진 배경 만들기\"에서 사진을 고르면 돼요.", choice -> {
                MainActivity.sharedSettings(this).edit().putInt("shell_style", choice).commit();
                buildGameScreen();
            });
        }), rowParams(8));
        boolean photo = PhotoBackground.rendered(this, "xbox", landscape()) != null;
        body.addView(menuRow(RoundIconView.PALETTE, Color.rgb(219, 39, 119), "사진 배경 만들기", photo ? "있음" : "없음", v -> {
            closeMenu(true);
            Intent intent = new Intent(this, BgEditorActivity.class);
            intent.putExtra(BgEditorActivity.EXTRA_MODE, "xbox");
            intent.putExtra(BgEditorActivity.EXTRA_MODE_NAME, "Xbox (초대)");
            intent.putExtra(BgEditorActivity.EXTRA_LANDSCAPE, landscape());
            intent.putExtra(BgEditorActivity.EXTRA_STYLE_KEY, "shell_style");
            startActivityForResult(intent, REQUEST_BG_EDITOR);
        }), rowParams(8));
    }

    private void fillControlsTab(LinearLayout body, Choice close) {
        body.addView(menuRow(RoundIconView.MOVE, RoundIconView.COLOR_MOVE, "버튼 · 화면 배치 편집", loadLayoutEdits().isEmpty() ? "기본 배치" : "바꾼 배치", v -> {
            closeMenu(true);
            startLayoutEditor();
        }), rowParams(0));
        int touchSens = stickSensIndex(false);
        int padSens = stickSensIndex(true);
        body.addView(menuRow(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도", "화면 " + Math.round(SENS_VALUES[touchSens] * 100) + "% · 패드 " + Math.round(SENS_VALUES[padSens] * 100) + "%", v -> {
            showChoices(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도 · 적용할 스틱", new String[]{"화면 스틱", "패드 스틱", "둘 다"},
                    new String[]{"지금 " + SENS_NAMES[touchSens], "지금 " + SENS_NAMES[padSens], "화면 · 패드 같이"}, -1,
                    "다음 화면에서 감도를 골라요. 모든 초대 Xbox 게임에 같이 적용돼요.", which -> {
                int current = which == 1 ? padSens : touchSens;
                showChoices(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도", SENS_NAMES,
                        new String[]{"조금만 움직여요", "덜 움직여요", "그대로 (기본)", "더 움직여요", "조금만 기울여도 끝까지"}, current,
                        "카메라가 너무 빠르거나 느릴 때 바꿔 보세요.", choice -> {
                    android.content.SharedPreferences.Editor editor = MainActivity.sharedSettings(this).edit();
                    if (which != 1) editor.putInt("xbox_sens_touch", choice);
                    if (which != 0) editor.putInt("xbox_sens_pad", choice);
                    editor.commit();
                });
            });
        }), rowParams(8));
        body.addView(menuRow(RoundIconView.PAD, Color.rgb(13, 148, 136), "화면 터치로 오른쪽 스틱", TouchStick.summary(MainActivity.sharedSettings(this), this.gamePath), v ->
                TouchStick.showSheet(this, MainActivity.sharedSettings(this), this.gamePath, getIntent().getStringExtra(EXTRA_TITLE), null, () -> {
                    touchStick();
                    if (this.menuRefresh != null) main.post(this.menuRefresh);
                })), rowParams(8));
        int turbo = Integer.bitCount(turboMask());
        body.addView(menuRow(RoundIconView.BOLT, Color.rgb(234, 88, 12), "연사", turbo == 0 ? "없음" : turbo + "개 버튼", v -> {
            showTurboSheet();
        }), rowParams(8));
        boolean hide = hidePadWithController();
        body.addView(menuRow(RoundIconView.PAD, RoundIconView.COLOR_MOVE, "패드를 쓰면 화면 버튼 감추기", onOff(hide), v -> {
            showChoices(RoundIconView.PAD, RoundIconView.COLOR_MOVE, "패드를 쓰면 화면 버튼 감추기", new String[]{"꺼짐", "켜짐"},
                    new String[]{"화면 버튼을 늘 보여요", "게임패드 버튼을 누르면 화면 버튼이 사라지고, 화면을 만지면 돌아와요 (기본)"}, hide ? 1 : 0,
                    "게임 화면의 크기 · 자리는 그대로예요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("xbox_pad_hide", choice == 1).commit();
                if (choice == 0 && this.padMode) {
                    this.padMode = false;
                    refreshPadMode();
                }
            });
        }), rowParams(8));
        String[] hapticNames = {"꺼짐", "약하게", "보통", "강하게"};
        int haptic = Math.max(0, Math.min(3, MainActivity.sharedSettings(this).getInt("haptic_level", 0)));
        body.addView(menuRow(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", hapticNames[haptic], v -> {
            showChoices(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", hapticNames, new String[]{"진동 없음 (기본)", "살짝 톡", "또렷하게 톡", "확실하게 툭"}, haptic,
                    "화면 버튼을 누를 때 짧게 진동해요.", choice -> {
                MainActivity.sharedSettings(this).edit().putInt("haptic_level", choice).commit();
                Haptics.setLevel(this, choice);
                Haptics.tap();
            });
        }), rowParams(8));
    }

    // ---- 프레임 생성 ----
    private static final int REQUEST_BG_EDITOR = 32;

    private static boolean fromController(android.view.InputEvent event) {
        int source = event.getSource();
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    // ---- 패드를 쓰면 화면 버튼 감추기 (0.0.392): with a controller in use the touch controls
    // fade out (the screen stays where it is); a touch on the screen brings them back. ----
    private boolean padMode;

    private boolean hidePadWithController() {
        return MainActivity.sharedSettings(this).getBoolean("xbox_pad_hide", true);
    }

    private void onPadUsed() {
        if (!this.padMode && hidePadWithController()) {
            this.padMode = true;
            refreshPadMode();
        }
    }

    private void refreshPadMode() {
        for (int k = 1; k < this.layoutControls.size(); k++) {
            this.layoutControls.get(k).setVisibility(this.padMode ? View.INVISIBLE : View.VISIBLE);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (this.padMode && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            this.padMode = false;
            refreshPadMode();
        }
        return super.dispatchTouchEvent(event);
    }

    // ---- 스틱 감도 (0.0.392): how far a stick's tilt reaches, touch and controller apart. ----
    private static final float[] SENS_VALUES = {0.6f, 0.8f, 1.0f, 1.25f, 1.5f};
    private static final String[] SENS_NAMES = {"느리게 (60%)", "조금 느리게 (80%)", "보통 (100%)", "조금 빠르게 (125%)", "빠르게 (150%)"};

    private int stickSensIndex(boolean pad) {
        int index = MainActivity.sharedSettings(this).getInt(pad ? "xbox_sens_pad" : "xbox_sens_touch", 2);
        return Math.max(0, Math.min(SENS_VALUES.length - 1, index));
    }

    private float stickSensitivity(boolean pad) {
        return SENS_VALUES[stickSensIndex(pad)];
    }

    // ---- 연사 (0.0.392): the buttons marked repeat while held, at the chosen speed. Ids as
    // the touch buttons' (digital1 << 16 | digital2). ----
    private static final int[] TURBO_RATES = {8, 12, 15, 20};
    private static final String[] TURBO_NAMES = {"초당 8번", "초당 12번", "초당 15번", "초당 20번"};
    private boolean turboPhase;
    private boolean turboRunning;

    private String turboKey() {
        return "xbox_turbo:" + this.gamePath;
    }

    private int turboMask() {
        return MainActivity.sharedSettings(this).getInt(turboKey(), 0);
    }

    private int turboRateIndex() {
        return Math.max(0, Math.min(TURBO_RATES.length - 1, MainActivity.sharedSettings(this).getInt("xbox_turbo_rate", 1)));
    }

    private final Runnable turboStep = new Runnable() {
        @Override
        public void run() {
            int mask = turboMask();
            int held = heldBits();
            if (mask == 0 || (held & mask) == 0 || XboxActivity.this.leaving) {
                turboRunning = false;
                turboPhase = false;
                pushPad();
                return;
            }
            turboPhase = !turboPhase;
            pushPad();
            main.postDelayed(this, 500 / TURBO_RATES[turboRateIndex()]);
        }
    };

    private void turboTick(boolean pressed) {
        if (pressed && !this.turboRunning && (heldBits() & turboMask()) != 0) {
            this.turboRunning = true;
            this.turboPhase = false;
            main.postDelayed(this.turboStep, 500 / TURBO_RATES[turboRateIndex()]);
        }
    }

    // A small "연사" mark on the touch buttons that repeat.
    private void markTurboButtons() {
        int mask = turboMask();
        for (View view : this.layoutControls) {
            if (view instanceof TouchButton) {
                TouchButton button = (TouchButton) view;
                button.setGunTag((mask & button.bit()) != 0 && button.bit() != 0 ? "연사" : null, false);
            }
        }
    }

    // {id, name} of the buttons 연사 can take.
    private static final int[] TURBO_IDS = {XboxActivity.B_A, XboxActivity.B_B, XboxActivity.B_X, XboxActivity.B_Y, XboxActivity.B_WHITE, XboxActivity.B_BLACK, XboxActivity.B_LT, XboxActivity.B_RT, XboxActivity.B_L3, XboxActivity.B_R3};
    private static final String[] TURBO_BUTTON_NAMES = {"A", "B", "X", "Y", "WHITE", "BLACK", "LT", "RT", "L3", "R3"};

    private void showTurboSheet() {
        enginePause();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        panel.addView(text("연사", 17, Color.rgb(31, 41, 51), true));
        panel.addView(text(getIntent().getStringExtra(EXTRA_TITLE) + " · 이 게임에만", 12, Color.rgb(122, 130, 140), false));
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            grid.removeAllViews();
            int mask = turboMask();
            LinearLayout line = null;
            for (int k = 0; k < TURBO_IDS.length; k++) {
                if (k % 5 == 0) {
                    line = new LinearLayout(this);
                    LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(46));
                    lineParams.setMargins(0, dp(8), 0, 0);
                    grid.addView(line, lineParams);
                }
                final int id = TURBO_IDS[k];
                boolean on = (mask & id) != 0;
                TextView chip = pill(TURBO_BUTTON_NAMES[k], on ? Color.rgb(234, 88, 12) : Color.rgb(241, 242, 244), on ? Color.WHITE : Color.rgb(75, 85, 99));
                chip.setOnClickListener(v -> {
                    MainActivity.sharedSettings(this).edit().putInt(turboKey(), turboMask() ^ id).commit();
                    fill[0].run();
                    markTurboButtons();
                });
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, -1, 1);
                chipParams.setMargins(k % 5 == 0 ? 0 : dp(6), 0, 0, 0);
                line.addView(chip, chipParams);
            }
        };
        fill[0].run();
        panel.addView(grid);
        TextView rate = pill("속도: " + TURBO_NAMES[turboRateIndex()] + "  ›", Color.rgb(238, 242, 255), Color.rgb(29, 78, 216));
        rate.setOnClickListener(v -> {
            int next = (turboRateIndex() + 1) % TURBO_RATES.length;
            MainActivity.sharedSettings(this).edit().putInt("xbox_turbo_rate", next).commit();
            rate.setText("속도: " + TURBO_NAMES[next] + "  ›");
        });
        LinearLayout.LayoutParams rateParams = new LinearLayout.LayoutParams(-1, dp(46));
        rateParams.setMargins(0, dp(12), 0, 0);
        panel.addView(rate, rateParams);
        TextView note = text("주황색 버튼은 누르고 있는 동안 저절로 연달아 눌려요. 화면 버튼과 게임패드 모두 같아요.", 12, Color.rgb(75, 85, 99), false);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        panel.addView(note);
        TextView close = pill("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(12), 0, 0);
        panel.addView(close, closeParams);
        dialog.setContentView(scrollable(panel));
        dialog.setOnDismissListener(d -> {
            resumeGame();
        });
        sizeDialog(dialog);
        dialog.show();
    }

    private interface Choice {
        void picked(int index);
    }

    private void showChoices(int icon, int color, String title, String[] names, String[] notes, int current, String note, Choice onPick) {
        enginePause();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView titleView = text(title, 17, Color.rgb(31, 41, 51), true);
        titleView.setPadding(dp(10), 0, 0, 0);
        header.addView(titleView);
        panel.addView(header);
        for (int k = 0; k < names.length; k++) {
            final int index = k;
            LinearLayout option = new LinearLayout(this);
            option.setOrientation(LinearLayout.VERTICAL);
            option.setPadding(dp(14), dp(10), dp(14), dp(10));
            option.setBackground(roundRect(k == current ? Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)) : Color.rgb(246, 247, 249), 14));
            option.addView(text((k == current ? "● " : "○ ") + names[k], 14.5f, Color.rgb(31, 41, 51), true));
            if (notes != null) {
                option.addView(text(notes[k], 11.5f, Color.rgb(122, 130, 140), false));
            }
            option.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.picked(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(8), 0, 0);
            panel.addView(option, params);
        }
        TextView noteView = text(note, 12, Color.rgb(75, 85, 99), false);
        noteView.setPadding(dp(4), dp(10), dp(4), 0);
        panel.addView(noteView);
        TextView close = pill("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(12), 0, 0);
        panel.addView(close, closeParams);
        dialog.setOnDismissListener(d -> resumeGame());
        if (ShortScreen.twoColumns(this) && names.length > 0) {
            // (0.0.410) Landscape: the title, note and close left, the choices right.
            View firstOption = panel.getChildAt(panel.indexOfChild(header) + 1);
            LinearLayout right = ShortScreen.takeChildren(this, panel, firstOption, noteView);
            View top = right.getChildAt(0);
            if (top != null && top.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) top.getLayoutParams()).topMargin = 0;
            }
            android.graphics.drawable.Drawable card = panel.getBackground();
            panel.setBackground(null);
            panel.setPadding(0, 0, 0, 0);
            ShortScreen.showColumns(dialog, ShortScreen.leftWithBottom(this, panel, close), right, card, 280);
            dialog.show();
            return;
        }
        dialog.setContentView(scrollable(panel));
        sizeDialog(dialog);
        dialog.show();
    }

    private LinearLayout menuTile(int icon, int color, String label, String detail, View.OnClickListener onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(14), dp(6), dp(12));
        tile.setBackground(roundRect(Color.rgb(246, 247, 249), 16));
        tile.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = text(label, 13, Color.rgb(31, 41, 51), true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(8), 0, 0);
        tile.addView(name);
        if (detail != null) {
            TextView sub = text(detail, 10.5f, Color.rgb(122, 130, 140), false);
            sub.setGravity(Gravity.CENTER);
            tile.addView(sub);
        }
        tile.setClickable(true);
        tile.setOnClickListener(onClick);
        return tile;
    }

    private LinearLayout.LayoutParams tileParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(column == 0 ? 0 : dp(5), 0, column == 2 ? 0 : dp(5), 0);
        return params;
    }

    private LinearLayout menuRow(int icon, int color, String label, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackground(roundRect(Color.rgb(246, 247, 249), 14));
        row.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView name = text(label, 14, Color.rgb(31, 41, 51), true);
        name.setPadding(dp(12), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(value + "  ›", 13.5f, color, true));
        row.setClickable(true);
        row.setOnClickListener(onClick);
        return row;
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private View scrollable(View panel) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        return scroll;
    }

    private void sizeDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380)), -2);
        }
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showExitDialog() {
        if (engineReady) {
            enginePause();
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        TextView title = text("게임을 나갈까요?", 17, Color.rgb(31, 41, 51), true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = text("게임 안에서 저장한 곳이나 빠른 저장에서 이어져요.", 12, Color.rgb(122, 130, 140), false);
        note.setGravity(Gravity.CENTER);
        panel.addView(note, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        TextView stay = pill("계속하기", Color.rgb(241, 251, 252), Color.rgb(14, 95, 107));
        stay.setOnClickListener(v -> dialog.cancel());
        TextView leave = pill("나가기", Color.rgb(229, 72, 77), Color.WHITE);
        leave.setOnClickListener(v -> {
            this.leaving = true;
            dialog.dismiss();
            leave();
        });
        row.addView(stay, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams leaveParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        leaveParams.setMargins(dp(10), 0, 0, 0);
        row.addView(leave, leaveParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, dp(14), 0, 0);
        panel.addView(row, rowParams);
        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> {
            if (engineReady) resumeGame();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360)), -2);
        }
        dialog.show();
    }

    private void showMessage(String title, String message, boolean closeOnly) {
        LinearLayout card = card();
        card.addView(text(title, 18, Color.rgb(31, 41, 51), true));
        TextView body = text(message, 13, Color.rgb(75, 85, 99), false);
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);
        TextView close = pill("닫기", INDIGO, Color.WHITE);
        close.setOnClickListener(v -> leave());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(16), 0, 0);
        card.addView(close, closeParams);
        setContentView(centered(card));
    }

    private void finishWithMessage(String title, String message) {
        showMessage(title, message, true);
    }

    private FrameLayout centered(View card) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(236, 242, 247));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380)), -2, Gravity.CENTER);
        frame.addView(card, params);
        return frame;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        card.setBackground(background);
        return card;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    private TextView pill(String label, int fill, int color) {
        TextView view = text(label, 15, color, true);
        view.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(14));
        view.setBackground(background);
        return view;
    }

    private LinearLayout iconButton(int icon, int color, String label, int labelColor, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView text = text(label, 11, labelColor, true);
        box.addView(text);
        box.setClickable(true);
        box.setOnClickListener(onClick);
        return box;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ==== 초대 Xbox (0.0.415): HakuX's core (libhakuxcore.so on SDL3), through NativeCoreActivity ====

    // Touch / controller button ids (bits): the core's NC_PAD_* digital bits as they are
    // (up, down, left, right, Start, Back, L3, R3), then A B X Y Black White as the core's
    // analog buttons 0-5 (bits 8-13), then LT · RT.
    private static final int B_UP = 0x01, B_DOWN = 0x02, B_LEFT = 0x04, B_RIGHT = 0x08, B_START = 0x10, B_BACK = 0x20, B_L3 = 0x40, B_R3 = 0x80,
            B_A = 1 << 8, B_B = 1 << 9, B_X = 1 << 10, B_Y = 1 << 11, B_BLACK = 1 << 12, B_WHITE = 1 << 13, B_LT = 1 << 14, B_RT = 1 << 15;
    private static final int GREEN = Color.rgb(16, 124, 16);

    private static boolean engineReady;
    private static String engineError;

    private int touchButtons;
    private int padButtons;
    private int padHat;
    private float padLT;
    private float padRT;
    private float leftXf;
    private float leftYf;
    private float rightXf;
    private float rightYf;
    private float padLX;
    private float padLY;
    private float padRX;
    private float padRY;
    private boolean padSticks;
    private volatile boolean booted;
    private String discArg = "";
    private boolean frameShown;
    private TextView fpsView;

    static File rootDir(Context context) {
        File dir = context.getExternalFilesDir("xbox");
        return dir != null ? dir : new File(context.getFilesDir(), "xbox");
    }

    // The core's hard disk (a folder it makes and keeps itself: no image needed).
    static File hddDir(Context context) {
        return new File(context.getFilesDir(), "xbox/hdd");
    }

    static File statesDir(Context context, String gamePath) {
        String name = new File(gamePath).getName().replaceAll("[^A-Za-z0-9가-힣._ -]", "_");
        return new File(rootDir(context), "states/" + name);
    }

    private static volatile String unsupportedChecked;
    private static volatile boolean unsupportedKnown;

    static String unsupportedReason(Context context) {
        if (!unsupportedKnown) {
            unsupportedChecked = unsupportedReasonUncached(context);
            unsupportedKnown = true;
        }
        return unsupportedChecked;
    }

    private static String unsupportedReasonUncached(Context context) {
        if (Build.VERSION.SDK_INT < 28) {
            return "초대 Xbox 엔진(HakuX)은 안드로이드 9 이상에서만 돌아가요.";
        }
        String[] abis = Build.SUPPORTED_64_BIT_ABIS;
        if (abis == null || abis.length == 0 || !java.util.Arrays.asList(abis).contains("arm64-v8a")) {
            return "초대 Xbox 엔진은 64비트 ARM 휴대폰에서만 돌아가요.";
        }
        String libs = context.getApplicationInfo().nativeLibraryDir;
        if (libs == null || !new File(libs, "libhakuxcore.so").exists() || !new File(libs, "libSDL3.so").exists()) {
            return "이 앱 빌드에 초대 Xbox 엔진이 들어 있지 않아요.";
        }
        if (!context.getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000)) {
            return "이 폰은 Vulkan 1.1을 지원하지 않아서 초대 Xbox 엔진이 돌아가지 않아요.";
        }
        return null;
    }

    // An original Xbox disc: default.xbe at the root of its XDVDFS volume.
    static boolean isXboxDisc(File file) {
        return X360Activity.isOriginalXboxDisc(file);
    }

    // ---- 설정 (게임기 탭): the core's start arguments ----
    static final String[] SCALE_NAMES = {"1x (480p)", "2x", "3x"};
    static final String[] LANG_NAMES = {"한국어", "English", "日本語", "Deutsch", "Français", "Español", "Italiano", "中文", "Português"};
    private static final int[] LANG_CODES = {7, 1, 2, 3, 4, 5, 6, 8, 9};

    private static android.content.SharedPreferences prefs(Context context) {
        return MainActivity.sharedSettings(context);
    }

    static int scaleIndex(Context context) {
        return Math.max(0, Math.min(SCALE_NAMES.length - 1, prefs(context).getInt("xbox_scale", 0)));
    }

    static int langIndex(Context context) {
        return Math.max(0, Math.min(LANG_NAMES.length - 1, prefs(context).getInt("xbox_lang", 0)));
    }

    @Override
    protected String[] getArguments() {
        Context c = getApplicationContext();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(this.discArg);
        File hdd = hddDir(c);
        //noinspection ResultOfMethodCallIgnored
        hdd.mkdirs();
        args.add(hdd.getAbsolutePath());
        String state = getIntent().getStringExtra(EXTRA_LOADSTATE);
        if (state != null) {
            args.add("loadstate=" + state);
        } else if (getIntent().getBooleanExtra(EXTRA_RELAUNCH, false)) {
            args.add("relaunch");
        }
        args.add("version=1.3.0");
        if (prefs(c).getBoolean("xbox_uncap", false)) args.add("uncap");
        args.add("scale=" + (scaleIndex(c) + 1));
        args.add("aspect=" + (aspect43(c) ? "4:3" : "auto"));
        args.add("fxaa=" + (prefs(c).getBoolean("xbox_fxaa", false) ? 1 : 0));
        args.add("upscale=bilinear");
        int video = 0x00080000;  // 480p
        if (prefs(c).getBoolean("xbox_wide", false)) video |= 0x00010000;
        if (prefs(c).getBoolean("xbox_pal60", false)) video |= 0x00400000;
        args.add("video=" + Integer.toHexString(video));
        args.add("avpack=hdtv");
        args.add("lang=" + LANG_CODES[langIndex(c)]);
        args.add("tz=" + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000);
        args.add("asyncpipe=1");
        args.add("prebuild=1");
        args.add("devtime=1");
        args.add("pads=1");
        return args.toArray(new String[0]);
    }

    // The app chooses the screen direction (portrait or landscape), not SDL.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
    }

    // (0.0.416) The status bar stays, as on the other consoles' screens (the top bar under it).
    @Override
    protected boolean keepFullscreen() {
        return false;
    }

    @Override
    protected boolean allowFullscreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle bundle) {
        this.gamePath = getIntent().getStringExtra(EXTRA_PATH);
        String why = this.gamePath == null ? "실행할 게임이 없어요." : unsupportedReason(this);
        if (why == null) {
            try {
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(this.gamePath), ParcelFileDescriptor.MODE_READ_ONLY);
                this.discArg = "fd:" + pfd.detachFd();
            } catch (Exception e) {
                why = "게임 파일을 열 수 없어요: " + e.getMessage();
            }
        }
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Haptics.setLevel(this, MainActivity.sharedSettings(this).getInt("haptic_level", 0));
        if (why == null && !engineLoaded()) {
            why = "초대 Xbox 엔진을 불러오지 못했어요.";
        }
        if (why != null) {
            engineError = why;
            showMessage("초대 Xbox 게임을 시작할 수 없어요", why, true);
            return;
        }
        engineReady = true;
        this.booted = true;
        buildGameScreen();
        main.postDelayed(this.poll, 500);
    }

    // ---- The game's picture: SDL's surface stays in SDL's layout for the whole run (moving it
    // to another parent would end the core's Vulkan surface); the app's screen goes around it:
    // the background under it, the buttons over it, and the surface follows the screen frame. ----
    private View screenBack;
    private View screenTop;
    private boolean surfaceTracking;
    private final android.graphics.Rect frameRect = new android.graphics.Rect();
    private final android.graphics.Rect layoutRect = new android.graphics.Rect();

    private void installScreen(FrameLayout root) {
        android.view.ViewGroup layout = mLayout;
        if (layout == null || mSurface == null) {
            setContentView(root);
            return;
        }
        if (this.screenBack != null) layout.removeView(this.screenBack);
        if (this.screenTop != null) layout.removeView(this.screenTop);
        View shell = this.shellView;
        if (shell != null && shell.getParent() == root) {
            root.removeView(shell);
            layout.addView(shell, 0, new android.widget.RelativeLayout.LayoutParams(-1, -1));
            this.screenBack = shell;
        } else {
            this.screenBack = null;
        }
        layout.addView(root, new android.widget.RelativeLayout.LayoutParams(-1, -1));
        this.screenTop = root;
        if (layout.getParent() == null) {
            setContentView(layout);
        }
        this.surfaceView = mSurface;
        if (!this.surfaceTracking) {
            this.surfaceTracking = true;
            layout.getViewTreeObserver().addOnPreDrawListener(() -> {
                followFrame();
                return true;
            });
        }
        layout.post(this::followFrame);
    }

    private void followFrame() {
        View frame = this.screenFrame;
        View surface = mSurface;
        android.view.ViewGroup layout = mLayout;
        if (frame == null || surface == null || layout == null || !frame.isAttachedToWindow()) return;
        if (!frame.getGlobalVisibleRect(this.frameRect) || !layout.getGlobalVisibleRect(this.layoutRect)) return;
        int left = this.frameRect.left - this.layoutRect.left;
        int top = this.frameRect.top - this.layoutRect.top;
        int width = Math.max(1, this.frameRect.width());
        int height = Math.max(1, this.frameRect.height());
        android.view.ViewGroup.LayoutParams old = surface.getLayoutParams();
        if (old instanceof android.widget.RelativeLayout.LayoutParams) {
            android.widget.RelativeLayout.LayoutParams p = (android.widget.RelativeLayout.LayoutParams) old;
            if (p.width == width && p.height == height && p.leftMargin == left && p.topMargin == top) return;
        }
        android.widget.RelativeLayout.LayoutParams params = new android.widget.RelativeLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        surface.setLayoutParams(params);
    }

    // ---- The core's state, polled: why it can't run, its relaunch into another XBE, the
    // title leaving, the first frame, the FPS line. ----
    private boolean fatalShown;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (XboxActivity.this.leaving || !XboxActivity.this.booted) return;
            try {
                String fatal = nativeGetFatal();
                if (fatal != null && !XboxActivity.this.fatalShown) {
                    XboxActivity.this.fatalShown = true;
                    showFatal(nativeGetFatalKind(), fatal);
                    return;
                }
                if (nativeGetRelaunch()) {
                    restartWith(null, true);
                    return;
                }
                if (nativeGetQuit()) {
                    leave();
                    return;
                }
                if (!XboxActivity.this.frameShown && nativeGetFrameShown()) {
                    XboxActivity.this.frameShown = true;
                    XboxActivity.this.statusHidden = true;
                    if (XboxActivity.this.statusView != null) XboxActivity.this.statusView.setVisibility(View.GONE);
                }
                if (!XboxActivity.this.frameShown && XboxActivity.this.statusView != null) {
                    int[] build = nativeGetShaderBuild();
                    if (build != null && build.length >= 2 && build[1] > 0) {
                        XboxActivity.this.statusView.setText("셰이더 준비 중 " + build[0] + " / " + build[1]);
                    }
                }
                updateFps();
            } catch (Throwable ignored) {
                // The core not up yet.
            }
            main.postDelayed(this, 500);
        }
    };

    private void updateFps() {
        boolean on = prefs(this).getBoolean("xbox_perf", false);
        if (!on) {
            if (this.fpsView != null) this.fpsView.setVisibility(View.GONE);
            return;
        }
        if (this.fpsView == null || this.fpsView.getParent() != this.screenFrame) {
            this.fpsView = text("FPS --", 11, Color.WHITE, true);
            this.fpsView.setBackgroundColor(Color.argb(110, 0, 0, 0));
            this.fpsView.setPadding(dp(6), dp(2), dp(6), dp(2));
            if (this.screenFrame != null) this.screenFrame.addView(this.fpsView, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        }
        float[] s = nativeGetFrameStats();
        if (s != null && s.length >= 2) {
            this.fpsView.setText(String.format(java.util.Locale.ROOT, "FPS %.0f · %.1fms", s[0], s[1]));
        }
        this.fpsView.setVisibility(View.VISIBLE);
    }

    private void showFatal(int kind, String why) {
        String title = kind == 1 ? "그래픽 드라이버가 이 엔진을 못 돌려요" : kind == 3 ? "빠른 저장을 불러오지 못했어요"
                : kind == 4 ? "게임 중 그래픽이 멈췄어요" : kind == 5 ? "게임을 켜지 못했어요" : "이 게임은 아직 안 돌아가요";
        String body = kind == 2 || kind == 0 ? "엔진(HakuX)이 아직 이 게임을 지원하지 않아요.\n\n" + why : why;
        enginePause();
        new AppDialog.Builder(this).setTitle(title).setMessage(body)
                .setPositiveButton("확인", (d, w) -> leave()).show();
    }

    // ---- 일시정지: the core stops while the app's menu or a sheet is open ----
    private boolean corePaused;

    private void enginePause() {
        if (this.booted && !this.corePaused) {
            try {
                nativeSetPaused(true);
                this.corePaused = true;
            } catch (Throwable ignored) {
                // Not running.
            }
        }
    }

    private void engineResume() {
        if (this.booted && !this.leaving && this.corePaused) {
            try {
                nativeSetPaused(false);
                this.corePaused = false;
            } catch (Throwable ignored) {
                // Not running.
            }
        }
    }

    // ---- 화면 버튼 ----

    // ---- (0.0.416) The buttons gathered ----

    // The landscape screen {width, height, top margin} in dp: as tall as the screen (4:3, the
    // original Xbox's shape, or 16:9), the columns beside it at least 190 wide.
    private int[] landscapeScreen(int widthDp, int heightDp, boolean four) {
        if (this.legacyLayout) {
            int w = Math.min(widthDp - 2 * 178, (heightDp + statusBarDp() - 70) * 16 / 9);
            int h = w * 9 / 16;
            if (four) w = h * 4 / 3;
            return new int[]{w, h, 10};
        }
        int tall = heightDp - 24;
        int w = Math.min(widthDp - 2 * 190, four ? tall * 4 / 3 : tall * 16 / 9);
        int h = four ? w * 3 / 4 : w * 9 / 16;
        return new int[]{w, h, Math.max(8, (heightDp - h) / 2)};
    }

    // Landscape: each side's buttons in the middle of its column — LT, the D-pad, the left
    // stick, BACK and L3 left; RT, A B X Y with White · Black beside A, the right stick, START
    // and R3 right (L3 · R3 by the screen).
    private void buildPadLandscape(FrameLayout pad) {
        if (this.legacyLayout) {
            buildPadLandscapeLegacy(pad);
            return;
        }
        TouchButton.Listener buttons = this::onButton;
        int col = this.landCol;
        addAt(pad, new TouchButton(this, "LT", B_LT, buttons, TouchButton.STYLE_SHOULDER), 16, u(66), col - 28, u(34), Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "RT", B_RT, buttons, TouchButton.STYLE_SHOULDER), 16, u(66), col - 28, u(34), Gravity.TOP | Gravity.END);
        addAt(pad, new DPadView(this, this::onDirections), Math.max(4, (col - u(120)) / 2), u(108), u(120), u(120), Gravity.TOP | Gravity.START);
        int center = col / 2;
        int faceTop = u(166);
        int face = u(44);
        addFaceCluster(pad, center, faceTop, face);
        int small = u(30);
        float side = face * 1.57f;
        int smallTop = Math.round(faceTop + face * 0.92f - small / 2f);
        addSmall(pad, "WHITE", B_WHITE, Math.max(2, Math.round(center + side - small / 2f)), smallTop, small);
        addSmall(pad, "BLACK", B_BLACK, Math.max(2, Math.round(center - side - small / 2f)), smallTop, small);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), Math.max(4, (col - u(104)) / 2), u(46), u(104), u(104), Gravity.BOTTOM | Gravity.START);
        addAt(pad, rightStickView(), Math.max(4, (col - u(104)) / 2), u(46), u(104), u(104), Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "BACK", B_BACK, buttons), 10, 10, 64, 30, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "START", B_START, buttons), 10, 10, 64, 30, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", B_L3, buttons, TouchButton.STYLE_DARK), Math.max(84, col - 50), 6, 36, 36, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", B_R3, buttons, TouchButton.STYLE_DARK), Math.max(84, col - 50), 6, 36, 36, Gravity.BOTTOM | Gravity.END);
    }

    // Portrait: one block in the middle of the space under the screen — LT · RT, the D-pad and
    // A B X Y with White · Black between, the sticks with L3 · R3 between them, BACK · START.
    private void buildPad(FrameLayout outer) {
        if (this.legacyLayout) {
            buildPadLegacy(outer);
            return;
        }
        FrameLayout pad = new FrameLayout(this);
        pad.setClipChildren(false);
        outer.addView(pad, new FrameLayout.LayoutParams(-1, dp(u(PAD_BLOCK_DP)), Gravity.CENTER_VERTICAL));
        TouchButton.Listener buttons = this::onButton;
        addAt(pad, new TouchButton(this, "LT", B_LT, buttons, TouchButton.STYLE_SHOULDER), u(16), 0, u(100), u(40), Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "RT", B_RT, buttons, TouchButton.STYLE_SHOULDER), u(16), 0, u(100), u(40), Gravity.TOP | Gravity.END);
        addSmall(pad, "WHITE", B_WHITE, u(192), u(64), u(38));
        addSmall(pad, "BLACK", B_BLACK, u(192), u(152), u(38));
        addPortraitBody(pad, buttons);
    }

    private void addSmall(FrameLayout pad, String label, int bit, int rightDp, int topDp, int size) {
        TouchButton button = new TouchButton(this, label, bit, this::onButton, bit == B_WHITE ? TouchButton.STYLE_ACTION : TouchButton.STYLE_DARK);
        if (bit == B_WHITE) button.setLabelColor(Color.rgb(229, 231, 235));
        addAt(pad, button, rightDp, topDp, size, size, Gravity.TOP | Gravity.END);
    }

    // The part under the shoulder row (as the Xbox 360 screen's).
    private void addPortraitBody(FrameLayout pad, TouchButton.Listener buttons) {
        addAt(pad, new DPadView(this, this::onDirections), u(20), u(52), u(150), u(150), Gravity.TOP | Gravity.START);
        addFaceCluster(pad, u(104), u(127), u(56));
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), u(30), u(216), u(124), u(124), Gravity.TOP | Gravity.START);
        addAt(pad, rightStickView(), u(30), u(216), u(124), u(124), Gravity.TOP | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", B_L3, buttons, TouchButton.STYLE_DARK), u(166), u(258), u(38), u(38), Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", B_R3, buttons, TouchButton.STYLE_DARK), u(166), u(258), u(38), u(38), Gravity.TOP | Gravity.END);
        FrameLayout.LayoutParams back = new FrameLayout.LayoutParams(dp(u(92)), dp(u(34)), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        back.topMargin = dp(u(350));
        TouchButton backButton = new TouchButton(this, "BACK", B_BACK, buttons);
        backButton.setTranslationX(-dp(u(64)));
        pad.addView(backButton, back);
        FrameLayout.LayoutParams start = new FrameLayout.LayoutParams(dp(u(92)), dp(u(34)), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        start.topMargin = dp(u(350));
        TouchButton startButton = new TouchButton(this, "START", B_START, buttons);
        startButton.setTranslationX(dp(u(64)));
        pad.addView(startButton, start);
    }

    // A B X Y around a centre rightDp from the right edge and topDp from the top.
    private void addFaceCluster(FrameLayout pad, int rightDp, int topDp, int size) {
        int[] bits = {B_Y, B_B, B_A, B_X};
        String[] labels = {"Y", "B", "A", "X"};
        int[] colors = {Color.rgb(234, 179, 8), Color.rgb(220, 38, 38), Color.rgb(22, 163, 74), Color.rgb(37, 99, 235)};
        int[][] at = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        float step = size * 0.92f;
        for (int k = 0; k < 4; k++) {
            TouchButton face = new TouchButton(this, labels[k], bits[k], this::onButton, TouchButton.STYLE_ACTION);
            face.setLabelColor(colors[k]);
            int right = Math.round(rightDp - at[k][0] * step - size / 2f);
            int top = Math.round(topDp + at[k][1] * step - size / 2f);
            addAt(pad, face, Math.max(2, right), Math.max(0, top), size, size, Gravity.TOP | Gravity.END);
        }
    }

    // Landscape: LT · RT on top, D-pad and A B X Y with White · Black under them, the sticks,
    // BACK · START at the bottom corners, L3 · R3 by them.
    private void buildPadLandscapeLegacy(FrameLayout pad) {
        TouchButton.Listener buttons = this::onButton;
        addAt(pad, new TouchButton(this, "LT", B_LT, buttons, TouchButton.STYLE_SHOULDER), 12, 68, 100, 38, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "RT", B_RT, buttons, TouchButton.STYLE_SHOULDER), 12, 68, 100, 38, Gravity.TOP | Gravity.END);
        addAt(pad, new DPadView(this, this::onDirections), 24, 112, 120, 120, Gravity.TOP | Gravity.START);
        addFaceButtons(pad, new int[][]{{66, 110}, {20, 154}, {66, 198}, {112, 154}}, 46);
        addSmallButtons(pad, new int[][]{{122, 208}, {12, 208}}, 32);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), 34, 50, 100, 100, Gravity.BOTTOM | Gravity.START);
        addAt(pad, rightStickView(), 34, 50, 100, 100, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "BACK", B_BACK, buttons), 8, 10, 62, 30, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "START", B_START, buttons), 8, 10, 62, 30, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", B_L3, buttons, TouchButton.STYLE_DARK), 80, 6, 36, 36, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", B_R3, buttons, TouchButton.STYLE_DARK), 80, 6, 36, 36, Gravity.BOTTOM | Gravity.END);
    }

    // Portrait: LT · RT on top, D-pad and A B X Y with White · Black beside them, the sticks,
    // L3 · R3 under their inner sides, BACK · START apart at the bottom.
    private void buildPadLegacy(FrameLayout pad) {
        TouchButton.Listener buttons = this::onButton;
        addAt(pad, new TouchButton(this, "LT", B_LT, buttons, TouchButton.STYLE_SHOULDER), 12, 6, 96, 40, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "RT", B_RT, buttons, TouchButton.STYLE_SHOULDER), 12, 6, 96, 40, Gravity.TOP | Gravity.END);
        addAt(pad, new DPadView(this, this::onDirections), 18, 64, 140, 140, Gravity.TOP | Gravity.START);
        addFaceButtons(pad, new int[][]{{62, 64}, {14, 112}, {62, 160}, {110, 112}}, 52);
        addSmallButtons(pad, new int[][]{{164, 70}, {164, 164}}, 36);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), 24, 110, 116, 116, Gravity.BOTTOM | Gravity.START);
        addAt(pad, rightStickView(), 24, 110, 116, 116, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", B_L3, buttons, TouchButton.STYLE_DARK), 112, 62, 40, 40, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", B_R3, buttons, TouchButton.STYLE_DARK), 112, 62, 40, 40, Gravity.BOTTOM | Gravity.END);
        FrameLayout.LayoutParams back = new FrameLayout.LayoutParams(dp(70), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        TouchButton backButton = new TouchButton(this, "BACK", B_BACK, buttons);
        backButton.setTranslationX(-dp(64));
        back.bottomMargin = dp(16);
        pad.addView(backButton, back);
        FrameLayout.LayoutParams start = new FrameLayout.LayoutParams(dp(70), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        TouchButton startButton = new TouchButton(this, "START", B_START, buttons);
        startButton.setTranslationX(dp(64));
        start.bottomMargin = dp(16);
        pad.addView(startButton, start);
    }

    // Y on top, B right, A bottom, X left, in the controller's colours.
    private void addFaceButtons(FrameLayout pad, int[][] offsets, int size) {
        int[] bits = {B_Y, B_B, B_A, B_X};
        String[] labels = {"Y", "B", "A", "X"};
        int[] colors = {Color.rgb(234, 179, 8), Color.rgb(220, 38, 38), Color.rgb(22, 163, 74), Color.rgb(37, 99, 235)};
        for (int k = 0; k < 4; k++) {
            TouchButton face = new TouchButton(this, labels[k], bits[k], this::onButton, TouchButton.STYLE_ACTION);
            face.setLabelColor(colors[k]);
            addAt(pad, face, offsets[k][0], offsets[k][1], size, size, Gravity.TOP | Gravity.END);
        }
    }

    // The original controller's White and Black (smaller, by A B X Y).
    private void addSmallButtons(FrameLayout pad, int[][] offsets, int size) {
        TouchButton white = new TouchButton(this, "WHITE", B_WHITE, this::onButton, TouchButton.STYLE_ACTION);
        white.setLabelColor(Color.rgb(229, 231, 235));
        addAt(pad, white, offsets[0][0], offsets[0][1], size, size, Gravity.TOP | Gravity.END);
        TouchButton black = new TouchButton(this, "BLACK", B_BLACK, this::onButton, TouchButton.STYLE_DARK);
        addAt(pad, black, offsets[1][0], offsets[1][1], size, size, Gravity.TOP | Gravity.END);
    }

    private void onButton(int id, boolean pressed) {
        this.touchButtons = pressed ? this.touchButtons | id : this.touchButtons & ~id;
        turboTick(pressed);
        pushPad();
    }

    // DPadView reports the libretro bits: up 16, down 32, left 64, right 128.
    private void onDirections(int mask) {
        int d = 0;
        if ((mask & 16) != 0) d |= B_UP;
        if ((mask & 32) != 0) d |= B_DOWN;
        if ((mask & 64) != 0) d |= B_LEFT;
        if ((mask & 128) != 0) d |= B_RIGHT;
        this.touchDirections = d;
        pushPad();
    }

    private void onStick(boolean left, float x, float y) {
        float sens = stickSensitivity(false);
        x = Math.max(-1f, Math.min(1f, x * sens));
        y = Math.max(-1f, Math.min(1f, y * sens));
        if (left) {
            this.leftXf = x;
            this.leftYf = y;
        } else {
            this.rightXf = x;
            this.rightYf = y;
        }
        pushPad();
    }

    private int heldBits() {
        return this.touchButtons | this.touchDirections | this.padButtons | this.padHat;
    }

    // The whole pad to the core as player 1 (screen buttons and the controller together).
    private void pushPad() {
        if (!this.booted || this.leaving) {
            return;
        }
        int turbo = this.turboPhase ? turboMask() : 0;
        int now = heldBits() & ~turbo;
        float lx = this.padSticks ? this.padLX : this.leftXf;
        float ly = this.padSticks ? this.padLY : this.leftYf;
        float rx = this.padSticks ? this.padRX : this.rightXf;
        float ry = this.padSticks ? this.padRY : this.rightYf;
        if (this.padSticks && Math.abs(this.leftXf) + Math.abs(this.leftYf) > 0.05f) {
            lx = this.leftXf;
            ly = this.leftYf;
        }
        if (this.padSticks && Math.abs(this.rightXf) + Math.abs(this.rightYf) > 0.05f) {
            rx = this.rightXf;
            ry = this.rightYf;
        }
        float lt = (now & B_LT) != 0 ? 1f : (turbo & B_LT) != 0 ? 0f : this.padLT;
        float rt = (now & B_RT) != 0 ? 1f : (turbo & B_RT) != 0 ? 0f : this.padRT;
        try {
            nativeSetOverlayPad(now & 0xFF, (now >> 8) & 0x3F, lx, ly, rx, ry, lt, rt);
        } catch (Throwable ignored) {
            // The core not up yet.
        }
    }

    // ---- 게임패드: one controller, player 1 (White = LB, Black = RB, as xemu's) ----

    private static int padBit(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A: return B_A;
            case android.view.KeyEvent.KEYCODE_BUTTON_B: return B_B;
            case android.view.KeyEvent.KEYCODE_BUTTON_X: return B_X;
            case android.view.KeyEvent.KEYCODE_BUTTON_Y: return B_Y;
            case android.view.KeyEvent.KEYCODE_BUTTON_L1: return B_WHITE;
            case android.view.KeyEvent.KEYCODE_BUTTON_R1: return B_BLACK;
            case android.view.KeyEvent.KEYCODE_BUTTON_L2: return B_LT;
            case android.view.KeyEvent.KEYCODE_BUTTON_R2: return B_RT;
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL: return B_L3;
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR: return B_R3;
            case android.view.KeyEvent.KEYCODE_BUTTON_START: return B_START;
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT: return B_BACK;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return B_UP;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return B_DOWN;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return B_LEFT;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return B_RIGHT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !this.leaving) {
                if (this.layoutEditor != null) return super.dispatchKeyEvent(event);
                onBackPressed();
            }
            return true;
        }
        if (fromController(event) && !this.leaving && this.layoutEditor == null) {
            int bit = padBit(event.getKeyCode());
            if (bit != 0) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN || event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                    if (bit == B_LT || bit == B_RT) {
                        if (bit == B_LT) this.padLT = down ? 1f : 0f;
                        else this.padRT = down ? 1f : 0f;
                    } else {
                        this.padButtons = down ? this.padButtons | bit : this.padButtons & ~bit;
                    }
                    onPadUsed();
                    turboTick(down);
                    pushPad();
                }
                return true;  // not to SDL as well
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static float axis(android.view.MotionEvent event, int axis, float sensitivity) {
        float value = event.getAxisValue(axis);
        if (Math.abs(value) < 0.08f) return 0f;
        return Math.max(-1f, Math.min(1f, value * sensitivity));
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (fromController(event) && event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && !this.leaving) {
            float sens = stickSensitivity(true);
            this.padLX = axis(event, android.view.MotionEvent.AXIS_X, sens);
            this.padLY = axis(event, android.view.MotionEvent.AXIS_Y, sens);
            this.padRX = axis(event, android.view.MotionEvent.AXIS_Z, sens);
            this.padRY = axis(event, android.view.MotionEvent.AXIS_RZ, sens);
            this.padSticks = this.padLX != 0 || this.padLY != 0 || this.padRX != 0 || this.padRY != 0;
            float hx = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
            this.padHat = (hx < -0.5f ? B_LEFT : 0) | (hx > 0.5f ? B_RIGHT : 0) | (hy < -0.5f ? B_UP : 0) | (hy > 0.5f ? B_DOWN : 0);
            this.padLT = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
            this.padRT = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
            onPadUsed();
            turboTick(this.padLT > 0.4f || this.padRT > 0.4f);
            pushPad();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // ---- 빠른 저장 · 불러오기 (the core's savestates: its push-buffer mode only) ----
    static final String EXTRA_LOADSTATE = "com.example.minigba.XBOX_LOADSTATE";
    static final String EXTRA_RELAUNCH = "com.example.minigba.XBOX_RELAUNCH";
    private static final int SLOTS = 5;

    private File slotFile(int slot) {
        return new File(statesDir(this, this.gamePath), (slot == 0 ? "quick" : "slot" + slot) + ".state");
    }

    private boolean statesAvailable() {
        try {
            return this.booted && nativeStatesAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private void saveState(int slot) {
        if (!statesAvailable()) {
            toast("이 게임은 빠른 저장이 안 돼요 (엔진이 지원하지 않는 그리기 방식이에요). 게임 안에서 저장해 주세요.");
            return;
        }
        if (this.stateBusy) return;
        this.stateBusy = true;
        enginePause();
        final File file = slotFile(slot);
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        new Thread(() -> {
            String why;
            try {
                File temp = new File(file.getPath() + ".part");
                why = nativeSaveState(temp.getAbsolutePath());
                if (why == null && !temp.renameTo(file)) why = "파일을 저장하지 못했어요.";
                if (why != null) //noinspection ResultOfMethodCallIgnored
                    temp.delete();
            } catch (Throwable t) {
                why = t.getMessage();
            }
            final String shown = why;
            main.post(() -> {
                this.stateBusy = false;
                toast(shown == null ? (slot == 0 ? "빠른 저장했어요" : slot + "번 칸에 저장했어요") : "저장하지 못했어요: " + shown);
                resumeGame();
            });
        }, "xbox-save").start();
    }

    private void loadState(int slot) {
        File file = slotFile(slot);
        if (!file.isFile()) {
            toast("저장한 것이 없어요");
            return;
        }
        String why = null;
        try {
            why = nativeStateCheck(file.getAbsolutePath());
        } catch (Throwable t) {
            why = t.getMessage();
        }
        if (why != null) {
            toast("불러올 수 없어요: " + why);
            return;
        }
        // The core loads a savestate as it starts: the game starts again into it.
        restartWith(file.getAbsolutePath(), false);
    }

    private String slotTime(int slot) {
        File file = slotFile(slot);
        if (!file.isFile()) return "비어 있음";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date(file.lastModified()));
    }

    private void pickSlot(boolean save) {
        String[] names = new String[SLOTS + 1];
        String[] notes = new String[SLOTS + 1];
        for (int k = 0; k <= SLOTS; k++) {
            names[k] = k == 0 ? "빠른 저장 칸" : k + "번 칸";
            notes[k] = slotTime(k);
        }
        showChoices(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, save ? "저장할 칸" : "불러올 칸", names, notes, -1,
                save ? "고른 칸에 지금 상태를 저장해요 (있던 것은 바뀌어요)." : "고른 칸의 상태로 게임을 다시 켜요.", choice -> {
                    if (save) saveState(choice);
                    else loadState(choice);
                });
    }

    // ---- 메뉴 ----

    private void fillBasicTab(LinearLayout body, Choice close, Runnable toPerf) {
        java.util.List<View> tiles = new java.util.ArrayList<>();
        tiles.add(menuTile(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "빠른 저장", "길게: 칸 고르기", v -> {
            closeMenu(true);
            saveState(0);
        }));
        tiles.add(menuTile(RoundIconView.LOAD, RoundIconView.COLOR_LOAD, "불러오기", slotTime(0), v -> {
            closeMenu(true);
            pickSlot(false);
        }));
        tiles.add(menuTile(RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", null, v -> {
            closeMenu(true);
            restartGame();
        }));
        tiles.add(menuTile(RoundIconView.EXIT, RoundIconView.COLOR_EXIT, "나가기", null, v -> {
            this.leaving = true;
            closeMenu(true);
            leave();
        }));
        tiles.get(0).setOnLongClickListener(v -> {
            closeMenu(true);
            pickSlot(true);
            return true;
        });
        LinearLayout row = new LinearLayout(this);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (int k = 0; k < tiles.size(); k++) {
            row.addView(tiles.get(k), tile4Params(k));
        }
        java.util.List<View> more = new java.util.ArrayList<>();
        more.add(menuTile(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, landscape() ? "세로 화면" : "가로 화면", null, v -> {
            closeMenu(false);
            toggleOrientation();
        }));
        more.add(menuTile(RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "스크린샷", "화면만", v -> {
            closeMenu(false);
            main.postDelayed(this::takeScreenshot, 300);
        }));
        LinearLayout row2 = new LinearLayout(this);
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(-1, -2);
        row2Params.setMargins(0, dp(8), 0, 0);
        body.addView(row2, row2Params);
        for (int k = 0; k < more.size(); k++) {
            row2.addView(more.get(k), tile4Params(k));
        }
        TextView status = text("해상도 " + SCALE_NAMES[scaleIndex(this)].split(" ")[0] + " · FPS 제한 풀기 " + onOff(prefs(this).getBoolean("xbox_uncap", false))
                + " · 언어 " + LANG_NAMES[langIndex(this)] + "   성능 탭 ›", 12, Color.rgb(17, 94, 89), true);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(roundRect(Color.rgb(240, 253, 250), 13));
        status.setOnClickListener(v -> toPerf.run());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(10), 0, 0);
        body.addView(status, statusParams);
        TextView note = text(statesAvailable() ? "빠른 저장은 이 게임에 따로 6칸(빠른 저장 칸 + 1~5번)이 있어요. 불러오면 게임이 그 상태로 다시 켜져요."
                : "이 게임은 엔진이 빠른 저장을 지원하지 않는 그리기 방식이에요. 게임 안에서 저장해 주세요 (앱 안 가상 하드디스크에 저장돼요).",
                11.5f, Color.rgb(107, 114, 128), false);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        body.addView(note);
    }

    private void addToggleRow(LinearLayout body, int top, int icon, int color, String label, String key, boolean fallback, String offNote, String onNote, String explain) {
        boolean on = prefs(this).getBoolean(key, fallback);
        body.addView(menuRow(icon, color, label, onOff(on), v ->
                showChoices(icon, color, label, new String[]{"꺼짐", "켜짐"}, new String[]{offNote, onNote}, on ? 1 : 0, explain, choice -> {
                    prefs(this).edit().putBoolean(key, choice == 1).commit();
                    if ((choice == 1) != on) toast("재시작하면 적용돼요");
                })), rowParams(top));
    }

    private void addScaleRow(LinearLayout body, int top) {
        int scale = scaleIndex(this);
        body.addView(menuRow(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도 배율", SCALE_NAMES[scale].split(" ")[0], v ->
                showChoices(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도 배율", SCALE_NAMES,
                        new String[]{"초대 Xbox 그대로 · 가벼움 (기본)", "두 배 또렷하게 · 무거움", "세 배 · 고사양 폰"}, scale,
                        "게임이 그리는 크기예요. 모든 초대 Xbox 게임에 같이 적용돼요. 재시작하면 적용돼요.", choice -> {
                    prefs(this).edit().putInt("xbox_scale", choice).commit();
                    if (choice != scale) toast("재시작하면 적용돼요");
                })), rowParams(top));
    }

    private void fillPerformanceTab(LinearLayout body, Choice close) {
        boolean perf = prefs(this).getBoolean("xbox_perf", false);
        body.addView(menuRow(RoundIconView.PERF, RoundIconView.COLOR_PERF, "FPS 표시", onOff(perf), v ->
                showChoices(RoundIconView.PERF, RoundIconView.COLOR_PERF, "FPS 표시", new String[]{"꺼짐", "켜짐"},
                        new String[]{"표시 없음 (기본)", "게임 화면 구석에 FPS · 프레임 시간"}, perf ? 1 : 0, null, choice ->
                                prefs(this).edit().putBoolean("xbox_perf", choice == 1).commit())), rowParams(0));
        addToggleRow(body, 8, RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "FPS 제한 풀기", "xbox_uncap", false,
                "화면 주사율에 맞춰요 (기본)", "기다리지 않고 바로 보여요 · 게임에 따라 빨라지거나 부드러워져요",
                "모든 초대 Xbox 게임에 같이 적용돼요. 재시작하면 적용돼요.");
        addScaleRow(body, 8);
    }

    private void fillSystemTab(LinearLayout body, Choice close) {
        addScaleRow(body, 0);
        addToggleRow(body, 8, RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "FXAA (가장자리 부드럽게)", "xbox_fxaa", false,
                "그대로 (기본)", "계단 현상을 줄여요 · 조금 흐려져요", "재시작하면 적용돼요.");
        addToggleRow(body, 8, RoundIconView.HD, Color.rgb(3, 105, 161), "와이드 화면 (16:9)", "xbox_wide", false,
                "4:3 TV처럼 (기본)", "본체를 와이드 TV로 설정 · 지원하는 게임은 16:9로 그려요",
                "본체 설정이에요. 게임 화면 틀 비율은 화면 탭에서 바꿔요. 재시작하면 적용돼요.");
        int lang = langIndex(this);
        body.addView(menuRow(RoundIconView.EDIT, Color.rgb(190, 18, 60), "본체 언어", LANG_NAMES[lang], v -> {
            String[] notes = new String[LANG_NAMES.length];
            notes[0] = "한국어가 들어 있는 게임이 한글로 (기본) · 없으면 게임이 영어로";
            for (int k = 1; k < notes.length; k++) notes[k] = "";
            showChoices(RoundIconView.EDIT, Color.rgb(190, 18, 60), "본체 언어", LANG_NAMES, notes, lang,
                    "본체(대시보드) 언어예요. 게임이 지원하면 그 언어로 나와요. 재시작하면 적용돼요.", choice -> {
                prefs(this).edit().putInt("xbox_lang", choice).commit();
                if (choice != lang) toast("재시작하면 적용돼요");
            });
        }), rowParams(8));
        addToggleRow(body, 8, RoundIconView.BOLT, Color.rgb(124, 58, 237), "PAL 60Hz", "xbox_pal60", false,
                "그대로 (기본)", "유럽판 게임을 60Hz로 (게임이 지원하면)", "재시작하면 적용돼요.");
        TextView info = text("엔진: HakuX 1.3.0 (초대 Xbox 휴대폰판 · BIOS 필요 없음)\n게임: " + getIntent().getStringExtra(EXTRA_TITLE), 12, Color.rgb(75, 85, 99), false);
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        info.setBackground(roundRect(Color.rgb(246, 247, 249), 14));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, dp(8), 0, 0);
        body.addView(info, infoParams);
    }

    // 재시작 (and a savestate's load, and the title's relaunch into another XBE): the core runs
    // once per process, so the game starts again in a new one, from the home screen.
    private void restartGame() {
        restartWith(null, false);
    }

    private void restartWith(String state, boolean relaunch) {
        this.leaving = true;
        Intent again = new Intent(this, MainActivity.class);
        again.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        again.putExtra(MainActivity.EXTRA_RELAUNCH_XBOX, this.gamePath);
        again.putExtra(MainActivity.EXTRA_RELAUNCH_XBOX_TITLE, getIntent().getStringExtra(EXTRA_TITLE));
        if (state != null) again.putExtra(EXTRA_LOADSTATE, state);
        if (relaunch) again.putExtra(EXTRA_RELAUNCH, true);
        startActivity(again);
        finish();
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
    }

    // 스크린샷: the game's picture (PixelCopy of the surface) into Pictures/Mini Emulator.
    private void takeScreenshot() {
        SurfaceView view = this.surfaceView;
        if (view == null || view.getWidth() == 0 || Build.VERSION.SDK_INT < 24) {
            toast("스크린샷을 찍을 수 없어요");
            return;
        }
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.request(view, bitmap, result -> {
            if (result != android.view.PixelCopy.SUCCESS) {
                toast("스크린샷 실패");
                return;
            }
            new Thread(() -> {
                String message;
                try {
                    String name = "Xbox " + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".png";
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
                    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Mini Emulator");
                    Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new java.io.IOException("사진첩에 저장할 수 없어요");
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new java.io.IOException("사진첩에 저장할 수 없어요");
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    message = "사진첩(Pictures/Mini Emulator)에 저장했어요";
                } catch (Throwable t) {
                    message = "스크린샷 실패: " + t.getMessage();
                }
                bitmap.recycle();
                String shown = message;
                main.post(() -> toast(shown));
            }, "xbox-shot").start();
        }, main);
    }

    // 위쪽 첫 버튼: 빠른 저장 (길게: 칸 고르기).
    private void onSaveClicked() {
        if (!this.booted || this.layoutEditor != null) return;
        saveState(0);
    }

    private void onResetClicked() {
        if (!this.booted || this.layoutEditor != null) return;
        enginePause();
        new AppDialog.Builder(this).setTitle("게임을 다시 시작할까요?").setMessage("저장하지 않은 진행은 없어져요.")
                .setNegativeButton("취소", (d, w) -> resumeGame())
                .setPositiveButton("재시작", (d, w) -> restartGame()).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BG_EDITOR) {
            buildGameScreen();
            resumeGame();
        }
    }

    // Ends this process (the core runs once per process).
    private void leave() {
        this.leaving = true;
        main.removeCallbacks(this.poll);
        finish();
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (this.booted && !this.leaving) {
            enginePause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.booted && !this.leaving && this.pauseDialog == null && this.layoutEditor == null) {
            engineResume();
        }
    }
}
