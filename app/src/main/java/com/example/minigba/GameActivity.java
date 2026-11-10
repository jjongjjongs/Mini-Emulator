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
    private static final String MENU_SHOT = "스크린샷";
    private static final String MENU_SHOT_FULL = "전체 화면 스크린샷";
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
        Theme.update(this);
        boolean z = MainActivity.sharedSettings(this).getInt("orientation_mode", 0) == 1;
        this.landscapeMode = z;
        setRequestedOrientation(z ? 6 : 1);
        requestWindowFeature(1);
        getWindow().setFlags(1024, 1024);
        this.romPath = getIntent().getStringExtra("com.example.minigba.ROM_PATH");
        // Marks a game session; closing the game clears it. If the game process ends any
        // other way the game list asks the system why (MainActivity.showUnexpectedGameExit).
        String stringExtra = getIntent().getStringExtra("com.example.minigba.CORE_ID");
        MainActivity.sharedSettings(this).edit().putLong(SESSION_START, System.currentTimeMillis()).putBoolean(SESSION_CLOSED, false).commit();
        this.coreId = stringExtra;
        Haptics.setLevel(this, hapticLevel());
        if (stringExtra == null) {
            this.coreId = "gba";
        }
        this.gameMode = getIntent().getStringExtra(EXTRA_MODE);
        if (this.gameMode == null) {
            this.gameMode = this.coreId.equals("gba") ? "gba" : isWonderSwan() ? "ws" : isNeoGeoPocket() ? "ngp" : isMegaCd() ? "mcd" : isSaturn() ? "ss" : isDolphin() ? "gc" : isSnes() ? "snes" : isNes() ? "nes" : isMasterSystem() ? "sms" : isN64() ? "n64" : isDreamcast() ? "dc" : isMegaDrive() ? "md" : isPcEngine() ? "pce" : isVita() ? "vita" : isPsp() ? "psp" : isPs1() ? "ps1" : isPs2() ? "ps2" : isN3ds() ? "3ds" : isWiiU() ? "wiiu" : isNds() ? "nds" : isDos() ? "dos" : isPc98() ? "pc98" : "arcade";
        }
        CrashReports.installJavaHandler(this);
        registerPadListener();
        registerThermalListener();
        CrashReports.noteGame(this, this.gameMode, this.coreId, this.romPath == null ? "" : new File(this.romPath).getName());
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
        TouchButton.slideEnabled = slidePressOn(this);
        if (stringExtra2 == null || stringExtra2.trim().isEmpty()) {
            this.saveBaseName = baseNameWithoutExtension(new File(this.romPath).getName());
        }
        // the console's (or this game's) screen filter, once both are known (0.0.360)
        applyScreenFilterToNative(screenFilter());
        rotateAutoSaves();
        this.audioSink = new AudioSink();
        if ("pcee2".equals(this.coreId)) {
            // PS2's speed wobbles frame to frame on heavy games; 140 ms covers most of it.
            this.audioSink.setFloorMs(140);
        }
        if (this.landscapeMode && motionActive()) {
            // Tilting must not turn the screen over, so landscape is fixed one way from the
            // start. Asking for that once the game's picture existed (0.0.214) made the
            // phone lay the screen out again, and the picture stopped while the sound went on.
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
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
        this.wiiuPadWindow = null;
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        LinearLayout topBar = createTopBar();
        this.layoutGuards.clear();
        this.layoutGuards.add(topBar);
        linearLayout.addView(topBar, new LinearLayout.LayoutParams(-1, dp(TOP_BAR_DP)));
        boolean full = isFullScreenStyle();
        int dp = full ? 0 : dp(4);
        int dp2 = full ? 0 : dp(2);
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setBackgroundColor(Theme.bg(Color.rgb(18, 22, 32)));
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
        frameLayout2.setBackgroundColor(Theme.bg(0));
        addControls(frameLayout2);
        applyButtonAlpha(frameLayout2, null);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, 0, 1.0f);
        // The controls start where the frame style puts them, whatever the style.
        controlsParams.topMargin = full ? Math.max(dp(10), dp(18) + frame[1] + dp(36) - dp(6) - screen[1]) : dp(noNameStrip() ? 14 : 36);
        if (isDualScreen()) {
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
        this.shell = shell;
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
        shell.setThinBezel(full);
        shell.track(frameLayout, frameLayout2);
        addPerfOverlay(root, false);
        setContentView(root);
        this.screenArea = frameLayout;
        addMotionBadges(frameLayout);
        addGcLanBadge(frameLayout);
        addTurboPill(frameLayout);
        addN3dsNetBadge(frameLayout);
        addWiimmfiBadge(frameLayout);
        addPlayerBadges(frameLayout);
        addVmuWindow(root);
        addGunButtons(root);
        installComboZones(root);
        applyLayoutEdits(root);
        recordLayoutGuide(root, frameLayout, false);
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
        int dp = full || isDualScreen() ? 0 : dp(2);
        final FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(Theme.bg(Color.rgb(18, 22, 32)));
        frameLayout2.setPadding(dp, dp, dp, dp);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        attachTouchScreen(surfaceView);
        frameLayout2.addView(this.surfaceView, new FrameLayout.LayoutParams(-1, -1, 17));
        addScreenFilter(frameLayout2);
        addWiiuPadWindow(frameLayout2);
        frameLayout.addView(frameLayout2, new FrameLayout.LayoutParams(dp(320), dp(214), 17));
        addLandscapeControls(frameLayout);
        addLandscapeCornerButtons(frameLayout);
        applyButtonAlpha(frameLayout, frameLayout2);
        frameLayout.addOnLayoutChangeListener((view, i2, i3, i4, i5, i6, i7, i8, i9) -> fitLandscapeScreen(i, frameLayout2, view, i2, i3, i4, i5, i6, i7, i8, i9));
        linearLayout.addView(frameLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        FrameLayout root = new FrameLayout(this);
        ClearShellView shell = createShell(true);
        this.shell = shell;
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
        shell.setThinBezel(full);
        shell.setCompactBezel(!full && (isDualShock() || isDualScreen()));
        // The DS screens get a thin frame too, in either screen style (0.0.277).
        shell.track(frameLayout2, frameLayout);
        // No case under the picture in full-screen style or the DS's edge-to-edge screens.
        addPerfOverlay(root, full || isDualScreen());
        setContentView(root);
        this.screenArea = frameLayout2;
        addMotionBadges(frameLayout2);
        addGcLanBadge(frameLayout2);
        addTurboPill(frameLayout2);
        addN3dsNetBadge(frameLayout2);
        addWiimmfiBadge(frameLayout2);
        addPlayerBadges(frameLayout2);
        addVmuWindow(root);
        addGunButtons(root);
        installComboZones(root);
        applyLayoutEdits(root);
        recordLayoutGuide(root, frameLayout2, true);
    }

    // Once the case is laid out, where its screen and controls are, for the photo
    // background editor's guide: "guide:<mode>:<orient>" = "W,H|screen l,t,r,b|kind:label:l,t,r,b;...".
    private void recordLayoutGuide(final FrameLayout root, final View screen, final boolean landscape) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (root.getWidth() == 0 || root.getHeight() == 0 || screen.getWidth() == 0) {
                    return;
                }
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int[] origin = new int[2];
                root.getLocationInWindow(origin);
                StringBuilder out = new StringBuilder();
                out.append(root.getWidth()).append(',').append(root.getHeight()).append('|');
                appendBounds(out, screen, origin);
                out.append('|');
                collectControls(root, origin, out);
                MainActivity.sharedSettings(GameActivity.this).edit()
                        .putString("guide:" + gameMode + ":" + (landscape ? "land" : "port"), out.toString()).apply();
            }
        });
    }

    private void appendBounds(StringBuilder out, View view, int[] origin) {
        int[] where = new int[2];
        view.getLocationInWindow(where);
        // getLocationInWindow already includes the view's offset and scale; the size follows.
        out.append(where[0] - origin[0]).append(',').append(where[1] - origin[1]).append(',')
                .append(Math.round(where[0] - origin[0] + view.getWidth() * view.getScaleX())).append(',')
                .append(Math.round(where[1] - origin[1] + view.getHeight() * view.getScaleY()));
    }

    private void collectControls(View view, int[] origin, StringBuilder out) {
        if (view.getVisibility() != View.VISIBLE || view instanceof SurfaceView) {
            return;
        }
        String kind = null;
        String label = "";
        if (view instanceof AnalogStickView) {
            kind = "stick";
        } else if (view instanceof DPadView || view instanceof ArcadeJoystickView) {
            kind = "pad";
        } else if (view instanceof TouchButton) {
            kind = "button";
            label = ((TouchButton) view).label();
        } else if (view instanceof SpeedButtonView || view instanceof RewindButtonView) {
            kind = "pill";
        }
        if (kind != null && view.getWidth() > 0) {
            out.append(kind).append(':').append(label.replace(':', ' ').replace(';', ' ')).append(':');
            appendBounds(out, view, origin);
            out.append(';');
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectControls(group.getChildAt(i), origin, out);
            }
        }
    }

    // "프레임" (the default) draws the case's bezel around the screen; "전체 화면" drops it
    // and makes the screen as large as fits beside the controls.
    private boolean isGbaWideFull() {
        return this.coreId.equals("gba") && this.landscapeMode && isFullScreenStyle();
    }

    private boolean isFullScreenStyle() {
        return "full".equals(MainActivity.sharedSettings(this).getString("screen_style", "frame"));
    }

    // "게임패드": for a physical controller. No touch controls or case, only the game screen;
    // the menu button shows for a moment after a tap, or SELECT + START opens the menu.
    private boolean isPadScreen() {
        return "pad".equals(MainActivity.sharedSettings(this).getString("screen_style", "frame"));
    }

    private int ndsLayout;

    private void setNdsLayout(int layout) {
        if (isDualScreen()) {
            this.ndsLayout = layout;
            // DS 화면 배치: melonDS DS draws its screens one above the other with no gap, and
            // the presenter places them.
            NativeGba.setNdsLayout(this.nativeHandle, isNds() && n3dsOwnLayout() ? 1 : layout);
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
        root.setBackgroundColor(Theme.bg(Color.BLACK));
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        attachTouchScreen(surfaceView);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        addScreenFilter(root);
        this.shell = null;
        addPerfOverlay(root, true);
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
        // The 3DS controls are taller: the circle pad sits above the D-pad.
        int tallest = height - dp(TOP_BAR_DP) - screenTop - dp(20) - dp(isN3ds() || isWiiU() ? 352 : 268) - padding;
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
        if (isGameBoy() || isGameGear() || isNeoGeoPocket() || (isWonderSwan() && wonderSwanTall()) || arcadeUpright()) {
            // A nearly square (or upright) screen would push the controls off a short phone.
            int widest = Math.round(dp(isWonderSwan() ? 330 : 250) * screenAspect());
            if (screenWidth > widest) {
                side += (screenWidth - widest) / 2;
                screenWidth = widest;
            }
        }
        if (isDualScreen()) {
            // The tall stacked DS screens would push the D-pad and face buttons off the
            // bottom, so they stop where the controls need dp(272) under the bezel.
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int statusId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            int statusBar = statusId > 0 ? getResources().getDimensionPixelSize(statusId) : dp(24);
            int tallest = metrics.heightPixels - statusBar - dp(TOP_BAR_DP) - dp(18) - dp(36) - dp(isN3ds() || isWiiU() ? 352 : 272) - padding;
            int widest = Math.max(dp(200), Math.round(tallest * screenAspect()));
            if (screenWidth > widest) {
                side += (screenWidth - widest) / 2;
                screenWidth = widest;
            }
        }
        return new int[]{side, Math.max(dp(176), Math.round(screenWidth / screenAspect()) + padding)};
    }

    // Background picked in the game settings (ClearShellView.STYLE_*), for all games.
    private int shellStyle() {
        // The clear case is gone (0.0.270): it, and no choice at all, read as 실물 플라스틱.
        return ClearShellView.normalize(shellPref("shell_style", ClearShellView.STYLE_PLASTIC));
    }

    // The background can be set for one game mode ("○○ 모드만"): its settings are stored
    // under "<name>@<mode>" and win over the ones for all modes while the style key exists.
    private static String modeShellKey(String base, String mode) {
        return base + "@" + mode;
    }

    private boolean shellModeOnly() {
        return MainActivity.sharedSettings(this).contains(modeShellKey("shell_style", this.gameMode));
    }

    private int shellPref(String base, int fallback) {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        String own = modeShellKey(base, this.gameMode);
        if (shellModeOnly() && prefs.contains(own)) {
            return prefs.getInt(own, fallback);
        }
        return prefs.getInt(base, fallback);
    }

    // Writes one background setting where the background in force for this mode lives.
    private void putShellPref(String base, int value) {
        MainActivity.sharedSettings(this).edit().putInt(shellModeOnly() ? modeShellKey(base, this.gameMode) : base, value).apply();
    }

    // Metal · carbon and pastel glass have no name strip under the screen, so the layout gives that room back.
    // Landscape has none for any style (its frames are thin, 0.0.276).
    private boolean noNameStrip() {
        return this.landscapeMode || !ClearShellView.hasNameStrip(shellStyle());
    }

    // 포인트 색 of the metal · carbon case (ClearShellView.TRIM_NAMES).
    private int shellTrim() {
        int value = shellPref("shell_trim", 0);
        return value >= 0 && value < ClearShellView.TRIM_NAMES.length ? value : 0;
    }

    // Kind of 네온 나이트 (ClearShellView.NEON_NAMES).
    private int shellNeon() {
        int value = shellPref("shell_neon", 0);
        return value >= 0 && value < ClearShellView.NEON_NAMES.length ? value : 0;
    }

    // Kind of 우주 (ClearShellView.SPACE_NAMES).
    private int shellSpace() {
        int value = shellPref("shell_space", 0);
        return value >= 0 && value < ClearShellView.SPACE_NAMES.length ? value : 0;
    }

    // Kinds of 재질 and 레트로 (ClearShellView.MATERIAL_NAMES / VINTAGE_NAMES).
    private int shellMaterial() {
        int value = shellPref("shell_material", 0);
        return value >= 0 && value < ClearShellView.MATERIAL_NAMES.length ? value : 0;
    }

    private int shellVintage() {
        int value = shellPref("shell_vintage", 0);
        return value >= 0 && value < ClearShellView.VINTAGE_NAMES.length ? value : 0;
    }

    private int shellSkin() {
        return ClearShellView.skinOf(shellStyle(), shellMaterial(), shellVintage());
    }

    // The background's name with its kind or 포인트 색, as the settings row shows it.
    private String shellStyleName() {
        int style = shellStyle();
        String name = ClearShellView.STYLE_NAMES[style];
        if (style == ClearShellView.STYLE_PASTEL) return name + " · " + ClearShellView.GLASS_NAMES[shellGlass()];
        if (style == ClearShellView.STYLE_NEON) return name + " · " + ClearShellView.NEON_NAMES[shellNeon()];
        if (style == ClearShellView.STYLE_METAL) return name + " · " + ClearShellView.TRIM_NAMES[shellTrim()];
        if (style == ClearShellView.STYLE_SPACE) return name + " · " + ClearShellView.SPACE_NAMES[shellSpace()];
        if (style == ClearShellView.STYLE_MATERIAL) return name + " · " + ClearShellView.MATERIAL_NAMES[shellMaterial()];
        if (style == ClearShellView.STYLE_VINTAGE) return name + " · " + ClearShellView.VINTAGE_NAMES[shellVintage()];
        return name;
    }

    // Kind of 파스텔 글라스 (ClearShellView.GLASS_NAMES).
    private int shellGlass() {
        int value = shellPref("shell_glass", 0);
        return value >= 0 && value < ClearShellView.GLASS_NAMES.length ? value : 0;
    }

    // The mode's colour on the mode picker, and the colour of the console's own plastic.
    private int[] shellColors() {
        if (this.coreId.equals("gba")) return new int[]{Color.rgb(22, 185, 207), Color.rgb(91, 79, 168)};
        if (isSnes()) return new int[]{Color.rgb(84, 92, 168), Color.rgb(200, 201, 207)};
        if (isN3ds()) return new int[]{Color.rgb(225, 29, 72), Color.rgb(52, 56, 66)};
        if (isNds()) return new int[]{Color.rgb(219, 39, 119), Color.rgb(52, 56, 66)};
        if (isNes()) return new int[]{Color.rgb(196, 42, 52), Color.rgb(170, 34, 42)};
        if (isN64()) return new int[]{Color.rgb(22, 163, 74), Color.rgb(45, 47, 52)};
        if (isNaomi()) return new int[]{Color.rgb(13, 148, 136), Color.rgb(28, 28, 32)};
        if (isDreamcast()) return new int[]{Color.rgb(202, 138, 4), Color.rgb(228, 230, 233)};
        if (isMasterSystem()) return new int[]{Color.rgb(162, 28, 175), Color.rgb(30, 30, 33)};
        if (isMegaCd()) return new int[]{Color.rgb(159, 18, 57), Color.rgb(32, 32, 35)};
        if (isMegaDrive()) return new int[]{Color.rgb(15, 118, 110), Color.rgb(28, 28, 30)};
        if (isPcEngine()) return new int[]{Color.rgb(232, 96, 44), Color.rgb(230, 230, 226)};
        if (isPsp()) return new int[]{Color.rgb(28, 30, 36), Color.rgb(22, 22, 26)};
        if (isPs1()) return new int[]{Color.rgb(120, 126, 138), Color.rgb(180, 182, 188)};
        if (isPs2()) return new int[]{Color.rgb(36, 84, 168), Color.rgb(24, 26, 34)};
        if (isSaturn()) return new int[]{Color.rgb(71, 85, 105), Color.rgb(58, 60, 68)};
        if (isWonderSwan()) return new int[]{Color.rgb(101, 163, 13), Color.rgb(96, 140, 52)};
        if (isNeoGeoPocket()) return new int[]{Color.rgb(3, 105, 161), Color.rgb(36, 74, 140)};
        if (isNeoGeoMode()) return new int[]{Color.rgb(124, 58, 237), Color.rgb(24, 24, 26)};
        return new int[]{Color.rgb(154, 91, 46), Color.rgb(59, 75, 140)};
    }

    // Labels drawn straight on the background turn light on the dark styles.
    // The case keeps its own colours in dark mode, so these are not run through Theme.
    private int shellTextColor() {
        if (shellStyle() == ClearShellView.STYLE_RETRO) {
            return Color.rgb(48, 46, 112);
        }
        if (shellStyle() == ClearShellView.STYLE_PASTEL) {
            return Color.rgb(76, 69, 119);
        }
        int skin = shellSkin();
        if (skin == ClearShellView.SKIN_INK) {
            return Color.rgb(43, 42, 40);
        }
        if (skin >= 0) {
            return Color.rgb(246, 236, 220);
        }
        return ClearShellView.darkBackground(shellStyle(), shellColors()[1]) ? Color.rgb(236, 238, 242) : Color.rgb(44, 48, 58);
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
        } else if (isN3ds()) {
            tint = ClearShellView.TINT_WHITE;
            name = "NINTENDO 3DS";
        } else if (isNds()) {
            tint = ClearShellView.TINT_WHITE;
            name = "NINTENDO DS";
        } else if (isNes()) {
            tint = ClearShellView.TINT_CREAM;
            name = "FAMICOM";
        } else if (isN64()) {
            tint = ClearShellView.TINT_GRAY;
            name = "NINTENDO 64";
        } else if (isNaomi()) {
            tint = ClearShellView.TINT_SMOKE;
            name = "NAOMI";
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
        int[] colors = shellColors();
        ClearShellView shell = new ClearShellView(this, tint, "MINI EMULATOR · " + name, landscape, shellStyle(), colors[0], colors[1]);
        shell.setTrim(shellTrim());
        shell.setGlass(shellGlass());
        shell.setNeon(shellNeon());
        shell.setSpace(shellSpace());
        shell.setSkin(shellSkin());
        if (shellStyle() == ClearShellView.STYLE_PHOTO) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            shell.setPhoto(PhotoBackground.load(PhotoBackground.rendered(this, this.gameMode, landscape), metrics.widthPixels, metrics.heightPixels));
        }
        return shell;
    }

    // With a photo background the buttons let it show through as much as the player set.
    private void applyButtonAlpha(android.view.ViewGroup controls, View except) {
        if (shellStyle() != ClearShellView.STYLE_PHOTO) {
            return;
        }
        float alpha = 1.0f - PhotoBackground.buttonAlpha(this, this.gameMode) / 100f;
        for (int i = 0; i < controls.getChildCount(); i++) {
            View child = controls.getChildAt(i);
            if (child != except && !(child instanceof SurfaceView)) {
                child.setAlpha(alpha);
            }
        }
    }

    private static final int REQUEST_BG_EDITOR = 3102;

    private void openBackgroundEditor() {
        openBackgroundEditor(shellModeOnly() ? modeShellKey("shell_style", this.gameMode) : "shell_style");
    }

    // styleKey: where the editor records 내 사진 · 그림 once a photo is applied.
    private void openBackgroundEditor(String styleKey) {
        Intent intent = new Intent(this, BgEditorActivity.class);
        intent.putExtra(BgEditorActivity.EXTRA_MODE, this.gameMode);
        intent.putExtra(BgEditorActivity.EXTRA_MODE_NAME, consoleName());
        intent.putExtra(BgEditorActivity.EXTRA_LANDSCAPE, this.landscapeMode);
        intent.putExtra(BgEditorActivity.EXTRA_STYLE_KEY, styleKey);
        startActivityForResult(intent, REQUEST_BG_EDITOR);
    }

    private void fitLandscapeScreen(int i, FrameLayout frameLayout, View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean full = i == 1;
        if (isKeyboardPc()) {
            fitDosLandscape(frameLayout, width, height);
            return;
        }
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
        if (isDualScreen()) {
            // Stacked DS screens take the full height, less a thin frame above and below
            // (in either screen style, 0.0.277).
            maxHeight = Math.max(dp(150), height - dsFrameMargin(full) * 2);
        } else if (full) {
            // Room for the thin glass bezel (dp(5) each side) included.
            maxHeight = Math.max(dp(150), height - (isGba ? dp(13) + dp(5) + dp(46) : dp(12) + dp(10)));
        } else {
            // Metal · carbon: only its thin surround below the screen, no name strip.
            boolean strip = !noNameStrip();
            maxHeight = Math.max(dp(150), height - (isGba ? dp(18) + dp(strip ? 26 : 10) + dp(46) : dp(12) + dp(strip ? 36 : 20)));
        }
        float aspect = screenAspect();
        int screenWidth = Math.min(maxWidth, Math.round(maxHeight * aspect));
        int screenHeight = Math.round(screenWidth / aspect);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameLayout.getLayoutParams();
        // The DS screens sit below their thin frame (the pad layout pins the screen to the top).
        int topMargin = isDualScreen() ? dsFrameMargin(full) : isGba ? dp(full ? 13 : 18) : 0;
        // Arcade screens sit centred, nudged up so the taller lower bezel stays balanced.
        int bottomMargin = isGba || full || noNameStrip() ? 0 : dp(16);
        if (layoutParams.width != screenWidth || layoutParams.height != screenHeight || layoutParams.topMargin != topMargin || layoutParams.bottomMargin != bottomMargin) {
            layoutParams.width = screenWidth;
            layoutParams.height = screenHeight;
            layoutParams.gravity = isGba ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER;
            layoutParams.topMargin = topMargin;
            layoutParams.bottomMargin = bottomMargin;
            frameLayout.setLayoutParams(layoutParams);
        }
    }

    // Room above and below the stacked DS screens in landscape for their frame.
    private int dsFrameMargin(boolean full) {
        return dp(full ? 6 : 8);
    }

    private void addLandscapeControls(FrameLayout frameLayout) {
        if (isKeyboardPc()) {
            addDosControls(frameLayout, true);
            return;
        }
        if (isDolphin()) {
            addDolphinLandscapeControls(frameLayout);
            return;
        }
        if (isWiiU()) {
            addWiiuLandscapeControls(frameLayout);
            return;
        }
        if (isSaturn()) {
            addSaturnLandscapeControls(frameLayout);
            return;
        }
        if (isWonderSwan()) {
            addWonderSwanLandscapeControls(frameLayout);
            return;
        }
        if (isMsx()) {
            addMsxLandscapeControls(frameLayout);
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
        if (isNaomi()) {
            addNaomiLandscapeControls(frameLayout);
            return;
        }
        if (isDreamcast()) {
            addDreamcastLandscapeControls(frameLayout);
            return;
        }
        if (isN3ds()) {
            addN3dsLandscapeControls(frameLayout);
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
            } else if (isSnes() || isDualScreen()) {
                // X on top, A right, B bottom, Y left, in the Super Famicom colours (DS: dark).
                boolean ds = isDualScreen();
                addLandscapeArcadeButton(frameLayout, "X", 1024, 66, -48, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_SNES_BLUE);
                addLandscapeArcadeButton(frameLayout, "A", 1, 18, 0, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_RED);
                addLandscapeArcadeButton(frameLayout, "B", 2, 66, 48, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_YELLOW);
                addLandscapeArcadeButton(frameLayout, "Y", 2048, 114, 0, 50, ds ? TouchButton.STYLE_DARK : TouchButton.STYLE_ARCADE_GREEN);
                if (isNds()) {
                    // The microphone in the lower right corner, under B and above START.
                    addButton(frameLayout, "MIC", kL3, Gravity.BOTTOM | Gravity.END, dp(10), dp(60), dp(46), dp(46), TouchButton.STYLE_PINK);
                }
            } else {
                // Master System: 2 is libretro A, 1 is B (Genesis Plus GX's two-button pad).
                boolean sms = isMasterSystem();
                addLandscapeActionButton(frameLayout, sms ? "2" : "A", 1, dp(wideGba ? 22 : 26), -dp(42), sms ? TouchButton.STYLE_DARK : 3);
                addLandscapeActionButton(frameLayout, sms ? "1" : "B", 2, dp(wideGba ? 78 : 92), dp(42), sms ? TouchButton.STYLE_DARK : 3);
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
        if (isKeyboardPc() && this.dosControls != null && !isControllerEvent(event)) {
            // A physical keyboard types into DOS (and the PC-98).
            int dosKey = DosControls.fromAndroid(event.getKeyCode());
            if (dosKey != 0 && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
                if (event.getRepeatCount() == 0) {
                    this.dosControls.physicalKey(dosKey, event.getAction() == KeyEvent.ACTION_DOWN);
                }
                return true;
            }
        }
        if (isMsx() && !isControllerEvent(event)) {
            // A physical keyboard types into the MSX.
            int msxKey = DosControls.fromAndroid(event.getKeyCode());
            if (msxKey != 0 && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)) {
                if (event.getRepeatCount() == 0) {
                    MsxKeys.send(this.nativeHandle, msxKey, event.getAction() == KeyEvent.ACTION_DOWN);
                }
                return true;
            }
        }
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
        int player = playerFor(event.getDevice());
        if (player > 0) {
            return otherPlayerKey(player, event) || super.dispatchKeyEvent(event);
        }
        int direction;
        int bit;
        android.util.SparseIntArray table = padMapFor(event.getDevice());
        if (table != null) {
            // The player's own table for this controller.
            int bits = table.get(keyCode);
            if (bits == 0) {
                return PadMapping.isMappableKey(keyCode) || super.dispatchKeyEvent(event);
            }
            direction = bits & PadMapping.DPAD_BITS;
            bit = bits & ~PadMapping.DPAD_BITS;
        } else {
            direction = padDirectionBit(keyCode);
            bit = padButtonBit(keyCode);
        }
        if (direction != 0) {
            this.padKeyDirection = down ? this.padKeyDirection | direction : this.padKeyDirection & ~direction;
            applyDirections();
            if (bit == 0) {
                return true;
            }
        }
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
        for (int p = 1; p < PlayerPads.MAX; p++) {
            clearPlayer(p);
        }
        if (isSonyPad() || isN64() || isDreamcast()) {
            onAnalogChanged(0, 0);
            onAnalogRightChanged(0, 0);
        }
    }

    // ---- "게임패드 버튼 설정": the player's own table for a controller ----
    // Effective tables by controller descriptor; null when the controller keeps the defaults.
    private final java.util.Map<String, android.util.SparseIntArray> padMaps = new java.util.HashMap<>();
    private final java.util.Set<String> padMapDefaults = new java.util.HashSet<>();
    // Axis sources (triggers, stick directions) held right now, by source.
    private final android.util.SparseBooleanArray padAxisHeld = new android.util.SparseBooleanArray();

    private void invalidatePadMaps() {
        this.padMaps.clear();
        this.padMapDefaults.clear();
        this.padAxisHeld.clear();
        releaseController();
    }

    private android.util.SparseIntArray padMapFor(android.view.InputDevice device) {
        String descriptor = PadMapping.descriptor(device);
        android.util.SparseIntArray table = this.padMaps.get(descriptor);
        if (table != null) {
            return table;
        }
        if (this.padMapDefaults.contains(descriptor)) {
            return null;
        }
        android.util.SparseIntArray custom = PadMappingDialog.customFor(MainActivity.sharedSettings(this), this.saveBaseName, this.gameMode, descriptor);
        if (custom.size() == 0) {
            this.padMapDefaults.add(descriptor);
            return null;
        }
        table = PadMapping.effective(padDefaults(), custom);
        this.padMaps.put(descriptor, table);
        return table;
    }

    // Axis inputs through the player's table: each mapped axis direction is a button that is
    // down past 0.6. Sticks still move the console's sticks where it has them.
    private void applyMappedAxes(android.view.MotionEvent event, android.util.SparseIntArray table, float x, float y) {
        if (hasLeftStick()) {
            onAnalogChanged(x, y);
            onAnalogRightChanged(padAxis(event, android.view.MotionEvent.AXIS_Z), padAxis(event, android.view.MotionEvent.AXIS_RZ));
        }
        int directions = 0;
        for (int k = 0; k < table.size(); k++) {
            int source = table.keyAt(k);
            if (!PadMapping.isAxisSource(source)) {
                continue;
            }
            int axis = PadMapping.axisOf(source);
            float value = event.getAxisValue(axis);
            if (axis == android.view.MotionEvent.AXIS_LTRIGGER) {
                value = Math.max(value, event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
            } else if (axis == android.view.MotionEvent.AXIS_RTRIGGER) {
                value = Math.max(value, event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
            }
            boolean pressed = PadMapping.axisNegative(source) ? value < -0.6f : value > 0.6f;
            int bits = table.valueAt(k);
            if (pressed) {
                directions |= bits & PadMapping.DPAD_BITS;
            }
            int buttons = bits & ~PadMapping.DPAD_BITS;
            if (buttons != 0 && this.padAxisHeld.get(source) != pressed) {
                this.padAxisHeld.put(source, pressed);
                setButtonPressed(buttons, pressed);
            }
        }
        if (directions != this.padHatDirection || this.padStickDirection != 0) {
            this.padHatDirection = directions;
            this.padStickDirection = 0;
            applyDirections();
        }
    }

    // {source, bit} pairs the console uses with no changes: the key table above, the hat and
    // trigger axes, and the left stick as a D-pad where the console has no stick.
    private int[][] padDefaults() {
        java.util.List<int[]> pairs = new java.util.ArrayList<>();
        int[] keys = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT};
        for (int key : keys) {
            int bit = padButtonBit(key) | padDirectionBit(key);
            if (bit != 0) {
                pairs.add(new int[]{key, bit});
            }
        }
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_LTRIGGER, false), kL2});
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_RTRIGGER, false), kR2});
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_HAT_X, false), PadMapping.DPAD_RIGHT});
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_HAT_X, true), PadMapping.DPAD_LEFT});
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_HAT_Y, false), PadMapping.DPAD_DOWN});
        pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_HAT_Y, true), PadMapping.DPAD_UP});
        if (!hasLeftStick()) {
            pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_X, false), PadMapping.DPAD_RIGHT});
            pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_X, true), PadMapping.DPAD_LEFT});
            pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_Y, false), PadMapping.DPAD_DOWN});
            pairs.add(new int[]{PadMapping.axisSource(android.view.MotionEvent.AXIS_Y, true), PadMapping.DPAD_UP});
        }
        return pairs.toArray(new int[0][]);
    }

    // The console's buttons as the picture shows them, with the bits the core reads.
    private java.util.List<PadMapping.Target> padTargets() {
        java.util.List<PadMapping.Target> targets = new java.util.ArrayList<>();
        int face = PadMapping.GROUP_FACE;
        int shoulderLeft = PadMapping.GROUP_SHOULDER_LEFT;
        int shoulderRight = PadMapping.GROUP_SHOULDER_RIGHT;
        int middle = PadMapping.GROUP_MIDDLE;
        if (isDolphin()) {
            int control = isWiiGame() ? wiiControl() : -1;
            if (control == -1) {
                // GameCube pad as Dolphin reads it: the triggers are L2 / R2, Z is R.
                targets.add(new PadMapping.Target(1, "A", face));
                targets.add(new PadMapping.Target(2, "B", face));
                targets.add(new PadMapping.Target(1024, "X", face));
                targets.add(new PadMapping.Target(2048, "Y", face));
                targets.add(new PadMapping.Target(kL2, "L", shoulderLeft));
                targets.add(new PadMapping.Target(kR2, "R", shoulderRight));
                targets.add(new PadMapping.Target(256, "Z", shoulderRight));
                targets.add(new PadMapping.Target(8, "START", middle));
            } else if (control == 1) {
                // Remote held sideways: 1 and 2 are the core's B and A.
                targets.add(new PadMapping.Target(2, "1", face));
                targets.add(new PadMapping.Target(1, "2", face));
                targets.add(new PadMapping.Target(1024, "A", face));
                targets.add(new PadMapping.Target(2048, "B", face));
                targets.add(new PadMapping.Target(kR2, "흔들기", shoulderRight));
                targets.add(new PadMapping.Target(4, "−", middle));
                targets.add(new PadMapping.Target(DOLPHIN_HOME, "HOME", middle));
                targets.add(new PadMapping.Target(8, "+", middle));
            } else if (control == 2) {
                // Classic controller: L / R are L2 / R2, ZL / ZR are L / R.
                targets.add(new PadMapping.Target(1, "a", face));
                targets.add(new PadMapping.Target(2, "b", face));
                targets.add(new PadMapping.Target(1024, "x", face));
                targets.add(new PadMapping.Target(2048, "y", face));
                targets.add(new PadMapping.Target(kL2, "L", shoulderLeft));
                targets.add(new PadMapping.Target(512, "ZL", shoulderLeft));
                targets.add(new PadMapping.Target(256, "ZR", shoulderRight));
                targets.add(new PadMapping.Target(kR2, "R", shoulderRight));
                targets.add(new PadMapping.Target(4, "−", middle));
                targets.add(new PadMapping.Target(DOLPHIN_HOME, "HOME", middle));
                targets.add(new PadMapping.Target(8, "+", middle));
            } else {
                // Remote + nunchuk: C / Z are X / Y, 1 / 2 START / SELECT, − / + L / R.
                targets.add(new PadMapping.Target(1, "A", face));
                targets.add(new PadMapping.Target(2, "B", face));
                targets.add(new PadMapping.Target(1024, "C", face));
                targets.add(new PadMapping.Target(2048, "Z", face));
                targets.add(new PadMapping.Target(8, "1", face));
                targets.add(new PadMapping.Target(4, "2", face));
                targets.add(new PadMapping.Target(kR2, "흔들기", shoulderRight));
                targets.add(new PadMapping.Target(kL2, "눈차크 흔들기", shoulderLeft));
                targets.add(new PadMapping.Target(512, "−", middle));
                targets.add(new PadMapping.Target(DOLPHIN_HOME, "HOME", middle));
                targets.add(new PadMapping.Target(256, "+", middle));
            }
        } else if (isWiiU()) {
            targets.add(new PadMapping.Target(1024, "X", face));
            targets.add(new PadMapping.Target(1, "A", face));
            targets.add(new PadMapping.Target(2, "B", face));
            targets.add(new PadMapping.Target(2048, "Y", face));
            targets.add(new PadMapping.Target(512, "L", shoulderLeft));
            targets.add(new PadMapping.Target(kL2, "ZL", shoulderLeft));
            targets.add(new PadMapping.Target(256, "R", shoulderRight));
            targets.add(new PadMapping.Target(kR2, "ZR", shoulderRight));
            targets.add(new PadMapping.Target(kL3, "L3", PadMapping.GROUP_STICK_LEFT));
            targets.add(new PadMapping.Target(kR3, "R3", PadMapping.GROUP_STICK_RIGHT));
            targets.add(new PadMapping.Target(4, "−", middle));
            targets.add(new PadMapping.Target(8, "+", middle));
        } else if (isSonyPad()) {
            targets.add(new PadMapping.Target(1024, "△", face));
            targets.add(new PadMapping.Target(1, "○", face));
            targets.add(new PadMapping.Target(2, "×", face));
            targets.add(new PadMapping.Target(2048, "□", face));
            targets.add(new PadMapping.Target(512, isDualShock() ? "L1" : "L", shoulderLeft));
            targets.add(new PadMapping.Target(256, isDualShock() ? "R1" : "R", shoulderRight));
            if (isDualShock()) {
                targets.add(new PadMapping.Target(kL2, "L2", shoulderLeft));
                targets.add(new PadMapping.Target(kR2, "R2", shoulderRight));
                targets.add(new PadMapping.Target(kL3, "L3", PadMapping.GROUP_STICK_LEFT));
                targets.add(new PadMapping.Target(kR3, "R3", PadMapping.GROUP_STICK_RIGHT));
            }
            targets.add(new PadMapping.Target(4, "SELECT", middle));
            targets.add(new PadMapping.Target(8, "START", middle));
        } else if (isN64()) {
            targets.add(new PadMapping.Target(N64_A, "A", face));
            targets.add(new PadMapping.Target(N64_B, "B", face));
            targets.add(new PadMapping.Target(N64_C_UP, "C▲", PadMapping.GROUP_C));
            targets.add(new PadMapping.Target(N64_C_RIGHT, "C▶", PadMapping.GROUP_C));
            targets.add(new PadMapping.Target(N64_C_DOWN, "C▼", PadMapping.GROUP_C));
            targets.add(new PadMapping.Target(N64_C_LEFT, "C◀", PadMapping.GROUP_C));
            targets.add(new PadMapping.Target(N64_L, "L", shoulderLeft));
            targets.add(new PadMapping.Target(N64_Z, "Z", shoulderLeft));
            targets.add(new PadMapping.Target(N64_R, "R", shoulderRight));
            targets.add(new PadMapping.Target(8, "START", middle));
        } else if (isSaturn()) {
            for (int k = 0; k < SATURN_LABELS.length; k++) {
                targets.add(new PadMapping.Target(SATURN_BITS[k], SATURN_LABELS[k], face));
            }
            targets.add(new PadMapping.Target(kL2, "L", shoulderLeft));
            targets.add(new PadMapping.Target(kR2, "R", shoulderRight));
            targets.add(new PadMapping.Target(8, "START", middle));
        } else if (isNaomi()) {
            int count = naomiButtons();
            for (int k = 0; k < count && k < 6; k++) {
                targets.add(new PadMapping.Target(NAOMI_BITS[k], String.valueOf(k + 1), face));
            }
            targets.add(new PadMapping.Target(4, "COIN", middle));
            targets.add(new PadMapping.Target(8, "START", middle));
            targets.add(new PadMapping.Target(kL3, "TEST", middle));
            targets.add(new PadMapping.Target(kR3, "SERVICE", middle));
        } else if (isDreamcast()) {
            targets.add(new PadMapping.Target(1024, "Y", face));
            targets.add(new PadMapping.Target(1, "B", face));
            targets.add(new PadMapping.Target(2, "A", face));
            targets.add(new PadMapping.Target(2048, "X", face));
            targets.add(new PadMapping.Target(kL2, "L", shoulderLeft));
            targets.add(new PadMapping.Target(kR2, "R", shoulderRight));
            targets.add(new PadMapping.Target(8, "START", middle));
        } else if (isMegaDrive()) {
            String[] labels = {"A", "B", "C", "X", "Y", "Z"};
            int[] bits = {2048, 2, 1, 512, 1024, 256};
            for (int k = 0; k < 6; k++) {
                targets.add(new PadMapping.Target(bits[k], labels[k], face));
            }
            targets.add(new PadMapping.Target(4, "MODE", middle));
            targets.add(new PadMapping.Target(8, "START", middle));
        } else if (isSnes() || isDualScreen()) {
            targets.add(new PadMapping.Target(1024, "X", face));
            targets.add(new PadMapping.Target(1, "A", face));
            targets.add(new PadMapping.Target(2, "B", face));
            targets.add(new PadMapping.Target(2048, "Y", face));
            targets.add(new PadMapping.Target(512, "L", shoulderLeft));
            targets.add(new PadMapping.Target(256, "R", shoulderRight));
            targets.add(new PadMapping.Target(4, "SELECT", middle));
            targets.add(new PadMapping.Target(8, "START", middle));
            if (isNds()) {
                targets.add(new PadMapping.Target(kL3, "MIC", middle));
            }
            if (isN3ds()) {
                targets.add(new PadMapping.Target(kMic3ds, "MIC", middle));
            }
        } else if (isPadLayout()) {
            // GBA, NES, Master System, PC Engine: two face buttons, shoulders on the GBA.
            boolean sms = isMasterSystem();
            String[] pair = turboLabels();
            targets.add(new PadMapping.Target(1, sms ? "2" : pair[0], face));
            targets.add(new PadMapping.Target(2, sms ? "1" : pair[1], face));
            if (hasShoulders()) {
                targets.add(new PadMapping.Target(512, "L", shoulderLeft));
                targets.add(new PadMapping.Target(256, "R", shoulderRight));
            }
            if (!sms) {
                targets.add(new PadMapping.Target(4, selectLabel(), middle));
            }
            targets.add(new PadMapping.Target(8, startLabel(), middle));
        } else if (isMsx()) {
            // The MSX joystick: triggers A and B (the keys stay on the screen).
            targets.add(new PadMapping.Target(1, "A", face));
            targets.add(new PadMapping.Target(2, "B", face));
        } else if (isWonderSwan() || isNeoGeoPocket()) {
            targets.add(new PadMapping.Target(1, "A", face));
            targets.add(new PadMapping.Target(2, "B", face));
            targets.add(new PadMapping.Target(8, isWonderSwan() ? "START" : "OPTION", middle));
        } else {
            // Neo Geo and arcade sets: A B C D, with E F on the six-button games.
            String[] labels = {"A", "B", "C", "D", "E", "F"};
            int[] bits = {1, 2, 1024, 2048, 4096, 8192};
            for (int k = 0; k < (isNeoGeoMode() ? 4 : 6); k++) {
                targets.add(new PadMapping.Target(bits[k], labels[k], face));
            }
            targets.add(new PadMapping.Target(4, selectLabel(), middle));
            targets.add(new PadMapping.Target(8, "START", middle));
        }
        targets.add(new PadMapping.Target(PadMapping.DPAD_UP, "▲", PadMapping.GROUP_DPAD));
        targets.add(new PadMapping.Target(PadMapping.DPAD_RIGHT, "▶", PadMapping.GROUP_DPAD));
        targets.add(new PadMapping.Target(PadMapping.DPAD_DOWN, "▼", PadMapping.GROUP_DPAD));
        targets.add(new PadMapping.Target(PadMapping.DPAD_LEFT, "◀", PadMapping.GROUP_DPAD));
        GunSetup gun = gunOn() ? gunSetup() : null;
        if (gun != null) {
            // 건슈팅: the gun's buttons can go on a controller too.
            for (int k = 0; k < gun.labels.length; k++) {
                targets.add(new PadMapping.Target(gun.bits[k], gun.labels[k], middle));
            }
        }
        return targets;
    }

    // Which pad picture the button-setting screen draws.
    private String padSheetStyle() {
        if (isDolphin()) {
            if (!isWiiGame()) return "gc";
            int control = wiiControl();
            return control == 1 ? "wiisw" : control == 2 ? "wiicc" : "wiinc";
        }
        if (isWiiU()) return "wiiu";
        if (isSonyPad()) return "ps";
        if (isSnes()) return "sfc";
        if (isN64()) return "n64";
        if (isMegaDrive()) return "md";
        if (isSaturn()) return "ss";
        if (isNaomi()) return "arcade";
        if (isDreamcast()) return "dc";
        if (this.coreId.equals("gba") || isDualScreen() || isWonderSwan() || isNeoGeoPocket()) return "gba";
        if (isPadLayout()) return "fc";
        return "arcade";
    }

    private String consoleName() {
        switch (this.gameMode) {
            case "gba": return isGameBoy() ? "게임보이" : "GBA";
            case "nds": return "닌텐도 DS";
            case "3ds": return "닌텐도 3DS";
            case "gc": return isWiiGame() ? "Wii" : "게임큐브";
            case "wiiu": return "Wii U";
            case "snes": return "슈퍼패미컴";
            case "nes": return "패미컴";
            case "md": return "메가드라이브";
            case "mcd": return "메가CD";
            case "sms": return "마스터시스템";
            case "pce": return "PC엔진";
            case "n64": return "닌텐도 64";
            case "psp": return "PSP";
            case "ps1": return "PS1";
            case "ps2": return "PS2";
            case "ss": return "새턴";
            case "dc": return "드림캐스트";
            case "naomi": return "Naomi · Atomiswave";
            case "ws": return "원더스완";
            case "ngp": return "네오지오 포켓";
            case "neogeo": return "Neo Geo";
            case "dos": return "DOS";
            default: return "아케이드";
        }
    }

    private LinearLayout createPadMappingRow(Runnable reopen) {
        android.view.InputDevice device = PadMapping.firstController();
        String shown;
        if (device == null) {
            shown = "게임패드 없음";
        } else {
            boolean custom = PadMappingDialog.hasCustom(MainActivity.sharedSettings(this), this.saveBaseName, this.gameMode, PadMapping.descriptor(device));
            shown = (custom ? "바꾼 설정" : "기본") + " · " + device.getName();
        }
        return createSettingRow(RoundIconView.PAD, RoundIconView.COLOR_PAD, "게임패드 버튼 설정", shown, view -> {
            android.view.InputDevice first = PadMapping.firstController();
            if (first == null) {
                Toast.makeText(this, "블루투스나 USB 게임패드를 먼저 연결해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            // With several pads, each has its own table: pick which one first.
            java.util.List<android.view.InputDevice> pads = PlayerPads.controllers();
            if (pads.size() > 1) {
                String[] names = new String[pads.size()];
                for (int k = 0; k < names.length; k++) {
                    int player = consolePlayers() > 1 ? playerPads().playerOf(pads.get(k)) : 0;
                    names[k] = (player + 1) + "P · " + pads.get(k).getName();
                }
                new AppDialog.Builder(this).setTitle("어느 패드를 설정할까요?")
                        .setItems(names, (d, which) -> openPadMapping(pads.get(which)))
                        .setNegativeButton("취소", null).show();
                return;
            }
            openPadMapping(first);
        });
    }

    private void openPadMapping(android.view.InputDevice now) {
        {
            releaseController();
            // The picture is wide: the screen turns landscape while it is open, then goes back.
            final int previous = getRequestedOrientation();
            setRequestedOrientation(6);
            PadMappingDialog.show(this, padTargets(), padDefaults(), this.saveBaseName, this.gameMode, consoleName(), this.gameTitle,
                    padSheetStyle(), now, new PadMappingDialog.Listener() {
                        @Override
                        public void onChanged() {
                            invalidatePadMaps();
                        }

                        @Override
                        public void onClosed() {
                            setRequestedOrientation(previous);
                        }
                    });
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
        int player = playerFor(event.getDevice());
        if (player > 0) {
            otherPlayerMotion(player, event);
            return true;
        }
        float x = padAxis(event, android.view.MotionEvent.AXIS_X);
        float y = padAxis(event, android.view.MotionEvent.AXIS_Y);
        android.util.SparseIntArray table = padMapFor(event.getDevice());
        if (table != null) {
            applyMappedAxes(event, table, x, y);
            return true;
        }
        int hat = directionsFor(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), 0.5f);
        int stick = 0;
        if (isSonyPad() || isN64() || isDreamcast() || hasLeftStick()) {
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

    // ---- "플레이어 · 패드": more controllers, more players (2P-4P) ----
    private static final int[] PLAYER_COLORS = {Color.rgb(37, 99, 235), Color.rgb(225, 29, 72), Color.rgb(22, 163, 74), Color.rgb(245, 158, 11)};
    private static final int COLOR_PLAYERS = Color.rgb(13, 148, 136);
    private PlayerPads playerPads;
    // Players 2-4 (index 1-3): what each one's pad holds.
    private final int[] otherButtons = new int[PlayerPads.MAX];
    private final int[] otherKeyDirections = new int[PlayerPads.MAX];
    private final int[] otherHatDirections = new int[PlayerPads.MAX];
    private final int[] otherStickDirections = new int[PlayerPads.MAX];
    private final int[] otherTriggers = new int[PlayerPads.MAX];
    private final int[] otherAxisButtons = new int[PlayerPads.MAX];
    private LinearLayout playerBadgeRow;

    // How many players the console takes at most with the 멀티탭 as set now.
    private int consolePlayers() {
        if (isMame139()) {
            return 1;
        }
        switch (this.gameMode) {
            case "n64":
            case "gc":
            case "dc":
            case "naomi":
            case "nes":
            case "pce":
            case "neogeo":
            case "arcade":
                return 4;
            case "snes":
            case "md":
            case "ps1":
                return multitapNow() != 0 ? 4 : 2;
            case "ps2":
            case "ss":
            case "sms":
            case "mcd":
                return 2;
            case "msx":
                // 2인용 (포트 2): 0 empty, 1 the second pad (default), 2 the screen pad on port 2.
                return msxInt("port2", 1) == 1 ? 2 : 1;
            default:
                return 1;
        }
    }

    // Super Famicom (Snes9x), Mega Drive (Genesis Plus GX), PS1 (PCSX ReARMed).
    private boolean multitapSupported() {
        return ("snes".equals(this.gameMode) && isSnes()) || ("md".equals(this.gameMode) && "genesis_plus_gx".equals(this.coreId))
                || ("ps1".equals(this.gameMode) && isPs1());
    }

    // 0 off, 1 on (Super Famicom, PS1), 2 Team Player, 3 EA 4-Way Play (Mega Drive); per game.
    private int multitapNow() {
        if (!multitapSupported()) {
            return 0;
        }
        int value = MainActivity.sharedSettings(this).getInt("multitap:" + this.saveBaseName, 0);
        int max = "md".equals(this.gameMode) ? 3 : 1;
        return value >= 0 && value <= max ? value : 0;
    }

    private PlayerPads playerPads() {
        if (this.playerPads == null) {
            this.playerPads = new PlayerPads(MainActivity.sharedSettings(this));
            this.playerPads.setMaxPlayers(consolePlayers());
            this.playerPads.refresh(null);
        }
        return this.playerPads;
    }

    private int playerFor(android.view.InputDevice device) {
        if (device == null || isMame139() || consolePlayers() < 2) {
            return 0;
        }
        return playerPads().playerOf(device);
    }

    // Follows the connected controllers: places new pads, tells the core how many players
    // there are, and (announce) says who joined or whose pad went away.
    private void refreshPlayers(boolean announce) {
        PlayerPads pads = playerPads();
        pads.setMaxPlayers(consolePlayers());
        java.util.List<int[]> joined = new java.util.ArrayList<>();
        java.util.List<Integer> lost = pads.refresh(joined);
        for (int p = 1; p < PlayerPads.MAX; p++) {
            if (pads.devicesOf(p).isEmpty()) {
                clearPlayer(p);
            }
        }
        long handle = this.nativeHandle;
        if (handle != 0 && !isMame139()) {
            NativeGba.setPlayers(handle, pads.players(), multitapNow());
        }
        updatePlayerBadges();
        if (!announce || pads.maxPlayers() < 2) {
            return;
        }
        for (int[] join : joined) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(join[0]);
            if (join[1] > 0 && device != null) {
                Toast.makeText(this, "🎮 " + device.getName() + " 연결됨 → " + (join[1] + 1) + "P", Toast.LENGTH_SHORT).show();
            }
        }
        if (!lost.isEmpty()) {
            java.util.Collections.sort(lost);
            StringBuilder who = new StringBuilder();
            for (int player : lost) {
                who.append(who.length() == 0 ? "" : " · ").append(player + 1).append("P");
            }
            Toast.makeText(this, who + " 패드가 끊겼어요 · 다시 연결하면 같은 자리로 돌아와요", Toast.LENGTH_LONG).show();
            if (this.nativeHandle != 0 && (this.pauseDialog == null || !this.pauseDialog.isShowing()) && !this.holdPause) {
                showGameMenu();
            }
        }
    }

    private void clearPlayer(int player) {
        this.otherButtons[player] = 0;
        this.otherKeyDirections[player] = 0;
        this.otherHatDirections[player] = 0;
        this.otherStickDirections[player] = 0;
        this.otherTriggers[player] = 0;
        this.otherAxisButtons[player] = 0;
        long handle = this.nativeHandle;
        if (handle != 0 && !isMame139()) {
            NativeGba.setPlayerInput(handle, player, 0);
            NativeGba.setPlayerAnalog(handle, player, 0, 0, 0, 0);
        }
    }

    private void sendPlayer(int player) {
        long handle = this.nativeHandle;
        if (handle == 0) {
            return;
        }
        int directions = this.otherKeyDirections[player] | this.otherHatDirections[player] | this.otherStickDirections[player];
        int buttons = this.otherButtons[player] | this.otherTriggers[player] | this.otherAxisButtons[player];
        NativeGba.setPlayerInput(handle, player, (directions & PadMapping.DPAD_BITS) | (buttons & ~PadMapping.DPAD_BITS));
    }

    // A key from 2P-4P's pad: the same buttons as 1P's pad (its own table if it has one).
    private boolean otherPlayerKey(int player, KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int direction;
        int bit;
        android.util.SparseIntArray table = padMapFor(event.getDevice());
        if (table != null) {
            int bits = table.get(keyCode);
            if (bits == 0) {
                return PadMapping.isMappableKey(keyCode);
            }
            direction = bits & PadMapping.DPAD_BITS;
            bit = bits & ~PadMapping.DPAD_BITS;
        } else {
            direction = padDirectionBit(keyCode);
            bit = padButtonBit(keyCode);
        }
        if (direction == 0 && bit == 0) {
            return false;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        if (direction != 0) {
            this.otherKeyDirections[player] = down ? this.otherKeyDirections[player] | direction : this.otherKeyDirections[player] & ~direction;
        }
        if (bit != 0) {
            this.otherButtons[player] = down ? this.otherButtons[player] | bit : this.otherButtons[player] & ~bit;
            if ((this.otherButtons[player] & 12) == 12) {
                // SELECT + START together open the menu, as on 1P's pad.
                openMenuFromController();
                return true;
            }
        }
        sendPlayer(player);
        return true;
    }

    // Sticks, hat and triggers from 2P-4P's pad.
    private void otherPlayerMotion(int player, android.view.MotionEvent event) {
        float x = padAxis(event, android.view.MotionEvent.AXIS_X);
        float y = padAxis(event, android.view.MotionEvent.AXIS_Y);
        float rx = padAxis(event, android.view.MotionEvent.AXIS_Z);
        float ry = padAxis(event, android.view.MotionEvent.AXIS_RZ);
        boolean sticks = isSonyPad() || isN64() || isDreamcast() || hasLeftStick();
        long handle = this.nativeHandle;
        if (sticks && handle != 0) {
            NativeGba.setPlayerAnalog(handle, player, Math.round(x * 32767), Math.round(y * 32767), Math.round(rx * 32767), Math.round(ry * 32767));
        }
        android.util.SparseIntArray table = padMapFor(event.getDevice());
        if (table != null) {
            int directions = 0;
            int buttons = 0;
            for (int k = 0; k < table.size(); k++) {
                int source = table.keyAt(k);
                if (!PadMapping.isAxisSource(source)) {
                    continue;
                }
                int axis = PadMapping.axisOf(source);
                float value = event.getAxisValue(axis);
                if (axis == android.view.MotionEvent.AXIS_LTRIGGER) {
                    value = Math.max(value, event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
                } else if (axis == android.view.MotionEvent.AXIS_RTRIGGER) {
                    value = Math.max(value, event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
                }
                if (PadMapping.axisNegative(source) ? value < -0.6f : value > 0.6f) {
                    directions |= table.valueAt(k) & PadMapping.DPAD_BITS;
                    buttons |= table.valueAt(k) & ~PadMapping.DPAD_BITS;
                }
            }
            this.otherHatDirections[player] = directions;
            this.otherStickDirections[player] = 0;
            this.otherTriggers[player] = 0;
            this.otherAxisButtons[player] = buttons;
        } else {
            this.otherHatDirections[player] = directionsFor(event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), 0.5f);
            this.otherStickDirections[player] = sticks ? 0 : directionsFor(x, y, 0.5f);
            float l2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
            float r2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
            this.otherTriggers[player] = (l2 > 0.5f ? kL2 : 0) | (r2 > 0.5f ? kR2 : 0);
            this.otherAxisButtons[player] = 0;
        }
        sendPlayer(player);
    }

    // 1P 2P 3P 4P in the top corner of the screen while more than one player has a pad.
    private void addPlayerBadges(FrameLayout screenFrame) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(isDolphin() && wiiServer() != 0 ? 34 : 8), dp(8), 0);
        screenFrame.addView(row, params);
        this.playerBadgeRow = row;
        updatePlayerBadges();
    }

    private TextView playerChip(int player, boolean on) {
        TextView chip = new TextView(this);
        chip.setText((player + 1) + "P");
        chip.setTextSize(10.5f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chip.setTextColor(Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(2), dp(6), dp(2));
        chip.setBackground(roundRect(on ? PLAYER_COLORS[player] : Color.argb(200, 75, 85, 99), dp(7)));
        chip.setAlpha(on ? 1.0f : 0.6f);
        return chip;
    }

    private void updatePlayerBadges() {
        LinearLayout row = this.playerBadgeRow;
        if (row == null) {
            return;
        }
        row.removeAllViews();
        PlayerPads pads = this.playerPads;
        if (pads == null || pads.maxPlayers() < 2 || pads.players() < 2) {
            row.setVisibility(View.GONE);
            return;
        }
        for (int p = 0; p < pads.maxPlayers(); p++) {
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.setMargins(p == 0 ? 0 : dp(4), 0, 0, 0);
            row.addView(playerChip(p, p == 0 || !pads.devicesOf(p).isEmpty()), chipParams);
        }
        row.setVisibility(View.VISIBLE);
    }

    private String playersShown() {
        PlayerPads pads = playerPads();
        int count = PlayerPads.controllers().size();
        if (count == 0) {
            return "패드 없음 · " + consolePlayers() + "명까지";
        }
        return "패드 " + count + "개 · " + Math.max(1, pads.players()) + "명";
    }

    private LinearLayout createPlayersRow() {
        return createSettingRow(RoundIconView.PAD, COLOR_PLAYERS, "플레이어 · 패드", playersShown(), view -> showPlayersSheet());
    }

    private static final String[] MULTITAP_NAMES = {"꺼짐", "켜기"};
    private static final String[] MULTITAP_NOTES = {"2명까지 (기본)", "4명까지 · 멀티탭을 지원하는 게임만"};
    private static final String[] MULTITAP_MD_NAMES = {"꺼짐", "세가 팀 플레이어", "EA 4-Way Play"};
    private static final String[] MULTITAP_MD_NOTES = {"2명까지 (기본)", "세가 · 대부분의 4인 게임 (건틀릿 4 · 마이크로 머신 등)", "EA 스포츠 게임 (NBA · NHL · 매든 등)"};

    private String multitapShown() {
        int value = multitapNow();
        if (value == 0) {
            return "꺼짐";
        }
        return ("md".equals(this.gameMode) ? MULTITAP_MD_NAMES[value] : "켜짐") + " (4명까지)";
    }

    private LinearLayout createMultitapRow(Runnable reopen) {
        boolean md = "md".equals(this.gameMode);
        return createSettingRow(RoundIconView.MULTI, COLOR_PLAYERS, "멀티탭", multitapShown(), view -> showChoiceDialog(RoundIconView.MULTI, COLOR_PLAYERS, "멀티탭",
                md ? MULTITAP_MD_NAMES : MULTITAP_NAMES, md ? MULTITAP_MD_NOTES : MULTITAP_NOTES, multitapNow(),
                "3명 이상 같이 하려면 켜세요. 멀티탭을 지원하지 않는 게임에서 켜면 2P가 움직이지 않을 수 있어요.\n이 게임에만 적용돼요.",
                Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용", choice -> {
                    MainActivity.sharedSettings(this).edit().putInt("multitap:" + this.saveBaseName, choice).apply();
                    long handle = this.nativeHandle;
                    if (handle != 0) {
                        NativeGba.setPlayers(handle, playerPads().players(), multitapNow());
                    }
                    refreshPlayers(false);
                    reopen.run();
                }));
    }

    // Who plays as 1P-4P, with 바꾸기 for each place and 순서 다시 정하기.
    private void showPlayersSheet() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        final Runnable[] build = new Runnable[1];
        build[0] = () -> dialog.setContentView(playersPanel(dialog, build[0]));
        build[0].run();
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            this.pauseDialog.dismiss();
        }
        dialog.setOnDismissListener(d -> {
            this.holdPause = false;
            resumeUnlessExiting();
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
    }

    private View playersPanel(android.app.Dialog dialog, Runnable rebuild) {
        refreshPlayers(false);
        PlayerPads pads = playerPads();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(22)));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, RoundIconView.PAD, COLOR_PLAYERS), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("플레이어 · 패드");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(this.gameTitle + " · " + consoleName() + " (" + consolePlayers() + "명까지)");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(sub);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header);
        java.util.List<android.view.InputDevice> controllers = PlayerPads.controllers();
        for (int p = 0; p < pads.maxPlayers(); p++) {
            final int player = p;
            java.util.List<Integer> ids = pads.devicesOf(p);
            StringBuilder names = new StringBuilder();
            for (int id : ids) {
                android.view.InputDevice device = android.view.InputDevice.getDevice(id);
                if (device != null) {
                    names.append(names.length() == 0 ? "" : " · ").append(device.getName());
                }
            }
            boolean empty = p > 0 && ids.isEmpty();
            String name = p == 0 ? (names.length() == 0 ? "터치 버튼" : "터치 버튼 + " + names) : empty ? "—" : names.toString();
            String detail = p == 0 ? "휴대폰 화면" + (ids.isEmpty() ? "" : " · 패드") : empty ? "패드를 하나 더 연결하면 " + (p + 1) + "P가 돼요" : "게임패드";
            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(0);
            slot.setGravity(16);
            slot.setPadding(dp(12), dp(9), dp(12), dp(9));
            android.graphics.drawable.GradientDrawable slotBackground = roundRect(empty ? Color.WHITE : Color.rgb(246, 247, 249), dp(13));
            if (empty) {
                slotBackground.setStroke(dp(1), Theme.line(Color.rgb(203, 213, 225)), dp(4), dp(3));
            }
            slot.setBackground(slotBackground);
            slot.addView(playerChip(p, !empty), new LinearLayout.LayoutParams(dp(30), dp(22)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(1);
            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextSize(13.5f);
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameView.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(nameView);
            TextView detailView = new TextView(this);
            detailView.setText(detail);
            detailView.setTextSize(11.0f);
            detailView.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
            texts.addView(detailView);
            LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            textsParams.setMargins(dp(10), 0, dp(6), 0);
            slot.addView(texts, textsParams);
            TextView action = new TextView(this);
            action.setText(controllers.isEmpty() ? (empty ? "비어 있음" : "") : "바꾸기 ›");
            action.setTextSize(12.5f);
            action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            action.setTextColor(Theme.text(controllers.isEmpty() ? Color.rgb(156, 163, 175) : Color.rgb(37, 99, 235)));
            slot.addView(action);
            if (!controllers.isEmpty()) {
                slot.setOnClickListener(view -> choosePadFor(player, controllers, rebuild));
            }
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(-1, -2);
            slotParams.setMargins(0, dp(8), 0, 0);
            panel.addView(slot, slotParams);
        }
        if (controllers.size() > 1 && pads.maxPlayers() > 1) {
            TextView reorder = new TextView(this);
            reorder.setText("버튼 눌러서 순서 다시 정하기");
            reorder.setTextSize(13.0f);
            reorder.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            reorder.setTextColor(Theme.text(COLOR_PLAYERS));
            reorder.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable outline = roundRect(Color.WHITE, dp(12));
            outline.setStroke(dp(2), Theme.line(COLOR_PLAYERS));
            reorder.setBackground(outline);
            reorder.setOnClickListener(view -> showPlayerOrderPicker(rebuild));
            LinearLayout.LayoutParams reorderParams = new LinearLayout.LayoutParams(-1, dp(42));
            reorderParams.setMargins(0, dp(10), 0, 0);
            panel.addView(reorder, reorderParams);
        }
        TextView note = new TextView(this);
        note.setText(pads.maxPlayers() < 2
                ? (multitapSupported() ? "지금은 2명까지예요. 3명 이상은 \"멀티탭\"을 켜세요." : "이 게임기는 한 명만 해요. 연결한 패드는 모두 1P예요.")
                : "패드 버튼 설정은 패드마다 기억해요. 같은 패드는 다음에도 같은 자리로 들어가요. 자리보다 패드가 많으면 남는 패드는 1P예요.");
        note.setTextSize(11.5f);
        note.setTextColor(Theme.text(Color.rgb(17, 94, 89)));
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        note.setBackground(roundRect(Color.rgb(240, 253, 250), dp(12)));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(0, dp(10), 0, 0);
        panel.addView(note, noteParams);
        TextView close = dialogButton("닫기", COLOR_PLAYERS, Color.WHITE);
        close.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.setMargins(0, dp(12), 0, 0);
        panel.addView(close, closeParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        return scroll;
    }

    // 바꾸기: which pad plays as this player.
    private void choosePadFor(int player, java.util.List<android.view.InputDevice> controllers, Runnable rebuild) {
        String[] names = new String[controllers.size()];
        PlayerPads pads = playerPads();
        for (int k = 0; k < names.length; k++) {
            android.view.InputDevice device = controllers.get(k);
            names[k] = device.getName() + "  (지금 " + (pads.playerOf(device) + 1) + "P)";
        }
        new AppDialog.Builder(this).setTitle((player + 1) + "P가 될 패드").setItems(names, (d, which) -> {
            pads.move(controllers.get(which).getId(), player);
            releaseController();
            refreshPlayers(false);
            rebuild.run();
        }).setNegativeButton("취소", null).show();
    }

    // 순서 다시 정하기: each pad's first button press takes the next place.
    private void showPlayerOrderPicker(Runnable rebuild) {
        final PlayerPads pads = playerPads();
        final int target = Math.min(pads.maxPlayers(), PlayerPads.controllers().size());
        final java.util.List<Integer> order = new java.util.ArrayList<>();
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(18), dp(22), dp(18), dp(18));
        panel.setBackground(roundRect(Color.WHITE, dp(22)));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(0);
        chips.setGravity(Gravity.CENTER);
        panel.addView(chips);
        TextView ask = new TextView(this);
        ask.setTextSize(17.0f);
        ask.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ask.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        ask.setGravity(Gravity.CENTER);
        ask.setPadding(0, dp(14), 0, 0);
        panel.addView(ask);
        TextView hint = new TextView(this);
        hint.setTextSize(12.0f);
        hint.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        panel.addView(hint);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        TextView restart = dialogButton("처음부터", Color.rgb(241, 243, 245), Color.rgb(55, 65, 81));
        TextView done = dialogButton("여기까지", COLOR_PLAYERS, Color.WHITE);
        buttons.addView(restart, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(46), 1.4f);
        doneParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(done, doneParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(16), 0, 0);
        panel.addView(buttons, buttonsParams);
        final Runnable show = () -> {
            chips.removeAllViews();
            for (int p = 0; p < pads.maxPlayers(); p++) {
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
                chipParams.setMargins(p == 0 ? 0 : dp(6), 0, 0, 0);
                TextView chip = playerChip(p, p < order.size());
                if (p == order.size()) {
                    android.graphics.drawable.GradientDrawable next = roundRect(Color.TRANSPARENT, dp(7));
                    next.setStroke(dp(2), Theme.line(PLAYER_COLORS[p]), dp(4), dp(3));
                    chip.setBackground(next);
                    chip.setTextColor(Theme.text(PLAYER_COLORS[p]));
                    chip.setAlpha(1.0f);
                }
                chips.addView(chip, chipParams);
            }
            ask.setText((order.size() + 1) + "P가 될 패드의\n아무 버튼이나 누르세요");
            StringBuilder fixed = new StringBuilder();
            for (int p = 0; p < order.size(); p++) {
                fixed.append(fixed.length() == 0 ? "" : " · ").append(p + 1).append("P");
            }
            hint.setText((order.isEmpty() ? "" : fixed + "는 정해졌어요.\n") + "남은 패드가 없으면 \"여기까지\"를 누르세요.");
        };
        final Runnable finish = () -> {
            if (!order.isEmpty()) {
                pads.setOrder(order);
            }
            releaseController();
            refreshPlayers(false);
            dialog.dismiss();
            rebuild.run();
        };
        restart.setOnClickListener(view -> {
            order.clear();
            show.run();
        });
        done.setOnClickListener(view -> finish.run());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (!isControllerEvent(event)) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && !order.contains(event.getDeviceId())) {
                order.add(event.getDeviceId());
                if (order.size() >= target) {
                    finish.run();
                } else {
                    show.run();
                }
            }
            return true;
        });
        show.run();
        dialog.setContentView(panel);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(380)), -2);
        }
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
        int level = MainActivity.sharedSettings(this).getInt("combo_level", 0);
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

    // 미끄러져 누르기: a finger slid onto another button presses it (all games).
    static boolean slidePressOn(android.content.Context context) {
        return MainActivity.sharedSettings(context).getBoolean("slide_press", true);
    }

    private LinearLayout createSlideRow(Runnable onChanged) {
        String[] names = {"켬 (추천)", "끔"};
        String[] notes = {"손가락을 떼지 않고 옆 버튼으로 미끄러뜨리면 그 버튼이 눌려요 · 실제 패드처럼 굴려 누르기",
                "버튼마다 손을 떼고 다시 눌러야 해요 (예전 방식)"};
        return createSettingRow(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "미끄러져 누르기", slidePressOn(this) ? "켬" : "끔",
                view -> showChoiceDialog(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "미끄러져 누르기", names, notes,
                        slidePressOn(this) ? 0 : 1, "모든 게임에 바로 적용돼요. 화면 버튼(동그란 버튼 · L/R · START 등)끼리 옮겨 가요. 빈 곳으로 미끄러지면 누르던 버튼을 그대로 눌러요.",
                        Color.rgb(255, 247, 232), Color.rgb(138, 90, 0), "적용", choice -> {
                    MainActivity.sharedSettings(this).edit().putBoolean("slide_press", choice == 0).apply();
                    TouchButton.slideEnabled = choice == 0;
                    onChanged.run();
                }));
    }

    private LinearLayout createComboRow(Runnable onChanged) {
        return createSettingRow(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "버튼 사이 동시 입력", COMBO_NAMES[comboLevel()],
                view -> showChoiceDialog(RoundIconView.COMBO, RoundIconView.COLOR_COMBO, "버튼 사이 동시 입력", COMBO_NAMES, COMBO_NOTES,
                        comboLevel(), "모든 게임에 바로 적용돼요.\n손가락 두 개로 각각 누르는 건 설정과 상관없이 항상 돼요.",
                        Color.rgb(255, 247, 232), Color.rgb(138, 90, 0), "적용", choice -> {
                    MainActivity.sharedSettings(this).edit().putInt("combo_level", choice).apply();
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
        return !isPs2() && !isDreamcast() && !isSaturn() && !isN3ds() && !isWiiU() && !isVita() && !isDolphin() && !isMame139() && !stvBoard();
    }

    private Boolean stvBoard;

    // Sega ST-V arcade games (BIOS stvbios): MAME takes their states, but a restored one
    // leaves a black screen that doesn't move. They get no automatic quick save (always
    // start from the beginning), no rewind, and a word before a quick save.
    private boolean stvBoard() {
        if (this.stvBoard == null) {
            boolean stv = false;
            if (this.romPath != null && this.coreId.startsWith("mame") && !isMame139()) {
                String name = new File(this.romPath).getName().toLowerCase(Locale.US);
                if (name.endsWith(".zip")) {
                    stv = RomsetIndex.usesBios(this, name.substring(0, name.length() - 4), "stvbios");
                }
            }
            this.stvBoard = stv;
        }
        return this.stvBoard;
    }


    // Before a quick save on an ST-V game: once per game, a word on what loading it does.
    private void confirmStvSave(Runnable save) {
        String key = "stv_save_warned:" + this.saveBaseName;
        if (!stvBoard() || MainActivity.sharedSettings(this).getBoolean(key, false)) {
            save.run();
            return;
        }
        android.widget.CheckBox again = new android.widget.CheckBox(this);
        again.setText("이 게임은 다시 묻지 않기");
        again.setTextSize(12.5f);
        again.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
        again.setChecked(true);
        new AppDialog.Builder(this).setTitle("빠른 저장")
                .setMessage("세가 ST-V 기판 게임은 저장한 장면을 불러오면 화면이 멈출 수 있어요. 그럴 땐 위쪽 재시작을 누르면 다시 켜져요.\n되감기도 같은 이유로 이 게임에서는 꺼져 있어요.")
                .setView(again)
                .setNegativeButton("취소", null)
                .setPositiveButton("그래도 저장", (d, w) -> {
                    if (again.isChecked()) {
                        MainActivity.sharedSettings(this).edit().putBoolean(key, true).apply();
                    }
                    save.run();
                }).show();
    }

    private boolean rewindOn() {
        return rewindSupported() && MainActivity.sharedSettings(this).getBoolean("rewind_enabled", false);
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
            background.setColor(Theme.bg(Color.argb(220, 76, 29, 149)));
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
    // "버튼 꾸미기" in force for this console or game and screen direction.
    private ButtonStyle buttonStyle = new ButtonStyle();
    // The game screen's frame, where the 버튼 꾸미기 window sits so the buttons stay in view.
    private View screenArea;

    private static boolean isLayoutControl(View view) {
        return view instanceof TouchButton || view instanceof DPadView || view instanceof AnalogStickView
                || view instanceof ArcadeJoystickView || view instanceof SpeedButtonView || view instanceof RewindButtonView
                || view instanceof WiiuPadWindowView || view instanceof VmuScreenView
                || (view instanceof DosControls.Item && ((DosControls.Item) view).itemKey() != null);
    }

    // Saved per console, screen direction and screen style.
    private String layoutKey() {
        return "layout:" + layoutConsole() + ":" + (this.landscapeMode ? "L" : "P") + ":" + (isFullScreenStyle() ? "full" : "frame");
    }

    private String layoutConsole() {
        return isGameGear() ? "gg" : isMasterSystem() ? "sms" : isGameBoy() ? "gb" : this.coreId;
    }

    // ---- 꾸미기 프리셋 ----
    private View undoBar;

    private void openPresetDialog() {
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            this.pauseDialog.dismiss();
        }
        String[] summary = {
                ClearShellView.STYLE_NAMES[shellStyle()],
                PhotoBackground.rendered(this, this.gameMode, this.landscapeMode) != null ? "있음" : "없음",
                PhotoBackground.buttonAlpha(this, this.gameMode) + "%",
                buttonStyleSummary(),
                hasLayoutEdits() ? "바꾼 배치" : "기본 배치",
                filterName(screenFilter()),
        };
        PresetDialog presets = new PresetDialog(this, consoleName(), this.landscapeMode, summary, presetListener());
        this.presetDialog = presets;
        presets.show(screenRectInWindow());
    }

    private PresetDialog presetDialog;
    private static final int REQUEST_PRESET_FILE = 3103;

    private PresetDialog.Listener presetListener() {
        return new PresetDialog.Listener() {
            @Override
            public Presets.Preset onSave(String id, String name, int scope, boolean[] items) throws java.io.IOException {
                return Presets.capture(GameActivity.this, id, name, gameMode, consoleName(), layoutConsole(), landscapeMode, scope, items);
            }

            @Override
            public void onApply(Presets.Preset preset) {
                applyPreset(preset);
            }

            @Override
            public void onShare(Presets.Preset preset) {
                sharePreset(preset);
            }

            @Override
            public void onImportFile() {
                // The window closed; the game stays held until the picker comes back.
                holdPause = true;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/zip", "application/x-mepreset"});
                // Opens in 다운로드/Mini Emulator, where "파일로 저장" puts the files.
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                                "primary:" + android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER));
                try {
                    startActivityForResult(intent, REQUEST_PRESET_FILE);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(GameActivity.this, "파일 선택 앱이 없어요.", Toast.LENGTH_SHORT).show();
                    holdPause = false;
                    resumeUnlessExiting();
                }
            }

            @Override
            public void onClosed() {
                presetDialog = null;
                if (!holdPause) return;
                holdPause = false;
                resumeUnlessExiting();
            }
        };
    }

    // "파일로 저장": straight into 다운로드/Mini Emulator, where "파일에서 가져오기" opens.
    private void sharePreset(Presets.Preset preset) {
        try {
            android.net.Uri uri = Presets.exportFile(this, preset);
            String name = Presets.fileName(preset);
            if ("content".equals(uri.getScheme())) {
                try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                        name = cursor.getString(0);
                    }
                } catch (RuntimeException ignored) {
                    // The requested name will do.
                }
                Toast.makeText(this, "다운로드 › " + MainActivity.DOWNLOAD_FOLDER + " 폴더에 저장했어요\n" + name, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "파일로 저장했어요: " + uri.getPath(), Toast.LENGTH_LONG).show();
            }
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, "저장하지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importPresetFile(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("파일을 열 수 없어요");
            Presets.Preset preset = Presets.importZip(this, in);
            this.holdPause = true;
            PresetDialog presets = new PresetDialog(this, consoleName(), this.landscapeMode, null, presetListener());
            this.presetDialog = presets;
            presets.showImport(preset);
            presets.show(screenRectInWindow());
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, "가져오지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            this.holdPause = false;
            resumeUnlessExiting();
        }
    }

    /** Keeps the current look as the undo preset, writes the preset's settings and rebuilds. */
    private void applyPreset(Presets.Preset preset) {
        boolean undo = Presets.UNDO_ID.equals(preset.id);
        try {
            if (!undo) {
                Presets.capture(this, Presets.UNDO_ID, "되돌리기용", this.gameMode, consoleName(), layoutConsole(), this.landscapeMode,
                        Presets.SCOPE_BOTH, new boolean[]{true, true, true, true, true, true});
            }
            boolean[] orientations = {preset.hasOrientation(false), preset.hasOrientation(true)};
            String skipped = Presets.apply(this, preset, this.gameMode, layoutConsole(), orientations);
            if (undo) {
                Presets.delete(this, Presets.UNDO_ID);
            }
            rebuildContent();
            applyScreenFilterToNative(screenFilter());
            updateScreenFilters();
            Toast.makeText(this, (undo ? "적용 전 모습으로 되돌렸어요." : "\"" + preset.name + "\"을 " + consoleName() + "에 적용했어요.")
                    + (skipped != null ? "\n" + skipped : ""), Toast.LENGTH_SHORT).show();
            if (!undo) {
                showUndoBar();
            }
        } catch (java.io.IOException e) {
            Toast.makeText(this, "적용하지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // A strip at the bottom for ten seconds: "되돌리기" brings the previous look back.
    // ---- 게임패드 자동 전환: a controller hides the touch controls and grows the picture ----
    private android.hardware.input.InputManager.InputDeviceListener padListener;
    private boolean padMode;
    private boolean padLeaveCancelled;

    private void registerPadListener() {
        android.hardware.input.InputManager manager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (manager == null) return;
        this.padListener = new android.hardware.input.InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                getWindow().getDecorView().postDelayed(() -> onPadsChanged(), 300);
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                getWindow().getDecorView().postDelayed(() -> onPadsChanged(), 300);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
            }
        };
        manager.registerInputDeviceListener(this.padListener, new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    // ---- 발열 단계: the system's thermal status (Android 10+), shown in the performance
    // readout and acted on per the 뜨거워지면 setting ----
    private Object thermalListener;
    private boolean heatWarned;
    private boolean heatRestWarned;
    private static final String[] HEAT_ACTION_NAMES = {"알려 주기", "자동으로 균형", "그대로 두기"};
    private static final String[] HEAT_ACTION_NOTES = {
            "\"뜨거움\" 단계가 되면 위에 알림. 한 게임에 한 번만",
            "알림 없이 균형으로 낮추고 짧게 알려요. 직접 성능을 고른 게임도 포함",
            "아무것도 하지 않아요"};

    private int heatAction() {
        int value = MainActivity.sharedSettings(this).getInt("heat_action", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private void registerThermalListener() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return;
        }
        android.os.PowerManager manager = getSystemService(android.os.PowerManager.class);
        if (manager == null) {
            return;
        }
        android.os.PowerManager.OnThermalStatusChangedListener listener = this::onThermalStatus;
        try {
            manager.addThermalStatusListener(getMainExecutor(), listener);
            this.thermalListener = listener;
        } catch (RuntimeException e) {
            // No thermal service: the readout shows temperatures only.
        }
    }

    private void unregisterThermalListener() {
        if (android.os.Build.VERSION.SDK_INT < 29 || this.thermalListener == null) {
            return;
        }
        android.os.PowerManager manager = getSystemService(android.os.PowerManager.class);
        if (manager != null) {
            try {
                manager.removeThermalStatusListener((android.os.PowerManager.OnThermalStatusChangedListener) this.thermalListener);
            } catch (RuntimeException e) {
                // Already gone.
            }
        }
        this.thermalListener = null;
    }

    // 0 보통, 1 약간, 2 뜨거움 (MODERATE), 3 심각 (SEVERE and above).
    private int thermalStatus = -1;

    private void onThermalStatus(int status) {
        this.thermalStatus = status;
        int level = Math.max(0, Math.min(3, status));
        PerfOverlayView.thermalLevel = level;
        if (this.destroyed || this.nativeHandle == 0 || heatAction() == 2) {
            return;
        }
        if (level >= 2 && powerMode() == 0 && !this.heatWarned) {
            this.heatWarned = true;
            if (heatAction() == 1) {
                setPowerModeNow(1);
                showHeatBar("폰이 뜨거워져서 균형으로 바꿨어요", "느려지면 설정 › 전원 모드에서 성능으로 되돌리세요", null, null);
            } else {
                showHeatBar("폰이 뜨거워지고 있어요", "휴대폰이 곧 스스로 속도를 낮출 수 있어요. 균형으로 바꾸면 덜 뜨거워져요", "균형으로 바꾸기", () -> {
                    setPowerModeNow(1);
                    Toast.makeText(this, "균형으로 바꿨어요.", Toast.LENGTH_SHORT).show();
                });
            }
        } else if (level >= 3 && !this.heatRestWarned) {
            this.heatRestWarned = true;
            showHeatBar("폰이 많이 뜨거워요", "잠시 쉬어 주세요. 휴대폰이 스스로 속도를 크게 낮출 수 있어요", null, null);
        }
    }

    // An orange notice over the top of the screen; the game keeps running. With an action
    // it has two buttons and stays 12 seconds, otherwise 7.
    private void showHeatBar(String title, String detail, String actionText, Runnable action) {
        hideHeatBanner();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(14), dp(12), dp(12), dp(12));
        bar.setBackground(roundRect(Color.rgb(255, 247, 237), dp(18)));
        bar.setElevation(dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new RoundIconView(this, RoundIconView.PERF, HEAT_ORANGE), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(sheetText(title, 13.5f, Color.rgb(154, 52, 18), true));
        texts.addView(sheetText(detail, 11.5f, Color.rgb(154, 52, 18), false));
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(10), 0, 0, 0);
        top.addView(texts, textsParams);
        bar.addView(top);
        if (action != null) {
            LinearLayout buttons = new LinearLayout(this);
            TextView later = dialogButton("괜찮아요", Color.WHITE, Color.rgb(154, 52, 18));
            later.setTextSize(12.5f);
            later.setOnClickListener(view -> hideHeatBanner());
            buttons.addView(later, new LinearLayout.LayoutParams(0, dp(38), 1.0f));
            TextView act = dialogButton(actionText, HEAT_ORANGE, Color.WHITE);
            act.setTextSize(12.5f);
            act.setOnClickListener(view -> {
                hideHeatBanner();
                action.run();
            });
            LinearLayout.LayoutParams actParams = new LinearLayout.LayoutParams(0, dp(38), 1.4f);
            actParams.setMargins(dp(8), 0, 0, 0);
            buttons.addView(act, actParams);
            bar.addView(buttons, marginTop(-1, -2, 10));
        } else {
            bar.setOnClickListener(view -> hideHeatBanner());
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        params.setMargins(dp(12), dp(22), dp(12), 0);
        addContentView(bar, params);
        this.heatBanner = bar;
        bar.postDelayed(() -> {
            if (this.heatBanner == bar) hideHeatBanner();
        }, action != null ? 12000 : 7000);
    }

    private LinearLayout createHeatActionRow() {
        LinearLayout row = createSettingRow(RoundIconView.PERF, HEAT_ORANGE, "뜨거워지면", HEAT_ACTION_NAMES[heatAction()], null);
        final TextView value = (TextView) row.getChildAt(row.getChildCount() - 1);
        row.setOnClickListener(view -> showChoiceDialog(RoundIconView.PERF, HEAT_ORANGE, "뜨거워지면", HEAT_ACTION_NAMES, HEAT_ACTION_NOTES,
                heatAction(), "모든 게임에 같이 적용돼요. 휴대폰이 알려 주는 발열 단계(보통 · 약간 · 뜨거움 · 심각)를 써요. 성능 표시를 켜면 온도 옆에 단계 색이 보여요.",
                Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용", choice -> {
            MainActivity.sharedSettings(this).edit().putInt("heat_action", choice).apply();
            value.setText(HEAT_ACTION_NAMES[choice] + "  ›");
        }));
        return row;
    }

    // ---- DS microphone: the MIC button (L3) and the phone's mic. The 3DS (0.0.370) has its
    // own MIC button (kMic3ds) and settings; there the app decides when the game hears the
    // phone's mic (or the blowing sound), while the engine only asks for samples. ----
    private static final int REQUEST_MIC = 4101;
    private static final String[] MIC_INPUT_NAMES = {"실제 마이크", "후 불기 소리 (내장)", "끔"};
    private static final String[] MIC_INPUT_NOTES = {
            "휴대폰 마이크로 말하거나 불어요. 닌텐독스처럼 목소리를 알아듣는 게임은 이걸로",
            "마이크 없이 MIC 버튼만 누르면 \"후\" 부는 소리가 들어가요. 촛불 끄기 · 바람 불기용",
            "게임에 아무 소리도 넣지 않아요"};
    private static final String[] MIC_BUTTON_NAMES = {"누르는 동안", "눌러서 켜고 끄기", "항상 켜기"};
    private static final String[] MIC_BUTTON_NOTES = {
            "MIC 버튼을 누르고 있는 동안만 (기본)",
            "한 번 누르면 켜지고, 다시 누르면 꺼져요",
            "버튼 없이 게임 중 계속 켜 둬요 · 배터리를 조금 더 써요"};
    private final MicCapture micCapture = new MicCapture();
    private boolean micButtonDown;
    private boolean micToggled;
    private boolean micAsking;
    private boolean micRefused;
    private View micBadge;
    private MicLevelView micLevel;
    private TextView micBadgeText;
    private final Runnable micPoll = this::pollMic;

    private String micKeyPrefix() {
        return isN3ds() ? "n3ds_mic_" : "ds_mic_";
    }

    private int dsMicInput() {
        int value = MainActivity.sharedSettings(this).getInt(micKeyPrefix() + "input", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private int dsMicButton() {
        int value = MainActivity.sharedSettings(this).getInt(micKeyPrefix() + "button", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private void onMicButton(boolean down) {
        if ((!isNds() && !isN3ds()) || down == this.micButtonDown) {
            return;
        }
        this.micButtonDown = down;
        if (down) {
            this.micToggled = !this.micToggled;
        }
    }

    // Whether the game hears something now, as the core's own MIC button mode decides it.
    private boolean micWanted() {
        switch (dsMicButton()) {
            case 1: return this.micToggled;
            case 2: return true;
            default: return this.micButtonDown;
        }
    }

    private void startMicPoll() {
        if ((!isNds() && !isN3ds()) || this.destroyed || this.nativeHandle == 0) {
            return;
        }
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(this.micPoll);
        decor.post(this.micPoll);
    }

    private void stopMicPoll() {
        getWindow().getDecorView().removeCallbacks(this.micPoll);
        this.micCapture.stop();
        showMicBadge(false, 0f);
    }

    // Every 50 ms while a DS game runs: records only while the core has the mic on, and
    // shows "듣는 중" with the level.
    private void pollMic() {
        if (this.destroyed || !this.activityResumed || this.nativeHandle == 0) {
            return;
        }
        int input = dsMicInput();
        boolean paused = this.pauseDialog != null && this.pauseDialog.isShowing();
        // The DS's core turns its mic on itself (L3); the 3DS's game only samples, and the
        // MIC button says when it hears something.
        boolean n3ds = isN3ds();
        if (n3ds && input == 1) {
            boolean on = micWanted() && !paused;
            this.micCapture.stop();
            if (on && NativeGba.isDsMicOn()) {
                pushBlowSound();
            }
            showMicBadge(on, on ? 0.75f : 0f);
        } else if (input == 0) {
            boolean on = NativeGba.isDsMicOn() && !paused && (!n3ds || micWanted());
            if (on && !this.micCapture.isRunning()) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    if (!this.micCapture.start()) {
                        Toast.makeText(this, "마이크를 열지 못했어요. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.", Toast.LENGTH_SHORT).show();
                    }
                } else if (!this.micAsking && !this.micRefused) {
                    askMicPermission();
                }
            } else if (!on && this.micCapture.isRunning()) {
                this.micCapture.stop();
            }
            showMicBadge(this.micCapture.isRunning(), this.micCapture.level());
        } else if (input == 1) {
            this.micCapture.stop();
            boolean on = micWanted() && !paused;
            showMicBadge(on, on ? 0.75f : 0f);
        } else {
            this.micCapture.stop();
            showMicBadge(false, 0f);
        }
        getWindow().getDecorView().postDelayed(this.micPoll, 50);
    }

    // 3DS "후 불기 소리": 50 ms of breathy noise (low-passed white noise at 44.1 kHz), the
    // poll's period, into the microphone queue.
    private final short[] blowSamples = new short[2205];
    private final java.util.Random blowNoise = new java.util.Random();
    private float blowState;

    private void pushBlowSound() {
        for (int k = 0; k < this.blowSamples.length; k++) {
            float white = this.blowNoise.nextFloat() * 2f - 1f;
            this.blowState += (white - this.blowState) * 0.35f;
            this.blowSamples[k] = (short) Math.max(-32767, Math.min(32767, Math.round(this.blowState * 26000f)));
        }
        NativeGba.pushMic(this.blowSamples, this.blowSamples.length);
    }

    private void showMicBadge(boolean shown, float level) {
        if (!shown) {
            if (this.micBadge != null && this.micBadge.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) this.micBadge.getParent()).removeView(this.micBadge);
            }
            this.micBadge = null;
            return;
        }
        if (this.micBadge == null || this.micBadge.getParent() == null) {
            LinearLayout badge = new LinearLayout(this);
            badge.setOrientation(LinearLayout.HORIZONTAL);
            badge.setGravity(Gravity.CENTER_VERTICAL);
            badge.setPadding(dp(14), dp(6), dp(14), dp(6));
            badge.setBackground(roundRect(Color.argb(235, 219, 39, 119), dp(18)));
            badge.setElevation(dp(6));
            TextView text = sheetText("", 12.0f, Color.WHITE, true);
            badge.addView(text);
            MicLevelView meter = new MicLevelView(this);
            LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(dp(60), dp(20));
            meterParams.setMargins(dp(8), 0, 0, 0);
            badge.addView(meter, meterParams);
            // Touches go through to the game.
            badge.setClickable(false);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.setMargins(0, dp(this.landscapeMode ? 12 : TOP_BAR_DP + 4), 0, 0);
            addContentView(badge, params);
            this.micBadge = badge;
            this.micBadgeText = text;
            this.micLevel = meter;
        }
        this.micBadgeText.setText(dsMicInput() == 1 ? "🎤 후 부는 중" : "🎤 듣는 중");
        this.micLevel.setLevel(level);
    }

    // The first time the game wants the phone's mic: what it is for, then the system's own
    // permission request.
    private void askMicPermission() {
        this.micAsking = true;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        panel.addView(sheetHeader(RoundIconView.PERF, Color.rgb(219, 39, 119), "마이크를 쓸까요?", this.gameTitle + (isN3ds() ? " · 3DS" : " · DS"), null));
        TextView body = sheetText("이 게임은 목소리나 바람(후 불기)으로 하는 장면이 있어요. MIC 버튼을 누르고 있는 동안만 소리를 게임에 넘겨요. 녹음하거나 어디에 보내지 않아요.",
                13.0f, Color.rgb(55, 65, 81), false);
        body.setLineSpacing(0, 1.2f);
        panel.addView(body, marginTop(-1, -2, 12));
        TextView hint = sheetText("다음에 휴대폰이 마이크 권한을 물어요. \"앱 사용 중에만 허용\"을 눌러 주세요.", 11.5f, Color.rgb(157, 23, 77), false);
        hint.setPadding(dp(12), dp(10), dp(12), dp(10));
        hint.setBackground(roundRect(Color.rgb(253, 242, 248), dp(12)));
        panel.addView(hint, marginTop(-1, -2, 10));
        LinearLayout buttons = new LinearLayout(this);
        TextView later = dialogButton("나중에", Color.rgb(241, 242, 244), Color.rgb(55, 65, 81));
        buttons.addView(later, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        TextView allow = dialogButton("마이크 켜기", Color.rgb(219, 39, 119), Color.WHITE);
        LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0, dp(46), 1.6f);
        allowParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(allow, allowParams);
        panel.addView(buttons, marginTop(-1, -2, 14));
        final android.app.Dialog dialog = cardDialog(panel, 0);
        final boolean[] asked = {false};
        later.setOnClickListener(view -> dialog.dismiss());
        allow.setOnClickListener(view -> {
            asked[0] = true;
            dialog.dismiss();
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        });
        dialog.setOnDismissListener(d -> {
            if (!asked[0]) {
                // 나중에: not asked again until the next game.
                this.micAsking = false;
                this.micRefused = true;
            }
        });
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MIC) {
            return;
        }
        this.micAsking = false;
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            this.micRefused = true;
            Toast.makeText(this, "마이크 권한이 없어서 게임에 소리가 들어가지 않아요. 일시정지 › 게임기 › 마이크 소리에서 \"후 불기 소리\"로 바꿀 수 있어요.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout createMicInputRow() {
        LinearLayout row = createSettingRow(RoundIconView.PERF, Color.rgb(219, 39, 119), "마이크 소리", MIC_INPUT_NAMES[dsMicInput()], null);
        final TextView value = (TextView) row.getChildAt(row.getChildCount() - 1);
        row.setOnClickListener(view -> showChoiceDialog(RoundIconView.PERF, Color.rgb(219, 39, 119), "마이크 소리", MIC_INPUT_NAMES, MIC_INPUT_NOTES,
                dsMicInput(), "모든 " + (isN3ds() ? "3DS" : "DS") + " 게임에 같이 적용 · 바로 바뀌어요.\n게임 화면의 MIC 버튼(또는 게임패드에 붙인 버튼)을 누를 때 들어가요.",
                Color.rgb(253, 242, 248), Color.rgb(157, 23, 77), "적용", choice -> {
            MainActivity.sharedSettings(this).edit().putInt(micKeyPrefix() + "input", choice).apply();
            if (isNds()) {
                NativeGba.setDsMic(choice, dsMicButton());
            }
            if (choice == 0) {
                this.micRefused = false;
            }
            value.setText(MIC_INPUT_NAMES[choice] + "  ›");
        }));
        return row;
    }

    private LinearLayout createMicButtonRow() {
        LinearLayout row = createSettingRow(RoundIconView.PERF, Color.rgb(219, 39, 119), "MIC 버튼 방식", MIC_BUTTON_NAMES[dsMicButton()], null);
        final TextView value = (TextView) row.getChildAt(row.getChildCount() - 1);
        row.setOnClickListener(view -> showChoiceDialog(RoundIconView.PERF, Color.rgb(219, 39, 119), "MIC 버튼 방식", MIC_BUTTON_NAMES, MIC_BUTTON_NOTES,
                dsMicButton(), "모든 " + (isN3ds() ? "3DS" : "DS") + " 게임에 같이 적용 · 바로 바뀌어요.",
                Color.rgb(253, 242, 248), Color.rgb(157, 23, 77), "적용", choice -> {
            MainActivity.sharedSettings(this).edit().putInt(micKeyPrefix() + "button", choice).apply();
            if (isNds()) {
                NativeGba.setDsMic(dsMicInput(), choice);
            }
            this.micToggled = false;
            value.setText(MIC_BUTTON_NAMES[choice] + "  ›");
        }));
        return row;
    }

    private void unregisterPadListener() {
        android.hardware.input.InputManager manager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (manager != null && this.padListener != null) {
            manager.unregisterInputDeviceListener(this.padListener);
        }
        this.padListener = null;
    }

    private boolean padPref(String key) {
        return MainActivity.sharedSettings(this).getBoolean(key, true);
    }

    /** 0 follows the general setting, 1 always shows the touch controls, 2 always hides them. */
    private int padAutoForGame() {
        return MainActivity.sharedSettings(this).getInt("pad_auto:" + this.saveBaseName, 0);
    }

    private boolean padAutoHides() {
        int game = padAutoForGame();
        return game == 2 || (game == 0 && padPref("pad_auto_hide"));
    }

    private void onPadsChanged() {
        if (!this.destroyed) {
            refreshPlayers(true);
        }
        if (this.destroyed || isPadScreen() || isMame139()) return;
        boolean connected = PadMapping.firstController() != null;
        if (connected && !this.padMode) {
            this.padLeaveCancelled = true;
            enterPadMode(true);
        } else if (!connected && this.padMode) {
            if (padPref("pad_auto_restore")) {
                scheduleLeavePadMode();
            }
        }
    }

    private void enterPadMode(boolean announce) {
        if (!padAutoHides() || this.layoutControls.isEmpty()) return;
        this.padMode = true;
        setTouchControlsShown(false);
        applyButtonStyle(this.buttonStyle);
        if (announce && padPref("pad_auto_toast")) {
            android.view.InputDevice device = PadMapping.firstController();
            showActionBar("🎮 " + (device != null ? device.getName() : "게임패드") + " 연결 · 터치 버튼을 숨겼어요", "터치 보이기", this::leavePadModeNow);
        }
    }

    private void leavePadModeNow() {
        if (!this.padMode) return;
        this.padMode = false;
        setTouchControlsShown(true);
        applyButtonStyle(this.buttonStyle);
    }

    private void scheduleLeavePadMode() {
        this.padLeaveCancelled = false;
        if (padPref("pad_auto_toast")) {
            showActionBar("🎮 패드가 빠졌어요 · 터치 버튼으로 돌아갑니다", "취소", () -> this.padLeaveCancelled = true);
        }
        getWindow().getDecorView().postDelayed(() -> {
            if (!this.padLeaveCancelled && PadMapping.firstController() == null) {
                hideUndoBar();
                leavePadModeNow();
            }
        }, 3000);
    }

    // Everything in the layout editor's list but the screen, and the combo zones between
    // the face buttons; the top icons and the DS touch screen stay.
    private void setTouchControlsShown(boolean shown) {
        float alpha = shellStyle() == ClearShellView.STYLE_PHOTO ? 1.0f - PhotoBackground.buttonAlpha(this, this.gameMode) / 100f : 1.0f;
        for (View view : this.layoutControls) {
            if (view == this.screenArea) continue;
            if (shown) {
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(alpha).setDuration(180).start();
            } else {
                view.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                    if (this.padMode) view.setVisibility(View.INVISIBLE);
                }).start();
            }
        }
        for (android.view.ViewGroup parent : this.comboParents) {
            for (int k = 0; k < parent.getChildCount(); k++) {
                View child = parent.getChildAt(k);
                if (child instanceof ComboZoneView) {
                    child.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
                }
            }
        }
    }

    // How much bigger the picture goes while the controls are hidden: to the phone's width
    // in portrait (top edge kept), to the phone in landscape (centred). 1 when off.
    private float padGrowFor(View screen, float scaleX, float scaleY) {
        if (!this.padMode || !padPref("pad_auto_grow") || screen.getWidth() == 0) return 1.0f;
        View root = screen.getRootView();
        if (root == null || root.getWidth() == 0) return 1.0f;
        float widthFit = root.getWidth() / (screen.getWidth() * scaleX);
        if (!this.landscapeMode) {
            return Math.max(1.0f, Math.min(1.6f, widthFit));
        }
        float heightFit = root.getHeight() / (screen.getHeight() * scaleY);
        return Math.max(1.0f, Math.min(1.5f, Math.min(widthFit, heightFit)));
    }

    private void showPadAutoSheet(Runnable reopen) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(16), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("게임패드 자동 전환");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        android.view.InputDevice device = PadMapping.firstController();
        TextView sub = new TextView(this);
        sub.setText(device != null ? device.getName() + " 연결됨" : "연결된 게임패드 없음 · 꽂으면 아래 설정대로 바뀌어요");
        sub.setTextSize(11.5f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        panel.addView(sub);
        panel.addView(shapeSection("꽂으면 자동으로"));
        panel.addView(shapeToggle("터치 버튼 숨기기", "방향키 · 버튼 · 스틱이 사라져요. 위쪽 저장 · 메뉴 아이콘은 남아요", padPref("pad_auto_hide"), on -> {
            prefs.edit().putBoolean("pad_auto_hide", on).apply();
            onPadModeSettingChanged();
        }));
        panel.addView(shapeToggle("화면 키우기", "버튼이 비운 자리까지 게임 화면을 넓혀요 (세로: 위쪽 고정, 가로: 가운데)", padPref("pad_auto_grow"), on -> {
            prefs.edit().putBoolean("pad_auto_grow", on).apply();
            if (this.padMode) applyButtonStyle(this.buttonStyle);
        }));
        panel.addView(shapeToggle("빼면 원래 배치로 돌아오기", "끄면 알림의 \"터치 보이기\"나 다음 실행 때 돌아와요", padPref("pad_auto_restore"), on -> prefs.edit().putBoolean("pad_auto_restore", on).apply()));
        panel.addView(shapeToggle("전환할 때 알림 띄우기", "", padPref("pad_auto_toast"), on -> prefs.edit().putBoolean("pad_auto_toast", on).apply()));
        panel.addView(shapeSection("이 게임만"));
        LinearLayout chips = new LinearLayout(this);
        final TextView[] chipViews = new TextView[3];
        String[] names = {"기본 설정 따름", "항상 터치 보이기", "항상 숨기기"};
        for (int i = 0; i < 3; i++) {
            final int value = i;
            TextView chip = shapeChip(names[i], padAutoForGame() == i);
            chip.setOnClickListener(view -> {
                if (value == 0) prefs.edit().remove("pad_auto:" + this.saveBaseName).apply();
                else prefs.edit().putInt("pad_auto:" + this.saveBaseName, value).apply();
                for (int k = 0; k < 3; k++) setShapeChip(chipViews[k], k == value);
                onPadModeSettingChanged();
            });
            chipViews[i] = chip;
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
            chipParams.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            chips.addView(chip, chipParams);
        }
        panel.addView(chips);
        TextView note = new TextView(this);
        note.setText("버튼 없는 \"게임패드 모드\" 화면 모드와는 별개예요. 평소엔 터치로, 패드를 꽂았을 때만 큰 화면으로 쓰고 싶을 때 켜 두세요.");
        note.setTextSize(10.5f);
        note.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        note.setPadding(dp(2), dp(8), 0, 0);
        panel.addView(note);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        TextView done = dialogButton("완료", Color.rgb(225, 29, 72), Color.WHITE);
        done.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, dp(46));
        doneParams.setMargins(0, dp(14), 0, 0);
        panel.addView(done, doneParams);
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            this.pauseDialog.dismiss();
        }
        dialog.setOnDismissListener(d -> {
            this.holdPause = false;
            resumeUnlessExiting();
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400)), -2);
        }
        dialog.show();
    }

    // A setting changed while a pad is (or is not) connected: match the controls to it.
    private void onPadModeSettingChanged() {
        boolean connected = PadMapping.firstController() != null;
        if (connected && padAutoHides() && !this.padMode) {
            enterPadMode(false);
        } else if ((!connected || !padAutoHides()) && this.padMode) {
            leavePadModeNow();
        }
    }

    private void showUndoBar() {
        showActionBar("프리셋을 적용했어요", "되돌리기", () -> {
            Presets.Preset previous = Presets.read(Presets.dir(this, Presets.UNDO_ID));
            if (previous != null) {
                applyPreset(previous);
            }
        });
    }

    // A strip at the bottom for ten seconds with one action on it.
    private void showActionBar(String message, String action, Runnable onAction) {
        hideUndoBar();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.argb(235, 31, 36, 48)));
        background.setCornerRadius(dp(12));
        bar.setBackground(background);
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextSize(12.5f);
        text.setTextColor(Color.WHITE);
        bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView undo = new TextView(this);
        undo.setText(action);
        undo.setTextSize(13.0f);
        undo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        undo.setTextColor(Theme.text(Color.rgb(255, 143, 168)));
        undo.setPadding(dp(12), dp(6), dp(12), dp(6));
        undo.setOnClickListener(view -> {
            hideUndoBar();
            onAction.run();
        });
        bar.addView(undo);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(16), 0, dp(16), dp(24));
        addContentView(bar, params);
        this.undoBar = bar;
        bar.postDelayed(() -> {
            if (this.undoBar == bar) hideUndoBar();
        }, 10000);
    }

    private void hideUndoBar() {
        View bar = this.undoBar;
        this.undoBar = null;
        if (bar != null && bar.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) bar.getParent()).removeView(bar);
        }
    }

    private java.util.Map<String, float[]> loadLayoutEdits() {
        java.util.Map<String, float[]> map = new java.util.HashMap<>();
        String saved = MainActivity.sharedSettings(this).getString(layoutKey(), "");
        for (String entry : saved.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] values = entry.substring(eq + 1).split(",");
            if (values.length != 3 && values.length != 4) {
                continue;
            }
            try {
                float scaleX = Float.parseFloat(values[2]);
                // Older entries have one scale; the game screen may have one per axis.
                float scaleY = values.length == 4 ? Float.parseFloat(values[3]) : scaleX;
                map.put(entry.substring(0, eq), new float[]{Float.parseFloat(values[0]), Float.parseFloat(values[1]), scaleX, scaleY});
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
    // 0.0.352-0.0.360: with a 3DS · DS 화면 배치 of their own in landscape, the picture spans
    // the whole area, and the check that moves edited things off the top corner buttons
    // moved it down and saved that, so the usual picture came out lower afterwards. The
    // saved move of the picture is dropped once (0.0.361).
    private void repairDualScreenEdits() {
        if (!isN3ds() && !isNds()) {
            return;
        }
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        String flag = "screen_edit_fix361:" + layoutConsole();
        if (prefs.getBoolean(flag, false)) {
            return;
        }
        android.content.SharedPreferences.Editor editor = prefs.edit().putBoolean(flag, true);
        for (String style : new String[]{"full", "frame"}) {
            String key = "layout:" + layoutConsole() + ":L:" + style;
            String text = prefs.getString(key, null);
            if (text == null || !text.contains(SCREEN_LAYOUT_ID + "=")) {
                continue;
            }
            StringBuilder kept = new StringBuilder();
            for (String part : text.split(";")) {
                if (!part.isEmpty() && !part.startsWith(SCREEN_LAYOUT_ID + "=")) {
                    kept.append(part).append(';');
                }
            }
            editor.putString(key, kept.toString());
        }
        editor.apply();
    }

    private void applyLayoutEdits(View root) {
        repairDualScreenEdits();
        this.contentRoot = root;
        // Views that stay across a rebuild of part of the controls (DOS pages) keep the
        // position they had before any edit.
        java.util.Map<View, float[]> oldBase = new java.util.IdentityHashMap<>();
        for (int k = 0; k < this.layoutControls.size() && k < this.layoutBase.size(); k++) {
            oldBase.put(this.layoutControls.get(k), this.layoutBase.get(k));
        }
        this.controlsGeneration++;
        this.gapOffsets.clear();
        this.layoutControls.clear();
        this.layoutIds.clear();
        this.layoutBase.clear();
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        // The game screen's frame first, so it is an item of the editor too (not in the
        // controller-only screen, which has no frame).
        if (this.screenArea != null && !isPadScreen()) {
            this.layoutControls.add(this.screenArea);
            this.layoutIds.add(SCREEN_LAYOUT_ID);
            float[] keep = oldBase.get(this.screenArea);
            this.layoutBase.add(keep != null ? keep : new float[]{this.screenArea.getTranslationX(), this.screenArea.getTranslationY()});
        }
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.poll();
            if (isLayoutControl(view)) {
                String kind = view instanceof DosControls.Item ? ((DosControls.Item) view).itemKey()
                        : view.getClass().getSimpleName() + (view instanceof TouchButton ? "." + ((TouchButton) view).bit() : "");
                int count = seen.containsKey(kind) ? seen.get(kind) : 0;
                seen.put(kind, count + 1);
                this.layoutControls.add(view);
                this.layoutIds.add(kind + "#" + count);
                float[] keep = oldBase.get(view);
                this.layoutBase.add(keep != null ? keep : new float[]{view.getTranslationX(), view.getTranslationY()});
            } else if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int k = 0; k < group.getChildCount(); k++) {
                    queue.add(group.getChildAt(k));
                }
            }
        }
        loadButtonStyle();
        applyButtonStyle(this.buttonStyle);
        // A controller already connected (or still connected after a rebuild) keeps the
        // touch controls hidden; the growth needs the layout, so it comes a moment later.
        root.post(() -> {
            if (this.destroyed) return;
            if (this.padMode) {
                setTouchControlsShown(false);
                applyButtonStyle(this.buttonStyle);
            } else if (PadMapping.firstController() != null && !isPadScreen() && !isMame139()) {
                enterPadMode(false);
            }
        });
    }

    static final String SCREEN_LAYOUT_ID = "Screen#0";

    // The app's own buttons the moved controls may not cover (the top bar in portrait, the
    // two top corners in landscape); set as each layout is built.
    private final java.util.List<View> layoutGuards = new java.util.ArrayList<>();

    private static android.graphics.RectF windowBox(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new android.graphics.RectF(at[0], at[1], at[0] + view.getWidth() * view.getScaleX(), at[1] + view.getHeight() * view.getScaleY());
    }

    // Once everything is laid out (the boxes are needed), for the layout still on screen.
    private void scheduleUncoverLayoutGuards(java.util.Map<String, float[]> edits, int generation) {
        if (edits.isEmpty() || this.layoutGuards.isEmpty()) {
            return;
        }
        View guard = this.layoutGuards.get(0);
        Runnable run = () -> {
            if (!this.destroyed && generation == this.controlsGeneration && this.layoutEditor == null) {
                uncoverLayoutGuards(edits);
            }
        };
        if (guard.isLaidOut() && guard.getWidth() > 0) {
            guard.post(run);
            return;
        }
        guard.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (guard.getWidth() == 0) return;
                guard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                guard.post(run);
            }
        });
    }

    // A saved layout from before the guard that covers the top bar or a corner: the controls
    // that do go down just below it, the rest stays, and the fixed layout is saved.
    private void uncoverLayoutGuards(java.util.Map<String, float[]> edits) {
        if (edits.isEmpty() || this.layoutGuards.isEmpty()) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        boolean changed = false;
        for (int k = 0; k < this.layoutControls.size(); k++) {
            View view = this.layoutControls.get(k);
            float[] edit = edits.get(this.layoutIds.get(k));
            if (edit == null || view.getVisibility() != View.VISIBLE || !view.isAttachedToWindow()) {
                continue;
            }
            android.graphics.RectF box = windowBox(view);
            box.inset(dp(1), dp(1));
            float down = 0;
            for (View guard : this.layoutGuards) {
                if (guard.getVisibility() != View.VISIBLE || !guard.isAttachedToWindow() || guard.getWidth() == 0) {
                    continue;
                }
                android.graphics.RectF guardBox = windowBox(guard);
                if (android.graphics.RectF.intersects(box, guardBox)) {
                    down = Math.max(down, guardBox.bottom - box.top + dp(1));
                }
            }
            if (down > 0) {
                edit[1] += down / density;
                applyLayoutEdit(k, edit[0], edit[1], edit[2], edit[3]);
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, float[]> entry : edits.entrySet()) {
            float[] edit = entry.getValue();
            sb.append(entry.getKey()).append('=').append(Math.round(edit[0])).append(',').append(Math.round(edit[1])).append(',')
                    .append(Math.round(edit[2] * 100) / 100.0f);
            if (Math.abs(edit[2] - edit[3]) >= 0.005f) {
                sb.append(',').append(Math.round(edit[3] * 100) / 100.0f);
            }
            sb.append(';');
        }
        MainActivity.sharedSettings(this).edit().putString(layoutKey(), sb.toString()).apply();
        showInfoNotice("버튼 배치를 조금 고쳤어요", this.landscapeMode ? "위쪽 모서리 버튼을 가리던 것을 바로 아래로 내렸어요" : "상단바를 가리던 것을 바로 아래로 내렸어요", 5000);
    }

    private void applyLayoutEdit(int index, float dxDp, float dyDp, float scale) {
        applyLayoutEdit(index, dxDp, dyDp, scale, scale);
    }

    private void applyLayoutEdit(int index, float dxDp, float dyDp, float scaleX, float scaleY) {
        View view = this.layoutControls.get(index);
        float[] base = this.layoutBase.get(index);
        float density = getResources().getDisplayMetrics().density;
        view.setTranslationX(base[0] + dxDp * density);
        view.setTranslationY(base[1] + dyDp * density);
        // The group size from "버튼 꾸미기" multiplies whatever the layout editor set, and
        // "버튼 사이 간격" adds its spread.
        float[] gap = index < this.gapOffsets.size() ? this.gapOffsets.get(index) : null;
        if (gap != null) {
            view.setTranslationX(view.getTranslationX() + gap[0]);
            view.setTranslationY(view.getTranslationY() + gap[1]);
        }
        float group = this.buttonStyle.scale(styleGroup(view));
        view.setScaleX(scaleX * group);
        view.setScaleY(scaleY * group);
        if (view instanceof WiiuPadWindowView) {
            // The core draws the GamePad picture where the window now is.
            view.post(this::applyWiiuLayout);
        }
        if (view == this.screenArea) {
            // 게임패드 자동 전환: the picture grows into the room the hidden controls leave,
            // keeping its top edge in portrait and its centre in landscape.
            float grow = padGrowFor(view, scaleX * group, scaleY * group);
            if (grow > 1.0f) {
                float sx = scaleX * group * grow;
                float sy = scaleY * group * grow;
                view.setScaleX(sx);
                view.setScaleY(sy);
                View root = view.getRootView();
                float centerX = view.getLeft() + view.getWidth() / 2f + view.getTranslationX();
                int[] origin = new int[2];
                int[] rootOrigin = new int[2];
                ((View) view.getParent()).getLocationInWindow(origin);
                root.getLocationInWindow(rootOrigin);
                float rootCenterX = rootOrigin[0] + root.getWidth() / 2f - origin[0];
                view.setTranslationX(view.getTranslationX() + (rootCenterX - centerX));
                if (!this.landscapeMode) {
                    view.setTranslationY(view.getTranslationY() + (grow - 1.0f) * view.getHeight() * scaleY * group / 2f);
                } else {
                    float centerY = view.getTop() + view.getHeight() / 2f + view.getTranslationY();
                    float rootCenterY = rootOrigin[1] + root.getHeight() / 2f - origin[1];
                    view.setTranslationY(view.getTranslationY() + (rootCenterY - centerY));
                }
            }
            if (this.shell != null) {
                // The case bezel follows the screen's frame.
                this.shell.invalidate();
            }
        }
    }

    // Per control, the extra offset "버튼 사이 간격" gives it (pixels); empty when 100%.
    private final java.util.List<float[]> gapOffsets = new java.util.ArrayList<>();

    /**
     * Spreads the A·B·X·Y group and the SELECT·START pair about their own centres by the
     * gap percent. Needs the controls laid out; before that it arranges to run once they are.
     */
    private void computeGapOffsets(Runnable then) {
        this.gapOffsets.clear();
        float gap = this.buttonStyle.gap / 100.0f;
        if (Math.abs(gap - 1.0f) < 0.005f || this.layoutControls.isEmpty()) {
            then.run();
            return;
        }
        for (View view : this.layoutControls) {
            if (view.getWidth() == 0 && isGapControl(view)) {
                final View probe = view;
                probe.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (probe.getWidth() == 0) return;
                        probe.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        computeGapOffsets(then);
                    }
                });
                return;
            }
        }
        java.util.Map<String, float[]> edits = loadLayoutEdits();
        // Two groups, each spread about its centre: round action buttons, and centre keycaps.
        for (int group = 0; group < 2; group++) {
            java.util.List<Integer> members = new java.util.ArrayList<>();
            float sumX = 0, sumY = 0;
            for (int k = 0; k < this.layoutControls.size(); k++) {
                View view = this.layoutControls.get(k);
                if (!(view instanceof TouchButton)) continue;
                TouchButton button = (TouchButton) view;
                boolean action = !button.isKeycap();
                boolean pill = button.isKeycap() && !button.isShoulder();
                if (group == 0 ? !action : !pill) continue;
                members.add(k);
                float[] center = editedCenter(k, edits);
                sumX += center[0];
                sumY += center[1];
            }
            if (members.size() < 2) continue;
            float cx = sumX / members.size();
            float cy = sumY / members.size();
            for (int k : members) {
                float[] center = editedCenter(k, edits);
                float dx = (center[0] - cx) * (gap - 1.0f);
                float dy = group == 0 ? (center[1] - cy) * (gap - 1.0f) : 0;
                // Stays inside its parent.
                View view = this.layoutControls.get(k);
                View parent = (View) view.getParent();
                float half = view.getWidth() / 2.0f * this.buttonStyle.scale(styleGroup(view));
                float halfH = view.getHeight() / 2.0f * this.buttonStyle.scale(styleGroup(view));
                dx = Math.max(half - center[0], Math.min(parent.getWidth() - half - center[0], dx));
                dy = Math.max(halfH - center[1], Math.min(parent.getHeight() - halfH - center[1], dy));
                while (this.gapOffsets.size() <= k) this.gapOffsets.add(null);
                this.gapOffsets.set(k, new float[]{dx, dy});
            }
        }
        then.run();
    }

    private static boolean isGapControl(View view) {
        return view instanceof TouchButton;
    }

    // The control's centre in its parent with the layout editor's move applied (gap excluded).
    private float[] editedCenter(int index, java.util.Map<String, float[]> edits) {
        View view = this.layoutControls.get(index);
        float[] base = this.layoutBase.get(index);
        float[] edit = edits.get(this.layoutIds.get(index));
        float density = getResources().getDisplayMetrics().density;
        float tx = base[0] + (edit != null ? edit[0] * density : 0);
        float ty = base[1] + (edit != null ? edit[1] * density : 0);
        return new float[]{view.getLeft() + view.getWidth() / 2.0f + tx, view.getTop() + view.getHeight() / 2.0f + ty};
    }

    /** The controls on this screen as (id, name, role) for "버튼마다 따로 고르기". */
    private java.util.List<String[]> styleControlNames() {
        java.util.List<String[]> names = new java.util.ArrayList<>();
        int sticks = 0;
        for (int k = 0; k < this.layoutControls.size(); k++) {
            View view = this.layoutControls.get(k);
            String name;
            int role;
            if (view instanceof TouchButton) {
                TouchButton button = (TouchButton) view;
                name = button.label();
                role = button.isKeycap() ? ButtonStyle.ROLE_PILL : ButtonStyle.roleOf(name);
            } else if (view instanceof DPadView) {
                name = "방향키";
                role = ButtonStyle.ROLE_DPAD;
            } else if (view instanceof AnalogStickView) {
                name = ++sticks == 1 ? "스틱" : "오른쪽 스틱";
                role = ButtonStyle.ROLE_DPAD;
            } else if (view instanceof DosControls.DosKey) {
                DosControls.DosKey key = (DosControls.DosKey) view;
                name = key.getText().toString();
                role = key.role;
            } else if (view instanceof DosControls.Item && ((DosControls.Item) view).styleGroup() == ButtonStyle.GROUP_DPAD) {
                name = "방향 패드";
                role = ButtonStyle.ROLE_DPAD;
            } else {
                continue;
            }
            names.add(new String[]{this.layoutIds.get(k), name, String.valueOf(role)});
        }
        return names;
    }

    // ---- 버튼 꾸미기 (button style) ----
    private static int styleGroup(View view) {
        if (view instanceof DosControls.Item) return ((DosControls.Item) view).styleGroup();
        if (view instanceof DPadView) return ButtonStyle.GROUP_DPAD;
        if (view instanceof AnalogStickView || view instanceof ArcadeJoystickView) return ButtonStyle.GROUP_STICK;
        if (view instanceof TouchButton) return ((TouchButton) view).isKeycap() ? ButtonStyle.GROUP_SHOULDER : ButtonStyle.GROUP_ACTION;
        return ButtonStyle.GROUP_NONE;
    }

    private boolean hasOwnButtonStyle() {
        return this.saveBaseName != null && ButtonStyle.exists(this, ButtonStyle.gameScope(this.saveBaseName), this.landscapeMode);
    }

    private void loadButtonStyle() {
        this.buttonStyle = ButtonStyle.load(this, this.gameMode, this.saveBaseName, this.landscapeMode);
        // The 원조 case wears the 원조 button colours unless the player picked others.
        if (shellStyle() == ClearShellView.STYLE_RETRO && this.buttonStyle.theme == ButtonStyle.THEME_DEFAULT) {
            this.buttonStyle.theme = ButtonStyle.THEME_RETRO;
        }
        // Likewise the pastel glass case and its glass buttons.
        if (shellStyle() == ClearShellView.STYLE_PASTEL && this.buttonStyle.theme == ButtonStyle.THEME_DEFAULT) {
            this.buttonStyle.theme = ButtonStyle.THEME_GLASS;
        }
        // And the neon night case its neon buttons, so the pad shows on the black body.
        if (shellStyle() == ClearShellView.STYLE_NEON && this.buttonStyle.theme == ButtonStyle.THEME_DEFAULT) {
            this.buttonStyle.theme = ButtonStyle.THEME_NEON;
        }
        // And the space case its slate glass buttons.
        if (shellStyle() == ClearShellView.STYLE_SPACE && this.buttonStyle.theme == ButtonStyle.THEME_DEFAULT) {
            this.buttonStyle.theme = ButtonStyle.THEME_SPACE;
        }
        // And the material / retro cases their matching colours.
        if (shellSkin() >= 0 && this.buttonStyle.theme == ButtonStyle.THEME_DEFAULT) {
            this.buttonStyle.theme = ButtonStyle.THEME_SKIN;
        }
        this.buttonStyle.skin = Math.max(0, shellSkin());
        this.buttonStyle.glass = shellGlass();
    }

    /** Draws the controls just built (or rebuilt) with the style in force, sizes included. */
    // Bumped whenever the controls are rebuilt, so a gap pass waiting on layout can tell.
    private int controlsGeneration;

    private void applyButtonStyle(ButtonStyle style) {
        this.buttonStyle = style;
        final int generation = this.controlsGeneration;
        for (int k = 0; k < this.layoutControls.size(); k++) {
            View view = this.layoutControls.get(k);
            String key = this.layoutIds.get(k);
            if (view instanceof TouchButton) {
                ((TouchButton) view).setStyle(style, key);
            } else if (view instanceof DPadView) {
                ((DPadView) view).setStyle(style, key);
            } else if (view instanceof AnalogStickView) {
                ((AnalogStickView) view).setStyle(style, key);
            } else if (view instanceof DosControls.Item) {
                ((DosControls.Item) view).applyStyle(style, key);
            }
        }
        computeGapOffsets(() -> {
            if (generation != this.controlsGeneration) return;
            java.util.Map<String, float[]> edits = loadLayoutEdits();
            for (int k = 0; k < this.layoutControls.size(); k++) {
                float[] edit = edits.get(this.layoutIds.get(k));
                applyLayoutEdit(k, edit != null ? edit[0] : 0, edit != null ? edit[1] : 0, edit != null ? edit[2] : 1, edit != null ? edit[3] : 1);
            }
            scheduleUncoverLayoutGuards(edits, generation);
            scheduleSettleVmu(generation);
            scheduleSettleGun(generation);
            for (android.view.ViewGroup parent : this.comboParents) {
                layoutComboZones(parent, true);
            }
        });
    }

    // ---- "화면의 버튼을 눌러 고르기" ----
    private ButtonPickOverlay pickOverlay;

    /** The id of the control under a window point, or null. */
    private String styleControlAt(float x, float y) {
        int[] origin = new int[2];
        for (int k = 0; k < this.layoutControls.size(); k++) {
            View view = this.layoutControls.get(k);
            if (!(view instanceof TouchButton || view instanceof DPadView || view instanceof AnalogStickView)) continue;
            view.getLocationInWindow(origin);
            // getLocationInWindow includes translation and scale about the centre.
            float w = view.getWidth() * view.getScaleX();
            float h = view.getHeight() * view.getScaleY();
            if (x >= origin[0] && x <= origin[0] + w && y >= origin[1] && y <= origin[1] + h) {
                return this.layoutIds.get(k);
            }
        }
        return null;
    }

    private void startButtonPick(ButtonPickOverlay.Listener listener) {
        stopButtonPick();
        ButtonPickOverlay overlay = new ButtonPickOverlay(this, screenRectInWindow(), this::styleControlAt, listener);
        addContentView(overlay, new FrameLayout.LayoutParams(-1, -1));
        this.pickOverlay = overlay;
    }

    private void stopButtonPick() {
        ButtonPickOverlay overlay = this.pickOverlay;
        this.pickOverlay = null;
        if (overlay != null && overlay.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    private String buttonStyleSummary() {
        ButtonStyle style = this.buttonStyle;
        String name = style.isDefault() ? "기본" : ButtonStyle.THEME_NAMES[style.theme].replace(" (케이스 색)", "");
        if (!style.isDefault() && (style.sizeAll != 100 || style.sizes[0] != 100 || style.sizes[1] != 100 || style.sizes[2] != 100 || style.sizes[3] != 100 || style.gap != 100)) {
            name += " · 크기 조절";
        }
        return hasOwnButtonStyle() ? name + " (이 게임)" : name;
    }

    private void openButtonStyleDialog() {
        if (this.layoutControls.isEmpty()) {
            Toast.makeText(this, "이 화면에는 꾸밀 버튼이 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            this.pauseDialog.dismiss();
        }
        final boolean landscape = this.landscapeMode;
        final String modeScope = this.gameMode;
        final String gameScope = this.saveBaseName != null ? ButtonStyle.gameScope(this.saveBaseName) : null;
        new ButtonStyleDialog(this, this.buttonStyle, hasOwnButtonStyle(), landscape, consoleName(), styleControlNames(), new ButtonStyleDialog.Listener() {
            @Override
            public void onChange(ButtonStyle style) {
                applyButtonStyle(style);
            }

            @Override
            public void onPickStart(ButtonPickOverlay.Listener picked) {
                startButtonPick(picked);
            }

            @Override
            public void onPickStop() {
                stopButtonPick();
            }

            @Override
            public void onDone(ButtonStyle style, boolean perGame) {
                if (perGame && gameScope != null) {
                    ButtonStyle.save(GameActivity.this, gameScope, landscape, style);
                } else {
                    if (gameScope != null) ButtonStyle.clear(GameActivity.this, gameScope, landscape);
                    ButtonStyle.save(GameActivity.this, modeScope, landscape, style);
                }
                applyButtonStyle(style);
                Toast.makeText(GameActivity.this, perGame && gameScope != null ? "이 게임의 버튼 꾸미기를 저장했어요." : consoleName() + " 버튼 꾸미기를 저장했어요.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCopyToOther(ButtonStyle style, boolean perGame) {
                ButtonStyle.save(GameActivity.this, perGame && gameScope != null ? gameScope : modeScope, !landscape, style);
            }

            @Override
            public void onReset(boolean perGame) {
                if (perGame && gameScope != null) {
                    ButtonStyle.clear(GameActivity.this, gameScope, landscape);
                } else {
                    if (gameScope != null) ButtonStyle.clear(GameActivity.this, gameScope, landscape);
                    ButtonStyle.clear(GameActivity.this, modeScope, landscape);
                }
                loadButtonStyle();
                applyButtonStyle(buttonStyle);
                Toast.makeText(GameActivity.this, "버튼을 기본 모습으로 되돌렸어요.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onClosed() {
                stopButtonPick();
                holdPause = false;
                resumeUnlessExiting();
            }
        }).show(screenRectInWindow());
    }

    /** Where the game screen is on the window, or null before layout. */
    private android.graphics.Rect screenRectInWindow() {
        View screen = this.screenArea;
        if (screen == null || screen.getWidth() == 0) {
            return null;
        }
        int[] origin = new int[2];
        screen.getLocationInWindow(origin);
        return new android.graphics.Rect(origin[0], origin[1], origin[0] + screen.getWidth(), origin[1] + screen.getHeight());
    }

    private void saveLayoutEdits(float[][] result) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < result.length && k < this.layoutIds.size(); k++) {
            float[] edit = result[k];
            if (Math.abs(edit[0]) < 0.5f && Math.abs(edit[1]) < 0.5f && Math.abs(edit[2] - 1) < 0.01f && Math.abs(edit[3] - 1) < 0.01f) {
                continue;
            }
            sb.append(this.layoutIds.get(k)).append('=').append(Math.round(edit[0])).append(',')
                    .append(Math.round(edit[1])).append(',').append(Math.round(edit[2] * 100) / 100.0f);
            if (Math.abs(edit[2] - edit[3]) >= 0.005f) {
                sb.append(',').append(Math.round(edit[3] * 100) / 100.0f);
            }
            sb.append(';');
        }
        MainActivity.sharedSettings(this).edit().putString(layoutKey(), sb.toString()).apply();
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
            edits[k] = edit != null ? edit.clone() : new float[]{0, 0, 1, 1};
        }
        int screenIndex = this.layoutControls.indexOf(this.screenArea);
        LayoutEditorView editor = new LayoutEditorView(this, new java.util.ArrayList<>(this.layoutControls), edits, screenIndex, new LayoutEditorView.Listener() {
            @Override
            public void apply(int index, float dxDp, float dyDp, float scaleX, float scaleY) {
                applyLayoutEdit(index, dxDp, dyDp, scaleX, scaleY);
            }

            @Override
            public void onSave(float[][] result) {
                saveLayoutEdits(result);
                finishLayoutEditor("버튼 배치를 저장했어요.");
            }
            @Override
            public void onCancel() {
                finishLayoutEditor(null);
            }

            @Override
            public boolean onHold(int index, float rawX, float rawY) {
                return dosEditorHold(index, rawX, rawY);
            }
        });
        editor.setGuards(new java.util.ArrayList<>(this.layoutGuards));
        addContentView(editor, new FrameLayout.LayoutParams(-1, -1));
        this.layoutEditor = editor;
        if (this.vmuView != null) {
            this.vmuView.setEditing(true);
        }
    }

    // DOS, in the layout editor: a key of the row held → it stands on its own; a key on its own
    // held → back into the row. The edits so far are kept, the editor opens again.
    private boolean dosEditorHold(int index, float rawX, float rawY) {
        if (this.dosControls == null || index < 0 || index >= this.layoutControls.size() || this.layoutEditor == null) {
            return false;
        }
        View view = this.layoutControls.get(index);
        String key = null;
        if (view instanceof DosControls.KeyStrip) {
            key = ((DosControls.KeyStrip) view).keyAt(rawX, rawY);
        } else if (view instanceof DosControls.DosKey && ((DosControls.DosKey) view).itemKey != null
                && ((DosControls.DosKey) view).itemKey.startsWith("dos.key.")) {
            key = ((DosControls.DosKey) view).keyName;
        }
        if (key == null) {
            return false;
        }
        float[][] result = this.layoutEditor.currentEdits();
        saveLayoutEdits(result);
        final boolean back = view instanceof DosControls.DosKey;
        finishLayoutEditor(null);
        this.dosControls.togglePulled(key);
        final String shown = key;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (this.destroyed) return;
            startLayoutEditor();
            Toast.makeText(this, back ? shown + " 키를 줄에 다시 넣었어요" : shown + " 키를 줄에서 꺼냈어요 · 끌어서 옮겨요", Toast.LENGTH_SHORT).show();
        }, 250);
        return true;
    }

    private void finishLayoutEditor(String message) {
        LayoutEditorView editor = this.layoutEditor;
        this.layoutEditor = null;
        if (this.vmuView != null) {
            this.vmuView.setEditing(false);
        }
        if (editor != null && editor.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) editor.getParent()).removeView(editor);
        }
        // Moved buttons shift the groups' centres, so the gap spread is taken again.
        applyButtonStyle(this.buttonStyle);
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
        if (isMame139() || this.romPath == null || !Cheats.supported(this.gameMode)) {
            return false;
        }
        // Arcade cheats are FBNeo's (addresses in its memory); PS2's need PCSX2 (Play! has none).
        if ("arcade".equals(this.gameMode) || "neogeo".equals(this.gameMode)) {
            return "fbneo".equals(this.coreId);
        }
        if (isPs2()) {
            return "pcee2".equals(this.coreId);
        }
        // Cemu and Citra find the codes by the game's title ID.
        if (isWiiU() || isN3ds()) {
            return Cheats.titleIdOf(romFile(), this.gameMode) != null;
        }
        return true;
    }

    private String cheatsUnsupportedText() {
        if ("arcade".equals(this.gameMode) || "neogeo".equals(this.gameMode)) {
            return "아케이드 치트는 FBNeo 엔진에서만 돼요. 게임 목록의 \"엔진 변경 → FBNeo\"로 바꾸면 쓸 수 있어요.";
        }
        if (isPs2()) {
            return "PS2 치트는 PCSX2 엔진에서만 돼요 (Play! 엔진은 치트 기능이 없어요).";
        }
        if (isWiiU()) {
            return "Wii U 치트 팩은 .wua 게임에서만 돼요 (게임 ID를 읽어야 해서요).";
        }
        if (isN3ds()) {
            return "이 3DS 파일에서 게임 ID를 읽지 못해서 치트를 쓸 수 없어요.";
        }
        return "이 기종은 엔진이 치트를 지원하지 않아요.";
    }

    // ---- 치트 for engines that read their codes when the game starts ----

    // PS2: the cheats that need PCSX2's patch engine, as <system>/pcsx2/patches/<CRC>_mini-cheats.pnach.
    // True when the file changed.
    private String ps2PnachWritten;

    private boolean writePs2CheatPnach() {
        if (!"pcee2".equals(this.coreId) || this.romPath == null) {
            return false;
        }
        String text = Cheats.ps2Pnach(cheats());
        long crc = OverlayPatches.ps2IsoCrc(romFile());
        if (crc < 0) {
            return false;
        }
        File file = new File(new File(new File(getFilesDir(), "system"), "pcsx2/patches"), String.format(Locale.US, "%08X", crc) + "_mini-cheats.pnach");
        boolean changed = this.ps2PnachWritten != null && !this.ps2PnachWritten.equals(text);
        this.ps2PnachWritten = text;
        if (text.isEmpty()) {
            file.delete();
            return changed;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return changed;
    }

    private String dolphinCodesWritten;

    // ---- MAME 2003-Plus: its own cheat menu over the game (cheat.dat, read when the game starts) ----
    private static final String MAME_CHEAT_URL = "https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/ab52620ee4cef6b3ca2671a00b8629ea47d66521/metadata/cheat.dat";
    private Boolean mameCheatsAtStart;
    private View mameMenuOverlay;

    private boolean isMame2003Plus() {
        return "mame2003plus".equals(this.coreId);
    }

    private File mameCheatFile() {
        return new File(new File(new File(getFilesDir(), "system"), "mame2003-plus"), "cheat.dat");
    }

    private void showMameCheatDialog() {
        if (this.mameCheatsAtStart == null) {
            this.mameCheatsAtStart = false;
        }
        boolean have = mameCheatFile().isFile();
        AppDialog.Builder builder = new AppDialog.Builder(this).setTitle("치트 · MAME 2003-Plus");
        if (have && this.mameCheatsAtStart) {
            builder.setMessage("MAME 2003-Plus는 치트를 게임 화면 위의 MAME 메뉴에서 켜고 꺼요 (영어).\n\n"
                    + "메뉴 → Cheat → Enable/Disable a Cheat → 원하는 치트에서 ◀ ▶ 로 On.\n"
                    + "메뉴가 열리면 화면 아래에 메뉴용 버튼(방향 · 확인 · 메뉴 닫기)이 나와요. 메뉴가 떠 있는 동안 게임은 멈춰 있어요.\n\n"
                    + "FBNeo 엔진으로 바꾸면 앱 치트 창에서 켜고 끄는 방식도 쓸 수 있어요.")
                    .setNegativeButton("닫기", null)
                    .setPositiveButton("MAME 치트 메뉴 열기", (d, w) -> openMameMenu());
        } else if (have) {
            builder.setMessage("치트 모음(cheat.dat)은 받아 두었어요. MAME는 게임을 켤 때 이 파일을 읽어서, 게임을 나갔다가 다시 켜면 MAME 메뉴에 Cheat가 생겨요.")
                    .setPositiveButton("확인", null);
        } else {
            builder.setMessage("MAME 2003-Plus는 치트를 MAME 자체 메뉴에서 켜고 꺼요. 먼저 MAME 치트 모음(cheat.dat, 약 7.8MB)을 한 번 받아야 해요.\n\n받은 뒤 게임을 나갔다가 다시 켜면 쓸 수 있어요.")
                    .setNegativeButton("닫기", null)
                    .setPositiveButton("치트 모음 받기", (d, w) -> downloadMameCheats());
        }
        builder.show();
    }

    private void downloadMameCheats() {
        Toast.makeText(this, "MAME 치트 모음을 받는 중…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            try {
                byte[] data = Cheats.fetchBytes(MAME_CHEAT_URL);
                if (data.length < 1_000_000) {
                    throw new IOException("short");
                }
                File file = mameCheatFile();
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
                File tmp = new File(file.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(file)) {
                    throw new IOException("rename");
                }
            } catch (IOException | RuntimeException e) {
                error = "치트 모음을 받지 못했어요. 인터넷 연결을 확인해 주세요.";
            }
            final String why = error;
            runOnUiThread(() -> new AppDialog.Builder(this).setTitle(why == null ? "치트 모음을 받았어요" : "받지 못했어요")
                    .setMessage(why == null ? "게임을 나갔다가 다시 켜면 MAME 메뉴에서 치트를 쓸 수 있어요." : why)
                    .setPositiveButton("확인", null).show());
        }, "mame-cheats").start();
    }

    // MAME's menu key (Tab) through the keyboard the bridge answers for, and buttons to move in it.
    private void tapKey(int code) {
        long handle = this.nativeHandle;
        if (handle == 0) {
            return;
        }
        NativeGba.dosKey(handle, code, true, 0);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (this.nativeHandle != 0) {
                NativeGba.dosKey(this.nativeHandle, code, false, 0);
            }
        }, 120);
    }

    private void openMameMenu() {
        if (this.mameMenuOverlay != null) {
            return;
        }
        tapKey(9);  // Tab
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.argb(235, 17, 24, 39));
        bg.setCornerRadius(dp(18));
        panel.setBackground(bg);
        TextView label = new TextView(this);
        label.setText("🕹 MAME 메뉴 · Cheat → Enable/Disable a Cheat → ◀ ▶");
        label.setTextColor(Color.rgb(254, 202, 202));
        label.setTextSize(11.5f);
        panel.addView(label);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.GridLayout cross = new android.widget.GridLayout(this);
        cross.setColumnCount(3);
        cross.setRowCount(3);
        int[][] arrows = {{0, 1, 273}, {1, 0, 276}, {1, 2, 275}, {2, 1, 274}};
        String[] marks = {"▲", "◀", "▶", "▼"};
        for (int k = 0; k < arrows.length; k++) {
            TextView b = mameKey(marks[k], arrows[k][2], Color.rgb(55, 65, 81));
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(arrows[k][0]), android.widget.GridLayout.spec(arrows[k][1]));
            lp.width = dp(52);
            lp.height = dp(46);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            cross.addView(b, lp);
        }
        row.addView(cross);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(1);
        TextView enter = mameKey("확인 (Enter)", 13, Color.rgb(15, 118, 110));
        right.addView(enter, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView close = mameKey("메뉴 닫기", 0, Color.rgb(185, 28, 28));
        close.setOnTouchListener(null);
        close.setOnClickListener(v -> closeMameMenu(true));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(44));
        closeParams.setMargins(0, dp(10), 0, 0);
        right.addView(close, closeParams);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        rightParams.setMargins(dp(14), 0, 0, 0);
        row.addView(right, rightParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, dp(8), 0, 0);
        panel.addView(row, rowParams);
        FrameLayout holder = new FrameLayout(this);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(420)), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelParams.setMargins(0, 0, 0, dp(16));
        holder.addView(panel, panelParams);
        addContentView(holder, new android.view.ViewGroup.LayoutParams(-1, -1));
        this.mameMenuOverlay = holder;
    }

    private TextView mameKey(String text, final int code, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13.0f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnTouchListener((v, e) -> {
            long handle = this.nativeHandle;
            int action = e.getActionMasked();
            if (handle != 0 && action == android.view.MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.6f);
                Haptics.tap();
                NativeGba.dosKey(handle, code, true, 0);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (handle != 0) NativeGba.dosKey(handle, code, false, 0);
            }
            return true;
        });
        return b;
    }

    private void closeMameMenu(boolean sendKey) {
        View overlay = this.mameMenuOverlay;
        this.mameMenuOverlay = null;
        if (overlay != null && overlay.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
        }
        if (sendKey) {
            tapKey(9);
        }
    }

    // 3DS: Citra reads cheats/<title ID>.txt (Gateway codes) when the game starts. True when it changed.
    private String citraCheatsWritten;

    private boolean writeCitraCheats() {
        String tid = Cheats.titleIdOf(romFile(), "3ds");
        if (tid == null) {
            return false;
        }
        String text = Cheats.citraCheatFile(cheats());
        boolean changed = this.citraCheatsWritten != null && !this.citraCheatsWritten.equals(text);
        this.citraCheatsWritten = text;
        File file = new File(getFilesDir(), "saves/citra/Citra/cheats/" + tid + ".txt");
        if (text.isEmpty()) {
            file.delete();
            return changed;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        return changed;
    }

    // Wii U: the cheat packs that are on, fetched and put where Cemu looks (read at start).
    private String wiiuPacksWritten;

    private void syncWiiuCheatPacks(boolean tell) {
        StringBuilder on = new StringBuilder();
        for (Cheats.Cheat cheat : cheats()) {
            if (cheat.on) on.append(cheat.code).append('\n');
        }
        String state = on.toString();
        boolean changed = this.wiiuPacksWritten != null && !this.wiiuPacksWritten.equals(state);
        this.wiiuPacksWritten = state;
        final java.util.List<Cheats.Cheat> list = new java.util.ArrayList<>(cheats());
        Runnable work = () -> {
            String error = null;
            try {
                Cheats.syncWiiuPacks(this, new File(new File(getFilesDir(), "system"), "Cemu"), list);
            } catch (IOException | RuntimeException e) {
                error = "치트 팩을 받지 못했어요. 인터넷 연결을 확인해 주세요.";
            }
            final String why = error;
            if (tell && (changed || why != null)) {
                runOnUiThread(() -> Toast.makeText(this, why != null ? why : "Wii U 치트 팩은 게임을 나갔다가 다시 켜면 적용돼요.", Toast.LENGTH_LONG).show());
            }
        };
        if (tell) {
            new Thread(work, "wiiu-cheat-packs").start();
        } else {
            work.run();
        }
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
        int on = Cheats.countOn(cheats());
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
        int on = Cheats.countOn(cheats());
        return this.gameTitle + (on > 0 ? " · 켜진 것 " + on + "개" : "");
    }

    private void applyCheats() {
        final long handle = this.nativeHandle;
        if (handle == 0 || !cheatsSupported()) {
            return;
        }
        if (isDolphin()) {
            String before = this.dolphinCodesWritten;
            applyWiiLinkCode();
            if (before != null && !before.equals(this.dolphinCodesWritten)) {
                Toast.makeText(this, "게임큐브 · Wii 치트는 게임을 다시 시작하면 적용돼요 (메뉴 → 재시작).", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (isN3ds()) {
            if (writeCitraCheats()) {
                Toast.makeText(this, "3DS 치트는 게임을 나갔다가 다시 켜면 적용돼요.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (isWiiU()) {
            syncWiiuCheatPacks(true);
            return;
        }
        if (isPs2() && writePs2CheatPnach()) {
            Toast.makeText(this, "조건 · 포인터가 있는 PS2 치트는 게임을 다시 시작하면 적용돼요.", Toast.LENGTH_LONG).show();
        }
        final String[] codes = Cheats.enabledCodes(cheats(), this.gameMode);
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
            case "3ds": return "3DS: Gateway · 액션리플레이 (예: 0044E534 E3A04000)";
            case "ps2": return "PS2: pnach 줄 (예: patch=1,EE,2031D8E4,extended,000F423F) 또는 풀린 RAW 코드 (예: 2031D8E4 000F423F)";
            case "gc": return isWiiGame() ? "Wii: Gecko · 액션리플레이 (예: C2000000 00000002)" : "게임큐브: 액션리플레이 · Gecko (예: 04123456 00000063, 암호화된 AR도 돼요)";
            case "ws": return "원더스완: RAM 주소:값 (예: 1E24:09)";
            case "ngp": return "네오지오 포켓: 주소:값 (예: 09F8:04)";
            case "msx": return "MSX: blueMSX 치트 주소:값:크기 (16진, 크기 0 = 8비트 · 1 = 16비트, 예: E0A2:01:0) · 치트 찾기로 찾은 주소도 돼요";
            default: return "";
        }
    }

    private static android.graphics.drawable.GradientDrawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Theme.bg(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView sheetText(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Theme.text(color));
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
        box.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        box.setHintTextColor(Theme.text(Color.rgb(154, 161, 170)));
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
            TextView file = dialogButton(isPs2() ? "파일에서 (.pnach · .cht)" : "파일에서 (.cht)", Color.rgb(241, 242, 244), Color.rgb(55, 65, 81));
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
        } else if (!Cheats.hasCollection(this.gameMode)) {
            title = "이 기종은 치트 모음이 없어요";
            detail = "직접 입력하거나 파일에서 가져온 치트예요";
            background = Color.rgb(246, 247, 249);
            color = Color.rgb(75, 85, 99);
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
        if (!this.cheatSearching && Cheats.hasCollection(this.gameMode)) {
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
        boolean collection = Cheats.hasCollection(this.gameMode);
        TextView title = sheetText(collection ? "치트 모음에서 이 게임을 못 찾았어요" : "이 기종은 치트 모음이 없어요", 15.0f, Color.rgb(55, 65, 81), true);
        title.setGravity(1);
        box.addView(title);
        TextView detail = sheetText(collection ? "한글판·일본판처럼 모음에 없는 판일 수 있어요.\n비슷한 판을 직접 고르거나 코드를 입력해 주세요."
                : isPs2() ? "치트 코드를 직접 입력하거나 PCSX2 .pnach 파일을 가져와 주세요." : "치트 코드를 직접 입력하거나 .cht 파일을 가져와 주세요.", 12.0f, Color.rgb(122, 130, 140), false);
        detail.setGravity(1);
        detail.setPadding(0, dp(6), 0, 0);
        box.addView(detail);
        panel.addView(box, marginTop(-1, -2, 14));
        if (collection) {
            panel.addView(createSettingRow(RoundIconView.SWAP, CHEAT_GREEN, "다른 판 고르기", "", view -> showCheatVersionPicker()), marginTop(-1, dp(52), 12));
        }
        panel.addView(createSettingRow(RoundIconView.CHEAT, CHEAT_RED, "직접 입력", "", view -> showCheatEditor(null)), marginTop(-1, dp(52), collection ? 8 : 12));
        panel.addView(createSettingRow(RoundIconView.IMPORT, Color.rgb(75, 85, 99), isPs2() ? "파일에서 가져오기 (.pnach · .cht)" : "파일에서 가져오기 (.cht)", "", view -> openCheatFile()), marginTop(-1, dp(52), 8));
        if (!collection) {
            return;
        }
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
            TextView code = sheetText(Cheats.displayCode(cheat.code), 11.0f, Color.rgb(156, 163, 175), false);
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
        new AppDialog.Builder(this).setTitle(cheat.desc).setItems(new String[]{"고치기", "지우기"}, (d, which) -> {
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
        if (isWiiU()) {
            Toast.makeText(this, "Wii U는 Cemu 공식 치트 팩만 켜고 끌 수 있어요 (코드 직접 넣기는 없어요).", Toast.LENGTH_LONG).show();
            return;
        }
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
        // Dreamcast · Naomi (and address codes from a file): an address, a value and a size.
        final boolean byAddress = editing != null ? editing.code.startsWith("@") && !editing.code.contains("+")
                : Cheats.addressMode(this.gameMode);
        final String[] oldParts = editing != null && byAddress ? editing.code.substring(1).split(":") : new String[0];
        EditText address = new EditText(this);
        EditText value = new EditText(this);
        final int[] size = {oldParts.length >= 3 ? cheatSize(oldParts[2]) : 4};
        if (byAddress) {
            LinearLayout pair = new LinearLayout(this);
            pair.setOrientation(0);
            LinearLayout addressColumn = new LinearLayout(this);
            addressColumn.setOrientation(1);
            addressColumn.addView(sheetText("주소 (16진수)", 12.5f, Color.rgb(55, 65, 81), true));
            address.setSingleLine(true);
            address.setTextSize(14.0f);
            address.setTypeface(android.graphics.Typeface.MONOSPACE);
            address.setHint("1EC4C8");
            address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            address.setPadding(dp(12), dp(10), dp(12), dp(10));
            address.setBackground(roundRect(Color.rgb(255, 241, 242), dp(12)));
            addressColumn.addView(address, marginTop(-1, -2, 6));
            pair.addView(addressColumn, new LinearLayout.LayoutParams(0, -2, 1.0f));
            LinearLayout valueColumn = new LinearLayout(this);
            valueColumn.setOrientation(1);
            valueColumn.addView(sheetText("값 (10진수)", 12.5f, Color.rgb(55, 65, 81), true));
            value.setSingleLine(true);
            value.setTextSize(14.0f);
            value.setTypeface(android.graphics.Typeface.MONOSPACE);
            value.setHint("9999999");
            value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            value.setPadding(dp(12), dp(10), dp(12), dp(10));
            value.setBackground(roundRect(Color.rgb(246, 247, 249), dp(12)));
            valueColumn.addView(value, marginTop(-1, -2, 6));
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            valueParams.setMargins(dp(8), 0, 0, 0);
            pair.addView(valueColumn, valueParams);
            panel.addView(pair, marginTop(-1, -2, 12));
            panel.addView(sheetText("크기", 12.5f, Color.rgb(55, 65, 81), true), marginTop(-2, -2, 12));
            LinearLayout sizes = new LinearLayout(this);
            sizes.setOrientation(0);
            final TextView[] sizeViews = new TextView[3];
            final int[] sizeValues = {1, 2, 4};
            Runnable paintSizes = () -> {
                for (int k = 0; k < 3; k++) {
                    boolean on = sizeValues[k] == size[0];
                    sizeViews[k].setTextColor(Theme.text(on ? Color.WHITE : Color.rgb(55, 65, 81)));
                    sizeViews[k].setBackground(roundRect(on ? CHEAT_RED : Color.rgb(241, 242, 244), dp(10)));
                }
            };
            for (int k = 0; k < 3; k++) {
                final int bytes = sizeValues[k];
                TextView option = sheetText(bytes + "바이트", 12.5f, Color.rgb(55, 65, 81), true);
                option.setGravity(17);
                option.setOnClickListener(view -> {
                    size[0] = bytes;
                    paintSizes.run();
                });
                sizeViews[k] = option;
                LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
                optionParams.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
                sizes.addView(option, optionParams);
            }
            paintSizes.run();
            panel.addView(sizes, marginTop(-1, -2, 6));
            if (oldParts.length >= 2) {
                address.setText(oldParts[0]);
                value.setText(oldParts[1]);
            }
        }
        TextView codeLabel = sheetText("코드 (한 줄에 하나, 여러 줄 가능)", 12.5f, Color.rgb(55, 65, 81), true);
        panel.addView(codeLabel, marginTop(-2, -2, 12));
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
        // GameCube · Wii: which kind the lines are (auto, Gecko, Action Replay); PS2 and Dolphin
        // codes are read as they are typed.
        final int[] dolphinKind = {editing != null && isDolphin() ? Cheats.dolphinKind(editing.code) : 0};
        final TextView verdict = sheetText("", 11.5f, Color.rgb(17, 94, 89), true);
        verdict.setPadding(dp(12), dp(8), dp(12), dp(8));
        verdict.setBackground(roundRect(Color.rgb(240, 253, 250), dp(12)));
        final Runnable judge = () -> {
            String typed = Cheats.codeFromInput(code.getText().toString());
            String text = typed.isEmpty() ? "" : isPs2() ? Cheats.ps2Describe(typed) : isN3ds() ? Cheats.n3dsDescribe(typed) : Cheats.dolphinDescribe(typed, dolphinKind[0], isWiiGame());
            verdict.setText(text);
            verdict.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        };
        if (!byAddress && (isPs2() || isDolphin() || isN3ds())) {
            if (isDolphin()) {
                LinearLayout kinds = new LinearLayout(this);
                final String[] kindNames = {"자동 판별", "Gecko", "액션리플레이"};
                final TextView[] kindViews = new TextView[3];
                final Runnable paintKinds = () -> {
                    for (int k = 0; k < 3; k++) {
                        boolean on = dolphinKind[0] == k;
                        kindViews[k].setTextColor(Theme.text(on ? Color.WHITE : Color.rgb(55, 65, 81)));
                        kindViews[k].setBackground(roundRect(on ? CHEAT_RED : Color.rgb(241, 242, 244), dp(10)));
                    }
                };
                for (int k = 0; k < 3; k++) {
                    final int kind = k;
                    TextView option = sheetText(kindNames[k], 12.0f, Color.rgb(55, 65, 81), true);
                    option.setGravity(17);
                    option.setOnClickListener(view -> {
                        dolphinKind[0] = kind;
                        paintKinds.run();
                        judge.run();
                    });
                    kindViews[k] = option;
                    LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
                    optionParams.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
                    kinds.addView(option, optionParams);
                }
                paintKinds.run();
                panel.addView(kinds, panel.indexOfChild(codeLabel), marginTop(-1, -2, 12));
            }
            panel.addView(verdict, marginTop(-1, -2, 6));
            verdict.setVisibility(View.GONE);
            code.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void afterTextChanged(android.text.Editable e) { judge.run(); }
            });
        }
        if (editing != null) {
            name.setText(editing.desc);
            code.setText(Cheats.codeForInput(isDolphin() ? Cheats.dolphinBody(editing.code) : editing.code));
        }
        if (byAddress) {
            codeLabel.setVisibility(View.GONE);
            code.setVisibility(View.GONE);
        }
        TextView note = sheetText(byAddress ? "주소 방식이에요. 켜 두면 앱이 매 프레임 그 주소에 값을 다시 써서 고정해요."
                : cheatFormatNote() + "\n형식은 엔진이 알아서 구분해요.", 11.5f, Color.rgb(75, 85, 99), false);
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
            String codeText;
            if (byAddress) {
                long addressValue;
                long valueValue;
                try {
                    String hex = address.getText().toString().trim().replaceFirst("^(0[xX])", "");
                    addressValue = Long.parseLong(hex, 16);
                    valueValue = Long.parseLong(value.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "주소(16진수)와 값(10진수)을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                long max = size[0] == 4 ? 0xFFFFFFFFL : (1L << (8 * size[0])) - 1;
                if (addressValue < 0 || addressValue > 0xFFFFFFFFL || valueValue < 0 || valueValue > max) {
                    Toast.makeText(this, size[0] + "바이트 값은 0~" + max + "까지예요.", Toast.LENGTH_SHORT).show();
                    return;
                }
                codeText = Cheats.addressCode(addressValue, valueValue, size[0]);
                // Keep the rest of a collection code (condition, repeat) when only these change.
                if (oldParts.length > 3) {
                    StringBuilder rest = new StringBuilder();
                    for (int k = 3; k < oldParts.length; k++) {
                        rest.append(':').append(oldParts[k]);
                    }
                    codeText += rest;
                }
            } else {
                codeText = Cheats.codeFromInput(code.getText().toString());
                if (!codeText.isEmpty() && isN3ds() && Cheats.n3dsDescribe(codeText).startsWith("✗")) {
                    Toast.makeText(this, "Gateway 코드 형식이 아니에요. \"XXXXXXXX YYYYYYYY\" 줄로 넣어 주세요.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!codeText.isEmpty() && isPs2() && Cheats.ps2Lines(codeText) == null) {
                    Toast.makeText(this, "PS2 코드로 읽지 못했어요. pnach 줄이나 풀린 RAW 코드로 넣어 주세요.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!codeText.isEmpty() && isDolphin()) {
                    if (Cheats.dolphinDescribe(codeText, dolphinKind[0], isWiiGame()).startsWith("✗")) {
                        Toast.makeText(this, "코드 형식이 아니에요. \"XXXXXXXX YYYYYYYY\" 줄로 넣어 주세요.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    codeText = Cheats.dolphinCode(codeText, dolphinKind[0], isWiiGame());
                }
            }
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

    private static int cheatSize(String text) {
        return "1".equals(text) ? 1 : "2".equals(text) ? 2 : 4;
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
    private String cheatFileName(android.net.Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name;
            }
        } catch (RuntimeException e) {
            // Fall through.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    private void importCheatFile(android.net.Uri uri) {
        java.util.List<Cheats.Cheat> found;
        boolean pnach = false;
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
            String text = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            String fileName = cheatFileName(uri);
            // PS2: PCSX2's .pnach files, each [section] a cheat of its own.
            pnach = isPs2() && (fileName.toLowerCase(Locale.US).endsWith(".pnach") || !text.contains("cheat0_"));
            found = pnach ? Cheats.parsePnach(text, fileName) : Cheats.parse(text);
            if (found.isEmpty() && !pnach) {
                // A plain list ("#Name" and "8007F5FC 0005" lines), as PS1 code sites give them.
                found = Cheats.parsePlain(text);
            }
        } catch (IOException | RuntimeException e) {
            found = new java.util.ArrayList<>();
        }
        if (found.isEmpty()) {
            Toast.makeText(this, isPs2() ? "이 파일에서 치트를 찾지 못했어요. (PCSX2 .pnach나 .cht 파일인지 확인해 주세요)"
                    : "이 파일에서 치트를 찾지 못했어요. (.cht 파일이나 \"#이름\" 아래 코드가 적힌 .txt인지 확인해 주세요)", Toast.LENGTH_LONG).show();
            return;
        }
        int patches = 0;
        int later = 0;
        if (pnach) {
            for (Cheats.Cheat cheat : found) {
                if (Cheats.isPnachPatch(cheat.desc)) patches++;
                if (Cheats.ps2Writes(cheat.code) == null) later++;
            }
        }
        for (Cheats.Cheat cheat : found) {
            cheat.on = false;
            cheat.custom = true;
        }
        cheats().addAll(firstCollectionCheat(), found);
        cheatsChanged();
        if (pnach && (patches > 0 || later > 0)) {
            new AppDialog.Builder(this).setTitle(".pnach에서 " + found.size() + "개를 가져왔어요")
                    .setMessage((later > 0 ? "조건 · 포인터 · 시작할 때 한 번 쓰는 코드가 있는 " + later + "개는 켠 뒤 게임을 다시 시작하면 적용돼요. 나머지는 바로 켜고 꺼져요.\n\n" : "")
                            + (patches > 0 ? "와이드스크린 · 화면 비율처럼 게임을 고치는 묶음이 " + patches + "개 있어요. 이런 건 게임 목록에서 게임을 길게 눌러 패치 칸에 넣는 게 더 맞아요 (치트로 켜도 동작은 해요)." : "").trim())
                    .setPositiveButton("확인", null).show();
        } else {
            Toast.makeText(this, "치트 " + found.size() + "개를 가져왔어요.", Toast.LENGTH_SHORT).show();
        }
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
                ring.setStroke(dp(2), Theme.line(on ? CHEAT_GREEN : Color.rgb(201, 206, 214)));
                ring.setColor(Theme.bg(Color.WHITE));
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
    // Engines that draw with the GPU, where FSR can enlarge the picture.
    private boolean is3d() {
        return "ppsspp".equals(this.coreId) || "pcee2".equals(this.coreId) || "play".equals(this.coreId)
                || "mupen64plus_next".equals(this.coreId) || "flycast".equals(this.coreId) || "yabasanshiro".equals(this.coreId);
    }

    // ---- 화면 필터 (0.0.360): the four of before, FSR for 3D, and shader presets — CRT,
    // curved CRT, handheld LCD, dot matrix, smooth pixels. Chosen per console (or per game),
    // with a fitting default per console until one is chosen. ----
    private static final int[] FILTER_ORDER = {0, 1, 5, 6, 7, 8, 9, 2, 3, 4};

    private static String filterName(int filter) {
        switch (filter) {
            case 1: return "선명하게";
            case 2: return "스캔라인";
            case 3: return "LCD 격자";
            case 4: return "FSR 업스케일";
            case 5: return "CRT 브라운관";
            case 6: return "CRT 곡면";
            case 7: return "휴대기 LCD";
            case 8: return "도트 매트릭스";
            case 9: return "도트 매끄럽게";
            default: return "기본";
        }
    }

    private static String filterNote(int filter) {
        switch (filter) {
            case 1: return "도트 그대로";
            case 2: return "가로 줄무늬";
            case 3: return "격자";
            case 4: return "3D를 또렷하게 키워요";
            case 5: return "주사선 + RGB 마스크 + 밝기";
            case 6: return "둥근 화면 · 가장자리 어둡게";
            case 7: return "격자 · 잔상 · 색 보정";
            case 8: return "게임보이 녹색 화면";
            case 9: return "계단을 곡선으로 (Scale2x)";
            default: return "부드럽게 확대";
        }
    }

    // Engines whose frames the app gets as pixels (not drawn on the GPU): the presets that
    // change the frame itself work there.
    private boolean softwareFrames() {
        return !is3d() && !isN3ds() && !isWiiU() && !isVita() && !isDolphin() && !isPs2() && !isSwan();
    }

    // Engines whose picture the app shows from the GPU (presentHwFrame), where FSR and 3D 다듬기
    // work: the 3D consoles and, from 0.0.371, the 3DS, GameCube · Wii, Wii U, Vita and
    // PS1's SwanStation.
    private boolean gpuFrames() {
        return is3d() || isN3ds() || isDolphin() || isWiiU() || isVita() || isSwan();
    }

    private boolean filterAvailable(int filter) {
        if (filter == 4) {
            return gpuFrames();
        }
        if (filter == 7 || filter == 8 || filter == 9) {
            return softwareFrames();
        }
        return filter >= 0 && filter <= 9;
    }

    // What suits the console (게임기 기본 필터).
    private int consoleDefaultFilter() {
        if (isGameBoy()) {
            String rom = this.romPath == null ? "" : this.romPath.toLowerCase(Locale.US);
            return rom.endsWith(".gbc") || rom.endsWith(".cgb") ? 7 : 8;
        }
        switch (this.gameMode == null ? "" : this.gameMode) {
            case "nes": case "snes": case "md": case "pce": case "sms": case "mcd":
            case "ps1": case "ss": case "msx": case "pc98": case "dos":
                return 5;
            case "arcade":
                return 6;
            case "gba": case "ws": case "ngp":
                return 7;
            default:
                return 0;
        }
    }

    private String filterGameKey() {
        return "filter_game:" + this.saveBaseName;
    }

    private boolean filterPerGame() {
        return MainActivity.sharedSettings(this).getBoolean("filter_game_on:" + this.saveBaseName, false);
    }

    private int screenFilter() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        int value;
        if (filterPerGame() && prefs.contains(filterGameKey())) {
            value = prefs.getInt(filterGameKey(), 0);
        } else if (prefs.contains("filter_con:" + this.gameMode)) {
            value = prefs.getInt("filter_con:" + this.gameMode, 0);
        } else if (prefs.getInt("screen_filter", 0) != 0) {
            value = prefs.getInt("screen_filter", 0);   // one filter for all, before 0.0.360
        } else {
            value = prefs.getBoolean("filter_defaults", true) ? consoleDefaultFilter() : 0;
        }
        return filterAvailable(value) ? value : 0;
    }

    private int filterInt(String what, int fallback) {
        return Math.max(0, Math.min(100, MainActivity.sharedSettings(this).getInt("filter_" + what + ":" + this.gameMode, fallback)));
    }

    private int filterStrength() {
        return filterInt("str", isPs1() || isSaturn() ? 40 : 70);
    }

    private int filterBright() {
        return filterInt("bright", 60);
    }

    private int filterCurve() {
        return filterInt("curve", 50);
    }

    // ---- 3D 다듬기 (0.0.369): FXAA, sharpening and colour for the 3D consoles, on top of the
    // chosen filter. Kept as "fxaa,sharp,grade" per console (fx3d_con:<mode>) or, with
    // 이 게임만 다르게, per game (fx3d_game:<game>). ----
    private static final String[] FX3D_GRADES = {"원래대로", "자연스러운 색", "또렷한 색"};

    private String fx3dKey() {
        return filterPerGame() ? "fx3d_game:" + this.saveBaseName : "fx3d_con:" + this.gameMode;
    }

    private int[] fx3d() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        String saved = filterPerGame() && prefs.contains("fx3d_game:" + this.saveBaseName)
                ? prefs.getString("fx3d_game:" + this.saveBaseName, "") : prefs.getString("fx3d_con:" + this.gameMode, "");
        int[] values = {0, 0, 0};
        String[] parts = saved.split(",");
        for (int k = 0; k < 3 && k < parts.length; k++) {
            try {
                values[k] = Integer.parseInt(parts[k].trim());
            } catch (NumberFormatException e) {
                values[k] = 0;
            }
        }
        values[0] = values[0] == 1 ? 1 : 0;
        values[1] = Math.max(0, Math.min(100, values[1]));
        values[2] = values[2] >= 0 && values[2] <= 2 ? values[2] : 0;
        return values;
    }

    private void saveFx3d(int[] values) {
        MainActivity.sharedSettings(this).edit().putString(fx3dKey(), values[0] + "," + values[1] + "," + values[2]).apply();
        applyScreenFilterToNative(screenFilter());
    }

    private void applyScreenFilterToNative(int filter) {
        // whole-pixel enlarging so the lines drawn over the picture meet the game's pixels
        NativeGba.setSharpScaling((filter >= 1 && filter <= 3) || filter >= 5);
        NativeGba.setFsr(filter == 4);
        int[] look = gpuFrames() ? fx3d() : new int[]{0, 0, 0};
        NativeGba.setFx3d(look[0] == 1, look[1], look[2]);
        int fx = filter == 5 || filter == 6 ? 1 : filter == 7 ? 2 : filter == 8 ? 3 : filter == 9 ? 4 : 0;
        NativeGba.setScreenFx(this.nativeHandle, softwareFrames() ? fx : 0, filter == 5 || filter == 6 ? filterBright() : filterStrength());
    }

    private void showScreenFilterSheet(Runnable refresh) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        scroll.addView(panel);
        TextView title = new TextView(this);
        title.setText("화면 필터");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        final TextView sub = new TextView(this);
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(10));
        panel.addView(sub);
        final int purple = Color.rgb(124, 58, 237);
        final android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(2);
        panel.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        final java.util.List<FilterPreview> previews = new java.util.ArrayList<>();
        final Runnable[] paint = new Runnable[1];
        final java.util.List<Integer> shown = new java.util.ArrayList<>();
        for (int f : FILTER_ORDER) {
            if (filterAvailable(f)) {
                shown.add(f);
            }
        }
        int cell = (Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)) - dp(32) - dp(12)) / 2;
        final java.util.List<LinearLayout> tiles = new java.util.ArrayList<>();
        for (final int f : shown) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(1);
            tile.setPadding(dp(5), dp(5), dp(5), dp(6));
            FilterPreview preview = new FilterPreview(this, f);
            previews.add(preview);
            tile.addView(preview, new LinearLayout.LayoutParams(-1, Math.round(cell * 0.62f)));
            TextView name = new TextView(this);
            name.setText(filterName(f) + (f >= 5 ? " ✦" : ""));
            name.setTextSize(12.0f);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setPadding(dp(2), dp(4), 0, 0);
            tile.addView(name);
            TextView note = new TextView(this);
            note.setText(filterNote(f));
            note.setTextSize(10.0f);
            note.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
            note.setPadding(dp(2), 0, 0, 0);
            tile.addView(note);
            tile.setTag(f);
            tile.setOnClickListener(v -> {
                prefs.edit().putInt(filterPerGame() ? filterGameKey() : "filter_con:" + this.gameMode, f).apply();
                applyScreenFilterToNative(screenFilter());
                updateScreenFilters();
                paint[0].run();
            });
            android.widget.GridLayout.LayoutParams cellParams = new android.widget.GridLayout.LayoutParams();
            cellParams.width = cell;
            cellParams.height = -2;
            cellParams.setMargins(dp(2), dp(2), dp(4), dp(6));
            grid.addView(tile, cellParams);
            tiles.add(tile);
        }
        final TextView[] sliderLabels = new TextView[3];
        final android.widget.SeekBar[] sliders = new android.widget.SeekBar[3];
        final String[] keys = {"str", "bright", "curve"};
        final String[] names = {"효과 세기", "밝기 보정 (CRT)", "곡면 (CRT 곡면)"};
        for (int k = 0; k < 3; k++) {
            final int index = k;
            TextView label = new TextView(this);
            label.setTextSize(12.0f);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
            label.setPadding(dp(2), dp(10), 0, 0);
            panel.addView(label);
            android.widget.SeekBar bar = new android.widget.SeekBar(this);
            bar.setMax(100);
            bar.setProgress(k == 0 ? filterStrength() : k == 1 ? filterBright() : filterCurve());
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar b, int progress, boolean user) {
                    sliderLabels[index].setText(names[index] + " · " + progress);
                    if (user) {
                        prefs.edit().putInt("filter_" + keys[index] + ":" + gameMode, progress).apply();
                        for (FilterPreview p : previews) {
                            p.setLook(filterStrength(), filterCurve(), filterBright());
                        }
                    }
                }

                public void onStartTrackingTouch(android.widget.SeekBar b) {
                }

                public void onStopTrackingTouch(android.widget.SeekBar b) {
                    applyScreenFilterToNative(screenFilter());
                    updateScreenFilters();
                }
            });
            sliderLabels[k] = label;
            sliders[k] = bar;
            label.setText(names[k] + " · " + bar.getProgress());
            panel.addView(bar);
        }
        if (gpuFrames()) {
            addFx3dRows(panel);
        }
        panel.addView(filterSwitchRow("이 게임만 다르게", "끄면 " + consoleName() + "에 고른 필터를 따라요", "filter_game_on:" + this.saveBaseName, false, on -> {
            if (on && !prefs.contains(filterGameKey())) {
                prefs.edit().putInt(filterGameKey(), screenFilter()).apply();
            }
            if (on && gpuFrames() && !prefs.contains("fx3d_game:" + this.saveBaseName)) {
                // The game starts from what the console has.
                String console = prefs.getString("fx3d_con:" + this.gameMode, "0,0,0");
                prefs.edit().putString("fx3d_game:" + this.saveBaseName, console).apply();
            }
            applyScreenFilterToNative(screenFilter());
            updateScreenFilters();
            paint[0].run();
        }));
        panel.addView(filterSwitchRow("게임기 기본 필터 쓰기", "아직 안 고른 게임기는 어울리는 필터로 (끄면 \"기본\")", "filter_defaults", true, on -> {
            applyScreenFilterToNative(screenFilter());
            updateScreenFilters();
            paint[0].run();
        }));
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.setMargins(0, dp(14), 0, 0);
        panel.addView(close, closeParams);
        paint[0] = () -> {
            int current = screenFilter();
            sub.setText(consoleName() + (filterPerGame() ? " · 이 게임만" : " · 이 게임기 전체") + " · 지금: " + filterName(current));
            for (LinearLayout tile : tiles) {
                boolean on = ((Integer) tile.getTag()) == current;
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Theme.bg(on ? Color.rgb(245, 243, 255) : Color.WHITE));
                shape.setCornerRadius(dp(12));
                shape.setStroke(dp(2), Theme.line(on ? purple : Color.rgb(229, 231, 235)));
                tile.setBackground(shape);
                ((TextView) tile.getChildAt(1)).setTextColor(Theme.text(on ? Color.rgb(91, 33, 182) : Color.rgb(55, 65, 81)));
            }
            sliderLabels[1].setVisibility(current == 5 || current == 6 ? View.VISIBLE : View.GONE);
            sliders[1].setVisibility(current == 5 || current == 6 ? View.VISIBLE : View.GONE);
            sliderLabels[2].setVisibility(current == 6 ? View.VISIBLE : View.GONE);
            sliders[2].setVisibility(current == 6 ? View.VISIBLE : View.GONE);
            boolean strong = current >= 5 && current <= 8;
            sliderLabels[0].setVisibility(strong ? View.VISIBLE : View.GONE);
            sliders[0].setVisibility(strong ? View.VISIBLE : View.GONE);
            for (FilterPreview p : previews) {
                p.setLook(filterStrength(), filterCurve(), filterBright());
            }
        };
        paint[0].run();
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(d -> refresh.run());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
        dialog.show();
    }

    private interface FilterToggle {
        void set(boolean on);
    }

    // The 3D 다듬기 part of the 화면 필터 sheet: FXAA, 선명하게+ and colour, applied as they change.
    private void addFx3dRows(LinearLayout panel) {
        final int purple = Color.rgb(124, 58, 237);
        TextView head = new TextView(this);
        head.setText("3D 다듬기 · 고른 필터와 함께 켜져요");
        head.setTextSize(13.0f);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setTextColor(Theme.text(Color.rgb(76, 29, 149)));
        head.setPadding(dp(2), dp(16), 0, dp(2));
        panel.addView(head);
        final int[] fx = fx3d();
        panel.addView(ps2Switch("계단 줄이기 (FXAA)", "3D 물체 모서리의 톱니를 부드럽게 · 가벼움", fx[0] == 1, on -> {
            fx[0] = on ? 1 : 0;
            saveFx3d(fx);
        }));
        final TextView sharpLabel = new TextView(this);
        sharpLabel.setTextSize(12.0f);
        sharpLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sharpLabel.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        sharpLabel.setPadding(dp(2), dp(10), 0, 0);
        sharpLabel.setText(fx[1] == 0 ? "선명하게+ · 끔" : "선명하게+ · " + fx[1]);
        panel.addView(sharpLabel);
        android.widget.SeekBar sharp = new android.widget.SeekBar(this);
        sharp.setMax(100);
        sharp.setProgress(fx[1]);
        sharp.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int progress, boolean user) {
                sharpLabel.setText(progress == 0 ? "선명하게+ · 끔" : "선명하게+ · " + progress);
            }

            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar b) {
                fx[1] = b.getProgress();
                saveFx3d(fx);
            }
        });
        panel.addView(sharp);
        TextView sharpNote = new TextView(this);
        sharpNote.setText("번진 3D를 또렷하게 (AMD CAS) · 0이면 끔");
        sharpNote.setTextSize(11.0f);
        sharpNote.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        sharpNote.setPadding(dp(2), 0, 0, 0);
        panel.addView(sharpNote);
        final LinearLayout gradeBox = new LinearLayout(this);
        gradeBox.setOrientation(1);
        gradeBox.setPadding(0, dp(8), 0, 0);
        panel.addView(gradeBox);
        final Runnable[] renderGrade = new Runnable[1];
        renderGrade[0] = () -> {
            gradeBox.removeAllViews();
            LinearLayout chips = backgroundChips("색", FX3D_GRADES, fx[2], purple, i -> {
                fx[2] = i;
                saveFx3d(fx);
                renderGrade[0].run();
            });
            chips.setPadding(0, 0, 0, 0);
            gradeBox.addView(chips);
        };
        renderGrade[0].run();
    }

    private LinearLayout filterSwitchRow(String title, String note, String key, boolean fallback, FilterToggle onChange) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        shape.setCornerRadius(dp(12));
        row.setBackground(shape);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(1);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.0f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        words.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextSize(11.0f);
        n.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(prefs.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            onChange.set(on);
        });
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);
        return row;
    }
    private static final String[] HAPTIC_NAMES = {"꺼짐", "약하게", "보통", "강하게"};
    private static final String[] HAPTIC_NOTES = {"진동 없음 (기본)", "살짝 톡", "또렷하게 톡", "확실하게 툭"};
    private final java.util.List<ScreenFilterView> screenFilters = new java.util.ArrayList<>();
    private final Runnable screenFilterPoll = new Runnable() {
        @Override
        public void run() {
            updateScreenFilters();
        }
    };


    private int hapticLevel() {
        int value = MainActivity.sharedSettings(this).getInt("haptic_level", 0);
        return value >= 0 && value <= 3 ? value : 0;
    }

    private void addScreenFilter(FrameLayout parent) {
        ScreenFilterView filter = new ScreenFilterView(this, this.surfaceView);
        this.screenFilters.clear();
        this.screenFilters.add(filter);
        parent.addView(filter, new FrameLayout.LayoutParams(-1, -1));
        // 화면 모양: the frame the picture sits in, and the corner mask over it.
        this.screenFrame = parent;
        ScreenMaskView mask = new ScreenMaskView(this);
        this.screenMask = mask;
        parent.addView(mask, new FrameLayout.LayoutParams(-1, -1));
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                v.post(this::applyScreenShape);
            }
        });
        parent.post(this::applyScreenShape);
        addGunCrosshair(parent);
        updateScreenFilters();
    }

    // The readout goes on the window root so it can sit on the case under the picture.
    // flat: the picture fills the window, so the text goes along its top edge instead.
    private void addPerfOverlay(FrameLayout root, boolean flat) {
        if (this.perfOverlay != null) {
            this.perfOverlay.removeCallbacks(this.perfPoll);
        }
        this.perfOverlay = new PerfOverlayView(this, this.surfaceView, flat);
        root.addView(this.perfOverlay, new FrameLayout.LayoutParams(-1, -1));
        updatePerfOverlay();
    }

    private ClearShellView shell;

    // ---- 성능 표시 (all games) and 전원 모드 (per game) ----

    private PerfOverlayView perfOverlay;
    private final Runnable perfPoll = new Runnable() {
        @Override
        public void run() {
            PerfOverlayView view = GameActivity.this.perfOverlay;
            if (view == null || !perfOverlayOn()) {
                return;
            }
            long handle = GameActivity.this.nativeHandle;
            view.update(handle != 0 ? NativeGba.frameStats(handle) : null);
            view.postDelayed(this, 1000);
        }
    };

    private boolean perfOverlayOn() {
        return MainActivity.sharedSettings(this).getBoolean("perf_overlay", false);
    }

    private void updatePerfOverlay() {
        PerfOverlayView view = this.perfOverlay;
        if (view == null) {
            return;
        }
        view.removeCallbacks(this.perfPoll);
        boolean on = perfOverlayOn();
        view.setVisibility(on ? View.VISIBLE : View.GONE);
        if (this.shell != null) {
            // The readout takes the engraved name's place under the picture.
            this.shell.setLabelHidden(on);
        }
        if (on) {
            view.restart();
            view.post(this.perfPoll);
        }
    }

    private static final String[] POWER_NAMES = {"성능", "균형", "절전"};
    private static final String[] POWER_SHORT = {"성능", "균형", "절전"};
    private static final String[] POWER_NOTES = {
            "고성능 코어에 붙이고 CPU 속도를 높게 유지. 무거운 기종의 기본",
            "CPU 속도를 휴대폰에 맡겨요. 가벼운 게임은 이걸로 충분하고 발열이 줄어요",
            "균형 + 화면을 한 장씩 건너뛰어 그려요 (게임 속도는 그대로). 2D 기기용 · 3D 기기는 균형과 같아요"};

    private String powerKey() {
        return "power:" + this.saveBaseName;
    }

    // Light consoles start on 균형: they run at full speed without pinning the fast cores,
    // and stay cooler (foldables heat up quickly). The rest start on 성능.
    private boolean lightSystem() {
        if ("fbneo".equals(this.coreId) || "mame2003plus".equals(this.coreId)) {
            return true;
        }
        switch (this.gameMode) {
            case "gba": case "snes": case "nes": case "md": case "sms": case "pce":
            case "ws": case "ngp": case "mcd":
                return true;
            default:
                return false;
        }
    }

    private int defaultPowerMode() {
        return lightSystem() ? 1 : 0;
    }

    private int powerMode() {
        int value = MainActivity.sharedSettings(this).getInt(powerKey(), defaultPowerMode());
        return value >= 0 && value <= 2 ? value : defaultPowerMode();
    }

    private boolean powerChosen() {
        return MainActivity.sharedSettings(this).contains(powerKey());
    }

    // Consoles whose engines keep a phone's CPU or GPU busy: told about once (발열 안내).
    private boolean heavySystem() {
        if ("mame2010".equals(this.coreId) || "mame2015".equals(this.coreId) || "mamearcade".equals(this.coreId)) {
            return true;
        }
        switch (this.gameMode) {
            case "ps2": case "3ds": case "ss": case "psp": case "dc": case "naomi": case "n64": case "wiiu": case "gc":
                return true;
            default:
                return false;
        }
    }

    private String heatKey() {
        return "heat_notice:" + ("arcade".equals(this.gameMode) || "neogeo".equals(this.gameMode) ? this.coreId : this.gameMode);
    }

    private String heatSystemName() {
        switch (this.coreId) {
            case "mame2010": return "아케이드 (MAME 2010)";
            case "mame2015": case "mamearcade": return "아케이드 (MAME 2015)";
            default: return consoleName();
        }
    }

    private View heatBanner;

    // The first game of a heavy console: a small notice over the top of the screen that
    // doesn't stop the game and goes away by itself; it opens 발열 줄이는 법.
    private void showStartNotices() {
        long delay = 0;
        if (wiiServer() != 0 && cheatsSupported() && Cheats.countOn(cheats()) > 0) {
            // Wiimmfi turns cheaters away; once per session, before going online.
            new AppDialog.Builder(this).setTitle("치트가 켜져 있어요")
                    .setMessage("Wiimmfi는 치트를 쓰면 접속을 막아요. 온라인 동안은 꺼 둘까요?")
                    .setNegativeButton("그대로", null)
                    .setPositiveButton("치트 끄기", (d, w) -> {
                        for (Cheats.Cheat cheat : cheats()) {
                            cheat.on = false;
                        }
                        cheatsChanged();
                        Toast.makeText(this, "치트를 모두 껐어요", Toast.LENGTH_SHORT).show();
                    }).show();
            return;
        }
        if (gcLanMode() != 0) {
            String tipKey = "gc_lan_tip:" + this.saveBaseName;
            if (!MainActivity.sharedSettings(this).getBoolean(tipKey, false)) {
                MainActivity.sharedSettings(this).edit().putBoolean(tipKey, true).apply();
                showInfoNotice("LAN 대전 켜짐", "게임 첫 화면에서 LAN 모드를 고르세요. 방장이 먼저 들어가고, 친구들이 뒤따라 들어오면 돼요", 8000);
                delay = 8500;
            }
        }
        if (motionSupported() && motionInput().available()) {
            String asked = "motion_asked:" + this.saveBaseName;
            if (!MainActivity.sharedSettings(this).getBoolean(asked, false)) {
                MainActivity.sharedSettings(this).edit().putBoolean(asked, true).apply();
                if (isGbaMotionCart()) {
                    showInfoNotice(isGameBoy() ? "기울이기 센서 카트리지예요" : "센서 카트리지예요",
                            "모션이 자동으로 켜졌어요. 휴대폰을 기울이거나 돌리면 게임에 들어가요. 화면의 '가운데로'로 기준을 다시 잡아요", 7000);
                    delay = 7500;
                } else {
                    showMotionPrompt();
                    return;
                }
            }
        }
        if (isVita() && !MainActivity.sharedSettings(this).getBoolean("vita_notice", false)) {
            MainActivity.sharedSettings(this).edit().putBoolean("vita_notice", true).apply();
            new AppDialog.Builder(this).setTitle("PS Vita 모드 · 실험적")
                    .setMessage("• 빠른 저장 · 이어하기 · 되감기가 없어요\n엔진이 장면 저장을 지원하지 않아요. 게임 안에서 저장해 주세요.\n\n"
                            + "• 터치\n게임 화면을 누르면 앞면 터치예요. L2를 누른 채 화면을 누르면 뒷면 터치예요.\n\n"
                            + "• 처음 실행\n.vpk를 처음 켤 때 한 번 설치해요(몇 분). 장면마다 셰이더를 처음 만들 때 잠깐 멈춰요.")
                    .setPositiveButton("알겠어요", null).show();
            return;
        }
        if (isWiiU() && !MainActivity.sharedSettings(this).getBoolean("wiiu_notice", false)) {
            MainActivity.sharedSettings(this).edit().putBoolean("wiiu_notice", true).apply();
            new AppDialog.Builder(this).setTitle("Wii U 모드 · 실험적")
                    .setMessage("• 이어하기가 없어요\n나가면 다음엔 타이틀부터예요. 게임 안에서 저장해 주세요. 되감기 · 세이브 슬롯도 없어요.\n\n"
                            + "• 처음엔 끊겨요\n장면마다 셰이더를 처음 만들 때 잠깐 멈춰요. 한 번 지나간 곳은 다음부터 부드러워요.\n\n"
                            + "• 뜨거워질 수 있어요\n전원 모드는 '성능'으로 시작해요. 뜨거우면 게임기 탭에서 해상도를 낮춰 보세요.")
                    .setPositiveButton("알겠어요", null).show();
            return;
        }
        String stvKey = "stv_notice:" + this.saveBaseName;
        if (stvBoard() && !MainActivity.sharedSettings(this).getBoolean(stvKey, false)) {
            MainActivity.sharedSettings(this).edit().putBoolean(stvKey, true).apply();
            showInfoNotice("처음부터 시작했어요", "세가 ST-V 기판 게임은 이어하기를 하면 화면이 멈춰서, 항상 처음부터 켜요", 6000);
            delay = 6500;
        }
        if (delay == 0) {
            maybeShowHeatNotice();
        } else {
            getWindow().getDecorView().postDelayed(() -> {
                if (!this.destroyed) maybeShowHeatNotice();
            }, delay);
        }
    }

    private View infoNotice;

    private void showInfoNotice(String title, String detail, long millis) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.setBackground(roundRect(Color.rgb(239, 246, 255), dp(18)));
        bar.setElevation(dp(8));
        TextView icon = sheetText("i", 15.0f, Color.WHITE, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(Color.rgb(37, 99, 235), dp(16)));
        bar.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(sheetText(title, 13.5f, Color.rgb(30, 58, 138), true));
        texts.addView(sheetText(detail, 11.5f, Color.rgb(30, 64, 175), false));
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(10), 0, 0, 0);
        bar.addView(texts, textsParams);
        bar.setOnClickListener(view -> removeNotice(bar));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        params.setMargins(dp(12), dp(22), dp(12), 0);
        addContentView(bar, params);
        this.infoNotice = bar;
        bar.postDelayed(() -> removeNotice(bar), millis);
    }

    private void removeNotice(View bar) {
        if (this.infoNotice == bar) {
            this.infoNotice = null;
        }
        if (bar.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) bar.getParent()).removeView(bar);
        }
    }

    private void maybeShowHeatNotice() {
        if (!heavySystem() || MainActivity.sharedSettings(this).getBoolean(heatKey(), false)) {
            return;
        }
        MainActivity.sharedSettings(this).edit().putBoolean(heatKey(), true).apply();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(10), dp(10));
        bar.setBackground(roundRect(Color.rgb(255, 247, 237), dp(18)));
        bar.setElevation(dp(8));
        bar.addView(new RoundIconView(this, RoundIconView.PERF, HEAT_ORANGE), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(sheetText("발열이 큰 기종이에요", 13.5f, Color.rgb(154, 52, 18), true));
        TextView sub = sheetText(heatSystemName() + " · 오래 하면 폰이 뜨거워질 수 있어요", 11.5f, Color.rgb(154, 52, 18), false);
        sub.setMaxLines(2);
        texts.addView(sub);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(10), 0, dp(8), 0);
        bar.addView(texts, textsParams);
        TextView open = sheetText("줄이는 법 ›", 12.0f, Color.WHITE, true);
        open.setPadding(dp(10), dp(7), dp(10), dp(7));
        open.setBackground(roundRect(HEAT_ORANGE, dp(10)));
        bar.addView(open);
        View.OnClickListener show = view -> {
            hideHeatBanner();
            showHeatTips(null);
        };
        open.setOnClickListener(show);
        bar.setOnClickListener(show);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        params.setMargins(dp(12), dp(22), dp(12), 0);
        addContentView(bar, params);
        this.heatBanner = bar;
        bar.postDelayed(() -> {
            if (this.heatBanner == bar) hideHeatBanner();
        }, 8000);
    }

    private void hideHeatBanner() {
        View bar = this.heatBanner;
        this.heatBanner = null;
        if (bar != null && bar.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) bar.getParent()).removeView(bar);
        }
    }

    private static final int HEAT_ORANGE = Color.rgb(249, 115, 22);

    // 발열 줄이는 법: what can be changed right away for this game (opened from the notice,
    // or from the pause menu, which is then passed in so a change can close it).
    private void showHeatTips(android.app.Dialog menu) {
        if (menu == null) {
            this.holdPause = true;
            long handle = this.nativeHandle;
            if (handle != 0) {
                NativeGba.pause(handle, true);
            }
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        panel.addView(sheetHeader(RoundIconView.PERF, HEAT_ORANGE, "발열 줄이는 법", this.gameTitle + " · " + heatSystemName(), null));
        final android.app.Dialog[] holder = new android.app.Dialog[1];
        int scale = isPsp() ? pspScale() : isN3ds() ? n3dsScale() : isN64() ? n64Scale() : 1;
        if (scale > 1) {
            final String key = (isPsp() ? "psp_scale:" : isN3ds() ? "n3ds_scale:" : "n64_scale:") + this.saveBaseName;
            panel.addView(createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 1배로", "지금 " + scale + "배 · 바꾸기 ›", view -> {
                MainActivity.sharedSettings(this).edit().putInt(key, 1).commit();
                holder[0].dismiss();
                if (menu != null) menu.dismiss();
                relaunchGame();
            }), marginTop(-1, dp(52), 14));
        }
        if (speedFirstSupported() && !speedFirst()) {
            panel.addView(createSettingRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선 켜기", "켜기 ›", view -> {
                MainActivity.sharedSettings(this).edit().putBoolean(speedFirstKey(), true).commit();
                holder[0].dismiss();
                if (menu != null) menu.dismiss();
                relaunchGame();
            }), marginTop(-1, dp(52), scale > 1 ? 8 : 14));
        }
        if (powerMode() == 0) {
            LinearLayout power = createSettingRow(RoundIconView.BATTERY, RoundIconView.COLOR_BATTERY, "전원 모드 균형", "바꾸기 ›", null);
            power.setOnClickListener(view -> {
                setPowerModeNow(1);
                power.setVisibility(View.GONE);
                Toast.makeText(this, "균형으로 바꿨어요. 느려지면 설정 › 전원 모드에서 되돌리세요.", Toast.LENGTH_SHORT).show();
            });
            panel.addView(power, marginTop(-1, dp(52), scale > 1 ? 8 : 14));
        }
        if (!perfOverlayOn()) {
            LinearLayout perf = createSettingRow(RoundIconView.PERF, RoundIconView.COLOR_PERF, "성능 표시 켜기", "켜기 ›", null);
            perf.setOnClickListener(view -> {
                MainActivity.sharedSettings(this).edit().putBoolean("perf_overlay", true).commit();
                updatePerfOverlay();
                perf.setVisibility(View.GONE);
                Toast.makeText(this, "게임 화면 왼쪽 위에 온도 · 속도가 보여요.", Toast.LENGTH_SHORT).show();
            });
            panel.addView(perf, marginTop(-1, dp(52), 8));
        }
        java.util.List<String> notes = new java.util.ArrayList<>();
        if (powerMode() == 0) {
            notes.add("균형으로 바꿔서 게임이 느려지면 설정 › 전원 모드에서 성능으로 되돌리세요.");
        }
        if (!isPsp() && !isN3ds() && !isN64()) {
            notes.add("이 기종은 앱에서 화질을 바꾸는 설정이 없어요. 오래 할 땐 중간에 쉬어 주세요.");
        }
        for (String text : notes) {
            TextView note = sheetText(text, 11.5f, Color.rgb(55, 65, 81), false);
            note.setLineSpacing(0, 1.15f);
            note.setPadding(dp(12), dp(10), dp(12), dp(10));
            note.setBackground(roundRect(Color.rgb(246, 247, 249), dp(12)));
            panel.addView(note, marginTop(-1, -2, 10));
        }
        TextView warm = sheetText("충전하면서 하거나 두꺼운 케이스를 끼우면 더 뜨거워져요. 폰이 뜨거워지면 휴대폰이 스스로 속도를 낮춰서 게임이 느려질 수 있어요.",
                11.5f, Color.rgb(154, 52, 18), false);
        warm.setLineSpacing(0, 1.15f);
        warm.setPadding(dp(12), dp(10), dp(12), dp(10));
        warm.setBackground(roundRect(Color.rgb(255, 247, 237), dp(12)));
        panel.addView(warm, marginTop(-1, -2, 10));
        android.widget.CheckBox again = new android.widget.CheckBox(this);
        again.setText(heatSystemName() + "는 다시 알리지 않기");
        again.setTextSize(12.5f);
        again.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
        again.setButtonTintList(android.content.res.ColorStateList.valueOf(HEAT_ORANGE));
        again.setChecked(MainActivity.sharedSettings(this).getBoolean(heatKey(), false));
        panel.addView(again, marginTop(-2, -2, 8));
        TextView close = dialogButton(menu == null ? "닫고 계속하기" : "닫기", Color.rgb(241, 242, 244), Color.rgb(55, 65, 81));
        panel.addView(close, marginTop(-1, dp(46), 10));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        final android.app.Dialog dialog = cardDialog(scroll, 0);
        holder[0] = dialog;
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            MainActivity.sharedSettings(this).edit().putBoolean(heatKey(), again.isChecked()).apply();
            if (menu == null) {
                this.holdPause = false;
                resumeUnlessExiting();
            }
        });
        dialog.show();
    }

    private LinearLayout createPerfRow() {
        LinearLayout row = createSettingRow(RoundIconView.PERF, RoundIconView.COLOR_PERF, "성능 표시", perfOverlayOn() ? "켜짐" : "꺼짐", null);
        final TextView value = (TextView) row.getChildAt(row.getChildCount() - 1);
        row.setOnClickListener(view -> showChoiceDialog(RoundIconView.PERF, RoundIconView.COLOR_PERF, "성능 표시",
                new String[]{"꺼짐", "켜짐"}, new String[]{"표시 없음 (기본)", "게임 화면 왼쪽 위에 FPS·속도, 온도, 배터리"},
                perfOverlayOn() ? 1 : 0, "모든 게임에 같이 적용돼요. 온도는 CPU 센서를 못 읽는 폰에선 배터리 온도로 보여요.\n드림캐스트의 FPS는 게임이 실제로 새로 그린 장수예요. 30장만 그리는 게임이 많아 30 근처로 보여도 게임 속도는 정상이에요(속도 %로 확인).",
                Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용", choice -> {
            MainActivity.sharedSettings(this).edit().putBoolean("perf_overlay", choice == 1).commit();
            value.setText((choice == 1 ? "켜짐" : "꺼짐") + "  ›");
            updatePerfOverlay();
        }));
        return row;
    }

    // A game stuck on a screen (no crash) leaves no report: this saves the engine's last log
    // lines as one, for 설정 → 오류 보고.
    private LinearLayout createLogSaveRow() {
        LinearLayout row = createSettingRow(RoundIconView.WARN, Color.rgb(100, 116, 139), "멈춤 기록 저장", "저장  ›", null);
        row.setOnClickListener(view -> {
            long start = MainActivity.sharedSettings(this).getLong(SESSION_START, 0);
            CrashReports.saveManual(this, start);
            Toast.makeText(this, "지금까지의 엔진 기록을 저장했어요 · 설정 → 오류 보고에서 보낼 수 있어요", Toast.LENGTH_LONG).show();
        });
        return row;
    }

    private LinearLayout createPowerRow(android.app.Dialog menu) {
        String[] names = POWER_NAMES.clone();
        names[defaultPowerMode()] += " · " + consoleName() + " 기본";
        String note = "이 게임에만 적용 · 바로 적용돼요. 게임을 다시 켜지 않아서 지금 장면 그대로 이어져요.\n성능 표시를 켜 두면 온도와 배터리 소모가 어떻게 달라지는지 바로 볼 수 있어요.";
        if (lightSystem()) {
            note = consoleName() + "처럼 가벼운 기종은 처음부터 균형으로 시작해요. 여기서 직접 고르면 이 게임은 그 값으로 기억돼요.\n" + note;
        }
        final String footer = note;
        LinearLayout row = createSettingRow(RoundIconView.BATTERY, RoundIconView.COLOR_BATTERY, "전원 모드", powerValueText(), null);
        final TextView value = (TextView) row.getChildAt(row.getChildCount() - 1);
        row.setOnClickListener(view -> showChoiceDialog(RoundIconView.BATTERY, RoundIconView.COLOR_BATTERY, "전원 모드", names, POWER_NOTES,
                powerMode(), footer, Color.rgb(236, 253, 243), Color.rgb(22, 101, 52), "적용", choice -> {
            if (choice == powerMode() && powerChosen()) {
                return;
            }
            setPowerModeNow(choice);
            value.setText(powerValueText() + "  ›");
        }));
        return row;
    }

    private String powerValueText() {
        return POWER_SHORT[powerMode()] + (powerChosen() ? "" : " · 기본");
    }

    // Saves the game's power mode and switches the running engine to it at once (the
    // engine picks it up on its next frame; no restart).
    private void setPowerModeNow(int mode) {
        MainActivity.sharedSettings(this).edit().putInt(powerKey(), mode).commit();
        if (!isMame139()) {
            NativeGba.setPowerMode(mode);
        }
    }

    // Lines follow the game's picture size, which can change (menus, resolution switches),
    // so it is checked twice a second while a line filter is on.
    private void updateScreenFilters() {
        int filter = screenFilter();
        long handle = this.nativeHandle;
        int[] size = handle != 0 && ScreenFilterView.drawsLines(filter) ? NativeGba.frameSize(handle) : null;
        boolean integer = shapeScaleMode() == 1;
        if (integer) {
            applyScreenShape();
        }
        for (ScreenFilterView view : this.screenFilters) {
            view.setFilter(filter);
            view.setLook(filterStrength(), filterCurve());
            if (size != null && size.length == 2) {
                view.setGameSize(size[0], size[1]);
            }
            view.removeCallbacks(this.screenFilterPoll);
            if ((ScreenFilterView.drawsLines(filter) || integer) && !this.destroyed) {
                view.postDelayed(this.screenFilterPoll, 500);
            }
        }
    }

    // ---- 내비게이션 바 숨기기 (0.0.358): Android's own immersive mode for the bar at the
    // bottom; a swipe up from the edge shows it for a moment. All games, on by default. ----
    private static final String PREF_HIDE_NAV = "hide_nav_bar";

    private boolean hideNavigationBar() {
        return MainActivity.sharedSettings(this).getBoolean(PREF_HIDE_NAV, true);
    }

    @SuppressWarnings("deprecation")
    private void applyNavigationBar() {
        android.view.Window window = getWindow();
        if (window == null) {
            return;
        }
        boolean hide = hideNavigationBar();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            if (hide) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.navigationBars());
            } else {
                controller.show(android.view.WindowInsets.Type.navigationBars());
            }
        } else {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int bits = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(hide ? flags | bits : flags & ~bits);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // a dialog or another app may have brought the bar back
            applyNavigationBar();
        }
    }

    // 화면 탭: the picture and the case, button looks and layout (all games).
    private void addScreenTabRows(LinearLayout panel, Runnable refresh) {
        if (isSwan()) {
            addSwanQualityRows(panel, refresh);
        }
        if (isN3ds()) {
            LinearLayout.LayoutParams vkParams = new LinearLayout.LayoutParams(-1, dp(52));
            vkParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createN3dsGraphicsRow(), vkParams);
        }
        if ("pcee2".equals(this.coreId)) {
            LinearLayout.LayoutParams wsParams = new LinearLayout.LayoutParams(-1, dp(52));
            wsParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createPs2WidescreenRow(), wsParams);
        }
        if (isPsp()) {
            LinearLayout.LayoutParams texParams = new LinearLayout.LayoutParams(-1, dp(52));
            texParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createPspTextureRow(), texParams);
        }
        if (vmuSupported()) {
            LinearLayout.LayoutParams vmuParams = new LinearLayout.LayoutParams(-1, dp(52));
            vmuParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createVmuRow(), vmuParams);
        }
        if (isN3ds() || isNds()) {
            LinearLayout.LayoutParams layParams = new LinearLayout.LayoutParams(-1, dp(52));
            layParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createN3dsLayoutRow(refresh), layParams);
        }
        if (isRotatingArcadeCore() && arcadeVerticalKnown()) {
            LinearLayout.LayoutParams tateParams = new LinearLayout.LayoutParams(-1, dp(52));
            tateParams.setMargins(0, 0, 0, dp(8));
            panel.addView(createArcadeRotationRow(refresh), tateParams);
        }
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(52));
        navParams.setMargins(0, 0, 0, dp(8));
        panel.addView(createSettingRow(RoundIconView.MOVE, Color.rgb(71, 85, 105), "안드로이드 내비게이션 바", hideNavigationBar() ? "숨김 (아래에서 밀어 올리면 잠깐 보임)" : "보임",
                view -> showChoiceDialog(RoundIconView.MOVE, Color.rgb(71, 85, 105), "안드로이드 내비게이션 바 · 모든 게임",
                        new String[]{"숨기기 (기본)", "보이기"},
                        new String[]{"게임 중 아래의 ◁ ○ ||| 바를 숨겨 화면을 넓게 써요. 화면 아래 끝에서 위로 밀면 잠깐 나타났다가 다시 숨어요",
                                "늘 보이게 둬요"},
                        hideNavigationBar() ? 0 : 1, "모든 게임에 바로 적용돼요.",
                        Color.rgb(241, 245, 249), Color.rgb(51, 65, 85), "적용", choice -> {
                    MainActivity.sharedSettings(this).edit().putBoolean(PREF_HIDE_NAV, choice == 0).apply();
                    applyNavigationBar();
                    refresh.run();
                })), navParams);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(-1, dp(52));
        filterParams.setMargins(0, 0, 0, 0);
        boolean threeD = is3d();
        panel.addView(createSettingRow(RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "화면 필터", filterName(screenFilter()),
                view -> showScreenFilterSheet(refresh)), filterParams);
        if (!isPadScreen()) {
            LinearLayout.LayoutParams shapeParams = new LinearLayout.LayoutParams(-1, dp(52));
            shapeParams.setMargins(0, dp(8), 0, 0);
            String shapeShown = (shapeScaleMode() == 1 ? "픽셀 정확" : "맞춤") + (shapeAspectMode() != 0 ? " · " + SHAPE_ASPECT_NAMES[shapeAspectMode()] : "")
                    + (shapeCornerDp() > 0 ? " · 둥근 " + shapeCornerDp() : "");
            panel.addView(createSettingRow(RoundIconView.HD, RoundIconView.COLOR_FILTER, "화면 모양", shapeShown, view -> {
                showScreenShapeSheet(refresh);
            }), shapeParams);
        }
        if (!isPadScreen()) {
            // The controller-only screen has no case behind it.
            LinearLayout.LayoutParams styleParams = new LinearLayout.LayoutParams(-1, dp(52));
            styleParams.setMargins(0, dp(8), 0, 0);
            // Kinds and 포인트 색 are picked in the 배경 window; the row shows them.
            String styleShown = shellStyleName() + (shellModeOnly() ? "\n" + consoleName() + " 모드만" : "");
            panel.addView(createSettingRow(RoundIconView.PALETTE, RoundIconView.COLOR_PALETTE, "배경", styleShown,
                    view -> showBackgroundDialog(refresh)), styleParams);
        }
        if (!isPadScreen()) {
            LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(-1, dp(52));
            editParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.MOVE, RoundIconView.COLOR_MOVE, "버튼 · 화면 배치 편집",
                    hasLayoutEdits() ? "바꾼 배치" : "기본 배치", view -> {
                        startLayoutEditor();
                    }), editParams);
            LinearLayout.LayoutParams styleRowParams = new LinearLayout.LayoutParams(-1, dp(52));
            styleRowParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.PALETTE, RoundIconView.COLOR_FILTER, "버튼 꾸미기",
                    buttonStyleSummary(), view -> {
                        openButtonStyleDialog();
                    }), styleRowParams);
            LinearLayout.LayoutParams presetRowParams = new LinearLayout.LayoutParams(-1, dp(52));
            presetRowParams.setMargins(0, dp(8), 0, 0);
            int presetCount = 0;
            for (Presets.Preset preset : Presets.list(this)) {
                if (!Presets.UNDO_ID.equals(preset.id)) presetCount++;
            }
            panel.addView(createSettingRow(RoundIconView.STAR, RoundIconView.COLOR_SAVE, "꾸미기 프리셋",
                    presetCount == 0 ? "저장 · 적용" : "저장된 것 " + presetCount, view -> {
                        openPresetDialog();
                    }), presetRowParams);
        }
    }

    // 조작 탭: vibration, combos, sticks, rewind and game controllers.
    private void addControlTabRows(LinearLayout panel, Runnable refresh) {
        addMotionRows(panel, this.pauseDialog, refresh);
        if (gunSetup() != null && !isPadScreen()) {
            LinearLayout.LayoutParams gunParams = new LinearLayout.LayoutParams(-1, dp(52));
            gunParams.setMargins(0, panel.getChildCount() == 0 ? 0 : dp(8), 0, 0);
            panel.addView(createGunRow(), gunParams);
        }
        if (!isPadScreen()) {
            LinearLayout.LayoutParams turboParams = new LinearLayout.LayoutParams(-1, dp(52));
            turboParams.setMargins(0, panel.getChildCount() == 0 ? 0 : dp(8), 0, 0);
            panel.addView(createTurboRow(refresh), turboParams);
        }
        if (!isPadScreen()) {
            LinearLayout.LayoutParams hapticParams = new LinearLayout.LayoutParams(-1, dp(52));
            hapticParams.setMargins(0, panel.getChildCount() == 0 ? 0 : dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", HAPTIC_NAMES[hapticLevel()],
                    view -> showChoiceDialog(RoundIconView.VIBRATE, RoundIconView.COLOR_VIBRATE, "버튼 진동", HAPTIC_NAMES, HAPTIC_NOTES,
                            hapticLevel(), "화면 버튼을 누를 때 짧게 진동해요.\n휴대폰이 무음·진동 끔이어도 울릴 수 있어요.",
                            Color.rgb(243, 240, 255), Color.rgb(91, 33, 182), "적용", choice -> {
                        MainActivity.sharedSettings(this).edit().putInt("haptic_level", choice).apply();
                        Haptics.setLevel(this, choice);
                        Haptics.tap();
                        refresh.run();
                    })), hapticParams);
            LinearLayout.LayoutParams comboParams = new LinearLayout.LayoutParams(-1, dp(52));
            comboParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createComboRow(refresh), comboParams);
            LinearLayout.LayoutParams slideParams = new LinearLayout.LayoutParams(-1, dp(52));
            slideParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSlideRow(refresh), slideParams);
            if (isDualShock() || isWiiU() || (isDolphin() && (!isWiiGame() || wiiControl() == 2))) {
                LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(-1, dp(52));
                rightParams.setMargins(0, dp(8), 0, 0);
                panel.addView(createStickRow(true, refresh), rightParams);
            }
            if (hasLeftStick()) {
                LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(-1, dp(52));
                leftParams.setMargins(0, dp(8), 0, 0);
                panel.addView(createStickRow(false, refresh), leftParams);
            }
            LinearLayout.LayoutParams rewindParams = new LinearLayout.LayoutParams(-1, dp(52));
            rewindParams.setMargins(0, dp(8), 0, 0);
            boolean supported = rewindSupported();
            panel.addView(createSettingRow(RoundIconView.REWIND, RoundIconView.COLOR_REWIND, "되감기",
                    !supported ? (stvBoard() ? "이 기판은 지원 안 함" : "이 기종은 안 돼요") : rewindOn() ? "켜짐" : "꺼짐",
                    view -> {
                        if (!supported) {
                            Toast.makeText(this, stvBoard() ? "세가 ST-V 기판은 장면을 되돌리면 화면이 멈춰서 되감기를 쓸 수 없어요."
                                    : "PS2·새턴·드림캐스트는 상태 저장이 크고 무거워서 되감기를 쓸 수 없어요.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        showChoiceDialog(RoundIconView.REWIND, RoundIconView.COLOR_REWIND, "되감기", REWIND_NAMES, REWIND_NOTES,
                                rewindOn() ? 1 : 0, "켜면 배속 버튼 옆에 되감기 버튼이 생겨요.\n누르고 있는 동안 최대 약 10초 전까지 거꾸로 가요. 휴대폰 메모리를 조금 더 써요.",
                                Color.rgb(243, 240, 255), Color.rgb(91, 33, 182), "적용", choice -> {
                            MainActivity.sharedSettings(this).edit().putBoolean("rewind_enabled", choice == 1).apply();
                            long handle = this.nativeHandle;
                            if (handle != 0) {
                                NativeGba.setRewindEnabled(handle, rewindOn());
                            }
                            rebuildContent();
                            refresh.run();
                        });
                    }), rewindParams);
        }

        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-1, dp(52));
        padParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createPadMappingRow(refresh), padParams);
        if (consolePlayers() > 1 || multitapSupported()) {
            LinearLayout.LayoutParams playersParams = new LinearLayout.LayoutParams(-1, dp(52));
            playersParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createPlayersRow(), playersParams);
        }
        if (multitapSupported()) {
            LinearLayout.LayoutParams tapParams = new LinearLayout.LayoutParams(-1, dp(52));
            tapParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createMultitapRow(refresh), tapParams);
        }
        if (!isPadScreen() && !isMame139()) {
            LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(-1, dp(52));
            autoParams.setMargins(0, dp(8), 0, 0);
            String autoShown = padAutoForGame() == 1 ? "이 게임은 항상 터치" : padAutoForGame() == 2 ? "이 게임은 항상 숨김" : padPref("pad_auto_hide") ? "꽂으면 터치 숨김" : "꺼짐";
            panel.addView(createSettingRow(RoundIconView.PAD, RoundIconView.COLOR_MOVE, "게임패드 자동 전환", autoShown, view -> {
                showPadAutoSheet(refresh);
            }), autoParams);
        }

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
        addPlayTime();
        if (isMame139()) {
            Emulator.pause();
            return;
        }
        this.activityResumed = false;
        rememberDisc();
        onRewindHeld(false);
        stopMicPoll();
        if (this.dosControls != null) {
            this.dosControls.releaseAll();
        }
        if (isMsx()) {
            msxTyper.removeCallbacksAndMessages(null);
            MsxKeys.send(this.nativeHandle, 13, false);
            if (this.msxKeys != null) this.msxKeys.releaseAll();
            releaseMsxPadKeys();
        }
        if (this.motionInput != null) {
            this.motionInput.stop();
        }
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
        applyNavigationBar();
        this.playStart = System.currentTimeMillis();
        this.activityResumed = true;
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        startMicPoll();
        long j = this.nativeHandle;
        if (j != 0) {
            startMotionIfOn();
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
        if (requestCode == REQUEST_MSX_BIOS && resultCode == RESULT_OK && data != null) {
            importMsxBios(data);
        }
        if (requestCode == REQUEST_MSX_SLOT2 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importMsxSlot2(data.getData());
        }
        if (requestCode == REQUEST_FBNEO_INI && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importFbneoIni(data.getData());
        }
        if (requestCode == REQUEST_BG_EDITOR) {
            // Whatever was applied (or not), draw the case as it now stands.
            rebuildContent();
        }
        if (requestCode == REQUEST_STATE_FILE) {
            this.holdPause = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                loadStateFromUri(data.getData());
            } else {
                resumeUnlessExiting();
            }
        }
        if (requestCode == REQUEST_PRESET_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importPresetFile(data.getData());
            } else {
                this.holdPause = false;
                resumeUnlessExiting();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // The room closes with the host's game (and starts again if the game is relaunched).
        WfcRoom.stop();
        unregisterPadListener();
        unregisterThermalListener();
        stopMicPoll();
        this.adhocBeacon.stop();
        if (this.directLink != null) {
            if (this.menuExiting) {
                this.directLink.detach();
            } else {
                this.directLink.release();
            }
        }
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
                MainActivity.sharedSettings(this).edit().putBoolean(SESSION_CLOSED, true).commit();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
        coreWorker.shutdown();
        if (finishing) {
            // An engine stuck inside a frame never returns; leave anyway. The player left on
            // purpose, so this is a closed session, not a crash to report (PS Vita's engine
            // waits up to 5 s for the game's threads when it stops, which reached here).
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    // Exit now.
                }
                MainActivity.sharedSettings(this).edit().putBoolean(SESSION_CLOSED, true).commit();
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

    // Tells the system the game's picture changes at the game's own rate (60 or 50 frames
    // a second, not the 120 Hz a phone screen can run at), so the screen can drop to it and
    // use less power and heat. Android 11+.
    private void applyFrameRate() {
        SurfaceHolder holder = this.activeSurfaceHolder;
        if (android.os.Build.VERSION.SDK_INT < 30 || holder == null) {
            return;
        }
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        float fps = 60f;
        long handle = this.nativeHandle;
        if (handle != 0) {
            double[] stats = NativeGba.frameStats(handle);
            if (stats != null && stats.length >= 3 && stats[2] >= 20 && stats[2] <= 120) {
                fps = (float) stats[2];
            }
        }
        try {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (RuntimeException e) {
            // A surface going away as it is set: the next one gets it.
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        this.surfaceReady = true;
        this.activeSurfaceHolder = surfaceHolder;
        applyFrameRate();
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
            // Battery saves / memory cards: both PS1 engines use PCSX ReARMed's folder.
            File file = new File(new File(getFilesDir(), "saves"), MainActivity.sramCore(this.coreId));
            if (!file.exists() && !file.mkdirs()) {
                showFatalError("ROM 실행 실패: 세이브 폴더를 만들 수 없습니다");
                return;
            }
            // Vita3K keeps its whole PS Vita (firmware, installed games) under its system folder.
            File file2 = isVita() ? new File(new File(getFilesDir(), "system"), "vita3k") : new File(getFilesDir(), "system");
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
                        NativeGba.setPspTexture(pspTexture());
                        NativeGba.setN3dsScale(n3dsScale());
                        int wifi = wifiMultiMode();
                        String server = wifi == 1 ? hostAddress() : wifi == 2 ? wifiMultiHost() : null;
                        NativeGba.setPspAdhoc(server == null ? 0 : wifi, server);
                    }
                    if (isWonderSwan()) {
                        NativeGba.setWonderSwanTall(wonderSwanTall());
                    }
                    if (isNds()) {
                        int ds = dsNetMode();
                        NativeGba.setDsNet(ds == 2 && dsNetHost() == null ? 0 : ds, ds == 2 ? dsNetHost() : null);
                    }
                    NativeGba.setSpeedFirst(speedFirstSupported() && speedFirst());
                    NativeGba.setSpeedFirstStrong("pcee2".equals(this.coreId) && speedFirstLevel() == 3);
                    NativeGba.setSpeedFirstLight("pcee2".equals(this.coreId) && speedFirstLevel() == 1);
                    NativeGba.setPowerMode(powerMode());
                    NativeGba.setNoAutoState(stvBoard());
                    NativeGba.setMotionEnabled(motionActive());
                    NativeGba.setPlayers(0, 1, multitapNow());
                    if (isDolphin()) {
                        int lan = gcLanMode();
                        NativeGba.setGcLan(lan == 2 && gcLanHost() == null ? 0 : lan, lan == 2 ? gcLanHost() : null);
                        NativeGba.setWiiControl(wiiControl());
                        NativeGba.setDolphinVideo(dolphinScale(), dolphinAspect());
                        NativeGba.setWiiPointer(isWiiGame() && wiiPointer() == 1);
                        NativeGba.setWiimmfi(wiimmfiOn());
                        NativeGba.setWiiLink(applyWiiLinkCode());
                        if (wiiRoomMode() == 1 && !WfcRoom.start(this)) {
                            runOnUiThread(() -> Toast.makeText(this, "방 서버를 켜지 못했어요. 접속 기록을 확인해 주세요.", Toast.LENGTH_LONG).show());
                        }
                        NativeGba.setWiiRoom(wiiRoomAddress());
                    }
                    if (isPs2()) {
                        writePs2CheatPnach();
                    }
                    if (isMame2003Plus()) {
                        this.mameCheatsAtStart = mameCheatFile().isFile();
                    }
                    if (isN3ds() && cheatsSupported()) {
                        writeCitraCheats();
                    }
                    if (isWiiU() && cheatsSupported()) {
                        syncWiiuCheatPacks(false);
                    }
                    if (isWiiU()) {
                        NativeGba.setWiiuResolution(WIIU_RES_VALUES[wiiuResolution()]);
                        applyWiiuLayout();
                    }
                    if (isDos()) {
                        // The save overlay linked to the game's .sav, the start file put in it.
                        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
                        String start = DosSetup.prepare(prefs, new File(savePath), new File(this.romPath), this.saveBaseName);
                        // The General MIDI SoundFont out of the APK, for MIDI music.
                        DosSetup.ensureSoundfont(this, new File(systemPath));
                        String[][] options = DosSetup.coreOptions(prefs, this.saveBaseName, new File(systemPath));
                        NativeGba.setDosOptions(options[0], options[1], start);
                    }
                    NativeGba.setCitraVulkan(isN3ds() && n3dsVulkan());
                    NativeGba.setPs2Patches(ps2Widescreen() && ps2WidescreenFound(), ps2NoInterlacing());
                    NativeGba.setCitraAsync(n3dsAsync());
                    NativeGba.setRotationMode(arcadeRotationMode());
                    NativeGba.setVmuSound(vmuShown() && vmuSound());
                    sendGunSetup();
                    NativeGba.setTurbo(turboActiveMask(), turboRate());
                    NativeGba.setFixedTurbo(turboEnabled());
                    TouchButton.fixedTurboMask = turboEnabled() ? fixedTurboBits() : 0;
                    TouchButton.turboRate = turboRate();
                    if (isSwan()) {
                        String[][] swan = swanOptions();
                        NativeGba.setDosOptions(swan[0], swan[1], "");
                    }
                    if (isVita()) {
                        int scale = vitaScale();
                        vitaResetMappingOnce();
                        NativeGba.setDosOptions(new String[]{"vita3k_backend_renderer", "vita3k_resolution_multiplier", "vita3k_async_pipeline", "vita3k_v_sync",
                                        "vita3k_memory_mapping"},
                                new String[]{"Vulkan", String.valueOf(scale), "enabled", "disabled", VITA_MM_VALUES[vitaMemoryMapping()]}, "");
                    }
                    if (isMsx()) {
                        // The machine type and screen / sound the player picked for this game.
                        this.msxResumed = autoStateFile().isFile();
                        this.msxMachine = msxPickMachine();
                        int hz = msxInt("hz", 0);
                        NativeGba.setDosOptions(new String[]{"bluemsx_msxtype", "bluemsx_nospritelimits", "bluemsx_ym2413_enable",
                                        "bluemsx_overscan", "bluemsx_vdp_synctype", "bluemsx_auto_rewind_cas", "bluemsx_additional_cart", "bluemsx_cartmapper"},
                                new String[]{this.msxMachine, msxBool("sprites", true) ? "ON" : "OFF",
                                        msxBool("fm", true) ? "enabled" : "disabled", msxBool("overscan", false) ? "disabled" : "enabled",
                                        hz == 1 ? "60Hz" : hz == 2 ? "50Hz" : "Auto", "ON", msxSlot2Path() == null ? "disabled" : msxSlot2Path(),
                                        MSX_MAPPER_VALUES[msxMapper()]}, "");
                    }
                    NativeGba.setN64Scale(isN64() ? n64Scale() : 2);
                    if (isNds()) {
                        NativeGba.setDsMic(dsMicInput(), dsMicButton());
                    }
                    // FBNeo's .ini cheats as this game left them (others: none of them).
                    sendFbneoCheats();
                    if (isPc98()) {
                        // A fresh start puts the first two disks in again; a resumed game
                        // keeps the drives as they were.
                        if (!autoStateFile().isFile()) {
                            MainActivity.sharedSettings(this).edit().remove(pc98Key("fdd1")).remove(pc98Key("fdd2")).apply();
                        }
                        sendPc98Options(null);
                        preparePc98Midi(new File(systemPath));
                    }
                    if ("fbneo".equals(this.coreId)) {
                        NativeGba.setDosOptions(new String[]{"fbneo-ips"}, new String[]{arcadePatchOn() ? "enabled" : "disabled"}, "");
                    }
                    // Turning the Korean patch on or off starts the game afresh: a scene saved
                    // with the other ROM contents would come back broken.
                    if (MainActivity.sharedSettings(this).getBoolean("fresh_start:" + this.saveBaseName, false)) {
                        MainActivity.sharedSettings(this).edit().remove("fresh_start:" + this.saveBaseName).apply();
                        autoStateFile().delete();
                    }
                    if (isDreamcast()) {
                        NativeGba.setDosOptions(new String[]{"dc-alpha"}, new String[]{dcAlphaExact() ? "per-pixel" : "default"}, "");
                    }
                    // A crash while on Vulkan turns it off for that engine for good (MainActivity);
                    // a Dreamcast game can also be set to OpenGL (그래픽).
                    NativeGba.setVulkan(!MainActivity.vulkanOff(MainActivity.sharedSettings(this), this.coreId) && !(isDreamcast() && dcOpenGl()));
                    // A Dreamcast game on several discs starts on the disc it was left on, so
                    // the scene saved with it comes back with the same disc in the drive.
                    NativeGba.setInitialDisk(isDiscGroup() ? MainActivity.sharedSettings(this).getInt("disc:" + this.saveBaseName, -1) : -1);
                    // A PS Vita game folder in the list (.vitadir): the engine gets the folder itself.
                    String corePath = isVita() && VitaPatch.isFolderGame(new File(this.romPath))
                            ? VitaPatch.folderOf(new File(this.romPath)).getAbsolutePath() : this.romPath;
                    handle = NativeGba.create(surface, corePath, savePath, systemPath, this.saveBaseName, this.coreId, this.audioSink);
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
        if (isVita()) {
            startVitaTextPoll();
        }
        startVmuPoll();
        if (isN3ds()) {
            startN3dsNet(handle);
        }
        if (isRotatingArcadeCore()) {
            checkArcadeVertical(handle);
        }
        applyTurbo();
        refreshPlayers(false);
        startAdhocBeaconIfHosting();
        shareVpnInviteIfPending();
        startMotionIfOn();
        sendMainInput(handle);
        NativeGba.setSpeedPercent(handle, this.speedPercent);
        if (isMsxTape() && !this.msxResumed) {
            msxAutoStartTape(false);
        }
        if (this.msxAutoXbios && !this.msxResumed) {
            Toast.makeText(this, "💾 원래 바이오스가 없어 디스크 게임을 XBIOS로 켰어요. 안 켜지면 메뉴 → MSX 바이오스에서 원래 바이오스를 넣어 주세요.", Toast.LENGTH_LONG).show();
        }
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
        // The game's own frame rate is known now (50 for PAL games).
        applyFrameRate();
        startMicPoll();
        // A phone already hot when the game started (the status came before the engine).
        if (this.thermalStatus >= 2) {
            onThermalStatus(this.thermalStatus);
        }
        showStartNotices();
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

    // Portrait top bar (0.0.289): four pills in one thin row, each a small round icon and its
    // name (it was four 40dp circles with names under them, 64dp tall).
    static final int TOP_BAR_DP = 36;

    private LinearLayout createTopBar() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(8), dp(4), dp(8), dp(4));
        String[] names = {"저장", "재시작", "화면 회전", "메뉴"};
        int[] icons = {RoundIconView.SAVE, RoundIconView.RESET, RoundIconView.ROTATE, RoundIconView.MENU};
        int[] colors = {RoundIconView.COLOR_SAVE, RoundIconView.COLOR_RESET, RoundIconView.COLOR_ROTATE, RoundIconView.COLOR_MENU};
        View.OnClickListener[] actions = {view -> onSaveClicked(), view -> onResetClicked(), view -> toggleOrientation(), view -> showGameMenu()};
        for (int k = 0; k < names.length; k++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
            params.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
            linearLayout.addView(createPillButton(icons[k], colors[k], names[k], actions[k]), params);
        }
        return linearLayout;
    }

    // A pill on the case: a small round icon and the name beside it.
    private LinearLayout createPillButton(int icon, int color, String label, View.OnClickListener onClickListener) {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER);
        int labelColor = shellTextColor();
        boolean lightLabel = Color.red(labelColor) + Color.green(labelColor) + Color.blue(labelColor) > 384;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(lightLabel ? Color.argb(46, 0, 0, 0) : Color.argb(110, 255, 255, 255));
        bg.setStroke(Math.max(1, dp(1)), lightLabel ? Color.argb(40, 255, 255, 255) : Color.argb(40, 0, 0, 0));
        pill.setBackground(bg);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        pill.addView(iconView, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11.5f);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextColor(labelColor);
        text.setShadowLayer(dp(2), 0, dp(1), lightLabel ? Color.argb(150, 0, 0, 0) : Color.argb(160, 255, 255, 255));
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(dp(5), 0, 0, 0);
        pill.addView(text, textParams);
        pill.setClickable(true);
        pill.setContentDescription(label);
        pill.setOnClickListener(onClickListener);
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
        this.layoutGuards.clear();
        this.layoutGuards.add(left);

        LinearLayout right = new LinearLayout(this);
        right.addView(createIconButton(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "회전", 34, 10, view -> toggleOrientation()), new LinearLayout.LayoutParams(dp(48), -2));
        right.addView(createIconButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", 34, 10, view -> showGameMenu()), new LinearLayout.LayoutParams(dp(48), -2));
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rightParams.setMargins(0, dp(6), dp(6), 0);
        frameLayout.addView(right, rightParams);
        this.layoutGuards.add(right);

        SpeedButtonView speedButtonView = new SpeedButtonView(this, this::onSpeedChanged);
        this.fastForwardButton = speedButtonView;
        speedButtonView.setPercent(this.speedPercent);
        speedButtonView.setContentDescription("배속");
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(dp(80), dp(36));
        if (isN3ds()) {
            // Above START at the bottom of the right column.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(127), dp(62));
        } else if (isWiiU()) {
            // Above + in the right column.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(14), dp(52));
        } else if (isDualScreen()) {
            // The DS screens run the full height, so the button sits beside START instead.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(146), dp(17));
        } else if (isN64()) {
            // Under the A button.
            speedParams.gravity = Gravity.BOTTOM | Gravity.END;
            speedParams.setMargins(0, 0, dp(18), dp(12));
        } else if ((isDreamcast() && !isNaomi()) || isSaturn() || isDolphin()) {
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
        if (isDualScreen()) {
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
        // The label sits on the case, not on an app surface: its colour follows the case,
        // with a faint shadow so it reads over any part of it.
        int labelColor = shellTextColor();
        text.setTextColor(labelColor);
        boolean lightLabel = Color.red(labelColor) + Color.green(labelColor) + Color.blue(labelColor) > 384;
        text.setShadowLayer(dp(2), 0, dp(1), lightLabel ? Color.argb(170, 0, 0, 0) : Color.argb(180, 255, 255, 255));
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
        if (noStates()) {
            showWiiuNoStates();
            return;
        }
        if (stvBoard() && !this.stvSaveConfirmed) {
            confirmStvSave(() -> {
                this.stvSaveConfirmed = true;
                createQuickSave(withSram);
                this.stvSaveConfirmed = false;
            });
            return;
        }
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

    // ---- Play time (per game), auto-save history, fixed slots, screenshots ----
    private long playStart;

    private String playTimeKey() {
        return "playtime:" + this.coreId + ":" + this.saveBaseName;
    }

    private long playTimeSeconds() {
        return MainActivity.sharedSettings(this).getLong(playTimeKey(), 0) + (this.playStart > 0 ? (System.currentTimeMillis() - this.playStart) / 1000 : 0);
    }

    private void addPlayTime() {
        if (this.playStart <= 0 || this.saveBaseName == null) {
            return;
        }
        long seconds = (System.currentTimeMillis() - this.playStart) / 1000;
        this.playStart = 0;
        if (seconds > 0) {
            android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
            prefs.edit().putLong(playTimeKey(), prefs.getLong(playTimeKey(), 0) + seconds).apply();
        }
    }

    static String playTimeText(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return "플레이 " + hours + "시간 " + minutes + "분";
        }
        return "플레이 " + Math.max(1, minutes) + "분";
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return Math.max(1, bytes / 1024) + " KB";
    }

    // The two automatic saves before the latest: auto1.state (the exit before this one) and
    // auto2.state. Moved along when the game starts, by hard links, so nothing is copied.
    private File autoHistoryFile(int n) {
        return new File(quickSaveDir(), "auto" + n + ".state");
    }

    private void rotateAutoSaves() {
        if (isMame139() || this.saveBaseName == null) {
            return;
        }
        final File latest = autoStateFile();
        final File latestPicture = autoThumbnail();
        final File one = autoHistoryFile(1);
        final File two = autoHistoryFile(2);
        final File dir = quickSaveDir();
        new Thread(() -> {
            if (!latest.isFile()) {
                return;
            }
            if (one.isFile() && one.length() == latest.length() && one.lastModified() == latest.lastModified()) {
                return; // Nothing new was written since the last turn.
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            two.delete();
            //noinspection ResultOfMethodCallIgnored
            thumbnailFor(two).delete();
            if (one.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                one.renameTo(two);
                //noinspection ResultOfMethodCallIgnored
                thumbnailFor(one).renameTo(thumbnailFor(two));
            }
            try {
                android.system.Os.link(latest.getAbsolutePath(), one.getAbsolutePath());
            } catch (android.system.ErrnoException e) {
                try {
                    PhotoBackground.copy(latest, one);
                } catch (java.io.IOException ignored) {
                    return;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            one.setLastModified(latest.lastModified());
            if (latestPicture.isFile()) {
                try {
                    PhotoBackground.copy(latestPicture, thumbnailFor(one));
                } catch (java.io.IOException ignored) {
                    // No picture for the older save.
                }
            }
        }, "auto-saves").start();
    }

    // ---- 내 칸: slot<n>.state with slot<n>.jpg and slot<n>.txt (memo, play time) ----
    private static final int FIXED_SLOTS = 8;

    private File slotFile(int n) {
        return new File(quickSaveDir(), "slot" + n + ".state");
    }

    private static File metaFor(File state) {
        String name = state.getName();
        return new File(state.getParentFile(), name.substring(0, name.length() - ".state".length()) + ".txt");
    }

    private static boolean isSlot(File state) {
        return state.getName().matches("slot\\d+\\.state");
    }

    private static int slotIndex(File state) {
        try {
            return Integer.parseInt(state.getName().substring(4, state.getName().indexOf('.')));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean isAutoHistory(File state) {
        return state.getName().matches("auto\\d+\\.state");
    }

    /** memo and play seconds of a slot, from its .txt ("memo\nseconds"). */
    private static String[] slotMeta(File state) {
        File meta = metaFor(state);
        if (!meta.isFile()) {
            return new String[]{"", ""};
        }
        String text = CrashReports.readText(meta, 4096);
        int newline = text.indexOf('\n');
        return newline < 0 ? new String[]{text.trim(), ""} : new String[]{text.substring(0, newline).trim(), text.substring(newline + 1).trim()};
    }

    private void writeSlotMeta(File state, String memo) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(metaFor(state))) {
            out.write((memo.replace('\n', ' ') + "\n" + playTimeSeconds()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
            // The slot works without its memo.
        }
    }

    private String describeSave(File save) {
        if (save.equals(autoStateFile())) {
            return isPs2() ? "이전 빠른 저장" : "자동 저장 · 방금 (종료 시)";
        }
        if (isAutoHistory(save)) {
            int n = Integer.parseInt(save.getName().substring(4, save.getName().indexOf('.')));
            return n == 1 ? "자동 저장 · 이전" : "자동 저장 · 그 전";
        }
        if (isSlot(save)) {
            String memo = slotMeta(save)[0];
            return "내 칸 " + slotIndex(save) + (memo.isEmpty() ? "" : " · " + memo);
        }
        return "빠른 저장 " + slotNumber(save);
    }

    private boolean stvSaveConfirmed;

    private void showWiiuNoStates() {
        Toast.makeText(this, (isVita() ? "PS Vita" : "Wii U") + "는 엔진이 장면 저장을 지원하지 않아요. 게임 안에서 저장해 주세요.", Toast.LENGTH_LONG).show();
    }

    private void saveToSlot(int n, String memo, boolean overwrite) {
        if (noStates()) {
            showWiiuNoStates();
            return;
        }
        if (stvBoard() && !this.stvSaveConfirmed) {
            confirmStvSave(() -> {
                this.stvSaveConfirmed = true;
                saveToSlot(n, memo, overwrite);
                this.stvSaveConfirmed = false;
            });
            return;
        }
        final File state = slotFile(n);
        if (state.isFile() && !overwrite) {
            new AppDialog.Builder(this).setTitle("내 칸 " + n + "을 덮어쓸까요?")
                    .setMessage(describeSave(state) + "\n" + saveTimeText(state.lastModified()) + "\n지금 모습으로 바뀌고 이전 것은 되돌릴 수 없어요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("덮어쓰기", (d, w) -> saveToSlot(n, memo, true)).show();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        quickSaveDir().mkdirs();
        captureThumbnail(thumbnailFor(state));
        final String note = memo == null ? "" : memo.trim();
        callCore(h -> {
            boolean saved = NativeGba.saveStateFile(h, state.getAbsolutePath());
            if (saved) {
                writeSlotMeta(state, note);
                long size = state.length();
                android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
                if (size > 30L * 1024 * 1024 && !prefs.getBoolean("slot_size_notice", false)) {
                    prefs.edit().putBoolean("slot_size_notice", true).apply();
                    runOnUiThread(() -> Toast.makeText(this, "이 기종은 칸 하나에 약 " + (size / (1024 * 1024)) + " MB를 써요. 안 쓰는 칸은 불러오기에서 지울 수 있어요.", Toast.LENGTH_LONG).show());
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                thumbnailFor(state).delete();
            }
            return saved;
        }, "내 칸 " + n + "에 저장했어요." + (note.isEmpty() ? "" : " · " + note), "저장하지 못했어요.");
    }

    /** "빠른 저장"을 길게 눌렀을 때: a quick slot or one of the fixed slots, with a memo. */
    private void showSaveChooser() {
        this.holdPause = true;
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        dialog.setOnDismissListener(d -> {
            this.holdPause = false;
            resumeUnlessExiting();
        });
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("어디에 저장할까요?");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText("짧게 누르면 전처럼 빠른 칸에 바로 저장돼요");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        panel.addView(sub);
        final android.widget.EditText memo = new android.widget.EditText(this);
        memo.setHint("메모 (선택) · 예: 보스 앞");
        memo.setTextSize(13.5f);
        memo.setSingleLine(true);
        memo.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        memo.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable memoBackground = new android.graphics.drawable.GradientDrawable();
        memoBackground.setColor(Theme.bg(Color.WHITE));
        memoBackground.setCornerRadius(dp(12));
        memoBackground.setStroke(dp(1), Theme.line(Color.rgb(214, 218, 226)));
        memo.setBackground(memoBackground);
        LinearLayout.LayoutParams memoParams = new LinearLayout.LayoutParams(-1, -2);
        memoParams.setMargins(0, dp(10), 0, dp(4));
        panel.addView(memo, memoParams);

        java.util.List<File> quick = quickSaves();
        int next = quick.isEmpty() ? 1 : slotNumber(quick.get(0)) + 1;
        panel.addView(saveChoiceRow(null, "빠른 칸 (새 칸 " + next + ")", "지금처럼 · 오래된 칸부터 교체", true, view -> {
            dialog.dismiss();
            createQuickSave(false);
        }));
        TextView section = new TextView(this);
        section.setText("내 칸 (밀려나지 않음)");
        section.setTextSize(11.5f);
        section.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        section.setTextColor(Theme.text(Color.rgb(85, 95, 110)));
        section.setPadding(0, dp(10), 0, dp(2));
        panel.addView(section);
        for (int n = 1; n <= FIXED_SLOTS; n++) {
            final int slot = n;
            File state = slotFile(n);
            boolean filled = state.isFile();
            panel.addView(saveChoiceRow(filled ? thumbnailFor(state) : null, filled ? describeSave(state) : n + " · 비어 있음",
                    filled ? "덮어쓰기 · " + saveTimeText(state.lastModified()) : "", false, view -> {
                        dialog.dismiss();
                        saveToSlot(slot, memo.getText().toString(), false);
                    }));
        }
        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(46));
        cancelParams.setMargins(0, dp(12), 0, 0);
        panel.addView(cancel, cancelParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400)), Math.min(getResources().getDisplayMetrics().heightPixels - dp(40), dp(560)));
        }
        dialog.show();
    }

    private View saveChoiceRow(File picture, String name, String detail, boolean highlight, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
        rowBackground.setColor(Theme.bg(highlight ? Color.rgb(255, 247, 248) : Color.rgb(246, 247, 249)));
        rowBackground.setCornerRadius(dp(14));
        if (highlight) rowBackground.setStroke(dp(2), Theme.line(Color.rgb(225, 29, 72)));
        row.setBackground(rowBackground);
        android.widget.ImageView thumb = new android.widget.ImageView(this);
        thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable thumbBackground = new android.graphics.drawable.GradientDrawable();
        thumbBackground.setColor(Theme.bg(Color.rgb(222, 226, 232)));
        thumbBackground.setCornerRadius(dp(6));
        thumb.setBackground(thumbBackground);
        thumb.setClipToOutline(true);
        if (picture != null && picture.isFile()) {
            thumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(picture.getAbsolutePath()));
        }
        row.addView(thumb, new LinearLayout.LayoutParams(dp(58), dp(36)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13.5f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(label);
        if (!detail.isEmpty()) {
            TextView small = new TextView(this);
            small.setText(detail);
            small.setTextSize(11.0f);
            small.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            texts.addView(small);
        }
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(10), 0, dp(8), 0);
        row.addView(texts, textsParams);
        TextView save = smallButton("저장");
        save.setOnClickListener(onClick);
        row.addView(save, new LinearLayout.LayoutParams(-2, dp(32)));
        row.setOnClickListener(onClick);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(rowParams);
        return row;
    }

    /** Long press on a save in the list: open, memo, overwrite (fixed slots), export, delete. */
    private void showSaveMenu(android.app.Dialog list, File save) {
        final boolean slot = isSlot(save);
        String[] meta = slot ? slotMeta(save) : new String[]{"", ""};
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("열기");
        if (slot) {
            names.add("메모 바꾸기");
            names.add("지금 모습으로 덮어쓰기");
        }
        names.add("파일로 내보내기");
        names.add("지우기");
        String played = "";
        if (!meta[1].isEmpty()) {
            try {
                played = " · " + playTimeText(Long.parseLong(meta[1]));
            } catch (NumberFormatException ignored) {
                // No play time noted.
            }
        }
        new AppDialog.Builder(this).setTitle(describeSave(save))
                .setMessage(saveTimeText(save.lastModified()) + played + " · " + formatSize(save.length()))
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    String choice = names.get(which);
                    switch (choice) {
                        case "열기":
                            list.dismiss();
                            callCore(h -> NativeGba.loadStateFile(h, save.getAbsolutePath()), describeSave(save) + "을 불러왔어요.", "불러오지 못했어요.");
                            break;
                        case "메모 바꾸기":
                            editSlotMemo(list, save);
                            break;
                        case "지금 모습으로 덮어쓰기":
                            list.dismiss();
                            saveToSlot(slotIndex(save), meta[0], false);
                            break;
                        case "파일로 내보내기":
                            exportSave(save);
                            break;
                        default:
                            new AppDialog.Builder(this).setTitle(describeSave(save) + "을 지울까요?")
                                    .setMessage("지운 세이브는 되돌릴 수 없어요.")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton("지우기", (d2, w2) -> {
                                        if (save.equals(autoStateFile())) {
                                            //noinspection ResultOfMethodCallIgnored
                                            save.delete();
                                            //noinspection ResultOfMethodCallIgnored
                                            autoThumbnail().delete();
                                        } else {
                                            deleteSlot(save);
                                            //noinspection ResultOfMethodCallIgnored
                                            metaFor(save).delete();
                                        }
                                        fillLoadList(list, false, new java.util.HashSet<>());
                                    }).show();
                    }
                }).setNegativeButton("닫기", null).show();
    }

    private void editSlotMemo(android.app.Dialog list, File save) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(slotMeta(save)[0]);
        input.setSingleLine(true);
        input.setSelection(input.getText().length());
        new AppDialog.Builder(this).setTitle("메모 바꾸기 · 내 칸 " + slotIndex(save)).setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (d, w) -> {
                    String[] meta = slotMeta(save);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(metaFor(save))) {
                        out.write((input.getText().toString().trim().replace('\n', ' ') + "\n" + meta[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (java.io.IOException ignored) {
                        Toast.makeText(this, "메모를 저장하지 못했어요.", Toast.LENGTH_SHORT).show();
                    }
                    fillLoadList(list, false, new java.util.HashSet<>());
                }).show();
    }

    // Copies a state to 다운로드/Mini Emulator/세이브 (Android 10+) as "<game> <slot>.state".
    private void exportSave(File save) {
        String base = (this.gameTitle + " " + describeSave(save)).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        String name = base + ".state";
        try {
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER + "/세이브/");
                android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("다운로드 폴더에 파일을 만들 수 없어요");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri); java.io.FileInputStream in = new java.io.FileInputStream(save)) {
                    if (out == null) throw new java.io.IOException("파일을 열 수 없어요");
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                where = "다운로드 › " + MainActivity.DOWNLOAD_FOLDER + " › 세이브 › " + name;
            } else {
                File folder = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new java.io.IOException("외부 저장 폴더를 쓸 수 없어요");
                File target = new File(folder, name);
                PhotoBackground.copy(save, target);
                where = target.getAbsolutePath();
            }
            Toast.makeText(this, "내보냈어요: " + where + "\n같은 게임의 불러오기 › 파일에서 로 되돌릴 수 있어요.", Toast.LENGTH_LONG).show();
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, "내보내지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static final int REQUEST_STATE_FILE = 3104;

    private void pickStateFile() {
        this.holdPause = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                        "primary:" + android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER + "/세이브"));
        try {
            startActivityForResult(intent, REQUEST_STATE_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "파일 선택 앱이 없어요.", Toast.LENGTH_SHORT).show();
            this.holdPause = false;
            resumeUnlessExiting();
        }
    }

    private void loadStateFromUri(android.net.Uri uri) {
        File temp = new File(getCacheDir(), "picked.state");
        try (java.io.InputStream in = getContentResolver().openInputStream(uri); java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            if (in == null) throw new java.io.IOException("파일을 열 수 없어요");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, "파일을 읽지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            resumeUnlessExiting();
            return;
        }
        callCore(h -> NativeGba.loadStateFile(h, temp.getAbsolutePath()), "파일의 장면을 불러왔어요.", "불러오지 못했어요. 다른 게임이나 다른 엔진 버전의 세이브일 수 있어요.");
    }

    // ---- 스크린샷 ----
    private void takeScreenshot(boolean fullRequested) {
        // Copying the whole window needs Android 8; older phones get the game picture.
        final boolean full = fullRequested && android.os.Build.VERSION.SDK_INT >= 26;
        final View source = full ? getWindow().getDecorView() : this.surfaceView;
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            Toast.makeText(this, "지금은 화면을 찍을 수 없어요.", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(source.getWidth(), source.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.view.PixelCopy.OnPixelCopyFinishedListener done = result -> {
            if (result != android.view.PixelCopy.SUCCESS) {
                Toast.makeText(this, "화면을 찍지 못했어요 (" + result + ")", Toast.LENGTH_SHORT).show();
                return;
            }
            flashScreen();
            new Thread(() -> {
                String outcome;
                try {
                    outcome = "📷 " + saveScreenshot(bitmap, full);
                } catch (java.io.IOException | RuntimeException e) {
                    outcome = "스크린샷을 저장하지 못했어요: " + e.getMessage();
                }
                final String message = outcome;
                runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
            }, "screenshot").start();
        };
        try {
            if (full) {
                android.view.PixelCopy.request(getWindow(), bitmap, done, new android.os.Handler(android.os.Looper.getMainLooper()));
            } else {
                android.view.PixelCopy.request((SurfaceView) source, bitmap, done, new android.os.Handler(android.os.Looper.getMainLooper()));
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "지금은 화면을 찍을 수 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    // A brief white flash over everything, like a camera.
    private void flashScreen() {
        final View flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0.7f);
        addContentView(flash, new FrameLayout.LayoutParams(-1, -1));
        flash.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            if (flash.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) flash.getParent()).removeView(flash);
            }
        }).start();
    }

    private String saveScreenshot(android.graphics.Bitmap bitmap, boolean full) throws java.io.IOException {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(new java.util.Date());
        String name = (this.gameTitle.isEmpty() ? "screenshot" : this.gameTitle.replaceAll("[\\\\/:*?\"<>|]", "_")) + " " + stamp + (full ? " 전체" : "") + ".png";
        String size = bitmap.getWidth() + "×" + bitmap.getHeight();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/" + MainActivity.DOWNLOAD_FOLDER + "/");
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("사진 폴더에 파일을 만들 수 없어요");
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("파일을 열 수 없어요");
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            return "사진 › " + MainActivity.DOWNLOAD_FOLDER + " › " + name + " (" + size + ")";
        }
        File folder = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new java.io.IOException("외부 저장 폴더를 쓸 수 없어요");
        File file = new File(folder, name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        return file.getAbsolutePath() + " (" + size + ")";
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
        if (noStates()) {
            showWiiuNoStates();
            return;
        }
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
            window.setLayout(width, Math.min(getResources().getDisplayMetrics().heightPixels - dp(40), dp(640)));
        }
        dialog.show();
    }

    // The list itself: tap a save to load it; in delete mode, tick saves and delete them.
    private void fillLoadList(android.app.Dialog dialog, boolean deleting, java.util.Set<File> ticked) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        final java.util.List<File> saves = new java.util.ArrayList<>();
        final File auto = autoStateFile();
        final java.util.List<File> autos = new java.util.ArrayList<>();
        if (auto.isFile()) {
            autos.add(auto);
        }
        for (int n = 1; n <= 2; n++) {
            if (autoHistoryFile(n).isFile()) autos.add(autoHistoryFile(n));
        }
        final java.util.List<File> quick = quickSaves();
        final java.util.List<File> fixed = new java.util.ArrayList<>();
        for (int n = 1; n <= FIXED_SLOTS; n++) {
            if (slotFile(n).isFile()) fixed.add(slotFile(n));
        }
        saves.addAll(autos);
        saves.addAll(quick);
        saves.addAll(fixed);
        long totalBytes = 0;
        for (File save : saves) totalBytes += save.length();

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
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(deleting ? ticked.size() + "개 선택됨" : this.gameTitle + " · 자동 " + autos.size() + " · 빠른 " + quick.size() + " · 내 칸 " + fixed.size()
                + (totalBytes > 0 ? " · 총 " + formatSize(totalBytes) : ""));
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
            empty.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            empty.setPadding(0, dp(24), 0, dp(24));
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        // Three groups: automatic saves, the quick slots, the fixed slots (empty ones too).
        java.util.List<Object> entries = new java.util.ArrayList<>();
        if (!autos.isEmpty()) entries.add("자동 저장 (최근 " + autos.size() + "개)");
        entries.addAll(autos);
        if (!quick.isEmpty()) entries.add("빠른 저장 (최근 " + QUICK_SLOTS + "칸 · 밀려남)");
        entries.addAll(quick);
        if (!isMame139()) entries.add("내 칸 (1 ~ " + FIXED_SLOTS + " · 밀려나지 않음)");
        for (int n = 1; n <= FIXED_SLOTS && !isMame139(); n++) {
            entries.add(slotFile(n).isFile() ? slotFile(n) : Integer.valueOf(n));
        }
        for (final Object entry : entries) {
            if (entry instanceof String) {
                TextView section = new TextView(this);
                section.setText((String) entry);
                section.setTextSize(11.5f);
                section.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                section.setTextColor(Theme.text(Color.rgb(85, 95, 110)));
                section.setPadding(dp(2), dp(12), 0, 0);
                panel.addView(section, new LinearLayout.LayoutParams(-1, -2));
                continue;
            }
            if (entry instanceof Integer) {
                if (deleting) continue;
                final int slot = (Integer) entry;
                LinearLayout empty = new LinearLayout(this);
                empty.setOrientation(0);
                empty.setGravity(16);
                empty.setPadding(dp(12), dp(8), dp(12), dp(8));
                android.graphics.drawable.GradientDrawable emptyBackground = new android.graphics.drawable.GradientDrawable();
                emptyBackground.setColor(Theme.bg(Color.WHITE));
                emptyBackground.setCornerRadius(dp(14));
                emptyBackground.setStroke(dp(1), Theme.line(Color.rgb(214, 218, 226)));
                empty.setBackground(emptyBackground);
                TextView emptyText = new TextView(this);
                emptyText.setText(slot + " · 비어 있음");
                emptyText.setTextSize(13.0f);
                emptyText.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                empty.addView(emptyText, new LinearLayout.LayoutParams(0, -2, 1.0f));
                TextView here = smallButton("여기에 저장");
                here.setOnClickListener(view -> {
                    dialog.dismiss();
                    saveToSlot(slot, "", false);
                });
                empty.addView(here, new LinearLayout.LayoutParams(-2, dp(30)));
                LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, -2);
                emptyParams.setMargins(0, dp(6), 0, 0);
                panel.addView(empty, emptyParams);
                continue;
            }
            final File save = (File) entry;
            final boolean isAuto = save.equals(auto);
            boolean on = ticked.contains(save);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
            rowBackground.setColor(Theme.bg(on ? Color.rgb(253, 236, 237) : Color.rgb(246, 247, 249)));
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
                boxBackground.setColor(Theme.bg(on ? Color.rgb(229, 72, 77) : Color.WHITE));
                boxBackground.setStroke(dp(2), Theme.line(on ? Color.rgb(229, 72, 77) : Color.rgb(201, 206, 214)));
                box.setBackground(boxBackground);
                LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(24), dp(24));
                boxParams.setMargins(0, 0, dp(12), 0);
                row.addView(box, boxParams);
            }
            android.widget.ImageView thumb = new android.widget.ImageView(this);
            thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.drawable.GradientDrawable thumbBackground = new android.graphics.drawable.GradientDrawable();
            thumbBackground.setColor(Theme.bg(Color.rgb(203, 208, 215)));
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
            name.setText(describeSave(save));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextSize(14.0f);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
            texts.addView(name);
            TextView when = new TextView(this);
            String played = "";
            if (isSlot(save)) {
                String[] meta = slotMeta(save);
                try {
                    if (!meta[1].isEmpty()) played = " · " + playTimeText(Long.parseLong(meta[1]));
                } catch (NumberFormatException ignored) {
                    // No play time noted.
                }
            }
            when.setText(saveTimeText(save.lastModified()) + played + " · " + formatSize(save.length()));
            when.setTextSize(12.0f);
            when.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
                callCore(h -> NativeGba.loadStateFile(h, save.getAbsolutePath()), describeSave(save) + "을 불러왔어요.", "불러오지 못했어요.");
            });
            if (!deleting) {
                row.setOnLongClickListener(view -> {
                    showSaveMenu(dialog, save);
                    return true;
                });
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMargins(0, dp(8), 0, 0);
            panel.addView(row, rowParams);
        }

        if (!deleting && !saves.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("누르면 불러오기 · 길게 누르면 메모 · 덮어쓰기 · 내보내기 · 지우기");
            hint.setTextSize(11.5f);
            hint.setGravity(17);
            hint.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
            if (!isMame139()) {
                TextView fromFile = dialogButton("파일에서", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
                fromFile.setOnClickListener(view -> {
                    dialog.dismiss();
                    pickStateFile();
                });
                LinearLayout.LayoutParams fromParams = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
                fromParams.setMargins(0, 0, dp(10), 0);
                buttons.addView(fromFile, fromParams);
            }
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
                names.append(describeSave(save));
            }
        }
        String note = names + "\n지운 세이브는 되돌릴 수 없어요.\n게임 안 세이브(메모리카드·SRAM)는 지워지지 않아요.";
        new AppDialog.Builder(this)
                .setTitle("세이브 " + ticked.size() + "개를 지울까요?")
                .setMessage(note)
                .setNegativeButton("취소", null)
                .setPositiveButton(ticked.size() + "개 지우기", (d, w) -> {
                    for (File save : ticked) {
                        if (save.equals(auto)) {
                            save.delete();
                            autoThumbnail().delete();
                        } else {
                            deleteSlot(save);
                            //noinspection ResultOfMethodCallIgnored
                            metaFor(save).delete();
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
        button.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
        button.setGravity(17);
        button.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.rgb(241, 242, 244)));
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
        MainActivity.sharedSettings(this).edit().putInt("orientation_mode", i).commit();
        setRequestedOrientation(i == 1 ? (motionActive() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : 6) : 1);
    }

    private void addControls(FrameLayout frameLayout) {
        if (isKeyboardPc()) {
            addDosControls(frameLayout, false);
            return;
        }
        if (isDolphin()) {
            addDolphinControls(frameLayout);
            return;
        }
        if (isWiiU()) {
            addWiiuControls(frameLayout);
            return;
        }
        if (isSaturn()) {
            addSaturnControls(frameLayout);
            return;
        }
        if (isWonderSwan()) {
            addWonderSwanControls(frameLayout);
            return;
        }
        if (isMsx()) {
            addMsxControls(frameLayout);
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
        if (isNaomi()) {
            addNaomiControls(frameLayout);
            return;
        }
        if (isDreamcast()) {
            addDreamcastControls(frameLayout);
            return;
        }
        View createDirectionControl = createDirectionControl();
        boolean isGba = isPadLayout();
        // The Super Famicom's four face buttons need more room, so its D-pad is a little smaller.
        boolean compactPad = isSnes() || isDualScreen() || isMegaDrive();
        int i = isSonyPad() ? 140 : compactPad ? 150 : isGba ? 166 : 156;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(i), dp(i));
        layoutParams.gravity = 8388691;
        // The PSP's D-pad sits higher to leave room for the analog stick below it.
        // The PS1/PS2 pads keep both sticks along the bottom, so their D-pad sits lower still.
        if (isDualScreen()) {
            // The DS packs its controls into the shorter space under the two screens
            // (the 3DS a little tighter still, with the circle pad above).
            i = isN3ds() ? 128 : 136;
            layoutParams.width = dp(i);
            layoutParams.height = dp(i);
        }
        layoutParams.setMargins(dp(isN3ds() ? 66 : compactPad || isSonyPad() ? 16 : isGba ? 24 : 16), 0, 0, dp(isN3ds() ? 56 : isDualScreen() ? 60 : isDualShock() ? 160 : isSonyPad() ? 214 : isGba ? 154 : 144));
        frameLayout.addView(createDirectionControl, layoutParams);
        if (isPsp()) {
            FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(92), dp(92));
            stickParams.gravity = 8388691;
            stickParams.setMargins(dp(40), 0, 0, dp(112));
            frameLayout.addView(leftStick(), stickParams);
        }
        if (isDualShock()) {
            // Both sticks in the bottom corners, SELECT/START between them as on a DualShock.
            FrameLayout.LayoutParams leftStick = new FrameLayout.LayoutParams(dp(84), dp(84));
            leftStick.gravity = 8388691;
            leftStick.setMargins(dp(26), 0, 0, dp(54));
            frameLayout.addView(leftStick(), leftStick);
            FrameLayout.LayoutParams rightStick = new FrameLayout.LayoutParams(dp(84), dp(84));
            rightStick.gravity = 8388693;
            rightStick.setMargins(0, 0, dp(26), dp(54));
            frameLayout.addView(rightStick(), rightStick);
            // L2 L1 on the left and R1 R2 on the right, either side of the speed button.
            int shoulderStyle = isPs1() ? TouchButton.STYLE_LIGHT : 2;
            addButton(frameLayout, "L2", kL2, 8388659, dp(14), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "L1", 512, 8388659, dp(72), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "R1", 256, 8388661, dp(72), dp(34), dp(54), dp(42), shoulderStyle);
            addButton(frameLayout, "R2", kR2, 8388661, dp(14), dp(34), dp(54), dp(42), shoulderStyle);
        }
        if (hasShoulders()) {
            int shoulderTop = isDualScreen() ? 8 : 34;
            addButton(frameLayout, "L", 512, 8388659, dp(24), dp(shoulderTop), dp(112), dp(46), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(24), dp(shoulderTop), dp(112), dp(46), 2);
        }
        addFastForwardButton(frameLayout);
        if (isSonyPad()) {
            addPspFace(frameLayout);
        } else if (isSnes() || isDualScreen()) {
            // Diamond centred on the D-pad's height: X top, A right, B bottom, Y left
            // (Super Famicom colours; the DS's are plain dark).
            boolean ds = isDualScreen();
            if (isN3ds()) {
                // The circle pad above the D-pad; the diamond (moved in from the edge) centred
                // between them, as on the 3DS's lower half.
                FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(88), dp(88));
                stickParams.gravity = 8388691;
                stickParams.setMargins(dp(36), 0, 0, dp(186));
                frameLayout.addView(leftStick(), stickParams);
                addButton(frameLayout, "X", 1024, 8388693, dp(82), dp(202), dp(50), dp(50), TouchButton.STYLE_DARK);
                addButton(frameLayout, "A", 1, 8388693, dp(34), dp(152), dp(50), dp(50), TouchButton.STYLE_DARK);
                addButton(frameLayout, "B", 2, 8388693, dp(82), dp(102), dp(50), dp(50), TouchButton.STYLE_DARK);
                addButton(frameLayout, "Y", 2048, 8388693, dp(130), dp(152), dp(50), dp(50), TouchButton.STYLE_DARK);
                addCenterButton(frameLayout, selectLabel(), 4, -66, 14);
                addCenterButton(frameLayout, startLabel(), 8, 66, 14);
                // The microphone (0.0.370) in the lower left corner, beside SELECT and under the D-pad.
                addButton(frameLayout, "MIC", kMic3ds, Gravity.BOTTOM | Gravity.START, dp(12), dp(10), dp(44), dp(44), TouchButton.STYLE_PINK);
                return;
            }
            if (ds) {
                // Smaller diamond centred on the DS's lower D-pad.
                addButton(frameLayout, "X", 1024, 8388693, dp(68), dp(154), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "A", 1, 8388693, dp(18), dp(102), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "B", 2, 8388693, dp(68), dp(50), dp(52), dp(52), TouchButton.STYLE_DARK);
                addButton(frameLayout, "Y", 2048, 8388693, dp(118), dp(102), dp(52), dp(52), TouchButton.STYLE_DARK);
                // The microphone (held to talk or blow), between the D-pad and the diamond.
                TouchButton mic = new TouchButton(this, "MIC", kL3, this::setButtonPressed, TouchButton.STYLE_PINK);
                FrameLayout.LayoutParams micParams = new FrameLayout.LayoutParams(dp(46), dp(46));
                micParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                micParams.setMargins(0, 0, 0, dp(58));
                mic.setTranslationX(dp(-20));
                frameLayout.addView(mic, micParams);
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
                // The stick clicks, L3 over SELECT and R3 over START (a tap on the stick itself
                // is a tilt, not a click).
                TouchButton click = new TouchButton(this, k == 0 ? "L3" : "R3", k == 0 ? kL3 : kR3, this::setButtonPressed, TouchButton.STYLE_DARK);
                FrameLayout.LayoutParams clickParams = new FrameLayout.LayoutParams(dp(40), dp(40));
                clickParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                clickParams.setMargins(0, 0, 0, dp(120));
                click.setTranslationX(dp(k == 0 ? -34 : 34));
                frameLayout.addView(click, clickParams);
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
        layoutParams.setMargins(0, dp(isDualScreen() ? 10 : 37), 0, 0);
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
        onMicButton((this.buttonMask & (isN3ds() ? kMic3ds : kL3)) != 0);
        if (isMame139()) {
            Emulator.setMiniInput(this.buttonMask);
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            sendMainInput(j);
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
        items.add(MENU_SHOT);
        items.add(MENU_SHOT_FULL);
        this.menuItems = items.toArray(new String[0]);
        showPauseDialog();
    }

    // Pause menu: six round tiles and a big continue button.
    private android.app.Dialog pauseDialog;

    private static final String[] PAUSE_TAB_NAMES = {"기본", "성능", "화면", "조작", "게임기"};

    // 게임기 탭: settings only some consoles have.
    private boolean hasDeviceTab() {
        return isPsp() || isNds() || isWonderSwan() || isWiiU() || isDolphin() || isPs1() || isN3ds();
    }

    // The pause menu: one card with tabs (기본 · 성능 · 화면 · 조작 · 게임기), a content area
    // of fixed height so the card doesn't jump between tabs, and 계속하기 always at the
    // bottom. In landscape the tabs run down the left side.
    private void showPauseDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        boolean wide = this.landscapeMode;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(16), dp(14), dp(14));
        card.setBackground(roundRect(Color.WHITE, dp(24)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setPadding(dp(4), 0, dp(4), 0);
        TextView title = new TextView(this);
        title.setText("일시정지");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        header.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView game = new TextView(this);
        game.setText(this.gameTitle + (playTimeSeconds() > 0 ? " · " + playTimeText(playTimeSeconds()) : ""));
        game.setTextSize(12.0f);
        game.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        game.setGravity(8388613);
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        gameParams.setMargins(dp(12), 0, 0, 0);
        header.addView(game, gameParams);

        final java.util.List<Integer> tabs = new java.util.ArrayList<>(java.util.Arrays.asList(0, 1, 2, 3));
        if (hasDeviceTab()) {
            tabs.add(4);
        }
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabBar.setBackground(roundRect(Color.rgb(241, 243, 245), dp(14)));
        final TextView[] tabViews = new TextView[tabs.size()];

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);

        final int[] current = {0};
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            int tab = tabs.get(current[0]);
            for (int k = 0; k < tabViews.length; k++) {
                boolean on = k == current[0];
                tabViews[k].setTextColor(Theme.text(on ? Color.rgb(15, 118, 110) : Color.rgb(107, 114, 128)));
                tabViews[k].setBackground(on ? roundRect(Color.WHITE, dp(10)) : null);
                tabViews[k].setElevation(on ? dp(1) : 0);
            }
            content.removeAllViews();
            this.cheatRowValue = null;
            Runnable refresh = () -> {
                int y = scroll.getScrollY();
                fill[0].run();
                scroll.post(() -> scroll.scrollTo(0, y));
            };
            switch (tab) {
                case 0: addBasicTabRows(content, dialog, () -> {
                    current[0] = tabs.indexOf(1);
                    fill[0].run();
                    scroll.scrollTo(0, 0);
                }); break;
                case 1: addPerfTabRows(content, dialog); break;
                case 2: addScreenTabRows(content, refresh); break;
                case 3: addControlTabRows(content, refresh); break;
                default: addDeviceTabRows(content, dialog); break;
            }
        };
        for (int k = 0; k < tabs.size(); k++) {
            TextView tab = new TextView(this);
            tab.setText(PAUSE_TAB_NAMES[tabs.get(k)]);
            tab.setTextSize(12.5f);
            tab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tab.setGravity(Gravity.CENTER);
            final int index = k;
            tab.setOnClickListener(view -> {
                if (current[0] != index) {
                    current[0] = index;
                    fill[0].run();
                    scroll.scrollTo(0, 0);
                }
            });
            tabViews[k] = tab;
            LinearLayout.LayoutParams tabParams = wide ? new LinearLayout.LayoutParams(-1, dp(40)) : new LinearLayout.LayoutParams(0, dp(34), 1.0f);
            if (wide && k > 0) {
                tabParams.setMargins(0, dp(4), 0, 0);
            }
            tabBar.addView(tab, tabParams);
        }

        TextView resume = new TextView(this);
        resume.setText("▶  계속하기");
        resume.setTextSize(16.0f);
        resume.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        resume.setTextColor(Color.WHITE);
        resume.setGravity(17);
        resume.setBackground(roundRect(Color.rgb(22, 185, 207), dp(14)));
        resume.setOnClickListener(view -> dialog.dismiss());

        // The content area keeps one height for every tab: up to seven rows, less when the
        // screen is short (it scrolls then).
        int screenH = Math.round(metrics.heightPixels / metrics.density);
        int contentDp = wide ? Math.max(160, screenH - 150) : Math.max(220, Math.min(380, screenH - 260));
        int width = wide ? Math.min(metrics.widthPixels - dp(40), dp(600)) : Math.min(metrics.widthPixels - dp(40), dp(380));
        if (wide) {
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(84), -2);
            card.addView(tabBar, barParams);
            LinearLayout right = new LinearLayout(this);
            right.setOrientation(1);
            right.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, dp(contentDp));
            scrollParams.setMargins(0, dp(10), 0, 0);
            right.addView(scroll, scrollParams);
            LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, dp(46));
            resumeParams.setMargins(0, dp(10), 0, 0);
            right.addView(resume, resumeParams);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
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
        dialog.setOnDismissListener(d -> resumeUnlessExiting());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    private LinearLayout.LayoutParams tabRowParams(LinearLayout panel) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, panel.getChildCount() == 0 ? 0 : dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams tile4Params(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
        params.setMargins(column == 0 ? 0 : dp(3), 0, column == 3 ? 0 : dp(3), 0);
        return params;
    }

    // 기본 탭: the actions (four to a row), cheats, and a line on how the game runs that
    // leads to the 성능 탭.
    private void addBasicTabRows(LinearLayout panel, android.app.Dialog dialog, Runnable toPerf) {
        // Indices follow onGameMenuItemSelected: 1 save, 2 load, 3 in-game save, 4 reset,
        // 5 rotate, 6 exit, 7 screenshot, 8 whole-screen capture.
        java.util.List<View> tiles = new java.util.ArrayList<>();
        // Wii U: the engine keeps no states; the game's own saves are written as it makes them.
        if (!isWiiU()) {
            tiles.add(createMenuTile(dialog, 1, RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "빠른 저장", isMame139() ? null : "길게: 내 칸"));
            tiles.add(createMenuTile(dialog, 2, RoundIconView.LOAD, RoundIconView.COLOR_LOAD, "불러오기", lastQuickSaveText()));
            tiles.add(createMenuTile(dialog, 3, RoundIconView.CARD, RoundIconView.COLOR_CARD, "세이브 저장", null));
        }
        tiles.add(createMenuTile(dialog, 4, RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", null));
        tiles.add(createMenuTile(dialog, 5, RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, this.landscapeMode ? "세로 화면" : "가로 화면", null));
        if (!isMame139()) {
            tiles.add(createMenuTile(dialog, 7, RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "스크린샷", "화면만"));
            tiles.add(createMenuTile(dialog, 8, RoundIconView.PALETTE, RoundIconView.COLOR_PALETTE, "전체 캡처", "케이스 포함"));
        }
        tiles.add(createMenuTile(dialog, 6, RoundIconView.EXIT, RoundIconView.COLOR_EXIT, "나가기", null));
        LinearLayout row = null;
        for (int k = 0; k < tiles.size(); k++) {
            if (k % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, k == 0 ? 0 : dp(6), 0, 0);
                panel.addView(row, rowParams);
            }
            row.addView(tiles.get(k), tile4Params(k % 4));
        }
        for (int k = tiles.size() % 4; k != 0 && k < 4; k++) {
            row.addView(new View(this), tile4Params(k));
        }
        LinearLayout cheatRow = createSettingRow(RoundIconView.CHEAT, RoundIconView.COLOR_CHEAT, "치트 코드", isMame2003Plus() ? "MAME 메뉴" : cheatSummary(), view -> {
            if (isMame2003Plus()) {
                dialog.dismiss();
                showMameCheatDialog();
                return;
            }
            if (!cheatsSupported()) {
                Toast.makeText(this, cheatsUnsupportedText(), Toast.LENGTH_LONG).show();
                return;
            }
            showCheatSheet();
        });
        // Its value ("2개 켜짐") follows changes made in the cheat sheet over it.
        this.cheatRowValue = (TextView) cheatRow.getChildAt(cheatRow.getChildCount() - 1);
        LinearLayout.LayoutParams cheatParams = new LinearLayout.LayoutParams(-1, dp(52));
        cheatParams.setMargins(0, dp(10), 0, 0);
        panel.addView(cheatRow, cheatParams);
        int[] disc = isDiscGroup() && this.nativeHandle != 0 ? NativeGba.diskInfo(this.nativeHandle) : null;
        if (disc != null && disc.length == 2 && disc[1] > 1) {
            LinearLayout.LayoutParams discParams = new LinearLayout.LayoutParams(-1, dp(52));
            discParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.SWAP, Color.rgb(202, 138, 4), "디스크 바꾸기", "지금 Disc " + (disc[0] + 1) + " / " + disc[1],
                    view -> showDiscDialog(dialog)), discParams);
        }
        if (isMsx()) {
            addMsxMenuRows(panel, dialog);
        }
        if (isPc98()) {
            addPc98MenuRows(panel, dialog);
        }
        if ("fbneo".equals(this.coreId)) {
            LinearLayout.LayoutParams iniParams = new LinearLayout.LayoutParams(-1, dp(52));
            iniParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.CHEAT, RoundIconView.COLOR_CHEAT, "FBNeo 치트 (.ini)", fbneoCheatSummary(),
                    view -> showFbneoCheatSheet(dialog)), iniParams);
        }
        if ("fbneo".equals(this.coreId) && arcadePatchDir().isDirectory()) {
            addArcadePatchRows(panel, dialog);
        }
        if (!isMame139()) {
            TextView status = new TextView(this);
            status.setText(runStatusText());
            status.setTextSize(12.0f);
            status.setTextColor(Theme.text(Color.rgb(17, 94, 89)));
            status.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            status.setPadding(dp(12), dp(10), dp(12), dp(10));
            status.setBackground(roundRect(Color.rgb(240, 253, 250), dp(13)));
            status.setOnClickListener(view -> toPerf.run());
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
            statusParams.setMargins(0, dp(8), 0, 0);
            panel.addView(status, statusParams);
        }
    }

    private static final String[] THERMAL_WORDS = {"보통", "약간", "뜨거움", "심각"};
    private static final int[] THERMAL_DOT_COLORS = {Color.rgb(22, 163, 74), Color.rgb(202, 138, 4), Color.rgb(234, 88, 12), Color.rgb(220, 38, 38)};

    // "⚡ 균형 · ● 약간 · 화질 1배 · 속도 우선   성능 탭 ›"
    private CharSequence runStatusText() {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder("⚡ " + POWER_SHORT[powerMode()]);
        int level = PerfOverlayView.thermalLevel;
        if (level >= 0 && level <= 3) {
            text.append(" · ");
            int start = text.length();
            text.append("● ").append(THERMAL_WORDS[level]);
            text.setSpan(new android.text.style.ForegroundColorSpan(THERMAL_DOT_COLORS[level]), start, text.length(), 0);
        }
        int scale = isPsp() ? pspScale() : isN3ds() ? n3dsScale() : isN64() ? n64Scale() : 0;
        if (scale > 0) {
            text.append(" · 화질 ").append(String.valueOf(scale)).append("배");
        }
        if (speedFirstSupported() && speedFirst()) {
            text.append(" · 속도 우선");
        }
        text.append("   성능 탭 ›");
        return text;
    }

    // 성능 탭: power, the readout, render scale, 속도 우선 and heat.
    // ---- Dreamcast 그래픽 (0.0.311), per game: transparency sorted per pixel (Flycast's
    // "per-pixel"; overlapped see-through pieces such as a face's eyes and mouth in order),
    // and OpenGL instead of Vulkan to compare. Applied by starting the game again. ----
    private static final int DC_COLOR = Color.rgb(202, 138, 4);

    private boolean dcAlphaExact() {
        return MainActivity.sharedSettings(this).getBoolean("dc_alpha:" + this.saveBaseName, false);
    }

    private boolean dcOpenGl() {
        return MainActivity.sharedSettings(this).getBoolean("dc_gl:" + this.saveBaseName, false);
    }

    private String dcGraphicsSummary() {
        return (dcAlphaExact() ? "반투명 정확하게" : "반투명 빠르게") + " · " + (dcOpenGl() ? "OpenGL" : "Vulkan");
    }

    private LinearLayout createDcGraphicsRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.PALETTE, DC_COLOR, "그래픽 (이 게임)", dcGraphicsSummary(), view -> showDcGraphicsDialog(menu));
    }

    private TextView dcChoice(String title, String note, boolean on) {
        TextView choice = new TextView(this);
        choice.setText(title + "\n" + note);
        choice.setGravity(17);
        choice.setTextSize(12.5f);
        choice.setLineSpacing(0, 1.1f);
        choice.setPadding(dp(6), dp(9), dp(6), dp(9));
        choice.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        choice.setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(55, 65, 81)));
        choice.setBackground(roundRect(on ? DC_COLOR : Color.rgb(241, 243, 245), dp(11)));
        return choice;
    }

    private TextView dcNote(String text, int background, int color) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(11.5f);
        note.setLineSpacing(0, 1.15f);
        note.setTextColor(Theme.text(color));
        note.setPadding(dp(12), dp(9), dp(12), dp(9));
        note.setBackground(roundRect(background, dp(12)));
        return note;
    }

    private void showDcGraphicsDialog(android.app.Dialog menu) {
        final boolean[] exact = {dcAlphaExact()};
        final boolean[] gl = {dcOpenGl()};
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        TextView title = new TextView(this);
        title.setText("그래픽 (이 게임)");
        title.setTextSize(17f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        LinearLayout[] rows = new LinearLayout[2];
        Runnable[] render = new Runnable[1];
        String[][][] texts = {
                {{"빠르게", "삼각형 단위 · 기본"}, {"정확하게", "픽셀 단위 · 조금 무거움"}},
                {{"Vulkan", "기본 · 빠름"}, {"OpenGL", "다르게 보이면 비교용"}}};
        String[] labels = {"반투명 처리", "그리기 방식"};
        String[] notes = {"겹쳐 그리는 반투명 조각(얼굴의 눈 · 입, 연기, 유리)의 앞뒤를 픽셀마다 정해요. 조각 사이로 검은 틈이나 엉뚱한 색이 보이면 켜 보세요.",
                "그려지는 결과가 엔진마다 조금 달라요. 반투명 정확하게로 안 나아지면 이쪽도 바꿔 보세요."};
        int[][] noteColors = {{Color.rgb(255, 251, 235), Color.rgb(146, 64, 14)}, {Color.rgb(241, 245, 249), Color.rgb(51, 65, 85)}};
        for (int g = 0; g < 2; g++) {
            TextView label = new TextView(this);
            label.setText(labels[g]);
            label.setTextSize(13f);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setTextColor(Theme.text(Color.rgb(51, 65, 85)));
            label.setPadding(0, dp(12), 0, dp(6));
            panel.addView(label);
            rows[g] = new LinearLayout(this);
            panel.addView(rows[g]);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.setMargins(0, dp(8), 0, 0);
            panel.addView(dcNote(notes[g], noteColors[g][0], noteColors[g][1]), noteParams);
        }
        render[0] = () -> {
            for (int g = 0; g < 2; g++) {
                rows[g].removeAllViews();
                for (int k = 0; k < 2; k++) {
                    boolean on = (g == 0 ? exact[0] : gl[0]) == (k == 1);
                    TextView choice = dcChoice(texts[g][k][0], texts[g][k][1], on);
                    final int group = g;
                    final int which = k;
                    choice.setOnClickListener(v -> {
                        if (group == 0) {
                            exact[0] = which == 1;
                        } else {
                            gl[0] = which == 1;
                        }
                        render[0].run();
                    });
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
                    params.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
                    rows[g].addView(choice, params);
                }
            }
        };
        render[0].run();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, dp(10), 0, 0);
        panel.addView(dcNote("바꾸면 게임이 다시 켜져요 — 지금 장면을 저장했다가 이어서 시작해요. 이 게임에만 적용.", Color.rgb(239, 246, 255), Color.rgb(30, 58, 138)), infoParams);
        LinearLayout buttons = new LinearLayout(this);
        TextView cancel = dcChoice("취소", "", false);
        cancel.setText("취소");
        cancel.setTextSize(15f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView apply = dcChoice("적용 (다시 켜기)", "", true);
        apply.setText("적용 (다시 켜기)");
        apply.setTextSize(15f);
        apply.setOnClickListener(v -> {
            if (exact[0] == dcAlphaExact() && gl[0] == dcOpenGl()) {
                dialog.dismiss();
                return;
            }
            MainActivity.sharedSettings(this).edit().putBoolean("dc_alpha:" + this.saveBaseName, exact[0])
                    .putBoolean("dc_gl:" + this.saveBaseName, gl[0]).commit();
            dialog.dismiss();
            if (menu != null) {
                menu.dismiss();
            }
            relaunchGame();
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        applyParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(400)), -2);
        }
        dialog.show();
    }

    // ---- Dreamcast games on several discs (the app's .m3u, 0.0.303) ----
    private boolean isDiscGroup() {
        return (isDreamcast() || isPs1() || isSaturn() || isMegaCd() || isPcEngine() || isDolphin()) && this.romPath != null
                && this.romPath.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u");
    }

    private void rememberDisc() {
        if (!isDiscGroup() || this.nativeHandle == 0) {
            return;
        }
        int[] disc = NativeGba.diskInfo(this.nativeHandle);
        if (disc != null && disc.length == 2 && disc[1] > 1) {
            MainActivity.sharedSettings(this).edit().putInt("disc:" + this.saveBaseName, disc[0]).apply();
        }
    }

    private void showDiscDialog(android.app.Dialog menu) {
        int[] disc = NativeGba.diskInfo(this.nativeHandle);
        if (disc == null || disc.length != 2 || disc[1] < 2) {
            return;
        }
        java.util.List<File> files = MainActivity.msxPlaylistDisks(new File(this.romPath));
        String[] names = new String[disc[1]];
        String[] notes = new String[disc[1]];
        for (int k = 0; k < names.length; k++) {
            names[k] = "Disc " + (k + 1) + (k == disc[0] ? " · 지금 넣은 디스크" : "");
            notes[k] = k < files.size() ? files.get(k).getName() : "";
        }
        showChoiceDialog(RoundIconView.SWAP, Color.rgb(202, 138, 4), "디스크 바꾸기", names, notes, disc[0],
                "게임이 \"다음 디스크를 넣어 주세요\"라고 할 때 바꾸세요. 엔진이 뚜껑을 열고 디스크를 바꿔 끼워요 — 게임은 그대로 이어져요.\n"
                        + "빠른 저장 · 불러오기는 지금 넣은 디스크로 해요. 다른 디스크에서 저장한 칸을 불러오면 디스크를 먼저 그 디스크로 바꿔 주세요.",
                Color.rgb(255, 251, 235), Color.rgb(146, 64, 14), "바꾸기", choice -> {
                    if (choice == disc[0]) {
                        return;
                    }
                    NativeGba.diskSet(this.nativeHandle, choice);
                    MainActivity.sharedSettings(this).edit().putInt("disc:" + this.saveBaseName, choice).apply();
                    Toast.makeText(this, "Disc " + (choice + 1) + "(으)로 바꿨어요", Toast.LENGTH_SHORT).show();
                    if (menu != null) {
                        menu.dismiss();
                    }
                });
    }

    private void addPerfTabRows(LinearLayout panel, android.app.Dialog dialog) {
        if (isPadScreen()) {
            panel.addView(createSpeedRow(), tabRowParams(panel));
        }
        panel.addView(createPowerRow(dialog), tabRowParams(panel));
        panel.addView(createPerfRow(), tabRowParams(panel));
        if (isDreamcast()) {
            panel.addView(createDcGraphicsRow(dialog), tabRowParams(panel));
        }
        panel.addView(createLogSaveRow(), tabRowParams(panel));
        if (isPsp()) {
            panel.addView(createScaleRow(dialog), tabRowParams(panel));
        } else if (isN3ds()) {
            panel.addView(createN3dsScaleRow(dialog), tabRowParams(panel));
        } else if (isN64()) {
            panel.addView(createN64ScaleRow(dialog), tabRowParams(panel));
        }
        if (speedFirstSupported() && !isDolphin()) {
            panel.addView(createFastRow(dialog), tabRowParams(panel));
        }
        if (heavySystem()) {
            panel.addView(createSettingRow(RoundIconView.PERF, HEAT_ORANGE, "발열 줄이는 법", "", view -> showHeatTips(dialog)), tabRowParams(panel));
        }
        if (!isMame139()) {
            panel.addView(createHeatActionRow(), tabRowParams(panel));
        }
    }

    // 게임기 탭: what only this console has.
    private void addDeviceTabRows(LinearLayout panel, android.app.Dialog dialog) {
        if (isPs1()) {
            panel.addView(createPs1EngineRow(dialog), tabRowParams(panel));
        }
        if (isPsp()) {
            panel.addView(createLanguageRow(dialog), tabRowParams(panel));
            panel.addView(createWifiRow(dialog), tabRowParams(panel));
        }
        if (isN3ds()) {
            panel.addView(createN3dsNetRow(dialog), tabRowParams(panel));
            if (n3dsNetMode() != 0) {
                panel.addView(createN3dsNameRow(dialog), tabRowParams(panel));
            }
            panel.addView(createMicInputRow(), tabRowParams(panel));
            panel.addView(createMicButtonRow(), tabRowParams(panel));
        }
        if (isNds()) {
            panel.addView(createDsNetRow(dialog), tabRowParams(panel));
            panel.addView(createMicInputRow(), tabRowParams(panel));
            panel.addView(createMicButtonRow(), tabRowParams(panel));
        }
        if (isWonderSwan()) {
            panel.addView(createWonderSwanTallRow(dialog), tabRowParams(panel));
        }
        if (isDolphin()) {
            if (!isWiiGame()) {
                panel.addView(createGcLanRow(dialog), tabRowParams(panel));
            }
            if (isWiiGame()) {
                panel.addView(createWiiControlRow(dialog), tabRowParams(panel));
                panel.addView(createWiiPointerRow(dialog), tabRowParams(panel));
                panel.addView(createWiimmfiRow(dialog), tabRowParams(panel));
                if (wiiServer() != 0) {
                    panel.addView(createSettingRow(RoundIconView.GLOBE, wiiServerColor(), "접속 기록", wiiServer() == 2 ? "켠 코드 · 접속 과정 보기" : "바꾼 주소 보기", view -> showWiimmfiLog()), tabRowParams(panel));
                    panel.addView(createSettingRow(RoundIconView.WARN, Color.rgb(220, 38, 38), "오류 코드 안내", "게임에 나온 숫자로 원인 · 해결 찾기", view -> WiiHelp.show(this)), tabRowParams(panel));
                    if (!wiiCertificatesReady()) {
                        TextView hint = new TextView(this);
                        hint.setText("인증서가 없으면 접속 단계에서 막힐 수 있어요.\n게임큐브 · Wii 목록 화면의 \"Wii 시스템 업데이트\"를 먼저 해 주세요.");
                        hint.setTextSize(11.5f);
                        hint.setTextColor(Theme.text(Color.rgb(154, 52, 18)));
                        hint.setLineSpacing(0, 1.15f);
                        hint.setPadding(dp(12), dp(10), dp(12), dp(10));
                        hint.setBackground(roundRect(Color.rgb(255, 247, 237), dp(12)));
                        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
                        hintParams.setMargins(0, dp(8), 0, 0);
                        panel.addView(hint, hintParams);
                    }
                }
            }
            panel.addView(createDolphinScaleRow(dialog), tabRowParams(panel));
            panel.addView(createDolphinAspectRow(dialog), tabRowParams(panel));
            panel.addView(createFastRow(dialog), tabRowParams(panel));
        }
        if (isVita()) {
            panel.addView(createVitaScaleRow(dialog), tabRowParams(panel));
            panel.addView(createVitaMemoryRow(dialog), tabRowParams(panel));
            TextView vitaNote = new TextView(this);
            vitaNote.setText("PS Vita는 엔진이 장면을 저장하지 못해서 이어하기 · 되감기 · 세이브 슬롯이 없어요. 게임 안에서 저장해 주세요.\n앞면 터치 = 게임 화면 누르기 · 뒷면 터치 = L2를 누른 채 화면 누르기");
            vitaNote.setTextSize(12f);
            vitaNote.setTextColor(Theme.text(Color.rgb(100, 116, 139)));
            vitaNote.setPadding(dp(4), dp(8), dp(4), 0);
            panel.addView(vitaNote);
        }
        if (isWiiU()) {
            panel.addView(createWiiuViewRow(dialog), tabRowParams(panel));
            panel.addView(createWiiuResolutionRow(dialog), tabRowParams(panel));
            TextView note = new TextView(this);
            note.setText("Wii U는 엔진이 장면을 저장하지 못해서 이어하기 · 되감기 · 세이브 슬롯이 없어요. 게임 안에서 저장해 주세요.\n"
                    + "가로 화면의 게임패드 작은 창은 '버튼 배치 편집'에서 옮기고 크기를 바꿀 수 있어요.");
            note.setTextSize(12.0f);
            note.setLineSpacing(0.0f, 1.25f);
            note.setTextColor(Theme.text(Color.rgb(7, 89, 133)));
            note.setPadding(dp(12), dp(10), dp(12), dp(10));
            note.setBackground(roundRect(Color.rgb(224, 242, 254), dp(13)));
            panel.addView(note, tabRowParams(panel));
        }
    }

    // ---- Wii U settings rows ----
    private static final String[] WIIU_RES_VALUES = {"0.5x", "0.75x", "native", "1.5x"};
    private static final String[] WIIU_RES_NAMES = {"0.5배 (가벼움)", "0.75배", "1배 · 720p (기본)", "1.5배 · 1080p"};
    private static final String[] WIIU_RES_NOTES = {"화질은 떨어지지만 가장 빠르고 덜 뜨거워요", "조금 가볍게", "Wii U 원래 해상도", "선명하지만 무거워요 · 최신 기기용"};

    private int wiiuResolution() {
        int value = MainActivity.sharedSettings(this).getInt("wiiu_res:" + this.saveBaseName, 2);
        return value >= 0 && value < WIIU_RES_VALUES.length ? value : 2;
    }

    private LinearLayout createWiiuViewRow(android.app.Dialog menu) {
        String[] names = this.landscapeMode ? WIIU_VIEW_LANDSCAPE : WIIU_VIEW_PORTRAIT;
        String[] notes = this.landscapeMode
                ? new String[]{"TV 화면을 크게, 게임패드는 구석 작은 창 (터치 가능)", "TV 화면만", "게임패드 화면만 크게 (지도 · 메뉴 볼 때)"}
                : new String[]{"위 TV · 아래 게임패드 (터치 가능)", "TV 화면만 크게", "게임패드 화면만 크게"};
        return createSettingRow(RoundIconView.DS, RoundIconView.COLOR_DS, "게임패드 화면", wiiuViewName(),
                view -> showChoiceDialog(RoundIconView.DS, RoundIconView.COLOR_DS, "게임패드 화면", names, notes, wiiuView(),
                        "화면의 '화면 전환' 버튼으로도 차례대로 바꿀 수 있어요. 세로 · 가로 따로 기억해요.",
                        Color.rgb(224, 242, 254), Color.rgb(7, 89, 133), "적용", choice -> {
                    if (choice == wiiuView()) {
                        return;
                    }
                    menu.dismiss();
                    setWiiuView(choice);
                }));
    }

    private LinearLayout createWiiuResolutionRow(android.app.Dialog menu) {
        int current = wiiuResolution();
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도", WIIU_RES_NAMES[current],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도", WIIU_RES_NAMES, WIIU_RES_NOTES, current,
                        "이 게임에만 적용돼요. 바꾸면 게임이 다시 켜지고, Wii U는 이어하기가 없어서 타이틀부터 시작해요. 먼저 게임 안에서 저장해 주세요.",
                        Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용하고 다시 켜기", choice -> {
                    if (choice == current) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("wiiu_res:" + this.saveBaseName, choice).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // PSP system language, per game. PSP games pick their text (and some their keyboard)
    // from it; an English game following a Korean setting shows a Hangul keyboard it has no
    // font for.
    private static final String[] PSP_LANGUAGES = {"Korean", "English", "Japanese"};
    private static final String[] PSP_LANGUAGE_NAMES = {"한국어", "English", "日本語"};
    private static final String[] PSP_LANGUAGE_NOTES = {"기본값 · 한국어 지원 게임·한글 패치용", "영문판 게임 (이름 입력 키보드가 영어로)", "일본판 게임"};

    private String pspLanguage() {
        String value = MainActivity.sharedSettings(this).getString("psp_language:" + this.saveBaseName, "Korean");
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
        background.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        TextView label = new TextView(this);
        label.setText("배속");
        label.setTextSize(15.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
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
            MainActivity.sharedSettings(this).edit().putString("psp_language:" + this.saveBaseName, language).apply();
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
        int scale = MainActivity.sharedSettings(this).getInt("psp_scale:" + this.saveBaseName, 2);
        return scale >= 1 && scale <= 3 ? scale : 2;
    }

    private LinearLayout createScaleRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", PSP_SCALE_SHORT[pspScale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", PSP_SCALE_NAMES, PSP_SCALE_NOTES,
                        pspScale() - 1, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요 (2~3초).\n추천 조합: 렉이 걸리는 게임은 1배 + 설정 › 화면 필터 'FSR 업스케일'.\n빠르면서도 2배에 가깝게 또렷해 보여요.",
                        Color.rgb(238, 246, 255), Color.rgb(30, 79, 138), "적용하고 이어하기", choice -> {
                    if (choice + 1 == pspScale()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("psp_scale:" + this.saveBaseName, choice + 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // N64 rendering scale, per game: GLideN64 at the console's 320x240 or twice that.
    private static final String[] N64_SCALE_NAMES = {"1배 · 320×240", "2배 · 640×480"};
    private static final String[] N64_SCALE_SHORT = {"1배 · 가벼움", "2배 · 기본"};
    private static final String[] N64_SCALE_NOTES = {"N64 원래 해상도. 가장 가볍고 시원해요", "지금처럼 선명하게 (기본)"};

    private int n64Scale() {
        return MainActivity.sharedSettings(this).getInt("n64_scale:" + this.saveBaseName, 2) == 1 ? 1 : 2;
    }

    private LinearLayout createN64ScaleRow(android.app.Dialog menu) {
        String[] names = N64_SCALE_NAMES.clone();
        names[1] += " · N64 기본";
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", N64_SCALE_SHORT[n64Scale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", names, N64_SCALE_NOTES,
                        n64Scale() - 1, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요 (2~3초).\n1배에 FSR 업스케일을 같이 켜면 덜 뭉개져 보여요.",
                        Color.rgb(238, 246, 255), Color.rgb(30, 79, 138), "적용하고 이어하기", choice -> {
                    if (choice + 1 == n64Scale()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("n64_scale:" + this.saveBaseName, choice + 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // 3DS rendering scale, per game: Citra draws the 400x240 and 320x240 screens at 1-3
    // times their size. Native by default; the core is heavy enough on phones as it is.
    private static final String[] N3DS_SCALE_NAMES = {"1배 · 400×240", "2배 · 800×480", "3배 · 1200×720"};
    private static final String[] N3DS_SCALE_SHORT = {"1배 · 기본", "2배 · 선명", "3배 · 가장 선명"};
    private static final String[] N3DS_SCALE_NOTES = {"3DS 원래 해상도. 가장 빠름 (기본)", "선명. 성능 좋은 폰·가벼운 게임용", "가장 선명. 무거운 게임은 느려질 수 있어요"};

    private int n3dsScale() {
        int scale = MainActivity.sharedSettings(this).getInt("n3ds_scale:" + this.saveBaseName, 1);
        return scale >= 1 && scale <= 3 ? scale : 1;
    }

    private LinearLayout createN3dsScaleRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", N3DS_SCALE_SHORT[n3dsScale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", N3DS_SCALE_NAMES, N3DS_SCALE_NOTES,
                        n3dsScale() - 1, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요.\n3DS는 1배로도 무거운 게임이 많아요. 끊기면 1배로 돌려 주세요.",
                        Color.rgb(238, 246, 255), Color.rgb(30, 79, 138), "적용하고 이어하기", choice -> {
                    if (choice + 1 == n3dsScale()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("n3ds_scale:" + this.saveBaseName, choice + 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // "속도 우선", per game (PSP, and PS2 on PCSX2): speed hacks that can drop some effects,
    // off by default.
    private static final String[] FAST_NAMES = {"끔 · 정확하게", "켬 · 빠르게"};
    private static final String[] FAST_NOTES = {"지금처럼. 화면 효과를 모두 그대로 그려요 (기본)", "끊기는 게임용. 프레임마다 하는 일을 줄여요"};
    // PS2 has four levels: off, drawing only (0.0.287), on, strong.
    private static final String[] FAST_NAMES_PS2 = {"끔 · 정확하게", "그림만 가볍게", "켬 · 빠르게", "강하게"};
    private static final String[] FAST_NOTES_PS2 = {"지금처럼. 화면 효과를 모두 그대로 그려요 (기본)",
            "기술 이펙트 같은 효과에서 끊기는 게임용. 그리기만 가볍게 · PS2 CPU는 100% 그대로라 게임이 느려지지 않아요",
            "끊기는 게임용. 그리기 가볍게 + PS2 CPU 75% · CPU를 많이 쓰는 게임(철권 등)은 오히려 느려질 수 있어요",
            "켬으로도 속도가 출렁이는 게임용. PS2 CPU 60% · 게임 속도가 고르지 않아질 수 있어요"};
    private static final String[] FAST_SHORT = {"꺼짐", "켜짐", "강하게"};
    private static final String[] FAST_SHORT_PS2 = {"꺼짐", "그림만", "켜짐", "강하게"};

    // 0 off, 1 on (PSP, N64, ...). PS2: 0 off, 1 drawing only, 2 on, 3 strong.
    private int speedFirstLevel() {
        if (!speedFirst()) {
            return 0;
        }
        if (!"pcee2".equals(this.coreId)) {
            return 1;
        }
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        return prefs.getBoolean("fast_light:" + this.saveBaseName, false) ? 1 : prefs.getBoolean("fast_strong:" + this.saveBaseName, false) ? 3 : 2;
    }

    private boolean speedFirstSupported() {
        return isPsp() || "pcee2".equals(this.coreId) || isN64() || isDreamcast() || isDolphin();
    }

    // PSP kept its 0.0.106 key.
    private String speedFirstKey() {
        return (isPsp() ? "psp_fast:" : "fast:") + this.saveBaseName;
    }

    private boolean speedFirst() {
        return MainActivity.sharedSettings(this).getBoolean(speedFirstKey(), false);
    }

    // ---- Wi-Fi Direct: the link between phones when there is no router ----
    private DirectLink directLink;

    private DirectLink directLink() {
        if (this.directLink == null) {
            this.directLink = new DirectLink(this);
        }
        return this.directLink;
    }

    // How the host links with guests (PSP and DS alike): 0 a shared Wi-Fi, 1 Wi-Fi Direct,
    // 2 a virtual LAN app (VPN) for friends far away.
    private int linkMode() {
        android.content.SharedPreferences settings = MainActivity.sharedSettings(this);
        return settings.getBoolean("link_vpn", false) ? 2 : settings.getBoolean("link_direct", false) ? 1 : 0;
    }

    private boolean linkDirect() {
        return linkMode() == 1;
    }

    private void setLinkMode(int mode) {
        MainActivity.sharedSettings(this).edit().putBoolean("link_direct", mode == 1).putBoolean("link_vpn", mode == 2).commit();
    }

    private void setLinkDirect(boolean direct) {
        setLinkMode(direct ? 1 : 0);
    }

    // The address guests reach the host at, for the mode it hosts with.
    private String hostAddress() {
        switch (linkMode()) {
            case 1: return DirectLink.OWNER_ADDRESS;
            case 2: return AdhocBeacon.vpnAddress();
            default: return AdhocBeacon.localAddress();
        }
    }

    private static final String[] LINK_NAMES = {"같은 와이파이", "Wi-Fi Direct", "멀리 (VPN)"};
    private static final String[] LINK_CHOICE_NAMES = {"같은 와이파이", "Wi-Fi Direct", "멀리 있는 친구 (VPN)"};
    private static final String[] LINK_NOTES = {"공유기나 핫스팟에 같이 붙어 있을 때 (기본)", "공유기 없이 휴대폰끼리 직접 · 속도는 와이파이와 같고 데이터 요금은 안 들어요",
            "Tailscale · ZeroTier로 같은 네트워크에 있을 때. 방을 연 뒤 내 가상 IP를 친구에게 보내요"};

    // Asks the host how guests will reach it, then saves and relaunches.
    private void chooseLinkThen(android.app.Dialog menu, int icon, int color, Runnable save) {
        String vpn = AdhocBeacon.vpnAddress();
        showChoiceDialog(icon, color, "방을 어떻게 열까요", LINK_CHOICE_NAMES, LINK_NOTES, linkMode(),
                "Wi-Fi Direct는 처음 한 번 상대 휴대폰에서 '연결 허용'을 눌러야 해요. 근처 기기를 찾기 위한 권한을 한 번 물어봐요.\n"
                        + (vpn != null ? "VPN이 켜져 있어요 · 내 가상 IP " + vpn + "\n멀리 있는 친구를 고르면 방을 연 뒤 IP를 보낼 수 있어요."
                        : "VPN은 꺼져 있어요. 멀리 있는 친구와 하려면 둘 다 Tailscale이나 ZeroTier를 켜 주세요."),
                Color.rgb(232, 246, 255), Color.rgb(3, 105, 161), "적용", choice -> {
            boolean direct = choice == 1;
            if (direct && !directLink().available()) {
                Toast.makeText(this, "이 휴대폰은 Wi-Fi Direct를 지원하지 않아요.", Toast.LENGTH_LONG).show();
                return;
            }
            if (direct && !DirectLink.permitted(this)) {
                DirectLink.ask(this);
                Toast.makeText(this, "권한을 허용한 뒤 다시 골라 주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            if (choice == 0 && AdhocBeacon.localAddress() == null) {
                Toast.makeText(this, "와이파이(또는 핫스팟)에 먼저 연결해 주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            if (choice == 2 && AdhocBeacon.vpnAddress() == null) {
                Toast.makeText(this, "VPN이 꺼져 있어요. Tailscale이나 ZeroTier를 먼저 켜 주세요.", Toast.LENGTH_LONG).show();
                return;
            }
            setLinkMode(choice);
            if (!direct) {
                directLink().release();
            }
            // The share sheet opens once the game is back, so the friend gets the address.
            MainActivity.sharedSettings(this).edit().putBoolean("vpn_share_pending", choice == 2).commit();
            save.run();
            menu.dismiss();
            relaunchGame();
        });
    }

    private String linkShown() {
        int mode = linkMode();
        if (mode == 2) {
            String address = AdhocBeacon.vpnAddress();
            return address == null ? "VPN 꺼짐" : "VPN · " + address;
        }
        if (mode == 0) {
            String address = AdhocBeacon.localAddress();
            return address == null ? "와이파이 없음" : address;
        }
        int guests = directLink().clientCount();
        return "Wi-Fi Direct" + (guests > 0 ? " · 참가 " + guests + "명" : "");
    }

    // ---- "멀리 (VPN)": friends on the same virtual LAN, reached by typing their address ----
    private String vpnRecentKey() {
        return isN3ds() ? "vpn_recent_3ds" : isNds() ? "vpn_recent_ds" : isDolphin() ? "vpn_recent_gc" : "vpn_recent_psp";
    }

    // Recently joined virtual addresses for this system, newest first.
    private java.util.List<String> vpnRecent() {
        java.util.List<String> recent = new java.util.ArrayList<>();
        String value = MainActivity.sharedSettings(this).getString(vpnRecentKey(), "");
        for (String address : (value == null ? "" : value).split(",")) {
            if (!address.isEmpty() && !recent.contains(address)) {
                recent.add(address);
            }
        }
        return recent;
    }

    private void rememberVpnFriend(String address) {
        java.util.List<String> recent = vpnRecent();
        recent.remove(address);
        recent.add(0, address);
        while (recent.size() > 5) {
            recent.remove(recent.size() - 1);
        }
        MainActivity.sharedSettings(this).edit().putString(vpnRecentKey(), android.text.TextUtils.join(",", recent)).commit();
    }

    private String vpnInviteText(String address) {
        String where = isN3ds() ? "3DS 통신 › 참가" : isNds() ? "DS 통신 › 참가" : isDolphin() ? "LAN 대전 › 참가" : "와이파이 멀티 › 참가";
        return "Mini Emulator " + this.gameTitle + " 방을 열었어요. " + where + " › 멀리 (VPN)에서 " + address + " 으로 연결해 주세요.";
    }

    private void shareText(String text) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "IP 보내기"));
        } catch (Exception e) {
            Toast.makeText(this, "보낼 앱을 찾지 못했어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyText(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IP", text));
            Toast.makeText(this, "복사했어요: " + text, Toast.LENGTH_SHORT).show();
        }
    }

    private void openStore(String packageName) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + packageName)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
            } catch (Exception ignored) {
                Toast.makeText(this, "스토어를 열지 못했어요.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // After hosting with VPN was chosen: hand the friend the address once the game is back.
    private void shareVpnInviteIfPending() {
        android.content.SharedPreferences settings = MainActivity.sharedSettings(this);
        if (!settings.getBoolean("vpn_share_pending", false)) {
            return;
        }
        settings.edit().putBoolean("vpn_share_pending", false).apply();
        boolean hosting = (isPsp() && wifiMultiMode() == 1) || (isNds() && dsNetMode() == 1) || gcLanMode() == 1 || wiiRoomMode() == 1 || n3dsNetMode() == 1;
        String address = AdhocBeacon.vpnAddress();
        if (!hosting || linkMode() != 2 || address == null) {
            return;
        }
        getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing()) {
                shareText(vpnInviteText(address));
            }
        }, 1200);
    }

    // ---- "와이파이 멀티": PSP ad-hoc play between phones on one Wi-Fi ----
    private static final String[] WIFI_NAMES = {"꺼짐", "방 만들기 (방장)", "참가"};
    private static final String[] WIFI_NOTES = {"혼자 플레이 (기본)", "이 휴대폰이 서버가 돼요. 친구는 '참가'로 들어와요", "같은 와이파이의 방장을 찾아서 연결해요"};
    private final AdhocBeacon adhocBeacon = new AdhocBeacon();

    // 0 off, 1 host, 2 guest; shared by all PSP games.
    private int wifiMultiMode() {
        int value = MainActivity.sharedSettings(this).getInt("wifi_multi", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private String wifiMultiHost() {
        String value = MainActivity.sharedSettings(this).getString("wifi_multi_host", "");
        return value == null || value.isEmpty() ? null : value;
    }

    private void setWifiMulti(int mode, String host) {
        MainActivity.sharedSettings(this).edit().putInt("wifi_multi", mode).putString("wifi_multi_host", host == null ? "" : host).commit();
    }

    private String wifiMultiShown() {
        switch (wifiMultiMode()) {
            case 1: return "방장 · " + linkShown();
            case 2: return "참가 · " + (wifiMultiHost() == null ? "?" : wifiMultiHost());
            default: return "꺼짐";
        }
    }

    // Hosting answers guests looking for a room while this game runs.
    private void startAdhocBeaconIfHosting() {
        boolean hosting = (isPsp() && wifiMultiMode() == 1) || (isNds() && dsNetMode() == 1) || gcLanMode() == 1 || wiiRoomMode() == 1 || n3dsNetMode() == 1;
        if (hosting) {
            this.adhocBeacon.startHost(this, this.gameTitle);
            if (linkDirect()) {
                directLink().host(this.gameTitle, AdhocBeacon.deviceName(this));
            }
        } else {
            this.adhocBeacon.stop();
        }
    }

    // ---- "LAN 대전" (게임큐브): the broadband adapter between phones (gclan.cpp) ----
    private static final String[] GCLAN_NAMES = {"꺼짐", "방 만들기 (방장)", "참가"};
    private static final String[] GCLAN_NOTES = {"혼자 플레이 (기본)",
            "이 휴대폰이 LAN 허브가 돼요. 친구는 \"참가\"로 들어와요",
            "같은 와이파이 · Wi-Fi Direct · VPN의 방장을 찾아서 연결해요"};

    // 0 off, 1 host, 2 guest; GameCube games only, shared by all of them.
    private int gcLanMode() {
        if (!isDolphin() || isWiiGame()) {
            return 0;
        }
        int value = MainActivity.sharedSettings(this).getInt("gc_lan", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private String gcLanHost() {
        String value = MainActivity.sharedSettings(this).getString("gc_lan_host", "");
        return value == null || value.isEmpty() ? null : value;
    }

    private void setGcLan(int mode, String host) {
        MainActivity.sharedSettings(this).edit().putInt("gc_lan", mode).putString("gc_lan_host", host == null ? "" : host).commit();
    }

    private String gcLanShown() {
        switch (gcLanMode()) {
            case 1: return "방장 · " + linkShown();
            case 2: return "참가 · " + (gcLanHost() == null ? "?" : gcLanHost());
            default: return "꺼짐";
        }
    }

    private LinearLayout createGcLanRow(android.app.Dialog menu) {
        int mode = gcLanMode();
        int color = Color.rgb(99, 102, 241);
        LinearLayout row = createSettingRow(RoundIconView.WIFI, color, "LAN 대전", gcLanShown(),
                view -> showChoiceDialog(RoundIconView.WIFI, color, "LAN 대전", GCLAN_NAMES, GCLAN_NOTES, mode,
                        "게임큐브 LAN 어댑터를 흉내 내요. 연결된 뒤 게임 메뉴의 LAN 모드로 들어가면 돼요 (마리오카트 더블대시 · 커비 에어 라이드 · 1080° 애벌랜치).\n"
                                + "모든 휴대폰이 같은 게임(같은 지역판)이어야 하고, 같은 와이파이가 가장 잘 돼요.\n"
                                + "게임큐브 게임 공통 · 저장하고 그 장면에서 이어져요.",
                        Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    if (choice == 2) {
                        showHostFinder(menu, RoundIconView.WIFI, color, address -> setGcLan(2, address));
                        return;
                    }
                    if (choice == 1) {
                        chooseLinkThen(menu, RoundIconView.WIFI, color, () -> setGcLan(1, gcLanHost()));
                        return;
                    }
                    if (choice == mode) {
                        return;
                    }
                    setGcLan(choice, gcLanHost());
                    directLink().release();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (mode == 0) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Theme.text(Color.rgb(154, 161, 170)));
        }
        return row;
    }

    // On the picture's top left while LAN play is on: who is in the room, every two seconds.
    private void addGcLanBadge(FrameLayout screenFrame) {
        if (gcLanMode() == 0) {
            return;
        }
        TextView badge = new TextView(this);
        badge.setTextSize(11.0f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackground(roundRect(Color.argb(230, 99, 102, 241), dp(11)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        params.setMargins(dp(8), dp(8), 0, 0);
        screenFrame.addView(badge, params);
        Runnable[] update = new Runnable[1];
        update[0] = () -> {
            if (this.destroyed || badge.getParent() == null) {
                return;
            }
            int peers = NativeGba.gcLanPeers();
            if (gcLanMode() == 1) {
                badge.setText("LAN · 방장 · " + (peers + 1) + "대 연결");
            } else {
                badge.setText(peers > 0 ? "LAN · 방장과 연결됨" : "LAN · 방장 찾는 중…");
            }
            badge.postDelayed(update[0], 2000);
        };
        update[0].run();
    }

    // ---- "3DS 통신": the 3DS's local wireless between phones, through a Citra room the host
    // phone opens (citra-zz-mini-net core patch). 3DS games share the setting. ----
    private static final String[] N3DSNET_NAMES = {"꺼짐", "방 만들기 (방장)", "참가"};
    private static final String[] N3DSNET_NOTES = {"혼자 플레이 (기본)",
            "이 휴대폰이 방을 열어요 · 최대 4명",
            "같은 와이파이의 방장을 찾아 들어가요"};
    private static final int N3DSNET_PORT = 24872;
    private int n3dsNetError = -1;

    private int n3dsNetMode() {
        if (!isN3ds()) {
            return 0;
        }
        int value = MainActivity.sharedSettings(this).getInt("n3ds_net", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private String n3dsNetHost() {
        String value = MainActivity.sharedSettings(this).getString("n3ds_net_host", "");
        return value == null || value.isEmpty() ? null : value;
    }

    private void setN3dsNet(int mode, String host) {
        MainActivity.sharedSettings(this).edit().putInt("n3ds_net", mode).putString("n3ds_net_host", host == null ? "" : host).commit();
    }

    // The name others see in the room: the player's choice, else this phone's name.
    private String n3dsNetName() {
        String value = MainActivity.sharedSettings(this).getString("n3ds_net_name", "");
        if (value == null || value.trim().isEmpty()) {
            value = AdhocBeacon.deviceName(this);
        }
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "Player" : (value.length() > 20 ? value.substring(0, 20) : value);
    }

    // A console ID of this install for the room (two phones must differ).
    private String n3dsConsoleId() {
        android.content.SharedPreferences settings = MainActivity.sharedSettings(this);
        String id = settings.getString("n3ds_console_id", "");
        if (id == null || id.length() != 64) {
            StringBuilder sb = new StringBuilder();
            java.security.SecureRandom random = new java.security.SecureRandom();
            for (int k = 0; k < 32; k++) {
                sb.append(String.format(java.util.Locale.ROOT, "%02x", random.nextInt(256)));
            }
            id = sb.toString();
            settings.edit().putString("n3ds_console_id", id).apply();
        }
        return id;
    }

    private String n3dsNetShown() {
        switch (n3dsNetMode()) {
            case 1: return "방장 · " + linkShown();
            case 2: return "참가 · " + (n3dsNetHost() == null ? "?" : n3dsNetHost());
            default: return "꺼짐";
        }
    }

    private LinearLayout createN3dsNetRow(android.app.Dialog menu) {
        int mode = n3dsNetMode();
        LinearLayout row = createSettingRow(RoundIconView.DS, RoundIconView.COLOR_DS, "3DS 통신", n3dsNetShown(),
                view -> showChoiceDialog(RoundIconView.DS, RoundIconView.COLOR_DS, "3DS 통신", N3DSNET_NAMES, N3DSNET_NOTES, mode,
                        "같은 와이파이에 있는 Mini Emulator끼리 3DS 로컬 통신으로 놀아요. 연결된 뒤 게임 안의 \"로컬 통신 · 함께 하기\" 메뉴를 그대로 쓰면 돼요.\n"
                                + "모두 같은 게임 · 같은 버전(업데이트)이어야 해요. 엇갈림 통신 · 인터넷 통신(닌텐도 서버 종료)은 안 돼요.\n"
                                + "방에서 보이는 이름: " + n3dsNetName() + " (아래 \"3DS 통신 이름\"에서 바꿔요)\n"
                                + "3DS 게임 공통 · 저장하고 그 장면에서 이어져요.",
                        Color.rgb(253, 242, 248), Color.rgb(157, 23, 77), "적용", choice -> {
                    if (choice == 2) {
                        showHostFinder(menu, RoundIconView.DS, RoundIconView.COLOR_DS, address -> setN3dsNet(2, address));
                        return;
                    }
                    if (choice == 1) {
                        chooseLinkThen(menu, RoundIconView.DS, RoundIconView.COLOR_DS, () -> setN3dsNet(1, n3dsNetHost()));
                        return;
                    }
                    if (choice == mode) {
                        return;
                    }
                    setN3dsNet(choice, n3dsNetHost());
                    directLink().release();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (mode == 0) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Theme.text(Color.rgb(154, 161, 170)));
        }
        return row;
    }

    private LinearLayout createN3dsNameRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.EDIT, RoundIconView.COLOR_DS, "3DS 통신 이름", n3dsNetName(), view -> {
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setSingleLine(true);
            input.setText(n3dsNetName());
            input.setSelection(input.getText().length());
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20)});
            android.widget.FrameLayout box = new android.widget.FrameLayout(this);
            box.setPadding(dp(20), dp(8), dp(20), 0);
            box.addView(input);
            new AppDialog.Builder(this).setTitle("3DS 통신 이름")
                    .setMessage("방에서 다른 사람에게 보이는 이름이에요. 같은 방에 같은 이름이 있으면 들어갈 수 없어요.")
                    .setView(box)
                    .setNegativeButton("취소", null)
                    .setPositiveButton("저장", (d, which) -> {
                        String name = input.getText().toString().trim();
                        MainActivity.sharedSettings(this).edit().putString("n3ds_net_name", name).commit();
                        menu.dismiss();
                        if (this.nativeHandle != 0 && n3dsNetMode() != 0) {
                            startN3dsNet(this.nativeHandle);  // join again under the new name
                        }
                    }).show();
        });
    }

    // Opens or joins the room once the game is running (off the main thread: joining waits).
    private void startN3dsNet(final long handle) {
        final int mode = n3dsNetMode();
        if (mode == 0) {
            return;
        }
        final String host = n3dsNetHost();
        if (mode == 2 && host == null) {
            return;
        }
        final String name = n3dsNetName();
        final String console = n3dsConsoleId();
        final String game = this.gameTitle;
        this.n3dsNetError = -1;
        new Thread(() -> {
            int result = NativeGba.n3dsNetStart(handle, mode == 1, mode == 2 ? host : "", N3DSNET_PORT, name, console, 4, game);
            runOnUiThread(() -> {
                if (isFinishing() || this.destroyed) {
                    return;
                }
                if (result < 0) {
                    Toast.makeText(this, "이 3DS 엔진은 아직 3DS 통신을 지원하지 않아요. 앱을 업데이트해 주세요.", Toast.LENGTH_LONG).show();
                } else if (result == 0) {
                    Toast.makeText(this, "방을 열지 못했어요. 다른 앱이 같은 포트(24872)를 쓰고 있는지 확인해 주세요.", Toast.LENGTH_LONG).show();
                }
            });
        }, "n3ds-net").start();
    }

    private static String n3dsNetErrorText(int error) {
        switch (error) {
            case 0: return "방과 연결이 끊겼어요.";
            case 1: return "방장이 내보냈어요.";
            case 3: return "같은 이름이 방에 있어요. 메뉴 › 3DS 통신 이름을 바꿔 주세요.";
            case 5: return "같은 콘솔 ID가 방에 있어요. 다른 휴대폰에서 들어가 주세요.";
            case 6: return "방장과 앱 버전이 달라요. 둘 다 같은 버전으로 업데이트해 주세요.";
            case 8: return "방장에게 연결하지 못했어요. 방장이 게임을 켜 두었는지, 같은 와이파이인지 확인해 주세요.";
            case 9: return "방이 꽉 찼어요 (최대 4명).";
            default: return "방에 들어가지 못했어요.";
        }
    }

    // On the picture's top left while 3DS 통신 is on: the room's state; tap for who is in it.
    private void addN3dsNetBadge(FrameLayout screenFrame) {
        if (n3dsNetMode() == 0) {
            return;
        }
        TextView badge = new TextView(this);
        badge.setTextSize(11.0f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackground(roundRect(Color.argb(230, 190, 18, 60), dp(11)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        params.setMargins(dp(8), dp(8), 0, 0);
        screenFrame.addView(badge, params);
        final String[] members = {""};
        final boolean[] wasIn = {false};
        badge.setOnClickListener(view -> showN3dsRoom(members[0]));
        Runnable[] update = new Runnable[1];
        update[0] = () -> {
            if (this.destroyed || badge.getParent() == null) {
                return;
            }
            String[] status = this.nativeHandle == 0 ? null : NativeGba.n3dsNetStatus(this.nativeHandle);
            int code = 0;
            try {
                code = status == null ? 0 : Integer.parseInt(status[0]);
            } catch (NumberFormatException ignored) {
                code = 0;
            }
            int state = code & 0xFF;
            int error = (code >> 8) - 1;
            members[0] = status == null || status[1] == null ? "" : status[1].trim();
            int count = members[0].isEmpty() ? 0 : members[0].split("\n").length;
            boolean in = state == 3 || state == 4;
            if (in) {
                badge.setText("3DS 통신 · " + (n3dsNetMode() == 1 ? "방장" : "참가") + " · " + count + "명");
                if (!wasIn[0]) {
                    Toast.makeText(this, "✅ 방에 들어갔어요 (" + count + "/4명). 게임 안의 로컬 통신 메뉴에서 상대를 찾으세요.", Toast.LENGTH_LONG).show();
                }
            } else if (state == 2 || (this.nativeHandle == 0)) {
                badge.setText("3DS 통신 · 연결 중…");
            } else {
                badge.setText(error >= 0 ? "3DS 통신 · 연결 안 됨 (눌러서 보기)" : "3DS 통신 · 연결 중…");
            }
            if (error >= 0 && error != this.n3dsNetError) {
                this.n3dsNetError = error;
                Toast.makeText(this, n3dsNetErrorText(error), Toast.LENGTH_LONG).show();
            }
            wasIn[0] = in;
            badge.postDelayed(update[0], 2000);
        };
        badge.postDelayed(update[0], 1500);
    }

    private void showN3dsRoom(String members) {
        StringBuilder text = new StringBuilder();
        if (members.isEmpty()) {
            text.append(this.n3dsNetError >= 0 ? n3dsNetErrorText(this.n3dsNetError) : "아직 방에 들어가지 않았어요.");
        } else {
            for (String name : members.split("\n")) {
                text.append("• ").append(name).append(name.equals(n3dsNetName()) ? " (나)" : "").append('\n');
            }
        }
        String where = n3dsNetMode() == 1 ? "방장 · " + linkShown() : "참가 · " + (n3dsNetHost() == null ? "?" : n3dsNetHost());
        new AppDialog.Builder(this).setTitle("3DS 통신 · " + where)
                .setMessage(text.toString().trim())
                .setNegativeButton("닫기", null)
                .setPositiveButton("방 나가기", (d, which) -> {
                    setN3dsNet(0, n3dsNetHost());
                    if (this.nativeHandle != 0) {
                        NativeGba.n3dsNetStop(this.nativeHandle);
                    }
                    relaunchGame();
                }).show();
    }

    // ---- "DS 통신": the DS's local wireless between phones, or the replacement online servers ----
    private static final String[] DSNET_NAMES = {"꺼짐", "방 만들기 (방장)", "참가", "인터넷 · Wiimmfi", "인터넷 · Kaeru WFC"};
    private static final String[] DSNET_NOTES = {"혼자 플레이 (기본)",
            "같은 와이파이의 휴대폰끼리 · 옆 사람 마을 놀러가기 · 로컬 대전 · 다운로드 플레이",
            "같은 와이파이의 방장을 찾아서 연결해요",
            "옛 닌텐도 Wi-Fi 커넥션 대체 서버 · 마리오 카트 DS · 동물의 숲 등 (게임마다 지원 여부가 달라요)",
            "다른 대체 서버 · Wiimmfi에서 안 되면 시도"};

    private int dsNetMode() {
        int value = MainActivity.sharedSettings(this).getInt("ds_net", 0);
        return value >= 0 && value <= 4 ? value : 0;
    }

    private String dsNetHost() {
        String value = MainActivity.sharedSettings(this).getString("ds_net_host", "");
        return value == null || value.isEmpty() ? null : value;
    }

    private void setDsNet(int mode, String host) {
        MainActivity.sharedSettings(this).edit().putInt("ds_net", mode).putString("ds_net_host", host == null ? "" : host).commit();
    }

    private String dsNetShown() {
        switch (dsNetMode()) {
            case 1: return "로컬 · 방장 · " + linkShown();
            case 2: return "로컬 · 참가 · " + (dsNetHost() == null ? "?" : dsNetHost());
            case 3: return "인터넷 · Wiimmfi";
            case 4: return "인터넷 · Kaeru WFC";
            default: return "꺼짐";
        }
    }

    private LinearLayout createDsNetRow(android.app.Dialog menu) {
        int mode = dsNetMode();
        LinearLayout row = createSettingRow(RoundIconView.DS, RoundIconView.COLOR_DS, "DS 통신", dsNetShown(),
                view -> showChoiceDialog(RoundIconView.DS, RoundIconView.COLOR_DS, "DS 통신", DSNET_NAMES, DSNET_NOTES, mode,
                        "로컬(방장 · 참가): 같은 와이파이에 있는 Mini Emulator끼리 DS 무선 통신으로 놀아요. 연결된 뒤 게임 안의 통신 메뉴를 그대로 쓰면 돼요. 두 대 모두 같은 게임이어야 하고, 와이파이가 느리면 끊길 수 있어요.\n"
                                + "인터넷: 게임 안 Wi-Fi 설정에서 접속하면 대체 서버로 가요. 친구 코드 같은 절차는 원래대로예요.\n"
                                + "DS 게임 공통 · 저장하고 그 장면에서 이어져요 (2~3초).",
                        Color.rgb(253, 242, 248), Color.rgb(157, 23, 77), "적용", choice -> {
                    if (choice == 2) {
                        showHostFinder(menu, RoundIconView.DS, RoundIconView.COLOR_DS, address -> setDsNet(2, address));
                        return;
                    }
                    if (choice == 1) {
                        chooseLinkThen(menu, RoundIconView.DS, RoundIconView.COLOR_DS, () -> setDsNet(1, dsNetHost()));
                        return;
                    }
                    if (choice == mode) {
                        return;
                    }
                    setDsNet(choice, dsNetHost());
                    directLink().release();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (mode == 0) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Theme.text(Color.rgb(154, 161, 170)));
        }
        return row;
    }

    private LinearLayout createWifiRow(android.app.Dialog menu) {
        int mode = wifiMultiMode();
        LinearLayout row = createSettingRow(RoundIconView.WIFI, RoundIconView.COLOR_WIFI, "와이파이 멀티", wifiMultiShown(),
                view -> showChoiceDialog(RoundIconView.WIFI, RoundIconView.COLOR_WIFI, "와이파이 멀티", WIFI_NAMES, WIFI_NOTES, mode,
                        "같은 와이파이에 있는 Mini Emulator끼리 PSP 애드혹 통신으로 놀아요 (몬스터 헌터 · 철권 6 등).\n"
                                + "연결된 뒤에는 게임 안에서 PSP처럼 애드혹 방을 만들고 들어가면 돼요. 두 대 모두 같은 게임(같은 지역판)이어야 해요.\n"
                                + "두 대 모두 게임이 제 속도로 돌아야 끊기지 않아요. 핫스팟을 켜고 다른 휴대폰을 거기 붙여도 돼요.\n"
                                + "PSP 게임 공통 · 저장하고 그 장면에서 이어져요 (2~3초).",
                        Color.rgb(236, 253, 250), Color.rgb(15, 118, 110), "적용", choice -> {
                    if (choice == 2) {
                        showHostFinder(menu);
                        return;
                    }
                    if (choice == 1) {
                        chooseLinkThen(menu, RoundIconView.WIFI, RoundIconView.COLOR_WIFI, () -> setWifiMulti(1, wifiMultiHost()));
                        return;
                    }
                    if (choice == mode) {
                        return;
                    }
                    setWifiMulti(choice, wifiMultiHost());
                    directLink().release();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (mode == 0) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Theme.text(Color.rgb(154, 161, 170)));
        }
        return row;
    }

    // "방장 찾기": hosts on this Wi-Fi as they answer, and a box to type an address.
    private interface HostPicked {
        void onPicked(String address);
    }

    private void showHostFinder(android.app.Dialog menu) {
        showHostFinder(menu, RoundIconView.WIFI, RoundIconView.COLOR_WIFI, address -> setWifiMulti(2, address));
    }

    private void showHostFinder(android.app.Dialog menu, int icon, int color, HostPicked picked) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(16);
        header.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("방장 찾기");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        final TextView status = new TextView(this);
        status.setText("같은 와이파이에서 찾는 중…");
        status.setTextSize(12.0f);
        status.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        titles.addView(status);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Same Wi-Fi (the beacon), Wi-Fi Direct (the phone's own search) or a VPN (typed address).
        String lastHost = isN3ds() ? n3dsNetHost() : isNds() ? dsNetHost() : isDolphin() ? (isWiiGame() ? wiiRoomHost() : gcLanHost()) : wifiMultiHost();
        final int[] tab = {lastHost != null && vpnRecent().contains(lastHost) ? 2 : linkMode()};
        final TextView[] tabs = new TextView[3];
        LinearLayout tabRow = new LinearLayout(this);
        android.graphics.drawable.GradientDrawable tabBackground = new android.graphics.drawable.GradientDrawable();
        tabBackground.setColor(Theme.bg(Color.rgb(233, 235, 240)));
        tabBackground.setCornerRadius(dp(12));
        tabRow.setBackground(tabBackground);
        tabRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int k = 0; k < 3; k++) {
            TextView tabView = new TextView(this);
            tabView.setText(LINK_NAMES[k]);
            tabView.setTextSize(12.5f);
            tabView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tabView.setGravity(17);
            tabView.setSingleLine(true);
            tabs[k] = tabView;
            tabRow.addView(tabView, new LinearLayout.LayoutParams(0, dp(34), 1.0f));
        }
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-1, -2);
        tabParams.setMargins(0, dp(10), 0, 0);
        panel.addView(tabRow, tabParams);
        final Runnable paintTabs = () -> {
            for (int k = 0; k < 3; k++) {
                boolean on = k == tab[0];
                android.graphics.drawable.GradientDrawable tabFill = new android.graphics.drawable.GradientDrawable();
                tabFill.setColor(Theme.bg(on ? Color.WHITE : Color.TRANSPARENT));
                tabFill.setCornerRadius(dp(10));
                tabs[k].setBackground(tabFill);
                tabs[k].setTextColor(Theme.text(on ? color : Color.rgb(102, 112, 128)));
            }
        };

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(1);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, -2);
        listParams.setMargins(0, dp(10), 0, 0);
        panel.addView(list, listParams);

        // The VPN tab: this phone's virtual address, recent friends, or how to get a VPN on.
        final LinearLayout vpnBox = new LinearLayout(this);
        vpnBox.setOrientation(1);
        vpnBox.setVisibility(View.GONE);
        panel.addView(vpnBox, new LinearLayout.LayoutParams(-1, -2));

        final TextView waiting = new TextView(this);
        waiting.setText("찾는 중… 방장이 '방 만들기'를 먼저 켜야 보여요");
        waiting.setTextSize(12.5f);
        waiting.setGravity(17);
        waiting.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        waiting.setPadding(0, dp(14), 0, dp(14));
        panel.addView(waiting, new LinearLayout.LayoutParams(-1, -2));

        // Typing the address shown on the host's menu row.
        LinearLayout manual = new LinearLayout(this);
        manual.setGravity(16);
        android.graphics.drawable.GradientDrawable manualBackground = new android.graphics.drawable.GradientDrawable();
        manualBackground.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        manualBackground.setCornerRadius(dp(14));
        manual.setBackground(manualBackground);
        manual.setPadding(dp(14), dp(4), dp(8), dp(4));
        final android.widget.EditText address = new android.widget.EditText(this);
        address.setHint("IP 직접 입력 (예: 192.168.0.12)");
        address.setText(lastHost == null ? "" : lastHost);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setSingleLine(true);
        address.setTextSize(14.0f);
        address.setBackground(null);
        manual.addView(address, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView connect = dialogButton("연결", color, Color.WHITE);
        connect.setPadding(dp(14), 0, dp(14), 0);
        connect.setOnClickListener(view -> {
            String typed = address.getText().toString().trim();
            if (!typed.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                Toast.makeText(this, "IP 주소 모양이 아니에요 (예: 192.168.0.12)", Toast.LENGTH_SHORT).show();
                return;
            }
            if (tab[0] == 2) {
                rememberVpnFriend(typed);
            }
            picked.onPicked(typed);
            dialog.dismiss();
            menu.dismiss();
            relaunchGame();
        });
        manual.addView(connect, new LinearLayout.LayoutParams(-2, dp(36)));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(-1, dp(52));
        panel.addView(manual, manualParams);

        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(48));
        cancelParams.setMargins(0, dp(12), 0, 0);
        panel.addView(cancel, cancelParams);

        final Thread[] search = new Thread[1];
        final Runnable[] again = new Runnable[1];
        final Runnable[] searchDirect = new Runnable[1];
        again[0] = () -> search[0] = AdhocBeacon.discover((hosts, finished) -> {
            if (!dialog.isShowing() || tab[0] != 0) {
                return;
            }
            list.removeAllViews();
            for (AdhocBeacon.Host host : hosts) {
                boolean sameGame = host.game.isEmpty() || host.game.equals(this.gameTitle);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(16);
                android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
                rowBackground.setColor(Theme.bg(Color.rgb(246, 247, 249)));
                rowBackground.setCornerRadius(dp(14));
                row.setBackground(rowBackground);
                row.setPadding(dp(14), dp(10), dp(10), dp(10));
                LinearLayout words = new LinearLayout(this);
                words.setOrientation(1);
                TextView name = new TextView(this);
                name.setText(host.name.isEmpty() ? host.address : host.name);
                name.setTextSize(14.5f);
                name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
                words.addView(name);
                TextView detail = new TextView(this);
                detail.setText((host.game.isEmpty() ? "" : host.game + " · ") + host.address + (sameGame ? "" : " · 같은 게임이 아니에요"));
                detail.setTextSize(12.0f);
                detail.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                words.addView(detail);
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
                if (sameGame) {
                    TextView go = dialogButton("연결", color, Color.WHITE);
                    go.setPadding(dp(14), 0, dp(14), 0);
                    go.setOnClickListener(view -> {
                        picked.onPicked(host.address);
                        dialog.dismiss();
                        menu.dismiss();
                        relaunchGame();
                    });
                    row.addView(go, new LinearLayout.LayoutParams(-2, dp(34)));
                } else {
                    row.setAlpha(0.55f);
                }
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, 0, 0, dp(8));
                list.addView(row, rowParams);
            }
            if (finished) {
                status.setText(hosts.isEmpty() ? "방장을 못 찾았어요" : hosts.size() + "곳 찾음");
                waiting.setText(hosts.isEmpty() ? "방장이 '방 만들기'를 켰는지, 같은 와이파이인지 확인하고 다시 찾기를 눌러 주세요" : "다시 찾기");
                waiting.setTextColor(Theme.text(color));
                waiting.setOnClickListener(view -> {
                    status.setText("같은 와이파이에서 찾는 중…");
                    waiting.setText("찾는 중…");
                    waiting.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                    waiting.setOnClickListener(null);
                    again[0].run();
                });
            }
        });
        // Wi-Fi Direct: rooms the phone's own search finds; connecting waits for the owner's acceptance.
        searchDirect[0] = () -> {
            if (!directLink().available()) {
                status.setText("이 휴대폰은 Wi-Fi Direct를 지원하지 않아요");
                waiting.setText("");
                return;
            }
            if (!DirectLink.permitted(this)) {
                status.setText("근처 기기를 찾을 권한이 필요해요");
                waiting.setText("권한 허용하기");
                waiting.setTextColor(Theme.text(color));
                waiting.setOnClickListener(view -> DirectLink.ask(this));
                return;
            }
            status.setText("근처 휴대폰을 찾는 중…");
            waiting.setText("방장이 '방 만들기 · Wi-Fi Direct'를 켜 두면 여기에 보여요");
            waiting.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            waiting.setOnClickListener(null);
            directLink().discover(rooms -> {
                if (!dialog.isShowing() || tab[0] != 1) {
                    return;
                }
                list.removeAllViews();
                for (DirectLink.Room room : rooms) {
                    boolean sameGame = room.game.isEmpty() || room.game.equals(this.gameTitle);
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(16);
                    android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
                    rowBackground.setColor(Theme.bg(Color.rgb(246, 247, 249)));
                    rowBackground.setCornerRadius(dp(14));
                    row.setBackground(rowBackground);
                    row.setPadding(dp(14), dp(10), dp(10), dp(10));
                    LinearLayout words = new LinearLayout(this);
                    words.setOrientation(1);
                    TextView name = new TextView(this);
                    name.setText(room.name);
                    name.setTextSize(14.5f);
                    name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
                    words.addView(name);
                    TextView detail = new TextView(this);
                    detail.setText("Mini Emulator" + (room.game.isEmpty() ? "" : " · " + room.game) + (sameGame ? "" : " · 같은 게임이 아니에요"));
                    detail.setTextSize(12.0f);
                    detail.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                    words.addView(detail);
                    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
                    if (sameGame) {
                        TextView go = dialogButton("연결", color, Color.WHITE);
                        go.setPadding(dp(14), 0, dp(14), 0);
                        go.setOnClickListener(view -> {
                            status.setText("방장의 연결 허용을 기다리는 중…");
                            go.setEnabled(false);
                            go.setAlpha(0.5f);
                            directLink().connect(room, new DirectLink.Connected() {
                                @Override
                                public void onConnected(String hostAddress) {
                                    if (!dialog.isShowing()) {
                                        return;
                                    }
                                    setLinkDirect(true);
                                    picked.onPicked(hostAddress);
                                    dialog.dismiss();
                                    menu.dismiss();
                                    relaunchGame();
                                }

                                @Override
                                public void onFailed(String why) {
                                    status.setText(why);
                                    go.setEnabled(true);
                                    go.setAlpha(1f);
                                }
                            });
                        });
                        row.addView(go, new LinearLayout.LayoutParams(-2, dp(34)));
                    } else {
                        row.setAlpha(0.55f);
                    }
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                    rowParams.setMargins(0, 0, 0, dp(8));
                    list.addView(row, rowParams);
                }
            });
        };
        final Runnable fillVpn = () -> {
            vpnBox.removeAllViews();
            String mine = AdhocBeacon.vpnAddress();
            if (mine != null) {
                status.setText("VPN으로 멀리 있는 방장에게 연결");
                LinearLayout card = new LinearLayout(this);
                card.setGravity(16);
                android.graphics.drawable.GradientDrawable cardBackground = new android.graphics.drawable.GradientDrawable();
                cardBackground.setColor(Theme.bg(Color.rgb(236, 253, 245)));
                cardBackground.setCornerRadius(dp(14));
                card.setBackground(cardBackground);
                card.setPadding(dp(14), dp(10), dp(10), dp(10));
                LinearLayout words = new LinearLayout(this);
                words.setOrientation(1);
                TextView on = new TextView(this);
                on.setText("VPN 켜짐");
                on.setTextSize(12.0f);
                on.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                on.setTextColor(Theme.text(Color.rgb(4, 120, 87)));
                words.addView(on);
                TextView ip = new TextView(this);
                ip.setText("내 가상 IP " + mine);
                ip.setTextSize(14.5f);
                ip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                ip.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
                words.addView(ip);
                card.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
                TextView copy = dialogButton("복사", Color.WHITE, Color.rgb(4, 120, 87));
                copy.setPadding(dp(12), 0, dp(12), 0);
                copy.setOnClickListener(view -> copyText(mine));
                card.addView(copy, new LinearLayout.LayoutParams(-2, dp(32)));
                TextView share = dialogButton("공유", Color.rgb(4, 120, 87), Color.WHITE);
                share.setPadding(dp(12), 0, dp(12), 0);
                share.setOnClickListener(view -> shareText("Mini Emulator 내 가상 IP: " + mine));
                LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(-2, dp(32));
                shareParams.setMargins(dp(6), 0, 0, 0);
                card.addView(share, shareParams);
                vpnBox.addView(card, new LinearLayout.LayoutParams(-1, -2));
                java.util.List<String> recent = vpnRecent();
                if (!recent.isEmpty()) {
                    TextView heading = new TextView(this);
                    heading.setText("최근 연결한 친구");
                    heading.setTextSize(12.0f);
                    heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    heading.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                    heading.setPadding(dp(4), dp(12), 0, dp(6));
                    vpnBox.addView(heading);
                    for (String friend : recent) {
                        LinearLayout row = new LinearLayout(this);
                        row.setGravity(16);
                        android.graphics.drawable.GradientDrawable rowBackground = new android.graphics.drawable.GradientDrawable();
                        rowBackground.setColor(Theme.bg(Color.rgb(246, 247, 249)));
                        rowBackground.setCornerRadius(dp(14));
                        row.setBackground(rowBackground);
                        row.setPadding(dp(14), dp(8), dp(10), dp(8));
                        TextView name = new TextView(this);
                        name.setText(friend);
                        name.setTextSize(14.0f);
                        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
                        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
                        TextView go = dialogButton("연결 ›", color, Color.WHITE);
                        go.setPadding(dp(14), 0, dp(14), 0);
                        go.setOnClickListener(view -> {
                            rememberVpnFriend(friend);
                            picked.onPicked(friend);
                            dialog.dismiss();
                            menu.dismiss();
                            relaunchGame();
                        });
                        row.addView(go, new LinearLayout.LayoutParams(-2, dp(32)));
                        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                        rowParams.setMargins(0, 0, 0, dp(6));
                        vpnBox.addView(row, rowParams);
                    }
                }
                TextView warn = new TextView(this);
                warn.setText("친구도 같은 VPN 네트워크에 있어야 해요. 멀리 떨어질수록 끊기기 쉬워요" + (isNds() ? " (특히 DS)." : "."));
                warn.setTextSize(12.0f);
                warn.setTextColor(Theme.text(Color.rgb(146, 64, 14)));
                android.graphics.drawable.GradientDrawable warnBackground = new android.graphics.drawable.GradientDrawable();
                warnBackground.setColor(Theme.bg(Color.rgb(255, 247, 237)));
                warnBackground.setCornerRadius(dp(12));
                warn.setBackground(warnBackground);
                warn.setPadding(dp(12), dp(8), dp(12), dp(8));
                LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(-1, -2);
                warnParams.setMargins(0, dp(8), 0, dp(10));
                vpnBox.addView(warn, warnParams);
                return;
            }
            status.setText("VPN이 꺼져 있어요");
            String[] steps = {"둘 다 가상 랜 앱 설치 — Tailscale 또는 ZeroTier (개인 무료)", "같은 계정 · 같은 네트워크에 들어가기", "앱을 켜고 여기로 돌아오기"};
            for (int k = 0; k < steps.length; k++) {
                TextView step = new TextView(this);
                step.setText((k + 1) + ".  " + steps[k]);
                step.setTextSize(13.0f);
                step.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
                step.setPadding(dp(4), dp(4), dp(4), dp(4));
                vpnBox.addView(step);
            }
            LinearLayout stores = new LinearLayout(this);
            TextView tailscale = dialogButton("Tailscale 받기", Color.rgb(241, 242, 244), Color.rgb(31, 41, 51));
            tailscale.setOnClickListener(view -> openStore("com.tailscale.ipn"));
            stores.addView(tailscale, new LinearLayout.LayoutParams(0, dp(40), 1.0f));
            TextView zerotier = dialogButton("ZeroTier 받기", Color.rgb(241, 242, 244), Color.rgb(31, 41, 51));
            zerotier.setOnClickListener(view -> openStore("com.zerotier.one"));
            LinearLayout.LayoutParams zerotierParams = new LinearLayout.LayoutParams(0, dp(40), 1.0f);
            zerotierParams.setMargins(dp(8), 0, 0, 0);
            stores.addView(zerotier, zerotierParams);
            LinearLayout.LayoutParams storesParams = new LinearLayout.LayoutParams(-1, -2);
            storesParams.setMargins(0, dp(8), 0, dp(10));
            vpnBox.addView(stores, storesParams);
        };
        final Runnable switchTab = () -> {
            paintTabs.run();
            list.removeAllViews();
            boolean vpn = tab[0] == 2;
            vpnBox.setVisibility(vpn ? View.VISIBLE : View.GONE);
            waiting.setVisibility(vpn ? View.GONE : View.VISIBLE);
            address.setHint(vpn ? "친구의 가상 IP (예: 100.64.12.8)" : "IP 직접 입력 (예: 192.168.0.12)");
            if (tab[0] != 0 && search[0] != null) {
                search[0].interrupt();
            }
            if (tab[0] != 1) {
                directLink().stopDiscovery();
            }
            if (tab[0] == 1) {
                searchDirect[0].run();
            } else if (vpn) {
                fillVpn.run();
            } else {
                status.setText("같은 와이파이에서 찾는 중…");
                waiting.setText("찾는 중… 방장이 '방 만들기'를 먼저 켜야 보여요");
                waiting.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
                waiting.setOnClickListener(null);
                again[0].run();
            }
        };
        for (int k = 0; k < 3; k++) {
            final int which = k;
            tabs[k].setOnClickListener(view -> {
                if (tab[0] != which) {
                    tab[0] = which;
                    switchTab.run();
                }
            });
        }
        switchTab.run();
        dialog.setOnDismissListener(d -> {
            if (search[0] != null) {
                search[0].interrupt();
            }
            directLink().stopDiscovery();
        });

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

    private LinearLayout createFastRow(android.app.Dialog menu) {
        String changes = isPsp()
                ? "· 텍스처 변경 검사를 덜 자주 해요\n· GPU에서 화면을 되읽는 작업을 건너뛰어요\n· 블러·빛 번짐 같은 효과는 낮은 해상도로 그려요\n· 곡면 모델을 덜 촘촘하게 그려요\n· 텍스처 비등방성 필터를 꺼요\n게임에 따라 일부 효과(빛 번짐·화면 위 그림)가 빠지거나 거칠어질 수 있어요."
                : isN64()
                ? "· 화면 내용을 CPU 쪽 메모리로 되돌려 복사하는 작업(색 · 깊이)을 꺼요\n· 같은 화면이 반복되면 다시 그리지 않아요\n게임에 따라 일부 효과(화면 속 화면, 사진 찍기, 렌즈 효과 등)가 빠질 수 있어요."
                : isDolphin()
                ? "· 게임기 CPU를 80%로 낮춰 계산을 줄여요\n· 텍스처 변경 검사를 가장 가볍게 해요\n· 디스크 읽기를 기다리지 않아요 (로딩이 빨라져요)\n게임에 따라 게임 속도가 느려지거나 텍스처가 잠깐 깨질 수 있어요."
                : isDreamcast()
                ? "· 사운드 DSP(울림 · 에코 효과) 흉내를 꺼요\n· 늦어질 때만 화면을 한 장씩 건너뛰어 그려요\n게임에 따라 소리 울림이 빠질 수 있어요."
                : "· 반투명 효과를 가장 단순하게 그려요\n· 디더링과 밉맵을 꺼요\n· 그린 화면을 되읽을 때 기다리지 않아요 (아지랑이 · 잔상 같은 효과가 살짝 어긋날 수 있어요)\n· (켬 · 강하게만) PS2 CPU를 75% · 60%로 낮추고 일부 계산을 건너뛰어요\n그림만 가볍게는 게임 속도에 손대지 않아요. 게임에 따라 일부 반투명 효과가 달라 보이거나, 켬 · 강하게에서는 게임이 느려지거나 드물게 멈출 수 있어요.";
        boolean ps2 = "pcee2".equals(this.coreId);
        int level = speedFirstLevel();
        LinearLayout row = createSettingRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", (ps2 ? FAST_SHORT_PS2 : FAST_SHORT)[level],
                view -> showChoiceDialog(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", ps2 ? FAST_NAMES_PS2 : FAST_NAMES, ps2 ? FAST_NOTES_PS2 : FAST_NOTES,
                        level, "켜면 이렇게 바뀌어요\n" + changes + "\n이 게임에만 적용 · 저장하고 그 장면에서 이어져요 (2~3초).",
                        Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용하고 이어하기", choice -> {
                    if (choice == level) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putBoolean(speedFirstKey(), choice >= 1)
                            .putBoolean("fast_light:" + this.saveBaseName, ps2 && choice == 1)
                            .putBoolean("fast_strong:" + this.saveBaseName, ps2 && choice == 3).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
        if (!speedFirst()) {
            ((TextView) row.getChildAt(row.getChildCount() - 1)).setTextColor(Theme.text(Color.rgb(154, 161, 170)));
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

    // "배경": [모든 모드 | ○○ 모드만], the styles, and under the picked one its own options
    // (pastel glass kind, metal 포인트 색). Applied with the button.
    private void showBackgroundDialog(Runnable after) {
        final int color = RoundIconView.COLOR_PALETTE;
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final String mode = this.gameMode;
        final String modeName = consoleName() + " 모드만";
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, RoundIconView.PALETTE, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("배경");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(consoleName() + " 모드");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        titles.addView(sub);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // state[0] scope (0 all modes, 1 this mode), [1] style (-1: follow all modes), [2] glass, [3] trim, [4] neon, [5] space,
        // [6] material, [7] retro
        final int[] state = new int[8];
        final int[] kindCounts = {0, 0, ClearShellView.GLASS_NAMES.length, ClearShellView.TRIM_NAMES.length, ClearShellView.NEON_NAMES.length,
                ClearShellView.SPACE_NAMES.length, ClearShellView.MATERIAL_NAMES.length, ClearShellView.VINTAGE_NAMES.length};
        final String styleOwn = modeShellKey("shell_style", mode);
        java.util.function.IntConsumer load = scope -> {
            state[0] = scope;
            if (scope == 1) {
                state[1] = prefs.contains(styleOwn) ? ClearShellView.normalize(prefs.getInt(styleOwn, ClearShellView.STYLE_PLASTIC)) : -1;
                state[2] = prefs.getInt(modeShellKey("shell_glass", mode), prefs.getInt("shell_glass", 0));
                state[3] = prefs.getInt(modeShellKey("shell_trim", mode), prefs.getInt("shell_trim", 0));
                state[4] = prefs.getInt(modeShellKey("shell_neon", mode), prefs.getInt("shell_neon", 0));
                state[5] = prefs.getInt(modeShellKey("shell_space", mode), prefs.getInt("shell_space", 0));
                state[6] = prefs.getInt(modeShellKey("shell_material", mode), prefs.getInt("shell_material", 0));
                state[7] = prefs.getInt(modeShellKey("shell_vintage", mode), prefs.getInt("shell_vintage", 0));
            } else {
                state[1] = ClearShellView.normalize(prefs.getInt("shell_style", ClearShellView.STYLE_PLASTIC));
                state[2] = prefs.getInt("shell_glass", 0);
                state[3] = prefs.getInt("shell_trim", 0);
                state[4] = prefs.getInt("shell_neon", 0);
                state[5] = prefs.getInt("shell_space", 0);
                state[6] = prefs.getInt("shell_material", 0);
                state[7] = prefs.getInt("shell_vintage", 0);
            }
            for (int k = 6; k < 8; k++) {
                state[k] = Math.max(0, Math.min(kindCounts[k] - 1, state[k]));
            }
            state[5] = Math.max(0, Math.min(ClearShellView.SPACE_NAMES.length - 1, state[5]));
            state[4] = Math.max(0, Math.min(ClearShellView.NEON_NAMES.length - 1, state[4]));
            state[2] = Math.max(0, Math.min(ClearShellView.GLASS_NAMES.length - 1, state[2]));
            state[3] = Math.max(0, Math.min(ClearShellView.TRIM_NAMES.length - 1, state[3]));
        };
        load.accept(shellModeOnly() ? 1 : 0);

        LinearLayout segment = new LinearLayout(this);
        segment.setOrientation(0);
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        android.graphics.drawable.GradientDrawable segmentBackground = new android.graphics.drawable.GradientDrawable();
        segmentBackground.setColor(Theme.bg(Color.rgb(238, 241, 245)));
        segmentBackground.setCornerRadius(dp(12));
        segment.setBackground(segmentBackground);
        TextView[] tabs = {new TextView(this), new TextView(this)};
        String[] tabNames = {"모든 모드", modeName};
        LinearLayout.LayoutParams segmentParams = new LinearLayout.LayoutParams(-1, -2);
        segmentParams.setMargins(0, dp(12), 0, 0);
        panel.addView(segment, segmentParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(1);
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setTextSize(12.0f);
        note.setLineSpacing(0, 1.15f);
        note.setTextColor(Theme.text(Color.rgb(159, 18, 57)));
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable noteBackground = new android.graphics.drawable.GradientDrawable();
        noteBackground.setColor(Theme.bg(Color.rgb(255, 241, 244)));
        noteBackground.setCornerRadius(dp(12));
        note.setBackground(noteBackground);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.setMargins(0, dp(12), 0, 0);
        panel.addView(note, noteParams);

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            for (int k = 0; k < 2; k++) {
                boolean on = state[0] == k;
                android.graphics.drawable.GradientDrawable tab = new android.graphics.drawable.GradientDrawable();
                tab.setColor(on ? Theme.bg(Color.WHITE) : 0);
                tab.setCornerRadius(dp(10));
                tabs[k].setBackground(tab);
                tabs[k].setTextColor(Theme.text(on ? Color.rgb(31, 41, 51) : Color.rgb(100, 116, 139)));
            }
            list.removeAllViews();
            if (state[0] == 1) {
                int all = ClearShellView.normalize(prefs.getInt("shell_style", ClearShellView.STYLE_PLASTIC));
                String allName = ClearShellView.STYLE_NAMES[all] + (all == ClearShellView.STYLE_PASTEL ? " · " + ClearShellView.GLASS_NAMES[Math.max(0, Math.min(2, prefs.getInt("shell_glass", 0)))] : "");
                list.addView(backgroundOption("모든 모드 설정 따르기", "지금 " + allName, state[1] == -1, color, () -> {
                    state[1] = -1;
                    render[0].run();
                }), optionParams());
            }
            for (int style : ClearShellView.CHOICES) {
                list.addView(backgroundOption(ClearShellView.STYLE_NAMES[style], ClearShellView.STYLE_NOTES[style], state[1] == style, color, () -> {
                    state[1] = style;
                    render[0].run();
                }), optionParams());
                if (state[1] == style && style == ClearShellView.STYLE_PASTEL) {
                    list.addView(backgroundChips("글라스 종류", ClearShellView.GLASS_NAMES, state[2], color, k -> {
                        state[2] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_NEON) {
                    list.addView(backgroundChips("네온 종류", ClearShellView.NEON_NAMES, state[4], color, k -> {
                        state[4] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_MATERIAL) {
                    list.addView(backgroundChips("재질 종류", ClearShellView.MATERIAL_NAMES, state[6], color, k -> {
                        state[6] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_VINTAGE) {
                    list.addView(backgroundChips("레트로 종류", ClearShellView.VINTAGE_NAMES, state[7], color, k -> {
                        state[7] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_SPACE) {
                    list.addView(backgroundChips("우주 종류", ClearShellView.SPACE_NAMES, state[5], color, k -> {
                        state[5] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_METAL) {
                    list.addView(backgroundChips("포인트 색", ClearShellView.TRIM_NAMES, state[3], color, k -> {
                        state[3] = k;
                        render[0].run();
                    }), optionParams());
                } else if (state[1] == style && style == ClearShellView.STYLE_PHOTO) {
                    TextView photoNote = new TextView(this);
                    photoNote.setText("사진은 원래처럼 모드마다 따로 저장돼요 · 적용을 누르면 사진 편집 창이 열려요");
                    photoNote.setTextSize(11.5f);
                    photoNote.setTextColor(Theme.text(Color.rgb(71, 85, 105)));
                    photoNote.setPadding(dp(14), dp(4), dp(8), dp(4));
                    list.addView(photoNote, optionParams());
                }
            }
            note.setText(state[0] == 1
                    ? consoleName() + " 모드의 모든 게임에 적용돼요. 다른 모드는 그대로예요. 버튼 배치는 그대로예요."
                    : "모든 모드에 적용돼요. \"모드만\"으로 따로 정한 모드는 그대로예요. 버튼 배치는 그대로예요.");
        };
        for (int k = 0; k < 2; k++) {
            final int scope = k;
            tabs[k].setText(tabNames[k]);
            tabs[k].setTextSize(13.0f);
            tabs[k].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tabs[k].setGravity(17);
            tabs[k].setPadding(0, dp(8), 0, dp(8));
            tabs[k].setOnClickListener(view -> {
                load.accept(scope);
                render[0].run();
            });
            segment.addView(tabs[k], new LinearLayout.LayoutParams(0, -2, 1.0f));
        }
        render[0].run();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        TextView cancel = dialogButton("취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1.0f));
        TextView apply = dialogButton("적용", color, Color.WHITE);
        apply.setOnClickListener(view -> {
            dialog.dismiss();
            boolean own = state[0] == 1;
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (own && state[1] == -1) {
                // Back to the setting for all modes.
                edit.remove(styleOwn).remove(modeShellKey("shell_glass", mode)).remove(modeShellKey("shell_trim", mode))
                        .remove(modeShellKey("shell_neon", mode)).remove(modeShellKey("shell_space", mode))
                        .remove(modeShellKey("shell_material", mode)).remove(modeShellKey("shell_vintage", mode)).apply();
            } else if (state[1] == ClearShellView.STYLE_PHOTO) {
                // The editor records the style once a photo is applied.
                edit.apply();
                openBackgroundEditor(own ? styleOwn : "shell_style");
                return;
            } else {
                edit.putInt(own ? styleOwn : "shell_style", state[1]);
                edit.putInt(own ? modeShellKey("shell_glass", mode) : "shell_glass", state[2]);
                edit.putInt(own ? modeShellKey("shell_trim", mode) : "shell_trim", state[3]);
                edit.putInt(own ? modeShellKey("shell_neon", mode) : "shell_neon", state[4]);
                edit.putInt(own ? modeShellKey("shell_space", mode) : "shell_space", state[5]);
                edit.putInt(own ? modeShellKey("shell_material", mode) : "shell_material", state[6]);
                edit.putInt(own ? modeShellKey("shell_vintage", mode) : "shell_vintage", state[7]);
                edit.apply();
            }
            rebuildContent();
            after.run();
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        applyParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(14), 0, 0);
        panel.addView(buttons, buttonsParams);

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

    private LinearLayout.LayoutParams optionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(7), 0, 0);
        return params;
    }

    // One radio row of the background list.
    private LinearLayout backgroundOption(String name, String detail, boolean on, int color, Runnable onPick) {
        LinearLayout option = new LinearLayout(this);
        option.setOrientation(0);
        option.setGravity(16);
        option.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.GradientDrawable optionBackground = new android.graphics.drawable.GradientDrawable();
        optionBackground.setColor(Theme.bg(on ? Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)) : Color.rgb(246, 247, 249)));
        optionBackground.setCornerRadius(dp(14));
        option.setBackground(optionBackground);
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(dp(2), Theme.line(on ? color : Color.rgb(201, 206, 214)));
        ring.setColor(Theme.bg(Color.WHITE));
        dot.setBackground(on ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring, innerDot(color)}) : ring);
        option.addView(dot, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(14.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        texts.addView(title);
        TextView note = new TextView(this);
        note.setText(detail);
        note.setTextSize(11.5f);
        note.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        texts.addView(note);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(12), 0, 0, 0);
        option.addView(texts, textsParams);
        option.setOnClickListener(view -> onPick.run());
        return option;
    }

    // A labelled row of chips under the picked background (glass kind, 포인트 색).
    private LinearLayout backgroundChips(String label, String[] names, int selected, int color, java.util.function.IntConsumer onPick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(1);
        box.setPadding(dp(14), dp(2), 0, dp(2));
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(12.0f);
        caption.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        caption.setTextColor(Theme.text(Color.rgb(51, 65, 85)));
        box.addView(caption);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        for (int k = 0; k < names.length; k++) {
            final int index = k;
            boolean on = k == selected;
            TextView chip = new TextView(this);
            chip.setText(names[k]);
            chip.setTextSize(names.length > 3 ? 12.0f : 13.0f);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setGravity(17);
            chip.setPadding(0, dp(8), 0, dp(8));
            chip.setTextColor(Theme.text(on ? color : Color.rgb(71, 85, 105)));
            android.graphics.drawable.GradientDrawable chipBackground = new android.graphics.drawable.GradientDrawable();
            chipBackground.setColor(Theme.bg(on ? Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)) : Color.rgb(246, 247, 249)));
            chipBackground.setStroke(dp(on ? 2 : 1), Theme.line(on ? color : Color.rgb(226, 230, 236)));
            chipBackground.setCornerRadius(dp(12));
            chip.setBackground(chipBackground);
            chip.setOnClickListener(view -> onPick.accept(index));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            chipParams.setMargins(k == 0 ? 0 : dp(6), dp(6), 0, 0);
            row.addView(chip, chipParams);
        }
        box.addView(row);
        return box;
    }

    // A pause-menu row: icon, name, the current value and an arrow.
    private LinearLayout createSettingRow(int icon, int color, String name, String current, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        row.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelParams.setMargins(dp(12), 0, 0, 0);
        row.addView(label, labelParams);
        TextView value = new TextView(this);
        value.setText(current + "  ›");
        value.setTextSize(13.5f);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setTextColor(Theme.text(color));
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
        background.setColor(Theme.bg(Color.WHITE));
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
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        TextView game = new TextView(this);
        game.setText(this.gameTitle);
        game.setTextSize(12.0f);
        game.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
                optionBackground.setColor(Theme.bg(on ? selectedFill : Color.rgb(246, 247, 249)));
                optionBackground.setCornerRadius(dp(14));
                options[k].setBackground(optionBackground);
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setStroke(dp(2), Theme.line(on ? color : Color.rgb(201, 206, 214)));
                dot.setColor(Theme.bg(Color.WHITE));
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
            name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
            texts.addView(name);
            TextView note = new TextView(this);
            note.setText(notes[k]);
            note.setTextSize(11.5f);
            note.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
        warning.setTextColor(Theme.text(noteColor));
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable warningBackground = new android.graphics.drawable.GradientDrawable();
        warningBackground.setColor(Theme.bg(noteBackground));
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

        // Long lists (the 7 backgrounds) scroll on a landscape screen.
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

    // The filled centre of a selected radio dot.
    private android.graphics.drawable.Drawable innerDot(int color) {
        android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable();
        inner.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        inner.setColor(Theme.bg(color));
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(inner, dp(5));
        return inset;
    }

    private TextView dialogButton(String text, int background, int textColor) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(15.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(Theme.text(textColor));
        button.setGravity(17);
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(Theme.bg(background));
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
        background.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        background.setCornerRadius(dp(16));
        tile.setBackground(background);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        tile.addView(iconView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13.0f);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        text.setGravity(1);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(0, dp(8), 0, 0);
        tile.addView(text, textParams);
        if (detail != null) {
            TextView sub = new TextView(this);
            sub.setText(detail);
            sub.setTextSize(10.5f);
            sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
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
        if (index == 1 && !isMame139()) {
            tile.setOnLongClickListener(view -> {
                showSaveChooser();
                dialog.dismiss();
                return true;
            });
        }
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
        for (int n = 1; n <= FIXED_SLOTS; n++) {
            if (slotFile(n).isFile()) modified = Math.max(modified, slotFile(n).lastModified());
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
        if (MENU_SHOT.equals(item) || MENU_SHOT_FULL.equals(item)) {
            final boolean full = MENU_SHOT_FULL.equals(item);
            // After the menu is gone and a frame has been drawn.
            getWindow().getDecorView().postDelayed(() -> takeScreenshot(full), 350);
            return;
        }
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
        // Two big tiles in the pause menu's style: carry on, or save this moment and leave.
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("게임을 나갈까요?");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        title.setGravity(1);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView game = new TextView(this);
        game.setText(this.gameTitle);
        game.setTextSize(12.0f);
        game.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        game.setGravity(1);
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(game, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(0);
        tiles.addView(createBackExitTile(RoundIconView.PLAY, Color.rgb(22, 185, 207), Color.rgb(241, 251, 252), "계속하기", "게임으로\n돌아가기",
                view -> dialog.cancel()), new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        exitParams.setMargins(dp(10), 0, 0, 0);
        tiles.addView(createBackExitTile(RoundIconView.EXIT, RoundIconView.COLOR_EXIT, Color.rgb(254, 243, 243), "나가기", "지금 장면 저장 후\nROM 목록으로",
                view -> {
                    // Flag the exit before the dismiss listener would resume the game.
                    this.menuExiting = true;
                    dialog.dismiss();
                    saveAndExit();
                }), exitParams);
        LinearLayout.LayoutParams tilesParams = new LinearLayout.LayoutParams(-1, -2);
        tilesParams.setMargins(0, dp(14), 0, 0);
        panel.addView(tiles, tilesParams);
        dialog.setContentView(panel);
        dialog.setOnCancelListener(d -> resumeEmulation());
        dialog.setOnDismissListener(d -> resumeUnlessExiting());
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(360));
            window.setLayout(width, -2);
        }
        dialog.show();
    }

    private LinearLayout createBackExitTile(int icon, int color, int fill, String label, String detail, View.OnClickListener onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(1);
        tile.setGravity(1);
        tile.setPadding(dp(8), dp(18), dp(8), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(fill));
        background.setCornerRadius(dp(18));
        tile.setBackground(background);
        RoundIconView iconView = new RoundIconView(this, icon, color);
        tile.addView(iconView, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(15.0f);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        text.setGravity(1);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(0, dp(10), 0, 0);
        tile.addView(text, textParams);
        TextView sub = new TextView(this);
        sub.setText(detail);
        sub.setTextSize(11.5f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        sub.setGravity(1);
        sub.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.setMargins(0, dp(3), 0, 0);
        tile.addView(sub, subParams);
        tile.setClickable(true);
        tile.setContentDescription(label);
        tile.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                iconView.setPressedLook(true);
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                iconView.setPressedLook(false);
            }
            return false;
        });
        tile.setOnClickListener(onClick);
        return tile;
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

    // The game could not start: a card in the app's style with a plain explanation first
    // and the engine's own words underneath, and one way out (back to the list).
    private void showFatalError(String str) {
        String raw = str == null ? "" : str;
        StringBuilder detail = new StringBuilder();
        String summary = null;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.contains("nvmem is DISABLED")) continue;
            if (trimmed.startsWith("ROM load failure: selected file could not be loaded")) {
                if (summary == null) summary = "엔진이 이 파일을 열지 못했어요.";
                continue;
            }
            if (trimmed.contains("Unknown game")) {
                summary = isNaomi() ? "Flycast가 이 ZIP의 이름으로 세트를 찾지 못했어요. 파일 이름이 MAME 세트명(예: mvsc2.zip)이어야 해요. 목록에서 게임을 길게 눌러 \"ROM 검사\"를 하면 내용을 보고 이름을 맞춰 줘요."
                        : "엔진이 이 게임을 알아보지 못했어요.";
            } else if (trimmed.toLowerCase(Locale.US).contains("bios")) {
                summary = isNaomi() ? "기판 BIOS가 없거나 맞지 않아요. 목록의 \"기판 BIOS\"에서 naomi.zip · naomi2.zip · awbios.zip을 등록해 주세요." : "BIOS가 없거나 맞지 않아요.";
            } else if (trimmed.toLowerCase(Locale.US).contains("gdrom") || trimmed.toLowerCase(Locale.US).contains(".chd")) {
                summary = "GD-ROM 디스크 이미지(.chd)를 찾지 못했어요. ZIP과 같은 폴더에 넣어 주세요.";
            }
            if (detail.length() > 0) detail.append('\n');
            detail.append(trimmed);
        }
        if (summary == null) {
            summary = detail.length() > 0 ? "게임을 시작하지 못했어요." : raw;
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        dialog.setCancelable(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.addView(new RoundIconView(this, RoundIconView.WARN, Color.rgb(225, 29, 72)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("게임을 열지 못했어요");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titleParams.setMargins(dp(12), 0, 0, 0);
        header.addView(title, titleParams);
        panel.addView(header);
        TextView text = new TextView(this);
        text.setText(summary);
        text.setTextSize(14.0f);
        text.setLineSpacing(0, 1.25f);
        text.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
        text.setPadding(0, dp(14), 0, 0);
        panel.addView(text);
        if (detail.length() > 0) {
            TextView raws = new TextView(this);
            raws.setText(detail);
            raws.setTextSize(11.0f);
            raws.setTypeface(android.graphics.Typeface.MONOSPACE);
            raws.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            raws.setPadding(dp(12), dp(10), dp(12), dp(10));
            raws.setTextIsSelectable(true);
            android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
            box.setColor(Theme.bg(Color.rgb(246, 247, 249)));
            box.setCornerRadius(dp(12));
            raws.setBackground(box);
            LinearLayout.LayoutParams rawParams = new LinearLayout.LayoutParams(-1, -2);
            rawParams.setMargins(0, dp(12), 0, 0);
            panel.addView(raws, rawParams);
        }
        TextView back = dialogButton("목록으로 돌아가기", Color.rgb(225, 29, 72), Color.WHITE);
        back.setOnClickListener(view -> {
            dialog.dismiss();
            finish();
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, dp(48));
        backParams.setMargins(0, dp(16), 0, 0);
        panel.addView(back, backParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(380)), -2);
        }
        try {
            dialog.show();
        } catch (RuntimeException e) {
            finish();
        }
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

    private boolean isN3ds() {
        return "citra".equals(this.coreId);
    }

    private boolean isWiiU() {
        return "cemu".equals(this.coreId);
    }

    // ---- 모션 (자이로): Wii remotes, the Wii U GamePad and GBA / Game Boy sensor carts ----
    private MotionInput motionInput;
    private Boolean gbaMotionCart;

    private MotionInput motionInput() {
        if (this.motionInput == null) {
            this.motionInput = new MotionInput(this);
        }
        return this.motionInput;
    }

    private boolean motionSupported() {
        return (isDolphin() && isWiiGame()) || isWiiU() || isGbaMotionCart();
    }

    private boolean motionOn() {
        return MainActivity.sharedSettings(this).getBoolean("motion:" + this.saveBaseName, isGbaMotionCart());
    }

    private boolean motionActive() {
        return motionSupported() && motionOn() && motionInput().available();
    }

    private static final int[] MOTION_SENS = {50, 75, 100, 150, 200};
    private static final String[] MOTION_SENS_NAMES = {"약하게 · 50%", "조금 약하게 · 75%", "보통 · 100%", "세게 · 150%", "아주 세게 · 200%"};

    private int motionSensitivity() {
        int value = MainActivity.sharedSettings(this).getInt("motion_sens", 100);
        for (int k : MOTION_SENS) {
            if (k == value) return value;
        }
        return 100;
    }

    private void startMotionIfOn() {
        if (!motionActive() || this.nativeHandle == 0 || !this.activityResumed) {
            return;
        }
        MotionInput input = motionInput();
        input.setSensitivity(motionSensitivity() / 100f);
        input.setUpright(isWiiU());
        input.recenter();
        input.start();
    }

    // GBA carts with a tilt or turn sensor (WarioWare Twisted!, Koro Koro Puzzle, Yoshi
    // Topsy-Turvy) by their game code, and Game Boy MBC7 carts (Kirby Tilt 'n' Tumble) by
    // their cartridge type; read from a zip's first entry too.
    private boolean isGbaMotionCart() {
        if (this.gbaMotionCart != null) {
            return this.gbaMotionCart;
        }
        boolean motion = false;
        if ("gba".equals(this.coreId) && this.romPath != null) {
            byte[] head = new byte[0x150];
            int got = 0;
            try {
                java.io.InputStream in;
                java.util.zip.ZipFile zip = null;
                if (this.romPath.toLowerCase(Locale.US).endsWith(".zip")) {
                    zip = new java.util.zip.ZipFile(this.romPath);
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                    java.util.zip.ZipEntry entry = null;
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry next = entries.nextElement();
                        if (!next.isDirectory()) {
                            entry = next;
                            break;
                        }
                    }
                    in = entry == null ? null : zip.getInputStream(entry);
                } else {
                    in = new java.io.FileInputStream(this.romPath);
                }
                if (in != null) {
                    try (java.io.InputStream stream = in) {
                        while (got < head.length) {
                            int n = stream.read(head, got, head.length - got);
                            if (n < 0) break;
                            got += n;
                        }
                    }
                }
                if (zip != null) {
                    zip.close();
                }
            } catch (IOException | RuntimeException e) {
                got = 0;
            }
            if (got >= 0x150) {
                if (isGameBoy()) {
                    motion = (head[0x147] & 0xFF) == 0x22;
                } else {
                    String code = new String(head, 0xAC, 3, java.nio.charset.StandardCharsets.ISO_8859_1);
                    motion = code.equals("RZW") || code.equals("KHP") || code.equals("KYG");
                }
            }
        }
        this.gbaMotionCart = motion;
        return motion;
    }

    // On the picture's top left while motion is on: a badge and the "가운데로" button.
    private void addMotionBadges(FrameLayout screenFrame) {
        if (!motionActive()) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView badge = new TextView(this);
        badge.setText("↻ 모션 켜짐");
        badge.setTextSize(11.0f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackground(roundRect(Color.argb(225, 13, 148, 136), dp(11)));
        row.addView(badge);
        TextView center = new TextView(this);
        center.setText("⊙ 가운데로");
        center.setTextSize(11.0f);
        center.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        center.setTextColor(Theme.text(Color.rgb(229, 231, 235)));
        center.setPadding(dp(9), dp(3), dp(9), dp(3));
        center.setBackground(roundRect(Color.argb(200, 17, 24, 39), dp(11)));
        center.setOnClickListener(view -> {
            motionInput().recenter();
            Toast.makeText(this, "지금 자세를 가운데로 맞췄어요", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams centerParams = new LinearLayout.LayoutParams(-2, -2);
        centerParams.setMargins(dp(6), 0, 0, 0);
        row.addView(center, centerParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        params.setMargins(dp(8), dp(8), 0, 0);
        screenFrame.addView(row, params);
    }

    // Wii / Wii U, the first time a game starts: whether to steer by tilting the phone.
    private void showMotionPrompt() {
        String what = isWiiU() ? "휴대폰을 게임패드처럼 기울이면 조준 · 모션 조작이 돼요." : "휴대폰을 리모컨처럼 기울이면 기울이기가, 흔들면 흔들기가 돼요. 흔들기 버튼도 그대로 써요.";
        new AppDialog.Builder(this).setTitle("휴대폰을 기울여서 조작할까요?")
                .setMessage(what + "\n\n편하게 잡은 자세에서 화면의 '가운데로'를 누르면 그 자세가 똑바로가 돼요. 언제든 일시정지 › 조작에서 끌 수 있어요."
                        + (isWiiU() ? "\n\n켜면 게임이 다시 켜져요 (Wii U는 타이틀부터)." : "\n\n켜면 지금 장면을 저장하고 다시 켜서 이어져요."))
                .setNegativeButton("나중에", null)
                .setPositiveButton("모션 켜기", (d, w) -> setMotionOn(true, null)).show();
    }

    private void setMotionOn(boolean on, android.app.Dialog menu) {
        MainActivity.sharedSettings(this).edit().putBoolean("motion:" + this.saveBaseName, on).commit();
        if (menu != null) {
            menu.dismiss();
        }
        relaunchGame();
    }

    private void addMotionRows(LinearLayout panel, android.app.Dialog dialog, Runnable refresh) {
        if (!motionSupported()) {
            return;
        }
        int teal = Color.rgb(13, 148, 136);
        if (!motionInput().available()) {
            panel.addView(createSettingRow(RoundIconView.ROTATE, teal, "모션 (자이로)", "이 휴대폰엔 센서가 없어요", view -> { }), tabRowParams(panel));
            return;
        }
        boolean on = motionOn();
        panel.addView(createSettingRow(RoundIconView.ROTATE, teal, "모션 (자이로)", on ? "켜짐" : "꺼짐",
                view -> showChoiceDialog(RoundIconView.ROTATE, teal, "모션 (자이로)", new String[]{"꺼짐", "켜짐"},
                        new String[]{"화면 버튼으로만 조작해요", "휴대폰을 기울이고 흔들어서 조작해요 · 버튼도 그대로"}, on ? 1 : 0,
                        "이 게임에만 적용돼요. " + (isWiiU() ? "바꾸면 게임이 다시 켜지고, Wii U는 이어하기가 없어서 타이틀부터 시작해요." : "지금 장면을 저장하고 다시 켜서 이어져요."),
                        Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용", choice -> {
                    if ((choice == 1) == on) {
                        return;
                    }
                    setMotionOn(choice == 1, dialog);
                })), tabRowParams(panel));
        if (!on) {
            return;
        }
        int sens = motionSensitivity();
        int index = java.util.Arrays.binarySearch(MOTION_SENS, sens);
        panel.addView(createSettingRow(RoundIconView.STICK, teal, "모션 감도", MOTION_SENS_NAMES[index],
                view -> showChoiceDialog(RoundIconView.STICK, teal, "모션 감도", MOTION_SENS_NAMES,
                        new String[]{"크게 기울여야 움직여요", "조금 둔하게", "기본", "조금 예민하게", "조금만 기울여도 크게 움직여요"}, index,
                        "모든 게임에 바로 적용돼요.", Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용", choice -> {
                    MainActivity.sharedSettings(this).edit().putInt("motion_sens", MOTION_SENS[choice]).apply();
                    motionInput().setSensitivity(MOTION_SENS[choice] / 100f);
                    refresh.run();
                })), tabRowParams(panel));
        panel.addView(createSettingRow(RoundIconView.MOVE, teal, "가운데로 맞추기", "지금 자세로", view -> {
            motionInput().recenter();
            Toast.makeText(this, "지금 자세를 가운데로 맞췄어요", Toast.LENGTH_SHORT).show();
        }), tabRowParams(panel));
    }

    // ---- Wii U: the TV picture and the GamePad's ----
    // Portrait: 0 the TV over the GamePad, 1 TV only, 2 GamePad only. Landscape: 0 the TV with
    // the GamePad in a small window (WiiuPadWindowView), 1 TV only, 2 GamePad only.
    private static final String[] WIIU_VIEW_PORTRAIT = {"위아래", "TV만", "게임패드만"};
    private static final String[] WIIU_VIEW_LANDSCAPE = {"작은 창", "TV만", "게임패드만"};
    private WiiuPadWindowView wiiuPadWindow;

    private int wiiuView() {
        int value = MainActivity.sharedSettings(this).getInt(this.landscapeMode ? "wiiu_view_l" : "wiiu_view_p", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private void setWiiuView(int view) {
        MainActivity.sharedSettings(this).edit().putInt(this.landscapeMode ? "wiiu_view_l" : "wiiu_view_p", view).apply();
        if (this.landscapeMode) {
            if (this.wiiuPadWindow != null) {
                this.wiiuPadWindow.setVisibility(view == 0 ? View.VISIBLE : View.GONE);
            }
            applyWiiuLayout();
        } else {
            // The screen's shape changes with it (two pictures tall or one).
            applyWiiuLayout();
            rebuildContent();
        }
    }

    private String wiiuViewName() {
        return (this.landscapeMode ? WIIU_VIEW_LANDSCAPE : WIIU_VIEW_PORTRAIT)[wiiuView()];
    }

    // Tells the core where to draw both pictures, as fractions of its output.
    private void applyWiiuLayout() {
        if (!isWiiU()) {
            return;
        }
        float[] none = {0f, 0f, 0f, 0f};
        float[] whole = {0f, 0f, 1f, 1f};
        int view = wiiuView();
        if (view == 1) {
            NativeGba.setWiiuLayout(1, whole, none);
        } else if (view == 2) {
            NativeGba.setWiiuLayout(1, none, whole);
        } else if (!this.landscapeMode) {
            NativeGba.setWiiuLayout(2, new float[]{0f, 0f, 1f, 0.5f}, new float[]{0f, 0.5f, 1f, 0.5f});
        } else {
            NativeGba.setWiiuLayout(1, whole, wiiuPadRect());
        }
    }

    // The window's rectangle over the picture, from where the layout editor left it.
    private float[] wiiuPadRect() {
        WiiuPadWindowView window = this.wiiuPadWindow;
        SurfaceView surface = this.surfaceView;
        if (window == null || surface == null || window.getWidth() <= 0 || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            return new float[]{0.68f, 0.02f, 0.30f, 0.30f};
        }
        float width = window.getWidth() * window.getScaleX();
        float height = window.getHeight() * window.getScaleY();
        float left = window.getLeft() + window.getTranslationX() + window.getWidth() * (1f - window.getScaleX()) / 2f - (surface.getLeft() + surface.getTranslationX());
        float top = window.getTop() + window.getTranslationY() + window.getHeight() * (1f - window.getScaleY()) / 2f - (surface.getTop() + surface.getTranslationY());
        float w = Math.max(0.05f, Math.min(1f, width / surface.getWidth()));
        float h = Math.max(0.05f, Math.min(1f, height / surface.getHeight()));
        float x = Math.max(0f, Math.min(1f - w, left / surface.getWidth()));
        float y = Math.max(0f, Math.min(1f - h, top / surface.getHeight()));
        return new float[]{x, y, w, h};
    }

    // Landscape: the GamePad window in the top right corner of the picture, 30% of its width.
    private void addWiiuPadWindow(FrameLayout screenFrame) {
        this.wiiuPadWindow = null;
        if (!isWiiU()) {
            return;
        }
        WiiuPadWindowView window = new WiiuPadWindowView(this, this::onWiiuPadTouch);
        window.setVisibility(wiiuView() == 0 ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(120), dp(68), Gravity.TOP | Gravity.END);
        screenFrame.addView(window, params);
        this.wiiuPadWindow = window;
        screenFrame.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.round((r - l) * 0.30f);
            int height = Math.round(width * 9f / 16f);
            int margin = Math.round((r - l) * 0.012f);
            FrameLayout.LayoutParams now = (FrameLayout.LayoutParams) window.getLayoutParams();
            if (width > 0 && (now.width != width || now.height != height || now.topMargin != margin)) {
                now.width = width;
                now.height = height;
                now.setMargins(0, margin, margin, 0);
                window.post(() -> {
                    window.setLayoutParams(now);
                    window.post(this::applyWiiuLayout);
                });
            } else {
                window.post(this::applyWiiuLayout);
            }
        });
    }

    // A touch on the window is a touch on the GamePad's screen, at that place in the picture.
    private void onWiiuPadTouch(WiiuPadWindowView window, float x, float y, boolean pressed) {
        long handle = this.nativeHandle;
        SurfaceView surface = this.surfaceView;
        if (handle == 0 || surface == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            return;
        }
        float pivotX = window.getWidth() / 2f;
        float pivotY = window.getHeight() / 2f;
        float parentX = window.getLeft() + window.getTranslationX() + pivotX + (x - pivotX) * window.getScaleX();
        float parentY = window.getTop() + window.getTranslationY() + pivotY + (y - pivotY) * window.getScaleY();
        float fx = Math.max(0f, Math.min(1f, (parentX - surface.getLeft() - surface.getTranslationX()) / surface.getWidth()));
        float fy = Math.max(0f, Math.min(1f, (parentY - surface.getTop() - surface.getTranslationY()) / surface.getHeight()));
        NativeGba.setPointer(handle, Math.round((fx * 2f - 1f) * 32767f), Math.round((fy * 2f - 1f) * 32767f), pressed);
    }

    // "화면 전환": the next of the three views.
    private TextView createWiiuSwitchButton() {
        TextView button = new TextView(this);
        button.setText("화면 전환");
        button.setTextSize(11.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(Theme.text(Color.rgb(203, 213, 225)));
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(Color.argb(210, 55, 65, 81), dp(15)));
        button.setOnClickListener(view -> {
            int next = (wiiuView() + 1) % 3;
            setWiiuView(next);
            Toast.makeText(this, "화면: " + (this.landscapeMode ? WIIU_VIEW_LANDSCAPE : WIIU_VIEW_PORTRAIT)[next], Toast.LENGTH_SHORT).show();
        });
        return button;
    }

    // Portrait, under the two pictures: ZL L and R ZR along the top, the left stick over the
    // D-pad, the diamond over the right stick, − + and the stick clicks between them, as on
    // the GamePad.
    private void addWiiuControls(FrameLayout frameLayout) {
        addButton(frameLayout, "ZL", kL2, Gravity.TOP | Gravity.START, dp(14), dp(8), dp(52), dp(40), 2);
        addButton(frameLayout, "L", 512, Gravity.TOP | Gravity.START, dp(72), dp(8), dp(52), dp(40), 2);
        addButton(frameLayout, "R", 256, Gravity.TOP | Gravity.END, dp(72), dp(8), dp(52), dp(40), 2);
        addButton(frameLayout, "ZR", kR2, Gravity.TOP | Gravity.END, dp(14), dp(8), dp(52), dp(40), 2);
        addFastForwardButton(frameLayout);
        FrameLayout.LayoutParams leftStick = new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.BOTTOM | Gravity.START);
        leftStick.setMargins(dp(24), 0, 0, dp(200));
        frameLayout.addView(leftStick(), leftStick);
        FrameLayout.LayoutParams pad = new FrameLayout.LayoutParams(dp(108), dp(108), Gravity.BOTTOM | Gravity.START);
        pad.setMargins(dp(16), 0, 0, dp(66));
        frameLayout.addView(createDirectionControl(), pad);
        addButton(frameLayout, "X", 1024, Gravity.BOTTOM | Gravity.END, dp(76), dp(254), dp(48), dp(48), TouchButton.STYLE_DARK);
        addButton(frameLayout, "A", 1, Gravity.BOTTOM | Gravity.END, dp(28), dp(208), dp(48), dp(48), TouchButton.STYLE_DARK);
        addButton(frameLayout, "Y", 2048, Gravity.BOTTOM | Gravity.END, dp(124), dp(208), dp(48), dp(48), TouchButton.STYLE_DARK);
        addButton(frameLayout, "B", 2, Gravity.BOTTOM | Gravity.END, dp(76), dp(162), dp(48), dp(48), TouchButton.STYLE_DARK);
        FrameLayout.LayoutParams rightStick = new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.BOTTOM | Gravity.END);
        rightStick.setMargins(0, 0, dp(32), dp(52));
        frameLayout.addView(rightStick(), rightStick);
        for (int k = 0; k < 2; k++) {
            TouchButton key = new TouchButton(this, k == 0 ? "−" : "+", k == 0 ? 4 : 8, this::setButtonPressed);
            FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(dp(44), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            keyParams.setMargins(0, 0, 0, dp(96));
            key.setTranslationX(dp(k == 0 ? -28 : 28));
            frameLayout.addView(key, keyParams);
            TouchButton click = new TouchButton(this, k == 0 ? "L3" : "R3", k == 0 ? kL3 : kR3, this::setButtonPressed, TouchButton.STYLE_DARK);
            FrameLayout.LayoutParams clickParams = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            clickParams.setMargins(0, 0, 0, dp(140));
            click.setTranslationX(dp(k == 0 ? -28 : 28));
            frameLayout.addView(click, clickParams);
        }
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(dp(84), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        switchParams.setMargins(0, 0, 0, dp(20));
        frameLayout.addView(createWiiuSwitchButton(), switchParams);
    }

    // Landscape, in the columns beside the TV picture: ZL L / R ZR at the top, the left stick
    // and the D-pad on the left, the diamond and the right stick on the right.
    private void addWiiuLandscapeControls(FrameLayout frameLayout) {
        addButton(frameLayout, "ZL", kL2, Gravity.TOP | Gravity.START, dp(14), dp(62), dp(50), dp(36), 2);
        addButton(frameLayout, "L", 512, Gravity.TOP | Gravity.START, dp(70), dp(62), dp(50), dp(36), 2);
        addButton(frameLayout, "R", 256, Gravity.TOP | Gravity.END, dp(70), dp(62), dp(50), dp(36), 2);
        addButton(frameLayout, "ZR", kR2, Gravity.TOP | Gravity.END, dp(14), dp(62), dp(50), dp(36), 2);
        FrameLayout.LayoutParams leftStick = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.START);
        leftStick.setMargins(dp(16), dp(112), 0, 0);
        frameLayout.addView(leftStick(), leftStick);
        FrameLayout.LayoutParams pad = new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.BOTTOM | Gravity.START);
        pad.setMargins(dp(60), 0, 0, dp(52));
        frameLayout.addView(createDirectionControl(), pad);
        addButton(frameLayout, "X", 1024, Gravity.TOP | Gravity.END, dp(62), dp(108), dp(44), dp(44), TouchButton.STYLE_DARK);
        addButton(frameLayout, "A", 1, Gravity.TOP | Gravity.END, dp(18), dp(146), dp(44), dp(44), TouchButton.STYLE_DARK);
        addButton(frameLayout, "Y", 2048, Gravity.TOP | Gravity.END, dp(106), dp(146), dp(44), dp(44), TouchButton.STYLE_DARK);
        addButton(frameLayout, "B", 2, Gravity.TOP | Gravity.END, dp(62), dp(184), dp(44), dp(44), TouchButton.STYLE_DARK);
        FrameLayout.LayoutParams rightStick = new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.BOTTOM | Gravity.END);
        rightStick.setMargins(0, 0, dp(100), dp(54));
        frameLayout.addView(rightStick(), rightStick);
        addButton(frameLayout, "−", 4, Gravity.BOTTOM | Gravity.START, dp(14), dp(12), dp(44), dp(30));
        addButton(frameLayout, "+", 8, Gravity.BOTTOM | Gravity.END, dp(14), dp(12), dp(44), dp(30));
        addButton(frameLayout, "L3", kL3, Gravity.TOP | Gravity.START, dp(118), dp(112), dp(36), dp(36), TouchButton.STYLE_DARK);
        addButton(frameLayout, "R3", kR3, Gravity.BOTTOM | Gravity.END, dp(18), dp(100), dp(36), dp(36), TouchButton.STYLE_DARK);
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(dp(80), dp(30), Gravity.BOTTOM | Gravity.START);
        switchParams.setMargins(dp(66), 0, 0, dp(12));
        frameLayout.addView(createWiiuSwitchButton(), switchParams);
    }

    // DS, 3DS and Wii U: two screens stacked in portrait, the lower one touched with a finger
    // (on the Wii U the GamePad's).
    private boolean isDualScreen() {
        return isNds() || isN3ds() || isWiiU();
    }

    // DS: a finger on the game screen is the stylus. Positions go to the core in libretro
    // pointer units across the whole screen view; melonDS DS maps them onto the lower screen.
    // The DS touch screen, and the Wii remote's pointer (Dolphin follows the finger).
    private void attachTouchScreen(View screen) {
        if (gunOn()) {
            // 건슈팅: the picture is where the gun points (0.0.373).
            screen.setOnTouchListener(this::onGunTouch);
            return;
        }
        if (isKeyboardPc()) {
            // The mouse mode's mouse: the game screen itself.
            screen.setOnTouchListener((view, event) -> this.dosControls != null && this.dosControls.screenTouch(view, event));
            return;
        }
        if (!isDualScreen() && !isDolphin() && !isVita()) {
            return;
        }
        if (isN3ds() || isNds()) {
            // 3DS · DS 화면 배치: worked out again whenever the picture's size changes.
            screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot || l != ol || t != ot) {
                    v.post(this::applyN3dsLayout);
                }
            });
        }
        screen.setOnTouchListener((view, event) -> {
            if ((isN3ds() || isNds()) && n3dsTouchOutside(event)) {
                return true;
            }
            long handle = this.nativeHandle;
            int width = view.getWidth();
            int height = view.getHeight();
            if (handle == 0 || width <= 0 || height <= 0) {
                return true;
            }
            int action = event.getActionMasked();
            boolean pressed = action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL;
            int[] ds = dsPointer(event);
            if (ds != null) {
                // DS 화면 배치: the lower screen's place on the engine's own frame
                NativeGba.setPointer(handle, ds[0], ds[1], pressed);
                return true;
            }
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

    private boolean isDolphin() {
        return "dolphin".equals(this.coreId);
    }

    // ---- GameCube / Wii (Dolphin) ----
    // Dolphin reads every pad one to one: A/B/X/Y, L/R, L2/R2, L3/R3, SELECT/START.
    private static final int DOLPHIN_HOME = 131072;    // R3
    private static final String[] WII_CONTROL_NAMES = {"리모컨 + 눈차크", "리모컨 가로로 잡기", "클래식 컨트롤러"};
    private static final String[] WII_CONTROL_NOTES = {"젤다·마리오 갤럭시 등 대부분 (기본)", "뉴 슈퍼마리오·마리오카트 등 가로 게임", "클래식 지원 게임 (몬헌3 등)"};
    private Boolean wiiDisc;

    // Wii or GameCube, from the disc image's header: a Wii magic word at 0x18 of a plain
    // image, the disc type of a WIA/RVZ image (0x48), the sub type of a GCZ image (4); WBFS
    // holds only Wii games.
    // The disc image to read a header from: a game on several discs (.m3u) is read from its
    // first disc.
    private String discImagePath() {
        if (this.romPath != null && this.romPath.toLowerCase(Locale.US).endsWith(".m3u")) {
            java.util.List<File> discs = MainActivity.msxPlaylistDisks(new File(this.romPath));
            if (!discs.isEmpty()) {
                return discs.get(0).getPath();
            }
        }
        return this.romPath;
    }

    private boolean isWiiGame() {
        if (this.wiiDisc != null) {
            return this.wiiDisc;
        }
        boolean wii = false;
        String lower = this.romPath == null ? "" : this.romPath.toLowerCase(Locale.US);
        if (lower.endsWith(".wbfs")) {
            wii = true;
        } else if (this.romPath != null) {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(discImagePath(), "r")) {
                byte[] head = new byte[0x50];
                file.readFully(head);
                int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                if (magic == 0x52565A01 || magic == 0x57494101) {
                    wii = ((head[0x48] & 0xFF) << 24 | (head[0x49] & 0xFF) << 16 | (head[0x4A] & 0xFF) << 8 | (head[0x4B] & 0xFF)) == 2;
                } else if (magic == 0x01C00BB1) {
                    wii = (head[4] & 0xFF) == 1;
                } else {
                    int wiiMagic = ((head[0x18] & 0xFF) << 24) | ((head[0x19] & 0xFF) << 16) | ((head[0x1A] & 0xFF) << 8) | (head[0x1B] & 0xFF);
                    wii = wiiMagic == 0x5D1C9EA3;
                }
            } catch (IOException | RuntimeException e) {
                wii = false;
            }
        }
        this.wiiDisc = wii;
        return wii;
    }

    // GameCube / Wii resolution and picture shape, per game.
    private static final String[] DOLPHIN_SCALE_NAMES = {"1배 · 640×528", "2배 · 720p급", "3배 · 1080p급"};
    private static final String[] DOLPHIN_SCALE_SHORT = {"1배 (기본)", "2배", "3배"};
    private static final String[] DOLPHIN_SCALE_NOTES = {"게임기 원래 해상도. 가장 빠름 (기본)", "선명. 성능 좋은 폰 · 게임큐브 게임용", "가장 선명. 무거워요 · Wii 게임은 끊길 수 있어요"};
    private static final String[] DOLPHIN_ASPECT_NAMES = {"게임대로", "4:3", "16:9로 늘리기", "와이드 (16:9로 그리기)"};
    private static final String[] DOLPHIN_ASPECT_NOTES = {"게임이 정한 비율 · 게임큐브 4:3, Wii 16:9 (기본)", "옛날 TV 비율로 고정", "4:3 화면을 옆으로 늘려요 (그림이 넓적해져요)",
            "게임을 16:9로 넓게 그려요 · 일부 게임은 화면 끝이 깨지거나 메뉴가 늘어나요"};

    private int dolphinScale() {
        int scale = MainActivity.sharedSettings(this).getInt("gc_scale:" + this.saveBaseName, 1);
        return scale >= 1 && scale <= 3 ? scale : 1;
    }

    private int dolphinAspect() {
        int aspect = MainActivity.sharedSettings(this).getInt("gc_aspect:" + this.saveBaseName, 0);
        return aspect >= 0 && aspect <= 3 ? aspect : 0;
    }

    private LinearLayout createDolphinScaleRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도", DOLPHIN_SCALE_SHORT[dolphinScale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도", DOLPHIN_SCALE_NAMES, DOLPHIN_SCALE_NOTES,
                        dolphinScale() - 1, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요 (2~3초).\n뜨거워지거나 끊기면 1배로 돌려 주세요.",
                        Color.rgb(238, 246, 255), Color.rgb(30, 79, 138), "적용하고 이어하기", choice -> {
                    if (choice + 1 == dolphinScale()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("gc_scale:" + this.saveBaseName, choice + 1).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    private LinearLayout createDolphinAspectRow(android.app.Dialog menu) {
        return createSettingRow(RoundIconView.ROTATE, Color.rgb(15, 118, 110), "화면 비율", DOLPHIN_ASPECT_NAMES[dolphinAspect()],
                view -> showChoiceDialog(RoundIconView.ROTATE, Color.rgb(15, 118, 110), "화면 비율", DOLPHIN_ASPECT_NAMES, DOLPHIN_ASPECT_NOTES,
                        dolphinAspect(), "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요. 화면 틀도 그 비율로 바뀌어요.",
                        Color.rgb(236, 253, 245), Color.rgb(17, 94, 89), "적용하고 이어하기", choice -> {
                    if (choice == dolphinAspect()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("gc_aspect:" + this.saveBaseName, choice).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // The remote's pointer: a finger on the screen, or a connected controller's right stick.
    private static final String[] WII_POINTER_NAMES = {"화면 터치", "오른쪽 스틱"};

    private int wiiPointer() {
        return MainActivity.sharedSettings(this).getInt("wii_pointer:" + this.saveBaseName, 0) == 1 ? 1 : 0;
    }

    private LinearLayout createWiiPointerRow(android.app.Dialog menu) {
        int current = wiiPointer();
        return createSettingRow(RoundIconView.STICK, Color.rgb(67, 56, 202), "리모컨 포인터", WII_POINTER_NAMES[current],
                view -> showChoiceDialog(RoundIconView.STICK, Color.rgb(67, 56, 202), "리모컨 포인터", WII_POINTER_NAMES,
                        new String[]{"게임 화면을 누른 곳을 가리켜요 (기본)", "게임패드의 오른쪽 스틱으로 포인터를 움직여요 · 게임패드가 있을 때"}, current,
                        "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요.",
                        Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용하고 이어하기", choice -> {
                    if (choice == current) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("wii_pointer:" + this.saveBaseName, choice).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    // The Wii's SSL certificates, from 게임큐브 · Wii › Wii 시스템 업데이트 (the core's Wii folder).
    private boolean wiiCertificatesReady() {
        File wii = new File(getFilesDir(), "saves/dolphin/User/Wii");
        for (String name : new String[]{"clientca.pem", "clientcakey.pem", "rootca.pem"}) {
            if (!new File(wii, name).isFile()) {
                return false;
            }
        }
        return true;
    }

    // Wii 인터넷 (Wiimmfi), per game: the bridge points the game's Nintendo Wi-Fi Connection
    // addresses in memory at Wiimmfi (as the Wiimmfi patcher does to the disc).
    private boolean wiimmfiOn() {
        return wiiServer() == 1;
    }

    // 인터넷 서버, per Wii game: 0 off, 1 Wiimmfi (the bridge rewrites the addresses in memory),
    // 2 WiiLink WFC (WiiLink's own first-stage Gecko code, from assets/wiilink_wfc.txt).
    private int wiiServer() {
        if (!isDolphin() || !isWiiGame()) {
            return 0;
        }
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        int value = prefs.getInt("wii_server:" + this.saveBaseName, prefs.getBoolean("wiimmfi:" + this.saveBaseName, false) ? 1 : 0);
        return value >= 0 && value <= 3 ? value : 0;
    }

    private int wiiServerColor() {
        int server = wiiServer();
        return server == 3 ? Color.rgb(124, 58, 237) : server == 2 ? Color.rgb(8, 145, 178) : Color.rgb(234, 88, 12);
    }

    private static final String[] WII_SERVER_NAMES = {"꺼짐", "Wiimmfi", "WiiLink WFC", "친구끼리"};

    // ---- "친구끼리": a Wi-Fi Connection room on the host phone (WfcRoom), 1 host, 2 guest ----
    private int wiiRoomMode() {
        if (wiiServer() != 3) {
            return 0;
        }
        int value = MainActivity.sharedSettings(this).getInt("wii_room", 1);
        return value == 2 ? 2 : 1;
    }

    private String wiiRoomHost() {
        String value = MainActivity.sharedSettings(this).getString("wii_room_host", "");
        return value == null || value.isEmpty() ? null : value;
    }

    private void setWiiRoom(int mode, String host) {
        MainActivity.sharedSettings(this).edit().putInt("wii_server:" + this.saveBaseName, 3).remove("wiimmfi:" + this.saveBaseName)
                .putInt("wii_room", mode).putString("wii_room_host", host == null ? "" : host).commit();
    }

    // Where this phone's game finds the room: the host's own address the guests use too (the
    // matchmaking hands it to them), or the host the guest picked. Null when not in a room.
    private String wiiRoomAddress() {
        int mode = wiiRoomMode();
        String address = mode == 1 ? hostAddress() : mode == 2 ? wiiRoomHost() : null;
        return address == null ? null : address + ":" + WfcRoom.HTTP_PORT;
    }

    // The disc's game ID (6 letters) and revision: ISO at 0, WBFS at 0x200, RVZ / WIA in the
    // copy of the disc header they keep at 0x58. Null when it cannot be read.
    private String[] wiiGameId;

    private String[] wiiGameId() {
        if (this.wiiGameId != null) {
            return this.wiiGameId.length == 2 ? this.wiiGameId : null;
        }
        this.wiiGameId = new String[0];
        if (this.romPath == null) {
            return null;
        }
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(discImagePath(), "r")) {
            byte[] head = new byte[0x60];
            file.readFully(head);
            int magic = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
            int at;
            if (magic == 0x52565A01 || magic == 0x57494101) {
                at = 0x58;
            } else if (this.romPath.toLowerCase(Locale.US).endsWith(".wbfs")) {
                file.seek(0x200);
                file.readFully(head, 0, 8);
                at = 0;
            } else {
                at = 0;
            }
            StringBuilder id = new StringBuilder();
            for (int k = 0; k < 6; k++) {
                char c = (char) (head[at + k] & 0xFF);
                if (!Character.isLetterOrDigit(c)) {
                    return null;
                }
                id.append(c);
            }
            this.wiiGameId = new String[]{id.toString(), String.format(Locale.US, "%02X", head[at + 7] & 0xFF)};
            return this.wiiGameId;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // WiiLink's title for the disc (game ID's first four letters, D, revision): RMCKD00.
    private String wiiLinkTitle() {
        String[] id = wiiGameId();
        return id == null ? null : id[0].substring(0, 4) + "D" + id[1];
    }

    // The WiiLink WFC code lines for this game, or null when WiiLink has none for it.
    private java.util.List<String> wiiLinkCode() {
        return wiiCode(wiiServer() == 3 ? "wfc_room_codes.txt" : "wiilink_wfc.txt");
    }

    // The game's first-stage code in a code list asset (WiiLink's own, or the room's).
    private java.util.List<String> wiiCode(String asset) {
        String title = wiiLinkTitle();
        if (title == null) {
            return null;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open(asset), "UTF-8"))) {
            java.util.List<String> lines = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("[")) {
                    if (lines != null) {
                        break;
                    }
                    if (line.equals("[" + title + "]")) {
                        lines = new java.util.ArrayList<>();
                    }
                } else if (lines != null && !line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
            return lines == null || lines.isEmpty() ? null : lines;
        } catch (IOException e) {
            return null;
        }
    }

    // Dolphin turns on only the codes in a game's settings file: with WiiLink WFC the game's
    // user settings file (<saves>/dolphin/User/GameSettings/<ID>.ini) gets the code, enabled;
    // otherwise the file the app wrote is removed. True when the code is in place.
    private boolean applyWiiLinkCode() {
        String[] id = wiiGameId();
        if (id == null) {
            return false;
        }
        File ini = new File(getFilesDir(), "saves/dolphin/User/GameSettings/" + id[0] + ".ini");
        boolean ours = false;
        if (ini.isFile()) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(ini))) {
                String first = reader.readLine();
                ours = first != null && first.startsWith("# Mini Emulator");
            } catch (IOException e) {
                ours = false;
            }
        }
        java.util.List<String> code = wiiServer() >= 2 ? wiiLinkCode() : null;
        // The player's cheats (치트 코드), only while the game stays off the internet.
        String cheatsIni = code == null && wiiServer() == 0 && cheatsSupported() ? Cheats.dolphinIni(cheats()) : "";
        this.dolphinCodesWritten = cheatsIni;
        if (code == null && cheatsIni.isEmpty()) {
            if (ours) {
                ini.delete();
            }
            return false;
        }
        if (ini.isFile() && !ours) {
            // The player's own settings file: leave it alone.
            return false;
        }
        StringBuilder text;
        if (code != null) {
            text = new StringBuilder("# Mini Emulator WiiLink WFC (" + wiiLinkTitle() + "). Online communications credit to WiiLink WFC - https://wfc.wiilink.ca\n");
            text.append("[Gecko]\n$WiiLink WFC [WiiLink]\n");
            for (String line : code) {
                text.append(line).append('\n');
            }
            text.append("[Gecko_Enabled]\n$WiiLink WFC\n");
        } else {
            text = new StringBuilder("# Mini Emulator cheats (치트 코드)\n").append(cheatsIni);
        }
        File dir = ini.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(ini)) {
            out.write(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String wiiServerShown() {
        int server = wiiServer();
        if (server == 0) {
            return "꺼짐";
        }
        if (server == 3) {
            return "친구끼리 · " + (wiiRoomMode() == 2 ? "참가 " + (wiiRoomHost() == null ? "?" : wiiRoomHost()) : "방장 · " + linkShown());
        }
        return WII_SERVER_NAMES[server] + (wiiCertificatesReady() ? "" : " · 인증서 없음");
    }

    private LinearLayout createWiimmfiRow(android.app.Dialog menu) {
        int server = wiiServer();
        String title = wiiLinkTitle();
        boolean hasCode = wiiCode("wiilink_wfc.txt") != null;
        boolean roomCode = wiiCode("wfc_room_codes.txt") != null;
        boolean roomShipped = WfcRoom.shipped(this);
        String[] notes = {"게임의 Wi-Fi 메뉴는 접속 오류가 나요 (원래 서버가 닫혔어요)",
                "공개 서버 · 메모리 속 접속 주소를 Wiimmfi로 · 실제 Wii 본체 정보가 없으면 23934로 막힐 수 있어요",
                hasCode ? "공개 서버 · WiiLink 공식 접속 코드를 켜요 (" + title + ")"
                        : "공개 서버 · 공식 접속 코드를 아직 못 받았어요" + (title == null ? "" : " (" + title + ")"),
                !roomShipped ? "이 버전에는 방 서버가 들어 있지 않아요"
                        : roomCode ? "방장 휴대폰이 Wi-Fi 서버가 돼요. 연결된 친구끼리만 만나요 · 본체 정보 필요 없음"
                        : "이 게임은 아직 안 돼요" + (title == null ? " (게임 ID를 못 읽었어요)" : " (" + title + ")")};
        return createSettingRow(RoundIconView.GLOBE, wiiServerColor(), "인터넷 서버", wiiServerShown(),
                view -> showChoiceDialog(RoundIconView.GLOBE, Color.rgb(124, 58, 237), "인터넷 서버", WII_SERVER_NAMES, notes, server,
                        "원본 게임 파일은 그대로예요. 켜 둔 치트는 꺼야 해요 (온라인에서 치트를 쓰면 막혀요).\n"
                                + "친구끼리 · WiiLink WFC: 온라인 기능 제공 WiiLink WFC (wfc.wiilink.ca). 저장하고 그 장면에서 이어져요.",
                        Color.rgb(245, 243, 255), Color.rgb(91, 33, 182), "다음", choice -> {
                    if (choice == 2 && !hasCode) {
                        Toast.makeText(this, "WiiLink WFC 공식 접속 코드를 아직 못 받았어요. \"친구끼리\"를 써 주세요.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (choice == 3) {
                        if (!roomShipped || !roomCode) {
                            Toast.makeText(this, !roomShipped ? "이 버전에는 방 서버가 들어 있지 않아요." : "이 게임은 친구끼리 대전이 아직 안 돼요.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        showWiiRoomChoice(menu);
                        return;
                    }
                    if (choice == server) {
                        return;
                    }
                    Runnable apply = () -> {
                        MainActivity.sharedSettings(this).edit().putInt("wii_server:" + this.saveBaseName, choice)
                                .remove("wiimmfi:" + this.saveBaseName).commit();
                        menu.dismiss();
                        relaunchGame();
                    };
                    if ((choice == 1 || choice == 2) && !WiiHelp.ownConsole(this)) {
                        // Public servers turn Dolphin's shared default console away.
                        new AppDialog.Builder(this).setTitle("공개 서버는 실제 Wii 본체 정보가 필요해요")
                                .setMessage("지금은 Dolphin 기본 본체라 " + (choice == 2 ? "WiiLink WFC에서 22001" : "Wiimmfi에서 23934") + " 오류로 막혀요.\n\n"
                                        + "내 Wii가 있으면: 앱 첫 화면 → 게임큐브 · Wii → Wii 온라인 → 내 Wii NAND 가져오기.\n"
                                        + "없으면: 친구끼리 방을 써 주세요 (본체 정보 필요 없음).")
                                .setNegativeButton("그래도 켜기", (d, which) -> apply.run())
                                .setPositiveButton("친구끼리로 바꾸기", (d, which) -> {
                                    if (!roomShipped || !roomCode) {
                                        Toast.makeText(this, !roomShipped ? "이 버전에는 방 서버가 들어 있지 않아요." : "이 게임은 친구끼리 대전이 아직 안 돼요.", Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    showWiiRoomChoice(menu);
                                }).show();
                        return;
                    }
                    apply.run();
                }));
    }

    private static final String[] WII_ROOM_NAMES = {"방 만들기 (방장)", "참가"};
    private static final String[] WII_ROOM_NOTES = {"이 휴대폰에서 방 서버를 켜요. 친구는 \"참가\"로 들어와요",
            "같은 와이파이 · Wi-Fi Direct · VPN의 방장을 찾아서 연결해요"};

    // 친구끼리: host (then how guests reach it) or join (then which host).
    private void showWiiRoomChoice(android.app.Dialog menu) {
        int color = Color.rgb(124, 58, 237);
        showChoiceDialog(RoundIconView.WIFI, color, "친구끼리", WII_ROOM_NAMES, WII_ROOM_NOTES, wiiRoomMode() == 2 ? 1 : 0,
                "연결 방법은 DS 통신 · PSP와 같아요. 방장이 먼저 게임을 켜 두세요.\n"
                        + "모두 같은 게임 · 같은 지역판 · 같은 앱 버전이어야 해요. 게임에서 Wi-Fi › 전국 대전을 고르면 이 방 사람들끼리 만나요.\n"
                        + "방 서버: WiiLink WFC 서버(AGPL-3.0)를 고친 것 · 소스 github.com/jjongjjongs/Mini-Emulator (cores/wfcserver)",
                Color.rgb(245, 243, 255), Color.rgb(91, 33, 182), "다음", choice -> {
                    if (choice == 1) {
                        showHostFinder(menu, RoundIconView.WIFI, color, address -> setWiiRoom(2, address));
                        return;
                    }
                    chooseLinkThen(menu, RoundIconView.WIFI, color, () -> setWiiRoom(1, wiiRoomHost()));
                });
    }

    // What the bridge changed in memory, from the engine log; shared to the developer when
    // the game still cannot connect.
    private void showWiimmfiLog() {
        StringBuilder lines = new StringBuilder();
        if (wiiServer() >= 2) {
            java.util.List<String> code = wiiLinkCode();
            String[] id = wiiGameId();
            lines.append("WiiLink 코드: ").append(wiiLinkTitle()).append(code == null ? " · 없음" : " · " + code.size() + "줄")
                    .append(id == null ? "" : " · 게임 ID " + id[0]).append('\n');
            if (wiiServer() == 2) {
                lines.append("인증서: ").append(wiiCertificatesReady() ? "있음" : "없음 (Wii 시스템 업데이트 필요)").append('\n');
            } else {
                lines.append("방: ").append(wiiRoomMode() == 1 ? "방장 · 서버 " + (WfcRoom.running() ? "켜짐" : "꺼짐") : "참가")
                        .append(" · ").append(wiiRoomAddress()).append('\n');
            }
        }
        for (String line : CrashReports.readGameLog(this).split("\n")) {
            if (line.contains("Wiimmfi") || line.contains("WiiLink") || line.contains("Gecko") || line.contains("Patch") || line.contains("Mini WFC")) {
                lines.append(line.replace("[Mini] Wiimmfi: ", "")).append('\n');
            }
        }
        if (wiiRoomMode() == 1) {
            String server = WfcRoom.tail(this, 40);
            if (!server.isEmpty()) {
                lines.append("\n── 방 서버 ──\n").append(server).append('\n');
            }
        }
        final String text = lines.length() == 0 ? "아직 기록이 없어요. 게임을 켜고 몇 초 뒤에 다시 봐 주세요." : lines.toString().trim();
        final String server = WII_SERVER_NAMES[wiiServer()];
        new AppDialog.Builder(this).setTitle("접속 기록 · " + server)
                .setMessage(text)
                .setNegativeButton("닫기", null)
                .setPositiveButton("공유", (d, w) -> shareText("Mini Emulator 접속 기록 · " + server + " (" + this.gameTitle + ")\n" + text)).show();
    }

    // On the picture's top right while Wiimmfi is on.
    private void addWiimmfiBadge(FrameLayout screenFrame) {
        if (wiiServer() == 0) {
            return;
        }
        TextView badge = new TextView(this);
        badge.setText("🌐 " + (wiiServer() == 3 ? (wiiRoomMode() == 1 ? "친구끼리 · 방장" : "친구끼리 · 참가") : WII_SERVER_NAMES[wiiServer()]));
        badge.setTextSize(11.0f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        badge.setBackground(roundRect((wiiServerColor() & 0x00FFFFFF) | 0xE6000000, dp(11)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(8), dp(8), 0);
        screenFrame.addView(badge, params);
    }

    private int wiiControl() {
        int value = getSharedPreferences("settings", 0).getInt("wii_control:" + this.saveBaseName, 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    private LinearLayout createWiiControlRow(android.app.Dialog menu) {
        int current = wiiControl();
        return createSettingRow(RoundIconView.MOVE, Color.rgb(67, 56, 202), "Wii 조작 방식", WII_CONTROL_NAMES[current],
                view -> showChoiceDialog(RoundIconView.MOVE, Color.rgb(67, 56, 202), "Wii 조작 방식", WII_CONTROL_NAMES, WII_CONTROL_NOTES,
                        current, "이 게임에만 적용돼요. 지금 장면을 저장하고 게임을 다시 켜서\n그 장면 그대로 이어져요.\n리모컨 포인터는 게임 화면을 손가락으로 눌러서 가리켜요.",
                        Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용하고 이어하기", choice -> {
                    if (choice == wiiControl()) {
                        return;
                    }
                    getSharedPreferences("settings", 0).edit().putInt("wii_control:" + this.saveBaseName, choice).commit();
                    menu.dismiss();
                    relaunchGame();
                }));
    }

    private void addStickAt(FrameLayout frameLayout, boolean fromRight, int cxDp, int cyDp, int sizeDp, boolean right) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.BOTTOM | (fromRight ? Gravity.END : Gravity.START));
        int side = dp(cxDp) - dp(sizeDp) / 2;
        params.setMargins(fromRight ? 0 : side, 0, fromRight ? side : 0, dp(cyDp) - dp(sizeDp) / 2);
        frameLayout.addView(new AnalogStickView(this, right ? this::onAnalogRightChanged : this::onAnalogChanged), params);
    }

    private void addDPadCentered(FrameLayout frameLayout, boolean fromRight, int cxDp, int cyDp, int sizeDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.BOTTOM | (fromRight ? Gravity.END : Gravity.START));
        int side = dp(cxDp) - dp(sizeDp) / 2;
        params.setMargins(fromRight ? 0 : side, 0, fromRight ? side : 0, dp(cyDp) - dp(sizeDp) / 2);
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), params);
    }

    // A keycap at the bottom, centred dxDp from the middle (or from the middle of a column).
    private void addPill(FrameLayout frameLayout, String label, int bit, int gravity, int sideDp, int dxDp, int bottomDp, int widthDp, int heightDp) {
        TouchButton button = new TouchButton(this, label, bit, this::setButtonPressed);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp), gravity);
        params.setMargins(sideDp > 0 && (gravity & Gravity.START) == Gravity.START ? dp(sideDp) : 0, 0,
                sideDp > 0 && (gravity & Gravity.END) == Gravity.END ? dp(sideDp) : 0, dp(bottomDp));
        button.setTranslationX(dp(dxDp));
        frameLayout.addView(button, params);
    }

    // − HOME + in a row centred on cx (from the left), or on the screen's middle when cx is 0.
    private void addWiiSystemKeys(FrameLayout frameLayout, int cxDp, int bottomDp, boolean small) {
        // With the nunchuk, Dolphin reads − and + from L and R (1 and 2 take SELECT and START).
        boolean nunchuk = isWiiGame() && wiiControl() == 0;
        int w = small ? 42 : 58;
        int home = small ? 58 : 68;
        int gap = 6;
        int gravity = Gravity.BOTTOM | (cxDp == 0 ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        int base = cxDp == 0 ? 0 : cxDp;
        int[] widths = {w, home, w};
        String[] labels = {"−", "HOME", "+"};
        int[] bits = nunchuk ? new int[]{512, DOLPHIN_HOME, 256} : new int[]{4, DOLPHIN_HOME, 8};
        int total = w * 2 + home + gap * 2;
        int x = -total / 2;
        for (int k = 0; k < 3; k++) {
            int center = x + widths[k] / 2;
            if (cxDp == 0) {
                addPill(frameLayout, labels[k], bits[k], gravity, 0, center, bottomDp, widths[k], 30);
            } else {
                addPill(frameLayout, labels[k], bits[k], gravity, base + center - widths[k] / 2, 0, bottomDp, widths[k], 30);
            }
            x += widths[k] + gap;
        }
    }

    // GameCube, 마름모 (B안): Y top, X right, A bottom, B left, all one size.
    private void addGameCubeDiamond(FrameLayout frameLayout, int rcDp, int cyDp, int size, int gap) {
        addRoundButton(frameLayout, "Y", 2048, true, rcDp, cyDp + gap, size, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "X", 1024, true, rcDp - gap, cyDp, size, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "A", 1, true, rcDp, cyDp - gap, size, TouchButton.STYLE_ARCADE_GREEN);
        addRoundButton(frameLayout, "B", 2, true, rcDp + gap, cyDp, size, TouchButton.STYLE_ARCADE_RED);
    }

    private void addDolphinControls(FrameLayout frameLayout) {
        if (!isWiiGame()) {
            // L | speed | R on top with Z under the speed button; the stick and the buttons
            // on one line, the D-pad and the C-stick (right stick) on the next.
            addButton(frameLayout, "L", kL2, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
            addButton(frameLayout, "R", kR2, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
            FrameLayout.LayoutParams zParams = new FrameLayout.LayoutParams(dp(80), dp(30), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            zParams.setMargins(0, dp(87), 0, 0);
            frameLayout.addView(new TouchButton(this, "Z", 256, this::setButtonPressed, 2), zParams);
            addStickAt(frameLayout, false, 86, 240, 112, false);
            addGameCubeDiamond(frameLayout, 100, 240, 52, 52);
            addDPadCentered(frameLayout, false, 86, 100, 66);
            addStickAt(frameLayout, true, 100, 100, 66, true);
            addFastForwardButton(frameLayout);
            addCenterButton(frameLayout, "START", 8, 0, 18);
            return;
        }
        int control = wiiControl();
        if (control == 1) {
            // Remote held sideways: D-pad, 2 and 1 (the core's A and B), small A/B above them.
            addDPadCentered(frameLayout, false, 96, 215, 150);
            addRoundButton(frameLayout, "1", 2, true, 130, 190, 66, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "2", 1, true, 62, 230, 66, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "A", 1024, true, 130, 300, 44, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "B", 2048, true, 62, 330, 44, TouchButton.STYLE_DARK);
            addPill(frameLayout, "흔들기", kR2, Gravity.BOTTOM | Gravity.START, 46, 0, 330, 80, 32);
            addFastForwardButton(frameLayout);
            addWiiSystemKeys(frameLayout, 0, 18, false);
            return;
        }
        if (control == 2) {
            // Classic controller: L ZL | speed | ZR R, D-pad and a b x y, then both sticks.
            addButton(frameLayout, "L", kL2, 8388659, dp(14), dp(34), dp(54), dp(42), 2);
            addButton(frameLayout, "ZL", 512, 8388659, dp(72), dp(34), dp(54), dp(42), 2);
            addButton(frameLayout, "ZR", 256, 8388661, dp(72), dp(34), dp(54), dp(42), 2);
            addButton(frameLayout, "R", kR2, 8388661, dp(14), dp(34), dp(54), dp(42), 2);
            addDPadCentered(frameLayout, false, 86, 250, 130);
            addRoundButton(frameLayout, "x", 1024, true, 100, 302, 52, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "a", 1, true, 48, 250, 52, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "b", 2, true, 100, 198, 52, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "y", 2048, true, 152, 250, 52, TouchButton.STYLE_DARK);
            addStickAt(frameLayout, false, 86, 110, 90, false);
            addStickAt(frameLayout, true, 100, 110, 90, true);
            addFastForwardButton(frameLayout);
            // Clear of the decorations along the case's bottom edge (dials, speaker, seal).
            addWiiSystemKeys(frameLayout, 0, 40, true);
            return;
        }
        // Remote + nunchuk: Z | speed | B on top, nunchuk stick beside the remote's D-pad,
        // C beside A, 1 and 2 under A (shake beside them), - HOME + at the bottom.
        addButton(frameLayout, "Z", 2048, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
        addButton(frameLayout, "B", 2, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        addStickAt(frameLayout, false, 86, 354, 110, false);
        addDPadCentered(frameLayout, true, 100, 354, 96);
        addRoundButton(frameLayout, "C", 1024, false, 86, 236, 54, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "A", 1, true, 100, 236, 66, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "1", 8, true, 130, 152, 46, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "2", 4, true, 70, 152, 46, TouchButton.STYLE_DARK);
        addPill(frameLayout, "흔들기", kR2, Gravity.BOTTOM | Gravity.START, 46, 0, 136, 80, 32);
        addFastForwardButton(frameLayout);
        // Clear of the decorations along the case's bottom edge (dials, speaker, seal).
        addWiiSystemKeys(frameLayout, 0, 46, false);
    }

    // Landscape: the same groups in the columns beside the screen (centres 94dp in).
    private void addDolphinLandscapeControls(FrameLayout frameLayout) {
        if (!isWiiGame()) {
            FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.START);
            lParams.setMargins(dp(14), dp(58), 0, 0);
            frameLayout.addView(new TouchButton(this, "L", kL2, this::setButtonPressed, 2), lParams);
            FrameLayout.LayoutParams rParams = new FrameLayout.LayoutParams(dp(88), dp(34), Gravity.TOP | Gravity.END);
            rParams.setMargins(0, dp(58), dp(76), 0);
            frameLayout.addView(new TouchButton(this, "R", kR2, this::setButtonPressed, 2), rParams);
            FrameLayout.LayoutParams zParams = new FrameLayout.LayoutParams(dp(56), dp(34), Gravity.TOP | Gravity.END);
            zParams.setMargins(0, dp(58), dp(14), 0);
            frameLayout.addView(new TouchButton(this, "Z", 256, this::setButtonPressed, 2), zParams);
            addStickAt(frameLayout, false, 94, 227, 96, false);
            // Kept inside the column beside the 4:3 picture.
            addGameCubeDiamond(frameLayout, 86, 227, 40, 36);
            addDPadCentered(frameLayout, false, 94, 108, 58);
            addStickAt(frameLayout, true, 94, 108, 58, true);
            addButton(frameLayout, "START", 8, 8388691, dp(47), dp(14), dp(94), dp(32));
            return;
        }
        int control = wiiControl();
        if (control == 1) {
            addDPadCentered(frameLayout, false, 94, 217, 140);
            addRoundButton(frameLayout, "1", 2, true, 140, 183, 62, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "2", 1, true, 66, 251, 62, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "A", 1024, true, 140, 290, 40, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "B", 2048, true, 66, 318, 40, TouchButton.STYLE_DARK);
            addPill(frameLayout, "흔들기", kR2, Gravity.BOTTOM | Gravity.START, 54, 0, 318, 80, 30);
            addWiiSystemKeys(frameLayout, 94, 16, true);
            return;
        }
        if (control == 2) {
            FrameLayout.LayoutParams[] tops = new FrameLayout.LayoutParams[4];
            String[] labels = {"L", "ZL", "ZR", "R"};
            int[] bits = {kL2, 512, 256, kR2};
            for (int k = 0; k < 4; k++) {
                tops[k] = new FrameLayout.LayoutParams(dp(78), dp(34), Gravity.TOP | (k < 2 ? Gravity.START : Gravity.END));
                int side = dp(k == 0 || k == 3 ? 14 : 98);
                tops[k].setMargins(k < 2 ? side : 0, dp(58), k < 2 ? 0 : side, 0);
                frameLayout.addView(new TouchButton(this, labels[k], bits[k], this::setButtonPressed, 2), tops[k]);
            }
            addDPadCentered(frameLayout, false, 94, 236, 108);
            addRoundButton(frameLayout, "x", 1024, true, 94, 278, 42, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "a", 1, true, 52, 236, 42, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "b", 2, true, 94, 194, 42, TouchButton.STYLE_DARK);
            addRoundButton(frameLayout, "y", 2048, true, 136, 236, 42, TouchButton.STYLE_DARK);
            addStickAt(frameLayout, false, 94, 110, 76, false);
            addStickAt(frameLayout, true, 94, 110, 76, true);
            addWiiSystemKeys(frameLayout, 94, 16, true);
            return;
        }
        FrameLayout.LayoutParams zParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.START);
        zParams.setMargins(dp(14), dp(58), 0, 0);
        frameLayout.addView(new TouchButton(this, "Z", 2048, this::setButtonPressed, 2), zParams);
        FrameLayout.LayoutParams bParams = new FrameLayout.LayoutParams(dp(150), dp(34), Gravity.TOP | Gravity.END);
        bParams.setMargins(0, dp(58), dp(14), 0);
        frameLayout.addView(new TouchButton(this, "B", 2, this::setButtonPressed, 2), bParams);
        addStickAt(frameLayout, false, 94, 250, 96, false);
        addDPadCentered(frameLayout, true, 94, 250, 84);
        addRoundButton(frameLayout, "C", 1024, false, 94, 150, 48, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "A", 1, true, 94, 150, 58, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "1", 8, true, 122, 86, 40, TouchButton.STYLE_DARK);
        addRoundButton(frameLayout, "2", 4, true, 66, 86, 40, TouchButton.STYLE_DARK);
        addPill(frameLayout, "흔들기", kR2, Gravity.BOTTOM | Gravity.START, 54, 0, 70, 80, 30);
        addWiiSystemKeys(frameLayout, 94, 16, true);
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
        return MainActivity.sharedSettings(this).getBoolean("ws_tall:" + this.saveBaseName, false);
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
                    MainActivity.sharedSettings(this).edit().putBoolean("ws_tall:" + this.saveBaseName, choice == 1).commit();
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

    // Naomi · Atomiswave arcade sets on the Dreamcast engine: arcade stick and numbered
    // buttons instead of the Dreamcast pad.
    private boolean isNaomi() {
        return "naomi".equals(this.gameMode);
    }

    // ---- DOS (DOSBox Pure) ----
    private DosControls dosControls;
    private View contentRoot;

    private boolean isDos() {
        return "dosbox_pure".equals(this.coreId);
    }

    // DOS and the PC-98: keyboard-and-mouse computers, on the same on-screen controls.
    private boolean isKeyboardPc() {
        return isDos() || isPc98();
    }

    // ---- PC-98 (NP2kai, 0.0.317) ----
    private static final String[] PC98_SOUND_NAMES = {"PC-9801-86 (추천)", "PC-9801-26K", "86 + 스피크 보드", "86 + 118", "PC-9801-118", "없음"};
    private static final String[] PC98_SOUND_VALUES = {"PC9801-86", "PC9801-26K", "PC9801-86 + Speak Board", "PC9801-86 + 118(B460)", "PC9801-118", "None"};
    private static final String[] PC98_SOUND_NOTES = {"90년대 게임 대부분 · FM 6음 + PCM", "80년대 게임 · FM 3음",
            "86에 스피크 보드를 더한 것 · 일부 게임의 음성", "86에 118을 더한 것 · 윈도우 시절 게임 일부", "118만", "소리를 끔"};
    // Hard disk images NP2kai mounts as IDE drives; the rest of a .cmd's files are floppies.
    private static final String[] PC98_HDD_EXTENSIONS = {".hdi", ".thd", ".nhd", ".vhd", ".hdn", ".slh"};

    private boolean isPc98() {
        return "np2kai".equals(this.coreId);
    }

    private String pc98Key(String name) {
        return "pc98_" + name + ":" + this.saveBaseName;
    }

    private int pc98Sound() {
        int value = MainActivity.sharedSettings(this).getInt(pc98Key("sound"), 0);
        return value < 0 || value >= PC98_SOUND_VALUES.length ? 0 : value;
    }

    // The game's options; drive: the drive the next disk swap goes into (null: FDD1).
    private void sendPc98Options(String drive) {
        // The joypad is left off: NP2kai's joypad keys share their held state with the
        // keyboard's, so a pad "up" would let go of the on-screen arrow keys every frame. The
        // NP2 menu stays on F12 (not on L2).
        int model = pc98Pick("model", PC98_MODEL_VALUES.length, 0);
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        NativeGba.setCoreOptions(new String[]{"np2kai_SNDboard", "np2kai_joymode", "np2kai_joynp2menu", "np2kai_drive",
                        "np2kai_model", "np2kai_clk_base", "np2kai_clk_mult", "np2kai_ExMemory", "np2kai_skipline", "np2kai_realpal"},
                new String[]{PC98_SOUND_VALUES[pc98Sound()], "OFF", "OFF", drive == null ? "FDD1" : drive,
                        PC98_MODEL_VALUES[model], model == 1 ? "1.9968 MHz" : "2.4576 MHz", PC98_SPEED_MULT[pc98Pick("speed", PC98_SPEED_MULT.length, 0)],
                        PC98_MEMORY_VALUES[pc98Pick("memory", PC98_MEMORY_VALUES.length, 1)],
                        prefs.getBoolean("pc98_scanlines", false) ? "OFF" : "Full 255 lines", prefs.getBoolean("pc98_realpal", false) ? "ON" : "OFF"}, false);
    }

    // The floppy images of the app's .cmd (FDD1 = the first, FDD2 = the second), in the
    // order NP2kai's disk control lists them.
    private java.util.List<File> pc98Floppies() {
        java.util.List<File> out = new java.util.ArrayList<>();
        if (this.romPath == null || !this.romPath.toLowerCase(java.util.Locale.ROOT).endsWith(".cmd")) {
            return out;
        }
        String text;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(new File(this.romPath).toPath());
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        String line = text.split("\n", 2)[0];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(line);
        while (m.find()) {
            String path = m.group(1);
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            boolean hdd = false;
            for (String ext : PC98_HDD_EXTENSIONS) {
                hdd |= lower.endsWith(ext);
            }
            if (!hdd) {
                out.add(new File(path));
            }
        }
        return out;
    }

    private int pc98Drive(int drive, int count) {
        int value = MainActivity.sharedSettings(this).getInt(pc98Key("fdd" + (drive + 1)), drive);
        return value >= 0 && value < count ? value : -1;
    }

    private void addPc98MenuRows(LinearLayout panel, android.app.Dialog dialog) {
        java.util.List<File> disks = pc98Floppies();
        if (disks.size() > 1) {
            String now = "FDD1 " + pc98DiskLabel(disks, pc98Drive(0, disks.size())) + " · FDD2 " + pc98DiskLabel(disks, pc98Drive(1, disks.size()));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
            params.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.SWAP, Color.rgb(15, 118, 110), "디스크 바꾸기", now, view -> showPc98DriveDialog(dialog, disks)), params);
        }
        LinearLayout.LayoutParams soundParams = new LinearLayout.LayoutParams(-1, dp(52));
        soundParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "사운드 보드", PC98_SOUND_NAMES[pc98Sound()],
                view -> showPc98SoundDialog(dialog)), soundParams);
        LinearLayout.LayoutParams midiParams = new LinearLayout.LayoutParams(-1, dp(52));
        midiParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.PLAY, RoundIconView.COLOR_LOAD, "MIDI 음악", PC98_MIDI_NAMES[pc98Midi()],
                view -> showPc98MidiDialog(dialog)), midiParams);
        LinearLayout.LayoutParams machineParams = new LinearLayout.LayoutParams(-1, dp(52));
        machineParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "기종 · CPU 속도",
                PC98_MODEL_NAMES[pc98Pick("model", PC98_MODEL_NAMES.length, 0)] + " · " + PC98_SPEED_NAMES[pc98Pick("speed", PC98_SPEED_NAMES.length, 0)],
                view -> showPc98MachineDialog(dialog)), machineParams);
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(-1, dp(52));
        screenParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "PC-98 화면", prefs.getBoolean("pc98_pixel", false) ? "픽셀 그대로" : "4:3 (원래 모니터)",
                view -> showPc98ScreenDialog(dialog)), screenParams);
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-1, dp(52));
        padParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.PAD, RoundIconView.COLOR_PAD, "게임패드 → 키", pc98PadSummary(),
                view -> showPc98PadDialog(dialog)), padParams);
    }

    private static String pc98DiskLabel(java.util.List<File> disks, int index) {
        if (index < 0) {
            return "비어 있음";
        }
        String name = disks.get(index).getName();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)[(\\[]\\s*(?:disk|disc|디스크)\\s*([0-9a-z]+)").matcher(name);
        return m.find() ? "Disk " + m.group(1).toUpperCase(java.util.Locale.ROOT) : "#" + (index + 1);
    }

    private void showPc98DriveDialog(android.app.Dialog menu, java.util.List<File> disks) {
        String[] names = new String[2];
        String[] notes = new String[2];
        for (int d = 0; d < 2; d++) {
            int at = pc98Drive(d, disks.size());
            names[d] = "FDD" + (d + 1) + " · " + pc98DiskLabel(disks, at);
            notes[d] = at < 0 ? "" : disks.get(at).getName();
        }
        showChoiceDialog(RoundIconView.SWAP, Color.rgb(15, 118, 110), "디스크 바꾸기 · 드라이브", names, notes, 0,
                "PC-98에는 플로피 드라이브가 2개 있어요. 처음에는 FDD1에 첫 디스크, FDD2에 둘째 디스크가 들어가요.\n"
                        + "게임이 \"Disk C를 FDD2에 넣어 주세요\"처럼 말하면, 그 드라이브를 고르고 디스크를 골라 주세요.",
                Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "디스크 고르기", drive -> showPc98DiskDialog(menu, disks, drive));
    }

    private void showPc98DiskDialog(android.app.Dialog menu, java.util.List<File> disks, int drive) {
        int now = pc98Drive(drive, disks.size());
        String[] names = new String[disks.size()];
        String[] notes = new String[disks.size()];
        for (int k = 0; k < names.length; k++) {
            int other = pc98Drive(1 - drive, disks.size());
            names[k] = pc98DiskLabel(disks, k) + (k == now ? " · 지금 이 드라이브 (다시 넣기)" : k == other ? " · FDD" + (2 - drive) + "에 있음" : "");
            notes[k] = disks.get(k).getName();
        }
        showChoiceDialog(RoundIconView.SWAP, Color.rgb(15, 118, 110), "FDD" + (drive + 1) + "에 넣을 디스크", names, notes, Math.max(0, now),
                "같은 디스크를 두 드라이브에 함께 넣지는 않아요. 다른 드라이브에 있던 디스크를 고르면 그쪽은 비워져요.\n"
                        + "지금 든 디스크를 고르면 꺼냈다가 다시 넣어요 (게임이 디스크를 못 알아볼 때).",
                Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "넣기", choice -> {
                    long handle = this.nativeHandle;
                    if (handle == 0) {
                        return;
                    }
                    String drives = drive == 0 ? "FDD1" : "FDD2";
                    sendPc98Options(drives);
                    NativeGba.diskSet(handle, choice);
                    android.content.SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                    edit.putInt(pc98Key("fdd" + (drive + 1)), choice);
                    if (pc98Drive(1 - drive, disks.size()) == choice) {
                        edit.putInt(pc98Key("fdd" + (2 - drive)), -1);
                    }
                    edit.apply();
                    Toast.makeText(this, drives + "에 " + pc98DiskLabel(disks, choice) + (choice == now ? "를 다시 넣었어요" : "를 넣었어요") + " · 게임에서 키를 눌러 주세요", Toast.LENGTH_SHORT).show();
                    if (menu != null) {
                        menu.dismiss();
                    }
                });
    }

    // ---- PC-98 phase 2 (0.0.318): machine, screen, MIDI, gamepad ----
    private static final String[] PC98_MODEL_NAMES = {"PC-9801VX (추천)", "PC-9801VM", "PC-286"};
    private static final String[] PC98_MODEL_VALUES = {"PC-9801VX", "PC-9801VM", "PC-286"};
    private static final String[] PC98_MODEL_NOTES = {"80286 · 대부분의 게임", "V30 · 80년대 초 게임 (빠르면 너무 빨라지는 게임)", "엡손 호환기 · 일부 게임만"};
    // CPU clock: the base clock (2.4576 MHz; 1.9968 MHz on the VM) times this.
    private static final String[] PC98_SPEED_NAMES = {"원래 속도", "빠르게 ×2", "아주 빠르게 ×4", "최고 ×7.5"};
    private static final String[] PC98_SPEED_MULT = {"4", "8", "16", "30"};
    private static final String[] PC98_MEMORY_NAMES = {"1MB", "3MB (기본)", "7MB", "13MB"};
    private static final String[] PC98_MEMORY_VALUES = {"1", "3", "7", "13"};
    private static final String[] PC98_MIDI_NAMES = {"끔", "General MIDI (앱 사운드폰트)", "내 사운드폰트 (.sf2)"};
    // The pad's buttons as the app's input bits (arcade layout; L / R either way), and the
    // keys they press by default.
    private static final String[] PC98_PAD_NAMES = {"A", "B", "X", "Y", "L", "R", "START", "SELECT"};
    private static final int[] PC98_PAD_BITS = {1, 2, 1024, 2048, 4096 | 512, 8192 | 256, 8, 4};
    private static final String[] PC98_PAD_DEFAULTS = {"Z", "X", "Enter", "Space", "F1", "F2", "Esc", "F10"};
    private static final String[] PC98_PAD_KEYS = {"없음", "Z", "X", "C", "V", "A", "S", "D", "Q", "W", "Y", "N", "Enter", "Space", "Esc", "Shift", "Ctrl", "Tab", "⌫",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "STOP", "GRPH", "NFER", "XFER", "1", "2", "3", "4", "kp0", "kp5"};
    private int pc98PadSent;

    private static String[] emptyNotes(int count) {
        String[] notes = new String[count];
        java.util.Arrays.fill(notes, "");
        return notes;
    }

    private int pc98Pick(String name, int count, int fallback) {
        int value = MainActivity.sharedSettings(this).getInt(pc98Key(name), fallback);
        return value >= 0 && value < count ? value : fallback;
    }

    private int pc98Midi() {
        return pc98Pick("midi", PC98_MIDI_NAMES.length, 1);
    }

    private void showPc98MachineDialog(android.app.Dialog menu) {
        showChoiceDialog(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "기종", PC98_MODEL_NAMES, PC98_MODEL_NOTES, pc98Pick("model", PC98_MODEL_NAMES.length, 0),
                "대부분의 게임은 VX 그대로 두면 돼요. 바꾸면 게임이 처음부터 다시 켜져요.",
                Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "다음: CPU 속도", model -> {
                    MainActivity.sharedSettings(this).edit().putInt(pc98Key("model_pending"), model).apply();
                    showChoiceDialog(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "CPU 속도", PC98_SPEED_NAMES,
                            new String[]{model == 1 ? "8MHz · 그 시절 그대로" : "10MHz · 그 시절 그대로", "로딩 · 계산이 빨라짐", "폰이 빨라야 해요", "가장 빠름 · 소리가 끊기면 낮추기"},
                            pc98Pick("speed", PC98_SPEED_NAMES.length, 0),
                            "빠르게 하면 로딩 · 전투 계산이 빨라지지만, 시간으로 움직이는 옛 게임은 너무 빨라질 수 있어요. 폰이 따라가지 못하면 소리가 끊겨요.",
                            Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "다음: 메모리", speed -> showChoiceDialog(RoundIconView.BOLT, RoundIconView.COLOR_BOLT,
                                    "메모리", PC98_MEMORY_NAMES, new String[]{"80년대 게임", "대부분의 게임", "EMS · XMS를 많이 쓰는 게임", "윈도우 시절 게임"},
                                    pc98Pick("memory", PC98_MEMORY_NAMES.length, 1), "모르면 3MB 그대로 두세요. 적용하면 게임이 처음부터 다시 켜져요.",
                                    Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용 후 다시 켜기", memory -> {
                                        MainActivity.sharedSettings(this).edit().putInt(pc98Key("model"), model).putInt(pc98Key("speed"), speed)
                                                .putInt(pc98Key("memory"), memory).remove(pc98Key("model_pending")).apply();
                                        pc98Restart(menu);
                                    }));
                });
    }

    // The machine starts over with the options as they are now (NP2kai takes its "Restart"
    // options when it next reads them, a frame away).
    private void pc98Restart(android.app.Dialog menu) {
        MainActivity.sharedSettings(this).edit().remove(pc98Key("fdd1")).remove(pc98Key("fdd2")).apply();
        sendPc98Options(null);
        if (menu != null) {
            menu.dismiss();
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            long handle = this.nativeHandle;
            if (handle != 0 && !this.destroyed) {
                NativeGba.reset(handle);
            }
        }, 300);
    }

    private void showPc98ScreenDialog(android.app.Dialog menu) {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        String[] names = {"4:3 (원래 모니터)", "픽셀 그대로 (16:10)", "주사선 (빈 줄) " + (prefs.getBoolean("pc98_scanlines", false) ? "켜짐" : "꺼짐"),
                "실제 팔레트 색 " + (prefs.getBoolean("pc98_realpal", false) ? "켜짐" : "꺼짐")};
        String[] notes = {"640×400을 세로로 늘림 · 추천", "글자가 또렷 · 그림이 조금 납작", "옛날 게임의 줄무늬 그대로 (누르면 바뀜)", "원래 모니터에 가까운 색 (누르면 바뀜)"};
        int current = prefs.getBoolean("pc98_pixel", false) ? 1 : 0;
        showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "PC-98 화면 (모든 게임)", names, notes, current,
                "PC-98 모니터는 4:3이라 원래 그림은 조금 세로로 길어요. 동그라미가 납작해 보이면 4:3으로. 부드럽게 · 필터는 메뉴의 화면 설정에서 바꿔요.",
                Color.rgb(240, 249, 255), Color.rgb(3, 105, 161), "적용", choice -> {
                    android.content.SharedPreferences.Editor edit = prefs.edit();
                    if (choice <= 1) {
                        edit.putBoolean("pc98_pixel", choice == 1);
                    } else if (choice == 2) {
                        edit.putBoolean("pc98_scanlines", !prefs.getBoolean("pc98_scanlines", false));
                    } else {
                        edit.putBoolean("pc98_realpal", !prefs.getBoolean("pc98_realpal", false));
                    }
                    edit.apply();
                    sendPc98Options(null);
                    View root = this.contentRoot;
                    if (root != null) {
                        root.requestLayout();
                    }
                    if (this.screenFrame != null) {
                        this.screenFrame.requestLayout();
                    }
                    applyScreenShape();
                    if (menu != null) {
                        menu.dismiss();
                    }
                    if (choice >= 2) {
                        showPc98ScreenDialog(null);
                    }
                });
    }

    private void showPc98MidiDialog(android.app.Dialog menu) {
        File userSf2 = DosSetup.userSoundfont(new File(getFilesDir(), "system"));
        showChoiceDialog(RoundIconView.PLAY, RoundIconView.COLOR_LOAD, "MIDI 음악 (이 게임)", PC98_MIDI_NAMES,
                new String[]{"86 · 26K 보드 음악만", "DOS 모드와 같은 TimGM6mb · 추가 파일 없이",
                        userSf2.isFile() ? "DOS 모드에서 넣은 user.sf2" : "아직 없음 · DOS 게임 설정 → MIDI에서 넣어요 (없으면 앱 사운드폰트)"}, pc98Midi(),
                "90년대 중반 이후의 일부 게임은 MIDI 음원(MPU-PC98)으로 음악을 내요. 켜면 PC-98에 MIDI 보드를 꽂고, 나오는 MIDI를 사운드폰트로 연주해요. "
                        + "게임 설정에서 음원을 MIDI(GM · MPU-401)로 골라야 나오는 게임이 많아요. MT-32 전용 게임은 악기가 다르게 들릴 수 있어요.\n바꾸면 게임을 나갔다가 다시 켤 때부터 적용돼요.",
                Color.rgb(245, 243, 255), Color.rgb(91, 33, 182), "적용", choice -> {
                    if (choice == pc98Midi()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt(pc98Key("midi"), choice).apply();
                    Toast.makeText(this, "게임을 나갔다가 다시 켜면 MIDI 설정이 적용돼요", Toast.LENGTH_LONG).show();
                    if (menu != null) {
                        menu.dismiss();
                    }
                });
    }

    // Before the game starts: the SoundFont, and the MIDI board in NP2kai's own settings file
    // (np2kai.cfg: MPU-PC98II on, MIDI out "direct" to the frontend).
    private void preparePc98Midi(File systemDir) {
        int choice = pc98Midi();
        String sf2 = "";
        if (choice != 0) {
            DosSetup.ensureSoundfont(this, systemDir);
            File user = DosSetup.userSoundfont(systemDir);
            File file = choice == 2 && user.isFile() ? user : DosSetup.defaultSoundfont(systemDir);
            sf2 = file.isFile() ? file.getAbsolutePath() : "";
        }
        boolean on = !sf2.isEmpty() && NativeGba.setMidiSoundfont(sf2);
        if (!on) {
            NativeGba.setMidiSoundfont("");
        }
        File cfg = new File(new File(systemDir, "np2kai"), "np2kai.cfg");
        java.util.Map<String, String> want = new java.util.LinkedHashMap<>();
        want.put("USEMPU98", on ? "true" : "false");
        want.put("mpu98port", "5");
        want.put("mpu98dir", on ? "true" : "false");
        want.put("mpu98mdl", "GM");
        writeIniKeys(cfg, "NekoProjectIIkai", want);
    }

    // Sets keys in one [section] of an .ini file, leaving the rest as it is.
    private static void writeIniKeys(File file, String section, java.util.Map<String, String> values) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            if (file.isFile()) {
                lines.addAll(java.nio.file.Files.readAllLines(file.toPath(), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        } catch (IOException | RuntimeException e) {
            lines.clear();
        }
        java.util.Map<String, String> left = new java.util.LinkedHashMap<>(values);
        int start = -1;
        int end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("[") && t.endsWith("]")) {
                if (start >= 0) {
                    end = i;
                    break;
                }
                if (t.substring(1, t.length() - 1).equalsIgnoreCase(section)) {
                    start = i;
                }
            }
        }
        if (start < 0) {
            lines.add("[" + section + "]");
            start = lines.size() - 1;
            end = lines.size();
        }
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            for (java.util.Map.Entry<String, String> e : values.entrySet()) {
                if (e.getKey().equalsIgnoreCase(key)) {
                    lines.set(i, e.getKey() + "=" + e.getValue());
                    left.remove(e.getKey());
                }
            }
        }
        int at = end;
        for (java.util.Map.Entry<String, String> e : left.entrySet()) {
            lines.add(at++, e.getKey() + "=" + e.getValue());
        }
        file.getParentFile().mkdirs();
        try (java.io.Writer out = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.ISO_8859_1)) {
            for (String line : lines) {
                out.write(line);
                out.write("\n");
            }
        } catch (IOException ignored) {
        }
    }

    private String[] pc98PadMap() {
        String[] map = PC98_PAD_DEFAULTS.clone();
        String saved = MainActivity.sharedSettings(this).getString(pc98Key("pad"), null);
        if (saved != null) {
            for (String part : saved.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String button = part.substring(0, eq);
                for (int k = 0; k < PC98_PAD_NAMES.length; k++) {
                    if (PC98_PAD_NAMES[k].equals(button)) {
                        map[k] = part.substring(eq + 1);
                    }
                }
            }
        }
        return map;
    }

    private String pc98PadSummary() {
        String[] map = pc98PadMap();
        return "A " + map[0] + " · B " + map[1] + " · X " + map[2] + " · Y " + map[3];
    }

    // A connected gamepad as keys: the D-pad on the arrow keys, the buttons on the keys picked
    // for this game (NP2kai's own pad option is off, see sendPc98Options).
    private void sendPc98Pad(long handle, int mask) {
        int changed = mask ^ this.pc98PadSent;
        if (changed == 0 || handle == 0) {
            this.pc98PadSent = mask;
            return;
        }
        int[] arrowBits = {16, 32, 64, 128};
        int[] arrowCodes = {275, 276, 273, 274};
        for (int k = 0; k < arrowBits.length; k++) {
            if ((changed & arrowBits[k]) != 0) {
                NativeGba.dosKey(handle, arrowCodes[k], (mask & arrowBits[k]) != 0, 0);
            }
        }
        String[] map = pc98PadMap();
        for (int k = 0; k < PC98_PAD_BITS.length; k++) {
            int bits = PC98_PAD_BITS[k];
            boolean was = (this.pc98PadSent & bits) != 0;
            boolean now = (mask & bits) != 0;
            int code = DosControls.code(map[k]);
            if (was != now && code != 0) {
                NativeGba.dosKey(handle, code, now, code < 128 ? code : 0);
            }
        }
        this.pc98PadSent = mask;
    }

    private void showPc98PadDialog(android.app.Dialog menu) {
        String[] map = pc98PadMap();
        String[] names = new String[PC98_PAD_NAMES.length];
        String[] notes = new String[PC98_PAD_NAMES.length];
        for (int k = 0; k < names.length; k++) {
            names[k] = PC98_PAD_NAMES[k] + " → " + map[k];
            notes[k] = k == 4 || k == 5 ? "L1 / R1" : "";
        }
        showChoiceDialog(RoundIconView.PAD, RoundIconView.COLOR_PAD, "게임패드 → 키 (이 게임)", names, notes, 0,
                "연결한 게임패드의 버튼을 PC-98 키로 바꿔 보내요. 방향 패드는 방향키예요. 바꿀 버튼을 고르세요.",
                Color.rgb(248, 250, 252), Color.rgb(71, 85, 105), "키 고르기", button -> {
                    int now = java.util.Arrays.asList(PC98_PAD_KEYS).indexOf(map[button]);
                    showChoiceDialog(RoundIconView.PAD, RoundIconView.COLOR_PAD, PC98_PAD_NAMES[button] + " 버튼", PC98_PAD_KEYS, emptyNotes(PC98_PAD_KEYS.length),
                            Math.max(0, now), "기본은 A Z · B X · X Enter · Y Space · L F1 · R F2 · START Esc · SELECT F10이에요.",
                            Color.rgb(248, 250, 252), Color.rgb(71, 85, 105), "바꾸기", key -> {
                                long handle = this.nativeHandle;
                                if (handle != 0) {
                                    sendPc98Pad(handle, 0);   // let go of what the old key held
                                }
                                map[button] = PC98_PAD_KEYS[key];
                                StringBuilder sb = new StringBuilder();
                                for (int k = 0; k < map.length; k++) {
                                    sb.append(k == 0 ? "" : ",").append(PC98_PAD_NAMES[k]).append('=').append(map[k]);
                                }
                                MainActivity.sharedSettings(this).edit().putString(pc98Key("pad"), sb.toString()).apply();
                                showPc98PadDialog(menu);
                            });
                });
    }

    private void showPc98SoundDialog(android.app.Dialog menu) {
        showChoiceDialog(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "사운드 보드", PC98_SOUND_NAMES, PC98_SOUND_NOTES, pc98Sound(),
                "게임 상자나 설명서에 적힌 음원에 맞추면 돼요. 모르면 86 그대로 두세요.\n"
                        + "드럼 소리(리듬 음원)는 직접 가진 기계에서 뽑은 2608_*.wav 파일을 바이오스 폴더(np2kai)에 넣으면 나와요.\n"
                        + "바꾸면 게임이 처음부터 다시 켜져요.",
                Color.rgb(255, 251, 235), Color.rgb(146, 64, 14), "적용 후 다시 켜기", choice -> {
                    if (choice == pc98Sound()) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt(pc98Key("sound"), choice)
                            .remove(pc98Key("fdd1")).remove(pc98Key("fdd2")).apply();
                    sendPc98Options(null);
                    if (menu != null) {
                        menu.dismiss();
                    }
                    // The core takes the new board when it next reads its options (a frame
                    // away), then starts the machine over.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        long handle = this.nativeHandle;
                        if (handle != 0 && !this.destroyed) {
                            NativeGba.reset(handle);
                        }
                    }, 300);
                });
    }

    // ---- MSX · 재믹스 (blueMSX) ----
    private static final int REQUEST_MSX_BIOS = 3110;
    private static final int REQUEST_MSX_SLOT2 = 3111;
    private static final String[] MSX_TYPE_NAMES = {"자동", "MSX1", "MSX2", "MSX2+", "turbo R"};
    private static final String[] MSX_TYPE_VALUES = {"Auto", "MSX", "MSX2", "MSX2+", "MSXturboR"};
    private static final String[] MSX_TYPE_NOTES = {"파일을 보고 골라요 (추천) · 원래 바이오스가 없으면 롬팩은 C-BIOS, 디스크는 XBIOS로",
            "1983년 첫 MSX", "1985년 · 그래픽이 좋아진 2세대", "1988년", "1990년 마지막 세대 · 원래 바이오스 필요"};
    private static final String[] MSX_BIOS_NAMES = {"자동 (추천)", "원래 바이오스", "C-BIOS", "XBIOS"};
    private static final String[] MSX_BIOS_NOTES = {"원래 바이오스가 있으면 그것 · 없으면 롬팩은 C-BIOS, 디스크는 XBIOS",
            "직접 가진 기계에서 뽑은 파일 · 모든 게임 · 테이프 · BASIC", "앱에 포함 · 롬팩만",
            "앱에 포함 · 롬팩 · 디스크(MSX-DOS 1) · SCREEN 9 한글 · 파이썬(XPY) · BASIC 없음"};
    // The blueMSX machine this run started (a machine name or "Auto"), and whether 자동 put
    // a disk game on XBIOS because the original BIOS is missing.
    private volatile String msxMachine = "Auto";
    private volatile boolean msxAutoXbios;
    private View msxRunView;
    private MsxKeys msxKeys;
    private View msxKeyboardView;
    // The game picked up from its automatic save (then the tape is already loaded).
    private volatile boolean msxResumed;

    private boolean isMsx() {
        return "bluemsx".equals(this.coreId);
    }

    private String msxKey(String name) {
        return "msx_" + name + ":" + this.saveBaseName;
    }

    private int msxInt(String name, int fallback) {
        return MainActivity.sharedSettings(this).getInt(msxKey(name), fallback);
    }

    private boolean msxBool(String name, boolean fallback) {
        return MainActivity.sharedSettings(this).getBoolean(msxKey(name), fallback);
    }

    private String msxString(String name, String fallback) {
        return MainActivity.sharedSettings(this).getString(msxKey(name), fallback);
    }

    // The keys on the screen's key rows: "KEY" or "KEY=label", comma separated.
    private static final String MSX_DEFAULT_KEYS = "ESC,F1,F2,F3,F4,F5,SPACE,RETURN=↵ 엔터,Y,N";
    private static final String[] MSX_PRESET_NAMES = {"기본", "코나미 (F1 일시정지)", "RPG (숫자 키)", "어드벤처 (Y/N · 엔터)"};
    private static final String[] MSX_PRESETS = {MSX_DEFAULT_KEYS,
            "ESC,F1=일시정지,F2,F3,F4,F5,SPACE,M,N,RETURN=↵",
            "ESC,1,2,3,4,5,SPACE,RETURN=↵ 엔터,Y,N",
            "ESC,F1,F2,F3,Y,N,RETURN=↵ 엔터,SPACE,BS,STOP"};
    private static final String[] MSX_ADDABLE = {"ESC", "F1", "F2", "F3", "F4", "F5", "STOP", "SELECT", "HOME", "INS", "DEL", "BS", "TAB",
            "RETURN", "SPACE", "Y", "N", "M", "Z", "X", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "GRAPH", "CTRL", "SHIFT", "↑", "↓", "←", "→"};

    private String[][] msxRowKeys() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String part : msxString("keys", MSX_DEFAULT_KEYS).split(",")) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            int eq = item.indexOf('=');
            String key = (eq > 0 ? item.substring(0, eq) : item).trim();
            String label = eq > 0 ? item.substring(eq + 1).trim() : key;
            if (MsxKeys.code(key) != 0) {
                out.add(new String[]{key, label.isEmpty() ? key : label});
            }
        }
        return out.toArray(new String[0][]);
    }

    private static String joinMsxKeys(java.util.List<String[]> keys) {
        StringBuilder text = new StringBuilder();
        for (String[] k : keys) {
            if (text.length() > 0) text.append(',');
            text.append(k[0]);
            if (!k[1].equals(k[0])) text.append('=').append(k[1].replace(",", " ").replace("=", " "));
        }
        return text.toString();
    }

    private String[][] msxSlice(String[][] keys, int from, int to) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int k = from; k < Math.min(to, keys.length); k++) {
            String[] key = keys[k];
            String weight = key[0].equals("SPACE") ? "2.2" : key[1].length() > 3 ? "1.4" : "1";
            out.add(new String[]{key[0], key[1], weight});
        }
        return out.toArray(new String[0][]);
    }

    private String[][] withKeyboardKey(String[][] keys) {
        boolean run = isXbiosExamples();
        String[][] out = java.util.Arrays.copyOf(keys, keys.length + (run ? 2 : 1));
        out[keys.length] = new String[]{"⌨", "⌨"};
        if (run) out[keys.length + 1] = new String[]{"▶", "▶ 예제", "1.6"};
        return out;
    }

    // ---- XBIOS example disk: run its XPY programs with a tap ----
    private static final String[][] XBIOS_PROGRAMS = {
            {"XMAZE", "3D 미로"}, {"XSHOT", "가로 슈팅"}, {"MZEBI", "세로 슈팅"}, {"XROAD", "차 피하기"}, {"XCLAIM", "땅따먹기"},
            {"XWAVE", "그래픽 데모"}, {"XROTO", "회전 데모"}, {"XPOLY", "3D 도형"}, {"MANDEL", "만델브로"}, {"BALLS", "스프라이트"},
            {"LIFE", "라이프 게임"}, {"SUNGJUK", "한글 성적표"}, {"SHEET", "한글 계산표"},
    };

    private TouchButton msxRunButton() {
        return new TouchButton(this, "▶ 예제", 0, (bit, down) -> {
            if (!down && this.msxRunView != null) {
                showMsxKeyboard(false);
                this.msxRunView.setVisibility(this.msxRunView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        }, TouchButton.STYLE_PINK);
    }

    private void addMsxRunPanel(FrameLayout frameLayout, boolean landscape) {
        if (!isXbiosExamples()) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(31, 33, 40));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), Color.rgb(15, 118, 110));
        panel.setBackground(bg);
        panel.setClickable(true);
        TextView title = new TextView(this);
        title.setText("빠른 실행 · 톡 하면 fs.load(\"이름.PY\")를 대신 입력 · 끝내려면 재시작");
        title.setTextColor(Color.rgb(204, 251, 241));
        title.setTextSize(11f);
        title.setPadding(dp(2), 0, 0, dp(6));
        panel.addView(title);
        int perRow = landscape ? 7 : 4;
        LinearLayout row = null;
        for (int k = 0; k <= XBIOS_PROGRAMS.length; k++) {
            if (k % perRow == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(landscape ? 34 : 40));
                rowParams.setMargins(0, k == 0 ? 0 : dp(5), 0, 0);
                panel.addView(row, rowParams);
            }
            TextView chip = new TextView(this);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(false);
            chip.setTextColor(Color.WHITE);
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setCornerRadius(dp(9));
            if (k == XBIOS_PROGRAMS.length) {
                chip.setText("닫기");
                chip.setTextSize(12f);
                chipBg.setColor(Color.rgb(15, 118, 110));
                chip.setOnClickListener(v -> panel.setVisibility(View.GONE));
            } else {
                final String name = XBIOS_PROGRAMS[k][0];
                chip.setText(name + "\n" + XBIOS_PROGRAMS[k][1]);
                chip.setTextSize(10.5f);
                chipBg.setColor(Color.rgb(47, 51, 64));
                chip.setOnClickListener(v -> {
                    panel.setVisibility(View.GONE);
                    msxTyper.removeCallbacksAndMessages(null);
                    msxTypeText("fs.load(\"" + name + ".PY\")", true);
                });
            }
            chip.setBackground(chipBg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
            params.setMargins(k % perRow == 0 ? 0 : dp(5), 0, 0, 0);
            row.addView(chip, params);
        }
        int rest = (XBIOS_PROGRAMS.length + 1) % perRow;
        if (rest != 0) {
            for (int k = rest; k < perRow; k++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1f);
                params.setMargins(dp(5), 0, 0, 0);
                row.addView(new View(this), params);
            }
        }
        FrameLayout.LayoutParams params = landscape ? new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM) : new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        if (landscape) {
            params.setMargins(dp(96), 0, dp(96), dp(2));
        } else {
            params.setMargins(dp(6), dp(34), dp(6), 0);
        }
        panel.setVisibility(View.GONE);
        frameLayout.addView(panel, params);
        this.msxRunView = panel;
    }

    // ---- 조작 방식: the pad as the joystick, or as keyboard keys (cursor keys and two keys) ----
    private int msxSentBits;

    private boolean msxKeyboardControl() {
        return isMsx() && msxInt("control", 0) == 1;
    }

    // Keyboard mode: the D-pad and A / B become keys; the joystick port sees nothing.
    private int msxFilterInput(int mask) {
        if (!msxKeyboardControl()) {
            return mask;
        }
        int[] bits = {16, 32, 64, 128, 1, 2};
        int[] codes = {275, 276, 273, 274, MsxKeys.code(msxString("key_a", "SPACE")), MsxKeys.code(msxString("key_b", "M"))};
        int keyBits = 16 | 32 | 64 | 128 | 1 | 2;
        int now = mask & keyBits;
        int changed = now ^ this.msxSentBits;
        for (int k = 0; k < bits.length; k++) {
            if ((changed & bits[k]) != 0) {
                MsxKeys.send(this.nativeHandle, codes[k], (now & bits[k]) != 0);
            }
        }
        this.msxSentBits = now;
        return mask & ~keyBits;
    }

    // ---- 테이프 자동 실행 ----
    private static final String[] MSX_TAPE_NAMES = {"자동 (추천)", "RUN\"CAS:\"", "BLOAD\"CAS:\",R", "CLOAD → RUN", "직접 입력"};
    private static final String[] MSX_TAPE_NOTES = {"테이프 첫 블록(BASIC · 기계어 · 텍스트)을 보고 골라요", "텍스트(ASCII)로 저장된 BASIC 게임",
            "기계어 게임 (가장 흔함)", "BASIC으로 저장된 게임", "명령을 직접 적어요"};
    private final android.os.Handler msxTyper = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean isMsxTape() {
        return isMsx() && this.romPath != null && this.romPath.toLowerCase(java.util.Locale.ROOT).endsWith(".cas");
    }

    // The command for this tape: CAS files start their first block with 1F A6 DE BA CC 13 7D 74,
    // then ten bytes of D3 (BASIC), EA (ASCII text) or D0 (machine code).
    private String msxTapeCommand() {
        int mode = msxInt("tape", 0);
        if (mode == 1) return "RUN\"CAS:\"";
        if (mode == 2) return "BLOAD\"CAS:\",R";
        if (mode == 3) return "CLOAD\nRUN";
        if (mode == 4) return msxString("tape_custom", "RUN\"CAS:\"");
        int kind = 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream(this.romPath)) {
            byte[] head = new byte[18];
            if (in.read(head) == 18 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0xA6) {
                kind = head[8] & 0xFF;
            }
        } catch (java.io.IOException ignored) {
        }
        return kind == 0xD3 ? "CLOAD\nRUN" : kind == 0xD0 ? "BLOAD\"CAS:\",R" : "RUN\"CAS:\"";
    }

    // Japanese-layout machines (MSX2+, turbo R: the default 자동 starts an MSX2+) type " as
    // SHIFT+2 and have their own : key; the international ones put " and : on SHIFT.
    private boolean msxJapaneseLayout() {
        if (this.msxMachine.startsWith("XBIOS")) return false;
        int type = msxType();
        return type == 0 || type == 3 || type == 4;
    }

    private void msxTypeText(String text) {
        msxTypeText(text, false);
    }

    // exactCase: capitals with SHIFT (XPY's file names); BASIC takes either.
    private void msxTypeText(String text, boolean exactCase) {
        long delay = 0;
        boolean jis = msxJapaneseLayout();
        for (char c : text.toCharArray()) {
            int code;
            boolean shift = false;
            if (c == '\n') {
                code = 13;
            } else if (c == '"') {
                code = jis ? '2' : 39;
                shift = true;
            } else if (c == ':') {
                code = jis ? 39 : 59;
                shift = !jis;
            } else if (c == '(' || c == ')') {
                code = jis ? (c == '(' ? '8' : '9') : (c == '(' ? '9' : '0');
                shift = true;
            } else if (c < 128 && Character.isLetter(c)) {
                code = Character.toLowerCase(c);
                shift = exactCase && Character.isUpperCase(c);
            } else if (c == ' ' || c == ',' || c == '.' || c == '/' || Character.isDigit(c)) {
                code = c;
            } else {
                continue;
            }
            final int key = code;
            final boolean withShift = shift;
            msxTyper.postDelayed(() -> {
                if (withShift) MsxKeys.send(this.nativeHandle, 304, true);
                MsxKeys.send(this.nativeHandle, key, true);
            }, delay);
            msxTyper.postDelayed(() -> {
                MsxKeys.send(this.nativeHandle, key, false);
                if (withShift) MsxKeys.send(this.nativeHandle, 304, false);
            }, delay + 70);
            delay += 150;
            if (c == '\n') delay += 1200;
        }
        // RETURN after the last line.
        final long end = delay;
        msxTyper.postDelayed(() -> MsxKeys.send(this.nativeHandle, 13, true), end);
        msxTyper.postDelayed(() -> MsxKeys.send(this.nativeHandle, 13, false), end + 80);
    }

    // After the machine has booted to BASIC's "Ok", type the tape's command.
    private void msxAutoStartTape(boolean afterReset) {
        if (!isMsxTape()) return;
        if (msxBiosCount() == 0 || this.msxMachine.startsWith("XBIOS") || this.msxMachine.contains("C-BIOS")) {
            Toast.makeText(this, "테이프 게임은 원래 바이오스가 있어야 해요 (C-BIOS는 롬팩만). 메뉴 → MSX 바이오스", Toast.LENGTH_LONG).show();
            return;
        }
        msxTyper.removeCallbacksAndMessages(null);
        String command = msxTapeCommand();
        long boot = msxJapaneseLayout() ? 9000 : 6000;
        Toast.makeText(this, "📼 테이프 불러오기: " + command.replace("\n", " → ") + " 자동 입력", Toast.LENGTH_LONG).show();
        msxTyper.postDelayed(() -> msxTypeText(command), boot);
    }


    private int msxType() {
        int value = MainActivity.sharedSettings(this).getInt("msx_type:" + this.saveBaseName, 0);
        return value >= 0 && value < MSX_TYPE_VALUES.length ? value : 0;
    }

    private int msxBiosChoice() {
        int value = msxInt("bios", isXbiosExamples() ? 3 : 0);
        return value >= 0 && value < MSX_BIOS_NAMES.length ? value : 0;
    }

    private boolean isXbiosExamples() {
        return this.romPath != null && new File(this.romPath).getName().equals(MainActivity.XBIOS_EXAMPLES_NAME);
    }

    private boolean isMsxDisk() {
        String name = this.romPath == null ? "" : this.romPath.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".dsk") || name.endsWith(".m3u");
    }

    // XBIOS ships machines for MSX1, MSX2 and MSX2+ (자동 and turbo R take the MSX2+ one).
    private String msxXbiosMachine() {
        int type = msxType();
        return type == 1 ? "XBIOS 1 XPY" : type == 2 ? "XBIOS 2 XPY" : "XBIOS 2+ XPY";
    }

    private String msxCbiosMachine() {
        int type = msxType();
        return type == 1 ? "MSX - C-BIOS" : type == 2 ? "MSX2 - C-BIOS" : "MSX2+ - C-BIOS";
    }

    // Whether every ROM a blueMSX machine names is on disk ("Machines/..." paths).
    private boolean msxMachineValid(String name) {
        File machines = new File(new File(getFilesDir(), "system"), "Machines");
        File config = new File(new File(machines, name), "config.ini");
        if (!config.isFile()) return false;
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(config))) {
            String line;
            while ((line = in.readLine()) != null) {
                int open = line.indexOf('"');
                int close = open < 0 ? -1 : line.indexOf('"', open + 1);
                if (close <= open + 1) continue;
                String path = line.substring(open + 1, close).replace('\\', '/');
                if (!path.regionMatches(true, 0, "Machines/", 0, 9)) continue;
                if (!new File(machines, path.substring(9)).isFile()) return false;
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return true;
    }

    private String msxPickMachine() {
        this.msxAutoXbios = false;
        String chosen = MSX_TYPE_VALUES[msxType()];
        switch (msxBiosChoice()) {
            case 1:
                return chosen;
            case 2:
                return msxCbiosMachine();
            case 3:
                return msxXbiosMachine();
            default:
                if (isMsxDisk() && !msxMachineValid("Auto".equals(chosen) ? "MSX2+" : chosen)) {
                    this.msxAutoXbios = true;
                    return msxXbiosMachine();
                }
                return chosen;
        }
    }

    private File msxSharedRoms() {
        return new File(new File(new File(getFilesDir(), "system"), "Machines"), "Shared Roms");
    }

    private int msxBiosCount() {
        File[] files = msxSharedRoms().listFiles();
        int count = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.length() > 0) count++;
            }
        }
        return count;
    }

    private MsxKeys msxKeys() {
        if (this.msxKeys == null) {
            this.msxKeys = new MsxKeys(this, new MsxKeys.Host() {
                @Override
                public long handle() {
                    return GameActivity.this.nativeHandle;
                }

                @Override
                public int dp(int value) {
                    return GameActivity.this.dp(value);
                }

                @Override
                public void closeKeyboard() {
                    showMsxKeyboard(false);
                }
            });
        }
        return this.msxKeys;
    }

    // A key of the MSX keyboard as a pad button (rows of often used keys).
    private TouchButton msxKeyButton(String label, String key) {
        int code = MsxKeys.code(key);
        TouchButton button = new TouchButton(this, label, MsxKeys.bitFor(code),
                (bit, down) -> MsxKeys.send(this.nativeHandle, MsxKeys.codeOfBit(bit), down), TouchButton.STYLE_DARK);
        return button;
    }

    // The ⌨ key: opens the full keyboard over the controls.
    private TouchButton msxKeyboardButton() {
        TouchButton button = new TouchButton(this, "⌨", 0, (bit, down) -> {
            if (!down) showMsxKeyboard(true);
        }, TouchButton.STYLE_PINK);
        return button;
    }

    private void showMsxKeyboard(boolean show) {
        if (show && this.msxRunView != null) this.msxRunView.setVisibility(View.GONE);
        if (this.msxKeyboardView != null) {
            this.msxKeyboardView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (!show && this.msxKeys != null) {
            this.msxKeys.releaseAll();
        }
    }

    // A row of keys: weights give the wide ones (SPACE) more room.
    private LinearLayout msxKeyRow(String[][] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String[] k : keys) {
            View view = k[0].equals("⌨") ? msxKeyboardButton() : k[0].equals("▶") ? msxRunButton() : msxKeyButton(k[1], k[0]);
            float weight = k.length > 2 ? Float.parseFloat(k[2]) : 1f;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
            params.setMargins(dp(2), 0, dp(2), 0);
            row.addView(view, params);
        }
        return row;
    }

    private void addMsxRow(FrameLayout frameLayout, String[][] keys, int gravity, int leftDp, int rightDp, int topDp, int bottomDp, int widthDp, int heightDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(widthDp > 0 ? dp(widthDp) : -1, dp(heightDp), gravity);
        params.setMargins(dp(leftDp), dp(topDp), dp(rightDp), dp(bottomDp));
        frameLayout.addView(msxKeyRow(keys), params);
    }

    // The full keyboard, hidden until ⌨ (portrait: the controls' area; landscape: the bottom).
    private void addMsxKeyboard(FrameLayout frameLayout, boolean landscape) {
        View board = msxKeys().keyboard(landscape ? 30 : 36);
        FrameLayout.LayoutParams params = landscape
                ? new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
                : new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        if (landscape) {
            params.setMargins(dp(96), 0, dp(96), dp(2));
        } else {
            params.setMargins(dp(6), dp(2), dp(6), 0);
        }
        board.setVisibility(View.GONE);
        frameLayout.addView(board, params);
        this.msxKeyboardView = board;
    }

    // Portrait: often used keys on top, the MSX joystick (D-pad, triggers A and B), SPACE ·
    // RETURN · Y · N, and STOP / GRAPH at the bottom with the speed button in between.
    private void addMsxControls(FrameLayout frameLayout) {
        String[][] keys = msxRowKeys();
        addMsxRow(frameLayout, withKeyboardKey(msxSlice(keys, 0, 6)), Gravity.TOP, 10, 10, 0, 0, 0, 30);
        addFastForwardButton(frameLayout);
        addDPadAt(frameLayout, 22, 100, 136);
        addRoundButton(frameLayout, "A", 1, true, 128, 150, 60, TouchButton.STYLE_ARCADE_RED);
        addRoundButton(frameLayout, "B", 2, true, 58, 206, 60, TouchButton.STYLE_ARCADE_RED);
        if (keys.length > 6) {
            addMsxRow(frameLayout, msxSlice(keys, 6, 10), Gravity.BOTTOM, 22, 22, 0, 52, 0, 34);
        }
        addMsxRow(frameLayout, new String[][]{{"STOP", "STOP"}}, Gravity.BOTTOM | Gravity.START, 22, 0, 0, 10, 96, 32);
        addMsxRow(frameLayout, new String[][]{{"GRAPH", "GRAPH"}}, Gravity.BOTTOM | Gravity.END, 0, 22, 0, 10, 96, 32);
        addMsxKeyboard(frameLayout, false);
        addMsxRunPanel(frameLayout, false);
    }

    // Pause menu (기본): machine type, the player's own BIOS ROMs, the next disk.
    // ---- 아케이드 한글패치 (FBNeo IPS): <system>/fbneo/ips/<set>, on or off per game ----
    // ---- FBNeo .ini cheats (0.0.316): the file goes to <system>/fbneo/cheats/<set>.ini, the
    // core makes each cheat an option ("fbneo-cheat-..."), and the app picks their values. A
    // pick is sent at once (the core applies cheats when options change) and kept per game. ----
    private static final int REQUEST_FBNEO_INI = 3112;

    private File fbneoIniFile() {
        String name = new File(this.romPath).getName();
        int dot = name.lastIndexOf('.');
        return new File(new File(new File(new File(getFilesDir(), "system"), "fbneo"), "cheats"), (dot > 0 ? name.substring(0, dot) : name) + ".ini");
    }

    private String fbneoPrefKey() {
        return "fbcheats:" + this.saveBaseName;
    }

    // Saved picks: "key\tindex" lines.
    private java.util.Map<String, Integer> fbneoPicks() {
        java.util.Map<String, Integer> picks = new java.util.LinkedHashMap<>();
        for (String line : MainActivity.sharedSettings(this).getString(fbneoPrefKey(), "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                try {
                    picks.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return picks;
    }

    private void saveFbneoPicks(java.util.Map<String, Integer> picks) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : picks.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        MainActivity.sharedSettings(this).edit().putString(fbneoPrefKey(), sb.toString()).apply();
    }

    private void sendFbneoCheats() {
        java.util.Map<String, Integer> picks = "fbneo".equals(this.coreId) ? fbneoPicks() : new java.util.HashMap<>();
        String[] keys = new String[picks.size()];
        String[] values = new String[picks.size()];
        int k = 0;
        for (java.util.Map.Entry<String, Integer> e : picks.entrySet()) {
            keys[k] = e.getKey();
            values[k] = "#" + e.getValue();
            k++;
        }
        NativeGba.setCoreOptions(keys, values, true);
    }

    private static final class FbneoCheat {
        String key;
        String name;
        String[] choices;
        int now;
    }

    private java.util.List<FbneoCheat> fbneoCheats() {
        java.util.List<FbneoCheat> list = new java.util.ArrayList<>();
        if (this.nativeHandle == 0) {
            return list;
        }
        String[] raw = NativeGba.coreOptions(this.nativeHandle, "fbneo-cheat-");
        for (int i = 0; raw != null && i + 3 < raw.length; i += 4) {
            FbneoCheat cheat = new FbneoCheat();
            cheat.key = raw[i];
            // "[Cheat][file] Heading Name" -> "Heading Name"
            cheat.name = raw[i + 1].replaceFirst("^\\[Cheat\\]\\[[^\\]]*\\]\\s*", "").trim();
            cheat.choices = raw[i + 2].split("\\|");
            String now = raw[i + 3];
            cheat.now = 0;
            if (now.startsWith("#")) {
                try {
                    cheat.now = Integer.parseInt(now.substring(1));
                } catch (NumberFormatException ignored) {
                }
            } else {
                for (int c = 0; c < cheat.choices.length; c++) {
                    if (cheat.choices[c].equals(now)) {
                        cheat.now = c;
                    }
                }
            }
            list.add(cheat);
        }
        return list;
    }

    // "1 - Enabled" -> "Enabled"
    private static String fbneoChoiceName(String choice) {
        return choice.replaceFirst("^\\d+\\s*-\\s*", "");
    }

    private String fbneoCheatSummary() {
        if (!fbneoIniFile().isFile()) {
            return "없음 · 넣기";
        }
        java.util.List<FbneoCheat> cheats = fbneoCheats();
        int on = 0;
        for (FbneoCheat cheat : cheats) {
            if (cheat.now != 0) {
                on++;
            }
        }
        return cheats.size() + "개 · " + on + "개 켜짐";
    }

    private void pickFbneoIni() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_FBNEO_INI);
        } catch (Exception e) {
            Toast.makeText(this, "파일을 고르는 화면을 열 수 없어요.", Toast.LENGTH_SHORT).show();
        }
    }

    private void importFbneoIni(android.net.Uri uri) {
        File target = fbneoIniFile();
        File part = new File(target.getPath() + ".part");
        try {
            target.getParentFile().mkdirs();
            try (java.io.InputStream in = getContentResolver().openInputStream(uri); java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                if (in == null) {
                    throw new java.io.IOException("열 수 없어요");
                }
                byte[] buffer = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                    if (total > 4L << 20) {
                        throw new java.io.IOException("치트 파일이 너무 커요");
                    }
                }
            }
            String head = new String(java.nio.file.Files.readAllBytes(part.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!head.toLowerCase(Locale.ROOT).contains("cheat")) {
                part.delete();
                Toast.makeText(this, "FBNeo 치트 파일(.ini)이 아닌 것 같아요 (cheat \"이름\" 줄이 없어요).", Toast.LENGTH_LONG).show();
                return;
            }
            if (!part.renameTo(target)) {
                throw new java.io.IOException("저장하지 못했어요");
            }
        } catch (java.io.IOException e) {
            part.delete();
            Toast.makeText(this, "치트 파일을 넣지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        // The core reads cheats when the game starts: start again from this scene.
        MainActivity.sharedSettings(this).edit().remove(fbneoPrefKey()).apply();
        Toast.makeText(this, "치트 파일을 넣었어요 · 게임을 다시 켜서 읽어요", Toast.LENGTH_LONG).show();
        relaunchGame();
    }

    private void showFbneoCheatSheet(android.app.Dialog menu) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(roundRect(Color.WHITE, dp(24)));
        TextView title = new TextView(this);
        title.setText("FBNeo 치트 (.ini)");
        title.setTextSize(17f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(fbneoIniFile().getName() + (fbneoIniFile().isFile() ? "" : " · 아직 없음"));
        sub.setTextSize(12f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        panel.addView(sub);
        java.util.List<FbneoCheat> cheats = fbneoIniFile().isFile() ? fbneoCheats() : new java.util.ArrayList<>();
        if (cheats.isEmpty()) {
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.setMargins(0, dp(10), 0, 0);
            panel.addView(dcNote(fbneoIniFile().isFile()
                    ? "이 파일에서 이 게임의 치트를 찾지 못했어요. 파일 이름이 롬 이름(" + fbneoIniFile().getName() + ")과 맞는지, FBNeo 형식(cheat \"이름\" · 0 \"끄기\" · 1 \"켜기\", 0, 주소, 값)인지 확인해 주세요."
                    : "FBNeo 형식 치트 파일(<롬 이름>.ini)을 넣으면 그 안의 치트가 여기 목록으로 나와요. 선택지가 여럿인 치트(캐릭터 고르기 등)도 돼요. 넣으면 게임을 다시 켜서 읽어요.",
                    Color.rgb(255, 251, 235), Color.rgb(146, 64, 14)), noteParams);
        }
        android.widget.ScrollView listScroll = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(1);
        listScroll.addView(list);
        for (FbneoCheat cheat : cheats) {
            LinearLayout row = createSettingRow(RoundIconView.CHEAT, cheat.now != 0 ? RoundIconView.COLOR_CHEAT : Color.rgb(156, 163, 175), cheat.name,
                    fbneoChoiceName(cheat.choices[Math.min(cheat.now, cheat.choices.length - 1)]), view -> {
                        String[] names = new String[cheat.choices.length];
                        String[] notes = new String[cheat.choices.length];
                        for (int c = 0; c < names.length; c++) {
                            names[c] = fbneoChoiceName(cheat.choices[c]);
                            notes[c] = c == 0 ? "원래대로" : "";
                        }
                        showChoiceDialog(RoundIconView.CHEAT, RoundIconView.COLOR_CHEAT, cheat.name, names, notes, cheat.now,
                                "바로 적용돼요. 이 게임에 기억해서 다음에 켤 때도 그대로예요.", Color.rgb(255, 251, 235), Color.rgb(146, 64, 14), "적용", choice -> {
                                    java.util.Map<String, Integer> picks = fbneoPicks();
                                    if (choice == 0) {
                                        picks.remove(cheat.key);
                                    } else {
                                        picks.put(cheat.key, choice);
                                    }
                                    saveFbneoPicks(picks);
                                    NativeGba.setCoreOptions(new String[]{cheat.key}, new String[]{"#" + choice}, false);
                                    dialog.dismiss();
                                    showFbneoCheatSheet(menu);
                                });
                    });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(52));
            rowParams.setMargins(0, dp(6), 0, 0);
            list.addView(row, rowParams);
        }
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, cheats.size() > 6 ? dp(360) : -2);
        listParams.setMargins(0, dp(8), 0, 0);
        panel.addView(listScroll, listParams);
        LinearLayout buttons = new LinearLayout(this);
        TextView close = dcChoice("닫기", "", false);
        close.setText("닫기");
        close.setTextSize(15f);
        close.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView pick = dcChoice("", "", true);
        pick.setText(fbneoIniFile().isFile() ? ".ini 바꾸기" : ".ini 넣기");
        pick.setTextSize(15f);
        pick.setBackground(roundRect(RoundIconView.COLOR_CHEAT, dp(11)));
        pick.setOnClickListener(v -> {
            dialog.dismiss();
            if (menu != null) {
                menu.dismiss();
            }
            pickFbneoIni();
        });
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(0, dp(48), 1.4f);
        pickParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(pick, pickParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(420)), -2);
        }
        dialog.show();
    }

    private File arcadePatchDir() {
        String name = new File(this.romPath).getName();
        int dot = name.lastIndexOf('.');
        return new File(new File(new File(new File(getFilesDir(), "system"), "fbneo"), "ips"), dot > 0 ? name.substring(0, dot) : name);
    }

    private boolean arcadePatchOn() {
        return arcadePatchDir().isDirectory() && MainActivity.sharedSettings(this).getBoolean("arcips:" + new File(this.romPath).getName(), true);
    }

    private void addArcadePatchRows(LinearLayout panel, android.app.Dialog dialog) {
        final String key = "arcips:" + new File(this.romPath).getName();
        boolean on = arcadePatchOn();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.PATCH, RoundIconView.COLOR_PATCH, "한글패치", on ? "켜짐" : "꺼짐", view ->
                showChoiceDialog(RoundIconView.PATCH, RoundIconView.COLOR_PATCH, "한글패치", new String[]{"켜기", "끄기"},
                        new String[]{"넣은 한글패치를 덧입혀 켜요", "원본 그대로 켜요"}, on ? 0 : 1,
                        "켜거나 끄면 게임이 다시 켜지고, 이어하기 대신 처음부터 시작해요 (롬 내용이 달라서). 빠른 저장은 그대로 남아요.",
                        Color.rgb(245, 243, 255), Color.rgb(76, 29, 149), "적용 (다시 시작)", choice -> {
                            if ((choice == 0) == on) return;
                            MainActivity.sharedSettings(this).edit().putBoolean(key, choice == 0)
                                    .putBoolean("fresh_start:" + this.saveBaseName, true).apply();
                            dialog.dismiss();
                            relaunchGame();
                        })), params);
    }

    private void addMsxMenuRows(LinearLayout panel, android.app.Dialog dialog) {
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(-1, dp(52));
        typeParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "MSX 기종", MSX_TYPE_NAMES[msxType()], view -> {
            showChoiceDialog(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "MSX 기종", MSX_TYPE_NAMES, MSX_TYPE_NOTES, msxType(),
                    "이 게임에만 적용돼요. 바꾸면 게임을 다시 시작해요 (빠른 저장으로 이어서).\n원래 바이오스가 없는 기종은 C-BIOS로 돌아가요 (롬팩 게임만).",
                    Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용", choice -> {
                        if (choice == msxType()) return;
                        MainActivity.sharedSettings(this).edit().putInt("msx_type:" + this.saveBaseName, choice).apply();
                        dialog.dismiss();
                        relaunchGame();
                    });
        }), typeParams);
        int bios = msxBiosCount();
        LinearLayout.LayoutParams biosParams = new LinearLayout.LayoutParams(-1, dp(52));
        biosParams.setMargins(0, dp(8), 0, 0);
        String biosShown = MSX_BIOS_NAMES[msxBiosChoice()] + (this.msxMachine.startsWith("XBIOS") ? " → XBIOS"
                : this.msxMachine.contains("C-BIOS") ? " → C-BIOS" : "");
        panel.addView(createSettingRow(RoundIconView.IMPORT, RoundIconView.COLOR_CARD, "MSX 바이오스", biosShown,
                view -> showChoiceDialog(RoundIconView.IMPORT, RoundIconView.COLOR_CARD, "MSX 바이오스 · 이 게임만", MSX_BIOS_NAMES, MSX_BIOS_NOTES, msxBiosChoice(),
                        "넣은 원래 바이오스: " + (bios > 0 ? bios + "개" : "없음") + "\n테이프 게임과 BASIC으로 시작하는 디스크 게임은 원래 바이오스가 있어야 해요. XBIOS는 MSX1 · MSX2 · MSX2+ 구성이 있어요 (turbo R은 MSX2+로).\n\n"
                                + "XBIOS © 2026 GHOST · CC BY-NC-ND 4.0 · 고치지 않고 그대로 포함",
                        Color.rgb(255, 241, 242), Color.rgb(159, 18, 57), "적용 (다시 시작)", choice -> {
                            MainActivity.sharedSettings(this).edit().putInt(msxKey("bios"), choice).apply();
                            dialog.dismiss();
                            relaunchGame();
                        })), biosParams);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, dp(52));
        importParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.IMPORT, RoundIconView.COLOR_CARD, "바이오스 상태 · 넣기", msxBiosSummary(),
                view -> showMsxBiosDialog()), importParams);
        if (isMsxTape()) {
            LinearLayout.LayoutParams tapeParams = new LinearLayout.LayoutParams(-1, dp(52));
            tapeParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.PLAY, RoundIconView.COLOR_LOAD, "테이프 시작", MSX_TAPE_NAMES[Math.max(0, Math.min(4, msxInt("tape", 0)))],
                    view -> showMsxTapeDialog(dialog)), tapeParams);
        }
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(-1, dp(52));
        controlParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.PAD, RoundIconView.COLOR_PAD, "조작 방식",
                msxKeyboardControl() ? "키보드 (A=" + msxString("key_a", "SPACE") + " · B=" + msxString("key_b", "M") + ")" : "조이스틱",
                view -> showMsxControlDialog()), controlParams);
        LinearLayout.LayoutParams keysParams = new LinearLayout.LayoutParams(-1, dp(52));
        keysParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.EDIT, RoundIconView.COLOR_EDIT, "키 줄 고르기",
                msxString("keys", MSX_DEFAULT_KEYS).equals(MSX_DEFAULT_KEYS) ? "기본" : "바꾼 키 줄", view -> showMsxKeyEditor()), keysParams);
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(-1, dp(52));
        screenParams.setMargins(0, dp(8), 0, 0);
        String screenShown = (msxBool("sprites", true) ? "깜빡임 줄임" : "깜빡임 그대로") + (msxBool("fm", true) ? " · FM" : "");
        panel.addView(createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "MSX 화면 · 소리", screenShown, view -> showMsxScreenDialog(dialog)), screenParams);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(-1, dp(52));
        slotParams.setMargins(0, dp(8), 0, 0);
        String slot2 = msxSlot2Path();
        panel.addView(createSettingRow(RoundIconView.IMPORT, RoundIconView.COLOR_LOAD, "두 번째 롬팩 슬롯", slot2 == null ? "없음" : new File(slot2).getName(),
                view -> showMsxSlot2Dialog(dialog)), slotParams);
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(-1, dp(52));
        portParams.setMargins(0, dp(8), 0, 0);
        panel.addView(createSettingRow(RoundIconView.PAD, RoundIconView.COLOR_PAD, "2인용 (포트 2)", MSX_PORT2_NAMES[msxPort2()],
                view -> showMsxPort2Dialog()), portParams);
        if (!isMsxDisk() && !isMsxTape()) {
            LinearLayout.LayoutParams mapperParams = new LinearLayout.LayoutParams(-1, dp(52));
            mapperParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "롬 매퍼 (고급)", MSX_MAPPER_NAMES[msxMapper()],
                    view -> showMsxMapperDialog(dialog)), mapperParams);
        }
        int[] disk = NativeGba.diskInfo(this.nativeHandle);
        if (disk != null && disk.length == 2 && disk[1] > 1) {
            LinearLayout.LayoutParams diskParams = new LinearLayout.LayoutParams(-1, dp(52));
            diskParams.setMargins(0, dp(8), 0, 0);
            panel.addView(createSettingRow(RoundIconView.SWAP, RoundIconView.COLOR_LOAD, "디스크 바꾸기", (disk[0] + 1) + " / " + disk[1] + " → 다음", view -> {
                NativeGba.diskNext(this.nativeHandle);
                Toast.makeText(this, "디스크 " + ((disk[0] + 1) % disk[1] + 1) + "번으로 바꿨어요", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }), diskParams);
        }
    }

    // 테이프 시작: which command, typed after the machine boots (and again on demand).
    private void showMsxTapeDialog(android.app.Dialog menu) {
        showChoiceDialog(RoundIconView.PLAY, RoundIconView.COLOR_LOAD, "테이프 시작", MSX_TAPE_NAMES, MSX_TAPE_NOTES, Math.max(0, Math.min(4, msxInt("tape", 0))),
                "켤 때 테이프를 처음으로 감고, 기계가 BASIC(\"Ok\")까지 켜지면 명령을 대신 입력해요. 빠른 저장으로 이어서 켤 때는 입력하지 않아요.\n"
                        + "blueMSX는 테이프를 거의 바로 읽어서 따로 빨리 감지 않아도 돼요. 원래 바이오스가 있어야 해요 (C-BIOS는 롬팩만).",
                Color.rgb(239, 246, 255), Color.rgb(30, 64, 175), "적용 후 다시 불러오기", choice -> {
                    android.content.SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                    edit.putInt(msxKey("tape"), choice).apply();
                    Runnable reload = () -> {
                        if (menu != null) menu.dismiss();
                        long handle = this.nativeHandle;
                        if (handle != 0) NativeGba.reset(handle);
                        msxAutoStartTape(true);
                    };
                    if (choice != 4) {
                        reload.run();
                        return;
                    }
                    android.widget.EditText input = msxInput(msxString("tape_custom", "RUN\"CAS:\""), true);
                    LinearLayout body = new LinearLayout(this);
                    body.setOrientation(1);
                    body.addView(msxNote("예: RUN\"CAS:\" · BLOAD\"CAS:\",R · CLOAD\n영문 · 숫자 · \" : , . / ( ) 만 입력돼요. 여러 줄은 쓸 수 없어요.",
                            Color.rgb(248, 250, 252), Color.rgb(71, 85, 105)));
                    body.addView(input, marginTop(-1, dp(46), 10));
                    msxSheet(RoundIconView.PLAY, RoundIconView.COLOR_LOAD, "테이프 시작 명령", body, "적용 후 다시 불러오기", () -> {
                        MainActivity.sharedSettings(this).edit().putString(msxKey("tape_custom"), input.getText().toString().trim()).apply();
                        reload.run();
                    }, null, null);
                });
    }

    private static final String[] MSX_AB_KEYS = {"SPACE", "M", "N", "Z", "X", "RETURN", "SHIFT", "CTRL", "GRAPH", "ESC"};

    // 조작 방식: joystick or keyboard, and (keyboard) the keys A and B press.
    private void showMsxControlDialog() {
        showChoiceDialog(RoundIconView.PAD, RoundIconView.COLOR_PAD, "조작 방식", new String[]{"조이스틱", "키보드"},
                new String[]{"방향키 · A · B를 조이스틱 포트로 (기본)", "방향키 = 커서 키 · A · B = 고른 키"}, msxInt("control", 0),
                "조이스틱을 안 읽고 키보드만 읽는 게임이 있어요. 방향키를 눌러도 안 움직이면 \"키보드\"로 바꿔 보세요. 이 게임에만 적용돼요.",
                Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용", choice -> {
                    MsxKeys.send(this.nativeHandle, 0, false);
                    releaseMsxPadKeys();
                    MainActivity.sharedSettings(this).edit().putInt(msxKey("control"), choice).apply();
                    if (choice == 1) {
                        pickMsxPadKey("A", "key_a", "SPACE", () -> pickMsxPadKey("B", "key_b", "M", this::updateInput));
                    } else {
                        updateInput();
                    }
                });
    }

    private void pickMsxPadKey(String button, String pref, String fallback, Runnable then) {
        String current = msxString(pref, fallback);
        int index = java.util.Arrays.asList(MSX_AB_KEYS).indexOf(current);
        String[] notes = new String[MSX_AB_KEYS.length];
        java.util.Arrays.fill(notes, "");
        showChoiceDialog(RoundIconView.PAD, RoundIconView.COLOR_PAD, button + " 버튼 = 어느 키", MSX_AB_KEYS, notes, Math.max(0, index),
                "키보드 방식일 때 " + button + " 버튼이 누르는 키예요.", Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "다음", choice -> {
                    MainActivity.sharedSettings(this).edit().putString(msxKey(pref), MSX_AB_KEYS[choice]).apply();
                    then.run();
                });
    }

    // Lets go of the keys the pad held in keyboard mode.
    private void releaseMsxPadKeys() {
        int held = this.msxSentBits;
        this.msxSentBits = 0;
        int[] bits = {16, 32, 64, 128, 1, 2};
        int[] codes = {275, 276, 273, 274, MsxKeys.code(msxString("key_a", "SPACE")), MsxKeys.code(msxString("key_b", "M"))};
        for (int k = 0; k < bits.length; k++) {
            if ((held & bits[k]) != 0) MsxKeys.send(this.nativeHandle, codes[k], false);
        }
    }

    private TextView msxChip(String text, boolean on, int color) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chip.setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(71, 85, 105)));
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? color : Theme.bg(Color.rgb(241, 245, 249)));
        bg.setCornerRadius(dp(10));
        chip.setBackground(bg);
        return chip;
    }

    // 키 줄 고르기: the keys on the rows, their order and names, for this game.
    private void showMsxKeyEditor() {
        final java.util.List<String[]> keys = new java.util.ArrayList<>(java.util.Arrays.asList(msxRowKeys()));
        final android.app.Dialog editor = new android.app.Dialog(this);
        editor.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("키 줄 고르기 · 이 게임만");
        title.setTextSize(17f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        panel.addView(body);
        final int accent = Color.rgb(159, 18, 57);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            TextView now = new TextView(this);
            now.setText("지금 키 줄 (" + keys.size() + "개 · 세로 위 6개 · 아래 4개) · 톡 = 이름 · 순서 · 빼기");
            now.setTextSize(11.5f);
            now.setTextColor(Theme.text(Color.rgb(71, 85, 105)));
            now.setPadding(0, dp(10), 0, dp(4));
            body.addView(now);
            body.addView(msxChipFlow(keys.size(), k -> {
                String[] key = keys.get(k);
                TextView chip = msxChip(key[1].equals(key[0]) ? key[1] + "  ✎" : key[1] + " (" + key[0] + ")  ✎", true, Color.rgb(58, 61, 69));
                chip.setOnClickListener(v -> {
                    LinearLayout actions = new LinearLayout(this);
                    actions.setOrientation(1);
                    final android.app.Dialog[] sheet = new android.app.Dialog[1];
                    String[] labels = {"✎  이름 바꾸기", "◀  앞으로", "▶  뒤로", "✕  빼기"};
                    for (int a = 0; a < labels.length; a++) {
                        final int which = a;
                        TextView action = msxActionRow(labels[a], which == 3 ? Color.rgb(190, 18, 60) : Theme.text(Color.rgb(31, 41, 51)));
                        action.setOnClickListener(av -> {
                            sheet[0].dismiss();
                            if (which == 0) {
                                android.widget.EditText input = msxInput(key[1], false);
                                msxSheet(RoundIconView.EDIT, RoundIconView.COLOR_EDIT, key[0] + " 키 이름", input, "적용", () -> {
                                    String name = input.getText().toString().trim();
                                    keys.set(k, new String[]{key[0], name.isEmpty() ? key[0] : name});
                                    render[0].run();
                                }, null, null);
                                return;
                            } else if (which == 1 && k > 0) {
                                java.util.Collections.swap(keys, k, k - 1);
                            } else if (which == 2 && k < keys.size() - 1) {
                                java.util.Collections.swap(keys, k, k + 1);
                            } else if (which == 3) {
                                keys.remove(k);
                            }
                            render[0].run();
                        });
                        actions.addView(action, marginTop(-1, dp(46), a == 0 ? 0 : 6));
                    }
                    sheet[0] = msxSheet(RoundIconView.EDIT, RoundIconView.COLOR_EDIT, key[0] + (key[1].equals(key[0]) ? "" : " · " + key[1]), actions, null, null, null, null);
                });
                return chip;
            }));
            TextView add = new TextView(this);
            add.setText("추가할 키 (최대 10개)");
            add.setTextSize(11.5f);
            add.setTextColor(Theme.text(Color.rgb(71, 85, 105)));
            add.setPadding(0, dp(12), 0, dp(4));
            body.addView(add);
            body.addView(msxChipFlow(MSX_ADDABLE.length, k -> {
                TextView chip = msxChip(MSX_ADDABLE[k], false, accent);
                chip.setOnClickListener(v -> {
                    if (keys.size() >= 10) {
                        Toast.makeText(this, "키 줄은 10개까지예요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    keys.add(new String[]{MSX_ADDABLE[k], MSX_ADDABLE[k]});
                    render[0].run();
                });
                return chip;
            }));
            TextView quick = new TextView(this);
            quick.setText("빠른 설정");
            quick.setTextSize(11.5f);
            quick.setTextColor(Theme.text(Color.rgb(71, 85, 105)));
            quick.setPadding(0, dp(12), 0, dp(4));
            body.addView(quick);
            body.addView(msxChipFlow(MSX_PRESET_NAMES.length, k -> {
                TextView chip = msxChip(MSX_PRESET_NAMES[k], false, accent);
                chip.setOnClickListener(v -> {
                    String saved = msxString("keys", MSX_DEFAULT_KEYS);
                    MainActivity.sharedSettings(this).edit().putString(msxKey("keys"), MSX_PRESETS[k]).commit();
                    keys.clear();
                    keys.addAll(java.util.Arrays.asList(msxRowKeys()));
                    MainActivity.sharedSettings(this).edit().putString(msxKey("keys"), saved).commit();
                    render[0].run();
                });
                return chip;
            }));
        };
        render[0].run();
        LinearLayout buttons = new LinearLayout(this);
        TextView reset = dialogButton("기본으로", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        reset.setOnClickListener(v -> {
            MainActivity.sharedSettings(this).edit().remove(msxKey("keys")).apply();
            editor.dismiss();
            rebuildContent();
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView apply = dialogButton("적용", accent, Color.WHITE);
        apply.setOnClickListener(v -> {
            MainActivity.sharedSettings(this).edit().putString(msxKey("keys"), joinMsxKeys(keys)).apply();
            editor.dismiss();
            rebuildContent();
        });
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(46), 1.5f);
        applyParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(14), 0, 0);
        panel.addView(buttons, buttonsParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        editor.setContentView(scroll);
        android.view.Window window = editor.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(400)), -2);
        }
        editor.show();
    }

    private interface MsxChipMaker {
        TextView make(int index);
    }

    // Chips wrapped onto rows of four.
    private LinearLayout msxChipFlow(int count, MsxChipMaker maker) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(1);
        LinearLayout row = null;
        for (int k = 0; k < count; k++) {
            if (k % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, k == 0 ? 0 : dp(5), 0, 0);
                column.addView(row, rowParams);
            }
            TextView chip = maker.make(k);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            params.setMargins(k % 4 == 0 ? 0 : dp(5), 0, 0, 0);
            row.addView(chip, params);
        }
        if (count % 4 != 0 && row != null) {
            for (int k = count % 4; k < 4; k++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1f);
                params.setMargins(dp(5), 0, 0, 0);
                row.addView(new View(this), params);
            }
        }
        return column;
    }

    // MSX 화면 · 소리: sprite limit, FM sound, overscan, refresh rate (restarts the game).
    private void showMsxScreenDialog(android.app.Dialog menu) {
        final boolean[] on = {msxBool("sprites", true), msxBool("fm", true), msxBool("overscan", false)};
        final int[] hz = {msxInt("hz", 0)};
        String[] names = {"스프라이트 깜빡임 줄이기", "FM 음원 (MSX-MUSIC)", "화면 테두리 보이기 (오버스캔)"};
        String[] notes = {"한 줄 스프라이트 수 제한을 풀어 깜빡임을 줄여요 · 일부 게임은 숨겨 둔 물체가 보일 수 있어요",
                "지원하는 게임에서 더 풍성한 음악 (MSX2+ 이상)", "실기처럼 화면 둘레 테두리 색까지"};
        final int accent = Color.rgb(159, 18, 57);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        for (int k = 0; k < 3; k++) {
            body.addView(msxToggleRow(names[k], notes[k], on, k, accent), marginTop(-1, -2, k == 0 ? 0 : 7));
        }
        TextView hzLabel = new TextView(this);
        hzLabel.setText("화면 주사율");
        hzLabel.setTextSize(13f);
        hzLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hzLabel.setTextColor(Theme.text(Color.rgb(51, 65, 85)));
        body.addView(hzLabel, marginTop(-1, -2, 14));
        final String[] hzNames = {"자동", "60Hz (일본 · 한국)", "50Hz (유럽)"};
        final LinearLayout hzRow = new LinearLayout(this);
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            hzRow.removeAllViews();
            for (int k = 0; k < hzNames.length; k++) {
                final int index = k;
                TextView chip = msxChip(hzNames[k], k == hz[0], accent);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(v -> {
                    hz[0] = index;
                    paint[0].run();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(40), k == 0 ? 0.8f : 1.3f);
                params.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
                hzRow.addView(chip, params);
            }
        };
        paint[0].run();
        body.addView(hzRow, marginTop(-1, -2, 6));
        body.addView(msxNote("바꾸면 게임을 다시 시작해요 (빠른 저장으로 이어서). 기종 · 바이오스는 메뉴의 \"MSX 기종\" · \"MSX 바이오스\"에서.",
                Color.rgb(248, 250, 252), Color.rgb(71, 85, 105)), marginTop(-1, -2, 12));
        msxSheet(RoundIconView.HD, RoundIconView.COLOR_HD, "MSX 화면 · 소리", body, "적용 (다시 시작)", () -> {
            MainActivity.sharedSettings(this).edit().putBoolean(msxKey("sprites"), on[0]).putBoolean(msxKey("fm"), on[1])
                    .putBoolean(msxKey("overscan"), on[2]).putInt(msxKey("hz"), hz[0]).apply();
            if (menu != null) menu.dismiss();
            relaunchGame();
        }, null, null);
    }

    // What the original BIOS is for, which file names blueMSX looks for, and the picker.
    private void showMsxBiosDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        String[][] rows = msxBiosRows();
        for (int k = 0; k < rows.length; k++) {
            body.addView(msxStatusRow(rows[k][0], rows[k][1], rows[k][2], rows[k][3]), marginTop(-1, -2, k == 0 ? 0 : 6));
        }
        body.addView(msxNote("직접 가진 MSX에서 뽑은 파일을 이 이름 그대로 넣어요 (여러 개를 한 번에). 이름과 있는지만 보고, 내용이 맞는지는 확인하지 못해요. "
                        + "blueMSX의 기종 구성은 적힌 파일이 모두 있어야 켜져요 (없으면 롬팩은 C-BIOS, 디스크는 XBIOS로).\n넣은 다음 게임을 다시 시작하면 적용돼요.",
                Color.rgb(248, 250, 252), Color.rgb(71, 85, 105)), marginTop(-1, -2, 12));
        msxSheet(RoundIconView.IMPORT, RoundIconView.COLOR_CARD, "바이오스 상태 · 넣은 파일 " + msxBiosCount() + "개", body, "파일 넣기", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(intent, REQUEST_MSX_BIOS);
            } catch (RuntimeException e) {
                Toast.makeText(this, "파일을 고를 앱이 없어요.", Toast.LENGTH_SHORT).show();
            }
        }, "예제 디스크 다시 넣기", () -> Toast.makeText(this,
                MainActivity.copyXbiosExamples(this, true) ? "게임 목록에 \"XBIOS 예제 디스크\"를 넣었어요" : "예제 디스크를 넣지 못했어요", Toast.LENGTH_LONG).show());
    }

    // ---- 바이오스 상태: what each blueMSX machine wants from Machines/Shared Roms ----
    private static final String[][] MSX_STATUS_MACHINES = {
            {"MSX2+", "MSX2+", "롬팩은 C-BIOS · 디스크는 XBIOS"}, {"MSX2", "MSX2", "롬팩은 C-BIOS · 디스크는 XBIOS"},
            {"MSX1", "MSX", "롬팩은 C-BIOS · 디스크는 XBIOS"}, {"turbo R", "MSXturboR", "원래 바이오스 필요"},
            {"한글 MSX2 (대우 CPC-300)", "MSX2 - Daewoo CPC-300", "없으면 다른 기종으로"}, {"한글 MSX1 (대우 DPC-200)", "MSX - Daewoo DPC-200", "없으면 다른 기종으로"},
    };

    // The Shared Roms file names a machine's config.ini names.
    private java.util.List<String> msxSharedNames(String machine) {
        java.util.List<String> names = new java.util.ArrayList<>();
        File config = new File(new File(new File(new File(getFilesDir(), "system"), "Machines"), machine), "config.ini");
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(config))) {
            String line;
            while ((line = in.readLine()) != null) {
                int open = line.indexOf('"');
                int close = open < 0 ? -1 : line.indexOf('"', open + 1);
                if (close <= open + 1) continue;
                String path = line.substring(open + 1, close).replace('\\', '/');
                String prefix = "Machines/Shared Roms/";
                if (path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    String name = path.substring(prefix.length());
                    if (!names.contains(name)) names.add(name);
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return names;
    }

    // {title, files line, state (ok / part / alt / no), state text}
    private String[][] msxBiosRows() {
        File shared = msxSharedRoms();
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String[] m : MSX_STATUS_MACHINES) {
            java.util.List<String> names = msxSharedNames(m[1]);
            if (names.isEmpty()) continue;
            int have = 0;
            StringBuilder files = new StringBuilder();
            for (String name : names) {
                boolean there = new File(shared, name).isFile();
                if (there) have++;
                files.append(files.length() == 0 ? "" : "  ").append(name).append(there ? " ✓" : " ✗");
            }
            String state = have == names.size() ? "ok" : have > 0 ? "part" : m[2].startsWith("롬팩") ? "alt" : "no";
            String text = have == names.size() ? "준비됨 ✓" : have > 0 ? "일부만 " + have + "/" + names.size() : m[2].startsWith("롬팩") ? "대체 사용" : "없음";
            rows.add(new String[]{m[0], files + (have == names.size() ? "" : "\n→ " + m[2]), state, text});
        }
        boolean philips = new File(shared, "PHILIPSDISK.rom").isFile();
        boolean panasonic = new File(shared, "PANASONICDISK.rom").isFile();
        rows.add(new String[]{"디스크", "PHILIPSDISK.rom" + (philips ? " ✓" : " ✗") + "  PANASONICDISK.rom" + (panasonic ? " ✓" : " ✗")
                + (philips || panasonic ? "" : "\n→ 디스크 게임은 XBIOS"), philips || panasonic ? "ok" : "alt", philips || panasonic ? "있음 ✓" : "대체 사용"});
        return rows.toArray(new String[0][]);
    }

    private String msxBiosSummary() {
        int bios = msxBiosCount();
        if (bios == 0) return "넣은 파일 없음";
        for (String[] m : MSX_STATUS_MACHINES) {
            if (msxMachineValid(m[1])) return m[0] + " 준비됨";
        }
        return "넣은 파일 " + bios + "개";
    }

    private View msxStatusRow(String title, String files, String state, String stateText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(1);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(roundRect(Theme.bg(Color.rgb(246, 247, 249)), dp(12)));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(13f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        // Solid badges with white text read the same in the light and dark themes.
        int badgeColor = "ok".equals(state) ? Color.rgb(22, 163, 74) : "part".equals(state) ? Color.rgb(217, 119, 6)
                : "alt".equals(state) ? Color.rgb(2, 132, 199) : Color.rgb(100, 116, 139);
        int[] colors = {badgeColor, Color.WHITE};
        TextView badge = new TextView(this);
        badge.setText(stateText);
        badge.setTextSize(10.5f);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setTextColor(colors[1]);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        badge.setBackground(roundRect(colors[0], dp(8)));
        top.addView(badge);
        row.addView(top);
        TextView list = new TextView(this);
        list.setText(files);
        list.setTextSize(10.5f);
        list.setTypeface(android.graphics.Typeface.MONOSPACE);
        list.setTextColor(Theme.text(Color.rgb(100, 116, 139)));
        list.setLineSpacing(0, 1.15f);
        row.addView(list, marginTop(-1, -2, 4));
        return row;
    }

    // ---- 두 번째 롬팩 슬롯 ----
    private String msxSlot2Path() {
        String path = msxString("slot2", "");
        return path.isEmpty() || !new File(path).isFile() ? null : path;
    }

    private void showMsxSlot2Dialog(android.app.Dialog menu) {
        final java.util.List<File> carts = new java.util.ArrayList<>();
        File folder = this.romPath == null ? null : new File(this.romPath).getParentFile();
        File[] files = folder == null ? null : folder.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && !f.getAbsolutePath().equals(new File(this.romPath).getAbsolutePath())
                        && (lower.endsWith(".rom") || lower.endsWith(".mx1") || lower.endsWith(".mx2"))) {
                    carts.add(f);
                }
            }
        }
        String current = msxSlot2Path();
        if (current != null && !carts.contains(new File(current))) carts.add(0, new File(current));
        String[] names = new String[carts.size() + 2];
        String[] notes = new String[names.length];
        names[0] = "없음";
        notes[0] = "슬롯 2를 비워 둠 (기본)";
        int selected = 0;
        for (int k = 0; k < carts.size(); k++) {
            names[k + 1] = carts.get(k).getName();
            notes[k + 1] = "롬팩 · " + Math.max(1, carts.get(k).length() / 1024) + "KB";
            if (current != null && carts.get(k).getAbsolutePath().equals(new File(current).getAbsolutePath())) selected = k + 1;
        }
        names[names.length - 1] = "다른 파일 고르기…";
        notes[names.length - 1] = "휴대폰에서 .rom 고르기";
        showChoiceDialog(RoundIconView.IMPORT, RoundIconView.COLOR_LOAD, "두 번째 롬팩 슬롯", names, notes, selected,
                "코나미 게임 일부는 다른 코나미 롬팩을 같이 꽂으면 숨겨진 요소가 열려요. 어떤 조합이 되는지는 게임마다 달라요. 이 게임에만 적용되고, 적용하면 다시 시작해요.",
                Color.rgb(239, 246, 255), Color.rgb(30, 58, 138), "적용 (다시 시작)", choice -> {
                    if (choice == names.length - 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        try {
                            startActivityForResult(intent, REQUEST_MSX_SLOT2);
                        } catch (RuntimeException e) {
                            Toast.makeText(this, "파일을 고를 앱이 없어요.", Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    android.content.SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                    if (choice == 0) edit.remove(msxKey("slot2"));
                    else edit.putString(msxKey("slot2"), carts.get(choice - 1).getAbsolutePath());
                    edit.apply();
                    if (menu != null) menu.dismiss();
                    relaunchGame();
                });
    }

    private void importMsxSlot2(android.net.Uri uri) {
        String name = new File(cheatFileName(uri)).getName();
        if (name.isEmpty() || name.startsWith(".")) name = "slot2.rom";
        File dir = new File(getFilesDir(), "msx_slot2");
        dir.mkdirs();
        File target = new File(dir, name);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new java.io.IOException();
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (java.io.IOException e) {
            target.delete();
            Toast.makeText(this, "파일을 넣지 못했어요", Toast.LENGTH_SHORT).show();
            return;
        }
        MainActivity.sharedSettings(this).edit().putString(msxKey("slot2"), target.getAbsolutePath()).apply();
        Toast.makeText(this, "슬롯 2에 " + name + "을(를) 꽂았어요 · 다시 시작해요", Toast.LENGTH_SHORT).show();
        relaunchGame();
    }

    // ---- 2인용 (포트 2) ----
    private static final String[] MSX_PORT2_NAMES = {"비워 둠", "두 번째 패드", "포트 바꾸기"};

    private int msxPort2() {
        int value = msxInt("port2", 1);
        return value >= 0 && value < MSX_PORT2_NAMES.length ? value : 1;
    }

    // The screen pad (and 1P's pad) to port 1, or to port 2 for games that read only that.
    private void sendMainInput(long handle) {
        int mask = isMsx() ? msxFilterInput(this.buttonMask) : this.buttonMask;
        mask = gunKeyFilter(turboToggleFilter(mask));
        if (isPc98()) {
            sendPc98Pad(handle, mask);
        }
        if (isMsx() && msxPort2() == 2) {
            NativeGba.setInput(handle, 0);
            NativeGba.setPlayerInput(handle, 1, mask);
        } else {
            NativeGba.setInput(handle, mask);
        }
    }

    private void showMsxPort2Dialog() {
        showChoiceDialog(RoundIconView.PAD, RoundIconView.COLOR_PAD, "2인용 · 조이스틱 포트 2", MSX_PORT2_NAMES,
                new String[]{"1인용", "연결한 블루투스 패드의 2P가 포트 2로 (기본)", "화면 패드를 포트 2로 (포트 2만 읽는 게임)"}, msxPort2(),
                "MSX 게임은 대부분 1P가 포트 1, 2P가 포트 2예요. 패드를 두 개 연결하면 두 번째 패드가 2P가 돼요. 이 게임에만 적용돼요.",
                Color.rgb(248, 250, 252), Color.rgb(71, 85, 105), "적용", choice -> {
                    long handle = this.nativeHandle;
                    if (handle != 0) {
                        NativeGba.setInput(handle, 0);
                        NativeGba.setPlayerInput(handle, 1, 0);
                    }
                    MainActivity.sharedSettings(this).edit().putInt(msxKey("port2"), choice).apply();
                    refreshPlayers(false);
                    updateInput();
                });
    }

    // ---- 롬 매퍼 (고급) ----
    private static final String[] MSX_MAPPER_NAMES = {"자동 (추천)", "코나미 (SCC 없음)", "코나미 SCC", "ASCII 8K", "ASCII 16K", "ASCII 8K + 세이브",
            "ASCII 16K + 세이브", "일반 (16 · 32KB)", "일반 · 거울상", "한국 메가롬 80", "한국 메가롬 90", "한국 메가롬 126"};
    private static final String[] MSX_MAPPER_VALUES = {"Auto", "konami4", "konami5", "ascii8", "ascii16", "ascii8sram",
            "ascii16sram", "Normal", "mirrored", "korean80", "korean90", "korean126"};

    private int msxMapper() {
        int value = msxInt("mapper", 0);
        return value >= 0 && value < MSX_MAPPER_VALUES.length ? value : 0;
    }

    private void showMsxMapperDialog(android.app.Dialog menu) {
        String[] notes = {"데이터베이스 → 자동 감지", "Konami4 · 메가롬 초기 코나미", "Konami5 · SCC 음원 코나미", "ASCII8 · 많은 메가롬", "ASCII16",
                "ascii8sram · 세이브가 있는 롬팩", "ascii16sram", "Normal · 메가롬이 아닌 롬팩", "mirrored", "korean80 · 국산 롬팩", "korean90", "korean126"};
        showChoiceDialog(RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "롬 매퍼 (고급)", MSX_MAPPER_NAMES, notes, msxMapper(),
                "켜지지 않거나 화면이 깨지는 롬팩에만 써요. 보통은 \"자동\"이 데이터베이스와 자동 감지로 맞춰요. 이 게임에만 적용되고, 적용하면 다시 시작해요.",
                Color.rgb(255, 247, 237), Color.rgb(154, 52, 18), "적용 (다시 시작)", choice -> {
                    if (choice == msxMapper()) return;
                    MainActivity.sharedSettings(this).edit().putInt(msxKey("mapper"), choice).apply();
                    if (menu != null) menu.dismiss();
                    relaunchGame();
                });
    }

    // ---- MSX dialogs in the app's own style (header icon, rounded panel, two buttons) ----
    private android.app.Dialog msxSheet(int icon, int color, String titleText, View body, String applyText, Runnable onApply,
                                        String extraText, Runnable onExtra) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(24));
        panel.setBackground(background);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(16);
        header.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        titles.addView(title);
        TextView game = new TextView(this);
        game.setText(this.gameTitle + " · 이 게임만");
        game.setTextSize(12.0f);
        game.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(game);
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        titlesParams.setMargins(dp(10), 0, 0, 0);
        header.addView(titles, titlesParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(body, marginTop(-1, -2, 14));
        if (extraText != null) {
            // A third action gets its own full-width row above the two buttons.
            TextView extra = dialogButton(extraText, Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
            extra.setTextSize(14f);
            extra.setSingleLine(true);
            extra.setOnClickListener(v -> {
                dialog.dismiss();
                onExtra.run();
            });
            panel.addView(extra, marginTop(-1, dp(46), 14));
        }
        LinearLayout buttons = new LinearLayout(this);
        buttons.setBaselineAligned(false);
        TextView close = dialogButton(applyText == null ? "닫기" : "취소", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1f));
        if (applyText != null) {
            TextView apply = dialogButton(applyText, color, Color.WHITE);
            apply.setOnClickListener(v -> {
                dialog.dismiss();
                onApply.run();
            });
            LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
            applyParams.setMargins(dp(8), 0, 0, 0);
            buttons.addView(apply, applyParams);
        }
        panel.addView(buttons, marginTop(-1, -2, extraText != null ? 8 : 14));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(400)), -2);
        }
        dialog.show();
        return dialog;
    }

    private TextView msxNote(String text, int background, int color) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(12f);
        note.setLineSpacing(0, 1.15f);
        note.setTextColor(Theme.text(color));
        note.setPadding(dp(12), dp(10), dp(12), dp(10));
        note.setBackground(roundRect(Theme.bg(background), dp(12)));
        return note;
    }

    private android.widget.EditText msxInput(String text, boolean mono) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(text);
        input.setSelection(input.getText().length());
        input.setTextSize(15f);
        input.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        if (mono) input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setPadding(dp(12), 0, dp(12), 0);
        android.graphics.drawable.GradientDrawable bg = roundRect(Theme.bg(Color.rgb(246, 247, 249)), dp(12));
        bg.setStroke(dp(2), Color.rgb(203, 213, 225));
        input.setBackground(bg);
        return input;
    }

    private TextView msxActionRow(String text, int color) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(14.5f);
        row.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.setTextColor(color);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackground(roundRect(Theme.bg(Color.rgb(246, 247, 249)), dp(13)));
        return row;
    }

    // A setting with its note and a switch; the whole row toggles it.
    private View msxToggleRow(String name, String note, boolean[] state, int index, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(13.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText(note);
        sub.setTextSize(11f);
        sub.setLineSpacing(0, 1.1f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        texts.addView(sub, marginTop(-1, -2, 2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        ToggleView toggle = new ToggleView(this, color);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(44), dp(26));
        toggleParams.setMargins(dp(10), 0, 0, 0);
        row.addView(toggle, toggleParams);
        Runnable paint = () -> {
            toggle.setOn(state[index]);
            row.setBackground(roundRect(Theme.bg(state[index] ? Color.rgb(255, 241, 242) : Color.rgb(246, 247, 249)), dp(13)));
        };
        paint.run();
        row.setOnClickListener(v -> {
            state[index] = !state[index];
            paint.run();
        });
        return row;
    }

    // blueMSX opens the files by their exact names: msx2p.rom becomes MSX2P.rom.
    private String msxCanonicalRomName(String name) {
        java.util.List<String> known = new java.util.ArrayList<>(java.util.Arrays.asList("PHILIPSDISK.rom", "PANASONICDISK.rom"));
        for (String[] m : MSX_STATUS_MACHINES) known.addAll(msxSharedNames(m[1]));
        for (String k : known) {
            if (k.equalsIgnoreCase(name)) return k;
        }
        return name;
    }

    // Copies the picked ROMs into <system>/Machines/Shared Roms under their own names.
    private void importMsxBios(Intent data) {
        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                uris.add(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        File dir = msxSharedRoms();
        dir.mkdirs();
        int copied = 0;
        for (android.net.Uri uri : uris) {
            String name = msxCanonicalRomName(new File(cheatFileName(uri)).getName());
            if (name.isEmpty() || name.startsWith(".")) continue;
            File target = new File(dir, name);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                if (in == null) continue;
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                copied++;
            } catch (java.io.IOException e) {
                target.delete();
            }
        }
        Toast.makeText(this, copied > 0 ? "바이오스 파일 " + copied + "개를 넣었어요 · 게임을 다시 시작하면 적용돼요" : "파일을 넣지 못했어요", Toast.LENGTH_LONG).show();
    }

    // Landscape: D-pad and keys in the left column, A / B and keys in the right one.
    private void addMsxLandscapeControls(FrameLayout frameLayout) {
        addDPadAt(frameLayout, 28, 104, 124);
        addRoundButton(frameLayout, "A", 1, true, 130, 168, 56, TouchButton.STYLE_ARCADE_RED);
        addRoundButton(frameLayout, "B", 2, true, 62, 214, 56, TouchButton.STYLE_ARCADE_RED);
        String[][] keys = msxRowKeys();
        if (keys.length > 0) addMsxRow(frameLayout, msxSlice(keys, 0, 3), Gravity.BOTTOM | Gravity.START, 12, 0, 0, 58, 168, 30);
        java.util.List<String[]> left = new java.util.ArrayList<>(java.util.Arrays.asList(new String[][]{{"STOP", "STOP"}}));
        left.addAll(java.util.Arrays.asList(msxSlice(keys, 6, 8)));
        addMsxRow(frameLayout, left.toArray(new String[0][]), Gravity.BOTTOM | Gravity.START, 12, 0, 0, 16, 168, 32);
        addMsxRow(frameLayout, withKeyboardKey(msxSlice(keys, 3, 6)), Gravity.BOTTOM | Gravity.END, 0, 12, 0, 58, 176, 30);
        java.util.List<String[]> right = new java.util.ArrayList<>(java.util.Arrays.asList(msxSlice(keys, 8, 10)));
        right.add(new String[]{"GRAPH", "GRAPH", "1.4"});
        addMsxRow(frameLayout, right.toArray(new String[0][]), Gravity.BOTTOM | Gravity.END, 0, 12, 0, 16, 176, 32);
        addMsxKeyboard(frameLayout, true);
        addMsxRunPanel(frameLayout, true);
    }

    private void addDosControls(FrameLayout frame, boolean landscape) {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        DosControls old = this.dosControls;
        int start = old != null ? old.page() : DosSetup.clamp(DosSetup.choice(prefs, "control", this.saveBaseName), DosSetup.CONTROL_NAMES.length);
        if (old != null) {
            old.releaseAll();
        }
        DosControls controls = new DosControls(this, new DosControls.Host() {
            @Override
            public long handle() {
                return nativeHandle;
            }

            @Override
            public int dp(int value) {
                return GameActivity.this.dp(value);
            }

            @Override
            public void rebuilt() {
                // Another page or a key taken out: the editor's list and the style again.
                frame.post(() -> {
                    View root = contentRoot;
                    if (!destroyed && root != null && root.isAttachedToWindow() && dosControls != null && frame.isAttachedToWindow()) {
                        applyLayoutEdits(root);
                    }
                });
            }
        }, landscape, DosSetup.keyRow(prefs, this.saveBaseName, isPc98() ? DosControls.PC98_KEYS : DosSetup.DEFAULT_KEYS),
                DosSetup.buttonRow(prefs, this.saveBaseName, isPc98() ? DosControls.PC98_BUTTONS : DosSetup.DEFAULT_BUTTONS), start,
                DosSetup.choice(prefs, "mouse", this.saveBaseName) == 1, isPc98());
        controls.setPulledStore(prefs);
        this.dosControls = controls;
        controls.attach(frame);
    }

    // DOS: the size of the landscape screen, between the D-pad and the right-hand buttons,
    // the key row under it.
    private void fitDosLandscape(FrameLayout screen, int width, int height) {
        float aspect = screenAspect();
        int maxWidth = Math.max(dp(240), width - dp(150) * 2);
        int maxHeight = Math.max(dp(150), height - dp(44) - dp(8));
        int screenWidth = Math.min(maxWidth, Math.round(maxHeight * aspect));
        int screenHeight = Math.round(screenWidth / aspect);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) screen.getLayoutParams();
        if (params.width != screenWidth || params.height != screenHeight || params.topMargin != dp(6)) {
            params.width = screenWidth;
            params.height = screenHeight;
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(6);
            params.bottomMargin = 0;
            screen.setLayoutParams(params);
        }
    }

    private NaomiSets.Set naomiSet() {
        return this.romPath == null ? null : NaomiSets.forFile(this, new File(this.romPath));
    }

    // Flycast's libretro Naomi mapping: buttons 1-6 are B, A, Y, X, R, L; coin SELECT,
    // start START, test L3, service R3.
    private static final int[] NAOMI_BITS = {2, 1, 2048, 1024, 256, 512};
    private static final int[] NAOMI_STYLES = {TouchButton.STYLE_ARCADE_BLUE, TouchButton.STYLE_ARCADE_BLUE, TouchButton.STYLE_ARCADE_BLUE,
            TouchButton.STYLE_ARCADE_RED, TouchButton.STYLE_ARCADE_RED, TouchButton.STYLE_ARCADE_RED};

    private int naomiButtons() {
        return NaomiSets.buttonsFor(this, this.saveBaseName, naomiSet());
    }

    // Portrait: the arcade stick at the bottom left, buttons 1 2 3 over 4 5 6 at the
    // bottom right, COIN · START in the middle with TEST · SERVICE above them.
    private void addNaomiControls(FrameLayout frameLayout) {
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(156), dp(156));
        stickParams.gravity = 8388691;
        stickParams.setMargins(dp(16), 0, 0, dp(144));
        frameLayout.addView(new ArcadeJoystickView(this, this::onDirectionChanged), stickParams);
        addFastForwardButton(frameLayout);
        int count = naomiButtons();
        int[] columns = {136, 76, 16};
        for (int k = 0; k < count && k < 6; k++) {
            int row = count <= 3 ? 0 : k / 3;
            int column = count <= 3 ? k : k % 3;
            int bottom = count <= 3 ? 197 : (row == 0 ? 227 : 167);
            addButton(frameLayout, String.valueOf(k + 1), NAOMI_BITS[k], 8388693, dp(columns[column]), dp(bottom), dp(50), dp(50), NAOMI_STYLES[k]);
        }
        addCenterButton(frameLayout, "TEST", kL3, -66, 112);
        addCenterButton(frameLayout, "SERVICE", kR3, 66, 112);
        addCenterButton(frameLayout, "COIN", 4, -66);
        addCenterButton(frameLayout, "START", 8, 66);
    }

    // Landscape: the stick on the left, the button rows on the right, COIN and START at the
    // bottom corners, TEST · SERVICE small at the top left under the speed button.
    private void addNaomiLandscapeControls(FrameLayout frameLayout) {
        View stick = new ArcadeJoystickView(this, this::onDirectionChanged);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(150), dp(150));
        stickParams.gravity = 8388627;
        stickParams.setMargins(dp(12), 0, 0, 0);
        stick.setTranslationY(dp(LANDSCAPE_SHIFT_DP));
        frameLayout.addView(stick, stickParams);
        int count = naomiButtons();
        int[] columns = {120, 66, 12};
        for (int k = 0; k < count && k < 6; k++) {
            int row = count <= 3 ? 0 : k / 3;
            int column = count <= 3 ? k : k % 3;
            int y = count <= 3 ? 0 : (row == 0 ? -27 : 27);
            addLandscapeArcadeButton(frameLayout, String.valueOf(k + 1), NAOMI_BITS[k], columns[column], y, 46, NAOMI_STYLES[k]);
        }
        FrameLayout.LayoutParams testParams = new FrameLayout.LayoutParams(dp(64), dp(28), Gravity.TOP | Gravity.START);
        testParams.setMargins(dp(14), dp(60), 0, 0);
        frameLayout.addView(new TouchButton(this, "TEST", kL3, this::setButtonPressed, 2), testParams);
        FrameLayout.LayoutParams serviceParams = new FrameLayout.LayoutParams(dp(78), dp(28), Gravity.TOP | Gravity.START);
        serviceParams.setMargins(dp(84), dp(60), 0, 0);
        frameLayout.addView(new TouchButton(this, "SERVICE", kR3, this::setButtonPressed, 2), serviceParams);
        addButton(frameLayout, "COIN", 4, 8388691, dp(42), dp(18), dp(94), dp(34));
        addButton(frameLayout, "START", 8, 8388693, dp(42), dp(18), dp(94), dp(34));
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
        frameLayout.addView(leftStick(), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(28), 0, 0, dp(66));
        frameLayout.addView(new DPadView(this, this::onDirectionChanged), padParams);
        addDreamcastFace(frameLayout, Gravity.BOTTOM | Gravity.END, 100, 176, 50, 56);
        addFastForwardButton(frameLayout);
        addCenterButton(frameLayout, startLabel(), 8, 0, 18);
    }

    // 3DS in landscape: L at the top left, the circle pad under it and the D-pad at the
    // bottom; on the right R above the diamond, which sits low and in from the edge.
    // SELECT and START go in the middle under the screens.
    private void addN3dsLandscapeControls(FrameLayout frameLayout) {
        // Left column: L under the save/restart buttons, the circle pad, the D-pad to its
        // lower right, SELECT at the bottom.
        addButton(frameLayout, "L", 512, 8388659, dp(14), dp(62), dp(104), dp(38), 2);
        FrameLayout.LayoutParams stickParams = new FrameLayout.LayoutParams(dp(100), dp(100), Gravity.TOP | Gravity.START);
        stickParams.setMargins(dp(18), dp(124), 0, 0);
        frameLayout.addView(leftStick(), stickParams);
        FrameLayout.LayoutParams padParams = new FrameLayout.LayoutParams(dp(120), dp(120), Gravity.TOP | Gravity.START);
        padParams.setMargins(dp(76), dp(232), 0, 0);
        frameLayout.addView(createDirectionControl(), padParams);
        addButton(frameLayout, selectLabel(), 4, 8388691, dp(100), dp(14), dp(94), dp(34));
        // The microphone (0.0.370) in the lower left corner, left of SELECT.
        addButton(frameLayout, "MIC", kMic3ds, Gravity.BOTTOM | Gravity.START, dp(16), dp(10), dp(46), dp(46), TouchButton.STYLE_PINK);
        // Right column: R, the diamond level with the circle pad and well in from the edge,
        // START at the bottom (the speed button sits above it).
        addButton(frameLayout, "R", 256, 8388661, dp(14), dp(62), dp(104), dp(38), 2);
        addButton(frameLayout, "X", 1024, 8388661, dp(101), dp(120), dp(46), dp(46), TouchButton.STYLE_DARK);
        addButton(frameLayout, "A", 1, 8388661, dp(54), dp(160), dp(46), dp(46), TouchButton.STYLE_DARK);
        addButton(frameLayout, "Y", 2048, 8388661, dp(148), dp(160), dp(46), dp(46), TouchButton.STYLE_DARK);
        addButton(frameLayout, "B", 2, 8388661, dp(101), dp(200), dp(46), dp(46), TouchButton.STYLE_DARK);
        addButton(frameLayout, startLabel(), 8, 8388693, dp(120), dp(14), dp(94), dp(34));
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
        frameLayout.addView(leftStick(), stickParams);
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
        frameLayout.addView(leftStick(), stickParams);
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
        frameLayout.addView(leftStick(), stickParams);
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

    // PS Vita (Vita3K): △○×□, L/R, two sticks, the screen as its touch screen.
    private boolean isVita() {
        return "vita3k".equals(this.coreId);
    }

    // Engines that cannot save a scene: no quick saves, slots, resume or rewind.
    private int vitaScale() {
        int value = MainActivity.sharedSettings(this).getInt("vita_scale:" + this.saveBaseName, 1);
        return value >= 1 && value <= 3 ? value : 1;
    }

    // GPU 메모리 매핑 (0.0.324): how Vita3K shares the game's memory with the GPU. Its default
    // (double buffer) makes one GPU buffer per region the game maps; some games ask for one the
    // phone's driver refuses and Vita3K stops ("vma::Allocator::createBuffer"). Off copies
    // instead: slower, works everywhere.
    private static final String[] VITA_MM_NAMES = {"기본 (Double Buffer)", "끔 (호환성 우선)", "Page Table"};
    private static final String[] VITA_MM_VALUES = {"double-buffer", "disabled", "page-table"};

    private int vitaMemoryMapping() {
        int value = MainActivity.sharedSettings(this).getInt("vita_mm:" + this.saveBaseName, 0);
        return value >= 0 && value < VITA_MM_VALUES.length ? value : 0;
    }

    // 0.0.324-0.0.326 turned memory mapping off after a crash that turned out to have another
    // cause (the screen filter rebuilt on an option refresh); those games get the default back.
    private void vitaResetMappingOnce() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        if (prefs.getBoolean("vita_mm_reset_327", false)) {
            return;
        }
        android.content.SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("vita_mm:")) {
                edit.remove(key);
            }
        }
        edit.remove("vita_last_game").putBoolean("vita_mm_reset_327", true).apply();
    }

    // (Unused since 0.0.327.) The last run of this game stopped on a GPU buffer the driver
    // refused: memory mapping off.
    private void vitaFallbackAfterCrash() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        boolean sameGame = this.saveBaseName.equals(prefs.getString("vita_last_game", null));
        prefs.edit().putString("vita_last_game", this.saveBaseName).apply();
        if (!sameGame || prefs.contains("vita_mm:" + this.saveBaseName)) {
            return;
        }
        String tail = readTail(new File(getFilesDir(), "engine_log.txt"), 256 * 1024) + CrashReports.readGameLog(this);
        if (tail.contains("vma::Allocator::createBuffer") || tail.contains("InitializationFailedError")) {
            prefs.edit().putInt("vita_mm:" + this.saveBaseName, 1).apply();
            runOnUiThread(() -> Toast.makeText(this, "지난번에 그래픽 메모리 오류로 멈춰서, 이 게임은 GPU 메모리 매핑을 끄고 켜요 (메뉴 → 성능에서 바꿀 수 있어요)",
                    Toast.LENGTH_LONG).show());
        }
    }

    private static String readTail(File file, int max) {
        if (!file.isFile()) return "";
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - max);
            byte[] data = new byte[(int) (in.length() - start)];
            in.seek(start);
            in.readFully(data);
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private LinearLayout createVitaMemoryRow(android.app.Dialog menu) {
        String[] notes = {"Vita3K 기본 · 빠름", "게임이 켜지다 \"그래픽 메모리\" 오류로 멈출 때 · 조금 느릴 수 있어요", "다른 방식 · 기기에 따라 더 빠르거나 안 될 수 있어요"};
        return createSettingRow(RoundIconView.PERF, RoundIconView.COLOR_PERF, "GPU 메모리 매핑", VITA_MM_NAMES[vitaMemoryMapping()],
                view -> showChoiceDialog(RoundIconView.PERF, RoundIconView.COLOR_PERF, "GPU 메모리 매핑", VITA_MM_NAMES, notes, vitaMemoryMapping(),
                        "게임 메모리를 GPU와 나누는 방식이에요. 켜자마자 엔진 오류로 멈추는 게임은 \"끔\"으로 해 보세요. 이 게임에만 적용되고, 바꾸면 다시 켜져요.",
                        Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용 (다시 시작)", choice -> {
                            MainActivity.sharedSettings(this).edit().putInt("vita_mm:" + this.saveBaseName, choice).apply();
                            if (menu != null) menu.dismiss();
                            relaunchGame();
                        }));
    }

    private LinearLayout createVitaScaleRow(android.app.Dialog menu) {
        String[] names = {"1배 · 960×544 (추천)", "2배 · 1920×1088", "3배 · 2880×1632"};
        String[] notes = {"PS Vita 원래 해상도 · 가장 가벼워요", "더 선명 · 휴대폰이 뜨거워질 수 있어요", "가장 선명 · 무거워요"};
        return createSettingRow(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도 배율", vitaScale() + "배",
                view -> showChoiceDialog(RoundIconView.HD, RoundIconView.COLOR_HD, "해상도 배율", names, notes, vitaScale() - 1,
                        "이 게임에만 적용돼요. 바꾸면 게임이 다시 켜져요 (PS Vita는 이어하기가 없어서 타이틀부터).",
                        Color.rgb(239, 246, 255), Color.rgb(30, 64, 175), "적용 (다시 시작)", choice -> {
                            MainActivity.sharedSettings(this).edit().putInt("vita_scale:" + this.saveBaseName, choice + 1).apply();
                            if (menu != null) menu.dismiss();
                            relaunchGame();
                        }));
    }

    // ---- PS Vita text input: a game asking for a name / password opens the system text box,
    // which this engine build can't draw; the phone's own text box answers it instead. ----
    private boolean vitaTextShowing;
    private final Runnable vitaTextPoll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            long handle = GameActivity.this.nativeHandle;
            if (handle != 0 && !GameActivity.this.vitaTextShowing) {
                String[] ask = NativeGba.vitaTextRequest(handle);
                if (ask != null && ask.length == 3) {
                    showVitaTextInput(handle, ask);
                }
            }
            getWindow().getDecorView().postDelayed(this, 400);
        }
    };

    private void startVitaTextPoll() {
        getWindow().getDecorView().removeCallbacks(this.vitaTextPoll);
        getWindow().getDecorView().postDelayed(this.vitaTextPoll, 400);
    }

    private void showVitaTextInput(final long handle, String[] ask) {
        int head;
        try {
            head = Integer.parseInt(ask[0]);
        } catch (NumberFormatException e) {
            return;
        }
        int max = head >> 8;
        this.vitaTextShowing = true;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(ask[2]);
        input.setSelection(input.getText().length());
        if (max > 0) {
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        }
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        String title = ask[1] == null || ask[1].trim().isEmpty() ? "글자 입력" : ask[1].trim();
        final boolean[] answered = {false};
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(max > 0 ? "게임이 글자를 입력해 달라고 해요 (최대 " + max + "자)" : "게임이 글자를 입력해 달라고 해요")
                .setView(box)
                .setPositiveButton("확인", (d, which) -> {
                    answered[0] = true;
                    NativeGba.vitaTextAnswer(handle, input.getText().toString(), true);
                })
                .setNegativeButton("취소", (d, which) -> {
                    answered[0] = true;
                    NativeGba.vitaTextAnswer(handle, "", false);
                })
                .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) {
                NativeGba.vitaTextAnswer(handle, "", false);
            }
            this.vitaTextShowing = false;
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                answered[0] = true;
                NativeGba.vitaTextAnswer(handle, input.getText().toString(), true);
                dialog.dismiss();
                return true;
            }
            return false;
        });
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    // ---- PS1 엔진 (0.0.342): SwanStation (high quality) or PCSX ReARMed (light), per game ----
    private static final String[] PS1_ENGINE_NAMES = {"SwanStation (추천 · 고화질)", "PCSX ReARMed (가벼움)"};
    private static final String[] PS1_ENGINE_NOTES = {
            "DuckStation 기반 · 고해상도 · 흔들림 보정(PGXP) · 24비트 색 · 와이드스크린 · 게임별 호환 설정 자동",
            "원래 해상도 그대로 · 오래된 · 느린 휴대폰에 좋아요"};

    private String romFileName() {
        return this.romPath == null ? "" : new File(this.romPath).getName();
    }

    private LinearLayout createPs1EngineRow(android.app.Dialog menu) {
        final int current = isSwan() ? 0 : 1;
        final int color = Color.rgb(79, 70, 229);
        return createSettingRow(RoundIconView.GEAR, color, "PS1 엔진", isSwan() ? "SwanStation" : "PCSX ReARMed",
                view -> showChoiceDialog(RoundIconView.GEAR, color, "PS1 엔진 · 이 게임만", PS1_ENGINE_NAMES, PS1_ENGINE_NOTES, current,
                        "엔진을 바꾸면 빠른 저장(장면)은 그 엔진에서만 열려요. 메모리 카드 세이브는 그대로 이어져요 (같은 파일을 써요). 게임 안에서 메모리 카드에 저장한 뒤 바꾸세요.\n"
                                + "바이오스는 지금 넣어 둔 PS1 바이오스를 그대로 써요.",
                        Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용 (다시 켜기)", choice -> {
                    if (choice == current) {
                        return;
                    }
                    if (choice == 0 && !new File(new File(getFilesDir(), "system"), "scph1001.bin").isFile()) {
                        Toast.makeText(this, "SwanStation은 PS1 바이오스가 있어야 해요. PS1 화면의 \"PS1 BIOS\" 칸에서 넣어 주세요.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String core = choice == 0 ? MainActivity.CORE_SWAN : MainActivity.CORE_PCSX;
                    MainActivity.sharedSettings(this).edit().putString(MainActivity.ps1EngineKey(romFileName()), core).commit();
                    menu.dismiss();
                    relaunchWithCore(core);
                }));
    }

    // Like relaunchGame, on another engine.
    private void relaunchWithCore(String core) {
        this.menuExiting = true;
        Intent game = new Intent(getIntent());
        game.putExtra("com.example.minigba.CORE_ID", core);
        Intent relaunch = new Intent(this, MainActivity.class);
        relaunch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        relaunch.putExtra(MainActivity.EXTRA_RELAUNCH_GAME, game);
        startActivity(relaunch);
        callCore(NativeGba::saveNow, null, null);
        finish();
    }

    // SwanStation picture settings, per game.
    private static final String[] SWAN_FILTER_NAMES = {"끔 (선명하게)", "부드럽게 (쌍선형)", "JINC2", "xBR"};
    private static final String[] SWAN_FILTER_VALUES = {"Nearest", "Bilinear", "JINC2", "xBR"};
    private static final String[] SWAN_FILTER_NOTES = {"원래 픽셀 그대로", "텍스처를 부드럽게", "선명하게 다듬기", "2D 그림을 매끈하게 (무거움)"};
    private static final String[] SWAN_MSAA_NAMES = {"끔", "2배", "4배"};
    private static final String[] SWAN_MSAA_VALUES = {"1", "2", "4"};

    private int swanInt(String name, int fallback) {
        return MainActivity.sharedSettings(this).getInt("ps1q:" + this.saveBaseName + ":" + name, fallback);
    }

    private void setSwanInt(String name, int value) {
        MainActivity.sharedSettings(this).edit().putInt("ps1q:" + this.saveBaseName + ":" + name, value).apply();
    }

    private int swanScale() {
        int value = swanInt("res", 2);
        return value >= 1 && value <= 4 ? value : 2;
    }

    private boolean swanVulkan() {
        return swanInt("vulkan", 0) == 1;
    }

    private String[][] swanOptions() {
        int filter = Math.max(0, Math.min(SWAN_FILTER_VALUES.length - 1, swanInt("filter", 0)));
        int msaa = Math.max(0, Math.min(SWAN_MSAA_VALUES.length - 1, swanInt("msaa", 0)));
        boolean wide = swanInt("wide", 0) == 1;
        boolean pgxp = swanInt("pgxp", 1) == 1;
        boolean trueColor = swanInt("truecolor", 1) == 1;
        return new String[][]{
                {"swanstation_GPU_Renderer", "swanstation_GPU_ResolutionScale", "swanstation_GPU_PGXPEnable", "swanstation_GPU_TrueColor",
                        "swanstation_GPU_TextureFilter", "swanstation_GPU_MSAA", "swanstation_GPU_WidescreenHack", "swanstation_Display_AspectRatio",
                        "swanstation_Main_ApplyGameSettings", "swanstation_Display_ShowOSDMessages", "swanstation_GPU_UseThread"},
                {swanVulkan() ? "Vulkan" : "OpenGL", String.valueOf(swanScale()), pgxp ? "true" : "false", trueColor ? "true" : "false",
                        SWAN_FILTER_VALUES[filter], SWAN_MSAA_VALUES[msaa], wide ? "true" : "false", wide ? "16:9" : "Auto",
                        "true", "false", "true"}};
    }

    // A change while the game runs: the engine reads its options again at the next frame.
    private void applySwanOptions() {
        String[][] swan = swanOptions();
        NativeGba.setCoreOptions(swan[0], swan[1], false);
    }

    private void addSwanQualityRows(LinearLayout panel, Runnable refresh) {
        final int color = Color.rgb(79, 70, 229);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, 0, 0, dp(8));
        final String[] scaleNames = {"1배 (원래)", "2배", "3배", "4배"};
        final String[] scaleNotes = {"가장 가벼워요", "중급 휴대폰도 괜찮아요 (기본)", "스냅드래곤 8 계열 권장", "가장 선명 · 가장 무거움"};
        panel.addView(createSettingRow(RoundIconView.HD, color, "PS1 해상도", scaleNames[swanScale() - 1],
                view -> showChoiceDialog(RoundIconView.HD, color, "PS1 해상도 · 이 게임만", scaleNames, scaleNotes, swanScale() - 1,
                        "3D가 또렷해져요 (2D 배경 · 글자는 원래 그대로). 느리거나 뜨거우면 낮춰 주세요.",
                        Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    setSwanInt("res", choice + 1);
                    applySwanOptions();
                    refresh.run();
                })), params);
        panel.addView(swanToggleRow("흔들림 보정 (PGXP)", "pgxp", 1, "카메라가 움직일 때 바닥 · 벽이 출렁이던 것이 줄어요. 화면이 깨지는 게임은 꺼 주세요.", refresh), params);
        panel.addView(swanToggleRow("24비트 색 (줄무늬 없애기)", "truecolor", 1, "하늘 · 그림자의 색 줄무늬(디더링)가 없어져요.", refresh), params);
        panel.addView(createSettingRow(RoundIconView.FILTER, color, "텍스처 다듬기", SWAN_FILTER_NAMES[Math.max(0, Math.min(3, swanInt("filter", 0)))],
                view -> showChoiceDialog(RoundIconView.FILTER, color, "텍스처 다듬기 · 이 게임만", SWAN_FILTER_NAMES, SWAN_FILTER_NOTES, swanInt("filter", 0),
                        "게임 속 그림(텍스처)을 다듬는 방법이에요.", Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    setSwanInt("filter", choice);
                    applySwanOptions();
                    refresh.run();
                })), params);
        panel.addView(swanToggleRow("와이드스크린 16:9", "wide", 0, "3D 게임은 화면 옆이 넓어져요. 2D · 동영상 장면은 늘어날 수 있고, 깨지는 게임은 꺼 주세요.", refresh), params);
        panel.addView(createSettingRow(RoundIconView.HD, color, "계단 현상 줄이기 (MSAA)", SWAN_MSAA_NAMES[Math.max(0, Math.min(2, swanInt("msaa", 0)))],
                view -> showChoiceDialog(RoundIconView.HD, color, "MSAA · 이 게임만", SWAN_MSAA_NAMES, new String[]{"가장 가벼움", "조금 무거움", "무거움"}, swanInt("msaa", 0),
                        "3D 물체 가장자리의 계단을 줄여요. 해상도를 올리는 것보다 효과가 작아요.", Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    setSwanInt("msaa", choice);
                    applySwanOptions();
                    refresh.run();
                })), params);
        panel.addView(createSettingRow(RoundIconView.GEAR, color, "PS1 그래픽", swanVulkan() ? "Vulkan" : "OpenGL",
                view -> showChoiceDialog(RoundIconView.GEAR, color, "PS1 그래픽 · 이 게임만", new String[]{"OpenGL (기본)", "Vulkan"},
                        new String[]{"대부분의 휴대폰에서 안정적", "일부 휴대폰에서 더 빨라요"}, swanVulkan() ? 1 : 0,
                        "바꾸면 장면을 저장하고 다시 켜요.", Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    if ((choice == 1) == swanVulkan()) {
                        return;
                    }
                    setSwanInt("vulkan", choice);
                    relaunchGame();
                })), params);
    }

    private LinearLayout swanToggleRow(String title, String name, int fallback, String note, Runnable refresh) {
        final int color = Color.rgb(79, 70, 229);
        final boolean on = swanInt(name, fallback) == 1;
        return createSettingRow(RoundIconView.HD, color, title, on ? "켬" : "끔",
                view -> showChoiceDialog(RoundIconView.HD, color, title + " · 이 게임만", new String[]{"켬", "끔"}, new String[]{"", ""}, on ? 0 : 1,
                        note, Color.rgb(238, 242, 255), Color.rgb(55, 48, 163), "적용", choice -> {
                    setSwanInt(name, choice == 0 ? 1 : 0);
                    applySwanOptions();
                    refresh.run();
                }));
    }

    // ---- 세로 화면 게임 (0.0.346): vertical arcade games stood up. FBNeo and MAME ask the
    // frontend to turn the picture (SET_ROTATION); the bridge turns the copied frame and the
    // layout gives the screen a 3:4 frame. Per game: upright, on its side, upright reversed. ----
    private static final String[] TATE_NAMES = {"세워서 보기 (추천)", "눕혀서 보기", "반대로 세우기"};
    private static final String[] TATE_NOTES = {"세로 폰에서 크게 · 가로 폰에선 가운데", "엔진이 그리는 그대로. 폰을 돌려 들고 할 때", "화면이 거꾸로 나오는 게임 (칵테일 기판)"};

    // Naomi · Atomiswave on Flycast too (0.0.366): the app turns its GPU picture.
    private boolean isRotatingArcadeCore() {
        return "fbneo".equals(this.coreId) || "mame2003plus".equals(this.coreId) || "mame2010".equals(this.coreId) || "mame2015".equals(this.coreId)
                || isNaomi();
    }

    private int arcadeRotationMode() {
        int value = MainActivity.sharedSettings(this).getInt("tate:" + this.saveBaseName, 0);
        return value >= 0 && value <= 2 ? value : 0;
    }

    // Learned from the core the first time the game ran (it asks for a quarter turn). A Naomi
    // set marked vertical in the set table is stood up from its first start.
    private boolean arcadeVerticalKnown() {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        String key = "tate_v:" + this.saveBaseName;
        if (!prefs.contains(key) && isNaomi()) {
            NaomiSets.Set set = naomiSet();
            return set != null && set.vertical;
        }
        return prefs.getBoolean(key, false);
    }

    private boolean arcadeUpright() {
        return isRotatingArcadeCore() && arcadeVerticalKnown() && arcadeRotationMode() != 1;
    }

    private void checkArcadeVertical(final long handle) {
        getWindow().getDecorView().postDelayed(() -> {
            if (this.destroyed || this.nativeHandle != handle) {
                return;
            }
            int turn = NativeGba.arcadeRotation(handle);
            boolean vertical = turn == 1 || turn == 3;
            if (vertical != arcadeVerticalKnown()) {
                MainActivity.sharedSettings(this).edit().putBoolean("tate_v:" + this.saveBaseName, vertical).apply();
                rebuildContent();
                if (vertical) {
                    Toast.makeText(this, "세로 화면 게임이라 세워서 보여 줘요 · 메뉴 › 화면 › 세로 게임 화면에서 바꿀 수 있어요", Toast.LENGTH_LONG).show();
                }
            }
        }, 1500);
    }

    private LinearLayout createArcadeRotationRow(Runnable refresh) {
        final int color = Color.rgb(13, 148, 136);
        final int current = arcadeRotationMode();
        return createSettingRow(RoundIconView.ROTATE, color, "세로 게임 화면", TATE_NAMES[current].replace(" (추천)", ""),
                view -> showChoiceDialog(RoundIconView.ROTATE, color, "세로 게임 화면 · 이 게임만", TATE_NAMES, TATE_NOTES, current,
                        "세로 모니터용 아케이드 게임이에요. 세워서 보면 세로 폰에서 화면이 크게 나와요.", Color.rgb(240, 253, 250), Color.rgb(17, 94, 89), "적용", choice -> {
                    if (choice == current) {
                        return;
                    }
                    MainActivity.sharedSettings(this).edit().putInt("tate:" + this.saveBaseName, choice).apply();
                    NativeGba.setRotationMode(choice);
                    if (this.pauseDialog != null) {
                        this.pauseDialog.dismiss();
                    }
                    rebuildContent();
                }));
    }

    // ---- 연사 (0.0.346): chosen buttons fire repeatedly while held (or from one press to the
    // next), for every system. The bridge clears them on the off frames. ----
    private static final int[] TURBO_RATES = {8, 12, 15, 20, 30};
    private int turboLatched;
    private int turboLastRaw;

    private String turboKey(String name) {
        return "turbo:" + this.saveBaseName + ":" + name;
    }

    private int turboMask() {
        // TA/TB are turbo by themselves, not chosen
        return MainActivity.sharedSettings(this).getInt(turboKey("mask"), 0) & ~fixedTurboBits();
    }

    // 패미컴 TA/TB · PC엔진 TI/TII: turbo buttons of their own, at the 연사 speed (0.0.365).
    private int fixedTurboBits() {
        return isNes() || isPcEngine() ? 1024 | 2048 : 0;
    }

    private int turboRate() {
        // 15 for the consoles whose TA/TB fired 15 a second before
        int index = MainActivity.sharedSettings(this).getInt(turboKey("rate"), fixedTurboBits() != 0 ? 2 : 1);
        return TURBO_RATES[Math.max(0, Math.min(TURBO_RATES.length - 1, index))];
    }

    private boolean turboToggleMode() {
        return MainActivity.sharedSettings(this).getBoolean(turboKey("toggle"), false);
    }

    private boolean turboEnabled() {
        return MainActivity.sharedSettings(this).getBoolean(turboKey("on"), true);
    }

    private int turboActiveMask() {
        return turboEnabled() ? turboMask() : 0;
    }

    private void applyTurbo() {
        int mask = turboActiveMask();
        NativeGba.setTurbo(mask, turboRate());
        NativeGba.setFixedTurbo(turboEnabled());
        TouchButton.turboMask = mask;
        TouchButton.fixedTurboMask = turboEnabled() ? fixedTurboBits() : 0;
        TouchButton.turboRate = turboRate();
        if (mask == 0) {
            this.turboLatched = 0;
        }
        View root = getWindow().getDecorView();
        java.util.ArrayDeque<View> views = new java.util.ArrayDeque<>();
        views.add(root);
        while (!views.isEmpty()) {
            View view = views.poll();
            if (view instanceof TouchButton) {
                view.invalidate();
            } else if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int k = 0; k < group.getChildCount(); k++) {
                    views.add(group.getChildAt(k));
                }
            }
        }
        updateTurboPill();
    }

    // 한 번 눌러 켜기: a press of a turbo button starts it, the next stops it.
    private int turboToggleFilter(int mask) {
        int turbo = turboActiveMask() | (turboEnabled() ? fixedTurboBits() : 0);
        if (turbo == 0 || !turboToggleMode()) {
            this.turboLastRaw = mask;
            return mask;
        }
        int pressedNow = mask & turbo & ~this.turboLastRaw;
        this.turboLatched ^= pressedNow;
        this.turboLastRaw = mask;
        return (mask & ~turbo) | this.turboLatched;
    }

    // The face buttons on screen: {label, bit}, without the D-pad, START and SELECT.
    private java.util.List<Object[]> turboCandidates() {
        java.util.List<Object[]> list = new java.util.ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<View> views = new java.util.ArrayDeque<>();
        views.add(getWindow().getDecorView());
        while (!views.isEmpty()) {
            View view = views.poll();
            if (view instanceof TouchButton) {
                TouchButton button = (TouchButton) view;
                int bit = button.buttonMask();
                if (bit != 0 && Integer.bitCount(bit) == 1 && (bit & 0xFC) == 0 && (bit & fixedTurboBits()) == 0 && seen.add(bit)) {
                    list.add(new Object[]{button.label(), bit});
                }
            } else if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int k = 0; k < group.getChildCount(); k++) {
                    views.add(group.getChildAt(k));
                }
            }
        }
        if (list.isEmpty()) {
            String[] names = {"A", "B", "C", "D", "L", "R"};
            int[] bits = {1, 2, 1024, 2048, 512, 256};
            for (int k = 0; k < names.length; k++) {
                list.add(new Object[]{names[k], bits[k]});
            }
        }
        return list;
    }

    private String turboShown() {
        int mask = turboMask();
        if (mask == 0 && fixedTurboBits() == 0) {
            return "끔";
        }
        StringBuilder sb = new StringBuilder();
        if (fixedTurboBits() != 0) {
            String[] labels = turboLabels();
            sb.append(labels[2]).append(" · ").append(labels[3]);
        }
        for (Object[] c : turboCandidates()) {
            if ((mask & (Integer) c[1]) != 0) {
                sb.append(sb.length() == 0 ? "" : " · ").append(c[0]);
            }
        }
        return (sb.length() == 0 ? "켬" : sb) + " · 초당 " + turboRate() + (turboEnabled() ? "" : " (꺼 둠)");
    }

    private LinearLayout createTurboRow(Runnable refresh) {
        return createSettingRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "연사 (터보)", turboShown(), view -> showTurboSheet(refresh));
    }

    private void showTurboSheet(Runnable refresh) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("연사 (터보) · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(fixedTurboBits() != 0
                ? "연사로 할 버튼을 더 고르세요. " + turboLabels()[2] + " · " + turboLabels()[3] + "는 원래부터 연사 버튼이라 늘 켜져 있고, 아래 속도 · 한 번 눌러 켜기 · ⚡ 끄기가 같이 적용돼요."
                : "연사로 할 버튼을 고르세요. 누르고 있으면 빠르게 연타해요. 블루투스 · USB 게임패드도 똑같이 돼요.");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(10));
        panel.addView(sub);
        final int amber = Color.rgb(217, 119, 6);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(0);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        panel.addView(chipScroll);
        if (fixedTurboBits() != 0) {
            // TA/TB (TI/TII): always turbo, shown first and not chosen
            for (String fixedLabel : new String[]{turboLabels()[2], turboLabels()[3]}) {
                TextView chip = new TextView(this);
                chip.setText(fixedLabel + " ⚡");
                chip.setTextSize(14.0f);
                chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), dp(8), dp(14), dp(8));
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Theme.bg(Color.rgb(253, 230, 138)));
                shape.setCornerRadius(dp(10));
                shape.setStroke(dp(2), Theme.line(Color.rgb(217, 119, 6)), dp(5), dp(3));
                chip.setBackground(shape);
                chip.setTextColor(Theme.text(Color.rgb(113, 63, 18)));
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
                chipParams.setMargins(0, 0, dp(6), 0);
                chips.addView(chip, chipParams);
            }
        }
        for (Object[] c : turboCandidates()) {
            final int bit = (Integer) c[1];
            final TextView chip = new TextView(this);
            chip.setText(String.valueOf(c[0]));
            chip.setTextSize(14.0f);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            final Runnable paint = () -> {
                boolean on = (turboMask() & bit) != 0;
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Theme.bg(on ? Color.rgb(254, 249, 195) : Color.rgb(243, 244, 246)));
                shape.setCornerRadius(dp(10));
                shape.setStroke(dp(2), Theme.line(on ? Color.rgb(234, 179, 8) : Color.TRANSPARENT));
                chip.setBackground(shape);
                chip.setTextColor(Theme.text(on ? Color.rgb(113, 63, 18) : Color.rgb(75, 85, 99)));
            };
            paint.run();
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(turboKey("mask"), turboMask() ^ bit).apply();
                paint.run();
                applyTurbo();
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.setMargins(0, 0, dp(6), 0);
            chips.addView(chip, chipParams);
        }
        // Speed.
        TextView speedLabel = new TextView(this);
        speedLabel.setText("속도 (초당 누르는 횟수) · 12~15 추천");
        speedLabel.setTextSize(12.0f);
        speedLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        speedLabel.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        speedLabel.setPadding(dp(2), dp(14), 0, dp(6));
        panel.addView(speedLabel);
        LinearLayout speeds = new LinearLayout(this);
        final TextView[] speedViews = new TextView[TURBO_RATES.length];
        for (int k = 0; k < TURBO_RATES.length; k++) {
            final int index = k;
            TextView speed = new TextView(this);
            speed.setText(String.valueOf(TURBO_RATES[k]));
            speed.setTextSize(13.0f);
            speed.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            speed.setGravity(Gravity.CENTER);
            speedViews[k] = speed;
            speed.setOnClickListener(v -> {
                prefs.edit().putInt(turboKey("rate"), index).apply();
                for (int j = 0; j < speedViews.length; j++) {
                    boolean on = TURBO_RATES[j] == turboRate();
                    android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                    shape.setColor(Theme.bg(on ? amber : Color.rgb(229, 231, 235)));
                    shape.setCornerRadius(dp(9));
                    speedViews[j].setBackground(shape);
                    speedViews[j].setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(75, 85, 99)));
                }
                applyTurbo();
            });
            LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(0, dp(36), 1.0f);
            speedParams.setMargins(k == 0 ? 0 : dp(5), 0, 0, 0);
            speeds.addView(speed, speedParams);
        }
        panel.addView(speeds);
        speedViews[0].post(() -> {
            for (int j = 0; j < speedViews.length; j++) {
                boolean on = TURBO_RATES[j] == turboRate();
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Theme.bg(on ? amber : Color.rgb(229, 231, 235)));
                shape.setCornerRadius(dp(9));
                speedViews[j].setBackground(shape);
                speedViews[j].setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(75, 85, 99)));
            }
        });
        // Mode and the on-screen switch.
        panel.addView(turboSwitchRow("한 번 눌러 켜기", "누르면 손을 떼도 계속 연타, 다시 누르면 멈춤 (끄면: 누르는 동안만)", "toggle", false));
        panel.addView(turboSwitchRow("화면에 연사 켜고 끄기 버튼", "게임 화면 위쪽에 ⚡ 버튼 · 연사를 잠깐 끌 때", "button", false));
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.setMargins(0, dp(14), 0, 0);
        panel.addView(close, closeParams);
        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> {
            applyTurbo();
            if (MainActivity.sharedSettings(this).getBoolean(turboKey("button"), false) != (this.turboPill != null)) {
                rebuildContent();
            }
            refresh.run();
        });
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
        dialog.show();
    }

    private LinearLayout turboSwitchRow(String title, String note, String key, boolean fallback) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        shape.setCornerRadius(dp(12));
        row.setBackground(shape);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(1);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.0f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        words.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextSize(11.0f);
        n.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(prefs.getBoolean(turboKey(key), fallback));
        toggle.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(turboKey(key), on).apply();
            this.turboLatched = 0;
        });
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    // "⚡ 연사 켬 / 끔" over the picture, when chosen.
    private TextView turboPill;

    private void addTurboPill(FrameLayout screenFrame) {
        this.turboPill = null;
        if (!MainActivity.sharedSettings(this).getBoolean(turboKey("button"), false) || (turboMask() == 0 && fixedTurboBits() == 0)) {
            return;
        }
        TextView pill = new TextView(this);
        pill.setTextSize(11.0f);
        pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        pill.setOnClickListener(v -> {
            MainActivity.sharedSettings(this).edit().putBoolean(turboKey("on"), !turboEnabled()).apply();
            applyTurbo();
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(8), dp(8), 0);
        screenFrame.addView(pill, params);
        this.turboPill = pill;
        updateTurboPill();
    }

    private void updateTurboPill() {
        TextView pill = this.turboPill;
        if (pill == null) {
            return;
        }
        boolean on = turboEnabled();
        pill.setText(on ? "⚡ 연사 켬 · " + turboRate() : "⚡ 연사 끔");
        pill.setTextColor(on ? Color.rgb(113, 63, 18) : Color.WHITE);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(on ? Color.argb(235, 254, 240, 138) : Color.argb(200, 55, 65, 81));
        shape.setCornerRadius(dp(12));
        pill.setBackground(shape);
    }

    // ---- 3DS 화면 배치 (0.0.352): per game and per screen direction (세로 · 가로 with the
    // touch controls · 컨트롤러). "default" keeps the engine's own stacked / side-by-side
    // layouts; the others are worked out here, in pixels of the view the picture is shown in,
    // and given to the engine as its custom layout. ⇅ swaps what the two places show. ----

    private static final String[] N3DS_PRESETS = {"default", "bigtop", "bigbot", "top", "bot", "side", "custom"};
    private static final String[] DS_PRESETS = {"default", "bigtop", "bigbot", "top", "bot", "side", "hybrid", "gapwide", "custom"};

    private String[] layoutPresets() {
        return isNds() ? DS_PRESETS : N3DS_PRESETS;
    }

    private String layoutPresetName(String preset, String ctx) {
        switch (preset) {
            case "bigtop": return "위 크게\n아래 작게";
            case "bigbot": return "아래 크게\n위 작게";
            case "top": return "위 화면만";
            case "bot": return "아래 화면만";
            case "side": return "나란히";
            case "hybrid": return "하이브리드\n(큰 것+둘 다 작게)";
            case "gapwide": return "위아래\n틈 넓게";
            case "custom": return "직접 편집 ✎";
            default: return "pad".equals(ctx) ? "나란히 (기본)" : "위아래 (기본)";
        }
    }
    private boolean n3dsSwap;
    private int[] n3dsRects;            // top, bottom, extra (DS hybrid) l,t,r,b in surface pixels
    private TextView n3dsFab;
    private boolean n3dsTouchIgnored;
    private String n3dsSentSpec;

    private String n3dsKey(String what) {
        return (isNds() ? "dslay:" : "n3dslay:") + this.saveBaseName + ":" + what;
    }

    private String n3dsContext() {
        return isPadScreen() ? "pad" : this.landscapeMode ? "land" : "port";
    }

    private String n3dsPreset(String ctx) {
        String preset = MainActivity.sharedSettings(this).getString(n3dsKey("p:" + ctx), "default");
        if ("pad".equals(ctx) && "side".equals(preset)) {
            return "default";   // the engine's side by side is the pad's default
        }
        if ("custom".equals(preset) && n3dsCustom(ctx) == null) {
            return "default";
        }
        return preset;
    }

    private boolean n3dsOwnLayout() {
        return (isN3ds() || isNds()) && !"default".equals(n3dsPreset(n3dsContext()));
    }

    private float n3dsSmall() {
        return Math.max(0.25f, Math.min(0.5f, MainActivity.sharedSettings(this).getFloat(n3dsKey("small"), 0.4f)));
    }

    private String n3dsPos() {
        return MainActivity.sharedSettings(this).getString(n3dsKey("pos"), "br");
    }

    private boolean n3dsFabOn() {
        return MainActivity.sharedSettings(this).getBoolean(n3dsKey("fab"), true);
    }

    // The saved 직접 편집 layout: 8 fractions of the frame (top l,t,r,b, bottom l,t,r,b), or null.
    private float[] n3dsCustom(String ctx) {
        String text = MainActivity.sharedSettings(this).getString(n3dsKey("c:" + ctx), null);
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",");
        if (parts.length != 8) {
            return null;
        }
        float[] out = new float[8];
        try {
            for (int k = 0; k < 8; k++) {
                out[k] = Math.max(0.0f, Math.min(1.0f, Float.parseFloat(parts[k])));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    // Height : width of the portrait 직접 편집 frame.
    private float n3dsPortRatio() {
        return Math.max(0.4f, Math.min(1.6f, MainActivity.sharedSettings(this).getFloat(n3dsKey("ca:port"), (layTopH() + layBotH()) / layTopW())));
    }

    // Screen sizes: 3DS 400x240 over 320x240, DS 256x192 twice.
    private float layTopW() {
        return isNds() ? 256.0f : 400.0f;
    }

    private float layTopH() {
        return isNds() ? 192.0f : 240.0f;
    }

    private float layBotW() {
        return isNds() ? 256.0f : 320.0f;
    }

    private float layBotH() {
        return isNds() ? 192.0f : 240.0f;
    }

    // DS 위아래 틈 넓게: the gap as a part of a screen's height.
    private float dsGap() {
        return Math.max(0.0f, Math.min(1.0f, MainActivity.sharedSettings(this).getFloat(n3dsKey("gap"), 0.3f)));
    }

    // Portrait: {W, H, top l,t,r,b, bottom l,t,r,b, extra l,t,r,b} in console pixels; the
    // screen frame gets W:H. "extra" is the DS hybrid's small copy of the upper screen.
    private float[] n3dsUnitLayout(String preset) {
        float s = n3dsSmall();
        String pos = n3dsPos();
        float tw = layTopW();
        float th = layTopH();
        float bw = layBotW();
        float bh = layBotH();
        float gap = 6.0f;
        switch (preset) {
            case "bigtop": {
                float w = tw * s;
                float h = w * bh / bw;
                float x = "bl".equals(pos) ? 0.0f : "bc".equals(pos) ? (tw - w) / 2.0f : tw - w;
                return new float[]{tw, th + gap + h, 0, 0, tw, th, x, th + gap, x + w, th + gap + h, 0, 0, 0, 0};
            }
            case "bigbot": {
                // the lower screen as wide as the upper one would be
                float bigH = tw * bh / bw;
                float w = tw * s;
                float h = w * th / tw;
                float x = "bl".equals(pos) ? 0.0f : "bc".equals(pos) ? (tw - w) / 2.0f : tw - w;
                return new float[]{tw, bigH + gap + h, x, bigH + gap, x + w, bigH + gap + h, 0, 0, tw, bigH, 0, 0, 0, 0};
            }
            case "top":
                return new float[]{tw, th, 0, 0, tw, th, 0, 0, 0, 0, 0, 0, 0, 0};
            case "bot":
                return new float[]{bw, bh, 0, 0, 0, 0, 0, 0, bw, bh, 0, 0, 0, 0};
            case "side":
                return new float[]{tw + bw, Math.max(th, bh), 0, 0, tw, th, tw, 0, tw + bw, bh, 0, 0, 0, 0};
            case "hybrid": {
                // DS: the upper screen big, both small side by side under it
                float half = (tw - gap) / 2.0f;
                float h = half * th / tw;
                return new float[]{tw, th + gap + h, 0, 0, tw, th, half + gap, th + gap, tw, th + gap + h, 0, th + gap, half, th + gap + h};
            }
            case "gapwide": {
                float g = th * dsGap();
                return new float[]{tw, th + g + bh, 0, 0, tw, th, (tw - bw) / 2.0f, th + g, (tw + bw) / 2.0f, th + g + bh, 0, 0, 0, 0};
            }
            case "custom": {
                float[] c = n3dsCustom("port");
                if (c != null) {
                    // the frame's height as edited (it stops above the controls), 400:480 before 0.0.357
                    float fh = tw * n3dsPortRatio();
                    return new float[]{tw, fh, c[0] * tw, c[1] * fh, c[2] * tw, c[3] * fh, c[4] * tw, c[5] * fh, c[6] * tw, c[7] * fh, 0, 0, 0, 0};
                }
                // fall through
            }
            default:
                return new float[]{tw, th + bh, 0, 0, tw, th, (tw - bw) / 2.0f, th, (tw + bw) / 2.0f, th + bh, 0, 0, 0, 0};
        }
    }

    // A rectangle of the given width:height as large as fits in the area, centred.
    private static android.graphics.Rect n3dsFit(android.graphics.Rect area, float aspect) {
        int w = Math.max(1, Math.min(area.width(), Math.round(area.height() * aspect)));
        int h = Math.max(1, Math.round(w / aspect));
        int x = area.left + (area.width() - w) / 2;
        int y = area.top + (area.height() - h) / 2;
        return new android.graphics.Rect(x, y, x + w, y + h);
    }

    // Where a view really is on the window (after any moving or scaling of it or its
    // parents by the layout editor), or null.
    private static android.graphics.Rect n3dsVisual(View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        // the top-left corner as drawn, and the size times every scale on the way up
        int[] where = new int[2];
        view.getLocationInWindow(where);
        float sx = 1.0f;
        float sy = 1.0f;
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            sx *= v.getScaleX();
            sy *= v.getScaleY();
        }
        return new android.graphics.Rect(where[0], where[1], where[0] + Math.round(view.getWidth() * sx), where[1] + Math.round(view.getHeight() * sy));
    }

    // The touch controls over the picture, in the surface's own pixels.
    private java.util.List<android.graphics.Rect> n3dsControlBounds() {
        java.util.List<android.graphics.Rect> out = new java.util.ArrayList<>();
        View surface = this.surfaceView;
        android.graphics.Rect s = surface == null ? null : n3dsVisual(surface);
        if (s == null) {
            return out;
        }
        float kx = surface.getWidth() / (float) s.width();
        float ky = surface.getHeight() / (float) s.height();
        for (android.graphics.Rect c : n3dsControlVisuals()) {
            out.add(new android.graphics.Rect(Math.round((c.left - s.left) * kx), Math.round((c.top - s.top) * ky),
                    Math.round((c.right - s.left) * kx), Math.round((c.bottom - s.top) * ky)));
        }
        return out;
    }

    // The touch controls where they are on the window.
    private java.util.List<android.graphics.Rect> n3dsControlVisuals() {
        java.util.List<android.graphics.Rect> out = new java.util.ArrayList<>();
        if (this.surfaceView != null) {
            collectN3dsControls(this.surfaceView.getRootView(), out);
        }
        return out;
    }

    private void collectN3dsControls(View view, java.util.List<android.graphics.Rect> out) {
        if (view.getVisibility() != View.VISIBLE || view instanceof SurfaceView || view == this.n3dsFab) {
            return;
        }
        if (isLayoutControl(view) && view.getWidth() > 0) {
            android.graphics.Rect r = n3dsVisual(view);
            if (r != null) {
                out.add(r);
            }
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectN3dsControls(group.getChildAt(i), out);
            }
        }
    }

    // The biggest free stretch of a side strip (x0..x1) between the controls, for the small
    // screen: its rectangle there, or null when it would be too small.
    private android.graphics.Rect n3dsSideGap(int x0, int x1, int height, float aspect, int maxWidth, java.util.List<android.graphics.Rect> controls) {
        int m = dp(6);
        java.util.List<int[]> used = new java.util.ArrayList<>();
        for (android.graphics.Rect r : controls) {
            if (r.right > x0 && r.left < x1) {
                used.add(new int[]{r.top - m, r.bottom + m});
            }
        }
        java.util.Collections.sort(used, (a, b) -> Integer.compare(a[0], b[0]));
        int bestTop = 0;
        int bestBottom = 0;
        int at = m;
        for (int[] u : used) {
            if (u[0] - at > bestBottom - bestTop) {
                bestTop = at;
                bestBottom = u[0];
            }
            at = Math.max(at, u[1]);
        }
        if (height - m - at > bestBottom - bestTop) {
            bestTop = at;
            bestBottom = height - m;
        }
        if (bestBottom - bestTop < dp(40)) {
            return null;
        }
        android.graphics.Rect area = new android.graphics.Rect(x0 + m, bestTop, x1 - m, bestBottom);
        android.graphics.Rect fit = n3dsFit(area, aspect);
        if (fit.width() > maxWidth) {
            int w = maxWidth;
            int h = Math.round(w / aspect);
            int x = area.left + (area.width() - w) / 2;
            int y = area.top + (area.height() - h) / 2;
            fit = new android.graphics.Rect(x, y, x + w, y + h);
        }
        return fit.width() < dp(56) ? null : fit;
    }

    // Where the screens go in the game screen as it is (W x H: its size as built, or as the
    // 버튼 · 화면 배치 편집 made it — that frame is the limit, 0.0.362): top l,t,r,b, bottom
    // l,t,r,b, extra l,t,r,b (an empty rectangle for a screen not shown; extra only for the
    // DS hybrid). The layout is fitted into the frame, centred, each screen in its own shape.
    private int[] n3dsRectsFor(String ctx, String preset, int w, int h, java.util.List<android.graphics.Rect> controls) {
        int[] out = new int[12];
        float[] c = "custom".equals(preset) ? n3dsCustom(ctx) : null;
        if (c != null) {
            for (int k = 0; k < 8; k++) {
                out[k] = Math.round(c[k] * (k % 2 == 0 ? w : h));
            }
            // saved before 0.0.363 for a frame of another shape, the two may overlap: then
            // the plain layout until it is edited again
            if (android.graphics.Rect.intersects(new android.graphics.Rect(out[0], out[1], out[2], out[3]),
                    new android.graphics.Rect(out[4], out[5], out[6], out[7]))) {
                c = null;
            }
        }
        if (c == null) {
            if ("custom".equals(preset)) {
                java.util.Arrays.fill(out, 0);
            }
            float[] u = n3dsUnitLayout("custom".equals(preset) ? "default" : preset);
            float scale = Math.min(w / u[0], h / u[1]);
            float dx = (w - u[0] * scale) / 2.0f;
            float dy = (h - u[1] * scale) / 2.0f;
            for (int k = 0; k < 12; k++) {
                out[k] = Math.round(u[2 + k] * scale + (k % 2 == 0 ? dx : dy));
            }
        }
        // each screen kept in its own shape inside its place (a frame of another shape than
        // the one a layout was edited in)
        float[] aspects = {layTopW() / layTopH(), layBotW() / layBotH(), layTopW() / layTopH()};
        for (int s = 0; s < 3 && c == null; s++) {
            int o = s * 4;
            if (!n3dsHas(out, o)) {
                continue;
            }
            android.graphics.Rect fit = n3dsFit(new android.graphics.Rect(out[o], out[o + 1], out[o + 2], out[o + 3]), aspects[s]);
            out[o] = fit.left;
            out[o + 1] = fit.top;
            out[o + 2] = fit.right;
            out[o + 3] = fit.bottom;
        }
        return out;
    }

    // Works the layout out for the view as it is now and gives it to the engine (3DS) or the
    // presenter (DS).
    private void applyN3dsLayout() {
        if (!isN3ds() && !isNds()) {
            return;
        }
        SurfaceView surface = this.surfaceView;
        int w = surface == null ? 0 : surface.getWidth();
        int h = surface == null ? 0 : surface.getHeight();
        int[] rects = null;
        if (n3dsOwnLayout() && w > 16 && h > 16) {
            String ctx = n3dsContext();
            rects = n3dsRectsFor(ctx, n3dsPreset(ctx), w, h, "land".equals(ctx) ? n3dsControlBounds() : new java.util.ArrayList<>());
        }
        this.n3dsRects = rects;
        this.n3dsViewW = w;
        this.n3dsViewH = h;
        if (rects == null) {
            this.n3dsSwap = false;
        }
        sendN3dsLayout();
        placeN3dsFab();
    }

    private int n3dsViewW;
    private int n3dsViewH;

    private void sendN3dsLayout() {
        int[] r = this.n3dsRects;
        if (isNds()) {
            NativeGba.setDsLayout(this.nativeHandle, r == null ? null : dsBlits(r, this.n3dsSwap));
            return;
        }
        String spec = "";
        if (r != null) {
            StringBuilder sb = new StringBuilder().append(this.n3dsViewW).append(',').append(this.n3dsViewH);
            for (int k = 0; k < 8; k++) {
                sb.append(',').append(Math.max(0, r[k]));
            }
            spec = sb.toString();
        }
        this.n3dsSentSpec = spec;
        NativeGba.setN3dsLayout(this.nativeHandle, spec, this.n3dsSwap);
    }

    // DS: what the presenter copies where — [W, H, n, then source, l, t, r, b ...]. ⇅ swaps
    // what every place shows (the hybrid's two small ones still show both screens).
    private int[] dsBlits(int[] r, boolean swap) {
        java.util.List<int[]> blits = new java.util.ArrayList<>();
        if (n3dsHas(r, 0)) {
            blits.add(new int[]{swap ? 1 : 0, r[0], r[1], r[2], r[3]});
        }
        if (n3dsHas(r, 4)) {
            blits.add(new int[]{swap ? 0 : 1, r[4], r[5], r[6], r[7]});
        }
        if (r.length >= 12 && n3dsHas(r, 8)) {
            blits.add(new int[]{swap ? 1 : 0, r[8], r[9], r[10], r[11]});
        }
        // the smaller ones drawn last, over a bigger one they overlap
        java.util.Collections.sort(blits, (a, b) -> Long.compare((long) (b[3] - b[1]) * (b[4] - b[2]), (long) (a[3] - a[1]) * (a[4] - a[2])));
        int[] out = new int[3 + blits.size() * 5];
        out[0] = this.n3dsViewW;
        out[1] = this.n3dsViewH;
        out[2] = blits.size();
        for (int k = 0; k < blits.size(); k++) {
            System.arraycopy(blits.get(k), 0, out, 3 + k * 5, 5);
        }
        return out;
    }

    private static boolean n3dsHas(int[] r, int o) {
        return r.length >= o + 4 && r[o + 2] > r[o] && r[o + 3] > r[o + 1];
    }

    // ⇅ where it covers no screen: under the big one, beside the small one, above the
    // big one, or (no room) on the big one's corner.
    private void placeN3dsFab() {
        SurfaceView surface = this.surfaceView;
        int[] r = this.n3dsRects;
        String preset = n3dsPreset(n3dsContext());
        boolean want = surface != null && r != null && n3dsFabOn() && !"side".equals(preset) && !"gapwide".equals(preset)
                && surface.getParent() instanceof FrameLayout;
        if (!want) {
            if (this.n3dsFab != null && this.n3dsFab.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) this.n3dsFab.getParent()).removeView(this.n3dsFab);
            }
            this.n3dsFab = null;
            return;
        }
        FrameLayout parent = (FrameLayout) surface.getParent();
        if (this.n3dsFab == null || this.n3dsFab.getParent() != parent) {
            if (this.n3dsFab != null && this.n3dsFab.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) this.n3dsFab.getParent()).removeView(this.n3dsFab);
            }
            TextView fab = new TextView(this);
            fab.setText("⇅");
            fab.setTextSize(17.0f);
            fab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            fab.setTextColor(Color.WHITE);
            fab.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            shape.setColor(Color.argb(215, 14, 165, 233));
            fab.setBackground(shape);
            fab.setContentDescription("화면 바꾸기");
            fab.setOnClickListener(v -> {
                this.n3dsSwap = !this.n3dsSwap;
                sendN3dsLayout();
            });
            parent.addView(fab, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.START));
            this.n3dsFab = fab;
        }
        int f = dp(38);
        int g = dp(8);
        int w = surface.getWidth();
        int h = surface.getHeight();
        boolean topBig = !n3dsHas(r, 4) || (n3dsHas(r, 0) && (r[2] - r[0]) >= (r[6] - r[4]));
        int bo = topBig ? 0 : 4;
        int so = topBig ? 4 : 0;
        android.graphics.Rect big = new android.graphics.Rect(r[bo], r[bo + 1], r[bo + 2], r[bo + 3]);
        android.graphics.Rect small = n3dsHas(r, so) ? new android.graphics.Rect(r[so], r[so + 1], r[so + 2], r[so + 3]) : null;
        android.graphics.Rect third = n3dsHas(r, 8) ? new android.graphics.Rect(r[8], r[9], r[10], r[11]) : null;
        java.util.List<android.graphics.Rect> avoid = "land".equals(n3dsContext()) ? n3dsControlBounds() : new java.util.ArrayList<>();
        int[][] spots = {
                {big.centerX() - f / 2, big.bottom + g},
                small == null ? null : new int[]{small.left - f - g, small.centerY() - f / 2},
                small == null ? null : new int[]{small.right + g, small.centerY() - f / 2},
                {big.centerX() - f / 2, big.top - f - g},
        };
        int[] chosen = null;
        for (int[] spot : spots) {
            if (spot == null || spot[0] < 0 || spot[1] < 0 || spot[0] + f > w || spot[1] + f > h) {
                continue;
            }
            android.graphics.Rect box = new android.graphics.Rect(spot[0], spot[1], spot[0] + f, spot[1] + f);
            boolean clear = !android.graphics.Rect.intersects(box, big) && (small == null || !android.graphics.Rect.intersects(box, small))
                    && (third == null || !android.graphics.Rect.intersects(box, third));
            for (android.graphics.Rect c : avoid) {
                clear &= !android.graphics.Rect.intersects(box, c);
            }
            if (clear) {
                chosen = spot;
                break;
            }
        }
        if (chosen == null) {
            chosen = new int[]{big.left + g, big.bottom - f - g};
        }
        this.n3dsFab.setAlpha(chosen[1] + f <= big.bottom && chosen[1] >= big.top && chosen[0] >= big.left && chosen[0] + f <= big.right ? 0.6f : 1.0f);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) this.n3dsFab.getLayoutParams();
        int left = surface.getLeft() + chosen[0];
        int topEdge = surface.getTop() + chosen[1];
        if (params.leftMargin != left || params.topMargin != topEdge) {
            params.leftMargin = left;
            params.topMargin = topEdge;
            this.n3dsFab.setLayoutParams(params);
        }
        this.n3dsFab.bringToFront();
    }

    // The rectangles where the lower screen is shown now (⇅ swapped or not).
    private java.util.List<int[]> n3dsTouchRects() {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int[] r = this.n3dsRects;
        if (r == null) {
            return out;
        }
        int o = this.n3dsSwap ? 0 : 4;
        if (n3dsHas(r, o)) {
            out.add(new int[]{r[o], r[o + 1], r[o + 2], r[o + 3]});
        }
        // the DS hybrid's small copy shows the lower screen once swapped
        if (this.n3dsSwap && n3dsHas(r, 8)) {
            out.add(new int[]{r[8], r[9], r[10], r[11]});
        }
        return out;
    }

    private int[] n3dsTouchRect;   // the rectangle the finger came down in (DS)

    // A finger on the picture is the stylus only where the lower screen is shown now.
    private boolean n3dsTouchOutside(android.view.MotionEvent event) {
        int[] r = this.n3dsRects;
        if (r == null) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            this.n3dsTouchRect = null;
            java.util.List<int[]> rects = n3dsTouchRects();
            for (int[] t : rects) {
                if (x >= t[0] && x < t[2] && y >= t[1] && y < t[3]) {
                    this.n3dsTouchRect = t;
                }
            }
            this.n3dsTouchIgnored = this.n3dsTouchRect == null;
        }
        boolean ignored = this.n3dsTouchIgnored;
        if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            this.n3dsTouchIgnored = false;
        }
        return ignored;
    }

    // DS with its own layout: a finger in the lower screen's rectangle, as a point on the
    // engine's frame (the two screens one above the other, the lower one the bottom half),
    // in libretro's -32767..32767; null when the touch doesn't go to the engine as it is.
    private int[] dsPointer(android.view.MotionEvent event) {
        int[] t = this.n3dsTouchRect;
        if (!isNds() || this.n3dsRects == null || t == null) {
            return null;
        }
        float fx = Math.max(0.0f, Math.min(1.0f, (event.getX() - t[0]) / Math.max(1, t[2] - t[0])));
        float fy = Math.max(0.0f, Math.min(0.999f, (event.getY() - t[1]) / Math.max(1, t[3] - t[1])));
        float frameY = 0.5f + fy * 0.5f;
        return new int[]{Math.round((fx * 2.0f - 1.0f) * 32767.0f), Math.round((frameY * 2.0f - 1.0f) * 32767.0f)};
    }

    private LinearLayout createN3dsLayoutRow(Runnable refresh) {
        String shown = layoutPresetName(n3dsPreset(n3dsContext()), n3dsContext()).replace('\n', ' ');
        return createSettingRow(RoundIconView.MOVE, Color.rgb(14, 165, 233), (isNds() ? "DS" : "3DS") + " 화면 배치", shown, view -> showN3dsLayoutSheet(refresh));
    }

    private void showN3dsLayoutSheet(Runnable refresh) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        scroll.addView(panel);
        TextView title = new TextView(this);
        title.setText((isNds() ? "DS" : "3DS") + " 화면 배치 · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText("게임 화면 칸(버튼 · 화면 배치 편집으로 정한 크기와 자리) 안에서 두 화면을 나눠요. 세로 · 가로 · 컨트롤러를 따로 기억하고, 고르면 바로 바뀌어요. 아래 화면이 보이는 자리에서 터치돼요.");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(10));
        panel.addView(sub);
        final int blue = Color.rgb(14, 165, 233);
        final String[] ctxs = {"port", "land", "pad"};
        final String[] ctxNames = {"세로 화면", "가로 화면", "컨트롤러"};
        final String[] tab = {n3dsContext()};
        final boolean[] changed = {false};
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        android.graphics.drawable.GradientDrawable tabsBg = new android.graphics.drawable.GradientDrawable();
        tabsBg.setColor(Theme.bg(Color.rgb(241, 245, 249)));
        tabsBg.setCornerRadius(dp(12));
        tabs.setBackground(tabsBg);
        panel.addView(tabs, new LinearLayout.LayoutParams(-1, dp(40)));
        final TextView[] tabViews = new TextView[3];
        final android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, -2);
        gridParams.setMargins(0, dp(10), 0, 0);
        panel.addView(grid, gridParams);
        // Small screen size.
        TextView sizeLabel = new TextView(this);
        sizeLabel.setTextSize(12.0f);
        sizeLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sizeLabel.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        sizeLabel.setPadding(dp(2), dp(12), 0, dp(2));
        panel.addView(sizeLabel);
        android.widget.SeekBar size = new android.widget.SeekBar(this);
        size.setMax(25);
        size.setProgress(Math.round(n3dsSmall() * 100) - 25);
        panel.addView(size);
        final Runnable sizeText = () -> sizeLabel.setText("작은 화면 크기 · 큰 화면의 " + (size.getProgress() + 25) + "%");
        sizeText.run();
        // Small screen position (portrait).
        final TextView posLabel = new TextView(this);
        posLabel.setText("작은 화면 자리");
        posLabel.setTextSize(12.0f);
        posLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        posLabel.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        posLabel.setPadding(dp(2), dp(10), 0, dp(6));
        panel.addView(posLabel);
        final LinearLayout posRow = new LinearLayout(this);
        panel.addView(posRow);
        final String[] posKeys = {"bl", "bc", "br"};
        final String[] posNames = {"왼쪽 아래", "가운데 아래", "오른쪽 아래"};
        final TextView[] posViews = new TextView[3];
        final Runnable[] paintAll = new Runnable[1];
        final Runnable apply = () -> {
            changed[0] = true;
            if (tab[0].equals(n3dsContext())) {
                n3dsRelayout();
            }
            paintAll[0].run();
        };
        for (int k = 0; k < 3; k++) {
            final int index = k;
            TextView chip = new TextView(this);
            chip.setText(posNames[k]);
            chip.setTextSize(12.0f);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                prefs.edit().putString(n3dsKey("pos"), posKeys[index]).apply();
                apply.run();
            });
            posViews[k] = chip;
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
            chipParams.setMargins(k == 0 ? 0 : dp(5), 0, 0, 0);
            posRow.addView(chip, chipParams);
        }
        // DS: the gap of 위아래 틈 넓게 (books held open, Nintendogs and the like).
        final float[] gapValues = {0.1f, 0.3f, 0.6f};
        final TextView[] gapViews = new TextView[3];
        if (isNds()) {
            TextView gapLabel = new TextView(this);
            gapLabel.setText("두 화면 사이 틈 (위아래 틈 넓게)");
            gapLabel.setTextSize(12.0f);
            gapLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            gapLabel.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
            gapLabel.setPadding(dp(2), dp(10), 0, dp(6));
            panel.addView(gapLabel);
            LinearLayout gapRow = new LinearLayout(this);
            panel.addView(gapRow);
            String[] gapNames = {"조금", "보통", "넓게"};
            for (int k = 0; k < 3; k++) {
                final int index = k;
                TextView chip = new TextView(this);
                chip.setText(gapNames[k]);
                chip.setTextSize(12.0f);
                chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                chip.setGravity(Gravity.CENTER);
                chip.setOnClickListener(v -> {
                    prefs.edit().putFloat(n3dsKey("gap"), gapValues[index]).apply();
                    apply.run();
                });
                gapViews[k] = chip;
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
                chipParams.setMargins(k == 0 ? 0 : dp(5), 0, 0, 0);
                gapRow.addView(chip, chipParams);
            }
        }
        size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean user) {
                sizeText.run();
            }

            public void onStartTrackingTouch(android.widget.SeekBar bar) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar bar) {
                prefs.edit().putFloat(n3dsKey("small"), (bar.getProgress() + 25) / 100.0f).apply();
                apply.run();
            }
        });
        panel.addView(n3dsSwitchRow("화면 바꾸기 버튼 ⇅", "게임 화면 구석의 ⇅로 큰 화면과 작은 화면(한 화면만일 때는 위 · 아래)을 바꿔요", "fab", true, apply));
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(46));
        closeParams.setMargins(0, dp(14), 0, 0);
        panel.addView(close, closeParams);
        paintAll[0] = () -> {
            for (int k = 0; k < 3; k++) {
                boolean on = ctxs[k].equals(tab[0]);
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(on ? Theme.bg(Color.WHITE) : Color.TRANSPARENT);
                shape.setCornerRadius(dp(10));
                tabViews[k].setBackground(shape);
                tabViews[k].setTextColor(Theme.text(on ? Color.rgb(3, 105, 161) : Color.rgb(71, 85, 105)));
            }
            grid.removeAllViews();
            String current = n3dsPreset(tab[0]);
            int cell = (Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)) - dp(32) - dp(14)) / 3;
            String[] presets = layoutPresets();
            for (int k = 0; k < presets.length; k++) {
                final String preset = presets[k];
                if ("pad".equals(tab[0]) && "side".equals(preset)) {
                    continue;
                }
                boolean on = preset.equals(current);
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(1);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(dp(4), dp(6), dp(4), dp(6));
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(Theme.bg(on ? Color.rgb(240, 249, 255) : Color.WHITE));
                shape.setCornerRadius(dp(12));
                shape.setStroke(dp(2), Theme.line(on ? blue : Color.rgb(229, 231, 235)));
                tile.setBackground(shape);
                tile.addView(new N3dsThumb(this, n3dsThumbRects(tab[0], preset)), new LinearLayout.LayoutParams(-1, dp(52)));
                TextView name = new TextView(this);
                name.setText(layoutPresetName(preset, tab[0]));
                name.setTextSize(10.5f);
                name.setGravity(Gravity.CENTER);
                name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                name.setTextColor(Theme.text(on ? Color.rgb(3, 105, 161) : Color.rgb(55, 65, 81)));
                tile.addView(name);
                tile.setOnClickListener(v -> {
                    if ("custom".equals(preset)) {
                        if (!tab[0].equals(n3dsContext())) {
                            Toast.makeText(this, "직접 편집은 지금 화면 방향(" + ctxNames[java.util.Arrays.asList(ctxs).indexOf(n3dsContext())] + ")에서 할 수 있어요", Toast.LENGTH_LONG).show();
                            return;
                        }
                        dialog.dismiss();
                        showN3dsEditor(refresh);
                        return;
                    }
                    prefs.edit().putString(n3dsKey("p:" + tab[0]), preset).apply();
                    apply.run();
                });
                android.widget.GridLayout.LayoutParams cellParams = new android.widget.GridLayout.LayoutParams();
                cellParams.width = cell;
                cellParams.height = -2;
                cellParams.setMargins(dp(2), dp(2), dp(3), dp(4));
                grid.addView(tile, cellParams);
            }

            if (gapViews[0] != null) {
                float gap = dsGap();
                for (int k = 0; k < 3; k++) {
                    boolean on = Math.abs(gapValues[k] - gap) < 0.05f;
                    android.graphics.drawable.GradientDrawable chipShape = new android.graphics.drawable.GradientDrawable();
                    chipShape.setColor(Theme.bg(on ? blue : Color.rgb(229, 231, 235)));
                    chipShape.setCornerRadius(dp(9));
                    gapViews[k].setBackground(chipShape);
                    gapViews[k].setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(75, 85, 99)));
                }
            }
            String pos = n3dsPos();
            for (int k = 0; k < 3; k++) {
                boolean on = posKeys[k].equals(pos);
                android.graphics.drawable.GradientDrawable chipShape = new android.graphics.drawable.GradientDrawable();
                chipShape.setColor(Theme.bg(on ? blue : Color.rgb(229, 231, 235)));
                chipShape.setCornerRadius(dp(9));
                posViews[k].setBackground(chipShape);
                posViews[k].setTextColor(on ? Color.WHITE : Theme.text(Color.rgb(75, 85, 99)));
            }
        };
        for (int k = 0; k < 3; k++) {
            final int index = k;
            TextView t = new TextView(this);
            t.setText(ctxNames[k]);
            t.setTextSize(12.5f);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                tab[0] = ctxs[index];
                paintAll[0].run();
            });
            tabViews[k] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, -1, 1.0f));
        }
        paintAll[0].run();
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(d -> {
            if (changed[0]) {
                refresh.run();
            }
        });
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
        dialog.show();
    }

    private LinearLayout n3dsSwitchRow(String title, String note, String key, boolean fallback, Runnable onChange) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        shape.setCornerRadius(dp(12));
        row.setBackground(shape);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(1);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.0f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        words.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextSize(11.0f);
        n.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(prefs.getBoolean(n3dsKey(key), fallback));
        toggle.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(n3dsKey(key), on).apply();
            onChange.run();
        });
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    // After a change: portrait sizes its screen frame from the layout, landscape and the pad
    // screen fill the view for an own layout, so those are laid out again; otherwise only
    // the engine is told.
    // After a change: the game screen keeps its place and size; the DS engine is told its
    // layout (own layouts take its screens one above the other), and the screens are placed.
    private void n3dsRelayout() {
        setNdsLayout(this.ndsLayout);
        applyN3dsLayout();
    }

    // The thumbnail of a choice: rectangles in a small frame of that screen direction.
    private float[] n3dsThumbRects(String ctx, String preset) {
        if ("custom".equals(preset)) {
            float[] c = n3dsCustom(ctx);
            float fw = layTopW();
            float fh = layTopH() + layBotH();
            if (c == null) {
                return new float[]{fw, fh};
            }
            return new float[]{fw, fh, c[0] * fw, c[1] * fh, c[2] * fw, c[3] * fh, c[4] * fw, c[5] * fh, c[6] * fw, c[7] * fh, 1};
        }
        if ("default".equals(preset) && "pad".equals(ctx)) {
            preset = "side";   // the engine's side by side
        }
        return n3dsUnitLayout(preset);
    }

    // A small picture of a layout: the frame, the upper screen blue, the lower one yellow.
    private static final class N3dsThumb extends View {
        private final float[] layout;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        N3dsThumb(android.content.Context context, float[] layout) {
            super(context);
            this.layout = layout;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float fw = this.layout[0];
            float fh = this.layout[1];
            float scale = Math.min((getWidth() - 4 * d) / fw, (getHeight() - 4 * d) / fh);
            float ox = (getWidth() - fw * scale) / 2;
            float oy = (getHeight() - fh * scale) / 2;
            this.paint.setStyle(android.graphics.Paint.Style.FILL);
            this.paint.setColor(Color.rgb(31, 41, 55));
            canvas.drawRoundRect(ox - 2 * d, oy - 2 * d, ox + fw * scale + 2 * d, oy + fh * scale + 2 * d, 4 * d, 4 * d, this.paint);
            if (this.layout.length < 10) {
                this.paint.setColor(Color.rgb(125, 211, 252));
                this.paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                this.paint.setTextSize(16 * d);
                canvas.drawText("✎", getWidth() / 2.0f, getHeight() / 2.0f + 6 * d, this.paint);
                return;
            }
            float[] r = this.layout;
            if (r[4] > r[2] && r[5] > r[3]) {
                this.paint.setColor(Color.rgb(56, 189, 248));
                canvas.drawRect(ox + r[2] * scale, oy + r[3] * scale, ox + r[4] * scale, oy + r[5] * scale, this.paint);
            }
            if (r.length >= 14 && r[12] > r[10] && r[13] > r[11]) {
                this.paint.setColor(Color.rgb(125, 211, 252));
                canvas.drawRect(ox + r[10] * scale, oy + r[11] * scale, ox + r[12] * scale, oy + r[13] * scale, this.paint);
            }
            if (r[8] > r[6] && r[9] > r[7]) {
                this.paint.setColor(Color.rgb(251, 191, 36));
                canvas.drawRect(ox + r[6] * scale, oy + r[7] * scale, ox + r[8] * scale, oy + r[9] * scale, this.paint);
            }
        }
    }

    // ---- 직접 편집: the two screens over the game, moved by a finger and sized by a corner.
    // The touch controls show through (dimmed); a screen over a control gets a red edge. ----

    private void showN3dsEditor(Runnable refresh) {
        SurfaceView surface = this.surfaceView;
        if (surface == null || surface.getWidth() <= 0) {
            return;
        }
        if (this.pauseDialog != null) {
            this.pauseDialog.dismiss();
        }
        // after the menu's own resume (its dismiss runs later)
        surface.post(() -> {
            if (this.nativeHandle != 0) {
                NativeGba.pause(this.nativeHandle, true);
            }
        });
        final String ctx = n3dsContext();
        // Where the picture really is (the button layout editor may have moved or scaled it).
        android.graphics.Rect seen = n3dsVisual(surface);
        if (seen == null) {
            return;
        }
        // The game screen as it is (moved or sized by the 버튼 · 화면 배치 편집 or not) is the
        // frame; the screens are placed inside it (0.0.362).
        int w = seen.width();
        int h = seen.height();
        final float portRatio = h / (float) w;
        final android.graphics.Rect frame = new android.graphics.Rect(seen);
        // Start from the layout shown now (the stacked one for 기본), in the surface's pixels,
        // then as seen.
        String preset = n3dsPreset(ctx);
        int sw = surface.getWidth();
        int sh = surface.getHeight();
        int[] start = java.util.Arrays.copyOf(n3dsRectsFor(ctx, preset, sw, sh, new java.util.ArrayList<>()), 8);
        int[] reset = java.util.Arrays.copyOf(n3dsRectsFor(ctx, "default", sw, sh, new java.util.ArrayList<>()), 8);
        for (int k = 0; k < 8; k++) {
            start[k] = Math.round(start[k] * (k % 2 == 0 ? w / (float) sw : h / (float) sh));
            reset[k] = Math.round(reset[k] * (k % 2 == 0 ? w / (float) sw : h / (float) sh));
        }
        // a screen left out (one screen only) comes back small, to be placed
        if (!n3dsHas(start, 0)) {
            start[0] = start[4]; start[1] = start[5]; start[2] = start[4] + w / 3; start[3] = start[5] + Math.round(w / 3 * 0.6f);
        }
        if (!n3dsHas(start, 4)) {
            start[4] = start[0]; start[5] = start[1]; start[6] = start[0] + w / 3; start[7] = start[1] + Math.round(w / 3 * 0.75f);
        }
        java.util.List<android.graphics.Rect> controls = n3dsControlVisuals();
        final android.view.ViewGroup decor = (android.view.ViewGroup) getWindow().getDecorView();
        final FrameLayout overlay = new FrameLayout(this);
        final N3dsEditorView editor = new N3dsEditorView(this, frame, start, reset, controls);
        overlay.addView(editor, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        String[] names = {"원래대로", "가운데 맞춤", "취소", "저장"};
        final Runnable close = () -> {
            decor.removeView(overlay);
            if (this.nativeHandle != 0) {
                NativeGba.pause(this.nativeHandle, false);
            }
        };
        for (int k = 0; k < names.length; k++) {
            final int index = k;
            TextView b = new TextView(this);
            b.setText(names[k]);
            b.setTextSize(13.0f);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setTextColor(k == 3 ? Color.WHITE : Color.rgb(229, 231, 235));
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setColor(k == 3 ? Color.rgb(14, 165, 233) : Color.argb(235, 31, 41, 55));
            shape.setCornerRadius(dp(12));
            b.setBackground(shape);
            b.setOnClickListener(v -> {
                switch (index) {
                    case 0:
                        editor.reset();
                        break;
                    case 1:
                        editor.centre();
                        break;
                    case 2:
                        close.run();
                        break;
                    default: {
                        float[] f = editor.fractions();
                        StringBuilder sb = new StringBuilder();
                        for (int j = 0; j < 8; j++) {
                            sb.append(j == 0 ? "" : ",").append(String.format(Locale.US, "%.4f", f[j]));
                        }
                        MainActivity.sharedSettings(this).edit().putString(n3dsKey("c:" + ctx), sb.toString())
                                .putFloat(n3dsKey("ca:" + ctx), portRatio)
                                .putString(n3dsKey("p:" + ctx), "custom").apply();
                        close.run();
                        n3dsRelayout();
                        Toast.makeText(this, (isNds() ? "DS" : "3DS") + " 화면 배치를 저장했어요 · 이 게임 · " + ("port".equals(ctx) ? "세로" : "land".equals(ctx) ? "가로" : "컨트롤러"), Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), k == 1 ? 1.4f : 1.0f);
            p.setMargins(k == 0 ? 0 : dp(6), 0, 0, 0);
            bar.addView(b, p);
        }
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(460)), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        // above Android's navigation bar (and the tip below the status bar)
        int insetBottom = 0;
        int insetTop = 0;
        if (android.os.Build.VERSION.SDK_INT >= 23 && decor.getRootWindowInsets() != null) {
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            insetBottom = Math.max(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
            insetTop = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
        }
        barParams.setMargins(0, 0, 0, dp(14) + insetBottom);
        overlay.addView(bar, barParams);
        TextView tip = new TextView(this);
        tip.setText("게임 화면 칸(점선) 안에서 끌어 옮겨요 · 모서리 ●는 원래 비율로 크기 조절, 옆 ■는 가로나 세로만 늘이기 · 두 화면은 겹치지 않게");
        tip.setTextSize(11.5f);
        tip.setTextColor(Color.rgb(226, 232, 240));
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(dp(10), dp(6), dp(10), dp(6));
        android.graphics.drawable.GradientDrawable tipShape = new android.graphics.drawable.GradientDrawable();
        tipShape.setColor(Color.argb(220, 15, 23, 42));
        tipShape.setCornerRadius(dp(10));
        tip.setBackground(tipShape);
        FrameLayout.LayoutParams tipParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tipParams.setMargins(dp(16), dp(10) + insetTop, dp(16), 0);
        overlay.addView(tip, tipParams);
        overlay.setClickable(true);
        decor.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private final class N3dsEditorView extends View {
        private final android.graphics.Rect frame;
        private final android.graphics.RectF[] screens = {new android.graphics.RectF(), new android.graphics.RectF()};
        private final float[] aspects = {5.0f / 3.0f, 4.0f / 3.0f};
        private final int[] resetRects;
        private final java.util.List<android.graphics.Rect> controls;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int active = -1;
        private int corner = -1;           // 0 tl, 1 tr, 2 bl, 3 br (in the screen's shape);
                                           // 4 left, 5 right, 6 top, 7 bottom edge (stretch); -1 moving
        private float lastX;
        private float lastY;

        N3dsEditorView(android.content.Context context, android.graphics.Rect frame, int[] start, int[] reset, java.util.List<android.graphics.Rect> controls) {
            super(context);
            this.frame = frame;
            this.resetRects = reset;
            this.controls = controls;
            // the shapes of this console's screens
            this.aspects[0] = layTopW() / layTopH();
            this.aspects[1] = layBotW() / layBotH();
            set(start);
        }

        private void set(int[] r) {
            for (int s = 0; s < 2; s++) {
                int o = s * 4;
                // as given (a screen may be stretched on purpose, 0.0.363)
                RectFromFrame(this.screens[s], r[o], r[o + 1], r[o + 2], r[o + 3]);
                clamp(s);
            }
            // two screens over each other (a layout saved for a frame of another shape):
            // start from the plain one instead
            if (r != this.resetRects && android.graphics.RectF.intersects(this.screens[0], this.screens[1])) {
                set(this.resetRects);
                return;
            }
            invalidate();
        }

        private void RectFromFrame(android.graphics.RectF out, int l, int t, int r, int b) {
            out.set(this.frame.left + l, this.frame.top + t, this.frame.left + Math.max(r, l + dp(40)), this.frame.top + Math.max(b, t + dp(24)));
        }

        void reset() {
            set(this.resetRects);
        }

        void centre() {
            for (android.graphics.RectF s : this.screens) {
                s.offset(this.frame.centerX() - s.centerX(), 0);
            }
            invalidate();
        }

        // Each screen made smaller around its middle until it is clear of the controls.
        void avoidControls() {
            for (int s = 0; s < 2; s++) {
                android.graphics.RectF r = this.screens[s];
                int guard = 0;
                while (overControls(r) && r.width() > dp(90) && guard++ < 60) {
                    float cx = r.centerX();
                    float cy = r.centerY();
                    float w = r.width() * 0.95f;
                    float h = w / this.aspects[s];
                    r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                }
                clamp(s);
            }
            invalidate();
        }

        private boolean overControls(android.graphics.RectF r) {
            for (android.graphics.Rect c : this.controls) {
                if (android.graphics.RectF.intersects(r, new android.graphics.RectF(c))) {
                    return true;
                }
            }
            return false;
        }

        float[] fractions() {
            float[] out = new float[8];
            for (int s = 0; s < 2; s++) {
                android.graphics.RectF r = this.screens[s];
                out[s * 4] = (r.left - this.frame.left) / this.frame.width();
                out[s * 4 + 1] = (r.top - this.frame.top) / this.frame.height();
                out[s * 4 + 2] = (r.right - this.frame.left) / this.frame.width();
                out[s * 4 + 3] = (r.bottom - this.frame.top) / this.frame.height();
            }
            for (int k = 0; k < 8; k++) {
                out[k] = Math.max(0.0f, Math.min(1.0f, out[k]));
            }
            return out;
        }

        private void clamp(int s) {
            android.graphics.RectF r = this.screens[s];
            if (r.width() > this.frame.width()) {
                r.right = r.left + this.frame.width();
            }
            if (r.height() > this.frame.height()) {
                r.bottom = r.top + this.frame.height();
            }
            float dx = r.left < this.frame.left ? this.frame.left - r.left : r.right > this.frame.right ? this.frame.right - r.right : 0;
            float dy = r.top < this.frame.top ? this.frame.top - r.top : r.bottom > this.frame.bottom ? this.frame.bottom - r.bottom : 0;
            r.offset(dx, dy);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    this.active = -1;
                    this.corner = -1;
                    float reach = dp(26);
                    // the smaller screen first: it is usually the one on top
                    int first = this.screens[0].width() <= this.screens[1].width() ? 0 : 1;
                    for (int pass = 0; pass < 2 && this.active < 0; pass++) {
                        int s = pass == 0 ? first : 1 - first;
                        android.graphics.RectF r = this.screens[s];
                        float[][] corners = {{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}};
                        for (int c = 0; c < 4; c++) {
                            if (Math.abs(x - corners[c][0]) < reach && Math.abs(y - corners[c][1]) < reach) {
                                this.active = s;
                                this.corner = c;
                                break;
                            }
                        }
                    }
                    for (int pass = 0; pass < 2 && this.active < 0; pass++) {
                        int s = pass == 0 ? first : 1 - first;
                        android.graphics.RectF r = this.screens[s];
                        float[][] edges = {{r.left, r.centerY()}, {r.right, r.centerY()}, {r.centerX(), r.top}, {r.centerX(), r.bottom}};
                        for (int e = 0; e < 4; e++) {
                            if (Math.abs(x - edges[e][0]) < reach && Math.abs(y - edges[e][1]) < reach) {
                                this.active = s;
                                this.corner = 4 + e;
                                break;
                            }
                        }
                    }
                    for (int pass = 0; pass < 2 && this.active < 0; pass++) {
                        int s = pass == 0 ? first : 1 - first;
                        if (this.screens[s].contains(x, y)) {
                            this.active = s;
                        }
                    }
                    this.lastX = x;
                    this.lastY = y;
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (this.active < 0) {
                        return true;
                    }
                    android.graphics.RectF r = this.screens[this.active];
                    android.graphics.RectF other = this.screens[1 - this.active];
                    android.graphics.RectF before = new android.graphics.RectF(r);
                    boolean apart = !android.graphics.RectF.intersects(before, other);
                    float aspect = this.aspects[this.active];
                    if (this.corner < 0) {
                        r.offset(x - this.lastX, y - this.lastY);
                        clamp(this.active);
                        snap(r);
                    } else if (this.corner >= 4) {
                        // one side only: stretched wider or taller, inside the frame
                        float min = dp(48);
                        switch (this.corner) {
                            case 4: r.left = Math.max(this.frame.left, Math.min(x, r.right - min)); break;
                            case 5: r.right = Math.min(this.frame.right, Math.max(x, r.left + min)); break;
                            case 6: r.top = Math.max(this.frame.top, Math.min(y, r.bottom - min)); break;
                            default: r.bottom = Math.min(this.frame.bottom, Math.max(y, r.top + min)); break;
                        }
                    } else {
                        // the opposite corner stays where it is
                        float ax = this.corner == 0 || this.corner == 2 ? r.right : r.left;
                        float ay = this.corner == 0 || this.corner == 1 ? r.bottom : r.top;
                        float w = Math.max(dp(64), Math.max(Math.abs(x - ax), Math.abs(y - ay) * aspect));
                        w = Math.min(w, Math.min(this.frame.width(), this.frame.height() * aspect));
                        float h = w / aspect;
                        float left = x < ax ? ax - w : ax;
                        float top = y < ay ? ay - h : ay;
                        if (this.corner == 0 || this.corner == 2) {
                            left = ax - w;
                        } else {
                            left = ax;
                        }
                        if (this.corner == 0 || this.corner == 1) {
                            top = ay - h;
                        } else {
                            top = ay;
                        }
                        r.set(left, top, left + w, top + h);
                        clamp(this.active);
                    }
                    // the two screens don't go over each other
                    if (apart && android.graphics.RectF.intersects(r, other)) {
                        r.set(before);
                    }
                    this.lastX = x;
                    this.lastY = y;
                    invalidate();
                    return true;
                }
                default:
                    this.active = -1;
                    invalidate();
                    return true;
            }
        }

        // Close to the frame's middle or an edge: put it there.
        private void snap(android.graphics.RectF r) {
            float near = dp(10);
            if (Math.abs(r.centerX() - this.frame.centerX()) < near) {
                r.offset(this.frame.centerX() - r.centerX(), 0);
            }
            if (Math.abs(r.left - this.frame.left) < near) {
                r.offset(this.frame.left - r.left, 0);
            }
            if (Math.abs(r.right - this.frame.right) < near) {
                r.offset(this.frame.right - r.right, 0);
            }
            if (Math.abs(r.top - this.frame.top) < near) {
                r.offset(0, this.frame.top - r.top);
            }
            if (Math.abs(r.bottom - this.frame.bottom) < near) {
                r.offset(0, this.frame.bottom - r.bottom);
            }
            android.graphics.RectF other = this.screens[r == this.screens[0] ? 1 : 0];
            if (Math.abs(r.top - other.bottom) < near) {
                r.offset(0, other.bottom - r.top);
            }
            if (Math.abs(r.bottom - other.top) < near) {
                r.offset(0, other.top - r.bottom);
            }
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            this.paint.setStyle(android.graphics.Paint.Style.FILL);
            this.paint.setColor(Color.argb(150, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), this.paint);
            // the frame the screens can go in
            this.paint.setStyle(android.graphics.Paint.Style.STROKE);
            this.paint.setStrokeWidth(1.5f * d);
            this.paint.setColor(Color.rgb(100, 116, 139));
            this.paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 5 * d}, 0));
            canvas.drawRect(this.frame, this.paint);
            this.paint.setPathEffect(null);
            if (this.active >= 0 && this.corner < 0 && Math.abs(this.screens[this.active].centerX() - this.frame.centerX()) < 1) {
                this.paint.setColor(Color.rgb(244, 114, 182));
                canvas.drawLine(this.frame.centerX(), this.frame.top, this.frame.centerX(), this.frame.bottom, this.paint);
            }
            String[] labels = {"위 화면", "아래 화면 (터치)"};
            int[] fills = {Color.argb(215, 59, 130, 246), Color.argb(215, 251, 191, 36)};
            int bigger = this.screens[0].width() >= this.screens[1].width() ? 0 : 1;
            for (int pass = 0; pass < 2; pass++) {
                int s = pass == 0 ? bigger : 1 - bigger;
                android.graphics.RectF r = this.screens[s];
                this.paint.setStyle(android.graphics.Paint.Style.FILL);
                this.paint.setColor(fills[s]);
                canvas.drawRoundRect(r, 3 * d, 3 * d, this.paint);
                this.paint.setColor(s == 0 ? Color.WHITE : Color.rgb(120, 53, 15));
                this.paint.setTextSize(12 * d);
                this.paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                this.paint.setFakeBoldText(true);
                boolean over = overControls(r);
                boolean stretched = Math.abs(r.width() / Math.max(1.0f, r.height()) - this.aspects[s]) > this.aspects[s] * 0.03f;
                canvas.drawText(labels[s] + (stretched ? " · 늘림" : "") + (over ? " · 버튼과 겹침" : ""), r.left + 6 * d, r.top + 16 * d, this.paint);
                this.paint.setFakeBoldText(false);
                this.paint.setStyle(android.graphics.Paint.Style.STROKE);
                this.paint.setStrokeWidth(2 * d);
                this.paint.setColor(over ? Color.rgb(239, 68, 68) : Color.rgb(56, 189, 248));
                canvas.drawRoundRect(r, 3 * d, 3 * d, this.paint);
                this.paint.setStyle(android.graphics.Paint.Style.FILL);
                float[][] corners = {{r.left, r.top}, {r.right, r.top}, {r.left, r.bottom}, {r.right, r.bottom}};
                for (float[] c : corners) {
                    this.paint.setColor(Color.WHITE);
                    canvas.drawCircle(c[0], c[1], 7 * d, this.paint);
                    this.paint.setColor(Color.rgb(14, 165, 233));
                    canvas.drawCircle(c[0], c[1], 4 * d, this.paint);
                }
                // side handles: stretch one way
                float[][] edges = {{r.left, r.centerY()}, {r.right, r.centerY()}, {r.centerX(), r.top}, {r.centerX(), r.bottom}};
                for (float[] c : edges) {
                    this.paint.setColor(Color.WHITE);
                    canvas.drawRect(c[0] - 6 * d, c[1] - 6 * d, c[0] + 6 * d, c[1] + 6 * d, this.paint);
                    this.paint.setColor(Color.rgb(249, 115, 22));
                    canvas.drawRect(c[0] - 3.5f * d, c[1] - 3.5f * d, c[0] + 3.5f * d, c[1] + 3.5f * d, this.paint);
                }
            }
        }
    }

    // ---- PS2 와이드스크린 (0.0.364): PCSX2's own widescreen (and no-interlacing) patches,
    // from the patch collection PCSX2 ships (PCSX2/pcsx2_patches), fetched from there when
    // first turned on and kept as <system>/pcsx2/resources/patches.zip. Per game. ----

    static final String PS2_PATCHES_URL = "https://github.com/PCSX2/pcsx2_patches/releases/download/latest/patches.zip";

    private File ps2PatchesZip() {
        return new File(new File(new File(getFilesDir(), "system"), "pcsx2"), "resources/patches.zip");
    }

    private boolean ps2Widescreen() {
        return "pcee2".equals(this.coreId) && MainActivity.sharedSettings(this).getBoolean("ps2ws:" + this.saveBaseName, false);
    }

    // Whether this game has a widescreen patch (found when the sheet looked).
    private boolean ps2WidescreenFound() {
        return MainActivity.sharedSettings(this).getBoolean("ps2wsok:" + this.saveBaseName, false);
    }

    private boolean ps2NoInterlacing() {
        return "pcee2".equals(this.coreId) && MainActivity.sharedSettings(this).getBoolean("ps2ni:" + this.saveBaseName, false);
    }

    private boolean ps2Stretch() {
        return "pcee2".equals(this.coreId) && MainActivity.sharedSettings(this).getBoolean("ps2stretch:" + this.saveBaseName, false);
    }

    // The picture is 16:9 with a widescreen patch on, or when stretched on purpose.
    private boolean ps2Wide() {
        return (ps2Widescreen() && ps2WidescreenFound()) || ps2Stretch();
    }

    // What the collection has for this game: {widescreen, no-interlacing}, or null when it
    // can't be read. CRC is the game's (as PCSX2 names the files "<serial>_<CRC>.pnach").
    static boolean[] ps2PatchesFor(File zip, long crc) {
        String tail = String.format(Locale.US, "%08X.PNACH", crc);
        boolean[] found = {false, false};
        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry e = entries.nextElement();
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                String base = (slash >= 0 ? name.substring(slash + 1) : name).toUpperCase(Locale.US);
                if (!(base.equals(tail) || base.endsWith("_" + tail)) || e.getSize() > (1 << 20)) {
                    continue;
                }
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = file.getInputStream(e)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        bytes.write(buffer, 0, n);
                    }
                }
                for (String line : new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
                    String l = line.trim().toLowerCase(Locale.US);
                    if (l.startsWith("[widescreen")) {
                        found[0] = true;
                    } else if (l.startsWith("[no-interlacing") || l.startsWith("[no interlacing")) {
                        found[1] = true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return found;
    }

    // Fetches the collection into place (a temporary file first, checked, then renamed).
    static String ps2DownloadPatches(File target) {
        File temp = new File(target.getParentFile(), "patches.zip.part");
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return "폴더를 만들지 못했어요";
            }
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(PS2_PATCHES_URL).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) {
                return "GitHub 서버 응답 " + code;
            }
            try (java.io.InputStream in = connection.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                long total = 0;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                    if (total > (64L << 20)) {
                        return "받은 파일이 너무 커요";
                    }
                }
            } finally {
                connection.disconnect();
            }
            int count;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(temp)) {
                count = zip.size();
            }
            if (count < 100) {
                return "받은 파일이 패치 모음이 아니에요";
            }
            if (!temp.renameTo(target)) {
                return "파일을 저장하지 못했어요";
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "받지 못했어요 (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
        } finally {
            temp.delete();
        }
    }

    // ---- 건슈팅 (0.0.373): a light gun the touch screen aims. Touching or dragging over the
    // picture moves the aim only; the gun's trigger, reload and own buttons are buttons of
    // their own, shown only in this mode (found a free spot once per layout, then moved like
    // any button). The pad stays as it is; where the gun takes the pad's place (PS1 in port
    // 1, Dreamcast, Naomi, arcade) the keypad's START, SELECT/COIN and D-pad reach the gun's.
    // Per game: gun_on, gun_aim (0 the touched point, 1 dragged like a trackpad), gun_cross,
    // gun_type (the console's other gun), gun_port (PS1: port 2), gun_dx / gun_dy (aim
    // correction, % of the picture), gun_sens (drag speed, %). ----
    private static final int kGunTrigger = 1 << 23;
    private static final int kGunReload = 1 << 24;
    private static final int kGunAuxA = 1 << 25;
    private static final int kGunAuxB = 1 << 26;
    private static final int kGunAuxC = 1 << 27;
    private static final int kGunStart = 1 << 28;
    private static final int RETRO_MOUSE = 2;
    private static final int RETRO_LIGHTGUN = 4;

    private static int retroSubclass(int base, int id) {
        return ((id + 1) << 8) | base;
    }

    // Where and how the console's gun is plugged in, and its buttons beside the trigger.
    private static final class GunSetup {
        final String name;
        final int port;
        final int device;
        final boolean replacesPad;
        final String[] labels;
        final int[] bits;
        final String otherName;

        GunSetup(String name, int port, int device, boolean replacesPad, String[] labels, int[] bits, String otherName) {
            this.name = name;
            this.port = port;
            this.device = device;
            this.replacesPad = replacesPad;
            this.labels = labels;
            this.bits = bits;
            this.otherName = otherName;
        }
    }

    private int gunInt(String key, int fallback) {
        return MainActivity.sharedSettings(this).getInt(key + ":" + this.saveBaseName, fallback);
    }

    // The gun for this console (and the player's choice of gun and port), or null when its
    // engine has none.
    private GunSetup gunSetup() {
        return gunSetupFor(gunInt("gun_type", 0), gunInt("gun_port", 0));
    }

    private GunSetup gunSetupFor(int gunType, int gunPort) {
        boolean other = gunType == 1;
        if (isNes()) {
            return new GunSetup("재퍼", 1, retroSubclass(RETRO_MOUSE, 0), false, new String[]{"발사"}, new int[]{kGunTrigger}, null);
        }
        if ("snes9x".equals(this.coreId)) {
            return other
                    ? new GunSetup("저스티파이어", 1, retroSubclass(RETRO_LIGHTGUN, 1), false, new String[]{"발사", "재장전", "총 START"},
                            new int[]{kGunTrigger, kGunReload, kGunStart}, "슈퍼 스코프")
                    : new GunSetup("슈퍼 스코프", 1, retroSubclass(RETRO_LIGHTGUN, 0), false, new String[]{"발사", "커서", "일시정지"},
                            new int[]{kGunTrigger, kGunAuxA, kGunStart}, "저스티파이어");
        }
        if ("genesis_plus_gx".equals(this.coreId) && isMasterSystem() && !isGameGear()) {
            return new GunSetup("라이트 페이저", 0, retroSubclass(RETRO_LIGHTGUN, 0), true, new String[]{"발사"}, new int[]{kGunTrigger}, null);
        }
        if ("genesis_plus_gx".equals(this.coreId) && !isMasterSystem() && !isGameGear() && !isGameBoy()) {
            return other
                    ? new GunSetup("저스티파이어", 1, retroSubclass(RETRO_LIGHTGUN, 2), false, new String[]{"발사", "재장전", "총 START"},
                            new int[]{kGunTrigger, kGunReload, kGunStart}, "메너서")
                    : new GunSetup("메너서", 1, retroSubclass(RETRO_LIGHTGUN, 1), false, new String[]{"발사", "총 B", "총 C", "총 START"},
                            new int[]{kGunTrigger, kGunAuxA, kGunAuxB, kGunStart}, "저스티파이어");
        }
        if ("pcsx_rearmed".equals(this.coreId) || isSwan()) {
            int port = gunPort == 1 ? 1 : 0;
            if (other && !isSwan()) {
                return new GunSetup("코나미 저스티파이어", port, retroSubclass(RETRO_LIGHTGUN, 1), port == 0,
                        new String[]{"발사", "재장전", "특수", "총 START"}, new int[]{kGunTrigger, kGunReload, kGunAuxA, kGunStart}, "건콘");
            }
            return new GunSetup("건콘", port, retroSubclass(RETRO_LIGHTGUN, 0), port == 0, new String[]{"발사", "재장전", "건콘 A", "건콘 B"},
                    new int[]{kGunTrigger, kGunReload, kGunAuxA, kGunAuxB}, isSwan() ? null : "코나미 저스티파이어");
        }
        if ("pcee2".equals(this.coreId)) {
            return new GunSetup("건콘2", 0, 0, false, new String[]{"발사", "재장전", "건콘 A", "건콘 B"},
                    new int[]{kGunTrigger, kGunReload, kGunAuxA, kGunAuxB}, null);
        }
        if ("flycast".equals(this.coreId)) {
            return isNaomi()
                    ? new GunSetup("라이트건", 0, RETRO_LIGHTGUN, true, new String[]{"발사", "재장전"}, new int[]{kGunTrigger, kGunReload}, null)
                    : new GunSetup("라이트건", 0, RETRO_LIGHTGUN, true, new String[]{"발사", "재장전", "총 B"}, new int[]{kGunTrigger, kGunReload, kGunAuxA}, null);
        }
        if ("fbneo".equals(this.coreId)) {
            return new GunSetup("라이트건", 0, RETRO_LIGHTGUN, true, new String[]{"발사", "재장전"}, new int[]{kGunTrigger, kGunReload}, null);
        }
        if ("mame2003plus".equals(this.coreId)) {
            return new GunSetup("라이트건", 0, 0, false, new String[]{"발사", "재장전"}, new int[]{kGunTrigger, kGunReload}, null);
        }
        return null;
    }

    private boolean gunOn() {
        return gunSetup() != null && MainActivity.sharedSettings(this).getBoolean("gun_on:" + this.saveBaseName, false);
    }

    private void sendGunSetup() {
        GunSetup gun = gunOn() ? gunSetup() : null;
        if (gun == null) {
            NativeGba.setGun(false, 0, 0, false);
        } else {
            NativeGba.setGun(true, gun.port, gun.device, gun.replacesPad);
        }
    }

    // ---- 총 버튼을 키패드에 붙이기 (0.0.376): where the gun takes the pad's place (PS1 with
    // the gun in 1P, Dreamcast, Naomi, FBNeo, Master System) the keypad's right-hand buttons
    // are idle, so each gun function can sit on one of them instead of on a new button.
    // Per game: gun_keys (true = on the keypad, the default) and gun_key:<game>:<gun bit> =
    // the keypad bit. sendMainInput turns the keypad bit into the gun bit, so a controller's
    // button mapped there follows too. START stays on the keypad's START, as before. ----
    private int[] gunKeyMap;

    // The keypad buttons a gun function can sit on: the face buttons and the right shoulders.
    private java.util.List<PadMapping.Target> gunKeyChoices() {
        java.util.List<PadMapping.Target> out = new java.util.ArrayList<>();
        for (PadMapping.Target target : padTargets()) {
            boolean right = target.group == PadMapping.GROUP_FACE || target.group == PadMapping.GROUP_SHOULDER_RIGHT;
            if (right && target.bit > 0 && target.bit < kGunTrigger && Integer.bitCount(target.bit) == 1) {
                out.add(target);
            }
        }
        if (isMasterSystem() && out.size() == 2 && "2".equals(out.get(0).label)) {
            java.util.Collections.reverse(out);  // 1 before 2
        }
        return out;
    }

    // The gun's functions that go on keypad buttons (all but START).
    private static int[] gunKeyFunctions(GunSetup gun) {
        int count = 0;
        for (int bit : gun.bits) {
            if (bit != kGunStart) count++;
        }
        int[] out = new int[count];
        int n = 0;
        for (int bit : gun.bits) {
            if (bit != kGunStart) out[n++] = bit;
        }
        return out;
    }

    private static String gunLabelOf(GunSetup gun, int bit) {
        for (int k = 0; k < gun.bits.length; k++) {
            if (gun.bits[k] == bit) return gun.labels[k];
        }
        return "";
    }

    private boolean gunKeysPossible(GunSetup gun, java.util.List<PadMapping.Target> choices) {
        return gun != null && gun.replacesPad && !isPadScreen() && choices.size() >= gunKeyFunctions(gun).length;
    }

    // The keypad button each function starts on: ○ ✕ △ □ / A X B / 1 2 / A B.
    private int gunKeyPreferred(int function) {
        if (isSonyPad()) {
            return function == kGunTrigger ? 1 : function == kGunReload ? 2 : function == kGunAuxA ? 1024 : 2048;
        }
        if (isNaomi()) {
            return function == kGunTrigger ? NAOMI_BITS[0] : NAOMI_BITS[1];
        }
        if (isDreamcast()) {
            return function == kGunTrigger ? 2 : function == kGunReload ? 2048 : 1;
        }
        if (isMasterSystem()) {
            return function == kGunTrigger ? 2 : 1;
        }
        return function == kGunTrigger ? 1 : function == kGunReload ? 2 : function == kGunAuxA ? 1024 : 2048;
    }

    // The keypad bit for each function (one function per button), from the chosen ones
    // (saved, or the sheet's own) and the starting places.
    private int[] gunKeyAssign(GunSetup gun, java.util.List<PadMapping.Target> choices, java.util.Map<Integer, Integer> chosen) {
        int[] functions = gunKeyFunctions(gun);
        int[] keys = new int[functions.length];
        java.util.Set<Integer> valid = new java.util.HashSet<>();
        for (PadMapping.Target target : choices) valid.add(target.bit);
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int pass = 0; pass < 3; pass++) {
            for (int k = 0; k < functions.length; k++) {
                if (keys[k] != 0) continue;
                int want = 0;
                if (pass == 0) {
                    Integer saved = chosen.get(functions[k]);
                    want = saved != null ? saved : 0;
                } else if (pass == 1) {
                    want = gunKeyPreferred(functions[k]);
                } else {
                    for (PadMapping.Target target : choices) {
                        if (!used.contains(target.bit)) {
                            want = target.bit;
                            break;
                        }
                    }
                }
                if (want != 0 && valid.contains(want) && !used.contains(want)) {
                    keys[k] = want;
                    used.add(want);
                }
            }
        }
        return keys;
    }

    private java.util.Map<Integer, Integer> gunKeysSaved(GunSetup gun) {
        java.util.Map<Integer, Integer> chosen = new java.util.HashMap<>();
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        for (int function : gunKeyFunctions(gun)) {
            int key = prefs.getInt("gun_key:" + this.saveBaseName + ":" + function, 0);
            if (key != 0) chosen.put(function, key);
        }
        return chosen;
    }

    // Works out the keypad-to-gun map for this game (null when the gun has its own buttons).
    private void refreshGunKeys() {
        this.gunKeyMap = null;
        GunSetup gun = gunOn() ? gunSetup() : null;
        if (gun == null || !MainActivity.sharedSettings(this).getBoolean("gun_keys:" + this.saveBaseName, true)) {
            return;
        }
        java.util.List<PadMapping.Target> choices = gunKeyChoices();
        if (!gunKeysPossible(gun, choices)) {
            return;
        }
        int[] functions = gunKeyFunctions(gun);
        int[] keys = gunKeyAssign(gun, choices, gunKeysSaved(gun));
        int[] map = new int[functions.length * 2];
        for (int k = 0; k < functions.length; k++) {
            map[k * 2] = keys[k];
            map[k * 2 + 1] = functions[k];
        }
        this.gunKeyMap = map;
    }

    private int gunKeyFilter(int mask) {
        int[] map = this.gunKeyMap;
        if (map == null) {
            return mask;
        }
        int out = mask;
        for (int k = 0; k + 1 < map.length; k += 2) {
            if (map[k] != 0 && (mask & map[k]) != 0) {
                out = (out & ~map[k]) | map[k + 1];
            }
        }
        return out;
    }

    // The tags on the keypad: the function's name on its button, the other right-hand
    // buttons faint (they do nothing while the gun is in).
    private void tagGunKeys(View view, GunSetup gun, java.util.Set<Integer> idle) {
        if (view instanceof TouchButton) {
            TouchButton button = (TouchButton) view;
            if (this.gunButtons.contains(button)) return;
            int bit = button.bit();
            String tag = null;
            int[] map = this.gunKeyMap;
            for (int k = 0; map != null && k + 1 < map.length; k += 2) {
                if (map[k] == bit) tag = gunLabelOf(gun, map[k + 1]);
            }
            button.setGunTag(tag, tag == null && idle.contains(bit));
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int k = 0; k < group.getChildCount(); k++) {
                tagGunKeys(group.getChildAt(k), gun, idle);
            }
        }
    }

    // The aim, 0..1 across and down the picture (dragging moves it from where it is).
    private float gunAimX = 0.5f;
    private float gunAimY = 0.5f;
    private float gunLastX;
    private float gunLastY;
    private GunCrosshairView gunCrosshair;

    private boolean onGunTouch(View view, android.view.MotionEvent event) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return true;
        }
        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        if (gunInt("gun_aim", 0) == 1) {
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                this.gunLastX = x;
                this.gunLastY = y;
            } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                float speed = gunInt("gun_sens", 100) / 100.0f;
                this.gunAimX = Math.max(0f, Math.min(1f, this.gunAimX + (x - this.gunLastX) / width * speed));
                this.gunAimY = Math.max(0f, Math.min(1f, this.gunAimY + (y - this.gunLastY) / height * speed));
                this.gunLastX = x;
                this.gunLastY = y;
            }
        } else if (action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL) {
            // 조준점 띄우기 (0.0.377): aim a set distance above the finger so it doesn't hide the crosshair.
            float lift = GUN_LIFT_MM[gunLift()] * getResources().getDisplayMetrics().ydpi / 25.4f;
            this.gunAimX = Math.max(0f, Math.min(1f, x / width));
            this.gunAimY = Math.max(0f, Math.min(1f, (y - lift) / height));
        }
        sendGunAim();
        return true;
    }

    // The aim to the engine (with the player's correction) and the crosshair to where it is.
    private void sendGunAim() {
        float ax = Math.max(0f, Math.min(1f, this.gunAimX + gunInt("gun_dx", 0) / 100.0f));
        float ay = Math.max(0f, Math.min(1f, this.gunAimY + gunInt("gun_dy", 0) / 100.0f));
        NativeGba.setGunAim(Math.round((ax * 2f - 1f) * 32767f), Math.round((ay * 2f - 1f) * 32767f));
        GunCrosshairView cross = this.gunCrosshair;
        SurfaceView surface = this.surfaceView;
        if (cross != null && surface != null) {
            boolean shown = MainActivity.sharedSettings(this).getBoolean("gun_cross:" + this.saveBaseName, true);
            cross.setAim(shown, surface.getX() + this.gunAimX * surface.getWidth(), surface.getY() + this.gunAimY * surface.getHeight());
        }
    }

    private void addGunCrosshair(FrameLayout parent) {
        this.gunCrosshair = null;
        if (!gunOn()) {
            return;
        }
        GunCrosshairView cross = new GunCrosshairView(this);
        parent.addView(cross, new FrameLayout.LayoutParams(-1, -1));
        this.gunCrosshair = cross;
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(this::sendGunAim));
    }

    // The gun's buttons: added to the content's root, out of sight until scheduleSettleGun
    // puts each in a free spot (kept per layout under gunbtn3:<layout key>:<bit> as x,y,size dp).
    private final java.util.List<TouchButton> gunButtons = new java.util.ArrayList<>();
    private final java.util.Set<View> gunUnplaced = new java.util.HashSet<>();

    private void addGunButtons(FrameLayout root) {
        this.gunButtons.clear();
        this.gunUnplaced.clear();
        refreshGunKeys();
        GunSetup gun = gunOn() ? gunSetup() : null;
        if (gun == null || isPadScreen()) {
            return;
        }
        if (this.gunKeyMap != null) {
            java.util.Set<Integer> idle = new java.util.HashSet<>();
            for (PadMapping.Target target : gunKeyChoices()) idle.add(target.bit);
            tagGunKeys(root, gun, idle);
            return;  // the keypad carries the gun's buttons; START is the keypad's START
        }
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        for (int k = 0; k < gun.labels.length; k++) {
            int bit = gun.bits[k];
            int style = bit == kGunTrigger ? TouchButton.STYLE_ARCADE_RED : bit == kGunReload ? TouchButton.STYLE_SNES_BLUE : TouchButton.STYLE_PURPLE;
            TouchButton button = new TouchButton(this, gun.labels[k], bit, this::setButtonPressed, style);
            int size = dp(bit == kGunTrigger ? 64 : 48);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
            // (gunbtn3: from 0.0.379, the places by the face buttons; gunbtn2 0.0.375, gunbtn 0.0.373.)
            String saved = prefs.getString("gunbtn3:" + layoutKey() + ":" + bit, null);
            boolean placed = false;
            if (saved != null) {
                String[] parts = saved.split(",");
                if (parts.length >= 2) {
                    try {
                        params.leftMargin = dp(Integer.parseInt(parts[0]));
                        params.topMargin = dp(Integer.parseInt(parts[1]));
                        if (parts.length >= 3) {
                            params.width = dp(Integer.parseInt(parts[2]));
                            params.height = params.width;
                        }
                        placed = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!placed) {
                button.setAlpha(0f);
                this.gunUnplaced.add(button);
            }
            root.addView(button, params);
            this.gunButtons.add(button);
        }
    }

    private void scheduleSettleGun(int generation) {
        if (this.gunButtons.isEmpty()) {
            return;
        }
        final View first = this.gunButtons.get(0);
        first.post(() -> first.post(() -> {
            if (!this.destroyed && generation == this.controlsGeneration && this.layoutEditor == null) {
                settleGunButtons();
            }
        }));
    }

    // First time in this layout: each unplaced gun button into the free spot nearest to where
    // a right thumb rests (the trigger first), clear of the screen, the controls and each other.
    private void settleGunButtons() {
        if (this.screenArea == null || this.screenArea.getWidth() == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        android.graphics.RectF screen = windowBox(this.screenArea);
        java.util.Set<View> unplaced = new java.util.HashSet<>(this.gunUnplaced);
        if (unplaced.isEmpty()) {
            return;
        }
        android.content.SharedPreferences.Editor editor = MainActivity.sharedSettings(this).edit();
        java.util.List<android.graphics.RectF> obstaclesPlaced = new java.util.ArrayList<>();
        // (0.0.379) Where they go, by the keypad's face buttons (the right thumb's):
        //   landscape: the trigger under the face buttons, on the outer side; the rest, small,
        //              stacked beside it toward the picture. PS2's GunCon 2 A / B go either side
        //              of △ (the right stick takes the room below).
        //   portrait:  one row just above the face buttons, the trigger on the right.
        android.graphics.RectF face = null;
        android.graphics.RectF triangle = null;
        for (View view : this.layoutControls) {
            if (view instanceof TouchButton && !this.gunButtons.contains(view) && view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                TouchButton key = (TouchButton) view;
                String name = key.label() == null ? "" : key.label();
                if (key.isShoulder() || name.matches("[LR][12]?|Z[LR]") || Integer.bitCount(key.bit()) != 1 || (key.bit() & (FACE_BITS | 256 | 512)) == 0) {
                    continue;
                }
                android.graphics.RectF one = windowBox(key);
                View top = key.getRootView();
                if (top == null || one.centerX() < top.getWidth() / 2f || one.width() > dp(110)) {
                    continue;  // only the right thumb's buttons (not L, not a wide key)
                }
                if (face == null) face = new android.graphics.RectF(one); else face.union(one);
                if (isSonyPad() && key.bit() == 1024) triangle = one;
            }
        }
        int extras = 0;
        for (TouchButton button : this.gunButtons) {
            if (button.bit() != kGunTrigger) extras++;
        }
        boolean besideTriangle = triangle != null && this.landscapeMode && "pcee2".equals(this.coreId);
        float[] trigger = null;
        int extraIndex = 0;
        for (TouchButton button : this.gunButtons) {
            if (!unplaced.contains(button)) {
                continue;
            }
            View root = (View) button.getParent();
            if (root == null || root.getWidth() == 0) {
                return;
            }
            int[] origin = new int[2];
            root.getLocationInWindow(origin);
            java.util.List<android.graphics.RectF> obstacles = new java.util.ArrayList<>();
            for (View view : this.layoutControls) {
                if (view != button && !unplaced.contains(view) && view.getVisibility() == View.VISIBLE && view.isAttachedToWindow() && view.getWidth() > 0) {
                    obstacles.add(windowBox(view));
                }
            }
            for (View guard : this.layoutGuards) {
                if (guard.getVisibility() == View.VISIBLE && guard.isAttachedToWindow() && guard.getWidth() > 0) {
                    obstacles.add(windowBox(guard));
                }
            }
            float targetX;
            float targetY;
            float baseSize = button.getLayoutParams().width;
            float gap = dp(8);
            boolean isTrigger = button.bit() == kGunTrigger;
            float triggerSize = dp(64);
            int nth = isTrigger ? -1 : extraIndex++;
            if (face != null && besideTriangle && (button.bit() == kGunAuxA || button.bit() == kGunAuxB)) {
                // GunCon 2 A left of △, B right of it.
                targetX = button.bit() == kGunAuxA ? triangle.left - gap - baseSize / 2f : triangle.right + gap + baseSize / 2f;
                targetY = triangle.centerY();
            } else if (face != null && this.landscapeMode) {
                float tx = face.right - triggerSize / 2f;
                float ty = face.bottom + gap + triggerSize / 2f;
                if (isTrigger) {
                    targetX = tx;
                    targetY = ty;
                } else {
                    int stacked = besideTriangle ? extras - 2 : extras;
                    targetX = tx - triggerSize / 2f - gap - baseSize / 2f;
                    targetY = ty + (nth - (Math.max(1, stacked) - 1) / 2f) * (baseSize + gap);
                }
            } else if (face != null) {
                float rowY = face.top - gap - triggerSize / 2f;
                if (isTrigger) {
                    targetX = face.right - triggerSize / 2f;
                } else {
                    targetX = face.right - triggerSize - gap - baseSize / 2f - nth * (baseSize + gap);
                }
                targetY = rowY;
            } else if (trigger != null) {
                targetX = trigger[0] - dp(60) * (nth + 1);
                targetY = trigger[1];
            } else if (this.landscapeMode) {
                targetX = (screen.right + origin[0] + root.getWidth()) / 2f;
                targetY = origin[1] + root.getHeight() - dp(90);
            } else {
                targetX = origin[0] + root.getWidth() - dp(70);
                targetY = (screen.bottom + origin[1] + root.getHeight()) / 2f;
            }
            // The free spot nearest to the target, trying smaller sizes when none fits (landscape
            // has little room beside the picture). Failing that, the spot that covers the least:
            // other gun buttons count most, then the picture, then the other controls.
            float margin = dp(3);
            float[] spot = null;
            float size = baseSize;
            float[] sizes = {baseSize, baseSize * 0.85f, baseSize * 0.72f, Math.min(baseSize * 0.62f, dp(38))};
            float bestPenalty = Float.MAX_VALUE;
            float bestScore = Float.MAX_VALUE;
            float[] bestSpot = null;
            float bestSize = baseSize;
            android.graphics.RectF box = new android.graphics.RectF();
            android.graphics.RectF cut = new android.graphics.RectF();
            for (float trySize : sizes) {
                for (float yy = origin[1] + margin; yy + trySize <= origin[1] + root.getHeight() - margin; yy += dp(6)) {
                    for (float xx = origin[0] + margin; xx + trySize <= origin[0] + root.getWidth() - margin; xx += dp(6)) {
                        box.set(xx - margin, yy - margin, xx + trySize + margin, yy + trySize + margin);
                        float penalty = 0f;
                        for (android.graphics.RectF other : obstacles) {
                            if (cut.setIntersect(box, other)) {
                                penalty += cut.width() * cut.height();
                            }
                        }
                        // Gun buttons placed just now (their new place isn't laid out yet) count most.
                        for (android.graphics.RectF placed : obstaclesPlaced) {
                            if (cut.setIntersect(box, placed)) {
                                penalty += 20f * cut.width() * cut.height();
                            }
                        }
                        if (cut.setIntersect(box, screen)) {
                            penalty += 4f * cut.width() * cut.height();
                        }
                        float score = Math.abs(xx + trySize / 2f - targetX) + Math.abs(yy + trySize / 2f - targetY);
                        if (penalty < bestPenalty - 1f || (Math.abs(penalty - bestPenalty) <= 1f && score < bestScore)) {
                            bestPenalty = penalty;
                            bestScore = score;
                            bestSpot = new float[]{xx - origin[0], yy - origin[1]};
                            bestSize = trySize;
                        }
                    }
                }
                if (bestPenalty <= 1f) {
                    break;  // a free spot at this size
                }
            }
            spot = bestSpot != null ? bestSpot : new float[]{0f, 0f};
            size = bestSize;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
            params.leftMargin = Math.round(spot[0]);
            params.topMargin = Math.round(spot[1]);
            params.width = Math.round(size);
            params.height = Math.round(size);
            button.setLayoutParams(params);
            button.setAlpha(1f);
            this.gunUnplaced.remove(button);
            unplaced.remove(button);
            // This button now counts as an obstacle for the next ones.
            obstaclesPlaced.add(new android.graphics.RectF(spot[0] + origin[0], spot[1] + origin[1], spot[0] + origin[0] + size, spot[1] + origin[1] + size));
            editor.putString("gunbtn3:" + layoutKey() + ":" + button.bit(),
                    Math.round(spot[0] / density) + "," + Math.round(spot[1] / density) + "," + Math.round(size / density));
            if (isTrigger) {
                trigger = new float[]{spot[0] + origin[0] + size / 2f, spot[1] + origin[1] + size / 2f};
            }
        }
        editor.apply();
    }

    private static final String[] GUN_AIM_NAMES = {"누른 곳으로", "끌어서 (트랙패드)"};
    // 조준점 띄우기 (0.0.377), per game under gun_lift: how far above the finger the aim sits.
    private static final String[] GUN_LIFT_NAMES = {"안 띄움", "조금", "보통", "많이"};
    private static final float[] GUN_LIFT_MM = {0f, 6f, 9f, 13f};

    private int gunLift() {
        int value = gunInt("gun_lift", 2);
        return value >= 0 && value < GUN_LIFT_MM.length ? value : 2;
    }

    private LinearLayout createGunRow() {
        GunSetup gun = gunSetup();
        String shown = gunOn() ? "켬 · " + gun.name : "끔";
        return createSettingRow(RoundIconView.PAD, Color.rgb(220, 38, 38), "건슈팅 (터치 조준)", shown, view -> showGunSheet());
    }

    private void showGunSheet() {
        final GunSetup gun = gunSetup();
        if (gun == null) {
            return;
        }
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final String base = this.saveBaseName;
        final boolean wasOn = gunOn();
        final int wasType = gunInt("gun_type", 0);
        final int wasPort = gunInt("gun_port", 0);
        final boolean[] on = {wasOn};
        final int[] aim = {gunInt("gun_aim", 0)};
        final int[] type = {wasType};
        final int[] port = {wasPort};
        final boolean[] cross = {prefs.getBoolean("gun_cross:" + base, true)};
        final int[] dx = {gunInt("gun_dx", 0)};
        final int[] dy = {gunInt("gun_dy", 0)};
        final int[] sens = {gunInt("gun_sens", 100)};
        final int[] lift = {gunLift()};
        final boolean wasKeys = prefs.getBoolean("gun_keys:" + base, true);
        final boolean[] keysOn = {wasKeys};
        final java.util.Map<Integer, Integer> keyChosen = new java.util.HashMap<>();
        for (int function : new int[]{kGunTrigger, kGunReload, kGunAuxA, kGunAuxB}) {
            int key = prefs.getInt("gun_key:" + base + ":" + function, 0);
            if (key != 0) keyChosen.put(function, key);
        }
        final java.util.Map<Integer, Integer> wasChosen = new java.util.HashMap<>(keyChosen);
        final java.util.List<PadMapping.Target> keyChoices = gunKeyChoices();
        final int red = Color.rgb(220, 38, 38);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("건슈팅 · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(this.gameTitle + "\n화면 터치는 조준만 하고, 발사 · 재장전은 새로 생기는 버튼으로 해요. 총이 패드 자리를 대신하는 게임기에서는 쉬고 있는 키패드 버튼에 붙일 수도 있어요.");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(4));
        panel.addView(sub);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            body.addView(ps2Switch("건슈팅 모드", "켜고 끄면 게임을 다시 켜요 (총을 꽂고 빼요)", on[0], v -> {
                on[0] = v;
                render[0].run();
            }));
            if (!on[0]) {
                return;
            }
            if (gun.otherName != null) {
                String[] names = wasType == 1 ? new String[]{gun.otherName, gun.name} : new String[]{gun.name, gun.otherName};
                body.addView(backgroundChips("총 종류 (게임이 쓰는 총)", names, type[0], red, i -> {
                    type[0] = i;
                    render[0].run();
                }));
            }
            if (("pcsx_rearmed".equals(this.coreId) || isSwan())) {
                body.addView(backgroundChips("총 꽂는 자리", new String[]{"1P (기본)", "2P · 패드도 살리기"}, port[0], red, i -> {
                    port[0] = i;
                    render[0].run();
                }));
                body.addView(ps2Note(port[0] == 1 ? "2P에 꽂으면 1P 패드가 그대로 남아요. 게임이 2P 총을 받지 않으면 1P로 바꿔 주세요."
                        : "1P에 꽂으면 그 게임에서 키패드는 쉬어요 (실제 기계처럼 패드를 빼고 총을 꽂아요). 건콘에는 START가 없어서, 시작 · 메뉴는 총의 A · B나 화면 밖 쏘기로 해요.",
                        Color.rgb(254, 242, 242), Color.rgb(153, 27, 27)));
            }
            body.addView(backgroundChips("조준 움직이는 방법", GUN_AIM_NAMES, aim[0], red, i -> {
                aim[0] = i;
                render[0].run();
            }));
            if (aim[0] == 1) {
                body.addView(gunSlider("끌기 속도", sens, 50, 250, "%"));
            } else {
                body.addView(backgroundChips("조준점 띄우기 · 손가락 위로", GUN_LIFT_NAMES, lift[0], red, i -> {
                    lift[0] = i;
                    render[0].run();
                }));
                body.addView(ps2Note("누른 곳보다 위를 겨눠서 조준점이 손가락에 안 가려요 (조금 약 6mm · 보통 약 9mm · 많이 약 13mm, 폰 크기와 상관없이 같은 거리)."
                        + "\n화면 맨 아래를 쏘려면 손가락을 게임 화면 아래 여백까지 내려도 돼요. 게임 화면에서 누르기 시작했으면 밖으로 나가도 조준이 따라가요.",
                        Color.rgb(246, 247, 249), Color.rgb(55, 65, 81)));
            }
            body.addView(ps2Switch("조준점 보이기", "손가락이 가려도 어디를 겨누는지 보여요", cross[0], v -> cross[0] = v));
            body.addView(gunSlider("조준 맞추기 · 좌우", dx, -15, 15, "%"));
            body.addView(gunSlider("조준 맞추기 · 위아래", dy, -15, 15, "%"));
            body.addView(ps2Note("쏜 곳이 조준점과 어긋나면 위 두 줄로 맞춰요 (예: 왼쪽에 맞으면 좌우를 + 쪽으로).", Color.rgb(246, 247, 249), Color.rgb(55, 65, 81)));
            StringBuilder buttons = new StringBuilder();
            GunSetup shownGun = gunSetupFor(type[0], port[0]);
            if (shownGun != null && gunKeysPossible(shownGun, keyChoices)) {
                body.addView(backgroundChips("총 버튼 자리", new String[]{"키패드 버튼에 붙이기", "새 버튼 따로"}, keysOn[0] ? 0 : 1, red, i -> {
                    keysOn[0] = i == 0;
                    render[0].run();
                }));
                if (keysOn[0]) {
                    int[] functions = gunKeyFunctions(shownGun);
                    int[] keys = gunKeyAssign(shownGun, keyChoices, keyChosen);
                    String[] names = new String[keyChoices.size()];
                    for (int c = 0; c < names.length; c++) names[c] = keyChoices.get(c).label;
                    for (int k = 0; k < functions.length; k++) {
                        final int function = functions[k];
                        final int[] current = keys;
                        final int self = k;
                        int selected = 0;
                        for (int c = 0; c < names.length; c++) {
                            if (keyChoices.get(c).bit == keys[k]) selected = c;
                        }
                        String label = gunLabelOf(shownGun, function);
                        body.addView(backgroundChips(label, names, selected, red, c -> {
                            int pick = keyChoices.get(c).bit;
                            // One function per button: the one already there takes this one's old button.
                            for (int o = 0; o < functions.length; o++) {
                                keyChosen.put(functions[o], current[o]);
                                if (o != self && current[o] == pick) keyChosen.put(functions[o], current[self]);
                            }
                            keyChosen.put(function, pick);
                            render[0].run();
                        }));
                    }
                    body.addView(ps2Note("고른 키패드 버튼이 그 기능이 되고, 새 버튼은 생기지 않아요. 붙인 버튼엔 이름표가 뜨고, 쉬는 버튼은 흐려져요."
                            + "\n한 버튼에 기능 하나만: 다른 기능이 쓰던 버튼을 고르면 서로 자리를 바꿔요. 게임패드의 같은 버튼도 따라가요."
                            + (shownGun.replacesPad ? "\nSTART · 방향키" + (isNaomi() || "fbneo".equals(this.coreId) ? " · COIN" : "") + "는 지금처럼 키패드 그대로예요." : ""),
                            Color.rgb(254, 242, 242), Color.rgb(153, 27, 27)));
                    return;
                }
            }
            if (shownGun != null) {
                for (int k = 0; k < shownGun.labels.length; k++) {
                    buttons.append(k == 0 ? "" : " · ").append(shownGun.labels[k]);
                }
                body.addView(ps2Note("생기는 버튼: " + buttons + "\n빈 곳에 놓이고, 버튼 · 화면 배치 편집으로 옮길 수 있어요. 게임패드 버튼에도 붙일 수 있어요."
                        + (shownGun.replacesPad ? "\n이 게임기는 총이 패드 자리를 대신해요: 키패드는 총에도 있는 버튼(START · SELECT(COIN) · 방향키)만 쓰이고, 나머지는 이 게임에서 쉬어요." : ""),
                        Color.rgb(254, 242, 242), Color.rgb(153, 27, 27)));
            }
        };
        render[0].run();
        LinearLayout buttons = new LinearLayout(this);
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        TextView apply = dialogButton("적용", red, Color.WHITE);
        apply.setOnClickListener(v -> {
            prefs.edit().putBoolean("gun_on:" + base, on[0]).putInt("gun_aim:" + base, aim[0]).putInt("gun_type:" + base, type[0])
                    .putInt("gun_port:" + base, port[0]).putBoolean("gun_cross:" + base, cross[0]).putInt("gun_dx:" + base, dx[0])
                    .putInt("gun_dy:" + base, dy[0]).putInt("gun_sens:" + base, sens[0]).putInt("gun_lift:" + base, lift[0]).putBoolean("gun_keys:" + base, keysOn[0]).commit();
            GunSetup appliedGun = gunSetupFor(type[0], port[0]);
            if (appliedGun != null && keysOn[0] && gunKeysPossible(appliedGun, keyChoices)) {
                int[] functions = gunKeyFunctions(appliedGun);
                int[] keys = gunKeyAssign(appliedGun, keyChoices, keyChosen);
                android.content.SharedPreferences.Editor keyEditor = prefs.edit();
                for (int k = 0; k < functions.length; k++) {
                    keyEditor.putInt("gun_key:" + base + ":" + functions[k], keys[k]);
                    keyChosen.put(functions[k], keys[k]);
                }
                keyEditor.commit();
            }
            boolean keysChanged = keysOn[0] != wasKeys || !keyChosen.equals(wasChosen);
            dialog.dismiss();
            if (on[0] != wasOn || (on[0] && (type[0] != wasType || port[0] != wasPort))) {
                if (this.pauseDialog != null) {
                    this.pauseDialog.dismiss();
                }
                relaunchGame();
            } else {
                if (on[0] && keysChanged) {
                    rebuildContent();  // the gun's buttons come or go, the keypad's tags change
                }
                sendGunAim();
            }
        });
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(46), 1.5f);
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(440)),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
        }
        dialog.show();
    }

    private LinearLayout gunSlider(String name, int[] value, int min, int max, String unit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(1);
        box.setPadding(dp(2), dp(10), 0, 0);
        TextView label = new TextView(this);
        label.setTextSize(12.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        label.setText(name + " · " + (value[0] > 0 && min < 0 ? "+" : "") + value[0] + unit);
        box.addView(label);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value[0] - min);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int progress, boolean user) {
                value[0] = progress + min;
                label.setText(name + " · " + (value[0] > 0 && min < 0 ? "+" : "") + value[0] + unit);
            }

            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            public void onStopTrackingTouch(android.widget.SeekBar b) {
            }
        });
        box.addView(bar);
        return box;
    }

    // ---- VMU 화면 (0.0.367): the Dreamcast memory card's 48x32 screen in its own window outside
    // the game's picture, a layout item like the buttons. Per game: vmu_show (0 그릴 때만,
    // 1 늘, 2 끔), vmu_color, vmu_sound. Its first place, a free spot found once per layout
    // (console, direction, style), is kept under vmu_pos:<layout key> so later moves in the
    // layout editor stay where they were put. ----
    private static final String[] VMU_SHOW_NAMES = {"그릴 때만", "늘", "끔"};
    private static final String[] VMU_COLOR_NAMES = {"원래 VMU", "흰 바탕", "반전"};
    private VmuScreenView vmuView;
    private final byte[] vmuDots = new byte[VmuScreenView.W * VmuScreenView.H];

    private boolean vmuSupported() {
        return isDreamcast() && !isNaomi() && "flycast".equals(this.coreId);
    }

    private int vmuShowMode() {
        int mode = MainActivity.sharedSettings(this).getInt("vmu_show:" + this.saveBaseName, 0);
        return mode >= 0 && mode <= 2 ? mode : 0;
    }

    private boolean vmuShown() {
        return vmuSupported() && vmuShowMode() != 2;
    }

    private int vmuColors() {
        int colors = MainActivity.sharedSettings(this).getInt("vmu_color:" + this.saveBaseName, 0);
        return colors >= 0 && colors <= 2 ? colors : 0;
    }

    private boolean vmuSound() {
        return MainActivity.sharedSettings(this).getBoolean("vmu_sound:" + this.saveBaseName, false);
    }

    private void addVmuWindow(FrameLayout root) {
        this.vmuView = null;
        if (!vmuShown() || isPadScreen()) {
            return;
        }
        VmuScreenView view = new VmuScreenView(this);
        view.setAlways(vmuShowMode() == 1);
        view.setColors(vmuColors());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(84), dp(56), Gravity.TOP | Gravity.START);
        float[] place = vmuSavedPlace();
        if (place != null) {
            params.width = dp(Math.round(place[2]));
            params.height = dp(Math.round(place[2] * 2f / 3f));
            params.leftMargin = dp(Math.round(place[0]));
            params.topMargin = dp(Math.round(place[1]));
        } else {
            // Not placed yet: out of the way until scheduleSettleVmu finds a free spot.
            view.setAlpha(0f);
        }
        root.addView(view, params);
        this.vmuView = view;
    }

    // [left, top, width] in dp within the content, or null before the first placement.
    private float[] vmuSavedPlace() {
        String saved = MainActivity.sharedSettings(this).getString("vmu_pos:" + layoutKey(), null);
        if (saved == null) {
            return null;
        }
        String[] parts = saved.split(",");
        if (parts.length < 3) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void scheduleSettleVmu(int generation) {
        final VmuScreenView view = this.vmuView;
        if (view == null) {
            return;
        }
        view.post(() -> view.post(() -> {
            if (!this.destroyed && generation == this.controlsGeneration && this.layoutEditor == null && view == this.vmuView) {
                settleVmu(view);
            }
        }));
    }

    // Everything the VMU window may not cover, in window coordinates: the game screen, the
    // other controls and the app's guarded buttons.
    private java.util.List<android.graphics.RectF> vmuObstacles(View self) {
        java.util.List<android.graphics.RectF> boxes = new java.util.ArrayList<>();
        for (View view : this.layoutControls) {
            if (view != self && view.getVisibility() == View.VISIBLE && view.isAttachedToWindow() && view.getWidth() > 0) {
                boxes.add(windowBox(view));
            }
        }
        for (View guard : this.layoutGuards) {
            if (guard.getVisibility() == View.VISIBLE && guard.isAttachedToWindow() && guard.getWidth() > 0) {
                boxes.add(windowBox(guard));
            }
        }
        return boxes;
    }

    private void settleVmu(VmuScreenView view) {
        View root = (View) view.getParent();
        if (root == null || root.getWidth() == 0 || this.screenArea == null || this.screenArea.getWidth() == 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int[] origin = new int[2];
        root.getLocationInWindow(origin);
        android.graphics.RectF screen = windowBox(this.screenArea);
        if (vmuSavedPlace() == null) {
            // First time in this layout: the free spot nearest to below the screen (portrait)
            // or the top of the left column (landscape), as big as fits (84 down to 48 dp wide).
            java.util.List<android.graphics.RectF> obstacles = vmuObstacles(view);
            float targetX = this.landscapeMode ? (origin[0] + screen.left) / 2f : screen.centerX();
            float targetY = this.landscapeMode ? origin[1] + root.getHeight() * 0.3f : screen.bottom + dp(40);
            float margin = dp(6);
            float[] best = null;
            for (int widthDp = 84; widthDp >= 48 && best == null; widthDp -= 12) {
                float w = widthDp * density;
                float h = w * 2f / 3f;
                float bestScore = Float.MAX_VALUE;
                for (float y = origin[1] + margin; y + h <= origin[1] + root.getHeight() - margin; y += dp(6)) {
                    for (float x = origin[0] + margin; x + w <= origin[0] + root.getWidth() - margin; x += dp(6)) {
                        android.graphics.RectF candidate = new android.graphics.RectF(x - margin, y - margin, x + w + margin, y + h + margin);
                        boolean free = true;
                        for (android.graphics.RectF box : obstacles) {
                            if (android.graphics.RectF.intersects(candidate, box)) {
                                free = false;
                                break;
                            }
                        }
                        if (!free) continue;
                        float score = Math.abs(x + w / 2f - targetX) + Math.abs(y + h / 2f - targetY);
                        if (score < bestScore) {
                            bestScore = score;
                            best = new float[]{(x - origin[0]) / density, (y - origin[1]) / density, widthDp};
                        }
                    }
                }
            }
            if (best == null) {
                // No room anywhere: the smallest size in the top left, to be moved in the editor.
                best = new float[]{8, this.landscapeMode ? 8 : TOP_BAR_DP + 8, 48};
            }
            MainActivity.sharedSettings(this).edit().putString("vmu_pos:" + layoutKey(),
                    Math.round(best[0]) + "," + Math.round(best[1]) + "," + Math.round(best[2])).apply();
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            params.width = dp(Math.round(best[2]));
            params.height = dp(Math.round(best[2] * 2f / 3f));
            params.leftMargin = dp(Math.round(best[0]));
            params.topMargin = dp(Math.round(best[1]));
            view.setLayoutParams(params);
            view.setAlpha(1f);
            return;
        }
        // Moved over the game screen in the editor: pushed out to the nearest side that is
        // still inside the content, and that move is saved.
        android.graphics.RectF box = windowBox(view);
        android.graphics.RectF inner = new android.graphics.RectF(box);
        inner.inset(dp(1), dp(1));
        if (!android.graphics.RectF.intersects(inner, screen)) {
            return;
        }
        float gap = dp(4);
        float[][] moves = {
                {screen.left - gap - box.right, 0}, {screen.right + gap - box.left, 0},
                {0, screen.top - gap - box.bottom}, {0, screen.bottom + gap - box.top}};
        float[] pick = null;
        for (float[] move : moves) {
            boolean inside = box.left + move[0] >= origin[0] && box.right + move[0] <= origin[0] + root.getWidth()
                    && box.top + move[1] >= origin[1] && box.bottom + move[1] <= origin[1] + root.getHeight();
            if (inside && (pick == null || Math.abs(move[0]) + Math.abs(move[1]) < Math.abs(pick[0]) + Math.abs(pick[1]))) {
                pick = move;
            }
        }
        int index = this.layoutControls.indexOf(view);
        if (pick == null || index < 0) {
            return;
        }
        java.util.Map<String, float[]> edits = loadLayoutEdits();
        String id = this.layoutIds.get(index);
        float[] edit = edits.get(id);
        if (edit == null) {
            edit = new float[]{0, 0, 1, 1};
            edits.put(id, edit);
        }
        edit[0] += pick[0] / density;
        edit[1] += pick[1] / density;
        applyLayoutEdit(index, edit[0], edit[1], edit[2], edit[3]);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, float[]> entry : edits.entrySet()) {
            float[] e = entry.getValue();
            sb.append(entry.getKey()).append('=').append(Math.round(e[0])).append(',').append(Math.round(e[1])).append(',')
                    .append(Math.round(e[2] * 100) / 100.0f);
            if (Math.abs(e[2] - e[3]) >= 0.005f) {
                sb.append(',').append(Math.round(e[3] * 100) / 100.0f);
            }
            sb.append(';');
        }
        MainActivity.sharedSettings(this).edit().putString(layoutKey(), sb.toString()).apply();
        showInfoNotice("VMU 화면을 옮겼어요", "게임 화면을 가리지 않게 바로 옆으로 옮겼어요", 4000);
    }

    // A few times a second while the game runs: the VMU's dots from the engine.
    private final Runnable vmuPoll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            long handle = GameActivity.this.nativeHandle;
            VmuScreenView view = GameActivity.this.vmuView;
            if (handle != 0 && view != null) {
                int lit = NativeGba.vmuScreen(handle, GameActivity.this.vmuDots);
                if (lit >= 0) {
                    view.setDots(GameActivity.this.vmuDots, lit);
                }
            }
            if (handle != 0) {
                getWindow().getDecorView().postDelayed(this, 200);
            }
        }
    };

    private void startVmuPoll() {
        if (!vmuShown()) {
            return;
        }
        getWindow().getDecorView().removeCallbacks(this.vmuPoll);
        getWindow().getDecorView().postDelayed(this.vmuPoll, 200);
    }

    private LinearLayout createVmuRow() {
        String shown = vmuShowMode() == 2 ? "끔" : VMU_SHOW_NAMES[vmuShowMode()] + " · " + VMU_COLOR_NAMES[vmuColors()];
        return createSettingRow(RoundIconView.HD, Color.rgb(13, 148, 136), "VMU 화면", shown, view -> showVmuSheet());
    }

    private void showVmuSheet() {
        final int[] show = {vmuShowMode()};
        final int[] colors = {vmuColors()};
        final boolean[] sound = {vmuSound()};
        final int accent = Color.rgb(13, 148, 136);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("VMU 화면 · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(this.gameTitle + "\n메모리카드(VMU)의 작은 화면을 게임 화면 밖에 따로 보여 줘요.");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(4));
        panel.addView(sub);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        panel.addView(body);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            body.addView(backgroundChips("보이기", VMU_SHOW_NAMES, show[0], accent, i -> { show[0] = i; render[0].run(); }));
            if (show[0] != 2) {
                body.addView(backgroundChips("색", VMU_COLOR_NAMES, colors[0], accent, i -> { colors[0] = i; render[0].run(); }));
                body.addView(ps2Switch("VMU 소리", "VMU 삐 소리 (일부 게임) · 바꾸면 게임을 다시 켜요", sound[0], on -> sound[0] = on));
                body.addView(ps2Note("그릴 때만: 게임이 VMU에 그림을 그렸을 때만 보여요(VMU를 안 쓰는 게임이 더 많아요). "
                        + "위치 · 크기는 버튼 · 화면 배치 편집에서 다른 버튼처럼 옮겨요. 게임 화면을 가리게 옮기면 바로 옆으로 비켜나요.",
                        Color.rgb(240, 253, 250), Color.rgb(17, 94, 89)));
            }
        };
        render[0].run();
        LinearLayout buttons = new LinearLayout(this);
        TextView edit = dialogButton("배치 편집", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            if (this.vmuView == null) {
                Toast.makeText(this, "VMU 화면을 켜고 적용한 뒤에 옮길 수 있어요", Toast.LENGTH_SHORT).show();
                return;
            }
            startLayoutEditor();
        });
        TextView apply = dialogButton("적용", accent, Color.WHITE);
        apply.setOnClickListener(v -> {
            boolean relaunch = sound[0] != vmuSound() && (show[0] != 2 || vmuShowMode() != 2);
            boolean rebuild = show[0] != vmuShowMode() || colors[0] != vmuColors();
            MainActivity.sharedSettings(this).edit().putInt("vmu_show:" + this.saveBaseName, show[0])
                    .putInt("vmu_color:" + this.saveBaseName, colors[0]).putBoolean("vmu_sound:" + this.saveBaseName, sound[0]).commit();
            dialog.dismiss();
            if (relaunch) {
                if (this.pauseDialog != null) {
                    this.pauseDialog.dismiss();
                }
                relaunchGame();
            } else if (rebuild) {
                if (this.pauseDialog != null) {
                    this.pauseDialog.dismiss();
                }
                rebuildContent();
                startVmuPoll();
            }
        });
        buttons.addView(edit, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(46), 1.5f);
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
        dialog.show();
    }

    // ---- PSP 텍스처 다듬기 (0.0.367): PPSSPP's texture options, per game. The seven choices
    // (see NativeGba.setPspTexture) are kept as "a,b,c,d,e,f,g" under psp_tex:<game>. ----
    private static final String[] PSP_TEX_SHADERS = {"끔", "2xBRZ", "4xBRZ", "MMPX"};
    private static final String[] PSP_TEX_LEVELS = {"끔", "2x", "3x", "4x", "5x"};
    private static final String[] PSP_TEX_TYPES = {"xBRZ", "하이브리드", "바이큐빅", "혼합"};
    private static final String[] PSP_TEX_FILTERS = {"자동", "선명하게", "부드럽게", "자동 최고 품질"};
    private static final String[] PSP_TEX_ANISO = {"16x (기본)", "끔", "2x", "4x", "8x"};

    private int[] pspTexture() {
        int[] choices = new int[7];
        String saved = MainActivity.sharedSettings(this).getString("psp_tex:" + this.saveBaseName, "");
        String[] parts = saved.split(",");
        int[] limits = {4, 5, 4, 2, 4, 2, 5};
        for (int k = 0; k < 7 && k < parts.length; k++) {
            try {
                int value = Integer.parseInt(parts[k].trim());
                choices[k] = value >= 0 && value < limits[k] ? value : 0;
            } catch (NumberFormatException e) {
                choices[k] = 0;
            }
        }
        return choices;
    }

    private String pspTextureSummary(int[] t) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (t[0] != 0) parts.add(PSP_TEX_SHADERS[t[0]]);
        if (t[1] != 0) parts.add("업스케일 " + PSP_TEX_LEVELS[t[1]]);
        if (t[4] != 0) parts.add(PSP_TEX_FILTERS[t[4]]);
        if (t[5] != 0) parts.add("스마트 2D");
        return parts.isEmpty() ? "기본" : String.join(" · ", parts);
    }

    private LinearLayout createPspTextureRow() {
        return createSettingRow(RoundIconView.HD, Color.rgb(79, 70, 229), "PSP 텍스처 다듬기", pspTextureSummary(pspTexture()), view -> showPspTextureSheet());
    }

    private void showPspTextureSheet() {
        final int[] t = pspTexture();
        final int[] before = t.clone();
        final int accent = Color.rgb(79, 70, 229);
        final boolean vulkan = !MainActivity.vulkanOff(MainActivity.sharedSettings(this), "ppsspp");
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("PSP 텍스처 다듬기 · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(this.gameTitle + "\n게임 속 그림(텍스처)의 도트 계단을 매끄럽게 해요. PSP 엔진의 기능이에요.");
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(4));
        panel.addView(sub);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            body.removeAllViews();
            LinearLayout previews = new LinearLayout(this);
            previews.setGravity(Gravity.CENTER_HORIZONTAL);
            int shownMode = t[0] != 0 || t[1] != 0 ? 1 : t[4] == 2 ? 2 : 0;
            previews.addView(new PspTexPreview(this, shownMode), new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView caption = new TextView(this);
            caption.setText(shownMode == 1 ? "  도트 계단을 매끄럽게" : shownMode == 2 ? "  부드럽게 (번짐)" : "  원래 도트 그대로");
            caption.setTextSize(12.0f);
            caption.setGravity(Gravity.CENTER_VERTICAL);
            caption.setTextColor(Theme.text(Color.rgb(71, 85, 105)));
            previews.addView(caption, new LinearLayout.LayoutParams(-2, dp(72)));
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
            previewParams.setMargins(0, dp(6), 0, dp(4));
            body.addView(previews, previewParams);
            body.addView(backgroundChips("텍스처 셰이더 · 그래픽 칩 · 가벼움", PSP_TEX_SHADERS, t[0], accent, i -> { t[0] = i; render[0].run(); }));
            body.addView(ps2Note(vulkan ? "Vulkan일 때만 돼요. 켜면 아래 업스케일보다 먼저예요. 처음엔 MMPX나 2xBRZ를 추천해요."
                    : "지금 PSP는 OpenGL로 돌고 있어서(Vulkan이 꺼져 있음) 텍스처 셰이더는 효과가 없어요. 아래 업스케일을 쓰세요.",
                    vulkan ? Color.rgb(238, 242, 255) : Color.rgb(254, 243, 199), vulkan ? Color.rgb(55, 48, 163) : Color.rgb(146, 64, 14)));
            body.addView(backgroundChips("텍스처 업스케일 · CPU · 무거움", PSP_TEX_LEVELS, t[1], accent, i -> { t[1] = i; render[0].run(); }));
            if (t[1] != 0) {
                body.addView(backgroundChips("업스케일 방식", PSP_TEX_TYPES, t[2], accent, i -> { t[2] = i; render[0].run(); }));
                body.addView(ps2Switch("띠 줄이기", "업스케일한 그림의 색 띠(계단)를 줄여요", t[3] == 1, on -> t[3] = on ? 1 : 0));
                if (t[1] >= 2) {
                    body.addView(ps2Note("3x 이상은 휴대폰에서 끊길 수 있어요.", Color.rgb(254, 243, 199), Color.rgb(146, 64, 14)));
                }
            }
            body.addView(backgroundChips("텍스처 필터", PSP_TEX_FILTERS, t[4], accent, i -> { t[4] = i; render[0].run(); }));
            body.addView(ps2Switch("스마트 2D 필터", "2D 게임에서 쓸데없이 번지는 곳만 선명하게", t[5] == 1, on -> t[5] = on ? 1 : 0));
            body.addView(backgroundChips("비등방 필터 · 비스듬한 바닥을 멀리까지 또렷하게", PSP_TEX_ANISO, t[6], accent, i -> { t[6] = i; render[0].run(); }));
            if (speedFirstSupported() && speedFirst()) {
                body.addView(ps2Note("속도 우선 중이라 업스케일 · 비등방 필터는 꺼진 채로 돌아요 (속도 우선을 끄면 적용).", Color.rgb(240, 253, 250), Color.rgb(17, 94, 89)));
            }
        };
        render[0].run();
        LinearLayout buttons = new LinearLayout(this);
        TextView reset = dialogButton("기본으로", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        reset.setOnClickListener(v -> {
            java.util.Arrays.fill(t, 0);
            render[0].run();
        });
        TextView apply = dialogButton("적용 (다시 켜기)", accent, Color.WHITE);
        apply.setOnClickListener(v -> {
            StringBuilder saved = new StringBuilder();
            for (int k = 0; k < 7; k++) {
                saved.append(k == 0 ? "" : ",").append(t[k]);
            }
            MainActivity.sharedSettings(this).edit().putString("psp_tex:" + this.saveBaseName, saved.toString()).commit();
            dialog.dismiss();
            if (!java.util.Arrays.equals(t, before)) {
                if (this.pauseDialog != null) {
                    this.pauseDialog.dismiss();
                }
                relaunchGame();
            }
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(46), 1.5f);
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(440)),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86f));
        }
        dialog.show();
    }

    // A small ring as PSP 텍스처 다듬기 shows it: blocky pixels (0), smoothed (1) or blurred (2).
    private static final class PspTexPreview extends View {
        private final int mode;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PspTexPreview(android.content.Context context, int mode) {
            super(context);
            this.mode = mode;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.rgb(253, 230, 138));
            canvas.drawRoundRect(0, 0, w, h, w * 0.12f, w * 0.12f, paint);
            int ink = Color.rgb(124, 45, 18);
            if (mode == 0) {
                String[] pattern = {"..XXXX..", ".X....X.", "X......X", "X..XX..X", "X..XX..X", "X......X", ".X....X.", "..XXXX.."};
                float cell = Math.min(w, h) * 0.8f / 8;
                float left = (w - cell * 8) / 2;
                float top = (h - cell * 8) / 2;
                paint.setColor(ink);
                for (int r = 0; r < 8; r++) {
                    for (int c = 0; c < 8; c++) {
                        if (pattern[r].charAt(c) == 'X') {
                            canvas.drawRect(left + c * cell, top + r * cell, left + (c + 1) * cell, top + (r + 1) * cell, paint);
                        }
                    }
                }
                return;
            }
            if (mode == 2) {
                paint.setMaskFilter(new android.graphics.BlurMaskFilter(w * 0.05f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            }
            paint.setColor(ink);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(w * 0.1f);
            canvas.drawCircle(w / 2, h / 2, w * 0.3f, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            canvas.drawRoundRect(w * 0.42f, h * 0.42f, w * 0.58f, h * 0.58f, w * 0.04f, w * 0.04f, paint);
            paint.setMaskFilter(null);
        }
    }

    private LinearLayout createPs2WidescreenRow() {
        String shown = ps2Widescreen() ? (ps2WidescreenFound() ? "켬 · 16:9" : "켬 · 이 게임은 패치 없음") : ps2Stretch() ? "늘려 보기 16:9" : "끔 (4:3)";
        return createSettingRow(RoundIconView.HD, Color.rgb(37, 99, 235), "와이드스크린 (16:9)", shown, view -> showPs2WidescreenSheet());
    }

    private void showPs2WidescreenSheet() {
        final File zip = ps2PatchesZip();
        if (!zip.isFile()) {
            showPs2PatchesDownload(zip);
            return;
        }
        final long crc = OverlayPatches.ps2IsoCrc(romFile());
        final android.app.Dialog busy = new AppDialog.Builder(this).setTitle("와이드스크린").setMessage("이 게임용 패치를 찾는 중…").show();
        new Thread(() -> {
            boolean[] found = crc < 0 ? null : ps2PatchesFor(zip, crc);
            runOnUiThread(() -> {
                busy.dismiss();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showPs2WidescreenChoices(zip, crc, found);
            });
        }, "ps2-patches").start();
    }

    private void showPs2PatchesDownload(File zip) {
        new AppDialog.Builder(this).setTitle("와이드스크린 패치 모음 받기")
                .setMessage("PS2 게임을 16:9로 바꾸는 패치는 게임마다 따로 있어요. PCSX2가 쓰는 공개 패치 모음(게임 약 4,700개 · 약 2MB)을 PCSX2 공식 저장소(github.com/PCSX2/pcsx2_patches)에서 받아 와서 이 게임 것을 찾아요.\n\n"
                        + "패치는 여러 사람이 만든 것을 PCSX2가 모아 배포해요. 패치에 따라 일부 장면(동영상 · 메뉴)이 늘어나 보이거나 화면 끝이 잘릴 수 있어요. 이상하면 끄면 원래대로예요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("받기", (d, w) -> {
                    final android.app.Dialog busy = new AppDialog.Builder(this).setTitle("받는 중").setMessage("PCSX2 패치 모음 (약 2MB)").show();
                    new Thread(() -> {
                        String error = ps2DownloadPatches(zip);
                        runOnUiThread(() -> {
                            busy.dismiss();
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (error != null) {
                                new AppDialog.Builder(this).setTitle("패치 모음을 받지 못했어요").setMessage(error + "\n인터넷 연결을 확인하고 다시 해 주세요.")
                                        .setPositiveButton("닫기", null).show();
                                return;
                            }
                            showPs2WidescreenSheet();
                        });
                    }, "ps2-patches-get").start();
                }).show();
    }

    private void showPs2WidescreenChoices(File zip, long crc, boolean[] found) {
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        final boolean hasWs = found != null && found[0];
        final boolean hasNi = found != null && found[1];
        prefs.edit().putBoolean("ps2wsok:" + this.saveBaseName, hasWs).apply();
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("와이드스크린 · 이 게임만");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(this.gameTitle + (crc >= 0 ? String.format(Locale.US, " · CRC %08X", crc) : ""));
        sub.setTextSize(12.0f);
        sub.setTextColor(Theme.text(Color.rgb(107, 114, 128)));
        sub.setPadding(0, dp(4), 0, dp(8));
        panel.addView(sub);
        final boolean[] ws = {ps2Widescreen() && hasWs};
        final boolean[] ni = {ps2NoInterlacing() && hasNi};
        final boolean[] stretch = {ps2Stretch()};
        if (hasWs) {
            panel.addView(ps2Switch("와이드스크린 패치 (16:9)", "게임 카메라를 옆으로 넓혀요 · 비율은 그대로", ws[0], on -> ws[0] = on));
            panel.addView(ps2Note("✓ 이 게임용 와이드 패치가 있어요 (PCSX2 패치 모음)", Color.rgb(220, 252, 231), Color.rgb(22, 101, 52)));
        } else {
            panel.addView(ps2Note(found == null ? "이 게임의 번호(CRC)를 읽지 못했거나 패치 모음을 읽지 못했어요."
                    : "이 게임용 와이드 패치가 모음에 없어요. 다른 지역판(북미 · 유럽)에는 있을 수 있어요.\n직접 구한 와이드 pnach는 치트 › pnach 넣기로 쓸 수 있어요.",
                    Color.rgb(254, 243, 199), Color.rgb(146, 64, 14)));
            panel.addView(ps2Switch("대신 16:9로 늘려 보기", "화면 모양을 16:9로 (그림이 옆으로 늘어나요)", stretch[0], on -> stretch[0] = on));
        }
        if (hasNi) {
            panel.addView(ps2Switch("인터레이스 줄무늬 없애기", "화면이 떨리거나 가로줄이 보이는 게임용 패치", ni[0], on -> ni[0] = on));
        }
        String date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.KOREA).format(new java.util.Date(zip.lastModified()));
        TextView again = new TextView(this);
        again.setText("패치 모음 · PCSX2 공식 · " + date + " 받음 · 새로 받기 ›");
        again.setTextSize(12.0f);
        again.setTextColor(Theme.text(Color.rgb(37, 99, 235)));
        again.setPadding(dp(4), dp(12), 0, dp(2));
        again.setOnClickListener(v -> {
            dialog.dismiss();
            showPs2PatchesDownload(zip);
        });
        panel.addView(again);
        LinearLayout buttons = new LinearLayout(this);
        TextView close = dialogButton("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        TextView apply = dialogButton("적용 (다시 켜기)", Color.rgb(37, 99, 235), Color.WHITE);
        apply.setOnClickListener(v -> {
            boolean changed = ws[0] != ps2Widescreen() || ni[0] != ps2NoInterlacing() || stretch[0] != ps2Stretch();
            prefs.edit().putBoolean("ps2ws:" + this.saveBaseName, ws[0]).putBoolean("ps2ni:" + this.saveBaseName, ni[0])
                    .putBoolean("ps2stretch:" + this.saveBaseName, stretch[0]).commit();
            dialog.dismiss();
            if (changed) {
                if (this.pauseDialog != null) {
                    this.pauseDialog.dismiss();
                }
                relaunchGame();
            }
        });
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(0, dp(46), 1.5f);
        applyParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(apply, applyParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(14), 0, 0);
        panel.addView(buttons, buttonsParams);
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
        }
        dialog.show();
    }

    private TextView ps2Note(String text, int background, int color) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(12.0f);
        note.setTextColor(color);
        note.setLineSpacing(0, 1.15f);
        note.setPadding(dp(10), dp(8), dp(10), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(12));
        note.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        note.setLayoutParams(params);
        return note;
    }

    private LinearLayout ps2Switch(String title, String note, boolean checked, FilterToggle onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Theme.bg(Color.rgb(246, 247, 249)));
        shape.setCornerRadius(dp(12));
        row.setBackground(shape);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(1);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13.0f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        words.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextSize(11.0f);
        n.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        words.addView(n);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1.0f));
        android.widget.Switch toggle = new android.widget.Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((b, on) -> onChange.set(on));
        row.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    // ---- 3DS 그래픽 (0.0.347): OpenGL (default) or Vulkan with background shader building,
    // per game. A crash on Vulkan turns it off for the 3DS engine (vulkan_off:citra). ----
    private boolean n3dsVulkan() {
        return MainActivity.sharedSettings(this).getBoolean("n3ds_vk:" + this.saveBaseName, false);
    }

    // Vulkan: new shaders built in the background (default) or waited for (0.0.355).
    private boolean n3dsAsync() {
        return MainActivity.sharedSettings(this).getBoolean("n3ds_async:" + this.saveBaseName, true);
    }

    private LinearLayout createN3dsGraphicsRow() {
        final int color = Color.rgb(190, 18, 60);
        final boolean off = MainActivity.vulkanOff(MainActivity.sharedSettings(this), "citra");
        final boolean vk = n3dsVulkan();
        final boolean async = n3dsAsync();
        String shown = vk ? (off ? "Vulkan → 오류로 OpenGL" : async ? "Vulkan · 비동기" : "Vulkan · 기다려서 완성") : "OpenGL";
        return createSettingRow(RoundIconView.GEAR, color, "3DS 그래픽 (실험)", shown,
                view -> showChoiceDialog(RoundIconView.GEAR, color, "3DS 그래픽 · 이 게임만",
                        new String[]{"OpenGL (안정 · 기본)", "Vulkan · 비동기 셰이더", "Vulkan · 기다려서 완성"},
                        new String[]{"지금 방식. 새 효과가 처음 나올 때 1초쯤 멈출 수 있어요 (한 번 지나간 장면은 저장돼 다음엔 덜 멈춤)",
                                "새 효과를 뒤에서 만들어 멈추지 않는 대신, 처음 보는 장면은 물건이 하나씩 그려져요",
                                "새 효과가 다 만들어질 때까지 잠깐 멈췄다가 완성된 화면을 보여 줘요 (처음 보는 장면에서만)"},
                        vk ? (async ? 1 : 2) : 0,
                        "실험 기능이에요. 화면이 깨지거나 검게 나오면 OpenGL로 돌려 주세요. Vulkan으로 그리다 엔진이 멈추면 다음 실행부터 자동으로 OpenGL로 켜요."
                                + (off ? "\n\n지금은 전에 Vulkan 오류가 있어서 OpenGL로 켜고 있어요. Vulkan을 다시 고르면 한 번 더 시도해요." : ""),
                        Color.rgb(255, 241, 242), Color.rgb(159, 18, 57), "적용 (다시 켜기)", choice -> {
                    boolean want = choice >= 1;
                    boolean wantAsync = choice != 2;
                    if (want == vk && !(want && off) && (!want || wantAsync == async)) {
                        return;
                    }
                    android.content.SharedPreferences.Editor editor = MainActivity.sharedSettings(this).edit().putBoolean("n3ds_vk:" + this.saveBaseName, want)
                            .putBoolean("n3ds_async:" + this.saveBaseName, wantAsync);
                    if (want) {
                        editor.remove("vulkan_off:citra");  // try again
                    }
                    editor.commit();
                    if (this.pauseDialog != null) {
                        this.pauseDialog.dismiss();
                    }
                    relaunchGame();
                }));
    }

    private boolean noStates() {
        return isWiiU() || isVita();
    }

    private boolean isPs1() {
        return "pcsx_rearmed".equals(this.coreId) || isSwan();
    }

    private boolean isSwan() {
        return "swanstation".equals(this.coreId);
    }

    private boolean isPs2() {
        return "play".equals(this.coreId) || "pcee2".equals(this.coreId);
    }

    // PSP, PS1 and PS2 share △○×□, a D-pad and analog sticks.
    private boolean isSonyPad() {
        return isPsp() || isPs1() || isPs2() || isVita();
    }

    // PS1 (DualShock) and PS2 pads: L1/L2/R1/R2 and two sticks.
    private boolean isDualShock() {
        return isPs1() || isPs2() || isVita();
    }

    // Button bits the bridge reads as libretro L2 and R2.
    private static final int kL2 = 16384;
    private static final int kR2 = 32768;
    private static final int kL3 = 65536;
    private static final int kR3 = 131072;
    // 3DS MIC button (0.0.370): the app's own (Citra reads L3 as HOME), never passed to the core.
    private static final int kMic3ds = 1 << 22;

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
        views[3] = leftStick();
        for (int k = 0; k < 4; k++) {
            TouchButton button = new TouchButton(this, PSP_LABELS[k], PSP_BITS[k], this::setButtonPressed, faceStyle());
            button.setLabelColor(PSP_COLORS[k]);
            views[4 + k] = button;
        }
        if (dual) {
            views[8] = new TouchButton(this, "L2", kL2, this::setButtonPressed, shoulderStyle);
            views[9] = new TouchButton(this, "R2", kR2, this::setButtonPressed, shoulderStyle);
            views[10] = rightStick();
        }
        for (View view : views) {
            frameLayout.addView(view, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.START));
        }
        this.pspSideViews = views;
        // The bottom row, out from the middle: the speed button (with rewind beside it on
        // PS1), SELECT and START, then L3 and R3 on PS1/PS2.
        int middleHalf = rewindOn() ? 76 : 40;
        int keyCentre = middleHalf + 8 + 39;
        for (int k = 0; k < 2; k++) {
            TouchButton key = new TouchButton(this, k == 0 ? "SELECT" : "START", k == 0 ? 4 : 8, this::setButtonPressed);
            FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(dp(78), dp(30));
            keyParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            keyParams.setMargins(0, 0, 0, dp(9));
            key.setTranslationX(dp(k == 0 ? -keyCentre : keyCentre));
            frameLayout.addView(key, keyParams);
            if (dual) {
                TouchButton click = new TouchButton(this, k == 0 ? "L3" : "R3", k == 0 ? kL3 : kR3, this::setButtonPressed, TouchButton.STYLE_DARK);
                FrameLayout.LayoutParams clickParams = new FrameLayout.LayoutParams(dp(36), dp(36));
                clickParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                clickParams.setMargins(0, 0, 0, dp(6));
                int clickCentre = keyCentre + 39 + 8 + 18;
                click.setTranslationX(dp(k == 0 ? -clickCentre : clickCentre));
                frameLayout.addView(click, clickParams);
            }
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

    // ---- 스틱 감도 ----
    // Each stick's touch sensitivity (0.5x..2x, StickSensitivityDialog), kept per side for
    // every game; the sticks on screen follow a change at once.
    private final java.util.List<AnalogStickView> leftSticks = new java.util.ArrayList<>();
    private final java.util.List<AnalogStickView> rightSticks = new java.util.ArrayList<>();

    private String stickKey(boolean right) {
        return right ? "stick_sens_right" : "stick_sens_left";
    }

    // The default for all games.
    private float stickSensitivityAll(boolean right) {
        return StickSensitivityDialog.clamp(MainActivity.sharedSettings(this).getFloat(stickKey(right), 1.0f));
    }

    // Whether this game has a value of its own.
    private boolean stickSensitivityOwn(boolean right) {
        return MainActivity.sharedSettings(this).contains(stickKey(right) + ":" + this.saveBaseName);
    }

    // The value in force for this game: its own, else the default.
    private float stickSensitivity(boolean right) {
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);
        return StickSensitivityDialog.clamp(prefs.getFloat(stickKey(right) + ":" + this.saveBaseName, stickSensitivityAll(right)));
    }

    private AnalogStickView leftStick() {
        AnalogStickView stick = new AnalogStickView(this, this::onAnalogChanged);
        stick.setSensitivity(stickSensitivity(false));
        this.leftSticks.add(stick);
        return stick;
    }

    private AnalogStickView rightStick() {
        AnalogStickView stick = new AnalogStickView(this, this::onAnalogRightChanged);
        stick.setSensitivity(stickSensitivity(true));
        this.rightSticks.add(stick);
        return stick;
    }

    private boolean hasLeftStick() {
        return isSonyPad() || isN64() || (isDreamcast() && !isNaomi()) || isN3ds() || isWiiU() || isDolphin();
    }

    private LinearLayout createStickRow(boolean right, Runnable reopen) {
        String name = right ? "오른쪽 스틱 감도" : isDualShock() || isWiiU() ? "왼쪽 스틱 감도" : "스틱 감도";
        String shown = StickSensitivityDialog.format(stickSensitivity(right)) + (stickSensitivityOwn(right) ? " · 이 게임" : "");
        return createSettingRow(RoundIconView.STICK, RoundIconView.COLOR_STICK, name, shown,
                view -> StickSensitivityDialog.show(this, name, stickSensitivity(right), stickSensitivityAll(right), this.gameTitle, (value, thisGame) -> {
                    android.content.SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                    if (thisGame) {
                        edit.putFloat(stickKey(right) + ":" + this.saveBaseName, value);
                    } else {
                        edit.putFloat(stickKey(right), value).remove(stickKey(right) + ":" + this.saveBaseName);
                    }
                    edit.apply();
                    for (AnalogStickView stick : right ? this.rightSticks : this.leftSticks) {
                        stick.setSensitivity(value);
                    }
                    reopen.run();
                }));
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
        return this.coreId.equals("gba") || isSnes() || isDualScreen() || isNes() || isMegaDrive() || isMasterSystem() || isPcEngine() || isSonyPad();
    }

    private boolean hasShoulders() {
        return (this.coreId.equals("gba") && !isGameBoy()) || isSnes() || isDualScreen() || isPsp();
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

    // ---- 화면 모양: 픽셀 정확 · 비율 · 모서리 · 테두리 ----
    private static final String[] SHAPE_ASPECT_NAMES = {"원본", "4:3", "16:9", "8:7"};
    private FrameLayout screenFrame;
    private ScreenMaskView screenMask;

    // Settings live per console, or per game once "이 게임에만" was turned on for it.
    private String shapeScope() {
        String game = "shape:game:" + this.saveBaseName;
        return MainActivity.sharedSettings(this).getBoolean(game + ":on", false) ? game : "shape:" + this.gameMode;
    }

    private int shapeInt(String key, int fallback) {
        return MainActivity.sharedSettings(this).getInt(shapeScope() + ":" + key, fallback);
    }

    /** 0 fit the frame (as always), 1 whole-number multiples of the game's pixels. */
    private int shapeScaleMode() {
        return is3d() || isMame139() ? 0 : shapeInt("scale", 0);
    }

    private int shapeAspectMode() {
        return isDualScreen() ? 0 : Math.max(0, Math.min(SHAPE_ASPECT_NAMES.length - 1, shapeInt("aspect", 0)));
    }

    private int shapeCornerDp() {
        return Math.max(0, Math.min(24, shapeInt("corner", 0)));
    }

    private boolean shapeBorder() {
        return shapeInt("border", 0) == 1;
    }

    private float screenAspect() {
        float base = baseScreenAspect();
        switch (shapeAspectMode()) {
            case 1: return 4.0f / 3.0f;
            case 2: return 16.0f / 9.0f;
            case 3: return 8.0f / 7.0f;
            default: return base;
        }
    }

    /**
     * Sizes the picture inside its frame: the whole frame, or in 픽셀 정확 mode the largest
     * whole multiple of the game's frame that fits, centred; the filter and the corner mask
     * follow it.
     */
    private void applyScreenShape() {
        SurfaceView surface = this.surfaceView;
        FrameLayout frame = this.screenFrame;
        if (surface == null || frame == null || !(surface.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        int width = -1;
        int height = -1;
        long handle = this.nativeHandle;
        if (shapeScaleMode() == 1 && handle != 0 && frame.getWidth() > 0) {
            int[] size = NativeGba.frameSize(handle);
            int availableWidth = frame.getWidth() - frame.getPaddingLeft() - frame.getPaddingRight();
            int availableHeight = frame.getHeight() - frame.getPaddingTop() - frame.getPaddingBottom();
            if (size != null && size.length == 2 && size[0] > 0 && size[1] > 0 && availableWidth > 0 && availableHeight > 0) {
                int k = Math.max(1, Math.min(availableWidth / size[0], availableHeight / size[1]));
                width = Math.min(availableWidth, size[0] * k);
                height = Math.min(availableHeight, size[1] * k);
            }
        }
        java.util.List<View> boxed = new java.util.ArrayList<>();
        boxed.add(surface);
        boxed.addAll(this.screenFilters);
        if (this.screenMask != null) boxed.add(this.screenMask);
        for (View view : boxed) {
            if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
                params.width = width;
                params.height = height;
                params.gravity = Gravity.CENTER;
                view.setLayoutParams(params);
            }
        }
        if (this.screenMask != null) {
            this.screenMask.setShape(dp(shapeCornerDp()), shapeBorder(), Color.rgb(18, 22, 32));
            this.screenMask.bringToFront();
        }
    }

    private void showScreenShapeSheet(Runnable reopen) {
        this.holdPause = true;
        if (this.pauseDialog != null && this.pauseDialog.isShowing()) {
            // The picture must be in view while its shape changes.
            this.pauseDialog.dismiss();
        }
        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(1);
        final int aspectBefore = shapeAspectMode();
        dialog.setOnDismissListener(d -> {
            this.holdPause = false;
            if (shapeAspectMode() != aspectBefore) {
                rebuildContent();
            }
            resumeUnlessExiting();
        });
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(16), dp(16), dp(14));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Theme.bg(Color.WHITE));
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        TextView title = new TextView(this);
        title.setText("화면 모양");
        title.setTextSize(17.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        panel.addView(title);
        TextView sub = new TextView(this);
        int[] size = this.nativeHandle != 0 ? NativeGba.frameSize(this.nativeHandle) : null;
        sub.setText(consoleName() + (size != null && size.length == 2 && size[0] > 0 ? " · " + size[0] + "×" + size[1] : "") + " · 바꾸면 바로 적용돼요");
        sub.setTextSize(11.5f);
        sub.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        panel.addView(sub);
        final String scope = shapeScope();
        final android.content.SharedPreferences prefs = MainActivity.sharedSettings(this);

        if (!is3d() && !isMame139()) {
            panel.addView(shapeSection("배율"));
            LinearLayout row = new LinearLayout(this);
            final TextView[] chips = new TextView[2];
            String[] names = {"맞춤 (틀에 맞게)", "픽셀 정확 (정수 배)"};
            for (int i = 0; i < 2; i++) {
                final int mode = i;
                TextView chip = shapeChip(names[i], shapeScaleMode() == i);
                chip.setOnClickListener(view -> {
                    prefs.edit().putInt(scope + ":scale", mode).apply();
                    for (int k = 0; k < 2; k++) setShapeChip(chips[k], k == mode);
                    applyScreenShape();
                    updateScreenFilters();
                });
                chips[i] = chip;
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
                chipParams.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
                row.addView(chip, chipParams);
            }
            panel.addView(row);
            TextView hint = new TextView(this);
            hint.setText("픽셀 정확: 틀에 들어가는 가장 큰 정수 배로 도트를 고르게 키워요. 남는 가장자리는 어두운 틀 색이고, 스캔라인 · LCD 격자 필터와 정확히 맞물려요.");
            hint.setTextSize(10.5f);
            hint.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            hint.setPadding(dp(2), dp(4), 0, 0);
            panel.addView(hint);
        }
        if (!isDualScreen()) {
            panel.addView(shapeSection("비율"));
            LinearLayout row = new LinearLayout(this);
            final TextView[] chips = new TextView[SHAPE_ASPECT_NAMES.length];
            for (int i = 0; i < SHAPE_ASPECT_NAMES.length; i++) {
                final int mode = i;
                TextView chip = shapeChip(SHAPE_ASPECT_NAMES[i], shapeAspectMode() == i);
                chip.setOnClickListener(view -> {
                    prefs.edit().putInt(scope + ":aspect", mode).apply();
                    for (int k = 0; k < chips.length; k++) setShapeChip(chips[k], k == mode);
                });
                chips[i] = chip;
                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
                chipParams.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
                row.addView(chip, chipParams);
            }
            panel.addView(row);
            TextView hint = new TextView(this);
            hint.setText("그림을 늘리지 않고 틀의 비율을 바꿔요. 창을 닫을 때 적용돼요 (4:3은 PS1 · 아케이드, 8:7은 슈퍼패미컴 실기 느낌).");
            hint.setTextSize(10.5f);
            hint.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
            hint.setPadding(dp(2), dp(4), 0, 0);
            panel.addView(hint);
        }
        panel.addView(shapeSection("모서리 둥글기"));
        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView value = new TextView(this);
        value.setText(shapeCornerDp() + "dp");
        value.setTextSize(12.0f);
        value.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        value.setGravity(Gravity.END);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(24);
        bar.setProgress(shapeCornerDp());
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "dp");
                if (fromUser) {
                    prefs.edit().putInt(scope + ":corner", progress).apply();
                    applyScreenShape();
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });
        sliderRow.addView(bar, new LinearLayout.LayoutParams(0, -2, 1.0f));
        sliderRow.addView(value, new LinearLayout.LayoutParams(dp(44), -2));
        panel.addView(sliderRow);
        panel.addView(shapeToggle("화면 테두리 선", "얇은 어두운 선으로 화면과 케이스 경계를 또렷하게", shapeBorder(), on -> {
            prefs.edit().putInt(scope + ":border", on ? 1 : 0).apply();
            applyScreenShape();
        }));
        final String gameScope = "shape:game:" + this.saveBaseName;
        panel.addView(shapeToggle("이 게임에만 적용", "켜면 지금 값이 이 게임의 설정으로 복사돼요", prefs.getBoolean(gameScope + ":on", false), on -> {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (on) {
                for (String key : new String[]{"scale", "aspect", "corner", "border"}) {
                    edit.putInt(gameScope + ":" + key, shapeInt(key, 0));
                }
                edit.putBoolean(gameScope + ":on", true);
            } else {
                edit.remove(gameScope + ":on");
            }
            edit.apply();
            dialog.dismiss();
            showScreenShapeSheet(reopen);
        }));
        LinearLayout actions = new LinearLayout(this);
        TextView reset = dialogButton("초기화", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        reset.setOnClickListener(view -> {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : new String[]{"scale", "aspect", "corner", "border"}) {
                edit.remove(scope + ":" + key);
            }
            edit.apply();
            applyScreenShape();
            updateScreenFilters();
            dialog.dismiss();
            showScreenShapeSheet(reopen);
        });
        TextView done = dialogButton("완료", Color.rgb(225, 29, 72), Color.WHITE);
        done.setOnClickListener(view -> dialog.dismiss());
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(46), 1.0f);
        doneParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(done, doneParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.setMargins(0, dp(14), 0, 0);
        panel.addView(actions, actionsParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.graphics.Rect screen = screenRectInWindow();
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            if (!this.landscapeMode && screen != null) {
                // Below the picture, so the corners and pixels can be watched while changing.
                window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                android.view.WindowManager.LayoutParams params = window.getAttributes();
                params.y = Math.min(screen.bottom + dp(6), metrics.heightPixels - dp(380));
                window.setAttributes(params);
                window.setLayout(Math.min(metrics.widthPixels - dp(24), dp(400)), Math.min(dp(420), metrics.heightPixels - params.y - dp(8)));
            } else {
                window.setGravity(Gravity.CENTER);
                window.setLayout(Math.min(metrics.widthPixels - dp(40), dp(430)), Math.min(metrics.heightPixels - dp(24), dp(420)));
            }
        }
        dialog.show();
    }

    private TextView shapeSection(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(Theme.text(Color.rgb(85, 95, 110)));
        view.setPadding(0, dp(12), 0, dp(6));
        return view;
    }

    private TextView shapeChip(String text, boolean on) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(11.5f);
        chip.setGravity(Gravity.CENTER);
        chip.setMaxLines(1);
        setShapeChip(chip, on);
        return chip;
    }

    private void setShapeChip(TextView chip, boolean on) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(17));
        background.setColor(Theme.bg(on ? Color.rgb(255, 241, 243) : Color.WHITE));
        background.setStroke(dp(on ? 2 : 1), Theme.line(on ? Color.rgb(225, 29, 72) : Color.rgb(214, 218, 226)));
        chip.setBackground(background);
        chip.setTextColor(Theme.text(on ? Color.rgb(159, 18, 57) : Color.rgb(31, 41, 51)));
        chip.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    }

    private interface BoolSetter {
        void set(boolean on);
    }

    private View shapeToggle(String name, String note, boolean on, BoolSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(13.0f);
        label.setTextColor(Theme.text(Color.rgb(31, 41, 51)));
        texts.addView(label);
        TextView small = new TextView(this);
        small.setText(note);
        small.setTextSize(10.5f);
        small.setTextColor(Theme.text(Color.rgb(122, 130, 140)));
        texts.addView(small);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((view, checked) -> setter.set(checked));
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private float baseScreenAspect() {
        if (arcadeUpright()) {
            return 3.0f / 4.0f;  // a vertical arcade game stood up, as its cabinet showed it
        }
        if (isVita()) {
            return 960.0f / 544.0f;
        }
        if (isWiiU()) {
            // The TV over the GamePad in portrait (both 16:9), one of them otherwise.
            return this.ndsLayout == 0 && wiiuView() == 0 ? 1280.0f / 1440.0f : 1280.0f / 720.0f;
        }
        if (isN3ds()) {
            // 400x240 over 320x240 as Citra lays them out, like the DS: stacked (the lower
            // one centred) or side by side.
            return this.ndsLayout == 2 ? 720.0f / 240.0f : 400.0f / 480.0f;
        }
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
        if (isDolphin()) {
            int aspect = dolphinAspect();
            return aspect == 1 ? 4.0f / 3.0f : aspect >= 2 || isWiiGame() ? 16.0f / 9.0f : 4.0f / 3.0f;
        }
        if (isNeoGeoPocket()) {
            return 160.0f / 152.0f;
        }
        if (isPc98() && MainActivity.sharedSettings(this).getBoolean("pc98_pixel", false)) {
            return 640.0f / 400.0f;   // pixels as they are (the monitor showed 4:3)
        }
        if (ps2Wide()) {
            return 16.0f / 9.0f;   // PS2 와이드스크린 패치 or 늘려 보기
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
            case "swanstation":
            case "play":
            case "pcee2":
            case "melonds":
            case "citra":
            case "cemu":
            case "mupen64plus_next":
            case "flycast":
            case "wswan":
            case "ngp":
            case "picodrive":
            case "yabasanshiro":
            case "dolphin":
            case "dosbox_pure":
            case "np2kai":
            case "bluemsx":
            case "vita3k":
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
