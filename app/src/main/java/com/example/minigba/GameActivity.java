package com.example.minigba;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
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
    private int speedMultiplier = 1;
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
        if (stringExtra == null) {
            this.coreId = "gba";
        }
        this.gameMode = getIntent().getStringExtra(EXTRA_MODE);
        if (this.gameMode == null) {
            this.gameMode = this.coreId.equals("gba") ? "gba" : isSnes() ? "snes" : isNes() ? "nes" : isMegaDrive() ? "md" : isPcEngine() ? "pce" : isPsp() ? "psp" : isPs1() ? "ps1" : isPs2() ? "ps2" : "arcade";
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
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(-1, -1);
        layoutParams.gravity = 17;
        frameLayout.addView(this.surfaceView, layoutParams);
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
        linearLayout.addView(frameLayout2, controlsParams);
        FrameLayout root = new FrameLayout(this);
        ClearShellView shell = createShell(false);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
        shell.setThinBezel(full);
        shell.track(frameLayout, frameLayout2);
        setContentView(root);
    }

    private void createLandscapeContent() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        FrameLayout frameLayout = new FrameLayout(this);
        final boolean full = isFullScreenStyle();
        final int i = full ? 1 : 0;
        int dp = full ? 0 : dp(2);
        final FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(Color.rgb(18, 22, 32));
        frameLayout2.setPadding(dp, dp, dp, dp);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        frameLayout2.addView(this.surfaceView, new FrameLayout.LayoutParams(-1, -1, 17));
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
        shell.track(frameLayout2, frameLayout);
        setContentView(root);
    }

    // "프레임" (the default) draws the case's bezel around the screen; "전체 화면" drops it
    // and makes the screen as large as fits beside the controls.
    private boolean isGbaWideFull() {
        return this.coreId.equals("gba") && this.landscapeMode && isFullScreenStyle();
    }

    private boolean isFullScreenStyle() {
        return "full".equals(getSharedPreferences("settings", 0).getString("screen_style", "frame"));
    }

    // Side margin and height of the portrait screen for a side margin and vertical padding.
    private int[] portraitScreen(int side, int padding) {
        int screenWidth = Math.max(dp(200), getResources().getDisplayMetrics().widthPixels - side * 2);
        if (isGameBoy()) {
            // The nearly square Game Boy screen would push the controls off a short phone.
            int widest = Math.round(dp(250) * screenAspect());
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
        } else if (isNes()) {
            tint = ClearShellView.TINT_CREAM;
            name = "FAMICOM";
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
        if (full) {
            // Room for the thin glass bezel (dp(5) each side) included.
            maxHeight = Math.max(dp(150), height - (isGba ? dp(13) + dp(5) + dp(46) : dp(12) + dp(10)));
        } else {
            maxHeight = Math.max(dp(150), height - (isGba ? dp(18) + dp(26) + dp(46) : dp(12) + dp(36)));
        }
        float aspect = screenAspect();
        int screenWidth = Math.min(maxWidth, Math.round(maxHeight * aspect));
        int screenHeight = Math.round(screenWidth / aspect);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameLayout.getLayoutParams();
        int topMargin = isGba ? dp(full ? 13 : 18) : 0;
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
            } else if (isSnes()) {
                // X on top, A right, B bottom, Y left, in the Super Famicom colours.
                addLandscapeArcadeButton(frameLayout, "X", 1024, 66, -48, 50, TouchButton.STYLE_SNES_BLUE);
                addLandscapeArcadeButton(frameLayout, "A", 1, 18, 0, 50, TouchButton.STYLE_ARCADE_RED);
                addLandscapeArcadeButton(frameLayout, "B", 2, 66, 48, 50, TouchButton.STYLE_ARCADE_YELLOW);
                addLandscapeArcadeButton(frameLayout, "Y", 2048, 114, 0, 50, TouchButton.STYLE_ARCADE_GREEN);
            } else {
                addLandscapeActionButton(frameLayout, "A", 1, dp(wideGba ? 6 : 26), -dp(42), 3);
                addLandscapeActionButton(frameLayout, "B", 2, dp(wideGba ? 62 : 92), dp(42), 3);
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
        this.buttonMask = i | (this.buttonMask & (-241));
        updateInput();
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

    private void setButtonPressed(int i, boolean z) {
        if (z) {
            this.buttonMask = i | this.buttonMask;
        } else {
            this.buttonMask = (~i) & this.buttonMask;
        }
        updateInput();
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
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, true);
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
            NativeGba.pause(j, false);
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
            NativeGba.pause(this.nativeHandle, false);
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
                    }
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
        NativeGba.setSpeedMultiplier(handle, this.speedMultiplier);
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
        speedButtonView.setMultiplier(this.speedMultiplier);
        speedButtonView.setContentDescription("배속");
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(dp(80), dp(36));
        if (isPadLayout()) {
            speedParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            speedParams.setMargins(0, 0, 0, dp(6));
        } else {
            speedParams.gravity = Gravity.TOP | Gravity.END;
            speedParams.setMargins(0, dp(62), dp(14), 0);
        }
        frameLayout.addView(speedButtonView, speedParams);
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
        } else if (isPs2()) {
            // PS2 games save to the memory card themselves; saveNow skips Play!'s state.
            callCore(NativeGba::saveStateNow, "빠른 저장을 완료했습니다.", "저장 실패.");
        } else {
            callCore(NativeGba::saveNow, "빠른 저장과 게임 내 세이브를 저장했습니다.", "저장 실패.");
        }
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

    private void onSpeedChanged(int i) {
        this.speedMultiplier = i;
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.setSpeedMultiplier(j, i);
        }
    }

    private void toggleOrientation() {
        callCore(NativeGba::saveNow, null, null);
        int i = !this.landscapeMode ? 1 : 0;
        getSharedPreferences("settings", 0).edit().putInt("orientation_mode", i).commit();
        setRequestedOrientation(i == 1 ? 6 : 1);
    }

    private void addControls(FrameLayout frameLayout) {
        View createDirectionControl = createDirectionControl();
        boolean isGba = isPadLayout();
        // The Super Famicom's four face buttons need more room, so its D-pad is a little smaller.
        boolean compactPad = isSnes() || isMegaDrive();
        int i = isSonyPad() ? 140 : compactPad ? 150 : isGba ? 166 : 156;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(i), dp(i));
        layoutParams.gravity = 8388691;
        // The PSP's D-pad sits higher to leave room for the analog stick below it.
        // The PS1/PS2 pads keep both sticks along the bottom, so their D-pad sits lower still.
        layoutParams.setMargins(dp(compactPad || isSonyPad() ? 16 : isGba ? 24 : 16), 0, 0, dp(isDualShock() ? 160 : isSonyPad() ? 214 : isGba ? 154 : 144));
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
            addButton(frameLayout, "L", 512, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        }
        addFastForwardButton(frameLayout);
        if (isSonyPad()) {
            addPspFace(frameLayout);
        } else if (isSnes()) {
            // Diamond centred on the D-pad's height: X top, A right, B bottom, Y left.
            addButton(frameLayout, "X", 1024, 8388693, dp(72), dp(254), dp(54), dp(54), TouchButton.STYLE_SNES_BLUE);
            addButton(frameLayout, "A", 1, 8388693, dp(20), dp(202), dp(54), dp(54), TouchButton.STYLE_ARCADE_RED);
            addButton(frameLayout, "B", 2, 8388693, dp(72), dp(150), dp(54), dp(54), TouchButton.STYLE_ARCADE_YELLOW);
            addButton(frameLayout, "Y", 2048, 8388693, dp(124), dp(202), dp(54), dp(54), TouchButton.STYLE_ARCADE_GREEN);
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
            addButton(frameLayout, "A", 1, 8388693, dp(44), dp(243), dp(72), dp(72), 3);
            addButton(frameLayout, "B", 2, 8388693, dp(78), dp(159), dp(72), dp(72), 3);
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
        speedButtonView.setMultiplier(this.speedMultiplier);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(42));
        layoutParams.gravity = 49;
        layoutParams.setMargins(0, dp(37), 0, 0);
        frameLayout.addView(this.fastForwardButton, layoutParams);
    }

    private void addCenterButton(FrameLayout frameLayout, String str, int i, int i2) {
        TouchButton touchButton = new TouchButton(this, str, i, this::setButtonPressed);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(34));
        layoutParams.gravity = 81;
        layoutParams.setMargins(0, 0, 0, dp(72));
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
        if (isPsp()) {
            LinearLayout.LayoutParams languageParams = new LinearLayout.LayoutParams(-1, dp(52));
            languageParams.setMargins(0, dp(12), 0, 0);
            panel.addView(createLanguageRow(dialog), languageParams);
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

        dialog.setContentView(panel);
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

    private LinearLayout createLanguageRow(android.app.Dialog menu) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.rgb(246, 247, 249));
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        row.addView(new RoundIconView(this, RoundIconView.GLOBE, RoundIconView.COLOR_GLOBE), new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = new TextView(this);
        label.setText("PSP 시스템 언어");
        label.setTextSize(14.0f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.rgb(31, 41, 51));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        labelParams.setMargins(dp(12), 0, 0, 0);
        row.addView(label, labelParams);
        TextView value = new TextView(this);
        value.setText(PSP_LANGUAGE_NAMES[pspLanguageIndex()] + "  ›");
        value.setTextSize(13.5f);
        value.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        value.setTextColor(RoundIconView.COLOR_GLOBE);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setOnClickListener(view -> {
            // The pause menu stays open underneath; the game stays paused meanwhile.
            showLanguageDialog(menu);
        });
        return row;
    }

    private void showLanguageDialog(android.app.Dialog menu) {
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
        header.addView(new RoundIconView(this, RoundIconView.GLOBE, RoundIconView.COLOR_GLOBE), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(1);
        TextView title = new TextView(this);
        title.setText("PSP 시스템 언어");
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

        final int[] selected = {pspLanguageIndex()};
        final LinearLayout[] options = new LinearLayout[PSP_LANGUAGES.length];
        final View[] dots = new View[PSP_LANGUAGES.length];
        Runnable refresh = () -> {
            for (int k = 0; k < options.length; k++) {
                boolean on = k == selected[0];
                android.graphics.drawable.GradientDrawable optionBackground = new android.graphics.drawable.GradientDrawable();
                optionBackground.setColor(on ? Color.rgb(238, 240, 255) : Color.rgb(246, 247, 249));
                optionBackground.setCornerRadius(dp(14));
                options[k].setBackground(optionBackground);
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setStroke(dp(2), on ? RoundIconView.COLOR_GLOBE : Color.rgb(201, 206, 214));
                dot.setColor(Color.WHITE);
                dots[k].setBackground(on ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{dot, innerDot()}) : dot);
            }
        };
        for (int k = 0; k < PSP_LANGUAGES.length; k++) {
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
            name.setText(PSP_LANGUAGE_NAMES[k]);
            name.setTextSize(14.5f);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(Color.rgb(31, 41, 51));
            texts.addView(name);
            TextView note = new TextView(this);
            note.setText(PSP_LANGUAGE_NOTES[k]);
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
        warning.setText("이 게임에만 적용돼요. 바꾸면 게임이 처음부터 다시 시작돼요.\n게임 안 세이브(메모리스틱)는 그대로 남아요.");
        warning.setTextSize(12.0f);
        warning.setLineSpacing(0, 1.15f);
        warning.setTextColor(Color.rgb(138, 90, 0));
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable warningBackground = new android.graphics.drawable.GradientDrawable();
        warningBackground.setColor(Color.rgb(255, 247, 232));
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
        TextView apply = dialogButton("적용하고 재시작", RoundIconView.COLOR_GLOBE, Color.WHITE);
        apply.setOnClickListener(view -> {
            String language = PSP_LANGUAGES[selected[0]];
            dialog.dismiss();
            if (language.equals(pspLanguage())) {
                return;
            }
            getSharedPreferences("settings", 0).edit().putString("psp_language:" + this.saveBaseName, language).apply();
            menu.dismiss();
            callCore(handle -> {
                NativeGba.setPspLanguage(handle, language);
                return true;
            }, "PSP 시스템 언어를 " + PSP_LANGUAGE_NAMES[selected[0]] + "로 바꾸고 게임을 재시작했습니다.", null);
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
    private android.graphics.drawable.Drawable innerDot() {
        android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable();
        inner.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        inner.setColor(RoundIconView.COLOR_GLOBE);
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
        File state = new File(new File(new File(getFilesDir(), "saves"), this.coreId), this.saveBaseName + ".state");
        if (!state.isFile()) {
            return "저장 없음";
        }
        long modified = state.lastModified();
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
                callCore(NativeGba::saveStateNow, "빠른 저장을 완료했습니다.", "빠른 저장 실패.");
                return;
            }
        }
        if (i == 2) {
            if (isMame139()) {
                Emulator.loadState();
                Toast.makeText(this, "MAME 0.139 빠른 저장 불러오기를 요청했습니다.", 0).show();
                return;
            } else {
                callCore(NativeGba::loadStateNow, "빠른 저장을 불러왔습니다.", "빠른 저장 파일이 없습니다.");
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
            if (this.menuExiting) {
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
        new AlertDialog.Builder(this).setTitle(isPadLayout() ? "Mini Emulator" : "Mini Arcade").setMessage(str).setPositiveButton(17039370, (dialogInterface, i) -> finish()).show();
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

    private boolean isNes() {
        return "fceumm".equals(this.coreId);
    }

    private boolean isMegaDrive() {
        return "genesis_plus_gx".equals(this.coreId);
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
        // The screen leaves dp(104) of height for its bezel and the bottom row, and at least
        // dp(136) on each side for the control columns.
        float aspect = screenAspect();
        int screenHeight = Math.max(dp(150), height - dp(104));
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
        boolean dual = v.length > 8;
        if (dual) {
            int lh = (lw - dp(8)) / 2;
            int rh = (rw - dp(8)) / 2;
            setBox(v[8], l0, shoulderTop, lh, dp(36));
            setBox(v[0], l0 + lh + dp(8), shoulderTop, lh, dp(36));
            setBox(v[1], r0, shoulderTop, rh, dp(36));
            setBox(v[9], r0 + rh + dp(8), shoulderTop, rh, dp(36));
        } else {
            setBox(v[0], l0, shoulderTop, lw, dp(36));
            setBox(v[1], r1 - rw, shoulderTop, rw, dp(36));
        }
        int columnTop = shoulderTop + dp(44);
        int columnBottom = height - dp(46);
        int avail = Math.max(dp(120), columnBottom - columnTop);
        int pad = Math.min(Math.min(lw, dp(130)), Math.round(avail * 0.58f));
        setBox(v[2], lc - pad / 2, columnTop, pad, pad);
        int stick = Math.max(dp(56), Math.min(Math.min(lw - dp(24), dp(88)), avail - pad - dp(10)));
        setBox(v[3], lc - stick / 2, columnTop + pad + dp(10), stick, stick);
        // △○×□ diamond centred a little below the D-pad's centre, fitted to the right column.
        float gap = Math.min(dp(46), rw / 3.1f);
        int size = Math.round(gap * 1.1f);
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
        return this.coreId.equals("gba") || isSnes() || isNes() || isMegaDrive() || isPcEngine() || isSonyPad();
    }

    private boolean hasShoulders() {
        return (this.coreId.equals("gba") && !isGameBoy()) || isSnes() || isPsp();
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
        if (isPsp()) {
            return 480.0f / 272.0f;
        }
        if (isGameBoy()) {
            return 160.0f / 144.0f;
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
