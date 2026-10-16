package com.example.minigba;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.example.minigba.ArcadeJoystickView;
import com.example.minigba.DPadView;
import com.example.minigba.SpeedButtonView;
import com.example.minigba.TouchButton;
import com.seleuco.mame4droid.Emulator;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GameActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_CORE_ID = "com.example.minigba.CORE_ID";
    static final String EXTRA_ROM_PATH = "com.example.minigba.ROM_PATH";
    static final String EXTRA_SAVE_BASE_NAME = "com.example.minigba.SAVE_BASE_NAME";
    static final String EXTRA_TITLE = "com.example.minigba.TITLE";
    static final String EXTRA_MODE = "com.example.minigba.MODE";
    static final String SESSION_START = "game_session_start";
    static final String SESSION_CLOSED = "game_session_closed";
    private static final String MENU_CONTINUE = "계속하기";
    private static final String MENU_SAVE_STATE = "빠른 저장";
    private static final String MENU_LOAD_STATE = "빠른 저장 불러오기";
    private static final String MENU_SAVE_SRAM = "게임 내 세이브 저장";
    private static final String MENU_RESET = "재시작";
    private static final String MENU_EXIT = "ROM 목록으로 나가기";
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREF_ORIENTATION_MODE = "orientation_mode";
    private SurfaceHolder activeSurfaceHolder;
    private AudioSink audioSink;
    private int buttonMask;
    private String coreId;
    private String gameMode;
    private String[] menuItems = new String[0];
    private SpeedButtonView fastForwardButton;
    // PSP landscape controls, placed beside the screen once its size is known.
    private View[] pspSideViews;
    private boolean landscapeMode;
    private boolean menuExiting;
    private long nativeHandle;
    // Calls that wait for the engine (loading, saving, stopping) run here, never on the UI
    // thread, so a slow or stuck engine cannot freeze the buttons and the back key. Loading a
    // MAME set recurses deeply, hence the large stack (the main thread it replaces has 8 MB).
    private final java.util.concurrent.ExecutorService coreWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> new Thread(null, runnable, "core-worker", 16L * 1024 * 1024));
    private boolean coreStarting;
    private volatile boolean destroyed;
    private boolean activityResumed;
    private static final int LANDSCAPE_SHIFT_DP = 12;
    private String romPath;
    private String saveBaseName;
    private String gameTitle = "";
    private int speedPercent = 100;
    private boolean surfaceReady;
    private SurfaceView surfaceView;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        boolean z = getSharedPreferences("settings", 0).getInt("orientation_mode", 0) == 1;
        this.landscapeMode = z;
        setRequestedOrientation(z ? 6 : 1);
        requestWindowFeature(1);
        getWindow().setFlags(1024, 1024);
        this.romPath = getIntent().getStringExtra("com.example.minigba.ROM_PATH");
        // Marks a game session; closing the game clears it. If the game process ends any
        // other way the game list asks the system why (MainActivity.showUnexpectedGameExit).
        getSharedPreferences("settings", 0).edit().putLong(SESSION_START, System.currentTimeMillis()).putBoolean(SESSION_CLOSED, false).commit();
        String stringExtra = getIntent().getStringExtra("com.example.minigba.CORE_ID");
        this.coreId = stringExtra;
        Haptics.setLevel(this, hapticLevel());
        NativeGba.setSharpScaling(screenFilter() != 0);
        if (stringExtra == null) {
            this.coreId = "gba";
        }
        this.gameMode = getIntent().getStringExtra(EXTRA_MODE);
        if (this.gameMode == null) {
            this.gameMode = this.coreId.equals("gba") ? "gba" : isWonderSwan() ? "ws" : isNeoGeoPocket() ? "ngp" : isMegaCd() ? "mcd" : isSaturn() ? "ss" : isSnes() ? "snes" : isNes() ? "nes" : isMasterSystem() ? "sms" : isN64() ? "n64" : isDreamcast() ? "dc" : isMegaDrive() ? "md" : isPcEngine() ? "pce" : isPsp() ? "psp" : isPs1() ? "ps1" : isPs2() ? "ps2" : isNds() ? "nds" : "arcade";
        }
        String str = this.romPath;
        String lowerCase = str == null ? "" : str.toLowerCase(Locale.US);
        if (!isSupportedGameFile(lowerCase)) {
            showFatalError("지원하지 않는 파일입니다");
            return;
        }
        String titleExtra = getIntent().getStringExtra(EXTRA_TITLE);
        this.gameTitle = titleExtra != null ? titleExtra : (this.romPath == null ? "" : baseNameWithoutExtension(new File(this.romPath).getName()));
        String stringExtra2 = getIntent().getStringExtra("com.example.minigba.SAVE_BASE_NAME");
        this.saveBaseName = stringExtra2;
        if (stringExtra2 == null || stringExtra2.trim().isEmpty()) {
            this.saveBaseName = baseNameWithoutExtension(new File(this.romPath).getName());
        }
        this.audioSink = new AudioSink();
        if (this.landscapeMode) {
            createLandscapeContent();
        } else {
            createPortraitContent();
        }
    }

    private void createPortraitContent() {
        if (isPadScreen()) {
            createPadContent();
            return;
        }
        setNdsLayout(0);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.addView(createTopBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        boolean full = isFullScreenStyle();
        int dp = full ? 0 : dp(4);
        int dp2 = full ? 0 : dp(2);
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setBackgroundColor(Color.rgb(18, 22, 32));
        frameLayout.setPadding(0, dp, 0, dp2);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        attachTouchScreen(surfaceView);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(-1, -1);
        layoutParams.gravity = 17;
        frameLayout.addView(this.surfaceView, layoutParams);
        addScreenFilter(frameLayout);
        // Frame style leaves room around the screen for the case's bezel and its screws;
        // full style runs the screen edge to edge.
        int[] frame = portraitScreen(dp(34), dp(4) + dp(2));
        int[] screen = full ? portraitScreen(dp(6), 0) : frame;
        int side = screen[0];
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, screen[1]);
        layoutParams2.setMargins(side, full ? dp(6) : dp(18), side, 0);
        linearLayout.addView(frameLayout, layoutParams2);
        FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(0);
        addControls(frameLayout2);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        // The controls start where the frame style puts them, whatever the style.
        controlsParams.topMargin = full ? Math.max(dp(10), dp(18) + frame[1] + dp(36) - dp(6) - screen[1]) : dp(36);
        if (isNds()) {
            // The DS screens grow into whatever the controls leave, so the bezel under them is short.
            controlsParams.topMargin = dp(20);
            final int screenTop = layoutParams2.topMargin;
            final int minSide = full ? dp(6) : dp(34);
            final int padding = full ? 0 : dp(4) + dp(2);
            linearLayout.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> fitNdsPortrait(frameLayout, r - l, b - t, screenTop, minSide, padding));
        }
        linearLayout.addView(frameLayout2, controlsParams);
        FrameLayout root = new FrameLayout(this);
        ClearShellView shell = createShell(false);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
        shell.setThinBezel(full);
        shell.track(frameLayout, frameLayout2);
        setContentView(root);
        installComboZones(root);
        applyLayoutEdits(root);
    }

    private void createLandscapeContent() {
        if (isPadScreen()) {
            createPadContent();
            return;
        }
        setNdsLayout(1);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        FrameLayout frameLayout = new FrameLayout(this);
        final boolean full = isFullScreenStyle();
        final int i = full ? 1 : 0;
        // The DS screens run from the top edge to the bottom edge, without a bezel.
        int dp = full || isNds() ? 0 : dp(2);
        final FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(Color.rgb(18, 22, 32));
        frameLayout2.setPadding(dp, dp, dp, dp);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        attachTouchScreen(surfaceView);
        frameLayout2.addView(this.surfaceView, new FrameLayout.LayoutParams(-1, -1, 17));
        addScreenFilter(frameLayout2);
        frameLayout.addView(frameLayout2, new FrameLayout.LayoutParams(dp(320), dp(214), 17));
        addLandscapeControls(frameLayout);
        addLandscapeCornerButtons(frameLayout);
        frameLayout.addOnLayoutChangeListener((view, i2, i3, i4, i5, i6, i7, i8, i9) -> fitLandscapeScreen(i, frameLayout2, view, i2, i3, i4, i5, i6, i7, i8, i9));
        linearLayout.addView(frameLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        FrameLayout root = new FrameLayout(this);
        ClearShellView shell = createShell(true);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
        shell.setThinBezel(full);
        shell.setCompactBezel(!full && (isDualShock() || isNds()));
        shell.track(isNds() ? null : frameLayout2, frameLayout);
        setContentView(root);
        installComboZones(root);
        applyLayoutEdits(root);
    }

    // "프레임" (the default) draws the case's bezel around the screen; "전체 화면" drops it
    // and makes the screen as large as fits beside the controls.
    private boolean isGbaWideFull() {
        return this.coreId.equals("gba") && this.landscapeMode && isFullScreenStyle();
    }

    private boolean isFullScreenStyle() {
        return "full".equals(getSharedPreferences("settings", 0).getString("screen_style", "frame"));
    }

    // "게임패드": for a physical controller. No touch controls or case, only the game screen;
    // the menu button shows for a moment after a tap, or SELECT + START opens the menu.
    private boolean isPadScreen() {
        return "pad".equals(getSharedPreferences("settings", 0).getString("screen_style", "frame"));
    }

    private int ndsLayout;

    private void setNdsLayout(int layout) {
        if (isNds()) {
            this.ndsLayout = layout;
            NativeGba.setNdsLayout(this.nativeHandle, layout);
        }
    }

    private View padMenuButton;
    private final Runnable hidePadMenuButton = () -> {
        if (this.padMenuButton != null) {
            this.padMenuButton.setVisibility(View.GONE);
        }
    };

    private void createPadContent() {
        // DS screens side by side in landscape: with no buttons beside them they get twice the size.
        setNdsLayout(this.landscapeMode ? 2 : 0);
        this.fastForwardButton = null;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        attachTouchScreen(surfaceView);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        addScreenFilter(root);
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> fitPadScreen(surfaceView, r - l, b - t));
        RoundIconView menu = new RoundIconView(this, RoundIconView.MENU, RoundIconView.COLOR_MENU);
        menu.setAlpha(0.8f);
        menu.setContentDescription("메뉴");
        menu.setOnClickListener(view -> showGameMenu());
        menu.setVisibility(View.GONE);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        menuParams.setMargins(0, dp(12), dp(12), 0);
        root.addView(menu, menuParams);
        this.padMenuButton = menu;
        setContentView(root);
    }

    // The game picture as large as fits, centred on black.
    private void fitPadScreen(View screen, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float aspect = screenAspect();
        int screenWidth = Math.min(width, Math.round(height * aspect));
        int screenHeight = Math.min(height, Math.round(screenWidth / aspect));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) screen.getLayoutParams();
        if (params.width != screenWidth || params.height != screenHeight) {
            params.width = screenWidth;
            params.height = screenHeight;
            screen.post(() -> screen.setLayoutParams(params));
        }
    }

    private void showPadMenuButton() {
        View menu = this.padMenuButton;
        if (menu == null || !isPadScreen()) {
            return;
        }
        menu.setVisibility(View.VISIBLE);
        menu.removeCallbacks(this.hidePadMenuButton);
        menu.postDelayed(this.hidePadMenuButton, 3000);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            showPadMenuButton();
        }
        return super.dispatchTouchEvent(event);
    }

    // Sizes the stacked DS screens to the height the window really has: the controls keep
    // dp(268) (L/R, then the D-pad and face buttons, then SELECT/START), the screens get the rest.
    private void fitNdsPortrait(FrameLayout screenFrame, int width, int height, int screenTop, int minSide, int padding) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float aspect = screenAspect();
        int tallest = height - dp(64) - screenTop - dp(20) - dp(268) - padding;
        int screenWidth = Math.max(dp(200), Math.min(width - minSide * 2, Math.round(tallest * aspect)));
        int side = (width - screenWidth) / 2;
        int screenHeight = Math.round(screenWidth / aspect) + padding;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) screenFrame.getLayoutParams();
        if (params.height != screenHeight || params.leftMargin != side || params.rightMargin != side) {
            params.height = screenHeight;
            params.leftMargin = side;
            params.rightMargin = side;
            screenFrame.post(() -> screenFrame.setLayoutParams(params));
        }
    }

    // Side margin and height of the portrait screen for a side margin and vertical padding.
    private int[] portraitScreen(int side, int padding) {
        int screenWidth = Math.max(dp(200), getResources().getDisplayMetrics().widthPixels - side * 2);
        if (isGameBoy() || isGameGear() || isNeoGeoPocket() || (isWonderSwan() && wonderSwanTall())) {
            // A nearly square (or upright) screen would push the controls off a short phone.
            int widest = Math.round(dp(isWonderSwan() ? 330 : 250) * screenAspect());
            if (screenWidth > widest) {
                side += (screenWidth - widest) / 2;
                screenWidth = widest;
            }
        }
        if (isNds()) {
            // The tall stacked DS screens would push the D-pad and face buttons off the
            // bottom, so they stop where the controls need dp(272) under the bezel.
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int statusId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            int statusBar = statusId > 0 ? getResources().getDimensionPixelSize(statusId) : dp(24);
            int tallest = metrics.heightPixels - statusBar - dp(64) - dp(18) - dp(36) - dp(272) - padding;
            int widest = Math.max(dp(200), Math.round(tallest * screenAspect()));
            if (screenWidth > widest) {
                side += (screenWidth - widest) / 2;
                screenWidth = widest;
            }
        }
        return new int[]{side, Math.max(dp(176), Math.round(screenWidth / screenAspect()) + padding)};
    }

    // Clear case behind the controls, tinted per game kind.
    private ClearShellView createShell(boolean landscape) {
        int tint;
        String name;
        if (this.coreId.equals("gba")) {
            tint = ClearShellView.TINT_WHITE;
            name = isGameBoy() ? "GBC" : "GBA";
        } else if (isSnes()) {
            tint = ClearShellView.TINT_GRAY;
            name = "SUPER FAMICOM";
        } else if (isNds()) {
            tint = ClearShellView.TINT_WHITE;
            name = "NINTENDO DS";
        } else if (isNes()) {
            tint = ClearShellView.TINT_CREAM;
            name = "FAMICOM";
        } else if (isN64()) {
            tint = ClearShellView.TINT_GRAY;
            name = "NINTENDO 64";
        } else if (isDreamcast()) {
            tint = ClearShellView.TINT_WHITE;
            name = "DREAMCAST";
        } else if (isMasterSystem()) {
            tint = ClearShellView.TINT_SMOKE;
            name = isGameGear() ? "GAME GEAR" : "MASTER SYSTEM";
        } else if (isMegaDrive()) {
            tint = ClearShellView.TINT_SMOKE;
            name = "MEGA DRIVE";
        } else if (isPcEngine()) {
            tint = ClearShellView.TINT_WHITE;
            name = "PC ENGINE";
        } else if (isPsp()) {
            tint = ClearShellView.TINT_SMOKE;
            name = "PSP";
        } else if (isPs1()) {
            tint = ClearShellView.TINT_GRAY;
            name = "PS1";
        } else if (isPs2()) {
            tint = ClearShellView.TINT_AQUA;
            name = "PS2";
        } else if (isNeoGeoMode()) {
            tint = ClearShellView.TINT_PURPLE;
            name = "NEO GEO";
        } else {
            tint = ClearShellView.TINT_ORANGE;
            name = "ARCADE";
        }
        return new ClearShellView(this, tint, "MINI EMULATOR · " + name, landscape);
    }

    private void fitLandscapeScreen(int i, FrameLayout frameLayout, View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean full = i == 1;
        if (isSonyPad() && this.pspSideViews != null) {
            layoutPspLandscape(frameLayout, width, height);
            return;
        }
        // Pad layouts (GBA, Super Famicom) keep the speed button below the screen.
        boolean isGba = isPadLayout();
        int maxWidth = Math.max(dp(240), width - dp(isGbaWideFull() ? 156 : isGba ? 174 : 188) * 2);
        // GBA keeps the speed button below the screen, so its screen sits at the top.
        // The bezel takes dp(10) above the screen and dp(26) below it; full style has none.
        int maxHeight;
        if (isNds()) {
            // Stacked DS screens take the full height, edge to edge.
            maxHeight = Math.max(dp(150), height);
        } else if (full) {
            // Room for the thin glass bezel (dp(5) each side) included.
            maxHeight = Math.max(dp(150), height - (isGba ? dp(13) + dp(5) + dp(46) : dp(12) + dp(10)));
        } else {
            maxHeight = Math.max(dp(150), height - (isGba ? dp(18) + dp(26) + dp(46) : dp(12) + dp(36)));
        }
        float aspect = screenAspect();
        int screenWidth = Math.min(maxWidth, Math.round(maxHeight * aspect));
        int screenHeight = Math.round(screenWidth / aspect);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameLayout.getLayoutParams();
        int topMargin = isNds() ? 0 : isGba ? dp(full ? 13 : 18) : 0;
        // Arcade screens sit centred, nudged up so the taller lower bezel stays balanced.
        int bottomMargin = isGba || full ? 0 : dp(16);
        if (layoutParams.width != screenWidth || layoutParams.height != screenHeight || layoutParams.topMargin != topMargin || layoutParams.bottomMargin != bottomMargin) {
            layoutParams.width = screenWidth;
            layoutParams.height = screenHeight;
            layoutParams.gravity = isGba ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER;
            layoutParams.topMargin = topMargin;
            layoutParams.bottomMargin = bottomMargin;
            frameLayout.setLayoutParams(layoutParams);
        }
    }

    private void addLandscapeControls(FrameLayout frameLayout) {
        if (isSaturn()) {
            addSaturnLandscapeControls(frameLayout);
            return;
        }
        if (isWonderSwan()) {
            addWonderSwanLandscapeControls(frameLayout);
            return;
        }
        if (isNeoGeoPocket()) {
            addNeoGeoPocketLandscapeControls(frameLayout);
            return;
        }
        if (isN64()) {
            addN64LandscapeControls(frameLayout);
            return;
        }
        if (isDreamcast()) {
            addDreamcastLandscapeControls(frameLayout);
            return;
        }
        View createDirectionControl = createDirectionControl();
        boolean isGba = isPadLayout();
        // GBA in full-screen style moves its D-pad and A/B a little outward so the wide
        // screen can grow until it meets the speed button.
        boolean wideGba = isGbaWideFull();
        if (isSonyPad()) {
            addPspLandscapeControls(frameLayout, createDirectionControl);
            return;
        }
        int padSize = 150;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(padSize), dp(padSize));
        layoutParams.gravity = 8388627;
        layoutParams.setMargins(wideGba ? 0 : dp(12), 0, 0, 0);
        // Everything sits a little lower to leave the top corners to the round buttons;
        // the PSP's D-pad goes up instead, with the analog stick under it.
        createDirectionControl.setTranslationY(dp(LANDSCAPE_SHIFT_DP));
        frameLayout.addView(createDirectionControl, layoutParams);
        if (isGba) {
            if (hasShoulders()) {
                addButton(frameLayout, "L", 512, 8388659, dp(18), dp(66), dp(104), dp(40), 2);
                addButton(frameLayout, "R", 256, 8388661, dp(18), dp(66), dp(104), dp(40), 2);
            }
            if (hasTurboPair()) {
                String[] labels = turboLabels();
                int style = isPcEngine() ? TouchButton.STYLE_ACTION : TouchButton.STYLE_ARCADE_RED;
                addLandscapeArcadeButton(frameLayout, labels[0], 1, 20, 0, 60, style);
                addLandscapeArcadeButton(frameLayout, labels[1], 2, 92, 14, 60, style);
                addLandscapeArcadeButton(frameLayout, labels[2], 1024, 29, -56, 42, style);
                addLandscapeArcadeButton(frameLayout, labels[3], 2048, 101, -42, 42, style);
            } else if (isMegaDrive()) {
                addMegaDriveButtons(frameLayout, true);
            } else if (isSnes() || isNds()) {
                // X on top, A right, B bottom, Y left, in the Super Famicom colours (DS: dark).
                boolean ds = isNds();
                addLandscapeArcadeButton(frameLayout, "X", 1024, 66, -48, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_SNES_BLUE);
                addLandscapeArcadeButton(frameLayout, "A", 1, 18, 0, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_RED);
                addLandscapeArcadeButton(frameLayout, "B", 2, 66, 48, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_YELLOW);
                addLandscapeArcadeButton(frameLayout, "Y", 2048, 114, 0, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_GREEN);
            } else {
                // Master System: 2 is libretro A, 1 is B (Genesis Plus GX's two-button pad).
                boolean sms = isMasterSystem();
                addLandscapeActionButton(frameLayout, sms ? "2" : "A", 1, dp(wideGba ? 6 : 26), -dp(42), sms ? TouchButton.STYLE_DARK : 3);
                addLandscapeActionButton(frameLayout, sms ? "1" : "B", 2, dp(wideGba ? 62 : 92), dp(42), sms ? TouchButton.STYLE_DARK : 3);
            }
        } else if (isNeoGeoMode()) {
            // Diamond with equal spacing; the right edge matches the joystick's left margin.
            addLandscapeArcadeButton(frameLayout, "C", 1024, 64, -52, 50, TouchButton.STYLE_ARCADE_GREEN);
            addLandscapeArcadeButton(frameLayout, "D", 2048, 12, 0, 50, TouchButton.STYLE_ARCADE_BLUE);
            addLandscapeArcadeButton(frameLayout, "B", 2, 64, 52, 50, TouchButton.STYLE_ARCADE_YELLOW);
            addLandscapeArcadeButton(frameLayout, "A", 1, 116, 0, 50, TouchButton.STYLE_ARCADE_RED);
        } else {
            // 3x2 grid: D E F on top, A B C below.
            addLandscapeArcadeButton(frameLayout, "A", 1, 120, 27, 46, TouchButton.STYLE_ARCADE_BLUE);
            addLandscapeArcadeButton(frameLayout, "B", 2, 66, 27, 46, TouchButton.STYLE_ARCADE_BLUE);
            addLandscapeArcadeButton(frameLayout, "C", 1024, 12, 27, 46, TouchButton.STYLE_ARCADE_BLUE);
            addLandscapeArcadeButton(frameLayout, "D", 2048, 120, -27, 46, TouchButton.STYLE_ARCADE_RED);
            addLandscapeArcadeButton(frameLayout, "E", 4096, 66, -27, 46, TouchButton.STYLE_ARCADE_RED);
            addLandscapeArcadeButton(frameLayout, "F", 8192, 12, -27, 46, TouchButton.STYLE_ARCADE_RED);
        }
        if (isMasterSystem()) {
            // No SELECT: START sits under the D-pad, the speed button under 1 and 2.
            addButton(frameLayout, startLabel(), 8, 8388691, dp(42), dp(18), dp(94), dp(34));
            return;
        }
        addButton(frameLayout, selectLabel(), 4, 8388691, dp(42), dp(18), dp(94), dp(34));
        addButton(frameLayout, startLabel(), 8, 8388693, dp(42), dp(18), dp(94), dp(34));
    }

    private View createDirectionControl() {
        if (!isPadLayout()) {
            return new ArcadeJoystickView(this, this::onDirectionChanged);
        }
        DPadView pad = new DPadView(this, this::onDirectionChanged);
        pad.setLight(isPs1());
        return pad;
    }

    private void onDirectionChanged(int i) {
        this.touchDirection = i;
        applyDirections();
    }

    // The D-pad bits are the touch pad's and the controller's together.
    private int touchDirection;
    private int padKeyDirection;
    private int padHatDirection;
    private int padStickDirection;

    private void applyDirections() {
        int directions = this.touchDirection | this.padKeyDirection | this.padHatDirection | this.padStickDirection;
        this.buttonMask = directions | (this.buttonMask & (-241));
        updateInput();
    }

    // ---- Physical controllers (Bluetooth / USB) ----
    // Face buttons go by position, as on the touch pads: bottom/right/left/top are the
    // libretro B/A/Y/X (arcade sets: A/B/C/D, with E/F on the shoulders).
    private int padButtons;

    private static boolean isControllerEvent(android.view.InputEvent event) {
        int source = event.getSource();
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    private int padButtonBit(int keyCode) {
        boolean arcade = !isPadLayout();
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return arcade ? 1 : 2;
            case KeyEvent.KEYCODE_BUTTON_B: return arcade ? 2 : 1;
            case KeyEvent.KEYCODE_BUTTON_X: return arcade ? 1024 : 2048;
            case KeyEvent.KEYCODE_BUTTON_Y: return arcade ? 2048 : 1024;
            case KeyEvent.KEYCODE_BUTTON_L1: return arcade ? 4096 : 512;
            case KeyEvent.KEYCODE_BUTTON_R1: return arcade ? 8192 : 256;
            case KeyEvent.KEYCODE_BUTTON_L2: return kL2;
            case KeyEvent.KEYCODE_BUTTON_R2: return kR2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 65536;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 131072;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 4;
            case KeyEvent.KEYCODE_BUTTON_START: return 8;
            default: return 0;
        }
    }

    private static int padDirectionBit(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 16;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 32;
            case KeyEvent.KEYCODE_DPAD_UP: return 64;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 128;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!isControllerEvent(event)) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!down) {
                openMenuFromController();
            }
            return true;
        }
        int direction = padDirectionBit(keyCode);
        if (direction != 0) {
            this.padKeyDirection = down ? this.padKeyDirection | direction : this.padKeyDirection & ~direction;
            applyDirections();
            return true;
        }
        int bit = padButtonBit(keyCode);
        if (bit == 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        this.padButtons = down ? this.padButtons | bit : this.padButtons & ~bit;
        if ((this.padButtons & 12) == 12) {
            // SELECT + START together open the menu instead of reaching the game.
            openMenuFromController();
            return true;
        }
        setButtonPressed(bit, down);
        return true;
    }

    // The menu dialog takes the key events from here on, so let go of everything first.
    private void openMenuFromController() {
        releaseController();
        showGameMenu();
    }

    private void releaseController() {
        this.padButtons = 0;
        this.padTriggers = 0;
        this.padKeyDirection = 0;
        this.padHatDirection = 0;
        this.padStickDirection = 0;
        this.buttonMask = 0;
        this.touchDirection = 0;
        java.util.Arrays.fill(this.buttonHolds, 0);
        updateInput();
        if (isSonyPad() || isN64() || isDreamcast()) {
            onAnalogChanged(0, 0);
            onAnalogRightChanged(0, 0);
        }
    }

    private static float padAxis(android.view.MotionEvent event, int axis) {
        android.view.InputDevice device = event.getDevice();
        float value = event.getAxisValue(axis);
        if (device != null) {
            android.view.InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
            if (range != null && Math.abs(value) <= Math.max(range.getFlat(), 0.12f)) {
                return 0;
            }
        }
        return Math.abs(value) <= 0.12f ? 0 : value;
    }

    private static int directionsFor(float x, float y, float threshold) {
        int bits = 0;
        if (x > threshold) bits |= 16;
        if (x < -threshold) bits |= 32;
        if (y < -threshold) bits |= 64;
        if (y > threshold) bits |= 128;
        return bits;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (!isControllerEvent(event) || event.getActionMasked() != android.view.MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        float x = padAxis(event, android.view.MotionEvent.AXIS_X);
        float y = padAxis(event, android.view.MotionEvent.AXIS_Y);
        int hat = directionsFor(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), 0.5f);
        int stick = 0;
        if (isSonyPad() || isN64() || isDreamcast()) {
            // PSP / PS1 / PS2 / N64 / Dreamcast read the sticks as sticks.
            onAnalogChanged(x, y);
            onAnalogRightChanged(padAxis(event, android.view.MotionEvent.AXIS_Z), padAxis(event, android.view.MotionEvent.AXIS_RZ));
        } else {
            stick = directionsFor(x, y, 0.5f);
        }
        if (hat != this.padHatDirection || stick != this.padStickDirection) {
            this.padHatDirection = hat;
            this.padStickDirection = stick;
            applyDirections();
        }
        // Analog triggers (pads that report L2/R2 only as axes).
        float l2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
        float r2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
        setTrigger(kL2, l2 > 0.5f);
        setTrigger(kR2, r2 > 0.5f);
        return true;
    }

    private int padTriggers;

    private void setTrigger(int bit, boolean pressed) {
        if (((this.padTriggers & bit) != 0) == pressed) {
            return;
        }
        this.padTriggers = pressed ? this.padTriggers | bit : this.padTriggers & ~bit;
        if (!pressed && (this.padButtons & bit) != 0) {
            return;
        }
        setButtonPressed(bit, pressed);
    }

    private void addLandscapeActionButton(FrameLayout frameLayout, String str, int i, int i2, int i3) {
        addLandscapeActionButton(frameLayout, str, i, i2, i3, 3);
    }

    private void addLandscapeActionButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4) {
        TouchButton touchButton = new TouchButton(this, str, i, this::setButtonPressed, i4);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(66), dp(66));
        layoutParams.gravity = 8388629;
        layoutParams.setMargins(0, 0, i2, 0);
        touchButton.setTranslationY(i3 + dp(LANDSCAPE_SHIFT_DP));
        frameLayout.addView(touchButton, layoutParams);
    }

    // How many touches (a button, a combo zone, a controller key) hold each button bit, so
    // letting go of one does not release a button another still holds.
    private final int[] buttonHolds = new int[32];

    private void setButtonPressed(int i, boolean z) {
        int held = 0;
        for (int b = 0; b < 32; b++) {
            if ((i & (1 << b)) != 0) {
                this.buttonHolds[b] = Math.max(0, this.buttonHolds[b] + (z ? 1 : -1));
            }
            if (this.buttonHolds[b] > 0) {
                held |= 1 << b;
            }
        }
        // The D-pad bits come from applyDirections.
        this.buttonMask = (this.buttonMask & 0xF0) | (held & ~0xF0);
        updateInput();
    }

    // ---- 버튼 사이 동시 입력: zones between neighbouring face buttons press both ----
    private static final String[] COMBO_NAMES = {"꺼짐", "좁게", "넓게"};
    private static final String[] COMBO_NOTES = {"손가락 하나 = 버튼 하나 (기본)", "버튼 사이 빈틈을 누르면 두 버튼 같이", "버튼 가장자리만 눌러도 같이 · 격투 게임용"};
    // Face buttons: A, B, X/C, Y/D, E, F.
    private static final int FACE_BITS = 1 | 2 | 1024 | 2048 | 4096 | 8192;
    private final java.util.List<android.view.ViewGroup> comboParents = new java.util.ArrayList<>();
    // The face-button positions each parent's zones were laid out for.
    private final java.util.Map<android.view.ViewGroup, String> comboSignatures = new java.util.HashMap<>();

    private int comboLevel() {
        int level = getSharedPreferences("settings", 0).getInt("combo_level", 0);
        return level >= 0 && level <= 2 ? level : 0;
    }

    // Finds the face buttons of the content just built and keeps zones between them in place.
    private void installComboZones(View root) {
        this.comboParents.clear();
        this.comboSignatures.clear();
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (view instanceof TouchButton && (((TouchButton) view).bit() & FACE_BITS) != 0
                    && view.getParent() instanceof FrameLayout && !this.comboParents.contains((android.view.ViewGroup) view.getParent())) {
                this.comboParents.add((android.view.ViewGroup) view.getParent());
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int k = 0; k < group.getChildCount(); k++) {
                    queue.add(group.getChildAt(k));
                }
            }
        }
        for (android.view.ViewGroup parent : this.comboParents) {
            parent.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> parent.post(() -> layoutComboZones(parent, false)));
        }
    }

    private void layoutComboZones(android.view.ViewGroup parent, boolean force) {
        java.util.List<TouchButton> buttons = new java.util.ArrayList<>();
        java.util.List<View> zones = new java.util.ArrayList<>();
        StringBuilder signature = new StringBuilder().append(comboLevel());
        for (int k = 0; k < parent.getChildCount(); k++) {
            View child = parent.getChildAt(k);
            if (child instanceof ComboZoneView) {
                zones.add(child);
            } else if (child instanceof TouchButton && (((TouchButton) child).bit() & FACE_BITS) != 0 && child.getWidth() > 0) {
                buttons.add((TouchButton) child);
                signature.append(',').append(child.getLeft() + (int) child.getTranslationX()).append(':').append(child.getTop() + (int) child.getTranslationY());
            }
        }
        // Adding the zones lays the parent out again; leave them be when nothing moved.
        if (!force && signature.toString().equals(this.comboSignatures.get(parent))) {
            return;
        }
        this.comboSignatures.put(parent, signature.toString());
        for (View zone : zones) {
            parent.removeView(zone);
        }
        int level = comboLevel();
        if (level == 0) {
            return;
        }
        for (int i = 0; i < buttons.size(); i++) {
            for (int j = i + 1; j < buttons.size(); j++) {
                TouchButton a = buttons.get(i);
                TouchButton b = buttons.get(j);
                float ax = a.getLeft() + a.getTranslationX() + a.getWidth() / 2.0f;
                float ay = a.getTop() + a.getTranslationY() + a.getHeight() / 2.0f;
                float bx = b.getLeft() + b.getTranslationX() + b.getWidth() / 2.0f;
                float by = b.getTop() + b.getTranslationY() + b.getHeight() / 2.0f;
                float size = Math.max(Math.max(a.getWidth(), a.getHeight()), Math.max(b.getWidth(), b.getHeight()));
                // Neighbours only: a diamond's sides, not its opposite corners.
                if (Math.hypot(ax - bx, ay - by) > size * 1.6f) {
                    continue;
                }
                int zoneSize = Math.round(size * (level == 1 ? 0.5f : 0.95f));
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(zoneSize, zoneSize, Gravity.TOP | Gravity.START);
                params.leftMargin = Math.round((ax + bx) / 2 - zoneSize / 2.0f);
                params.topMargin = Math.round((ay + by) / 2 - zoneSize / 2.0f);
                parent.addView(new ComboZoneView(this, a, b, this::setButtonPressed), params);
            }
        }
    }

    private LinearLayout createComboRow(Runnable onChanged) {
        return createSettingRow(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "버튼 사이 동시 입력", COMBO_NAMES[comboLevel()],
                view -> showChoiceDialog(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "버튼 사이 동시 입력", COMBO_NAMES, COMBO_NOTES,
                        comboLevel(), "모든 게임에 바로 적용돼요.\n손가락 두 개로 각각 누르는 건 설정과 상관없이 항상 돼요.",
                        Color.rgb(255, 247, 232), Color.rgb(138, 90, 0), "적용", choice -> {
                    getSharedPreferences("settings", 0).edit().putInt("combo_level", choice).apply();
                    for (android.view.ViewGroup parent : this.comboParents) {
                        layoutComboZones(parent, true);
                    }
                    onChanged.run();
                }));
    }


    // ---- 되감기 (rewind) ----
    private static final int REWIND_BESIDE = 0;
    private static final int REWIND_LEFT = 1;
    private static final int REWIND_BELOW = 2;
    private static final int REWIND_MIRROR = 3;
    private static final String[] REWIND_NAMES = {"꺼짐", "켜짐"};
    private static final String[] REWIND_NOTES = {"되감기 버튼 없음 (기본)", "버튼을 누르고 있는 동안 거꾸로"};
    private TextView rewindBadge;
    private final Runnable rewindBadgeUpdate = new Runnable() {
        @Override
        public void run() {
            updateRewindBadge();
        }
    };
    private boolean rewindHeld;

    // PS2 and Dreamcast states are tens of MB; MAME 0.139 has no states here at all.
    private boolean rewindSupported() {
        return !isPs2() && !isDreamcast() && !isSaturn() && !isMame139();
    }

    private boolean rewindOn() {
        return rewindSupported() && getSharedPreferences("settings", 0).getBoolean("rewind_enabled", false);
    }

    // Puts the rewind button by the speed button: centred as a pair (BESIDE), to its left in
    // the same row (LEFT, optionally narrowing the speed button), centred under it (BELOW), or
    // at the mirrored spot on the other side (MIRROR, for a bottom-right speed button).
    private void addRewindButton(FrameLayout parent, View speed, FrameLayout.LayoutParams speedParams, int placement, int widthDp, boolean narrowSpeed) {
        if (!rewindOn()) {
            return;
        }
        RewindButtonView rewind = new RewindButtonView(this, this::onRewindHeld);
        int width = dp(widthDp);
        int gap = dp(6);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, speedParams.height, speedParams.gravity);
        if (placement == REWIND_BESIDE) {
            params.setMargins(speedParams.leftMargin, speedParams.topMargin, speedParams.rightMargin, speedParams.bottomMargin);
            speed.setTranslationX(speed.getTranslationX() - (width + gap) / 2.0f);
            rewind.setTranslationX((speedParams.width + gap) / 2.0f);
        } else if (placement == REWIND_LEFT) {
            if (narrowSpeed) {
                speedParams.width = width;
                speed.setLayoutParams(speedParams);
            }
            params.setMargins(0, speedParams.topMargin, speedParams.rightMargin + speedParams.width + gap, speedParams.bottomMargin);
        } else if (placement == REWIND_MIRROR) {
            params.gravity = Gravity.BOTTOM | Gravity.START;
            params.setMargins(speedParams.rightMargin, 0, 0, speedParams.bottomMargin);
        } else {
            params.setMargins(speedParams.leftMargin, speedParams.topMargin + speedParams.height + dp(8), speedParams.rightMargin, 0);
        }
        parent.addView(rewind, params);
    }

    private void onRewindHeld(boolean held) {
        if (this.rewindHeld == held) {
            return;
        }
        this.rewindHeld = held;
        long handle = this.nativeHandle;
        if (handle != 0) {
            NativeGba.setRewinding(handle, held);
        }
        updateRewindBadge();
    }

    // "◀◀ 되감는 중 · 3초 전" over the top of the screen while the button is held.
    private void updateRewindBadge() {
        TextView badge = this.rewindBadge;
        if (!this.rewindHeld) {
            if (badge != null) {
                badge.removeCallbacks(this.rewindBadgeUpdate);
                badge.setVisibility(View.GONE);
            }
            return;
        }
        if (badge == null || !badge.isAttachedToWindow()) {
            badge = new TextView(this);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(13.0f);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(14), dp(7), dp(14), dp(7));
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(Color.argb(220, 76, 29, 149));
            background.setCornerRadius(dp(16));
            badge.setBackground(background);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.setMargins(0, dp(this.landscapeMode ? 12 : 72), 0, 0);
            addContentView(badge, params);
            this.rewindBadge = badge;
        }
        long handle = this.nativeHandle;
        int seconds = handle == 0 ? 0 : Math.round(NativeGba.rewoundSeconds(handle));
        badge.setText(seconds > 0 ? "◀◀  되감는 중 · " + seconds + "초 전" : "◀◀  되감는 중");
        badge.setVisibility(View.VISIBLE);
        badge.removeCallbacks(this.rewindBadgeUpdate);
        badge.postDelayed(this.rewindBadgeUpdate, 250);
    }

    // Builds the screen again (a setting that adds or removes buttons changed).
    private void rebuildContent() {
        releaseController();
        onRewindHeld(false);
        if (this.landscapeMode) {
            createLandscapeContent();
        } else {
            createPortraitContent();
        }
    }

    // ---- 버튼 배치 편집 (layout editor) ----
    private final java.util.List<View> layoutControls = new java.util.ArrayList<>();
    private final java.util.List<String> layoutIds = new java.util.ArrayList<>();
    private final java.util.List<float[]> layoutBase = new java.util.ArrayList<>();
    private LayoutEditorView layoutEditor;

    private static boolean isLayoutControl(View view) {
        return view instanceof TouchButton || view instanceof DPadView || view instanceof AnalogStickView
                || view instanceof ArcadeJoystickView || view instanceof SpeedButtonView || view instanceof RewindButtonView;
    }

    // Saved per console, screen direction and screen style.
    private String layoutKey() {
        String console = isGameGear() ? "gg" : isMasterSystem() ? "sms" : isGameBoy() ? "gb" : this.coreId;
        return "layout:" + console + ":" + (this.landscapeMode ? "L" : "P") + ":" + (isFullScreenStyle() ? "full" : "frame");
    }

    private java.util.Map<String, float[]> loadLayoutEdits() {
        java.util.Map<String, float[]> map = new java.util.HashMap<>();
        String saved = getSharedPreferences("settings", 0).getString(layoutKey(), "");
        for (String entry : saved.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] values = entry.substring(eq + 1).split(",");
            if (values.length != 3) {
                continue;
            }
            try {
                map.put(entry.substring(0, eq), new float[]{Float.parseFloat(values[0]), Float.parseFloat(values[1]), Float.parseFloat(values[2])});
            } catch (NumberFormatException ignored) {
                // A damaged entry leaves that control where it was.
            }
        }
        return map;
    }

    private boolean hasLayoutEdits() {
        return !loadLayoutEdits().isEmpty();
    }

    // Finds the controls just built, names them (kind, button bit, n-th of the same) so a saved
    // layout still fits when another button comes or goes, and applies the saved moves.
    private void applyLayoutEdits(View root) {
        this.layoutControls.clear();
        this.layoutIds.clear();
        this.layoutBase.clear();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (isLayoutControl(view)) {
                String kind = view.getClass().getSimpleName() + (view instanceof TouchButton ? "." + ((TouchButton) view).bit() : "");
                int count = seen.containsKey(kind) ? seen.get(kind) : 0;
                seen.put(kind, count + 1);
                this.layoutControls.add(view);
                this.layoutIds.add(kind + "#" + count);
                this.layoutBase.add(new float[]{view.getTranslationX(), view.getTranslationY()});
            } else if (view instanceof android.view.ViewGroup) {
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
                applyLayoutEdit(k, edit[0], edit[1], edit[2]);
            }
        }
    }

    private void applyLayoutEdit(int index, float dxDp, float dyDp, float scale) {
        View view = this.layoutControls.get(index);
        float[] base = this.layoutBase.get(index);
        float density = getResources().getDisplayMetrics().density;
        view.setTranslationX(base[0] + dxDp * density);
        view.setTranslationY(base[1] + dyDp * density);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    private void startLayoutEditor() {
        if (this.layoutControls.isEmpty() || this.layoutEditor != null) {
            return;
        }
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            this.pauseDialog.dismiss();
        }
        java.util.Map<String, float[]> saved = loadLayoutEdits();
        float[][] edits = new float[this.layoutControls.size()][];
        for (int k = 0; k < edits.length; k++) {
            float[] edit = saved.get(this.layoutIds.get(k));
            edits[k] = edit != null ? edit.clone() : new float[]{0, 0, 1};
        }
        LayoutEditorView editor = new LayoutEditorView(this, new java.util.ArrayList<>(this.layoutControls), edits, new LayoutEditorView.Listener() {
            @Override
            public void apply(int index, float dxDp, float dyDp, float scale) {
                applyLayoutEdit(index, dxDp, dyDp, scale);
            }

            @Override
            public void onSave(float[][] result) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < result.length; k++) {
                    float[] edit = result[k];
                    if (Math.abs(edit[0]) < 0.5f && Math.abs(edit[1]) < 0.5f && Math.abs(edit[2] - 1) < 0.01f) {
                        continue;
                    }
                    sb.append(layoutIds.get(k)).append('=').append(Math.round(edit[0])).append(',')
                            .append(Math.round(edit[1])).append(',').append(Math.round(edit[2] * 100) / 100.0f).append(';');
                }
                getSharedPreferences("settings", 0).edit().putString(layoutKey(), sb.toString()).apply();
                finishLayoutEditor("버튼 배치를 저장했어요.");
            }

            @Override
            public void onCancel() {
                finishLayoutEditor(null);
            }
        });
        addContentView(editor, new FrameLayout.LayoutParams(-1, -1));
        this.layoutEditor = editor;
    }

    private void finishLayoutEditor(String message) {
        LayoutEditorView editor = this.layoutEditor;
        this.layoutEditor = null;
        if (editor != null && editor.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) editor.getParent()).removeView(editor);
        }
        for (android.view.ViewGroup parent : this.comboParents) {
            layoutComboZones(parent, true);
        }
        if (message != null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
        this.holdPause = false;
        resumeUnlessExiting();
    }


    // ---- 치트 코드 ----
    private static final int REQUEST_CHEAT_FILE = 3101;
    private static final int CHEAT_RED = Color.rgb(225, 29, 72);
    private static final int CHEAT_GREEN = Color.rgb(5, 150, 105);
    private java.util.List<Cheats.Cheat> cheatList;
    private android.app.Dialog cheatDialog;
    private TextView cheatSubtitle;
    private TextView cheatRowValue;
    private boolean cheatSearching;
    private String cheatError;

    private boolean cheatsSupported() {
        return !isMame139() && this.romPath != null && Cheats.supported(this.gameMode);
    }

    private File romFile() {
        return new File(this.romPath);
    }

    private java.util.List<Cheats.Cheat> cheats() {
        if (this.cheatList == null) {
            this.cheatList = cheatsSupported() ? Cheats.load(this, romFile()) : new java.util.ArrayList<>();
        }
        return this.cheatList;
    }

    private String cheatSummary() {
        if (!cheatsSupported()) {
            return "지원 안 함";
        }
        int on = Cheats.enabledCodes(cheats()).length;
        return on == 0 ? "꺼짐" : on + "개 켜짐";
    }

    // Saves the list and hands the codes that are on to the engine.
    private void cheatsChanged() {
        Cheats.save(this, romFile(), cheats());
        applyCheats();
        if (this.cheatSubtitle != null) {
            this.cheatSubtitle.setText(cheatSubtitleText());
        }
        if (this.cheatRowValue != null) {
            this.cheatRowValue.setText(cheatSummary() + "  ›");
        }
    }

    private String cheatSubtitleText() {
        int on = Cheats.enabledCodes(cheats()).length;
        return this.gameTitle + (on > 0 ? " · 켜진 것 " + on + "개" : "");
    }

    private void applyCheats() {
        final long handle = this.nativeHandle;
        if (handle == 0 || !cheatsSupported()) {
            return;
        }
        final String[] codes = Cheats.enabledCodes(cheats());
        // Nothing was ever set for this game: leave the engine alone.
        if (codes.length == 0 && !Cheats.file(this, romFile()).exists()) {
            return;
        }
        coreWorker.execute(() -> NativeGba.setCheats(handle, codes));
    }

    // How codes look on this console; shown under the code box.
    private String cheatFormatNote() {
        switch (this.gameMode) {
            case "gba": return isGameBoy() ? "게임보이: 게임지니 · 게임샤크 (예: 01FF16D0)" : "GBA: 게임샤크 · 액션리플레이 · 코드브레이커 (예: 82025D7C 423F)";
            case "snes": return "슈퍼패미컴: 게임지니 · 프로액션리플레이 (예: 7E0DBE09)";
            case "nes": return "패미컴: 게임지니 (예: SXIOPO)";
            case "md": return "메가드라이브: 게임지니 · 프로액션리플레이 (예: FF0FC4:0009)";
            case "sms": return "마스터시스템·게임기어: 게임지니 · 프로액션리플레이";
            case "mcd": return "메가CD·32X: 게임지니 · 프로액션리플레이 (예: FF0FC4:0009)";
            case "ss": return "새턴: 액션리플레이 (예: 1606BB4C 0063)";
            case "pce": return "PC엔진: 주소:값 형식";
            case "nds": return "DS: 액션리플레이 (예: 221D2B0C 000000FF)";
            case "n64": return "N64: 게임샤크 (예: 8033B21E 0008)";
            case "ps1": return "PS1: 게임샤크 (예: 800B1234 FFFF)";
            case "psp": return "PSP: CWCheat (예: _L 0x20123456 0x0000270F)";
            default: return "";
        }
    }

    private static android.graphics.drawable.GradientDrawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView sheetText(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    // Icon, title and a one-line subtitle (returned through the subtitle holder).
    private LinearLayout sheetHeader(int icon, int color, String title, String subtitle, TextView[] subtitleOut) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        titles.addView(sheetText(title, 17.0f, Color.rgb(31, 41, 51), true));
        TextView sub = sheetText(subtitle, 12.0f, Color.rgb(122, 130, 140), false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(sub);
        if (subtitleOut != null) {
            subtitleOut[0] = sub;
        }
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        return header;
    }

    private EditText searchBox(String hint) {
        EditText box = new EditText(this);
        box.setHint(hint);
        box.setSingleLine(true);
        box.setTextSize(13.5f);
        box.setTextColor(Color.rgb(31, 41, 51));
        box.setHintTextColor(Color.rgb(154, 161, 170));
        box.setPadding(dp(12), 0, dp(12), 0);
        box.setBackground(roundRect(Color.rgb(241, 243, 245), dp(12)));
        return box;
    }

    private void onTextChanged(EditText box, Runnable action) {
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable text) {
                action.run();
            }
        });
    }

    // A white card dialog; heightShare > 0 gives it that share of the screen height.
    private android.app.Dialog cardDialog(View content, float heightShare) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        dialog.setContentView(content);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400));
            window.setLayout(width, heightShare > 0 ? Math.round(getResources().getDisplayMetrics().heightPixels * heightShare) : -2);
        }
        return dialog;
    }

    private LinearLayout.LayoutParams marginTop(int width, int height, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    // The cheat list (or the "not found" page), opened from the pause menu.
    private void showCheatSheet() {
        if (this.cheatDialog != null && this.cheatDialog.isShowing()) {
            this.cheatDialog.dismiss();
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        TextView[] subtitle = new TextView[1];
        panel.addView(sheetHeader(RoundIconView.CHEAT, CHEAT_RED, "치트 코드", cheatSubtitleText(), subtitle));
        this.cheatSubtitle = subtitle[0];
        String source = Cheats.source(this, romFile());
        if (source == null && !this.cheatSearching && this.cheatError == null) {
            startCheatSearch();
        }
        final android.app.Dialog dialog;
        if ("-".equals(source) && cheats().isEmpty() && !this.cheatSearching) {
            fillCheatNotFound(panel);
            dialog = cardDialog(panel, 0);
        } else {
            panel.addView(cheatBanner(source), marginTop(-1, -2, 10));
            EditText search = searchBox("🔍  치트 검색 (이름이 영어예요)");
            panel.addView(search, marginTop(-1, dp(38), 10));
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(1);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.addView(rows);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
            scrollParams.setMargins(0, dp(8), 0, 0);
            panel.addView(scroll, scrollParams);
            Runnable fill = () -> fillCheatRows(rows, search.getText().toString());
            onTextChanged(search, fill);
            fill.run();
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(0);
            TextView add = dialogButton("＋ 직접 입력", Color.rgb(255, 228, 230), Color.rgb(190, 18, 60));
            add.setTextSize(13.5f);
            add.setOnClickListener(view -> showCheatEditor(null));
            buttons.addView(add, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
            TextView file = dialogButton("파일에서 (.cht)", Color.rgb(241, 242, 244), Color.rgb(55, 65, 81));
            file.setTextSize(13.5f);
            file.setOnClickListener(view -> openCheatFile());
            LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
            fileParams.setMargins(dp(8), 0, 0, 0);
            buttons.addView(file, fileParams);
            panel.addView(buttons, marginTop(-1, -2, 10));
            dialog = cardDialog(panel, 0.86f);
        }
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(view -> dialog.dismiss());
        panel.addView(close, marginTop(-1, dp(46), 10));
        dialog.setOnDismissListener(d -> {
            if (this.cheatDialog == dialog) {
                this.cheatDialog = null;
                this.cheatSubtitle = null;
            }
        });
        this.cheatDialog = dialog;
        dialog.show();
    }

    private View cheatBanner(String source) {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(0);
        banner.setGravity(16);
        banner.setPadding(dp(11), dp(9), dp(9), dp(9));
        String title;
        String detail;
        int background;
        int color;
        if (this.cheatSearching) {
            title = "치트 모음에서 찾는 중…";
            detail = "게임 이름으로 찾고 있어요";
            background = Color.rgb(239, 246, 255);
            color = Color.rgb(30, 64, 175);
        } else if (this.cheatError != null && source == null) {
            title = "치트 모음을 받지 못했어요";
            detail = this.cheatError;
            background = Color.rgb(255, 247, 237);
            color = Color.rgb(154, 52, 18);
        } else if (source == null || "-".equals(source)) {
            title = "치트 모음에 없는 게임이에요";
            detail = "다른 판을 고르거나 직접 입력해 주세요";
            background = Color.rgb(246, 247, 249);
            color = Color.rgb(75, 85, 99);
        } else {
            int count = 0;
            for (Cheats.Cheat cheat : cheats()) {
                if (!cheat.custom) {
                    count++;
                }
            }
            title = "치트 모음에서 찾음 · " + count + "개";
            detail = source.substring(source.indexOf('/') + 1);
            background = Color.rgb(236, 253, 245);
            color = Color.rgb(6, 95, 70);
        }
        banner.setBackground(roundRect(background, dp(12)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        texts.addView(sheetText(title, 12.5f, color, true));
        TextView sub = sheetText(detail, 11.5f, color, false);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        banner.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (!this.cheatSearching) {
            final boolean retry = this.cheatError != null && source == null;
            TextView action = sheetText(retry ? "다시 시도" : "다른 판 ›", 12.0f, retry ? color : CHEAT_GREEN, true);
            action.setPadding(dp(10), dp(6), dp(10), dp(6));
            action.setBackground(roundRect(Color.WHITE, dp(10)));
            action.setOnClickListener(view -> {
                if (retry) {
                    this.cheatError = null;
                    showCheatSheet();
                } else {
                    showCheatVersionPicker();
                }
            });
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
            actionParams.setMargins(dp(8), 0, 0, 0);
            banner.addView(action, actionParams);
        }
        return banner;
    }

    private void fillCheatNotFound(LinearLayout panel) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(1);
        box.setGravity(1);
        box.setPadding(dp(14), dp(20), dp(14), dp(20));
        box.setBackground(roundRect(Color.rgb(246, 247, 249), dp(16)));
        box.addView(sheetText("✱", 28.0f, Color.rgb(203, 210, 217), true));
        TextView title = sheetText("치트 모음에서 이 게임을 못 찾았어요", 15.0f, Color.rgb(55, 65, 81), true);
        title.setGravity(1);
        box.addView(title);
        TextView detail = sheetText("한글판·일본판처럼 모음에 없는 판일 수 있어요.\n비슷한 판을 직접 고르거나 코드를 입력해 주세요.", 12.0f, Color.rgb(122, 130, 140), false);
        detail.setGravity(1);
        detail.setPadding(0, dp(6), 0, 0);
        box.addView(detail);
        panel.addView(box, marginTop(-1, -2, 14));
        panel.addView(createSettingRow(RoundIconView.SWAP, CHEAT_GREEN, "다른 판 고르기", "", view -> showCheatVersionPicker()), marginTop(-1, dp(52), 12));
        panel.addView(createSettingRow(RoundIconView.CHEAT, CHEAT_RED, "직접 입력", "", view -> showCheatEditor(null)), marginTop(-1, dp(52), 8));
        panel.addView(createSettingRow(RoundIconView.IMPORT, Color.rgb(75, 85, 99), "파일에서 가져오기 (.cht)", "", view -> openCheatFile()), marginTop(-1, dp(52), 8));
        TextView note = sheetText("다른 판의 코드는 주소가 달라서 안 먹히거나 엉뚱하게 동작할 수 있어요. 켜보고 이상하면 끄면 돼요.", 11.5f, Color.rgb(154, 52, 18), false);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        note.setBackground(roundRect(Color.rgb(255, 247, 237), dp(12)));
        panel.addView(note, marginTop(-1, -2, 10));
    }

    private void fillCheatRows(LinearLayout rows, String query) {
        rows.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        java.util.List<Cheats.Cheat> list = cheats();
        int shown = 0;
        for (final Cheats.Cheat cheat : list) {
            if (!q.isEmpty() && !cheat.desc.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(1);
            LinearLayout nameLine = new LinearLayout(this);
            nameLine.setGravity(16);
            nameLine.addView(sheetText(cheat.desc, 13.5f, Color.rgb(31, 41, 51), true), new LinearLayout.LayoutParams(0, -2, 1.0f));
            boolean master = Cheats.isMaster(cheat);
            if (master || cheat.custom) {
                TextView badge = sheetText(master ? "먼저 켜기" : "직접 입력", 10.5f, master ? Color.rgb(146, 64, 14) : Color.rgb(190, 18, 60), true);
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
                badge.setBackground(roundRect(master ? Color.rgb(253, 230, 138) : Color.rgb(255, 228, 230), dp(8)));
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
                badgeParams.setMargins(dp(6), 0, 0, 0);
                nameLine.addView(badge, badgeParams);
            }
            texts.addView(nameLine);
            TextView code = sheetText(cheat.code.replace("+", " · "), 11.0f, Color.rgb(156, 163, 175), false);
            code.setTypeface(android.graphics.Typeface.MONOSPACE);
            code.setSingleLine(true);
            code.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(code);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
            ToggleView toggle = new ToggleView(this, CHEAT_RED);
            toggle.setOn(cheat.on);
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(44), dp(26));
            toggleParams.setMargins(dp(10), 0, 0, 0);
            row.addView(toggle, toggleParams);
            Runnable paint = () -> {
                toggle.setOn(cheat.on);
                row.setBackground(roundRect(cheat.on ? Color.rgb(255, 241, 242) : Color.rgb(246, 247, 249), dp(13)));
            };
            paint.run();
            row.setOnClickListener(view -> setCheatOn(cheat, !cheat.on, paint));
            row.setOnLongClickListener(view -> {
                showCheatActions(cheat);
                return true;
            });
            rows.addView(row, marginTop(-1, -2, shown == 1 ? 0 : 6));
        }
        if (shown == 0) {
            String text = !list.isEmpty() ? "‘" + query.trim() + "’ 이름의 치트가 없어요."
                    : this.cheatSearching ? "찾는 중…" : "아직 치트가 없어요. 직접 입력하거나 파일에서 가져오세요.";
            TextView empty = sheetText(text, 12.5f, Color.rgb(122, 130, 140), false);
            empty.setGravity(17);
            empty.setPadding(0, dp(24), 0, dp(24));
            rows.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // Turning the first cheat of a game on offers a quick save first (asked once per game).
    private void setCheatOn(Cheats.Cheat cheat, boolean on, Runnable done) {
        if (on && !Cheats.warned(this, romFile())) {
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(1);
            panel.setPadding(dp(16), dp(18), dp(16), dp(14));
            panel.setBackground(roundRect(Color.WHITE, dp(22)));
            panel.addView(sheetText("치트를 켜기 전에 저장할까요?", 16.0f, Color.rgb(31, 41, 51), true));
            TextView body = sheetText("치트는 게임 데이터를 직접 바꿔서 세이브가 망가질 수 있어요. 지금 상태를 빠른 저장 칸에 남겨두면 언제든 돌아올 수 있어요.", 12.5f, Color.rgb(75, 85, 99), false);
            body.setLineSpacing(0, 1.2f);
            panel.addView(body, marginTop(-1, -2, 8));
            panel.addView(sheetText("게임마다 처음 한 번만 물어봐요.", 11.5f, Color.rgb(156, 163, 175), false), marginTop(-1, -2, 6));
            LinearLayout buttons = new LinearLayout(this);
            TextView plain = dialogButton("그냥 켜기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
            buttons.addView(plain, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
            TextView save = dialogButton("저장하고 켜기", CHEAT_RED, Color.WHITE);
            LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(46), 1.6f);
            saveParams.setMargins(dp(10), 0, 0, 0);
            buttons.addView(save, saveParams);
            panel.addView(buttons, marginTop(-1, -2, 14));
            final android.app.Dialog dialog = cardDialog(panel, 0);
            plain.setOnClickListener(view -> {
                dialog.dismiss();
                Cheats.setWarned(this, romFile());
                setCheatOn(cheat, true, done);
            });
            save.setOnClickListener(view -> {
                dialog.dismiss();
                Cheats.setWarned(this, romFile());
                // Queued on the core worker ahead of the cheat change.
                createQuickSave(false);
                setCheatOn(cheat, true, done);
            });
            dialog.show();
            return;
        }
        cheat.on = on;
        cheatsChanged();
        done.run();
    }

    private void showCheatActions(Cheats.Cheat cheat) {
        new AlertDialog.Builder(this).setTitle(cheat.desc).setItems(new String[]{"고치기", "지우기"}, (d, which) -> {
            if (which == 0) {
                showCheatEditor(cheat);
            } else {
                cheats().remove(cheat);
                cheatsChanged();
                showCheatSheet();
            }
        }).show();
    }

    private int firstCollectionCheat() {
        int at = 0;
        while (at < cheats().size() && cheats().get(at).custom) {
            at++;
        }
        return at;
    }

    // 직접 입력 / 고치기: a name and one code per line.
    private void showCheatEditor(final Cheats.Cheat editing) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        panel.addView(sheetHeader(RoundIconView.CHEAT, CHEAT_RED, editing == null ? "치트 코드 추가" : "치트 코드 고치기", this.gameTitle, null));
        panel.addView(sheetText("이름", 12.5f, Color.rgb(55, 65, 81), true), marginTop(-2, -2, 12));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextSize(14.0f);
        name.setHint("예: 돈 999999");
        name.setPadding(dp(12), dp(10), dp(12), dp(10));
        name.setBackground(roundRect(Color.rgb(246, 247, 249), dp(12)));
        panel.addView(name, marginTop(-1, -2, 6));
        panel.addView(sheetText("코드 (한 줄에 하나, 여러 줄 가능)", 12.5f, Color.rgb(55, 65, 81), true), marginTop(-2, -2, 12));
        EditText code = new EditText(this);
        code.setTextSize(14.0f);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setMinLines(3);
        code.setGravity(Gravity.TOP | Gravity.START);
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setPadding(dp(12), dp(10), dp(12), dp(10));
        code.setBackground(roundRect(Color.rgb(255, 241, 242), dp(12)));
        panel.addView(code, marginTop(-1, -2, 6));
        if (editing != null) {
            name.setText(editing.desc);
            code.setText(Cheats.codeForInput(editing.code));
        }
        TextView note = sheetText(cheatFormatNote() + "\n형식은 엔진이 알아서 구분해요.", 11.5f, Color.rgb(75, 85, 99), false);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        note.setBackground(roundRect(Color.rgb(246, 247, 249), dp(12)));
        panel.addView(note, marginTop(-1, -2, 10));
        LinearLayout buttons = new LinearLayout(this);
        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        TextView ok = dialogButton(editing == null ? "추가" : "저장", CHEAT_RED, Color.WHITE);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(0, dp(46), 1.6f);
        okParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(ok, okParams);
        panel.addView(buttons, marginTop(-1, -2, 14));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        final android.app.Dialog dialog = cardDialog(scroll, 0);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        cancel.setOnClickListener(view -> dialog.dismiss());
        ok.setOnClickListener(view -> {
            String codeText = Cheats.codeFromInput(code.getText().toString());
            if (codeText.isEmpty()) {
                Toast.makeText(this, "코드를 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            String nameText = name.getText().toString().trim();
            dialog.dismiss();
            if (editing != null) {
                if (!nameText.isEmpty()) {
                    editing.desc = nameText;
                }
                editing.code = codeText;
                cheatsChanged();
                showCheatSheet();
                return;
            }
            // The player's own codes go first, and a new one starts on.
            Cheats.Cheat cheat = new Cheats.Cheat(nameText.isEmpty() ? "내 치트 " + (firstCollectionCheat() + 1) : nameText, codeText, false, true);
            cheats().add(firstCollectionCheat(), cheat);
            Cheats.save(this, romFile(), cheats());
            setCheatOn(cheat, true, this::showCheatSheet);
        });
        dialog.show();
    }

    private void openCheatFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_CHEAT_FILE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "파일을 고를 앱이 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    // A .cht file the player picked: its cheats join the player's own (off).
    private void importCheatFile(android.net.Uri uri) {
        java.util.List<Cheats.Cheat> found;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("no stream");
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < 4 * 1024 * 1024) {
                out.write(buffer, 0, read);
            }
            found = Cheats.parse(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            found = new java.util.ArrayList<>();
        }
        if (found.isEmpty()) {
            Toast.makeText(this, "이 파일에서 치트를 찾지 못했어요. (.cht 파일인지 확인해 주세요)", Toast.LENGTH_LONG).show();
            return;
        }
        for (Cheats.Cheat cheat : found) {
            cheat.on = false;
            cheat.custom = true;
        }
        cheats().addAll(firstCollectionCheat(), found);
        cheatsChanged();
        Toast.makeText(this, "치트 " + found.size() + "개를 가져왔어요.", Toast.LENGTH_SHORT).show();
        showCheatSheet();
    }

    // Looks the game up in the collection and downloads its cheats (once per game).
    private void startCheatSearch() {
        if (this.cheatSearching) {
            return;
        }
        this.cheatSearching = true;
        final File rom = romFile();
        final String mode = this.gameMode;
        final String title = this.gameTitle;
        new Thread(() -> {
            String error = null;
            String source = null;
            java.util.List<Cheats.Cheat> fresh = null;
            try {
                Cheats.Source match = Cheats.match(this, rom, mode, title);
                if (match == null) {
                    source = "-";
                } else {
                    fresh = Cheats.download(match);
                    source = match.folder + "/" + match.name;
                }
            } catch (IOException e) {
                error = "인터넷 연결을 확인해 주세요.";
            }
            final String doneSource = source;
            final String doneError = error;
            final java.util.List<Cheats.Cheat> doneCheats = fresh;
            runOnUiThread(() -> {
                this.cheatSearching = false;
                this.cheatError = doneError;
                if (doneSource != null) {
                    if (doneCheats != null) {
                        this.cheatList = Cheats.merge(cheats(), doneCheats);
                        Cheats.save(this, rom, this.cheatList);
                    }
                    Cheats.setSource(this, rom, doneSource);
                }
                if (this.cheatDialog != null && this.cheatDialog.isShowing()) {
                    showCheatSheet();
                }
            });
        }, "cheat-search").start();
    }

    // 다른 판 고르기: every collection file of this console, searchable.
    private void showCheatVersionPicker() {
        final java.util.List<Cheats.Source> all = new java.util.ArrayList<>();
        for (String folder : Cheats.folders(this.gameMode, romFile().getName().toLowerCase(Locale.US))) {
            all.addAll(Cheats.sources(this, folder));
        }
        String current = Cheats.source(this, romFile());
        final Cheats.Source[] selected = {null};
        for (Cheats.Source source : all) {
            if ((source.folder + "/" + source.name).equals(current)) {
                selected[0] = source;
            }
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        panel.addView(sheetHeader(RoundIconView.SWAP, CHEAT_GREEN, "다른 판 고르기", "치트 모음 " + all.size() + "개 중에서", null));
        EditText search = searchBox("🔍  게임 이름 (영어)");
        // Starts from the first word of the game's (or the chosen file's) English title.
        String guess = CoverMatcher.title(selected[0] != null ? selected[0].name : romFile().getName().replaceFirst("\\.[^.]+$", ""));
        if (guess.matches("[a-z0-9 ]+")) {
            search.setText(guess.split(" ")[0]);
        }
        panel.addView(search, marginTop(-1, dp(38), 10));
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(rows);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        scrollParams.setMargins(0, dp(8), 0, 0);
        panel.addView(scroll, scrollParams);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            rows.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (final Cheats.Source source : all) {
                if (!q.isEmpty() && !source.name.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                if (++shown > 150) {
                    rows.addView(sheetText("… 더 있어요. 이름을 더 입력해 주세요.", 11.5f, Color.rgb(156, 163, 175), false), marginTop(-1, -2, 8));
                    break;
                }
                boolean on = source == selected[0];
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(0);
                row.setGravity(16);
                row.setPadding(dp(12), dp(9), dp(12), dp(9));
                row.setBackground(roundRect(on ? Color.rgb(236, 253, 245) : Color.rgb(246, 247, 249), dp(13)));
                View dot = new View(this);
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke(dp(2), on ? CHEAT_GREEN : Color.rgb(201, 206, 214));
                ring.setColor(Color.WHITE);
                dot.setBackground(on ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring, innerDot(CHEAT_GREEN)}) : ring);
                row.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(20)));
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(1);
                texts.addView(sheetText(source.name, 12.5f, Color.rgb(31, 41, 51), true));
                texts.addView(sheetText("치트 " + source.count + "개", 11.0f, Color.rgb(156, 163, 175), false));
                LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
                textsParams.setMargins(dp(10), 0, 0, 0);
                row.addView(texts, textsParams);
                row.setOnClickListener(view -> {
                    selected[0] = source;
                    fill[0].run();
                });
                rows.addView(row, marginTop(-1, -2, shown == 1 ? 0 : 6));
            }
            if (shown == 0) {
                TextView empty = sheetText("찾는 이름이 없어요.", 12.5f, Color.rgb(122, 130, 140), false);
                empty.setGravity(17);
                empty.setPadding(0, dp(24), 0, dp(24));
                rows.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        onTextChanged(search, fill[0]);
        fill[0].run();
        TextView warn = sheetText("판이 다르면 코드가 안 맞을 수 있어요. 직접 입력한 치트는 그대로 남아요.", 11.5f, Color.rgb(154, 52, 18), false);
        warn.setPadding(dp(12), dp(8), dp(12), dp(8));
        warn.setBackground(roundRect(Color.rgb(255, 247, 237), dp(12)));
        panel.addView(warn, marginTop(-1, -2, 10));
        LinearLayout buttons = new LinearLayout(this);
        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        TextView ok = dialogButton("이 판으로", CHEAT_GREEN, Color.WHITE);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(0, dp(46), 1.6f);
        okParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(ok, okParams);
        panel.addView(buttons, marginTop(-1, -2, 10));
        final android.app.Dialog dialog = cardDialog(panel, 0.86f);
        cancel.setOnClickListener(view -> dialog.dismiss());
        ok.setOnClickListener(view -> {
            final Cheats.Source source = selected[0];
            if (source == null) {
                Toast.makeText(this, "판을 하나 골라 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            Toast.makeText(this, "치트를 받는 중…", Toast.LENGTH_SHORT).show();
            final File rom = romFile();
            new Thread(() -> {
                java.util.List<Cheats.Cheat> fresh;
                try {
                    fresh = Cheats.download(source);
                } catch (IOException e) {
                    fresh = null;
                }
                final java.util.List<Cheats.Cheat> done = fresh;
                runOnUiThread(() -> {
                    if (done == null) {
                        Toast.makeText(this, "치트를 받지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    this.cheatList = Cheats.merge(cheats(), done);
                    Cheats.setSource(this, rom, source.folder + "/" + source.name);
                    this.cheatError = null;
                    cheatsChanged();
                    showCheatSheet();
                });
            }, "cheat-download").start();
        });
        dialog.show();
    }

    // ---- 설정: screen filter, button vibration, combo zones (all games) ----
    private static final String[] FILTER_NAMES = {"기본", "선명하게", "스캔라인", "LCD 격자"};
    private static final String[] FILTER_NOTES = {"지금처럼 부드럽게 확대", "도트 경계를 또렷하게", "옛날 TV 느낌 · 가로 줄무늬", "휴대용 게임기 느낌 · 격자"};
    private static final String[] HAPTIC_NAMES = {"꺼짐", "약하게", "보통", "강하게"};
    private static final String[] HAPTIC_NOTES = {"진동 없음 (기본)", "살짝 톡", "또렷하게 톡", "확실하게 툭"};
    private final java.util.List<ScreenFilterView> screenFilters = new java.util.ArrayList<>();
    private final Runnable screenFilterPoll = new Runnable() {
        @Override
        public void run() {
            updateScreenFilters();
        }
    };

    private int screenFilter() {
        int value = getSharedPreferences("settings", 0).getInt("screen_filter", 0);
        return value >= 0 && value <= 3 ? value : 0;
    }

    private int hapticLevel() {
        int value = getSharedPreferences("settings", 0).getInt("haptic_level", 0);
        return value >= 0 && value <= 3 ? value : 0;
    }

    private void addScreenFilter(FrameLayout parent) {
        ScreenFilterView filter = new ScreenFilterView(this, this.surfaceView);
        this.screenFilters.clear();
        this.screenFilters.add(filter);
        parent.addView(filter, new FrameLayout.LayoutParams(-1, -1));
        updateScreenFilters();
    }

    // Lines follow the game's picture size, which can change (menus, resolution switches),
    // so it is checked twice a second while a line filter is on.
    private void updateScreenFilters() {
        int filter = screenFilter();
        long handle = this.nativeHandle;
        int[] size = handle != 0 && filter >= 2 ? NativeGba.frameSize(handle) : null;
        for (ScreenFilterView view : this.screenFilters) {
            view.setFilter(filter);
            if (size != null && size.length == 2) {
                view.setGameSize(size[0], size[1]);
            }
            view.removeCallbacks(this.screenFilterPoll);
            if (filter >= 2 && !this.destroyed) {
                view.postDelayed(this.screenFilterPoll, 500);
            }
        }
    }

    private void showSettingsSheet() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, RoundIconView.GEAR, RoundIconView.COLOR_GEAR), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("게임 설정");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        titles.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("모든 게임 공통 · 바로 적용");
        subtitle.setTextSize(12.0f);
        subtitle.setTextColor(Color.rgb(122, 130, 140));
        titles.addView(subtitle);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // A choice reopens this sheet so the rows show the new values.
        Runnable reopen = () -> {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
            showSettingsSheet();
        };
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(-1, dp(52));
        filterParams.setMargins(0, dp(14), 0, 0);
        panel.addView(createSettingRow(RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "화면 필터", FILTER_NAMES[screenFilter()],
                view -> showChoiceDialog(RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "화면 필터", FILTER_NAMES, FILTER_NOTES,
                        screenFilter(), "모든 게임에 바로 적용돼요.\nPSP·PS2·N64·드림캐스트 같은 3D 게임은 '기본'이 가장 깔끔해요.",
                        Color.rgb(232, 246, 255), Color.rgb(3, 105, 161), "적용", choice -> {
                    getSharedPreferences("settings", 0).edit().putInt("screen_filter", choice).apply();
                    NativeGba.setSharpScaling(choice != 0);
                    updateScreenFilters();
                    reopen.run();
                })), filterParams);
        if (!isPadScreen()) {
            LinearLayout.LayoutParams hapticParams = new LinearLayout.LayoutParams(-1, dp(52));
            hapticParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", HAPTIC_NAMES[hapticLevel()],
                    view -> showChoiceDialog(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", HAPTIC_NAMES, HAPTIC_NOTES,
                            hapticLevel(), "화면 버튼을 누를 때 짧게 진동해요.\n휴대폰이 무음·진동 끔이어도 울릴 수 있어요.",
                            Color.rgb(243, 240, 255), Color.rgb(91, 33, 182), "적용", choice -> {
                        getSharedPreferences("settings", 0).edit().putInt("haptic_level", choice).apply();
                        Haptics.setLevel(this, choice);
                        Haptics.tap();
                        reopen.run();
                    })), hapticParams);
            LinearLayout.LayoutParams comboParams = new LinearLayout.LayoutParams(-1, dp(52));
            comboParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createComboRow(reopen), comboParams);
            LinearLayout.LayoutParams rewindParams = new LinearLayout.LayoutParams(-1, dp(52));
            rewindParams.setMargins(0, dp(8), 0, 0);
            boolean supported = rewindSupported();
            panel.addView(createSettingRow(RoundIconView.REWIND, RoundIconView.COLOR_REWIND, "되감기",
                    !supported ? "이 기종은 안 돼요" : rewindOn() ? "켜짐" : "꺼짐",
                    view -> {
                        if (!supported) {
                            Toast.makeText(this, "PS2·새턴·드림캐스트는 상태 저장이 크고 무거워서 되감기를 쓸 수 없어요.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        showChoiceDialog(RoundIconView.REWIND, RoundIconView.COLOR_REWIND, "되감기", REWIND_NAMES, REWIND_NOTES,
                                rewindOn() ? 1 : 0, "켜면 배속 버튼 옆에 되감기 버튼이 생겨요.\n누르고 있는 동안 최대 약 10초 전까지 거꾸로 가요. 휴대폰 메모리를 조금 더 써요.",
                                Color.rgb(243, 240, 255), Color.rgb(91, 33, 182), "적용", choice -> {
                            getSharedPreferences("settings", 0).edit().putBoolean("rewind_enabled", choice == 1).apply();
                            long handle = this.nativeHandle;
                            if (handle != 0) {
                                NativeGba.setRewindEnabled(handle, rewindOn());
                            }
                            rebuildContent();
                            reopen.run();
                        });
                    }), rewindParams);
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(-1, dp(52));
            editParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.MOVE, RoundIconView.COLOR_MOVE, "버튼 배치 편집",
                    hasLayoutEdits() ? "바꾼 배치" : "기본 배치", view -> {
                        dialog.dismiss();
                        startLayoutEditor();
                    }), editParams);
        }

        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(14), 0, 0);
        panel.addView(close, closeParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    private void addLandscapeArcadeButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int size, int i4) {
        TouchButton touchButton = new TouchButton(this, str, i, this::setButtonPressed, i4);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(size), dp(size));
        layoutParams.gravity = 8388629;
        layoutParams.setMargins(0, 0, dp(i2), 0);
        touchButton.setTranslationY(dp(i3 + LANDSCAPE_SHIFT_DP));
        frameLayout.addView(touchButton, layoutParams);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isMame139()) {
            Emulator.pause();
            return;
        }
        this.activityResumed = false;
        onRewindHeld(false);
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, true);
            if (!isPs2()) {
                // The picture for the automatic save the game writes on the way out.
                captureThumbnail(autoThumbnail());
            }
            callCore(NativeGba::saveNow, null, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.activityResumed = true;
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            // Back from a file picker opened over the pause menu: stay paused under it.
            boolean menuOpen = this.holdPause || (this.pauseDialog != null && this.pauseDialog.isShowing());
            NativeGba.pause(j, menuOpen);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHEAT_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importCheatFile(data.getData());
        }
    }

    @Override
    protected void onDestroy() {
        this.destroyed = true;
        final boolean finishing = isFinishing();
        if (isMame139() && finishing) {
            Emulator.stop();
        }
        final long handle = this.nativeHandle;
        this.nativeHandle = 0L;
        final AudioSink sink = this.audioSink;
        // Stopping saves the game and waits for the engine's current frame, so it runs on
        // the worker after any queued saves. Games run in their own process (see the
        // manifest), which ends here so the next game starts with a clean engine.
        coreWorker.execute(() -> {
            if (handle != 0) {
                NativeGba.destroy(handle);
            }
            if (sink != null) {
                sink.release();
            }
            if (finishing) {
                getSharedPreferences("settings", 0).edit().putBoolean(SESSION_CLOSED, true).commit();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        coreWorker.shutdown();
        if (finishing) {
            // An engine stuck inside a frame never returns; leave anyway.
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    // Exit now.
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }, "exit-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
        super.onDestroy();
    }

    private interface CoreCall {
        boolean run(long handle);
    }

    // Runs an engine call on the worker and shows the matching message when it is done.
    private void callCore(CoreCall call, String okMessage, String failMessage) {
        final long handle = this.nativeHandle;
        if (handle == 0 || this.destroyed) {
            return;
        }
        coreWorker.execute(() -> {
            String message = call.run(handle) ? okMessage : failMessage;
            if (message != null) {
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, message, 0).show();
                    }
                });
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (this.layoutEditor != null) {
            // Back leaves the editor without saving: the screen is built again as saved.
            finishLayoutEditor(null);
            rebuildContent();
            return;
        }
        showBackExitDialog();
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        this.surfaceReady = true;
        this.activeSurfaceHolder = surfaceHolder;
        if (isMame139()) {
            Emulator.attachSurface(surfaceHolder);
            startCore(surfaceHolder.getSurface());
            return;
        }
        long j = this.nativeHandle;
        if (j == 0) {
            startCore(surfaceHolder.getSurface());
        } else {
            NativeGba.setSurface(j, surfaceHolder.getSurface());
            // Rebuilt under the pause menu (a setting changed): stay paused until it closes.
            boolean menuOpen = this.holdPause || (this.pauseDialog != null && this.pauseDialog.isShowing());
            NativeGba.pause(this.nativeHandle, menuOpen);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
        if (this.surfaceReady) {
            return;
        }
        startCore(surfaceHolder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        if (this.activeSurfaceHolder == surfaceHolder) {
            this.surfaceReady = false;
            this.activeSurfaceHolder = null;
            if (isMame139()) {
                Emulator.detachSurface(surfaceHolder);
                return;
            }
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.clearSurface(j);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        this.landscapeMode = configuration.orientation == 2;
        // The controls are rebuilt; whatever the old ones held is let go.
        releaseController();
        onRewindHeld(false);
        if (this.layoutEditor != null) {
            finishLayoutEditor(null);
        }
        if (isMame139()) {
            Emulator.pause();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.pause(j, true);
            }
        }
        if (this.landscapeMode) {
            createLandscapeContent();
        } else {
            createPortraitContent();
        }
    }

    private void startCore(Surface surface) {
        if ((isMame139() || (this.nativeHandle == 0 && !this.coreStarting)) && surface.isValid()) {
            if (isMame139()) {
                try {
                    Emulator.start(this, this.activeSurfaceHolder, this.romPath);
                    Emulator.setMiniInput(this.buttonMask);
                    return;
                } catch (IOException e) {
                    showFatalError(e.getMessage());
                    return;
                } catch (UnsatisfiedLinkError e2) {
                    showFatalError("MAME4droid 0.139 엔진 로드 실패: " + e2.getMessage());
                    return;
                }
            }
            String arcadePreflightError = arcadePreflightError();
            if (arcadePreflightError != null) {
                showFatalError(arcadePreflightError);
                return;
            }
            File file = new File(new File(getFilesDir(), "saves"), this.coreId);
            if (!file.exists() && !file.mkdirs()) {
                showFatalError("ROM 실행 실패: 세이브 폴더를 만들 수 없습니다");
                return;
            }
            File file2 = new File(getFilesDir(), "system");
            if (!file2.exists() && !file2.mkdirs()) {
                showFatalError("ROM 실행 실패: 시스템 폴더를 만들 수 없습니다");
                return;
            }
            // Big arcade sets take seconds to load; do it on the worker so the screen stays live.
            this.coreStarting = true;
            final String savePath = file.getAbsolutePath();
            final String systemPath = file2.getAbsolutePath();
            coreWorker.execute(() -> {
                long handle = 0;
                String error = null;
                try {
                    if (isPsp()) {
                        NativeGba.setPspLanguage(0, pspLanguage());
                        NativeGba.setPspScale(pspScale());
                    }
                    if (isWonderSwan()) {
                        NativeGba.setWonderSwanTall(wonderSwanTall());
                    }
                    NativeGba.setSpeedFirst(speedFirstSupported() && speedFirst());
                    // A crash while on Vulkan turns it off for that engine for good (MainActivity).
                    NativeGba.setVulkan(!MainActivity.vulkanOff(getSharedPreferences("settings", 0), this.coreId));
                    handle = NativeGba.create(surface, this.romPath, savePath, systemPath, this.saveBaseName, this.coreId, this.audioSink);
                } catch (RuntimeException e3) {
                    error = e3.getMessage() == null ? "ROM 실행 실패" : e3.getMessage();
                } catch (UnsatisfiedLinkError e4) {
                    error = "코어 로드 실패: " + e4.getMessage();
                }
                if (handle != 0 && this.destroyed) {
                    NativeGba.destroy(handle);
                    return;
                }
                final long started = handle;
                final String failure = error;
                runOnUiThread(() -> onCoreStarted(started, failure, surface));
            });
            getWindow().getDecorView().postDelayed(() -> {
                if (this.coreStarting && !this.destroyed) {
                    Toast.makeText(this, "게임을 불러오는 중입니다…", 0).show();
                }
            }, 800);
        }
    }

    private void onCoreStarted(long handle, String error, Surface startSurface) {
        this.coreStarting = false;
        if (this.destroyed) {
            return;
        }
        if (handle == 0) {
            showFatalError(error);
            return;
        }
        this.nativeHandle = handle;
        NativeGba.setInput(handle, this.buttonMask);
        NativeGba.setSpeedPercent(handle, this.speedPercent);
        NativeGba.setRewindEnabled(handle, rewindOn());
        applyCheats();
        // The surface or the activity state may have changed while the game was loading.
        if (!this.surfaceReady || this.activeSurfaceHolder == null) {
            NativeGba.clearSurface(handle);
        } else if (this.activeSurfaceHolder.getSurface() != startSurface) {
            NativeGba.setSurface(handle, this.activeSurfaceHolder.getSurface());
        }
        if (!this.activityResumed) {
            NativeGba.pause(handle, true);
        }
    }

    private String arcadePreflightError() {
        if (!"mamearcade".equals(this.coreId) || this.romPath == null || !new File(this.romPath).getName().equalsIgnoreCase("twcup98.zip")) {
            return null;
        }
        File file = new File(this.romPath);
        File file2 = new File(file.getParentFile(), "stvbios.zip");
        File file3 = new File(new File(new File(getFilesDir(), "system"), "mame/bios"), "stvbios.zip");
        String[] strArr = {"epr-23603.ic8", "epr-18022.ic2"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            String str = strArr[i];
            if (!zipContains(file, str) && !zipContains(file2, str) && !zipContains(file3, str)) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(str);
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return "테크모 월드컵 98 실행에 필요한 ST-V BIOS가 누락됐습니다.\n누락 파일: " + ((Object) sb) + "\n해당 파일이 포함된 stvbios.zip을 아케이드 ROM 가져오기로 추가하세요.";
    }

    private boolean zipContains(File file, final String str) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (ZipFile zipFile = new ZipFile(file)) {
            if (zipFile.getEntry(str) != null) {
                return true;
            }
            return zipFile.stream().anyMatch(entry -> new File(entry.getName()).getName().equalsIgnoreCase(str));
        } catch (IOException unused) {
            return false;
        }
    }

    // Portrait: the four round buttons with their names above the game screen.
    private LinearLayout createTopBar() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(8), dp(4), dp(8), dp(2));
        linearLayout.addView(createIconButton(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "저장", 40, 11, view -> onSaveClicked()), new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout.addView(createIconButton(RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", 40, 11, view -> onResetClicked()), new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout.addView(createIconButton(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "화면 회전", 40, 11, view -> toggleOrientation()), new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout.addView(createIconButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", 40, 11, view -> showGameMenu()), new LinearLayout.LayoutParams(0, -2, 1.0f));
        return linearLayout;
    }

    // Landscape has no top bar: save/reset sit in the top left corner, rotate/menu in the
    // top right with the speed button under them (GBA, whose shoulder buttons are there,
    // gets the speed button below the screen instead).
    private void addLandscapeCornerButtons(FrameLayout frameLayout) {
        LinearLayout left = new LinearLayout(this);
        left.addView(createIconButton(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "저장", 34, 10, view -> onSaveClicked()), new LinearLayout.LayoutParams(dp(48), -2));
        left.addView(createIconButton(RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", 34, 10, view -> onResetClicked()), new LinearLayout.LayoutParams(dp(48), -2));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        leftParams.setMargins(dp(6), dp(6), 0, 0);
        frameLayout.addView(left, leftParams);

        LinearLayout right = new LinearLayout(this);
        right.addView(createIconButton(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "회전", 34, 10, view -> toggleOrientation()), new LinearLayout.LayoutParams(dp(48), -2));
        right.addView(createIconButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", 34, 10, view -> showGameMenu()), new LinearLayout.LayoutParams(dp(48), -2));
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rightParams.setMargins(0, dp(6), dp(6), 0);
        frameLayout.addView(right, rightParams);

        SpeedButtonView speedButtonView = new SpeedButtonView(this, this::onSpeedChanged);
        this.fastForwardButton = speedButtonView;
        speedButtonView.setPercent(this.speedPercent);
        speedButtonView.setContentDescription("배속");
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(dp(80), dp(36));
        if (isNds()) {
            // The DS screens run the full height, so the button sits beside START instead.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(146), dp(17));
        } else if (isN64()) {
            // Under the A button.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(18), dp(12));
        } else if (isDreamcast() || isSaturn()) {
            // Under the face buttons.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(54), dp(12));
        } else if (isNeoGeoPocket()) {
            // Bottom of the right column, rewind beside it.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(16), dp(17));
        } else if (isMasterSystem()) {
            // Centred under the 1 and 2 buttons.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(52), dp(17));
        } else if (isPadLayout()) {
            speedParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            speedParams.setMargins(0, 0, 0, dp(6));
        } else {
            speedParams.gravity = Gravity.TOP | Gravity.END;
            speedParams.setMargins(0, dp(62), dp(14), 0);
        }
        frameLayout.addView(speedButtonView, speedParams);
        if (isNds()) {
            // The screens fill the middle: the left column, mirroring the speed button.
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_MIRROR, 72, false);
        } else if (isN64() || isDreamcast() || isMasterSystem()) {
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_LEFT, 66, true);
        } else if (isNeoGeoPocket()) {
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_LEFT, 66, false);
        } else if (isWonderSwan()) {
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_BELOW, 80, false);
        } else if (isPadLayout()) {
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_BESIDE, 66, false);
        } else {
            addRewindButton(frameLayout, speedButtonView, speedParams, REWIND_LEFT, 66, false);
        }
    }

    // A round coloured icon with its name underneath; the circle darkens while pressed.
    private LinearLayout createIconButton(int icon, int color, String label, int iconDp, int textSp, View.OnClickListener onClickListener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(1);
        box.setGravity(1);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        box.addView(iconView, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(textSp);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextColor(Color.rgb(58, 63, 75));
        text.setGravity(1);
        text.setSingleLine(true);
        box.addView(text, new LinearLayout.LayoutParams(-2, -2));
        box.setClickable(true);
        box.setContentDescription(label);
        box.setOnClickListener(onClickListener);
        box.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                iconView.setPressedLook(true);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                iconView.setPressedLook(false);
            }
            return false;
        });
        return box;
    }

    private void onSaveClicked() {
        if (isMame139()) {
            Emulator.saveState();
            Toast.makeText(this, "MAME 0.139 빠른 저장을 요청했습니다.", 0).show();
        } else {
            // A new quick save slot, and the in-game save as well (PS2 games write their
            // memory card themselves).
            createQuickSave(!isPs2());
        }
    }

    // ---- Quick save slots: the automatic save (on leaving, resumed from) plus up to
    // QUICK_SLOTS saves of the player's own, each with a picture of the screen ----
    private static final int QUICK_SLOTS = 5;

    private File autoStateFile() {
        return new File(new File(new File(getFilesDir(), "saves"), this.coreId), this.saveBaseName + ".state");
    }

    private File quickSaveDir() {
        return new File(new File(new File(new File(getFilesDir(), "saves"), this.coreId), "quicksaves"), this.saveBaseName);
    }

    private static File thumbnailFor(File state) {
        String name = state.getName();
        return new File(state.getParentFile(), name.substring(0, name.length() - ".state".length()) + ".jpg");
    }

    private File autoThumbnail() {
        return new File(quickSaveDir(), "auto.jpg");
    }

    // The player's slots, newest first; each is "<number>.state".
    private java.util.List<File> quickSaves() {
        java.util.List<File> slots = new java.util.ArrayList<>();
        File[] files = quickSaveDir().listFiles((dir, name) -> name.matches("\\d+\\.state"));
        if (files != null) {
            slots.addAll(java.util.Arrays.asList(files));
        }
        java.util.Collections.sort(slots, (a, b) -> Integer.compare(slotNumber(b), slotNumber(a)));
        return slots;
    }

    private static int slotNumber(File state) {
        try {
            return Integer.parseInt(state.getName().substring(0, state.getName().indexOf('.')));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void createQuickSave(boolean withSram) {
        final long handle = this.nativeHandle;
        if (handle == 0) {
            return;
        }
        File dir = quickSaveDir();
        dir.mkdirs();
        java.util.List<File> slots = quickSaves();
        int number = slots.isEmpty() ? 1 : slotNumber(slots.get(0)) + 1;
        final File state = new File(dir, number + ".state");
        captureThumbnail(thumbnailFor(state));
        callCore(h -> {
            boolean saved = NativeGba.saveStateFile(h, state.getAbsolutePath());
            if (withSram) {
                NativeGba.saveSramNow(h);
            }
            if (saved) {
                // Keep the newest QUICK_SLOTS; the oldest give way.
                java.util.List<File> all = quickSaves();
                for (int k = QUICK_SLOTS; k < all.size(); k++) {
                    deleteSlot(all.get(k));
                }
            } else {
                thumbnailFor(state).delete();
            }
            return saved;
        }, "빠른 저장 " + number + "에 저장했어요." + (slots.size() >= QUICK_SLOTS ? " (가장 오래된 칸을 바꿨어요)" : ""), "빠른 저장 실패.");
    }

    private static void deleteSlot(File state) {
        state.delete();
        thumbnailFor(state).delete();
    }

    // A small picture of the game screen, written as JPEG once Android has copied it.
    private void captureThumbnail(File target) {
        SurfaceView view = this.surfaceView;
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0 || android.os.Build.VERSION.SDK_INT < 24) {
            return;
        }
        int width = 192;
        int height = Math.max(1, Math.round(width * view.getHeight() / (float) view.getWidth()));
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(view, bitmap, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    return;
                }
                new Thread(() -> {
                    target.getParentFile().mkdirs();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
                    } catch (java.io.IOException ignored) {
                        // No picture for this save.
                    }
                }, "thumbnail").start();
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (IllegalArgumentException ignored) {
            // The surface is not ready.
        }
    }

    private static String saveTimeText(long modified) {
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(modified);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR);
        boolean sameYear = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR);
        String time = new java.text.SimpleDateFormat("a h:mm", Locale.KOREAN).format(new java.util.Date(modified));
        if (sameYear && days == 0) {
            return "오늘 " + time;
        }
        if (sameYear && days == 1) {
            return "어제 " + time;
        }
        return new java.text.SimpleDateFormat(sameYear ? "M월 d일 a h:mm" : "yyyy년 M월 d일 a h:mm", Locale.KOREAN).format(new java.util.Date(modified));
    }

    // Keeps the game paused while a sheet opened from the pause menu is up.
    private boolean holdPause;

    private void showLoadList() {
        this.holdPause = true;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        dialog.setOnDismissListener(d -> {
            this.holdPause = false;
            resumeUnlessExiting();
        });
        fillLoadList(dialog, false, new java.util.HashSet<>());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    // The list itself: tap a save to load it; in delete mode, tick saves and delete them.
    private void fillLoadList(android.app.Dialog dialog, boolean deleting, java.util.Set<File> ticked) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        final java.util.List<File> saves = new java.util.ArrayList<>();
        final File auto = autoStateFile();
        if (auto.isFile()) {
            saves.add(auto);
        }
        saves.addAll(quickSaves());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, RoundIconView.LOAD, RoundIconView.COLOR_LOAD), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText(deleting ? "지울 세이브 선택" : "불러오기");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(deleting ? ticked.size() + "개 선택됨" : this.gameTitle + " · " + saves.size() + "개");
        sub.setTextSize(12.0f);
        sub.setTextColor(Color.rgb(122, 130, 140));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(sub);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(titles, titlesParams);
        if (!saves.isEmpty()) {
            TextView action = smallButton(deleting ? (ticked.size() == saves.size() ? "선택 해제" : "전체 선택") : "선택 삭제");
            action.setOnClickListener(view -> {
                if (!deleting) {
                    fillLoadList(dialog, true, new java.util.HashSet<>());
                } else {
                    if (ticked.size() == saves.size()) {
                        ticked.clear();
                    } else {
                        ticked.addAll(saves);
                    }
                    fillLoadList(dialog, true, ticked);
                }
            });
            header.addView(action, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (saves.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 저장한 장면이 없어요.\n일시정지 메뉴의 \"빠른 저장\"으로 저장해 보세요.");
            empty.setTextSize(13.0f);
            empty.setLineSpacing(0, 1.2f);
            empty.setGravity(17);
            empty.setTextColor(Color.rgb(122, 130, 140));
            empty.setPadding(0, dp(24), 0, dp(24));
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (final File save : saves) {
            final boolean isAuto = save.equals(auto);
            boolean on = ticked.contains(save);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
            rowBackground.setColor(on ? Color.rgb(253, 236, 237) : Color.rgb(246, 247, 249));
            rowBackground.setCornerRadius(dp(14));
            row.setBackground(rowBackground);
            if (deleting) {
                TextView box = new TextView(this);
                box.setText(on ? "✓" : "");
                box.setTextColor(Color.WHITE);
                box.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                box.setGravity(17);
                android.graphics.drawable.GradientDrawable boxBackground = new android.graphics.drawable.GradientDrawable();
                boxBackground.setCornerRadius(dp(7));
                boxBackground.setColor(on ? Color.rgb(229, 72, 77) : Color.WHITE);
                boxBackground.setStroke(dp(2), on ? Color.rgb(229, 72, 77) : Color.rgb(201, 206, 214));
                box.setBackground(boxBackground);
                LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(24), dp(24));
                boxParams.setMargins(0, 0, dp(12), 0);
                row.addView(box, boxParams);
            }
            android.widget.ImageView thumb = new android.widget.ImageView(this);
            thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable thumbBackground = new android.graphics.drawable.GradientDrawable();
            thumbBackground.setColor(Color.rgb(203, 208, 215));
            thumbBackground.setCornerRadius(dp(6));
            thumb.setBackground(thumbBackground);
            thumb.setClipToOutline(true);
            File picture = isAuto ? autoThumbnail() : thumbnailFor(save);
            if (picture.isFile()) {
                thumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(picture.getAbsolutePath()));
            }
            row.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(44)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(1);
            TextView name = new TextView(this);
            name.setText(isAuto ? (isPs2() ? "이전 빠른 저장" : "자동 저장") : "빠른 저장 " + slotNumber(save));
            name.setTextSize(14.0f);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(Color.rgb(31, 41, 51));
            texts.addView(name);
            TextView when = new TextView(this);
            when.setText(saveTimeText(save.lastModified()) + (isAuto && !isPs2() ? " · 게임 나갈 때" : ""));
            when.setTextSize(12.0f);
            when.setTextColor(Color.rgb(122, 130, 140));
            texts.addView(when);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            textsParams.setMargins(dp(12), 0, 0, 0);
            row.addView(texts, textsParams);
            row.setOnClickListener(view -> {
                if (deleting) {
                    if (!ticked.remove(save)) {
                        ticked.add(save);
                    }
                    fillLoadList(dialog, true, ticked);
                    return;
                }
                dialog.dismiss();
                callCore(h -> NativeGba.loadStateFile(h, save.getAbsolutePath()),
                        (isAuto ? "자동 저장" : "빠른 저장 " + slotNumber(save)) + "을 불러왔어요.", "불러오지 못했어요.");
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, dp(8), 0, 0);
            panel.addView(row, rowParams);
        }

        if (!deleting && !saves.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("항목을 누르면 그 장면으로 불러와요");
            hint.setTextSize(11.5f);
            hint.setGravity(17);
            hint.setTextColor(Color.rgb(122, 130, 140));
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
            hintParams.setMargins(0, dp(10), 0, 0);
            panel.addView(hint, hintParams);
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        if (deleting) {
            TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
            cancel.setOnClickListener(view -> fillLoadList(dialog, false, new java.util.HashSet<>()));
            buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
            TextView delete = dialogButton(ticked.isEmpty() ? "삭제" : ticked.size() + "개 삭제",
                    ticked.isEmpty() ? Color.rgb(242, 170, 172) : Color.rgb(229, 72, 77), Color.WHITE);
            delete.setOnClickListener(view -> {
                if (!ticked.isEmpty()) {
                    confirmDelete(dialog, saves, ticked, auto);
                }
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
            deleteParams.setMargins(dp(10), 0, 0, 0);
            buttons.addView(delete, deleteParams);
        } else {
            TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
            close.setOnClickListener(view -> dialog.dismiss());
            buttons.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        }
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
    }

    private void confirmDelete(android.app.Dialog list, java.util.List<File> saves, java.util.Set<File> ticked, File auto) {
        StringBuilder names = new StringBuilder();
        for (File save : saves) {
            if (ticked.contains(save)) {
                if (names.length() > 0) {
                    names.append(" · ");
                }
                names.append(save.equals(auto) ? (isPs2() ? "이전 빠른 저장" : "자동 저장") : "빠른 저장 " + slotNumber(save));
            }
        }
        String note = names + "\n지운 세이브는 되돌릴 수 없어요.\n게임 안 세이브(메모리카드·SRAM)는 지워지지 않아요.";
        new AlertDialog.Builder(this)
                .setTitle("세이브 " + ticked.size() + "개를 지울까요?")
                .setMessage(note)
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, w) -> {
                    for (File save : ticked) {
                        if (save.equals(auto)) {
                            save.delete();
                            autoThumbnail().delete();
                        } else {
                            deleteSlot(save);
                        }
                    }
                    Toast.makeText(this, "세이브 " + ticked.size() + "개를 지웠어요.", 0).show();
                    fillLoadList(list, false, new java.util.HashSet<>());
                })
                .show();
    }

    private TextView smallButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(13.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(Color.rgb(55, 65, 81));
        button.setGravity(17);
        button.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(241, 242, 244));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        return button;
    }

    private void onResetClicked() {
        if (isMame139()) {
            Toast.makeText(this, "MAME 0.139 재시작은 ROM 목록에서 다시 실행하세요.", 0).show();
            return;
        }
        callCore(handle -> {
            NativeGba.reset(handle);
            return true;
        }, "게임을 재시작했습니다.", null);
    }

    private void onSpeedChanged(int percent) {
        this.speedPercent = percent;
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.setSpeedPercent(j, percent);
        }
    }

    private void toggleOrientation() {
        // No save here: the activity handles the rotation itself and the game keeps running,
        // and saving stalled the game long enough (a DS state is megabytes) to drag the music.
        int i = !this.landscapeMode ? 1 : 0;
        getSharedPreferences("settings", 0).edit().putInt("orientation_mode", i).commit();
        setRequestedOrientation(i == 1 ? 6 : 1);
    }

    private void addControls(FrameLayout frameLayout) {
        if (isSaturn()) {
            addSaturnControls(frameLayout);
            return;
        }
        if (isWonderSwan()) {
            addWonderSwanControls(frameLayout);
            return;
        }
        if (isNeoGeoPocket()) {
            addNeoGeoPocketControls(frameLayout);
            return;
        }
        if (isN64()) {
            addN64Controls(frameLayout);
            return;
        }
        if (isDreamcast()) {
            addDreamcastControls(frameLayout);
            return;
        }
        View createDirectionControl = createDirectionControl();
        boolean isGba = isPadLayout();
        // The Super Famicom's four face buttons need more room, so its D-pad is a little smaller.
        boolean compactPad = isSnes() || isNds() || isMegaDrive();
        int i = isSonyPad() ? 140 : compactPad ? 150 : isGba ? 166 : 156;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(i), dp(i));
        layoutParams.gravity = 8388691;
        // The PSP's D-pad sits higher to leave room for the analog stick below it.
        // The PS1/PS2 pads keep both sticks along the bottom, so their D-pad sits lower still.
        if (isNds()) {
            // The DS packs its controls into the shorter space under the two screens.
            i = 136;
            layoutParams.width = dp(i);
            layoutParams.height = dp(i);
        }
        layoutParams.setMargins(dp(compactPad || isSonyPad() ? 16 : isGba ? 24 : 16), 0, 0, dp(isNds() ? 60 : isDualShock() ? 160 : isSonyPad() ? 214 : isGba ? 154 : 144));
        frameLayout.addView(createDirectionControl, layoutParams);
        if (isPsp()) {
            FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(92), dp(92));
            stickParams.gravity = 8388691;
            stickParams.setMargins(dp(40), 0, 0, dp(112));
            frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), stickParams);
        }
        if (isDualShock()) {
            // Both sticks in the bottom corners, SELECT/START between them as on a DualShock.
            FrameLayout.LayoutParams leftStick = new FrameLayout.LayoutParams(dp(84), dp(84));
            leftStick.gravity = 8388691;
            leftStick.setMargins(dp(26), 0, 0, dp(54));
            frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), leftStick);
            FrameLayout.LayoutParams rightStick = new FrameLayout.LayoutParams(dp(84), dp(84));
            rightStick.gravity = 8388693;
            rightStick.setMargins(0, 0, dp(26), dp(54));
            frameLayout.addView(new AnalogStickView(this, this::onAnalogRightChanged), rightStick);
            // L2 L1 on the left and R1 R2 on the right, either side of the speed button.
            int shoulderStyle = isPs1() ? TouchButton.STYLE_LIGHT : 2;
            addButton(frameLayout, "L2", kL2, 8388659, dp(14), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "L1", 512, 8388659, dp(72), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "R1", 256, 8388661, dp(72), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "R2", kR2, 8388661, dp(14), dp(34), dp(54), dp(42), shoulderStyle);
        }
        if (hasShoulders()) {
            int shoulderTop = isNds() ? 8 : 34;
            addButton(frameLayout, "L", 512, 8388659, dp(24), dp(shoulderTop), dp(112), dp(46), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(24), dp(shoulderTop), dp(112), dp(46), 2);
        }
        addFastForwardButton(frameLayout);
        if (isSonyPad()) {
            addPspFace(frameLayout);
        } else if (isSnes() || isNds()) {
            // Diamond centred on the D-pad's height: X top, A right, B bottom, Y left
            // (Super Famicom colours; the DS's are plain dark).
            boolean ds = isNds();
            if (ds) {
                // Smaller diamond centred on the DS's lower D-pad.
                addButton(frameLayout, "X", 1024, 8388693, dp(68), dp(154), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "A", 1, 8388693, dp(18), dp(102), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "B", 2, 8388693, dp(68), dp(50), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "Y", 2048, 8388693, dp(118), dp(102), dp(52), dp(52), TouchButton.STYLE_DARK);
                addCenterButton(frameLayout, selectLabel(), 4, -66, 14);
                addCenterButton(frameLayout, startLabel(), 8, 66, 14);
                return;
            }
            addButton(frameLayout, "X", 1024, 8388693, dp(72), dp(254), dp(54), dp(54), ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_SNES_BLUE);
            addButton(frameLayout, "A", 1, 8388693, dp(20), dp(202), dp(54), dp(54), ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_RED);
            addButton(frameLayout, "B", 2, 8388693, dp(72), dp(150), dp(54), dp(54), ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_YELLOW);
            addButton(frameLayout, "Y", 2048, 8388693, dp(124), dp(202), dp(54), dp(54), ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_GREEN);
        } else if (hasTurboPair()) {
            // B and A like the Famicom pad (II and I on the PC Engine), with smaller turbo buttons above them.
            String[] labels = turboLabels();
            int style = isPcEngine() ? TouchButton.STYLE_ACTION : TouchButton.STYLE_ARCADE_RED;
            addButton(frameLayout, labels[0], 1, 8388693, dp(30), dp(210), dp(62), dp(62), style);
            addButton(frameLayout, labels[1], 2, 8388693, dp(102), dp(196), dp(62), dp(62), style);
            addButton(frameLayout, labels[2], 1024, 8388693, dp(40), dp(286), dp(42), dp(42), style);
            addButton(frameLayout, labels[3], 2048, 8388693, dp(112), dp(272), dp(42), dp(42), style);
        } else if (isMegaDrive()) {
            // Six-button pad: X Y Z above A B C, each row rising to the right.
            addMegaDriveButtons(frameLayout, false);
        } else if (isGba) {
            boolean sms = isMasterSystem();
            addButton(frameLayout, sms ? "2" : "A", 1, 8388693, dp(44), dp(243), dp(72), dp(72), sms ? TouchButton.STYLE_DARK : 3);
            addButton(frameLayout, sms ? "1" : "B", 2, 8388693, dp(78), dp(159), dp(72), dp(72), sms ? TouchButton.STYLE_DARK : 3);
        } else if (isNeoGeoMode()) {
            // Diamond centred on the joystick's height; the right margin mirrors its left margin.
            addButton(frameLayout, "C", 1024, 8388693, dp(70), dp(250), dp(52), dp(52), TouchButton.STYLE_ARCADE_GREEN);
            addButton(frameLayout, "D", 2048, 8388693, dp(16), dp(196), dp(52), dp(52), TouchButton.STYLE_ARCADE_BLUE);
            addButton(frameLayout, "B", 2, 8388693, dp(70), dp(142), dp(52), dp(52), TouchButton.STYLE_ARCADE_YELLOW);
            addButton(frameLayout, "A", 1, 8388693, dp(124), dp(196), dp(52), dp(52), TouchButton.STYLE_ARCADE_RED);
        } else {
            // 3x2 grid with 10dp gaps: D E F on top, A B C below.
            addButton(frameLayout, "A", 1, 8388693, dp(136), dp(167), dp(50), dp(50), TouchButton.STYLE_ARCADE_BLUE);
            addButton(frameLayout, "B", 2, 8388693, dp(76), dp(167), dp(50), dp(50), TouchButton.STYLE_ARCADE_BLUE);
            addButton(frameLayout, "C", 1024, 8388693, dp(16), dp(167), dp(50), dp(50), TouchButton.STYLE_ARCADE_BLUE);
            addButton(frameLayout, "D", 2048, 8388693, dp(136), dp(227), dp(50), dp(50), TouchButton.STYLE_ARCADE_RED);
            addButton(frameLayout, "E", 4096, 8388693, dp(76), dp(227), dp(50), dp(50), TouchButton.STYLE_ARCADE_RED);
            addButton(frameLayout, "F", 8192, 8388693, dp(16), dp(227), dp(50), dp(50), TouchButton.STYLE_ARCADE_RED);
        }
        if (isDualShock()) {
            for (int k = 0; k < 2; k++) {
                TouchButton key = new TouchButton(this, k == 0 ? "SELECT" : "START", k == 0 ? 4 : 8, this::setButtonPressed);
                FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(dp(60), dp(30));
                keyParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                keyParams.setMargins(0, 0, 0, dp(81));
                key.setTranslationX(dp(k == 0 ? -34 : 34));
                frameLayout.addView(key, keyParams);
            }
            return;
        }
        if (isMasterSystem()) {
            addCenterButton(frameLayout, startLabel(), 8, 0);
            return;
        }
        addCenterButton(frameLayout, selectLabel(), 4, -66);
        addCenterButton(frameLayout, startLabel(), 8, 66);
    }

    private void addButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4, int i5, int i6) {
        addButton(frameLayout, str, i, i2, i3, i4, i5, i6, 0);
    }

    private void addButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4, int i5, int i6, int i7) {
        View touchButton = new TouchButton(this, str, i, this::setButtonPressed, i7);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(i5, i6);
        layoutParams.gravity = i2;
        int max = Math.max(0, i3);
        layoutParams.setMargins(max, i4, max, i4);
        frameLayout.addView(touchButton, layoutParams);
    }

    private void addFastForwardButton(FrameLayout frameLayout) {
        SpeedButtonView speedButtonView = new SpeedButtonView(this, this::onSpeedChanged);
        this.fastForwardButton = speedButtonView;
        speedButtonView.setPercent(this.speedPercent);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(42));
        layoutParams.gravity = 49;
        layoutParams.setMargins(0, dp(isNds() ? 10 : 37), 0, 0);
        frameLayout.addView(this.fastForwardButton, layoutParams);
        // The speed button keeps its place between L and R; rewind goes in the gap below it.
        addRewindButton(frameLayout, speedButtonView, layoutParams, REWIND_BELOW, 72, false);
    }

    private void addCenterButton(FrameLayout frameLayout, String str, int i, int i2) {
        addCenterButton(frameLayout, str, i, i2, 72);
    }

    private void addCenterButton(FrameLayout frameLayout, String str, int i, int i2, int bottomDp) {
        TouchButton touchButton = new TouchButton(this, str, i, this::setButtonPressed);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(34));
        layoutParams.gravity = 81;
        layoutParams.setMargins(0, 0, 0, dp(bottomDp));
        touchButton.setTranslationX(dp(i2));
        frameLayout.addView(touchButton, layoutParams);
    }

    private void updateInput() {
        if (isMame139()) {
            Emulator.setMiniInput(this.buttonMask);
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.setInput(j, this.buttonMask);
        }
    }

    private void showGameMenu() {
        if (isMame139()) {
            Emulator.pause();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.pause(j, true);
            }
        }
        this.menuExiting = false;
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(MENU_CONTINUE);
        items.add(MENU_SAVE_STATE);
        items.add(MENU_LOAD_STATE);
        items.add(MENU_SAVE_SRAM);
        items.add(MENU_RESET);
        items.add(this.landscapeMode ? "세로 화면으로 전환" : "가로 화면으로 전환");
        items.add(MENU_EXIT);
        this.menuItems = items.toArray(new String[0]);
        showPauseDialog();
    }

    // Pause menu: six round tiles and a big continue button.
    private android.app.Dialog pauseDialog;

    private void showPauseDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setPadding(dp(4), 0, dp(4), dp(12));
        TextView title = new TextView(this);
        title.setText("일시정지");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView game = new TextView(this);
        game.setText(this.gameTitle);
        game.setTextSize(12.0f);
        game.setTextColor(Color.rgb(122, 130, 140));
        game.setGravity(8388613);
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        gameParams.setMargins(dp(12), 0, 0, 0);
        header.addView(game, gameParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Indices follow this.menuItems: 1 save, 2 load, 3 in-game save, 4 reset, 5 rotate, 6 exit.
        LinearLayout row1 = new LinearLayout(this);
        row1.addView(createMenuTile(dialog, 1, RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "빠른 저장", null), menuTileParams(0));
        row1.addView(createMenuTile(dialog, 2, RoundIconView.LOAD, RoundIconView.COLOR_LOAD, "불러오기", lastQuickSaveText()), menuTileParams(1));
        row1.addView(createMenuTile(dialog, 3, RoundIconView.CARD, RoundIconView.COLOR_CARD, "게임 세이브\n저장", null), menuTileParams(2));
        panel.addView(row1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(createMenuTile(dialog, 4, RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", null), menuTileParams(0));
        row2.addView(createMenuTile(dialog, 5, RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, this.landscapeMode ? "세로 화면" : "가로 화면", null), menuTileParams(1));
        row2.addView(createMenuTile(dialog, 6, RoundIconView.EXIT, RoundIconView.COLOR_EXIT, "나가기", null), menuTileParams(2));
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(-1, -2);
        row2Params.setMargins(0, dp(10), 0, 0);
        panel.addView(row2, row2Params);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, dp(52));
        settingsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(createSettingRow(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "설정", "", view -> showSettingsSheet()), settingsParams);
        LinearLayout.LayoutParams cheatParams = new LinearLayout.LayoutParams(-1, dp(52));
        cheatParams.setMargins(0, dp(8), 0, 0);
        LinearLayout cheatRow = createSettingRow(RoundIconView.CHEAT, RoundIconView.COLOR_CHEAT, "치트 코드", cheatSummary(), view -> {
            if (!cheatsSupported()) {
                Toast.makeText(this, "이 기종은 엔진이 치트를 지원하지 않아요.", Toast.LENGTH_LONG).show();
                return;
            }
            showCheatSheet();
        });
        // Its value ("2개 켜짐") follows changes made in the cheat sheet over it.
        this.cheatRowValue = (TextView) cheatRow.getChildAt(cheatRow.getChildCount() - 1);
        panel.addView(cheatRow, cheatParams);
        if (isPadScreen()) {
            LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(-1, dp(52));
            speedParams.setMargins(0, dp(12), 0, 0);
            panel.addView(createSpeedRow(), speedParams);
        }
        if (isPsp()) {
            LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-1, dp(52));
            languageParams.setMargins(0, dp(12), 0, 0);
            panel.addView(createLanguageRow(dialog), languageParams);
            LinearLayout.LayoutParams scaleParams = new LinearLayout.LayoutParams(-1, dp(52));
            scaleParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createScaleRow(dialog), scaleParams);
            LinearLayout.LayoutParams fastParams = new LinearLayout.LayoutParams(-1, dp(52));
            fastParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createFastRow(dialog), fastParams);
        }
        if (isWonderSwan()) {
            LinearLayout.LayoutParams tallParams = new LinearLayout.LayoutParams(-1, dp(52));
            tallParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createWonderSwanTallRow(dialog), tallParams);
        }

        TextView resume = new TextView(this);
        resume.setText("▶  계속하기");
        resume.setTextSize(16.0f);
        resume.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        resume.setTextColor(Color.WHITE);
        resume.setGravity(17);
        android.graphics.drawable.GradientDrawable resumeBackground = new android.graphics.drawable.GradientDrawable();
        resumeBackground.setColor(Color.rgb(22, 185, 207));
        resumeBackground.setCornerRadius(dp(14));
        resume.setBackground(resumeBackground);
        resume.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, dp(50));
        resumeParams.setMargins(0, dp(14), 0, 0);
        panel.addView(resume, resumeParams);

        // Scrolls when the rows outgrow a landscape screen.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        this.pauseDialog = dialog;
        dialog.setOnDismissListener(d -> resumeUnlessExiting());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    // PSP system language, per game. PSP games pick their text (and some their keyboard)
    // from it; an English game following a Korean setting shows a Hangul keyboard it has no
    // font for.
    private static final String[] PSP_LANGUAGES = {"Korean", "English", "Japanese"};
    private static final String[] PSP_LANGUAGE_NAMES = {"한국어", "English", "日本語"};
    private static final String[] PSP_LANGUAGE_NOTES = {"기본값 · 한국어 지원 게임·한글 패치용", "영문판 게임 (이름 입력 키보드가 영어로)", "일본판 게임"};

    private String pspLanguage() {
        String value = getSharedPreferences("settings", 0).getString("psp_language:" + this.saveBaseName, "Korean");
        for (String language : PSP_LANGUAGES) {
            if (language.equals(value)) {
                return value;
            }
        }
        return "Korean";
    }

    private int pspLanguageIndex() {
        return java.util.Arrays.asList(PSP_LANGUAGES).indexOf(pspLanguage());
    }

    // Game pad screen mode has no speed button beside the screen; it sits in the menu.
    private LinearLayout createSpeedRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(14), 0, dp(10), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(246, 247, 249));
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        TextView label = new TextView(this);
        label.setText("배속");
        label.setTextSize(15.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.rgb(31, 41, 51));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1.0f));
        SpeedButtonView speed = new SpeedButtonView(this, this::onSpeedChanged);
        speed.setPercent(this.speedPercent);
        speed.setContentDescription("배속");
        row.addView(speed, new LinearLayout.LayoutParams(dp(80), dp(36)));
        return row;
    }

    private LinearLayout createLanguageRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.GLOBE, RoundIconView.COLOR_GLOBE, "PSP 시스템 언어", PSP_LANGUAGE_NAMES[pspLanguageIndex()],
                view -> showLanguageDialog(menu));
    }

    private void showLanguageDialog(android.app.Dialog menu) {
        showChoiceDialog(RoundIconView.GLOBE, RoundIconView.COLOR_GLOBE, "PSP 시스템 언어", PSP_LANGUAGE_NAMES, PSP_LANGUAGE_NOTES,
                pspLanguageIndex(), "이 게임에만 적용돼요. 바꾸면 게임이 처음부터 다시 시작돼요.\n게임 안 세이브(메모리스틱)는 그대로 남아요.",
                Color.rgb(255, 247, 232), Color.rgb(138, 90, 0), "적용하고 재시작", choice -> {
            String language = PSP_LANGUAGES[choice];
            if (language.equals(pspLanguage())) {
                return;
            }
            getSharedPreferences("settings", 0).edit().putString("psp_language:" + this.saveBaseName, language).apply();
            menu.dismiss();
            callCore(handle -> {
                NativeGba.setPspLanguage(handle, language);
                return true;
            }, "PSP 시스템 언어를 " + PSP_LANGUAGE_NAMES[choice] + "로 바꾸고 게임을 재시작했습니다.", null);
        });
    }

    // PSP rendering scale, per game: 1x is the PSP's own 480x272.
    private static final String[] PSP_SCALE_NAMES = {"1배 · 480×272", "2배 · 960×544", "3배 · 1440×816"};
    private static final String[] PSP_SCALE_SHORT = {"1배 · 빠름", "2배 · 기본", "3배 · 선명"};
    private static final String[] PSP_SCALE_NOTES = {"PSP 원래 해상도. 가장 빠름 — 무거운 게임이 느릴 때", "선명하고 대부분 게임에 무난 (기본)", "가장 선명. 성능 좋은 폰·가벼운 게임용"};

    private int pspScale() {
        int scale = getSharedPreferences("settings", 0).getInt("psp_scale:" + this.saveBaseName, 2);
        return scale >= 1 && scale <= 3 ? scale : 2;
    }

    private LinearLayout createScaleRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", PSP_SCALE_SHORT[pspScale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", PSP_SCALE_NAMES, PSP_SCALE_NOTES,
                        pspScale() - 1, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요 (2~3초).",
                        Color.rgb(238, 246, 255), Color.rgb(30, 79, 138), "적용하고 이어하기", choice -> {
                    if (choice + 1 == pspScale()) {
                        return;
                    }
                    getSharedPreferences("settings", 0).edit().putInt("psp_scale:" + this.saveBaseName, choice + 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // "속도 우선", per game (PSP, and PS2 on PCSX2): speed hacks that can drop some effects,
    // off by default.
    private static final String[] FAST_NAMES = {"끔 · 정확하게", "켬 · 빠르게"};
    private static final String[] FAST_NOTES = {"지금처럼. 화면 효과를 모두 그대로 그려요 (기본)", "끊기는 게임용. 프레임마다 하는 일을 줄여요"};

    private boolean speedFirstSupported() {
        return isPsp();
    }

    // PSP kept its 0.0.106 key.
    private String speedFirstKey() {
        return (isPsp() ? "psp_fast:" : "fast:") + this.saveBaseName;
    }

    private boolean speedFirst() {
        return getSharedPreferences("settings", 0).getBoolean(speedFirstKey(), false);
    }

    private LinearLayout createFastRow(android.app.Dialog menu) {
        String changes = isPsp()
                ? "· 텍스처 변경 검사를 덜 자주 해요\n· GPU에서 화면을 되읽는 작업을 건너뛰어요\n· 많이 느려지는 순간엔 1프레임씩 건너뛰어요\n게임에 따라 일부 효과(빛 번짐·화면 위 그림)가 빠질 수 있어요."
                : "· GPU에서 화면을 되읽을 때 기다리지 않아요\n· PS2 CPU를 75%로 낮추고 일부 계산을 건너뛰어요\n· 반투명 효과를 가장 단순하게 그려요\n게임에 따라 일부 효과가 빠지거나, 드물게 게임이 멈출 수 있어요.";
        LinearLayout row = createSettingRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", speedFirst() ? "켜짐" : "꺼짐",
                view -> showChoiceDialog(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", FAST_NAMES, FAST_NOTES,
                        speedFirst() ? 1 : 0, "켜면 이렇게 바뀌어요\n" + changes + "\n이 게임에만 적용 · 저장하고 그 장면에서 이어져요 (2~3초).",
                        Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용하고 이어하기", choice -> {
                    if ((choice == 1) == speedFirst()) {
                        return;
                    }
                    getSharedPreferences("settings", 0).edit().putBoolean(speedFirstKey(), choice == 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (!speedFirst()) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Color.rgb(154, 161, 170));
        }
        return row;
    }

    // Closes the game (saving it as on exit) and has the ROM list open it again once this
    // game's process is gone; it resumes from the quick save made on the way out.
    private void relaunchGame() {
        this.menuExiting = true;
        Intent relaunch = new Intent(this, MainActivity.class);
        relaunch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        relaunch.putExtra(MainActivity.EXTRA_RELAUNCH_GAME, new Intent(getIntent()));
        startActivity(relaunch);
        callCore(NativeGba::saveNow, null, null);
        finish();
    }

    // A pause-menu row: icon, name, the current value and an arrow.
    private LinearLayout createSettingRow(int icon, int color, String name, String current, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(246, 247, 249));
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        row.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.rgb(31, 41, 51));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelParams.setMargins(dp(12), 0, 0, 0);
        row.addView(label, labelParams);
        TextView value = new TextView(this);
        value.setText(current + "  ›");
        value.setTextSize(13.5f);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setTextColor(color);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        // The pause menu stays open underneath; the game stays paused meanwhile.
        row.setOnClickListener(onClick);
        return row;
    }

    private interface ChoiceListener {
        void onChoice(int index);
    }

    // A sheet of radio options over the pause menu, with a note and cancel / apply buttons.
    private void showChoiceDialog(int icon, int color, String titleText, String[] names, String[] notes, int current,
                                  String noteText, int noteBackground, int noteColor, String applyText, ChoiceListener onApply) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        titles.addView(title);
        TextView game = new TextView(this);
        game.setText(this.gameTitle);
        game.setTextSize(12.0f);
        game.setTextColor(Color.rgb(122, 130, 140));
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(game);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        final int[] selected = {current};
        final LinearLayout[] options = new LinearLayout[names.length];
        final View[] dots = new View[names.length];
        int selectedFill = Color.argb(28, Color.red(color), Color.green(color), Color.blue(color));
        Runnable refresh = () -> {
            for (int k = 0; k < options.length; k++) {
                boolean on = k == selected[0];
                android.graphics.drawable.GradientDrawable optionBackground = new android.graphics.drawable.GradientDrawable();
                optionBackground.setColor(on ? selectedFill : Color.rgb(246, 247, 249));
                optionBackground.setCornerRadius(dp(14));
                options[k].setBackground(optionBackground);
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setStroke(dp(2), on ? color : Color.rgb(201, 206, 214));
                dot.setColor(Color.WHITE);
                dots[k].setBackground(on ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{dot, innerDot(color)}) : dot);
            }
        };
        for (int k = 0; k < names.length; k++) {
            final int index = k;
            LinearLayout option = new LinearLayout(this);
            option.setOrientation(0);
            option.setGravity(16);
            option.setPadding(dp(14), dp(12), dp(14), dp(12));
            View dot = new View(this);
            option.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(1);
            TextView name = new TextView(this);
            name.setText(names[k]);
            name.setTextSize(14.5f);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(Color.rgb(31, 41, 51));
            texts.addView(name);
            TextView note = new TextView(this);
            note.setText(notes[k]);
            note.setTextSize(11.5f);
            note.setTextColor(Color.rgb(122, 130, 140));
            texts.addView(note);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            textsParams.setMargins(dp(12), 0, 0, 0);
            option.addView(texts, textsParams);
            option.setOnClickListener(view -> {
                selected[0] = index;
                refresh.run();
            });
            LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(-1, -2);
            optionParams.setMargins(0, dp(8), 0, 0);
            panel.addView(option, optionParams);
            options[k] = option;
            dots[k] = dot;
        }
        refresh.run();

        TextView warning = new TextView(this);
        warning.setText(noteText);
        warning.setTextSize(12.0f);
        warning.setLineSpacing(0, 1.15f);
        warning.setTextColor(noteColor);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable warningBackground = new android.graphics.drawable.GradientDrawable();
        warningBackground.setColor(noteBackground);
        warningBackground.setCornerRadius(dp(12));
        warning.setBackground(warningBackground);
        LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(-1, -2);
        warningParams.setMargins(0, dp(12), 0, 0);
        panel.addView(warning, warningParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        TextView apply = dialogButton(applyText, color, Color.WHITE);
        apply.setOnClickListener(view -> {
            dialog.dismiss();
            onApply.onChoice(selected[0]);
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        applyParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(14), 0, 0);
        panel.addView(buttons, buttonsParams);

        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    // The filled centre of a selected radio dot.
    private android.graphics.drawable.Drawable innerDot(int color) {
        android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable();
        inner.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        inner.setColor(color);
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(inner, dp(5));
        return inset;
    }

    private TextView dialogButton(String text, int background, int textColor) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(15.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(17);
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(14));
        button.setBackground(drawable);
        return button;
    }

    private LinearLayout.LayoutParams menuTileParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
        params.setMargins(column == 0 ? 0 : dp(5), 0, column == 2 ? 0 : dp(5), 0);
        return params;
    }

    private LinearLayout createMenuTile(android.app.Dialog dialog, int index, int icon, int color, String label, String detail) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(1);
        tile.setGravity(1);
        tile.setPadding(dp(6), dp(14), dp(6), dp(12));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(246, 247, 249));
        background.setCornerRadius(dp(16));
        tile.setBackground(background);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        tile.addView(iconView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13.0f);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextColor(Color.rgb(31, 41, 51));
        text.setGravity(1);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(0, dp(8), 0, 0);
        tile.addView(text, textParams);
        if (detail != null) {
            TextView sub = new TextView(this);
            sub.setText(detail);
            sub.setTextSize(10.5f);
            sub.setTextColor(Color.rgb(122, 130, 140));
            sub.setGravity(1);
            tile.addView(sub, new LinearLayout.LayoutParams(-2, -2));
        }
        tile.setClickable(true);
        tile.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                iconView.setPressedLook(true);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                iconView.setPressedLook(false);
            }
            return false;
        });
        tile.setOnClickListener(view -> {
            // Runs the action first so leaving the game is flagged before the dialog's
            // dismiss listener would resume it.
            onGameMenuItemSelected(dialog, index);
            dialog.dismiss();
        });
        return tile;
    }

    // When the quick save was last written, from the state file the bridge keeps.
    private String lastQuickSaveText() {
        if (isMame139()) {
            return null;
        }
        long modified = 0;
        File auto = autoStateFile();
        if (auto.isFile()) {
            modified = auto.lastModified();
        }
        for (File save : quickSaves()) {
            modified = Math.max(modified, save.lastModified());
        }
        if (modified == 0) {
            return "저장 없음";
        }
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(modified);
        java.util.Calendar now = java.util.Calendar.getInstance();
        boolean today = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) && then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
        return new java.text.SimpleDateFormat(today ? "a h:mm" : "M/d a h:mm", Locale.KOREAN).format(new java.util.Date(modified));
    }

    private void onGameMenuItemSelected(DialogInterface dialogInterface, int index) {
        String item = index < this.menuItems.length ? this.menuItems[index] : "";
        // Map the label back to the fixed action numbers used below.
        int i;
        switch (item) {
            case MENU_CONTINUE: i = 0; break;
            case MENU_SAVE_STATE: i = 1; break;
            case MENU_LOAD_STATE: i = 2; break;
            case MENU_SAVE_SRAM: i = 3; break;
            case MENU_RESET: i = 4; break;
            case MENU_EXIT: i = 6; break;
            default: i = 5; break;
        }
        if (i == 0) {
            dialogInterface.dismiss();
            return;
        }
        if (i == 1) {
            if (isMame139()) {
                Emulator.saveState();
                Toast.makeText(this, "MAME 0.139 빠른 저장을 요청했습니다.", 0).show();
                return;
            } else {
                createQuickSave(false);
                return;
            }
        }
        if (i == 2) {
            if (isMame139()) {
                Emulator.loadState();
                Toast.makeText(this, "MAME 0.139 빠른 저장 불러오기를 요청했습니다.", 0).show();
                return;
            } else {
                showLoadList();
                return;
            }
        }
        if (i == 3) {
            if (isMame139()) {
                Emulator.saveState();
                Toast.makeText(this, "MAME 0.139 상태를 저장했습니다.", 0).show();
                return;
            } else {
                callCore(NativeGba::saveSramNow, "게임 내 세이브 파일을 저장했습니다.", "게임 내 세이브 데이터가 없습니다.");
                return;
            }
        }
        if (i != 4) {
            if (i == 5) {
                toggleOrientation();
                return;
            } else {
                if (i == 6) {
                    this.menuExiting = true;
                    finish();
                    return;
                }
                return;
            }
        }
        if (isMame139()) {
            Toast.makeText(this, "ROM 목록에서 다시 실행하면 재시작됩니다.", 0).show();
            return;
        }
        callCore(handle -> {
            NativeGba.reset(handle);
            return true;
        }, "게임을 재시작했습니다.", null);
    }

    private void resumeUnlessExiting() {
        if (!this.menuExiting && isMame139()) {
            Emulator.resume();
        } else {
            // A menu dismissed while another took its place (or a sheet still holds the game).
            if (this.menuExiting || this.holdPause || (this.pauseDialog != null && this.pauseDialog.isShowing())) {
                return;
            }
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.pause(j, false);
            }
        }
    }

    private void showBackExitDialog() {
        if (isMame139()) {
            Emulator.pause();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.pause(j, true);
            }
        }
        this.menuExiting = false;
        AlertDialog create = new AlertDialog.Builder(this).setTitle("게임으로 돌아갈까요?").setMessage("아니오를 누르면 현재 게임을 저장하고 ROM 목록으로 나갑니다.").setNegativeButton("예", (dialog, which) -> resumeEmulation()).setPositiveButton("아니오", (dialog, which) -> saveAndExit()).create();
        create.setOnCancelListener(dialog -> resumeEmulation());
        create.setOnDismissListener(dialog -> resumeUnlessExiting());
        create.show();
    }

    private void resumeEmulation() {
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, false);
        }
    }

    private void saveAndExit() {
        this.menuExiting = true;
        if (isMame139()) {
            Emulator.stop();
        } else {
            callCore(NativeGba::saveNow, null, null);
        }
        finish();
    }

    private void showFatalError(String str) {
        new AlertDialog.Builder(this).setTitle("Mini Emulator").setMessage(str).setPositiveButton(17039370, (dialogInterface, i) -> finish()).show();
    }

    private int dp(int i) {
        return (int) ((i * getResources().getDisplayMetrics().density) + 0.5f);
    }

    // Game Boy and Game Boy Color games run on the GBA engine but have no L/R and a 10:9 screen.
    private boolean isGameBoy() {
        return this.coreId.equals("gba") && this.romPath != null && MainActivity.isGameBoyRomName(this.romPath.toLowerCase(Locale.US));
    }

    private boolean isSnes() {
        return "snes9x".equals(this.coreId);
    }

    private boolean isNds() {
        return "melonds".equals(this.coreId);
    }

    // DS: a finger on the game screen is the stylus. Positions go to the core in libretro
    // pointer units across the whole screen view; melonDS DS maps them onto the lower screen.
    private void attachTouchScreen(View screen) {
        if (!isNds()) {
            return;
        }
        screen.setOnTouchListener((view, event) -> {
            long handle = this.nativeHandle;
            int width = view.getWidth();
            int height = view.getHeight();
            if (handle == 0 || width <= 0 || height <= 0) {
                return true;
            }
            int action = event.getActionMasked();
            boolean pressed = action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL;
            float fx = Math.max(0.0f, Math.min(1.0f, event.getX() / width));
            float fy = Math.max(0.0f, Math.min(1.0f, event.getY() / height));
            NativeGba.setPointer(handle, Math.round((fx * 2.0f - 1.0f) * 32767.0f), Math.round((fy * 2.0f - 1.0f) * 32767.0f), pressed);
            return true;
        });
    }

    private boolean isNes() {
        return "fceumm".equals(this.coreId);
    }

    // Mega CD and 32X (PicoDrive) use the Mega Drive's six-button pad and layout.
    private boolean isMegaDrive() {
        return ("genesis_plus_gx".equals(this.coreId) && !isMasterSystem()) || isMegaCd();
    }

    private boolean isMegaCd() {
        return "picodrive".equals(this.coreId);
    }

    private boolean isSaturn() {
        return "yabasanshiro".equals(this.coreId);
    }

    // Saturn pad as YabaSanshiro reads it: A/B/C are libretro B/A/L, X/Y/Z are Y/X/R, and
    // the shoulder L/R are L2/R2.
    private static final String[] SATURN_LABELS = {"A", "B", "C", "X", "Y", "Z"};
    private static final int[] SATURN_BITS = {2, 1, 512, 2048, 1024, 256};

    // Six buttons: A B C along the bottom, X Y Z above, each row stepping up a little to the
    // right like the pad's curve. Centres are measured from the bottom-right corner.
    private void addSaturnFace(FrameLayout frameLayout, int rightDp, int bottomDp, int gapDp, int bigDp, int smallDp, int rowDp) {
        for (int k = 0; k < 6; k++) {
            int column = k % 3;
            boolean top = k >= 3;
            int cx = rightDp + (2 - column) * gapDp;
            int cy = bottomDp + (top ? rowDp : 0) + column * 6;
            addRoundButton(frameLayout, SATURN_LABELS[k], SATURN_BITS[k], true, cx, cy, top ? smallDp : bigDp, TouchButton.STYLE_DARK);
        }
    }

    private void addSaturnControls(FrameLayout frameLayout) {
        addButton(frameLayout, "L", kL2, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
        addButton(frameLayout, "R", kR2, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        addDPadAt(frameLayout, 20, 120, 146);
        addSaturnFace(frameLayout, 40, 170, 56, 54, 46, 60);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, startLabel(), 8, 0, 36);
    }

    // Landscape: L and R on top of the columns, D-pad left, the six buttons right; START under
    // the D-pad and the speed button under the buttons.
    private void addSaturnLandscapeControls(FrameLayout frameLayout) {
        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.START);
        lParams.setMargins(dp(14), dp(58), 0, 0);
        frameLayout.addView(new TouchButton(this, "L", kL2, this::setButtonPressed, 2), lParams);
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.END);
        rParams.setMargins(0, dp(58), dp(14), 0);
        frameLayout.addView(new TouchButton(this, "R", kR2, this::setButtonPressed, 2), rParams);
        addDPadAt(frameLayout, 30, 82, 124);
        addSaturnFace(frameLayout, 38, 112, 52, 48, 42, 56);
        addButton(frameLayout, startLabel(), 8, 8388691, dp(43), dp(14), dp(94), dp(32));
    }

    private boolean isN64() {
        return "mupen64plus_next".equals(this.coreId);
    }

    private boolean isWonderSwan() {
        return "wswan".equals(this.coreId);
    }

    private boolean isNeoGeoPocket() {
        return "ngp".equals(this.coreId);
    }

    // ---- WonderSwan / Neo Geo Pocket ----
    // WonderSwan Y pad: Y1-Y4 are the core's R2 / R / L2 / L inputs (up, right, down, left).
    private static final int[] WS_Y_BITS = {32768, 256, 16384, 512};
    // Held upright, the core turns the picture and its pad: the D-pad works the Y pad, the
    // small buttons are X1-X4 (Y / X / A / B inputs, left / top / right / bottom on screen)
    // and A/B come from L/R.
    private static final String[] WS_TALL_LABELS = {"X2", "X3", "X4", "X1"};
    private static final int[] WS_TALL_BITS = {1024, 1, 2, 2048};
    private static final String[] WS_TALL_NAMES = {"가로 게임", "세로 게임"};
    private static final String[] WS_TALL_NOTES = {"보통 게임 (기본)", "세로로 들고 하는 게임 · 화면을 세워요"};

    private boolean wonderSwanTall() {
        return getSharedPreferences("settings", 0).getBoolean("ws_tall:" + this.saveBaseName, false);
    }

    private LinearLayout createWonderSwanTallRow(android.app.Dialog menu) {
        boolean tall = wonderSwanTall();
        return createSettingRow(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "게임 방향", WS_TALL_NAMES[tall ? 1 : 0],
                view -> showChoiceDialog(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "게임 방향", WS_TALL_NAMES, WS_TALL_NOTES,
                        tall ? 1 : 0, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요.",
                        Color.rgb(236, 253, 243), Color.rgb(21, 128, 61), "적용하고 이어하기", choice -> {
                    if ((choice == 1) == wonderSwanTall()) {
                        return;
                    }
                    getSharedPreferences("settings", 0).edit().putBoolean("ws_tall:" + this.saveBaseName, choice == 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // A round button centred on (cx, cy), measured from the bottom-left or bottom-right corner.
    private void addRoundButton(FrameLayout frameLayout, String label, int bit, boolean fromRight, int cxDp, int cyDp, int sizeDp, int style) {
        TouchButton button = new TouchButton(this, label, bit, this::setButtonPressed, style);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.BOTTOM | (fromRight ? Gravity.END : Gravity.START));
        int side = dp(cxDp) - dp(sizeDp) / 2;
        int bottom = dp(cyDp) - dp(sizeDp) / 2;
        params.setMargins(fromRight ? 0 : side, 0, fromRight ? side : 0, bottom);
        frameLayout.addView(button, params);
    }

    private void addDPadAt(FrameLayout frameLayout, int leftDp, int bottomDp, int sizeDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.BOTTOM | Gravity.START);
        params.setMargins(dp(leftDp), 0, 0, dp(bottomDp));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), params);
    }

    // The small purple Y pad (or X pad when upright), a diamond centred on (cx, cy).
    private void addWonderSwanSmallPad(FrameLayout frameLayout, int cxDp, int cyDp, int gapDp, int sizeDp) {
        boolean tall = wonderSwanTall();
        // up, right, down, left
        int[][] at = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        for (int k = 0; k < 4; k++) {
            String label = tall ? WS_TALL_LABELS[k] : "Y" + (k + 1);
            int bit = tall ? WS_TALL_BITS[k] : WS_Y_BITS[k];
            addRoundButton(frameLayout, label, bit, false, cxDp + at[k][0] * gapDp, cyDp + at[k][1] * gapDp, sizeDp, TouchButton.STYLE_PURPLE);
        }
    }

    private int wonderSwanA() {
        return wonderSwanTall() ? 512 : 1;
    }

    private int wonderSwanB() {
        return wonderSwanTall() ? 256 : 2;
    }

    // Portrait: D-pad with the Y pad just above it, B and A on the right, START at the bottom.
    private void addWonderSwanControls(FrameLayout frameLayout) {
        addDPadAt(frameLayout, 22, 120, 150);
        addWonderSwanSmallPad(frameLayout, 97, 327, 34, 38);
        addRoundButton(frameLayout, "B", wonderSwanB(), true, 130, 180, 62, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "A", wonderSwanA(), true, 62, 220, 62, TouchButton.STYLE_DARK);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, "START", 8, 0, 40);
    }

    // Landscape: Y pad over the D-pad on the left, A/B and START on the right.
    private void addWonderSwanLandscapeControls(FrameLayout frameLayout) {
        addDPadAt(frameLayout, 34, 40, 120);
        addWonderSwanSmallPad(frameLayout, 94, 220, 30, 34);
        addRoundButton(frameLayout, "B", wonderSwanB(), true, 118, 176, 54, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "A", wonderSwanA(), true, 70, 221, 54, TouchButton.STYLE_DARK);
        addButton(frameLayout, "START", 8, 8388693, dp(49), dp(18), dp(90), dp(32));
    }

    // Neo Geo Pocket: the core reads its A from the B input and its B from the A input.
    private void addNeoGeoPocketControls(FrameLayout frameLayout) {
        addDPadAt(frameLayout, 22, 140, 150);
        addRoundButton(frameLayout, "A", 2, true, 130, 190, 66, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "B", 1, true, 62, 230, 66, TouchButton.STYLE_DARK);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, "OPTION", 8, 0, 40);
    }

    private void addNeoGeoPocketLandscapeControls(FrameLayout frameLayout) {
        // D-pad centred at the height between A and B.
        addDPadAt(frameLayout, 22, 147, 140);
        addRoundButton(frameLayout, "A", 2, true, 150, 183, 62, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "B", 1, true, 76, 251, 62, TouchButton.STYLE_DARK);
        addButton(frameLayout, "OPTION", 8, 8388691, dp(45), dp(18), dp(94), dp(32));
    }

    private boolean isDreamcast() {
        return "flycast".equals(this.coreId);
    }

    // Dreamcast pad in its own colours: Y on top, B right, A bottom, X left. Flycast reads the
    // libretro pad by position, so A/B/X/Y are libretro B/A/Y/X; the triggers are L2/R2.
    private void addDreamcastFace(FrameLayout frameLayout, int gravity, int rightCenter, int verticalCenter, int gap, int size) {
        String[] labels = {"Y", "B", "A", "X"};
        int[] bits = {1024, 1, 2, 2048};
        int[] styles = {TouchButton.STYLE_ARCADE_YELLOW, TouchButton.STYLE_ARCADE_RED, TouchButton.STYLE_SNES_BLUE, TouchButton.STYLE_ARCADE_GREEN};
        int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        boolean fromTop = (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
        for (int k = 0; k < 4; k++) {
            TouchButton button = new TouchButton(this, labels[k], bits[k], this::setButtonPressed, styles[k]);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(size), dp(size), gravity);
            params.rightMargin = dp(rightCenter - offsets[k][0] * gap - size / 2);
            int centre = verticalCenter + (fromTop ? offsets[k][1] : -offsets[k][1]) * gap;
            if (fromTop) {
                params.topMargin = dp(centre - size / 2);
            } else {
                params.bottomMargin = dp(centre - size / 2);
            }
            frameLayout.addView(button, params);
        }
    }

    // Portrait: L and R triggers on top, the stick over the D-pad on the left, the face
    // buttons on the right, START below.
    private void addDreamcastControls(FrameLayout frameLayout) {
        addButton(frameLayout, "L", kL2, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
        addButton(frameLayout, "R", kR2, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM | Gravity.START);
        stickParams.setMargins(dp(24), 0, 0, dp(186));
        frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(28), 0, 0, dp(66));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), padParams);
        addDreamcastFace(frameLayout, Gravity.BOTTOM | Gravity.END, 100, 176, 50, 56);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, startLabel(), 8, 0, 18);
    }

    // Landscape: the same groups in the columns beside the screen; START under the D-pad,
    // the speed button under the face buttons.
    private void addDreamcastLandscapeControls(FrameLayout frameLayout) {
        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.START);
        lParams.setMargins(dp(14), dp(58), 0, 0);
        frameLayout.addView(new TouchButton(this, "L", kL2, this::setButtonPressed, 2), lParams);
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.END);
        rParams.setMargins(0, dp(58), dp(14), 0);
        frameLayout.addView(new TouchButton(this, "R", kR2, this::setButtonPressed, 2), rParams);
        // The stick a little lower and the D-pad a little higher, closer together.
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(100), dp(100), Gravity.TOP | Gravity.START);
        stickParams.setMargins(dp(40), dp(118), 0, 0);
        frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(42), 0, 0, dp(74));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), padParams);
        addButton(frameLayout, startLabel(), 8, 8388691, dp(43), dp(14), dp(94), dp(32));
        addDreamcastFace(frameLayout, Gravity.TOP | Gravity.END, 94, 196, 42, 50);
    }

    // Nintendo 64 pad, as Mupen64Plus-Next reads it with independent C buttons: A and B are
    // libretro B and Y, the C buttons X/A/L/R (up/down/left/right), Z is L2, R is R2 and
    // L is SELECT.
    private static final int N64_A = 2;
    private static final int N64_B = 2048;
    private static final int N64_C_UP = 1024;
    private static final int N64_C_DOWN = 1;
    private static final int N64_C_LEFT = 512;
    private static final int N64_C_RIGHT = 256;
    private static final int N64_Z = 16384;
    private static final int N64_R = 32768;
    private static final int N64_L = 4;

    private void addN64CButtons(FrameLayout frameLayout, int gravity, int rightCenter, int verticalCenter, int gap, int size) {
        String[] labels = {"▲", "▶", "▼", "◀"};
        int[] bits = {N64_C_UP, N64_C_RIGHT, N64_C_DOWN, N64_C_LEFT};
        int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        boolean fromTop = (gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
        for (int k = 0; k < 4; k++) {
            TouchButton button = new TouchButton(this, labels[k], bits[k], this::setButtonPressed, TouchButton.STYLE_ARCADE_YELLOW);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(size), dp(size), gravity);
            params.rightMargin = dp(rightCenter - offsets[k][0] * gap - size / 2);
            int centre = verticalCenter + (fromTop ? offsets[k][1] : -offsets[k][1]) * gap;
            if (fromTop) {
                params.topMargin = dp(centre - size / 2);
            } else {
                params.bottomMargin = dp(centre - size / 2);
            }
            frameLayout.addView(button, params);
        }
    }

    // Portrait: L/Z and R on top, the stick with a small D-pad under it on the left, the C
    // buttons level with the stick and B/A level with the D-pad on the right, START below.
    private void addN64Controls(FrameLayout frameLayout) {
        addButton(frameLayout, "L", N64_L, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
        addButton(frameLayout, "R", N64_R, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        FrameLayout.LayoutParams zParams = new FrameLayout.LayoutParams(dp(112), dp(40), Gravity.TOP | Gravity.START);
        zParams.setMargins(dp(24), dp(88), 0, 0);
        frameLayout.addView(new TouchButton(this, "Z", N64_Z, this::setButtonPressed, 2), zParams);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(116), dp(116), Gravity.BOTTOM | Gravity.START);
        stickParams.setMargins(dp(22), 0, 0, dp(170));
        frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(38), 0, 0, dp(70));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), padParams);
        addN64CButtons(frameLayout, Gravity.BOTTOM | Gravity.END, 92, 230, 42, 42);
        addButton(frameLayout, "B", N64_B, 8388693, dp(150 - 27), dp(118 - 27), dp(54), dp(54), TouchButton.STYLE_ARCADE_GREEN);
        addButton(frameLayout, "A", N64_A, 8388693, dp(80 - 33), dp(92 - 33), dp(66), dp(66), TouchButton.STYLE_SNES_BLUE);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, startLabel(), 8, 0, 18);
    }

    // Landscape: the same groups in the columns beside the screen; START under the D-pad,
    // the speed button under A.
    private void addN64LandscapeControls(FrameLayout frameLayout) {
        FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(dp(66), dp(34), Gravity.TOP | Gravity.START);
        lParams.setMargins(dp(12), dp(58), 0, 0);
        frameLayout.addView(new TouchButton(this, "L", N64_L, this::setButtonPressed, 2), lParams);
        FrameLayout.LayoutParams zParams = new FrameLayout.LayoutParams(dp(66), dp(34), Gravity.TOP | Gravity.START);
        zParams.setMargins(dp(84), dp(58), 0, 0);
        frameLayout.addView(new TouchButton(this, "Z", N64_Z, this::setButtonPressed, 2), zParams);
        FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(dp(132), dp(34), Gravity.TOP | Gravity.END);
        rParams.setMargins(0, dp(58), dp(14), 0);
        frameLayout.addView(new TouchButton(this, "R", N64_R, this::setButtonPressed, 2), rParams);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.TOP | Gravity.START);
        stickParams.setMargins(dp(26), dp(104), 0, 0);
        frameLayout.addView(new AnalogStickView(this, this::onAnalogChanged), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(45), 0, 0, dp(56));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), padParams);
        addButton(frameLayout, startLabel(), 8, 8388691, dp(34), dp(14), dp(94), dp(32));
        addN64CButtons(frameLayout, Gravity.TOP | Gravity.END, 82, 150, 34, 36);
        addButton(frameLayout, "B", N64_B, 8388693, dp(112 - 24), dp(98), dp(48), dp(48), TouchButton.STYLE_ARCADE_GREEN);
        addButton(frameLayout, "A", N64_A, 8388693, dp(56 - 29), dp(62), dp(58), dp(58), TouchButton.STYLE_SNES_BLUE);
    }

    // Master System, Game Gear and SG-1000 run on Genesis Plus GX too, with a two-button pad.
    private boolean isMasterSystem() {
        if (!"genesis_plus_gx".equals(this.coreId) || this.romPath == null) {
            return false;
        }
        String lower = this.romPath.toLowerCase(Locale.US);
        return lower.endsWith(".sms") || lower.endsWith(".gg") || lower.endsWith(".sg");
    }

    private boolean isGameGear() {
        return isMasterSystem() && this.romPath.toLowerCase(Locale.US).endsWith(".gg");
    }

    private boolean isPcEngine() {
        return "pce_fast".equals(this.coreId);
    }

    private boolean isPsp() {
        return "ppsspp".equals(this.coreId);
    }

    private boolean isPs1() {
        return "pcsx_rearmed".equals(this.coreId);
    }

    private boolean isPs2() {
        return "play".equals(this.coreId) || "pcee2".equals(this.coreId);
    }

    // PSP, PS1 and PS2 share △○×□, a D-pad and analog sticks.
    private boolean isSonyPad() {
        return isPsp() || isPs1() || isPs2();
    }

    // PS1 (DualShock) and PS2 pads: L1/L2/R1/R2 and two sticks.
    private boolean isDualShock() {
        return isPs1() || isPs2();
    }

    // Button bits the bridge reads as libretro L2 and R2.
    private static final int kL2 = 16384;
    private static final int kR2 = 32768;

    // Face buttons: light grey on the PS1 pad, dark on the PSP and PS2.
    private int faceStyle() {
        return isPs1() ? TouchButton.STYLE_LIGHT : TouchButton.STYLE_ACTION;
    }

    // PSP face buttons: label, bit (the libretro X/A/B/Y the core reads as △○×□), colour.
    private static final String[] PSP_LABELS = {"△", "○", "×", "□"};
    private static final int[] PSP_BITS = {1024, 1, 2, 2048};
    private static final int[] PSP_COLORS = {Color.rgb(47, 182, 122), Color.rgb(226, 71, 90), Color.rgb(77, 142, 240), Color.rgb(212, 107, 196)};

    // PSP landscape: L/R, D-pad and analog stick on the left, △○×□ and R on the right, each in
    // the column between the case edge and the screen bezel; SELECT, speed and START along the
    // bottom. layoutPspLandscape sizes them once the screen size is known.
    // PS1/PS2 add L2 and R2 beside L1 and R1, and a right stick under △○×□.
    private void addPspLandscapeControls(FrameLayout frameLayout, View dpad) {
        boolean dual = isDualShock();
        int shoulderStyle = isPs1() ? TouchButton.STYLE_LIGHT : 2;
        View[] views = new View[dual ? 11 : 8];
        views[0] = new TouchButton(this, dual ? "L1" : "L", 512, this::setButtonPressed, shoulderStyle);
        views[1] = new TouchButton(this, dual ? "R1" : "R", 256, this::setButtonPressed, shoulderStyle);
        views[2] = dpad;
        views[3] = new AnalogStickView(this, this::onAnalogChanged);
        for (int k = 0; k < 4; k++) {
            TouchButton button = new TouchButton(this, PSP_LABELS[k], PSP_BITS[k], this::setButtonPressed, faceStyle());
            button.setLabelColor(PSP_COLORS[k]);
            views[4 + k] = button;
        }
        if (dual) {
            views[8] = new TouchButton(this, "L2", kL2, this::setButtonPressed, shoulderStyle);
            views[9] = new TouchButton(this, "R2", kR2, this::setButtonPressed, shoulderStyle);
            views[10] = new AnalogStickView(this, this::onAnalogRightChanged);
        }
        for (View view : views) {
            frameLayout.addView(view, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.START));
        }
        this.pspSideViews = views;
        for (int k = 0; k < 2; k++) {
            TouchButton key = new TouchButton(this, k == 0 ? "SELECT" : "START", k == 0 ? 4 : 8, this::setButtonPressed);
            FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(dp(78), dp(30));
            keyParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            keyParams.setMargins(0, 0, 0, dp(9));
            key.setTranslationX(dp(k == 0 ? -120 : 120));
            frameLayout.addView(key, keyParams);
        }
    }

    private void layoutPspLandscape(FrameLayout screen, int width, int height) {
        // The screen leaves dp(124) of height for its bezel and the bottom row (a little more
        // than it needs, so the columns beside it are wide enough for big buttons), and at
        // least dp(136) on each side for the control columns.
        float aspect = screenAspect();
        // PS1/PS2 (4:3) are narrow enough to leave wide columns anyway, so their screen takes
        // nearly the full height: dp(12) above, a compact dp(10) bezel below (no name strip,
        // see ClearShellView.setCompactBezel) and the SELECT/START row.
        boolean dual = this.pspSideViews.length > 8;
        int screenHeight = Math.max(dp(150), height - dp(dual ? 68 : 124));
        int screenWidth = Math.round(screenHeight * aspect);
        int widest = width - dp(136) * 2;
        if (screenWidth > widest) {
            screenWidth = Math.max(dp(240), widest);
            screenHeight = Math.round(screenWidth / aspect);
        }
        int top = dp(12);
        setBox(screen, (width - screenWidth) / 2, top, screenWidth, screenHeight);
        // Columns between the case edge and the bezel (dp(10) wide), with a little air.
        int screenLeft = (width - screenWidth) / 2;
        int l0 = dp(14);
        int l1 = screenLeft - dp(10) - dp(10);
        int r0 = screenLeft + screenWidth + dp(10) + dp(10);
        int r1 = width - dp(14);
        int lw = Math.max(dp(60), l1 - l0);
        int rw = Math.max(dp(60), r1 - r0);
        int lc = l0 + lw / 2;
        int rc = r0 + rw / 2;
        View[] v = this.pspSideViews;
        // L/R sit under the round corner buttons; PS1/PS2 split each into L2 L1 and R1 R2.
        int shoulderTop = dp(62);
        int shoulderHeight = dp(42);
        if (dual) {
            int lh = (lw - dp(8)) / 2;
            int rh = (rw - dp(8)) / 2;
            setBox(v[8], l0, shoulderTop, lh, shoulderHeight);
            setBox(v[0], l0 + lh + dp(8), shoulderTop, lh, shoulderHeight);
            setBox(v[1], r0, shoulderTop, rh, shoulderHeight);
            setBox(v[9], r0 + rh + dp(8), shoulderTop, rh, shoulderHeight);
        } else {
            setBox(v[0], l0, shoulderTop, lw, shoulderHeight);
            setBox(v[1], r1 - rw, shoulderTop, rw, shoulderHeight);
        }
        int columnTop = shoulderTop + shoulderHeight + dp(8);
        // SELECT/START sit in the middle of the bottom row, clear of the side columns, so the
        // columns run down to the case edge; leaving the bottom row out as well made the
        // D-pad and stick small on phones that are not very tall in landscape.
        int columnBottom = height - dp(14);
        int avail = Math.max(dp(120), columnBottom - columnTop);
        int pad = Math.min(Math.min(lw, dp(150)), Math.round(avail * 0.62f));
        setBox(v[2], lc - pad / 2, columnTop, pad, pad);
        int stick = Math.max(dp(56), Math.min(Math.min(lw - dp(20), dp(100)), avail - pad - dp(10)));
        setBox(v[3], lc - stick / 2, columnTop + pad + dp(10), stick, stick);
        // △○×□ diamond centred a little below the D-pad's centre, fitted to the right column.
        // PS1/PS2 keep them a little smaller, clear of R1/R2 above and the right stick below.
        float gap = Math.min(dp(dual ? 48 : 54), rw / 3.0f);
        int size = Math.round(gap * (dual ? 1.12f : 1.15f));
        // With a right stick below them the buttons line up with the D-pad instead.
        int cy = columnTop + pad / 2 + (dual ? 0 : dp(22));
        int[][] offsets = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int k = 0; k < 4; k++) {
            int x = Math.round(rc + offsets[k][0] * gap - size / 2f);
            int y = Math.round(cy + offsets[k][1] * gap - size / 2f);
            setBox(v[4 + k], x, y, size, size);
        }
        if (dual) {
            setBox(v[10], rc - stick / 2, columnTop + pad + dp(10), stick, stick);
        }
    }

    // Moves a view in a FrameLayout to a box, laying out again only when the box changed.
    private static void setBox(View view, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width == w && lp.height == h && lp.leftMargin == x && lp.topMargin == y && lp.gravity == (Gravity.TOP | Gravity.START)) {
            return;
        }
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(x, y, 0, 0);
        view.setLayoutParams(lp);
    }

    private void addPspFace(FrameLayout frameLayout) {
        // Diamond: △ top, ○ right, × bottom, □ left.
        int[][] margins = {{72, 314}, {20, 262}, {72, 210}, {124, 262}};
        // PS1/PS2: centred on the lower D-pad (its centre is 230dp up rather than 284dp).
        int lift = isDualShock() ? -56 : 0;
        for (int k = 0; k < 4; k++) {
            TouchButton button = new TouchButton(this, PSP_LABELS[k], PSP_BITS[k], this::setButtonPressed, faceStyle());
            button.setLabelColor(PSP_COLORS[k]);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(54), dp(54));
            params.gravity = 8388693;
            params.setMargins(0, 0, dp(margins[k][0]), dp(margins[k][1] + lift));
            frameLayout.addView(button, params);
        }
    }

    private void onAnalogRightChanged(float x, float y) {
        long handle = this.nativeHandle;
        if (handle != 0) {
            NativeGba.setAnalogRight(handle, Math.round(x * 32767), Math.round(y * 32767));
        }
    }

    private void onAnalogChanged(float x, float y) {
        long handle = this.nativeHandle;
        if (handle != 0) {
            NativeGba.setAnalog(handle, Math.round(x * 32767), Math.round(y * 32767));
        }
    }

    // Two face buttons with turbo buttons above them: Famicom (B A / TB TA) and PC Engine (II I / TII TI).
    private boolean hasTurboPair() {
        return isNes() || isPcEngine();
    }

    // Consoles with a D-pad and SELECT/START, laid out like the GBA.
    private boolean isPadLayout() {
        return this.coreId.equals("gba") || isSnes() || isNds() || isNes() || isMegaDrive() || isMasterSystem() || isPcEngine() || isSonyPad();
    }

    private boolean hasShoulders() {
        return (this.coreId.equals("gba") && !isGameBoy()) || isSnes() || isNds() || isPsp();
    }

    private String selectLabel() {
        return isMegaDrive() ? "MODE" : isPadLayout() ? "SELECT" : "COIN";
    }

    // Labels for the face pair and its turbo pair: right button, left button, turbo right, turbo left.
    private String[] turboLabels() {
        return isPcEngine() ? new String[]{"I", "II", "TI", "TII"} : new String[]{"A", "B", "TA", "TB"};
    }

    private String startLabel() {
        return isPcEngine() ? "RUN" : "START";
    }

    // Mega Drive six-button pad. The bits follow Genesis Plus GX's mapping of the libretro
    // pad: A<-Y, B<-B, C<-A, X<-L, Y<-X, Z<-R (MODE is SELECT).
    private void addMegaDriveButtons(FrameLayout frameLayout, boolean landscape) {
        String[] labels = {"A", "B", "C", "X", "Y", "Z"};
        int[] bits = {2048, 2, 1, 512, 1024, 256};
        for (int k = 0; k < 6; k++) {
            int column = k % 3;
            boolean top = k >= 3;
            if (landscape) {
                int right = 118 - column * 52;
                int dy = (top ? -20 : 36) - column * 6;
                addLandscapeArcadeButton(frameLayout, labels[k], bits[k], right, dy, 46, TouchButton.STYLE_ACTION);
            } else {
                int right = 128 - column * 54;
                int bottom = (top ? 235 : 181) + column * 8;
                addButton(frameLayout, labels[k], bits[k], 8388693, dp(right), dp(bottom), dp(48), dp(48), TouchButton.STYLE_ACTION);
            }
        }
    }

    private float screenAspect() {
        if (isNds()) {
            // Both 256x192 screens as the bridge asks melonDS DS to lay them out: stacked
            // with a 4-row gap, stacked and touching, or side by side.
            return this.ndsLayout == 2 ? 512.0f / 192.0f : this.ndsLayout == 1 ? 256.0f / 384.0f : 256.0f / 388.0f;
        }
        if (isPsp()) {
            return 480.0f / 272.0f;
        }
        if (isGameBoy() || isGameGear()) {
            return 160.0f / 144.0f;
        }
        if (isWonderSwan()) {
            return wonderSwanTall() ? 144.0f / 224.0f : 224.0f / 144.0f;
        }
        if (isNeoGeoPocket()) {
            return 160.0f / 152.0f;
        }
        return this.coreId.equals("gba") ? 1.5f : 1.3333334f;
    }

    private boolean isSupportedGameFile(String lowerCasePath) {
        switch (this.coreId) {
            case "gba":
                return MainActivity.isHandheldRomName(lowerCasePath);
            case "snes9x":
            case "fceumm":
            case "genesis_plus_gx":
            case "pce_fast":
            case "ppsspp":
            case "pcsx_rearmed":
            case "play":
            case "pcee2":
            case "melonds":
            case "mupen64plus_next":
            case "flycast":
            case "wswan":
            case "ngp":
            case "picodrive":
            case "yabasanshiro":
                return MainActivity.isConsoleRomNameForCore(this.coreId, lowerCasePath);
            case "fbneo":
            case "mame2003plus":
            case "mame2010":
            case "mame2015":
            case "mame139":
            case "mamearcade":
                return lowerCasePath.endsWith(".zip");
            default:
                return false;
        }
    }

    private boolean isNeoGeoMode() {
        return "neogeo".equals(this.gameMode);
    }

    private boolean isMame139() {
        return "mame139".equals(this.coreId);
    }

    private String baseNameWithoutExtension(String str) {
        int lastIndexOf = str.lastIndexOf(46);
        return lastIndexOf >= 0 ? str.substring(0, lastIndexOf) : str;
    }
}
