package com.example.minigba;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    static final String EXTRA_MODE = "com.example.minigba.MODE";
    private static final String MENU_CONTINUE = "계속하기";
    private static final String MENU_SAVE_STATE = "빠른 저장";
    private static final String MENU_LOAD_STATE = "빠른 저장 불러오기";
    private static final String MENU_SAVE_SRAM = "게임 내 세이브 저장";
    private static final String MENU_RESET = "재시작";
    private static final String MENU_EXIT = "ROM 목록으로 나가기";
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREF_ORIENTATION_MODE = "orientation_mode";
    private static final String PREF_SCREEN_MODE = "screen_mode";
    private SurfaceHolder activeSurfaceHolder;
    private AudioSink audioSink;
    private int buttonMask;
    private String coreId;
    private String gameMode;
    private String[] menuItems = new String[0];
    private SpeedButtonView fastForwardButton;
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
    private String romPath;
    private String saveBaseName;
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
        String stringExtra = getIntent().getStringExtra("com.example.minigba.CORE_ID");
        this.coreId = stringExtra;
        if (stringExtra == null) {
            this.coreId = "gba";
        }
        this.gameMode = getIntent().getStringExtra(EXTRA_MODE);
        if (this.gameMode == null) {
            this.gameMode = this.coreId.equals("gba") ? "gba" : "arcade";
        }
        String str = this.romPath;
        String lowerCase = str == null ? "" : str.toLowerCase(Locale.US);
        if (!isSupportedGameFile(lowerCase)) {
            showFatalError("지원하지 않는 파일입니다");
            return;
        }
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
        int dp;
        int dp2;
        int i;
        int i2;
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(Color.rgb(218, 215, 207));
        linearLayout.addView(createTopBar(), new LinearLayout.LayoutParams(-1, dp(50)));
        int i3 = getSharedPreferences("settings", 0).getInt("screen_mode", 0);
        if (i3 == 1) {
            i = dp(12);
            i2 = dp(3);
            dp = dp(3);
            dp2 = dp(3);
        } else if (i3 == 2) {
            i = dp(22);
            i2 = dp(3);
            dp = dp(3);
            dp2 = dp(3);
        } else {
            dp = dp(4);
            dp2 = dp(2);
            i = 0;
            i2 = 0;
        }
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setBackgroundColor(Color.rgb(18, 22, 32));
        frameLayout.setPadding(i2, dp, i2, dp2);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(-1, -1);
        layoutParams.gravity = 17;
        frameLayout.addView(this.surfaceView, layoutParams);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, Math.max(dp(176), Math.round(Math.max(dp(240), (getResources().getDisplayMetrics().widthPixels - (i * 2)) - (i2 * 2)) * (this.coreId.equals("gba") ? 0.6666667f : 0.75f)) + dp + dp2));
        layoutParams2.setMargins(i, dp(2), i, 0);
        linearLayout.addView(frameLayout, layoutParams2);
        FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(0);
        frameLayout2.addView(new ControlPanelView(this), new FrameLayout.LayoutParams(-1, -1));
        addControls(frameLayout2);
        linearLayout.addView(frameLayout2, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(linearLayout);
    }

    private void createLandscapeContent() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(Color.rgb(218, 215, 207));
        linearLayout.addView(createTopBar(), new LinearLayout.LayoutParams(-1, dp(46)));
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setBackgroundColor(Color.rgb(218, 215, 207));
        frameLayout.addView(new ControlPanelView(this), new FrameLayout.LayoutParams(-1, -1));
        final int i = getSharedPreferences("settings", 0).getInt("screen_mode", 0);
        int dp = dp(i == 0 ? 2 : 3);
        final FrameLayout frameLayout2 = new FrameLayout(this);
        frameLayout2.setBackgroundColor(Color.rgb(18, 22, 32));
        frameLayout2.setPadding(dp, dp, dp, dp);
        SurfaceView surfaceView = new SurfaceView(this);
        this.surfaceView = surfaceView;
        surfaceView.getHolder().addCallback(this);
        frameLayout2.addView(this.surfaceView, new FrameLayout.LayoutParams(-1, -1, 17));
        frameLayout.addView(frameLayout2, new FrameLayout.LayoutParams(dp(320), dp(214), 17));
        addLandscapeControls(frameLayout);
        frameLayout.addOnLayoutChangeListener((view, i2, i3, i4, i5, i6, i7, i8, i9) -> fitLandscapeScreen(i, frameLayout2, view, i2, i3, i4, i5, i6, i7, i8, i9));
        linearLayout.addView(frameLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(linearLayout);
    }

    private void fitLandscapeScreen(int i, FrameLayout frameLayout, View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return;
        }
        int inset = i == 1 ? dp(10) : i == 2 ? dp(20) : 0;
        boolean isGba = this.coreId.equals("gba");
        int maxWidth = Math.max(dp(240), width - ((dp(isGba ? 174 : 188) + inset) * 2));
        int maxHeight = Math.max(dp(160), (height - dp(12)) - (inset * 2));
        float aspect = isGba ? 1.5f : 1.3333334f;
        int screenWidth = Math.min(maxWidth, Math.round(maxHeight * aspect));
        int screenHeight = Math.round(screenWidth / aspect);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameLayout.getLayoutParams();
        if (layoutParams.width != screenWidth || layoutParams.height != screenHeight) {
            layoutParams.width = screenWidth;
            layoutParams.height = screenHeight;
            layoutParams.gravity = 17;
            frameLayout.setLayoutParams(layoutParams);
        }
    }

    private void addLandscapeControls(FrameLayout frameLayout) {
        View createDirectionControl = createDirectionControl();
        boolean isGba = this.coreId.equals("gba");
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(150), dp(150));
        layoutParams.gravity = 8388627;
        layoutParams.setMargins(dp(12), 0, 0, 0);
        frameLayout.addView(createDirectionControl, layoutParams);
        if (isGba) {
            addButton(frameLayout, "L", 512, 8388659, dp(18), dp(14), dp(104), dp(40), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(18), dp(14), dp(104), dp(40), 2);
            addLandscapeActionButton(frameLayout, "A", 1, dp(26), -dp(42), 3);
            addLandscapeActionButton(frameLayout, "B", 2, dp(92), dp(42), 3);
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
        addButton(frameLayout, isGba ? "SELECT" : "COIN", 4, 8388691, dp(42), dp(18), dp(94), dp(34));
        addButton(frameLayout, "START", 8, 8388693, dp(42), dp(18), dp(94), dp(34));
    }

    private View createDirectionControl() {
        if (!this.coreId.equals("gba")) {
            return new ArcadeJoystickView(this, this::onDirectionChanged);
        }
        return new DPadView(this, this::onDirectionChanged);
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
        touchButton.setTranslationY(i3);
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
        touchButton.setTranslationY(dp(i3));
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

    private LinearLayout createTopBar() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(16), dp(4), dp(16), dp(4));
        linearLayout.setBackgroundColor(Color.rgb(218, 215, 207));
        addTopButton(linearLayout, 0, "저장", view -> onSaveClicked());
        addTopButton(linearLayout, 1, "재시작", view -> onResetClicked());
        if (this.landscapeMode) {
            addTopSpeedButton(linearLayout);
        }
        addTopButton(linearLayout, 3, "화면 회전", view -> toggleOrientation());
        addTopButton(linearLayout, 2, "메뉴", view -> showGameMenu());
        return linearLayout;
    }

    private void onSaveClicked() {
        if (isMame139()) {
            Emulator.saveState();
            Toast.makeText(this, "MAME 0.139 빠른 저장을 요청했습니다.", 0).show();
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

    private void addTopSpeedButton(LinearLayout linearLayout) {
        SpeedButtonView speedButtonView = new SpeedButtonView(this, this::onSpeedChanged);
        this.fastForwardButton = speedButtonView;
        speedButtonView.setMultiplier(this.speedMultiplier);
        this.fastForwardButton.setContentDescription("배속");
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        layoutParams.setMargins(dp(10), 0, dp(10), 0);
        linearLayout.addView(this.fastForwardButton, layoutParams);
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

    private void addTopButton(LinearLayout linearLayout, int i, String str, View.OnClickListener onClickListener) {
        TopIconButtonView topIconButtonView = new TopIconButtonView(this, i);
        topIconButtonView.setContentDescription(str);
        topIconButtonView.setOnClickListener(onClickListener);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        layoutParams.setMargins(dp(18), 0, dp(18), 0);
        linearLayout.addView(topIconButtonView, layoutParams);
    }

    private void addControls(FrameLayout frameLayout) {
        View createDirectionControl = createDirectionControl();
        boolean isGba = this.coreId.equals("gba");
        int i = isGba ? 166 : 156;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(i), dp(i));
        layoutParams.gravity = 8388691;
        layoutParams.setMargins(dp(isGba ? 24 : 16), 0, 0, dp(isGba ? 154 : 144));
        frameLayout.addView(createDirectionControl, layoutParams);
        if (isGba) {
            addButton(frameLayout, "L", 512, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        }
        addFastForwardButton(frameLayout);
        if (isGba) {
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
        addCenterButton(frameLayout, isGba ? "SELECT" : "COIN", 4, -66);
        addCenterButton(frameLayout, "START", 8, 66);
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
        String[] strArr = this.menuItems;
        AlertDialog create = new AlertDialog.Builder(this).setTitle(this.coreId.equals("gba") ? "Mini Emulator" : "Mini Arcade").setItems(strArr, (dialogInterface, i) -> onGameMenuItemSelected(dialogInterface, i)).create();
        create.setOnDismissListener(dialog -> resumeUnlessExiting());
        create.show();
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
        new AlertDialog.Builder(this).setTitle(this.coreId.equals("gba") ? "Mini Emulator" : "Mini Arcade").setMessage(str).setPositiveButton(17039370, (dialogInterface, i) -> finish()).show();
    }

    private int dp(int i) {
        return (int) ((i * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private boolean isSupportedGameFile(String lowerCasePath) {
        switch (this.coreId) {
            case "gba":
                return lowerCasePath.endsWith(".gba");
            case "fbneo":
            case "mame2003plus":
            case "mame2010":
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
