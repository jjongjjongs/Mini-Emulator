package com.example.minigba;

import android.app.Activity;
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
import android.view.SurfaceHolder;
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
import com.armsx3.core.ProgressRepository;
import com.armsx3.core.ARMSX3;

// PS3 games on the ARMSX3 engine (RPCS3 for Android), and its firmware install. The engine is
// not a libretro core: it draws to the Surface itself, plays its own sound and takes the whole
// pad state on every change. It runs in its own process (":ps3", see the manifest), which ends
// when the game is left, so every launch starts the engine fresh.
public final class Ps3Activity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_ACTION = "com.example.minigba.PS3_ACTION";
    static final String EXTRA_PATH = "com.example.minigba.PS3_PATH";
    static final String EXTRA_TITLE = "com.example.minigba.PS3_TITLE";
    // (0.0.392) Where a PSN game installed from a .pkg gets its entry (<title>.ps3game).
    static final String EXTRA_ROM_DIR = "com.example.minigba.PS3_ROM_DIR";
    // (0.0.396) Files in the app's own cache to install (PKGs taken out of a patch zip),
    // deleted after.
    static final String EXTRA_INSTALL_PATHS = "com.example.minigba.PS3_INSTALL_PATHS";
    static final String ACTION_BOOT = "boot";
    static final String ACTION_FIRMWARE = "firmware";
    // (0.0.392) From a game's long-press menu: its patches, or a file to install for it.
    static final String ACTION_PATCHES = "patches";
    static final String ACTION_INSTALL = "install";

    private static final int INDIGO = Color.rgb(79, 70, 229);

    // CELL_PAD digital1 / digital2 bits.
    private static final int D1_SELECT = 0x1;
    private static final int D1_L3 = 0x2;
    private static final int D1_R3 = 0x4;
    private static final int D1_START = 0x8;
    private static final int D1_UP = 0x10;
    private static final int D1_RIGHT = 0x20;
    private static final int D1_DOWN = 0x40;
    private static final int D1_LEFT = 0x80;
    private static final int D1_PS = 0x100;
    private static final int D2_L2 = 0x1;
    private static final int D2_R2 = 0x2;
    private static final int D2_L1 = 0x4;
    private static final int D2_R1 = 0x8;
    private static final int D2_TRIANGLE = 0x10;
    private static final int D2_CIRCLE = 0x20;
    private static final int D2_CROSS = 0x40;
    private static final int D2_SQUARE = 0x80;
    // Button ids for TouchButton: digital2 bits as they are, digital1 bits shifted up.
    private static final int D1_SHIFT = 16;

    private static boolean engineReady;
    private static String engineError;

    private final Handler main = new Handler(Looper.getMainLooper());
    private int digital1;
    private int digital2;
    private int touchDirections;
    private int leftX = 128;
    private int leftY = 128;
    private int rightX = 128;
    private int rightY = 128;
    private boolean surfaceSeen;
    private boolean bootStarted;
    private volatile boolean leaving;
    private String gamePath;
    private TextView statusView;
    private boolean statusHidden;

    static File rootDir(Context context) {
        return new File(context.getFilesDir(), "ps3");
    }

    static boolean firmwareInstalled(Context context) {
        return new File(rootDir(context), "config/dev_flash/vsh/module/vsh.self").isFile();
    }

    // Why this phone cannot run the PS3 engine, or null. It is built for Android 11+ and
    // ARMv8.2 (LSE atomics and RDMA, which ARMv8.0 cores fault on).
    // (0.0.398) Worked out once per process: it reads /proc/cpuinfo, and the console tiles ask
    // on every redraw.
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
        if (Build.VERSION.SDK_INT < 30) {
            return "PS3 모드는 안드로이드 11 이상에서 돼요.";
        }
        if (!new File(context.getApplicationInfo().nativeLibraryDir, "libarmsx3-core.so").isFile()) {
            return "이 버전에는 PS3 엔진이 들어 있지 않아요.";
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("Features") && (!line.contains("atomics") || !line.contains("asimdrdm"))) {
                    return "이 폰의 CPU(ARMv8.0)로는 PS3 엔진을 돌릴 수 없어요.";
                }
            }
        } catch (Exception ignored) {
            // Unreadable: let it try.
        }
        return null;
    }

    // Loads the engine and brings it up against our data folder, once per process.
    private static synchronized boolean ensureEngine(Context context) {
        if (engineReady) {
            return true;
        }
        if (engineError != null) {
            return false;
        }
        String reason = unsupportedReason(context);
        if (reason != null) {
            engineError = reason;
            return false;
        }
        try {
            File core = new File(context.getApplicationInfo().nativeLibraryDir, "libarmsx3-core.so");
            if (!ARMSX3.instance.openLibrary(core.getAbsolutePath())) {
                engineError = "PS3 엔진을 불러오지 못했어요.";
                return false;
            }
            File root = rootDir(context);
            root.mkdirs();
            new File(root, ".nomedia").createNewFile();
            stageOverlayIcons(context, new File(root, "config/Icons"));
            String soc = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null ? Build.SOC_MODEL : "";
            // (1.6.7) The SPU object cache in the app's own cache folder, before initialize(), as
            // ARMSX3's own app does: compiled SPU code is kept between boots there.
            try {
                ARMSX3.instance.setPrivateCacheDir(context.getCacheDir().getAbsolutePath());
            } catch (Throwable ignored) {
                // An engine without it.
            }
            if (!ARMSX3.instance.initialize(root.getAbsolutePath() + "/", "00000001", soc)) {
                engineError = "PS3 엔진을 시작하지 못했어요.";
                return false;
            }
            // Two loops the engine needs a thread each for, for the life of the process: work
            // posted to its "main thread" (save data, state saves) and PPU/SPU compilation.
            startDaemon("ps3-main-thread", () -> ARMSX3.instance.startMainThreadProcessor());
            startDaemon("ps3-compile", () -> ARMSX3.instance.processCompilationQueue());
            applyEngineSettings(context, null);
            try {
                ARMSX3.instance.setGpuTurbo(MainActivity.sharedSettings(context).getBoolean("ps3_gpu_turbo", false));
            } catch (Throwable ignored) {
                // Not an Adreno GPU, or an engine without it.
            }
            engineReady = true;
            return true;
        } catch (Throwable t) {
            engineError = "PS3 엔진 오류: " + t.getMessage();
            return false;
        }
    }

    // Resolution scale and the FPS overlay, kept in our settings and handed to the engine.
    private static final int[] RES_VALUES = {50, 75, 100, 150, 200};
    private static final String[] RES_NAMES = {"0.5배 · 360p", "0.75배 · 540p", "1배 · 720p", "1.5배 · 1080p", "2배 · 1440p"};
    private static final String[] RES_NOTES = {"가장 빠름 · 글이 뭉개짐", "0.5배와 1배 사이 · 글이 좀 더 또렷", "PS3 원래 해상도 (기본)", "더 선명 · 조금 느려짐", "가장 선명 · 많이 느려질 수 있음"};

    private static int resIndex(Context context) {
        int value = MainActivity.sharedSettings(context).getInt("ps3_res", 100);
        for (int k = 0; k < RES_VALUES.length; k++) {
            if (RES_VALUES[k] == value) {
                return k;
            }
        }
        return 2;
    }

    private static boolean perfOverlay(Context context) {
        return MainActivity.sharedSettings(context).getBoolean("ps3_perf", false);
    }

    // "속도 우선", per game: bigger SPU blocks, drawing before shaders are ready, relaxed ZCULL,
    // no MSAA, low shader precision and at most 75% resolution. Off puts RPCS3's defaults back,
    // as the config is shared. (Names and values as in the engine's config.yml.)
    private static boolean speedFirst(Context context, String gamePath) {
        return gamePath != null && MainActivity.sharedSettings(context).getBoolean("ps3_fast:" + gamePath, false);
    }

    // Hands our settings to the engine; the SPU and shader ones take effect at the next boot.
    // Returns the names of the settings the engine refused.
    private static String applyEngineSettings(Context context, String gamePath) {
        boolean fast = speedFirst(context, gamePath);
        int scale = RES_VALUES[resIndex(context)];
        StringBuilder refused = new StringBuilder();
        set(refused, "화질", "Video@@Resolution Scale", String.valueOf(fast ? Math.min(75, scale) : scale));
        set(refused, "성능 표시", "Video@@Performance Overlay@@Enabled", perfOverlay(context) ? "true" : "false");
        // The engine's own values stay unless we changed them for a speed-first game earlier.
        android.content.SharedPreferences prefs = MainActivity.sharedSettings(context);
        if (fast || prefs.getBoolean("ps3_fast_applied", false)) {
            set(refused, "SPU 블록", "Core@@SPU Block Size", fast ? "Mega" : "Safe");
            set(refused, "셰이더", "Video@@Shader Mode", fast ? "Async Recompiler with Shader Interpreter" : "Async Recompiler (multi-threaded)");
            set(refused, "ZCULL", "Video@@Relaxed ZCULL Sync", fast ? "true" : "false");
            // GPU savings: no MSAA, lower shader precision.
            set(refused, "안티에일리어싱", "Video@@MSAA", fast ? "Disabled" : "Auto");
            set(refused, "셰이더 정밀도", "Video@@Shader Precision", fast ? "Low" : "Auto");
            prefs.edit().putBoolean("ps3_fast_applied", fast).commit();
        }
        // SPU threads, per game: 0 is the engine's automatic choice.
        int spu = spuThreads(context, gamePath);
        if (spu != 0 || prefs.getBoolean("ps3_spu_applied", false)) {
            set(refused, "SPU 스레드", "Core@@Preferred SPU Threads", String.valueOf(spu));
            prefs.edit().putBoolean("ps3_spu_applied", spu != 0).commit();
        }
        // (0.0.395) PS3 본체 언어 (games pick their text by it), 확인 버튼, 선명하게 키우기.
        set(refused, "본체 언어", "System@@Language", LANG_VALUES[langIndex(context)]);
        set(refused, "확인 버튼", "System@@Enter button assignment", prefs.getBoolean("ps3_enter_circle", false) ? "Enter with circle" : "Enter with cross");
        set(refused, "선명하게 키우기", "Video@@Output Scaling Mode", UPSCALE_VALUES[upscaleIndex(context)]);
        // (0.0.392) SPU 코드 저장, 화면 비율, 프레임 생성.
        set(refused, "SPU 코드 저장", "Core@@SPU Cache", prefs.getBoolean("ps3_spu_cache", true) ? "true" : "false");
        set(refused, "화면 비율", "Video@@Aspect ratio", aspect43(context) ? "4:3" : "16:9");
        int fg = frameGenIndex(context);
        if (fg != 0 || prefs.getBoolean("ps3_fg_applied", false)) {
            boolean shaders = false;
            try {
                shaders = ARMSX3.instance.frameGenShaderCount() > 0;
            } catch (Throwable ignored) {
                // An engine without frame generation.
            }
            set(refused, "프레임 생성", "Video@@Frame Generation", shaders ? FG_VALUES[fg] : "Off");
            prefs.edit().putBoolean("ps3_fg_applied", fg != 0).commit();
        }
        return refused.toString();
    }

    // ---- 프레임 생성 (0.0.392): Lossless Scaling's frame generation, which the engine has but
    // whose shaders come only from the player's own copy of Lossless Scaling (Lossless.dll):
    // nothing of it is in the app. ----
    static final String[] LANG_VALUES = {"Korean", "English (US)", "Japanese", "English (UK)", "French", "Spanish", "German", "Italian",
            "Chinese (Traditional)", "Chinese (Simplified)", "Portuguese (Brazil)", "Russian"};
    static final String[] LANG_NAMES = {"한국어", "English (US)", "日本語", "English (UK)", "Français", "Español", "Deutsch", "Italiano",
            "中文 (繁體)", "中文 (简体)", "Português (Brasil)", "Русский"};

    static int langIndex(Context context) {
        return Math.max(0, Math.min(LANG_VALUES.length - 1, MainActivity.sharedSettings(context).getInt("ps3_lang", 0)));
    }

    static final String[] UPSCALE_VALUES = {"Bilinear", "FidelityFX Super Resolution", "Snapdragon Game Super Resolution"};
    static final String[] UPSCALE_NAMES = {"꺼짐", "FSR", "SGSR"};

    static int upscaleIndex(Context context) {
        return Math.max(0, Math.min(2, MainActivity.sharedSettings(context).getInt("ps3_upscale", 0)));
    }

    static final String[] FG_VALUES = {"Off", "x2", "x3", "x4"};
    static final String[] FG_NAMES = {"꺼짐", "2배", "3배", "4배"};

    static int frameGenIndex(Context context) {
        return Math.max(0, Math.min(3, MainActivity.sharedSettings(context).getInt("ps3_fg", 0)));
    }

    private static int spuThreads(Context context, String gamePath) {
        return gamePath == null ? 0 : Math.max(0, Math.min(6, MainActivity.sharedSettings(context).getInt("ps3_spu:" + gamePath, 0)));
    }

    // Android's performance hints (API 31+) for the engine's PPU, SPU and RSX threads: they are
    // reported as always running a little late on a 60 Hz frame, so the system keeps their CPU
    // clocks up instead of letting them drop between bursts of work. The thread list is
    // refreshed as the game starts and stops SPU threads.
    private boolean hintsStarted;

    private void startPerformanceHintsOnce() {
        if (!this.hintsStarted) {
            this.hintsStarted = true;
            startPerformanceHints();
        }
    }

    private void startPerformanceHints() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        final android.os.PerformanceHintManager manager = getSystemService(android.os.PerformanceHintManager.class);
        if (manager == null) {
            return;
        }
        new Thread(() -> {
            final long target = 16_666_667L;
            android.os.PerformanceHintManager.Session session = null;
            String known = "";
            long refreshed = 0;
            while (!this.leaving) {
                long now = System.currentTimeMillis();
                if (now - refreshed > 5000) {
                    refreshed = now;
                    int[] tids = engineThreads();
                    String key = java.util.Arrays.toString(tids);
                    if (!key.equals(known)) {
                        known = key;
                        if (session != null) {
                            session.close();
                            session = null;
                        }
                        if (tids.length > 0) {
                            try {
                                session = manager.createHintSession(tids, target);
                            } catch (Throwable ignored) {
                                session = null;
                            }
                        }
                    }
                }
                if (session != null && ARMSX3.instance.getState() == ARMSX3.STATE_RUNNING) {
                    session.reportActualWorkDuration(target * 5 / 4);
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
            if (session != null) {
                session.close();
            }
        }, "ps3-hints").start();
    }

    // Threads of this process named by the engine as PPU, SPU or RSX ones.
    private static int[] engineThreads() {
        File[] tasks = new File("/proc/self/task").listFiles();
        java.util.ArrayList<Integer> found = new java.util.ArrayList<>();
        if (tasks != null) {
            for (File task : tasks) {
                String name = "";
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(new File(task, "comm")))) {
                    String line = reader.readLine();
                    name = line == null ? "" : line.trim();
                } catch (Exception ignored) {
                    continue;
                }
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("PPU") || upper.startsWith("SPU") || upper.startsWith("RSX")) {
                    try {
                        found.add(Integer.parseInt(task.getName()));
                    } catch (NumberFormatException ignored) {
                        // Not a thread id.
                    }
                }
            }
        }
        java.util.Collections.sort(found);
        int[] tids = new int[found.size()];
        for (int k = 0; k < tids.length; k++) {
            tids[k] = found.get(k);
        }
        return tids;
    }

    private static void set(StringBuilder refused, String name, String path, String value) {
        boolean ok;
        try {
            ok = ARMSX3.instance.settingsSet(path, value);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            refused.append(refused.length() == 0 ? "" : ", ").append(name);
        }
    }

    private static void startDaemon(String name, Runnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable ignored) {
                // The loop ended; nothing to do in this process any more.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    // The engine draws its save-data list and dialogs with these (from ARMSX3's assets/Icons).
    private static void stageOverlayIcons(Context context, File dest) {
        if (new File(dest, ".staged").isFile()) {
            return;
        }
        try {
            if (copyAssetTree(context, "ps3/Icons", dest) > 0) {
                new File(dest, ".staged").createNewFile();
            }
        } catch (Exception ignored) {
            // The game runs without them; only those overlays lose their pictures.
        }
    }

    private static int copyAssetTree(Context context, String asset, File dest) throws Exception {
        String[] entries = context.getAssets().list(asset);
        if (entries == null || entries.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
            return 1;
        }
        int count = 0;
        for (String entry : entries) {
            count += copyAssetTree(context, asset + "/" + entry, new File(dest, entry));
        }
        return count;
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Haptics.setLevel(this, MainActivity.sharedSettings(this).getInt("haptic_level", 0));
        if (ACTION_FIRMWARE.equals(getIntent().getStringExtra(EXTRA_ACTION))) {
            showFirmwareInstall(getIntent().getData());
            return;
        }
        this.gamePath = getIntent().getStringExtra(EXTRA_PATH);
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        if (ACTION_PATCHES.equals(action) || ACTION_INSTALL.equals(action)) {
            LinearLayout wait = card();
            wait.addView(text("PS3 엔진을 준비하는 중…", 14, Color.rgb(75, 85, 99), false));
            setContentView(centered(wait));
            new Thread(() -> {
                boolean ok = ensureEngine(this);
                main.post(() -> {
                    if (!ok) {
                        finishWithMessage("PS3 엔진을 시작할 수 없어요", engineError);
                    } else if (ACTION_PATCHES.equals(action)) {
                        showPatches();
                    } else {
                        runInstall(getIntent().getData());
                    }
                });
            }, "ps3-tools").start();
            return;
        }
        this.players = new PlayerPads(MainActivity.sharedSettings(this));
        this.players.setMaxPlayers(MainActivity.sharedSettings(this).getInt("ps3_players", 1));
        this.players.refresh(null);
        if (!ensureEngine(this)) {
            showMessage("PS3 게임을 시작할 수 없어요", engineError, true);
            return;
        }
        buildGameScreen();
    }

    // ---- Firmware ----

    private void showFirmwareInstall(Uri uri) {
        LinearLayout card = card();
        TextView title = text("PS3 펌웨어 설치", 18, Color.rgb(31, 41, 51), true);
        card.addView(title);
        TextView message = text("엔진을 준비하는 중…", 13, Color.rgb(75, 85, 99), false);
        message.setPadding(0, dp(8), 0, 0);
        card.addView(message);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(10));
        barParams.setMargins(0, dp(14), 0, 0);
        card.addView(bar, barParams);
        setContentView(centered(card));
        new Thread(() -> {
            if (!ensureEngine(this)) {
                main.post(() -> finishWithMessage("펌웨어를 설치할 수 없어요", engineError));
                return;
            }
            ProgressRepository.setListener((id, value, max, text) -> main.post(() -> {
                if (max > 0 && value >= 0) {
                    bar.setProgress((int) (1000 * Math.min(1.0, value / (double) max)));
                    message.setText("설치 중… " + (int) (100 * Math.min(1.0, value / (double) max)) + "%");
                }
            }));
            boolean ok = false;
            String failure = "파일을 열 수 없어요.";
            try (ParcelFileDescriptor pfd = uri == null ? null : getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    // The engine takes the descriptor over; hand it a duplicate to own.
                    ok = ARMSX3.instance.installFw(pfd.dup().detachFd(), 1);
                    failure = "PS3UPDAT.PUP가 맞는지 확인해 주세요.";
                }
            } catch (Exception e) {
                failure = e.getMessage();
            }
            ProgressRepository.setListener(null);
            final boolean installed = ok && firmwareInstalled(this);
            final String why = failure;
            main.post(() -> {
                if (installed) {
                    finishWithMessage("펌웨어를 설치했어요", "이제 PS3 게임을 추가해서 할 수 있어요.");
                } else {
                    finishWithMessage("펌웨어 설치에 실패했어요", why);
                }
            });
        }, "ps3-firmware").start();
    }

    // ---- Game screen ----

    private boolean landscape() {
        return MainActivity.sharedSettings(this).getBoolean("ps3_landscape", false);
    }

    // ---- 화면 비율 · 배치 (0.0.392) ----
    private static boolean aspect43(Context context) {
        return MainActivity.sharedSettings(context).getBoolean("ps3_aspect43", false);
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

    private void buildGameScreen() {
        this.layoutEditor = null;
        setRequestedOrientation(landscape() ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        int style = shellStyle();
        int body = Color.rgb(24, 26, 34);
        ClearShellView shell = new ClearShellView(this, ClearShellView.TINT_SMOKE, "MINI EMULATOR · PS3", wide, style, INDIGO, body);
        if (style == ClearShellView.STYLE_PHOTO) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            shell.setPhoto(PhotoBackground.load(PhotoBackground.rendered(this, "ps3", wide), metrics.widthPixels, metrics.heightPixels));
        }
        this.shellView = shell;
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        int labelColor = ClearShellView.darkBackground(style, body) ? Color.rgb(230, 232, 238) : Color.rgb(58, 63, 75);

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        this.statusView = text("PS3 엔진을 시작하는 중…\n처음에는 셰이더와 코드를 준비하느라 오래 걸릴 수 있어요.", 13, Color.rgb(205, 214, 244), false);
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
            int screenW = Math.min(widthDp - 2 * 178, (heightDp - 70) * 16 / 9);
            int screenH = screenW * 9 / 16;
            if (four) {
                screenW = screenH * 4 / 3;
            }
            FrameLayout.LayoutParams screenParams = new FrameLayout.LayoutParams(dp(screenW), dp(screenH), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            screenParams.topMargin = dp(10);
            controls.addView(holder, screenParams);
            this.screenBaseW = dp(screenW);
            this.screenBaseH = dp(screenH);
            buildPadLandscape(controls);
            // (0.0.393) As the other consoles: 저장 · 재시작 top left, 회전 · 메뉴 top right.
            LinearLayout left = new LinearLayout(this);
            left.addView(cornerButton(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "저장", labelColor, v -> onSaveClicked(), v -> {
                pickSlot(true);
                return true;
            }), new LinearLayout.LayoutParams(dp(48), -2));
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
            setContentView(root);
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
            if (k == 0) {
                pill.setOnLongClickListener(v -> {
                    pickSlot(true);
                    return true;
                });
            }
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

        // 16:9 screen, the width of the phone with a small margin (4:3: as tall, narrower).
        int width = getResources().getDisplayMetrics().widthPixels - dp(16);
        int height = width * 9 / 16;
        if (four) {
            width = height * 4 / 3;
        }
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(width, height);
        screenParams.gravity = Gravity.CENTER_HORIZONTAL;
        screenParams.setMargins(0, dp(10), 0, dp(30));
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
        setContentView(root);
        finishScreen(root, pad);
    }

    // Saved per screen direction (the PS3 has one layout of its own, like each console).
    private String layoutKey() {
        boolean wide = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        return "layout:ps3:" + (wide ? "L" : "P") + ":frame";
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
        MainActivity.sharedSettings(this).edit().putString(layoutKey(), sb.toString()).commit();
    }

    private static final String SCREEN_ID = "screen#0";

    // Names the controls just built (kind, button bit, n-th of the same), applies the saved
    // moves, the photo background's button see-through and the turbo marks.
    private void finishScreen(View root, android.view.ViewGroup controls) {
        this.contentRoot = root;
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
        float alpha = shellStyle() == ClearShellView.STYLE_PHOTO ? 1.0f - PhotoBackground.buttonAlpha(this, "ps3") / 100f : 1.0f;
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

    private void startLayoutEditor() {
        if (this.layoutControls.isEmpty() || this.layoutEditor != null || this.contentRoot == null) {
            return;
        }
        ARMSX3.instance.pause();
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
            ARMSX3.instance.resume();
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

    // Landscape: left column L2 L1, D-pad, left stick; right column R1 R2, face buttons, right
    // stick; L3, SELECT, PS, START, R3 along the bottom under the screen.
    private void buildPadLandscape(FrameLayout pad) {
        TouchButton.Listener buttons = this::onButton;
        addAt(pad, new TouchButton(this, "L2", D2_L2, buttons, TouchButton.STYLE_SHOULDER), 12, 68, 74, 38, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "L1", D2_L1, buttons, TouchButton.STYLE_SHOULDER), 92, 68, 74, 38, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "R1", D2_R1, buttons, TouchButton.STYLE_SHOULDER), 92, 68, 74, 38, Gravity.TOP | Gravity.END);
        addAt(pad, new TouchButton(this, "R2", D2_R2, buttons, TouchButton.STYLE_SHOULDER), 12, 68, 74, 38, Gravity.TOP | Gravity.END);
        addAt(pad, new DPadView(this, this::onDirections), 24, 116, 126, 126, Gravity.TOP | Gravity.START);
        int[] bits = {D2_TRIANGLE, D2_CIRCLE, D2_CROSS, D2_SQUARE};
        String[] labels = {"△", "○", "×", "□"};
        int[] colors = {Color.rgb(47, 182, 122), Color.rgb(226, 71, 90), Color.rgb(77, 142, 240), Color.rgb(212, 107, 196)};
        int[][] offsets = {{66, 114}, {20, 158}, {66, 202}, {112, 158}};
        for (int k = 0; k < 4; k++) {
            TouchButton face = new TouchButton(this, labels[k], bits[k], buttons, TouchButton.STYLE_ACTION);
            face.setLabelColor(colors[k]);
            addAt(pad, face, offsets[k][0], offsets[k][1], 46, 46, Gravity.TOP | Gravity.END);
        }
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), 34, 12, 104, 104, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(false, x, y)), 34, 12, 104, 104, Gravity.BOTTOM | Gravity.END);
        int[] xs = {-150, -76, 0, 76, 150};
        String[] names = {"L3", "SELECT", "PS", "START", "R3"};
        int[] ids = {D1_L3, D1_SELECT, D1_PS, D1_START, D1_R3};
        for (int k = 0; k < 5; k++) {
            boolean small = k == 0 || k == 2 || k == 4;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(small ? 40 : 66), dp(small ? 40 : 30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.bottomMargin = dp(small ? 12 : 17);
            TouchButton button = small ? new TouchButton(this, names[k], ids[k] << D1_SHIFT, buttons, TouchButton.STYLE_DARK)
                    : new TouchButton(this, names[k], ids[k] << D1_SHIFT, buttons);
            button.setTranslationX(dp(xs[k]));
            pad.addView(button, params);
        }
    }

    private void buildPad(FrameLayout pad) {
        TouchButton.Listener buttons = this::onButton;
        // Shoulders.
        addAt(pad, new TouchButton(this, "L2", D2_L2, buttons, TouchButton.STYLE_SHOULDER), 12, 6, 72, 40, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "L1", D2_L1, buttons, TouchButton.STYLE_SHOULDER), 90, 6, 72, 40, Gravity.TOP | Gravity.START);
        addAt(pad, new TouchButton(this, "R1", D2_R1, buttons, TouchButton.STYLE_SHOULDER), 90, 6, 72, 40, Gravity.TOP | Gravity.END);
        addAt(pad, new TouchButton(this, "R2", D2_R2, buttons, TouchButton.STYLE_SHOULDER), 12, 6, 72, 40, Gravity.TOP | Gravity.END);
        // D-pad and face buttons.
        addAt(pad, new DPadView(this, this::onDirections), 18, 64, 140, 140, Gravity.TOP | Gravity.START);
        int[] bits = {D2_TRIANGLE, D2_CIRCLE, D2_CROSS, D2_SQUARE};
        String[] labels = {"△", "○", "×", "□"};
        int[] colors = {Color.rgb(47, 182, 122), Color.rgb(226, 71, 90), Color.rgb(77, 142, 240), Color.rgb(212, 107, 196)};
        int[][] offsets = {{62, 64}, {14, 112}, {62, 160}, {110, 112}};
        for (int k = 0; k < 4; k++) {
            TouchButton face = new TouchButton(this, labels[k], bits[k], buttons, TouchButton.STYLE_ACTION);
            face.setLabelColor(colors[k]);
            addAt(pad, face, offsets[k][0], offsets[k][1], 52, 52, Gravity.TOP | Gravity.END);
        }
        // Sticks; a tap on the stick view itself is not L3/R3, those are small buttons beside them.
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), 24, 110, 116, 116, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(false, x, y)), 24, 110, 116, 116, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", D1_L3 << D1_SHIFT, buttons, TouchButton.STYLE_DARK), 146, 146, 40, 40, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", D1_R3 << D1_SHIFT, buttons, TouchButton.STYLE_DARK), 146, 146, 40, 40, Gravity.BOTTOM | Gravity.END);
        // SELECT, PS, START along the middle.
        FrameLayout.LayoutParams select = new FrameLayout.LayoutParams(dp(70), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        TouchButton selectButton = new TouchButton(this, "SELECT", D1_SELECT << D1_SHIFT, buttons);
        selectButton.setTranslationX(-dp(44));
        select.bottomMargin = dp(20);
        pad.addView(selectButton, select);
        FrameLayout.LayoutParams start = new FrameLayout.LayoutParams(dp(70), dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        TouchButton startButton = new TouchButton(this, "START", D1_START << D1_SHIFT, buttons);
        startButton.setTranslationX(dp(44));
        start.bottomMargin = dp(20);
        pad.addView(startButton, start);
        FrameLayout.LayoutParams ps = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ps.bottomMargin = dp(106);
        pad.addView(new TouchButton(this, "PS", D1_PS << D1_SHIFT, buttons, TouchButton.STYLE_DARK), ps);
    }

    private void addAt(FrameLayout parent, View view, int sideDp, int edgeDp, int widthDp, int heightDp, int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp), gravity);
        boolean right = (gravity & Gravity.END) == Gravity.END && (gravity & Gravity.START) != Gravity.START;
        boolean bottom = (gravity & Gravity.BOTTOM) == Gravity.BOTTOM;
        params.setMargins(right ? 0 : dp(sideDp), bottom ? 0 : dp(edgeDp), right ? dp(sideDp) : 0, bottom ? dp(edgeDp) : 0);
        parent.addView(view, params);
    }

    private void onButton(int id, boolean pressed) {
        if (id >= (1 << D1_SHIFT)) {
            int bit = id >>> D1_SHIFT;
            this.digital1 = pressed ? this.digital1 | bit : this.digital1 & ~bit;
        } else {
            this.digital2 = pressed ? this.digital2 | id : this.digital2 & ~id;
        }
        turboTick(pressed);
        pushPad();
    }

    // DPadView reports the libretro bits: up 16, down 32, left 64, right 128.
    private void onDirections(int mask) {
        int d = 0;
        if ((mask & 16) != 0) d |= D1_UP;
        if ((mask & 32) != 0) d |= D1_DOWN;
        if ((mask & 64) != 0) d |= D1_LEFT;
        if ((mask & 128) != 0) d |= D1_RIGHT;
        this.touchDirections = d;
        pushPad();
    }

    private void onStick(boolean left, float x, float y) {
        float sens = stickSensitivity(false);
        x = Math.max(-1f, Math.min(1f, x * sens));
        y = Math.max(-1f, Math.min(1f, y * sens));
        int ax = Math.max(0, Math.min(255, Math.round(128 + x * 127)));
        int ay = Math.max(0, Math.min(255, Math.round(128 + y * 127)));
        if (left) {
            this.leftX = ax;
            this.leftY = ay;
        } else {
            this.rightX = ax;
            this.rightY = ay;
        }
        pushPad();
    }

    private void pushPad() {
        if (!engineReady || this.leaving) {
            return;
        }
        int turbo = this.turboPhase ? turboMask() : 0;
        int turbo1 = turbo >>> D1_SHIFT;
        int turbo2 = turbo & 0xFFFF;
        int players = Math.max(1, this.players.maxPlayers());
        for (int port = 0; port < players; port++) {
            int[] p = this.ports[port];
            int d1 = p[0] | p[2];
            int d2 = p[1] | p[3];
            int lx = p[4], ly = p[5], rx = p[6], ry = p[7];
            if (port == 0) {
                d1 |= this.digital1 | this.touchDirections;
                d2 |= this.digital2;
                if (p[8] == 0) {
                    lx = this.leftX;
                    ly = this.leftY;
                    rx = this.rightX;
                    ry = this.rightY;
                }
            }
            ARMSX3.instance.overlayPadData(port, d1 & ~turbo1, d2 & ~turbo2, lx, ly, rx, ry);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!engineReady) {
            return;
        }
        ARMSX3.instance.surfaceSizeChanged(width, height);
        ARMSX3.instance.surfaceEvent(holder.getSurface(), this.surfaceSeen ? ARMSX3.SURFACE_CHANGED : ARMSX3.SURFACE_CREATED);
        this.surfaceSeen = true;
        if (!this.bootStarted) {
            this.bootStarted = true;
            bootGame();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (engineReady && this.surfaceSeen) {
            ARMSX3.instance.surfaceEvent(holder.getSurface(), ARMSX3.SURFACE_DESTROYED);
            this.surfaceSeen = false;
        }
    }

    private void bootGame() {
        final String path = this.gamePath;
        new Thread(() -> {
            if (path != null) {
                applyEngineSettings(this, path);
            }
            int result = path == null ? 2 : ARMSX3.instance.boot(path);
            if (result != ARMSX3.BOOT_OK && result != ARMSX3.BOOT_ALREADY_ADDED) {
                main.post(() -> showMessage("게임을 시작하지 못했어요", bootError(result), true));
                return;
            }
            main.post(this::startPerformanceHintsOnce);
            // Hide the starting note once the game runs, or after 15 s (the engine shows its own
            // "Compiling shaders" note meanwhile).
            for (int i = 0; i < 600 && !this.leaving; i++) {
                if (ARMSX3.instance.getState() == ARMSX3.STATE_RUNNING || i >= 30) {
                    main.post(() -> {
                        this.statusHidden = true;
                        this.statusView.setVisibility(View.GONE);
                    });
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "ps3-boot").start();
    }

    // ARMSX3 1.6.7's BootResult, by number.
    private static String bootError(int result) {
        switch (result) {
            case 2: return "실행할 게임이 없어요.";
            case 3: return "디스크 위치가 맞지 않아요.";
            case 4: case 5: return "PS3 게임 파일(ISO)이 아니거나 손상됐어요.";
            case 6: return "게임 설치에 실패했어요.";
            case 7: return "이 게임은 복호화 키(.rap 등)가 있어야 실행돼요.";
            case 8: return "게임 파일을 만들지 못했어요 (저장 공간을 확인해 주세요).";
            case 9: return "PS3 펌웨어가 설치되지 않았어요. PS3 화면의 'PS3 펌웨어'에서 설치해 주세요.";
            case 10: return "이 게임은 더 새로운 PS3 펌웨어가 필요해요.";
            case 11: return "지원하지 않는 디스크 형식이에요.";
            case 12: case 13: return "저장한 장면 파일이 손상됐거나 이 엔진 버전과 맞지 않아요.";
            case 14: return "이전 게임이 아직 끝나지 않았어요. 잠시 후 다시 해 주세요.";
            case 16: return "지금은 이 게임을 실행할 수 없어요.";
            case 17: return "엔진의 게임 설정 데이터가 없어요.";
            default: return "PS3 엔진이 오류를 알렸어요 (코드 " + result + ").";
        }
    }

    // ---- Leaving ----

    @Override
    public void onBackPressed() {
        showExitDialog();
    }

    // ---- Pause menu ----

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
            ARMSX3.instance.resume();
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
        ARMSX3.instance.pause();
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

    private void fillBasicTab(LinearLayout body, Choice close, Runnable toPerf) {
        java.util.List<View> tiles = new java.util.ArrayList<>();
        LinearLayout save = menuTile(RoundIconView.SAVE, RoundIconView.COLOR_SAVE, "빠른 저장", "길게: 4칸", v -> {
            closeMenu(true);
            quickSave(1);
        });
        save.setOnLongClickListener(v -> {
            pickSlot(true);
            return true;
        });
        tiles.add(save);
        tiles.add(menuTile(RoundIconView.LOAD, RoundIconView.COLOR_LOAD, "불러오기", slotTime(1), v -> pickSlot(false)));
        tiles.add(menuTile(RoundIconView.RESET, RoundIconView.COLOR_RESET, "재시작", null, v -> {
            closeMenu(true);
            restartGame();
        }));
        tiles.add(menuTile(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, landscape() ? "세로 화면" : "가로 화면", null, v -> {
            closeMenu(false);
            toggleOrientation();
        }));
        tiles.add(menuTile(RoundIconView.FILTER, RoundIconView.COLOR_FILTER, "스크린샷", "화면만", v -> takeScreenshot()));
        tiles.add(menuTile(RoundIconView.PS_HOME, RoundIconView.COLOR_PS_HOME, "PS 홈", "XMB", v -> {
            closeMenu(false);
            ARMSX3.instance.openHomeMenu();
        }));
        int patches = enabledPatchCount();
        tiles.add(menuTile(RoundIconView.PATCH, Color.rgb(124, 58, 237), "게임 패치", patches > 0 ? patches + "개 켜짐" : null, v -> showPatches()));
        tiles.add(menuTile(RoundIconView.EXIT, RoundIconView.COLOR_EXIT, "나가기", null, v -> {
            this.leaving = true;
            closeMenu(true);
            leave();
        }));
        LinearLayout row = null;
        for (int k = 0; k < tiles.size(); k++) {
            if (k % 4 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, k == 0 ? 0 : dp(6), 0, 0);
                body.addView(row, rowParams);
            }
            row.addView(tiles.get(k), tile4Params(k % 4));
        }
        body.addView(menuRow(RoundIconView.LOAD, Color.rgb(202, 138, 4), "디스크 바꾸기", "여러 장짜리", v -> showDiscSwap()), rowParams(10));
        TextView status = text("⚡ 화질 " + RES_NAMES[resIndex(this)].split(" ")[0] + " · 속도 우선 " + onOff(speedFirst(this, this.gamePath))
                + " · SPU " + new String[]{"자동", "1", "2", "3", "4", "5", "6"}[spuThreads(this, this.gamePath)]
                + " · 프레임 생성 " + (frameGenShaders() > 0 ? FG_NAMES[frameGenIndex(this)] : "꺼짐") + "   성능 탭 ›", 12, Color.rgb(17, 94, 89), true);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(roundRect(Color.rgb(240, 253, 250), 13));
        status.setOnClickListener(v -> toPerf.run());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(8), 0, 0);
        body.addView(status, statusParams);
    }

    private int enabledPatchCount() {
        String serial = safe(() -> ARMSX3.instance.getTitleId());
        if (serial.isEmpty()) return 0;
        try {
            org.json.JSONArray list = new org.json.JSONArray(ARMSX3.instance.patchesList(serial));
            int count = 0;
            for (int k = 0; k < list.length(); k++) {
                org.json.JSONObject o = list.optJSONObject(k);
                if (o != null && o.optBoolean("enabled")) count++;
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    // 위쪽 "저장": a quick save into slot 1 (long press: pick the slot), as the other consoles'.
    private void onSaveClicked() {
        if (!engineReady || !this.bootStarted || this.layoutEditor != null) return;
        ARMSX3.instance.pause();
        quickSave(1);
    }

    private void onResetClicked() {
        if (!engineReady || !this.bootStarted || this.layoutEditor != null) return;
        ARMSX3.instance.pause();
        restartGame();
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
        MainActivity.sharedSettings(this).edit().putBoolean("ps3_landscape", !landscape()).commit();
        buildGameScreen();
    }

    // 스크린샷: the game's picture (not the case) into Pictures/Mini Emulator.
    private void takeScreenshot() {
        new Thread(() -> {
            String message;
            File shot = new File(getCacheDir(), "ps3shot.png");
            try {
                shot.delete();
                if (!ARMSX3.instance.saveScreenshot(shot.getAbsolutePath()) || !shot.isFile() || shot.length() == 0) {
                    throw new java.io.IOException("엔진이 화면을 저장하지 못했어요");
                }
                String name = "PS3 " + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".png";
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png");
                values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Mini Emulator");
                Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("사진첩에 저장할 수 없어요");
                try (OutputStream out = getContentResolver().openOutputStream(uri); InputStream in = new java.io.FileInputStream(shot)) {
                    if (out == null) throw new java.io.IOException("사진첩에 저장할 수 없어요");
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                message = "사진첩(Pictures/Mini Emulator)에 저장했어요";
            } catch (Throwable t) {
                message = "스크린샷 실패: " + t.getMessage();
            }
            shot.delete();
            String shown = message;
            main.post(() -> toast(shown));
        }, "ps3-shot").start();
    }

    private void fillPerformanceTab(LinearLayout body, Choice close) {
        boolean fast = speedFirst(this, this.gamePath);
        body.addView(menuRow(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", onOff(fast), v -> {
            showChoices(RoundIconView.BOLT, RoundIconView.COLOR_BOLT, "속도 우선", new String[]{"꺼짐", "켜짐"},
                    new String[]{"엔진 기본 설정 (정확도 우선)",
                            "· SPU 코드를 크게 묶어 번역 (SPU 블록 Mega)\n· 셰이더를 기다리지 않고 먼저 그림 (끊김 감소)\n· 화면 깊이 계산 느슨하게 (ZCULL)\n· 안티에일리어싱 끄고 셰이더 정밀도 낮춤\n· 화질 75%로 낮춤"},
                    fast ? 1 : 0,
                    getIntent().getStringExtra(EXTRA_TITLE) + " · 이 게임에만 적용돼요. 게임을 다시 켤 때 적용돼요. 그림자·효과가 잠깐 늦게 나오거나 어색할 수 있어요. "
                            + "처음 가는 장소는 코드를 만드느라 원래 잠깐씩 끊기고, 두 번째부터 나아져요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_fast:" + this.gamePath, choice == 1).commit();
                String refused = applyEngineSettings(this, this.gamePath);
                if (!refused.isEmpty()) {
                    toast("엔진이 받지 않은 설정: " + refused);
                } else if ((choice == 1) != fast) {
                    toast("재시작하거나 게임을 다시 켜면 적용돼요");
                }
            });
        }), rowParams(0));
        body.addView(menuRow(RoundIconView.MULTI, Color.rgb(13, 148, 136), "프레임 생성 (부드럽게)", frameGenSummary(), v -> {
            showFrameGen();
        }), rowParams(8));
        body.addView(menuRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", RES_NAMES[resIndex(this)], v -> {
            showChoices(RoundIconView.HD, RoundIconView.COLOR_HD, "화질 (해상도)", RES_NAMES, RES_NOTES, resIndex(this),
                    "바로 적용돼요. 모든 PS3 게임에 같이 적용돼요.", choice -> {
                MainActivity.sharedSettings(this).edit().putInt("ps3_res", RES_VALUES[choice]).commit();
                applyEngineSettings(this, this.gamePath);
            });
        }), rowParams(8));
        int up = upscaleIndex(this);
        body.addView(menuRow(RoundIconView.HD, Color.rgb(13, 148, 136), "선명하게 키우기", UPSCALE_NAMES[up], v ->
                showChoices(RoundIconView.HD, Color.rgb(13, 148, 136), "선명하게 키우기", UPSCALE_NAMES,
                        new String[]{"그대로 늘려요 (기본) · 낮은 화질에서 흐림", "AMD FSR · 테두리 · 글자를 또렷하게", "스냅드래곤 SGSR · 퀄컴 폰에 맞춘 방식 · 가볍고 또렷"}, up,
                        "화질(해상도)을 낮췄을 때 화면을 키우는 방법이에요. 모든 PS3 게임에 같이 적용돼요. GPU를 조금 더 써요.", choice -> {
                    MainActivity.sharedSettings(this).edit().putInt("ps3_upscale", choice).commit();
                    String refused = applyEngineSettings(this, this.gamePath);
                    if (!refused.isEmpty()) toast("엔진이 받지 않은 설정: " + refused);
                })), rowParams(8));
        int spu = spuThreads(this, this.gamePath);
        String[] spuNames = {"자동", "1개", "2개", "3개", "4개", "5개", "6개"};
        body.addView(menuRow(RoundIconView.MULTI, RoundIconView.COLOR_FILTER, "SPU 스레드", spuNames[spu], v -> {
            showChoices(RoundIconView.MULTI, RoundIconView.COLOR_FILTER, "SPU 스레드", spuNames,
                    new String[]{"엔진이 정해요 (기본)", "가장 적게 · 게임에 따라 빨라지기도 해요", "적게", "중간", "보통 많이", "많이", "최대 (PS3와 같은 수)"},
                    spu, getIntent().getStringExtra(EXTRA_TITLE) + " · 이 게임에만 적용돼요. 재시작하거나 게임을 다시 켜면 적용돼요. "
                            + "게임마다 맞는 값이 달라서, 바꿔 보고 성능 표시의 FPS로 비교해 보세요.", choice -> {
                MainActivity.sharedSettings(this).edit().putInt("ps3_spu:" + this.gamePath, choice).commit();
                String refused = applyEngineSettings(this, this.gamePath);
                toast(refused.isEmpty() ? "재시작하거나 게임을 다시 켜면 적용돼요" : "엔진이 받지 않은 설정: " + refused);
            });
        }), rowParams(8));
        boolean turbo = MainActivity.sharedSettings(this).getBoolean("ps3_gpu_turbo", false);
        body.addView(menuRow(RoundIconView.BOLT, Color.rgb(234, 88, 12), "GPU 터보", onOff(turbo), v -> {
            showChoices(RoundIconView.BOLT, Color.rgb(234, 88, 12), "GPU 터보", new String[]{"꺼짐", "켜짐"},
                    new String[]{"폰이 GPU 속도를 알아서 (기본)", "GPU를 늘 최고 속도로 · 퀄컴(Adreno) 폰만 · 발열 · 배터리 늘어남"}, turbo ? 1 : 0,
                    "모든 PS3 게임에 같이 적용돼요. 바로 적용돼요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_gpu_turbo", choice == 1).commit();
                try {
                    ARMSX3.instance.setGpuTurbo(choice == 1);
                } catch (Throwable ignored) {
                    toast("이 폰에서는 쓸 수 없어요");
                }
            });
        }), rowParams(8));
        body.addView(menuRow(RoundIconView.PERF, RoundIconView.COLOR_PERF, "FPS · 부하 표시", onOff(perfOverlay(this)), v -> {
            showChoices(RoundIconView.PERF, RoundIconView.COLOR_PERF, "FPS · 부하 표시", new String[]{"꺼짐", "켜짐"},
                    new String[]{"표시 없음 (기본)", "화면 구석에 FPS와 CPU·GPU 사용량"}, perfOverlay(this) ? 1 : 0,
                    "엔진이 게임 화면 위에 직접 그려요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_perf", choice == 1).commit();
                applyEngineSettings(this, this.gamePath);
            });
        }), rowParams(8));
        boolean cache = MainActivity.sharedSettings(this).getBoolean("ps3_spu_cache", true);
        body.addView(menuRow(RoundIconView.SAVE, Color.rgb(5, 150, 105), "SPU 코드 저장", onOff(cache), v -> {
            showChoices(RoundIconView.SAVE, Color.rgb(5, 150, 105), "SPU 코드 저장", new String[]{"꺼짐", "켜짐"},
                    new String[]{"매번 새로 번역 (저장 공간을 안 씀)", "번역한 SPU 코드를 저장 · 두 번째 실행부터 빨리 켜지고 덜 끊김 (기본)"}, cache ? 1 : 0,
                    "모든 PS3 게임에 같이 적용돼요. 게임을 다시 켜면 적용돼요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_spu_cache", choice == 1).commit();
                applyEngineSettings(this, this.gamePath);
            });
        }), rowParams(8));
    }

    private void fillScreenTab(LinearLayout body, Choice close) {
        body.addView(menuRow(RoundIconView.ROTATE, RoundIconView.COLOR_ROTATE, "화면 방향", landscape() ? "가로" : "세로", v -> {
            closeMenu(false);
            toggleOrientation();
        }), rowParams(0));
        boolean four = aspect43(this);
        body.addView(menuRow(RoundIconView.HD, RoundIconView.COLOR_HD, "화면 비율", four ? "4:3" : "16:9", v -> {
            showChoices(RoundIconView.HD, RoundIconView.COLOR_HD, "화면 비율", new String[]{"16:9 (와이드)", "4:3"},
                    new String[]{"PS3를 와이드 TV에 연결한 것처럼 (기본)", "PS3를 4:3 TV에 연결한 것처럼 · 게임이 4:3 화면을 그려요"}, four ? 1 : 0,
                    "모든 PS3 게임에 같이 적용돼요. 게임 화면 틀도 그 비율로 바뀌어요. 게임에 따라 다시 켜야 바뀌어요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_aspect43", choice == 1).commit();
                applyEngineSettings(this, this.gamePath);
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
        boolean photo = PhotoBackground.rendered(this, "ps3", landscape()) != null;
        body.addView(menuRow(RoundIconView.PALETTE, Color.rgb(219, 39, 119), "사진 배경 만들기", photo ? "있음" : "없음", v -> {
            closeMenu(true);
            Intent intent = new Intent(this, BgEditorActivity.class);
            intent.putExtra(BgEditorActivity.EXTRA_MODE, "ps3");
            intent.putExtra(BgEditorActivity.EXTRA_MODE_NAME, "PS3");
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
        int faces = padFaceLayout();
        body.addView(menuRow(RoundIconView.PAD, Color.rgb(29, 78, 216), "패드 버튼", faces == 1 ? "닌텐도식" : "Xbox식", v -> {
            showChoices(RoundIconView.PAD, Color.rgb(29, 78, 216), "패드 버튼 (게임패드)", new String[]{"Xbox식", "닌텐도식"},
                    new String[]{"아래 A = ✕ · 오른쪽 B = ○ · 왼쪽 X = □ · 위 Y = △ (기본)", "아래 B = ✕ · 오른쪽 A = ○ · 왼쪽 Y = □ · 위 X = △"}, faces,
                    "버튼 자리는 PS3 패드와 같게 맞춰요. 연결한 패드 모두에 적용돼요.", choice ->
                    MainActivity.sharedSettings(this).edit().putInt("ps3_pad_face", choice).commit());
        }), rowParams(8));
        int touchSens = stickSensIndex(false);
        int padSens = stickSensIndex(true);
        body.addView(menuRow(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도", "화면 " + Math.round(SENS_VALUES[touchSens] * 100) + "% · 패드 " + Math.round(SENS_VALUES[padSens] * 100) + "%", v -> {
            showChoices(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도 · 적용할 스틱", new String[]{"화면 스틱", "패드 스틱", "둘 다"},
                    new String[]{"지금 " + SENS_NAMES[touchSens], "지금 " + SENS_NAMES[padSens], "화면 · 패드 같이"}, -1,
                    "다음 화면에서 감도를 골라요. 모든 PS3 게임에 같이 적용돼요.", which -> {
                int current = which == 1 ? padSens : touchSens;
                showChoices(RoundIconView.PAD, RoundIconView.COLOR_FILTER, "스틱 감도", SENS_NAMES,
                        new String[]{"조금만 움직여요", "덜 움직여요", "그대로 (기본)", "더 움직여요", "조금만 기울여도 끝까지"}, current,
                        "카메라가 너무 빠르거나 느릴 때 바꿔 보세요.", choice -> {
                    android.content.SharedPreferences.Editor editor = MainActivity.sharedSettings(this).edit();
                    if (which != 1) editor.putInt("ps3_sens_touch", choice);
                    if (which != 0) editor.putInt("ps3_sens_pad", choice);
                    editor.commit();
                });
            });
        }), rowParams(8));
        int turbo = Integer.bitCount(turboMask());
        body.addView(menuRow(RoundIconView.BOLT, Color.rgb(234, 88, 12), "연사", turbo == 0 ? "없음" : turbo + "개 버튼", v -> {
            showTurboSheet();
        }), rowParams(8));
        body.addView(menuRow(RoundIconView.ROTATE, Color.rgb(13, 148, 136), "자이로 (식스액시스)", onOff(gyroOn()), v -> {
            boolean now = gyroOn();
            showChoices(RoundIconView.ROTATE, Color.rgb(13, 148, 136), "자이로 (식스액시스)", new String[]{"꺼짐", "켜짐"},
                    new String[]{"폰을 기울여도 아무 일 없음 (기본)", "폰의 기울기 · 회전을 PS3 패드의 모션 센서로 (1P)"}, now ? 1 : 0,
                    getIntent().getStringExtra(EXTRA_TITLE) + " · 이 게임에만. 폰을 손에 쥔 그대로가 패드를 반듯이 든 상태예요. 기울이기로 조종하는 장면이 있는 게임용이에요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean(gyroKey(), choice == 1).commit();
                if (choice == 1) startMotion(); else stopMotion();
            });
        }), rowParams(8));
        int max = this.players == null ? 1 : this.players.maxPlayers();
        body.addView(menuRow(RoundIconView.MULTI, Color.rgb(79, 70, 229), "멀티 패드 (2P~4P)", max <= 1 ? "1P만" : max + "명", v -> {
            showPlayers();
        }), rowParams(8));
        boolean hide = hidePadWithController();
        body.addView(menuRow(RoundIconView.PAD, RoundIconView.COLOR_MOVE, "패드를 쓰면 화면 버튼 감추기", onOff(hide), v -> {
            showChoices(RoundIconView.PAD, RoundIconView.COLOR_MOVE, "패드를 쓰면 화면 버튼 감추기", new String[]{"꺼짐", "켜짐"},
                    new String[]{"화면 버튼을 늘 보여요", "게임패드 버튼을 누르면 화면 버튼이 사라지고, 화면을 만지면 돌아와요 (기본)"}, hide ? 1 : 0,
                    "게임 화면의 크기 · 자리는 그대로예요.", choice -> {
                MainActivity.sharedSettings(this).edit().putBoolean("ps3_pad_hide", choice == 1).commit();
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

    private void fillSystemTab(LinearLayout body, Choice close) {
        body.addView(menuRow(RoundIconView.LOAD, Color.rgb(71, 85, 105), "디스크 바꾸기", "여러 장짜리 게임", v -> {
            showDiscSwap();
        }), rowParams(0));
        body.addView(menuRow(RoundIconView.DLC, Color.rgb(29, 78, 216), "업데이트 · DLC · 라이선스", "PKG · RAP · EDAT", v -> {
            pickInstallFile();
        }), rowParams(8));
        int lang = langIndex(this);
        body.addView(menuRow(RoundIconView.EDIT, Color.rgb(190, 18, 60), "PS3 본체 언어", LANG_NAMES[lang], v -> {
            String[] notes = new String[LANG_NAMES.length];
            notes[0] = "한국어가 들어 있는 게임(아시아판 · 한국 정발판)이 한글로 · 없는 게임은 보통 영어로 (기본)";
            notes[1] = "엔진 기본값 (0.0.394까지의 설정)";
            notes[2] = "일본판 게임";
            for (int k = 3; k < notes.length; k++) notes[k] = "";
            showChoices(RoundIconView.EDIT, Color.rgb(190, 18, 60), "PS3 본체 언어", LANG_NAMES, notes, lang,
                    "게임은 켤 때 언어를 정해서, 바꾸면 재시작해야 적용돼요. 모든 PS3 게임에 같이 적용돼요. "
                            + "한글패치가 영어 자리를 한글로 바꾼 것이면 English (US)에서 한글이 나와요. 한국어에서 안 나오면 English로도 해 보세요.", choice -> {
                MainActivity.sharedSettings(this).edit().putInt("ps3_lang", choice).commit();
                applyEngineSettings(this, this.gamePath);
                if (choice != lang) toast("재시작하면 적용돼요");
            });
        }), rowParams(8));
        boolean circle = MainActivity.sharedSettings(this).getBoolean("ps3_enter_circle", false);
        body.addView(menuRow(RoundIconView.PAD, Color.rgb(8, 145, 178), "확인 버튼", circle ? "○ (한국 · 일본식)" : "✕ (서양식)", v ->
                showChoices(RoundIconView.PAD, Color.rgb(8, 145, 178), "확인 버튼", new String[]{"✕ 결정 · ○ 취소", "○ 결정 · ✕ 취소"},
                        new String[]{"서양식 (기본)", "예전 한국 · 일본 PS3처럼 · 게임에 따라 따르지 않는 것도 있어요"}, circle ? 1 : 0,
                        "PS3 메뉴와 게임의 \"결정\" 버튼이에요. 재시작하면 적용돼요. 모든 PS3 게임에 같이 적용돼요.", choice -> {
                    MainActivity.sharedSettings(this).edit().putBoolean("ps3_enter_circle", choice == 1).commit();
                    applyEngineSettings(this, this.gamePath);
                    if ((choice == 1) != circle) toast("재시작하면 적용돼요");
                })), rowParams(8));
        body.addView(menuRow(RoundIconView.EXPORT, Color.rgb(71, 85, 105), "엔진 기록 내보내기", "ARMSX3.log", v -> showLogExport()), rowParams(8));
        String version = safe(() -> ARMSX3.instance.getVersion());
        String serial = safe(() -> ARMSX3.instance.getTitleId());
        TextView info = text("엔진: ARMSX3 1.6.7 (RPCS3 휴대폰판)" + (version.isEmpty() ? "" : " · " + version)
                + "\n게임: " + getIntent().getStringExtra(EXTRA_TITLE) + (serial.isEmpty() ? "" : " (" + serial + ")")
                + "\nPS3 펌웨어: " + (firmwareInstalled(this) ? "설치됨" : "없음")
                + "\n프레임 생성 셰이더: " + (frameGenShaders() > 0 ? frameGenShaders() + "개 (가져옴)" : "없음"), 12, Color.rgb(75, 85, 99), false);
        info.setPadding(dp(14), dp(12), dp(14), dp(12));
        info.setBackground(roundRect(Color.rgb(246, 247, 249), 14));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.setMargins(0, dp(8), 0, 0);
        body.addView(info, infoParams);
    }

    // ---- 엔진 기록 내보내기 (0.0.400): the engine's own log (ARMSX3.log and the run before's),
    // zipped into Download/Mini Emulator, to see where a game stops. ----
    private void showLogExport() {
        ARMSX3.instance.pause();
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        panel.addView(text("엔진 기록 내보내기", 17, Color.rgb(31, 41, 51), true));
        final String name = "PS3 기록 " + safeFileName(getIntent().getStringExtra(EXTRA_TITLE)) + " "
                + new java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.ROOT).format(new java.util.Date()) + ".zip";
        TextView about = text("게임을 켠 뒤부터 엔진이 남긴 기록(ARMSX3.log, 바로 전 실행 것 포함)을 zip 하나로\n다운로드/" + MainActivity.DOWNLOAD_FOLDER + "/" + name + "에 저장해요.\n\n"
                + "저장한 뒤 그 파일을 보내 주면 어느 단계에서 무엇을 기다리다 멈췄는지 볼 수 있어요.\n\n"
                + "기록에는 게임 이름 · 폰 기종 · 엔진 설정 · 폴더 경로가 들어 있어요. 개인 사진 · 계정 정보는 없어요.", 13, Color.rgb(75, 85, 99), false);
        about.setPadding(dp(4), dp(10), dp(4), 0);
        panel.addView(about);
        TextView save = pill("저장하기", Color.rgb(15, 118, 110), Color.WHITE);
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            save.setText("저장하는 중…");
            new Thread(() -> {
                String message = exportLogs(name);
                main.post(() -> {
                    dialog.dismiss();
                    new android.app.AlertDialog.Builder(this).setTitle("엔진 기록").setMessage(message).setPositiveButton("확인", null).show();
                });
            }, "ps3-log").start();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.setMargins(0, dp(14), 0, 0);
        panel.addView(save, saveParams);
        TextView close = pill("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(8), 0, 0);
        panel.addView(close, closeParams);
        dialog.setContentView(scrollable(panel));
        dialog.setOnDismissListener(d -> resumeGame());
        sizeDialog(dialog);
        dialog.show();
    }

    private static String safeFileName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static void findLogs(File dir, int depth, java.util.List<File> out) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (depth > 0 && !n.equals("dev_hdd0") && !n.equals("dev_flash") && !n.equals("lsfg")) findLogs(f, depth - 1, out);
            } else {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if ((n.startsWith("armsx3") || n.equals("tty.log") || n.equals("spu.log")) && n.endsWith(".log")) out.add(f);
            }
        }
    }

    // The logs into one zip in Download/Mini Emulator; a message for the player.
    private String exportLogs(String name) {
        java.util.List<File> logs = new java.util.ArrayList<>();
        findLogs(rootDir(this), 3, logs);
        findLogs(getCacheDir(), 2, logs);
        findLogs(getFilesDir(), 1, logs);
        java.util.Set<String> seen = new java.util.HashSet<>();
        Uri uri = null;
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER);
            uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("다운로드 폴더에 파일을 만들 수 없어요");
            int count = 0;
            try (OutputStream raw = getContentResolver().openOutputStream(uri);
                 java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw)) {
                // What the app set, first: the engine's version, the phone, our settings.
                StringBuilder info = new StringBuilder();
                info.append("Mini Emulator PS3 기록\n게임: ").append(getIntent().getStringExtra(EXTRA_TITLE))
                        .append("\n게임 번호: ").append(safe(() -> ARMSX3.instance.getTitleId()))
                        .append("\n경로: ").append(this.gamePath)
                        .append("\n엔진: ").append(safe(() -> ARMSX3.instance.getVersion()))
                        .append("\n폰: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(Build.VERSION.SDK_INT >= 31 ? " · " + Build.SOC_MODEL : "").append(" · Android ").append(Build.VERSION.RELEASE)
                        .append("\n엔진 상태: ").append(safe(() -> String.valueOf(ARMSX3.instance.getState())))
                        .append("\n설정: 화질 ").append(RES_VALUES[resIndex(this)]).append("% · 속도 우선 ").append(speedFirst(this, this.gamePath))
                        .append(" · SPU ").append(spuThreads(this, this.gamePath)).append(" · 선명하게 ").append(UPSCALE_VALUES[upscaleIndex(this)])
                        .append(" · 언어 ").append(LANG_VALUES[langIndex(this)]).append(" · 프레임 생성 ").append(FG_VALUES[frameGenIndex(this)])
                        .append(" · GPU 터보 ").append(MainActivity.sharedSettings(this).getBoolean("ps3_gpu_turbo", false)).append('\n');
                String error = safe(() -> ARMSX3.instance.takeGameError());
                if (!error.isEmpty()) info.append("게임 오류: ").append(error).append('\n');
                zip.putNextEntry(new java.util.zip.ZipEntry("정보.txt"));
                zip.write(info.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
                byte[] buffer = new byte[1 << 16];
                for (File log : logs) {
                    String entry = log.getName();
                    if (!seen.add(entry)) entry = log.getParentFile().getName() + "_" + entry;
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry));
                    try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(log, "r")) {
                        // A very long log: its last 30 MB (where it stopped).
                        long start = Math.max(0, in.length() - (30L << 20));
                        in.seek(start);
                        int n;
                        while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                    count++;
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues done = new android.content.ContentValues();
                done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            }
            return count == 0 ? "엔진 기록 파일을 찾지 못했어요. 정보만 담아 저장했어요:\n다운로드/" + MainActivity.DOWNLOAD_FOLDER + "/" + name
                    : "기록 " + count + "개를 저장했어요:\n다운로드/" + MainActivity.DOWNLOAD_FOLDER + "/" + name + "\n\n이 파일을 보내 주세요.";
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            return "저장하지 못했어요: " + t.getMessage();
        }
    }

    // ---- 프레임 생성 ----
    private static final int REQUEST_BG_EDITOR = 32;
    private static final int REQUEST_FRAMEGEN_DLL = 33;
    private static final int REQUEST_INSTALL_FILE = 34;

    private static int frameGenShaders() {
        try {
            return ARMSX3.instance.frameGenShaderCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String frameGenSummary() {
        if (frameGenShaders() <= 0) return "셰이더 필요";
        return FG_NAMES[frameGenIndex(this)];
    }

    private void showFrameGen() {
        if (frameGenShaders() <= 0) {
            ARMSX3.instance.pause();
            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel = card();
            panel.addView(text("프레임 생성 (부드럽게)", 17, Color.rgb(31, 41, 51), true));
            TextView about = text("게임이 그린 두 장면 사이에 중간 장면을 만들어 끼워서 2~4배로 부드럽게 보여 줘요 (Lossless Scaling의 프레임 생성).\n\n"
                    + "이 기능의 셰이더는 Lossless Scaling(PC 프로그램, 유료)의 것이라 앱에 넣을 수 없어요. 직접 산 Lossless Scaling의 설치 폴더에 있는 "
                    + "Lossless.dll 파일을 폰에 옮긴 뒤 아래에서 골라 주세요. 엔진이 그 안의 셰이더만 꺼내 써요.\n\n"
                    + "주의: 게임 속도가 빨라지지는 않아요 (보이는 것만 부드러워짐). 조작이 아주 조금 늦게 느껴질 수 있고, GPU를 더 써서 발열이 늘어요.", 13, Color.rgb(75, 85, 99), false);
            about.setPadding(dp(4), dp(10), dp(4), 0);
            panel.addView(about);
            TextView pick = pill("Lossless.dll 고르기", INDIGO, Color.WHITE);
            pick.setOnClickListener(v -> {
                dialog.dismiss();
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, REQUEST_FRAMEGEN_DLL);
                } catch (Exception e) {
                    toast("파일을 고르는 화면을 열 수 없어요");
                }
            });
            LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(-1, dp(48));
            pickParams.setMargins(0, dp(14), 0, 0);
            panel.addView(pick, pickParams);
            TextView close = pill("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
            close.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
            closeParams.setMargins(0, dp(8), 0, 0);
            panel.addView(close, closeParams);
            dialog.setContentView(scrollable(panel));
            dialog.setOnDismissListener(d -> {
                resumeGame();
            });
            sizeDialog(dialog);
            dialog.show();
            return;
        }
        showChoices(RoundIconView.MULTI, Color.rgb(13, 148, 136), "프레임 생성 (부드럽게)", FG_NAMES,
                new String[]{"게임이 그린 장면만 (기본)", "중간 장면 1장씩 · 30fps 게임이 60fps처럼", "중간 장면 2장씩", "중간 장면 3장씩 · GPU를 가장 많이 씀"},
                frameGenIndex(this), "모든 PS3 게임에 같이 적용돼요. 바로 적용돼요. 게임 속도는 그대로이고, 조작이 아주 조금 늦게 느껴질 수 있어요. "
                        + "셰이더: Lossless.dll에서 " + frameGenShaders() + "개", choice -> {
            MainActivity.sharedSettings(this).edit().putInt("ps3_fg", choice).commit();
            String refused = applyEngineSettings(this, this.gamePath);
            if (!refused.isEmpty()) toast("엔진이 받지 않은 설정: " + refused);
        });
    }

    private void importFrameGen(Uri uri) {
        toast("셰이더를 꺼내는 중…");
        new Thread(() -> {
            String message;
            File dll = new File(rootDir(this), "lsfg/Lossless.dll");
            try {
                dll.getParentFile().mkdirs();
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dll)) {
                    if (in == null) throw new java.io.IOException();
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                int count = ARMSX3.instance.frameGenImportShaders(dll.getAbsolutePath());
                if (count > 0) {
                    message = "셰이더 " + count + "개를 가져왔어요. 메뉴에서 2배 · 3배 · 4배를 고르세요.";
                } else {
                    String why = safe(() -> ARMSX3.instance.frameGenShaderError());
                    message = "Lossless.dll에서 셰이더를 찾지 못했어요" + (why.isEmpty() ? "" : " (" + why + ")");
                }
            } catch (Throwable t) {
                message = "파일을 읽지 못했어요";
            }
            final String shown = message;
            main.post(() -> {
                new android.app.AlertDialog.Builder(this).setTitle("프레임 생성").setMessage(shown).setPositiveButton("확인", null).show();
            });
        }, "ps3-framegen").start();
    }

    // ---- 디스크 바꾸기 (0.0.392): another .iso of the PS3 list, put in while the game runs. ----
    private void showDiscSwap() {
        File dir = this.gamePath == null ? null : new File(this.gamePath).getParentFile();
        final java.util.List<File> discs = new java.util.ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".iso") && !f.getAbsolutePath().equals(this.gamePath)) {
                    discs.add(f);
                }
            }
        }
        java.util.Collections.sort(discs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        if (discs.isEmpty()) {
            showMessage("디스크 바꾸기", "PS3 목록에 다른 디스크(.iso)가 없어요. 2번 디스크도 PS3 모드에 추가한 뒤 해 주세요.", false);
            return;
        }
        String[] names = new String[discs.size()];
        String[] notes = new String[discs.size()];
        for (int k = 0; k < names.length; k++) {
            names[k] = discs.get(k).getName();
            notes[k] = (discs.get(k).length() >> 20) + " MB";
        }
        showChoices(RoundIconView.LOAD, Color.rgb(71, 85, 105), "디스크 바꾸기", names, notes, -1,
                "게임이 \"디스크를 넣어 주세요\"라고 할 때 고르세요. 게임이 하던 디스크를 놓아야 바뀌므로 몇 초 걸릴 수 있어요.", choice -> {
            final String path = discs.get(choice).getAbsolutePath();
            toast("디스크를 바꾸는 중…");
            new Thread(() -> {
                int result;
                try {
                    result = ARMSX3.instance.changeDisc(path);
                } catch (Throwable t) {
                    result = -1;
                }
                final int code = result;
                main.post(() -> toast(code == 0 ? "디스크를 바꿨어요" : "디스크를 바꾸지 못했어요 (코드 " + code + ")"));
            }, "ps3-disc").start();
        });
    }

    // ---- 업데이트 · DLC · 라이선스 (0.0.392): a .pkg installed by the engine (game update,
    // DLC, PSN game), a .rap licence into the user's exdata (named by the content id the game
    // asks for), an .edat licence by the engine. ----
    private void pickInstallFile() {
        ARMSX3.instance.pause();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_INSTALL_FILE);
        } catch (Exception e) {
            toast("파일을 고르는 화면을 열 수 없어요");
            resumeGame();
        }
    }

    static String displayName(Context context, Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // The path's last part will do.
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    // Installs one file; a message for the player. Called off the main thread.
    static String installFile(Context context, Uri uri, String gamePath, File romDir, ProgressRepository.Listener progress) {
        String name = displayName(context, uri);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        try {
            if (lower.endsWith(".rap")) {
                return installRap(context, uri, name, gamePath);
            }
            ProgressRepository.setListener(progress);
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) return "파일을 열 수 없어요";
                boolean ok;
                if (lower.endsWith(".edat")) {
                    ok = ARMSX3.instance.installKey(pfd.dup().detachFd(), 2, "");
                    return ok ? "라이선스(" + name + ")를 넣었어요" : "라이선스를 넣지 못했어요";
                }
                org.json.JSONObject info = null;
                // Its own descriptor: a duplicate would share the read position with the install's.
                try (ParcelFileDescriptor probe = context.getContentResolver().openFileDescriptor(uri, "r")) {
                    String raw = probe == null ? null : ARMSX3.instance.probePkgInfo(probe.detachFd());
                    info = raw == null ? null : new org.json.JSONObject(raw);
                } catch (Throwable ignored) {
                    // Installed all the same.
                }
                ok = ARMSX3.instance.install(pfd.dup().detachFd(), 2);
                if (!ok) {
                    return name + "을(를) 설치하지 못했어요 (PS3용 PKG가 맞는지, 저장 공간이 넉넉한지 확인해 주세요)";
                }
                String category = info == null ? "" : info.optString("category").trim().toUpperCase(java.util.Locale.ROOT);
                String id = info == null ? "" : info.optString("titleId").trim().toUpperCase(java.util.Locale.ROOT);
                String title = info == null ? "" : info.optString("title").trim();
                if ("HG".equals(category) && !id.isEmpty() && romDir != null) {
                    // A PSN game: an entry in the PS3 list that starts it from the hard disk.
                    File game = new File(rootDir(context), "config/dev_hdd0/game/" + id);
                    String base = (title.isEmpty() ? id : title).replaceAll("[\\/:*?\"<>|]", "_").trim();
                    File link = new File(romDir, base + ".ps3game");
                    try (OutputStream out = new FileOutputStream(link)) {
                        out.write(game.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    return (title.isEmpty() ? name : title) + " 설치를 마쳤어요. PS3 목록에 생겼어요.";
                }
                if ("GD".equals(category)) {
                    return (title.isEmpty() ? name : title) + " 업데이트를 설치했어요. 게임을 다시 켜면 적용돼요.";
                }
                return (title.isEmpty() ? name : title) + " 설치를 마쳤어요" + ("AC".equals(category) ? " (DLC)" : "") + ". 게임을 다시 켜면 적용돼요.";
            } finally {
                ProgressRepository.setListener(null);
            }
        } catch (Throwable t) {
            return "설치하지 못했어요: " + t.getMessage();
        }
    }

    private static String installRap(Context context, Uri uri, String name, String gamePath) throws java.io.IOException {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("파일을 열 수 없어요");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0 && out.size() < (1 << 20)) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        if (bytes.length < 0x10) return "라이선스 파일(.rap)이 아니에요";
        // The game's own content id beats the file's name, which may have been changed.
        String id = "";
        if (gamePath != null) {
            try {
                id = ARMSX3.instance.gameContentId(gamePath);
            } catch (Throwable ignored) {
                id = "";
            }
        }
        if (id == null || id.isEmpty()) {
            id = name.substring(0, name.toLowerCase(java.util.Locale.ROOT).lastIndexOf(".rap"));
        }
        String user = "00000001";
        try {
            String u = ARMSX3.instance.getUser();
            if (u != null && !u.trim().isEmpty()) user = u.trim();
        } catch (Throwable ignored) {
            // The default user.
        }
        File dir = new File(rootDir(context), "config/dev_hdd0/home/" + user + "/exdata");
        if (!dir.isDirectory() && !dir.mkdirs()) return "라이선스 폴더를 만들지 못했어요";
        try (OutputStream out = new FileOutputStream(new File(dir, id + ".rap"))) {
            out.write(bytes);
        }
        return "라이선스를 넣었어요 (" + id + ")";
    }

    private void runInstall(Uri uri) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (uri != null) uris.add(uri);
        android.content.ClipData clip = getIntent().getClipData();
        if (!this.bootStarted && clip != null) {
            uris.clear();
            for (int k = 0; k < clip.getItemCount(); k++) {
                if (clip.getItemAt(k).getUri() != null) uris.add(clip.getItemAt(k).getUri());
            }
        }
        final String[] cached = this.bootStarted ? null : getIntent().getStringArrayExtra(EXTRA_INSTALL_PATHS);
        if (cached != null) {
            for (String path : cached) uris.add(Uri.fromFile(new File(path)));
        }
        if (uris.isEmpty()) {
            finishWithMessage("설치할 파일이 없어요", "");
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        LinearLayout panel = card();
        panel.addView(text("설치 중", 17, Color.rgb(31, 41, 51), true));
        final TextView message = text(displayName(this, uris.get(0)), 13, Color.rgb(75, 85, 99), false);
        message.setPadding(0, dp(8), 0, 0);
        panel.addView(message);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(10));
        barParams.setMargins(0, dp(14), 0, 0);
        panel.addView(bar, barParams);
        dialog.setContentView(panel);
        sizeDialog(dialog);
        dialog.show();
        String romDirPath = getIntent().getStringExtra(EXTRA_ROM_DIR);
        final File romDir = romDirPath != null ? new File(romDirPath) : this.gamePath != null ? new File(this.gamePath).getParentFile() : null;
        new Thread(() -> {
            StringBuilder results = new StringBuilder();
            for (int k = 0; k < uris.size(); k++) {
                final Uri one = uris.get(k);
                final String label = (uris.size() > 1 ? (k + 1) + "/" + uris.size() + " · " : "") + displayName(this, one);
                main.post(() -> {
                    message.setText(label);
                    bar.setProgress(0);
                });
                String result = installFile(this, one, this.gamePath, romDir, (id, value, max, text) -> main.post(() -> {
                    if (max > 0 && value >= 0) {
                        bar.setProgress((int) (1000 * Math.min(1.0, value / (double) max)));
                    }
                }));
                if (results.length() > 0) results.append("\n\n");
                results.append(result);
            }
            if (cached != null) {
                for (String path : cached) {
                    File f = new File(path);
                    if (f.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath())) f.delete();
                }
            }
            main.post(() -> {
                dialog.dismiss();
                new android.app.AlertDialog.Builder(this).setTitle("업데이트 · DLC · 라이선스").setMessage(results.toString()).setPositiveButton("확인", null)
                        .setOnDismissListener(d -> {
                            if (!this.bootStarted) {
                                leave();
                            } else {
                                resumeGame();
                            }
                        }).show();
            });
        }, "ps3-install").start();
    }

    // ---- 멀티 패드 (0.0.392): how many players, and which controller is whose. ----
    private void showPlayers() {
        final java.util.List<android.view.InputDevice> pads = PlayerPads.controllers();
        String[] names = {"1P만", "2P까지", "3P까지", "4P까지"};
        StringBuilder now = new StringBuilder();
        if (pads.isEmpty()) {
            now.append("지금 연결된 게임패드가 없어요.");
        } else {
            for (android.view.InputDevice device : pads) {
                if (now.length() > 0) now.append("\n");
                now.append(this.players.playerOf(device) + 1).append("P: ").append(device.getName());
            }
        }
        showChoices(RoundIconView.MULTI, Color.rgb(79, 70, 229), "멀티 패드 (2P~4P)", names,
                new String[]{"모든 패드가 1P (기본)", "패드를 연결한 순서대로 1P · 2P", "1P · 2P · 3P", "1P · 2P · 3P · 4P"},
                this.players.maxPlayers() - 1,
                now + "\n\n화면 버튼은 늘 1P예요. 패드를 연결한 순서가 P 순서가 되고, 다음에도 기억해요. 블루투스 · USB 패드 모두 돼요.", choice -> {
            MainActivity.sharedSettings(this).edit().putInt("ps3_players", choice + 1).commit();
            this.players.setMaxPlayers(choice + 1);
            this.players.refresh(null);
            this.knownPads.clear();
            for (int[] p : this.ports) {
                p[0] = p[1] = p[2] = p[3] = 0;
            }
            pushPad();
        });
    }

    // (0.0.391) Four slots, as the engine numbers them (1-4).
    private String slotTime(int slot) {
        long saved = MainActivity.sharedSettings(this).getLong("ps3_state_time" + (slot == 1 ? "" : slot) + ":" + this.gamePath, 0);
        return saved == 0 ? "비어 있음" : new java.text.SimpleDateFormat("M/d a h:mm", java.util.Locale.KOREA).format(new java.util.Date(saved));
    }

    private void pickSlot(boolean save) {
        String[] names = new String[4];
        String[] notes = new String[4];
        for (int k = 0; k < 4; k++) {
            names[k] = (k + 1) + "번 칸";
            notes[k] = slotTime(k + 1);
        }
        showChoices(save ? RoundIconView.SAVE : RoundIconView.LOAD, save ? RoundIconView.COLOR_SAVE : RoundIconView.COLOR_LOAD,
                save ? "빠른 저장 · 칸 고르기" : "불러오기 · 칸 고르기", names, notes, -1,
                "저장은 엔진이 게임을 잠깐 멈췄다가 다시 이어 가요(시험 기능). 큰 게임은 1분 넘게 걸릴 수 있어요.", choice -> {
                    closeMenu(true);
                    if (save) {
                        quickSave(choice + 1);
                    } else {
                        quickLoad(choice + 1);
                    }
                });
    }

    private void quickSave(int slot) {
        toast("저장 중… 잠깐 멈췄다가 이어져요");
        this.stateBusy = true;
        new Thread(() -> {
            boolean ok = ARMSX3.instance.saveStateToSlot(slot);
            if (ok) {
                MainActivity.sharedSettings(this).edit().putLong("ps3_state_time" + (slot == 1 ? "" : slot) + ":" + this.gamePath, System.currentTimeMillis()).commit();
            }
            main.post(() -> {
                this.stateBusy = false;
                resumeGame();
                toast(ok ? "지금 장면을 저장했어요" : "이 게임은 빠른 저장이 안 돼요");
            });
        }, "ps3-save").start();
    }

    private void quickLoad(int slot) {
        this.stateBusy = true;
        new Thread(() -> {
            boolean has = ARMSX3.instance.hasStateInSlot(slot);
            boolean ok = has && ARMSX3.instance.loadStateFromSlot(slot);
            main.post(() -> {
                this.stateBusy = false;
                if (!ok) {
                    resumeGame();
                }
                toast(!has ? "저장한 장면이 없어요" : ok ? "저장한 장면을 불러오는 중…" : "불러오지 못했어요");
            });
        }, "ps3-load").start();
    }

    private void restartGame() {
        toast("게임을 다시 시작해요");
        new Thread(() -> {
            ARMSX3.instance.kill();
            for (int i = 0; i < 80 && ARMSX3.instance.getState() != ARMSX3.STATE_STOPPED; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
            main.post(() -> {
                this.statusHidden = false;
                if (this.statusView != null) {
                    this.statusView.setVisibility(View.VISIBLE);
                }
                bootGame();
            });
        }, "ps3-restart").start();
    }

    // ---- 게임 패치 (0.0.391): RPCS3's patch.yml through the engine's patch_engine, as ARMSX3's
    // own app does (Ps3PatchRepo.kt): the list for the running game's serial as JSON
    // [{hash, name, author, notes, version, appVersion, game, enabled}], one switched with
    // patchSetEnabled(hash, name, serial, appVersion, on); the database downloaded from
    // rpcs3.net (a JSON envelope whose YAML is checked against its SHA-256) into patches/patch.yml,
    // or a file of the player's own merged into imported_patch.yml. A change takes effect at the
    // next boot. ----
    private static final int REQUEST_IMPORT_PATCH = 31;

    private void showPatches() {
        if (this.bootStarted) ARMSX3.instance.pause();
        String running = this.bootStarted ? safe(() -> ARMSX3.instance.getTitleId()) : "";
        final String serial = !running.isEmpty() ? running : this.gamePath == null ? "" : Ps3Disc.titleId(new File(this.gamePath));
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        panel.addView(text("게임 패치", 17, Color.rgb(31, 41, 51), true));
        panel.addView(text(getIntent().getStringExtra(EXTRA_TITLE) + (serial.isEmpty() ? "" : " · " + serial), 12, Color.rgb(122, 130, 140), false));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list, new LinearLayout.LayoutParams(-1, -2));
        final Runnable[] fill = new Runnable[1];
        final boolean[] changed = {false};
        fill[0] = () -> {
            list.removeAllViews();
            org.json.JSONArray patches;
            try {
                patches = new org.json.JSONArray(ARMSX3.instance.patchesList(serial));
            } catch (Throwable t) {
                patches = new org.json.JSONArray();
            }
            if (serial.isEmpty() || patches.length() == 0) {
                TextView none = text(serial.isEmpty() ? "게임이 시작된 뒤에 이 게임의 패치를 볼 수 있어요."
                        : "이 게임용 패치가 없어요. 아래 \"최신 목록 받기\"로 패치 목록을 받아 보세요.", 13, Color.rgb(75, 85, 99), false);
                none.setPadding(dp(4), dp(12), dp(4), dp(4));
                list.addView(none);
                return;
            }
            for (int k = 0; k < patches.length(); k++) {
                final org.json.JSONObject o = patches.optJSONObject(k);
                if (o == null) continue;
                final boolean on = o.optBoolean("enabled");
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(9), dp(12), dp(9));
                row.setBackground(roundRect(on ? Color.argb(26, 124, 58, 237) : Color.rgb(246, 247, 249), 14));
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(text(o.optString("name"), 14, Color.rgb(31, 41, 51), true));
                StringBuilder sub = new StringBuilder();
                if (!o.optString("author").isEmpty()) sub.append("작성: ").append(o.optString("author"));
                if (!o.optString("version").isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append("v").append(o.optString("version"));
                if (!o.optString("appVersion").isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append("게임 버전 ").append(o.optString("appVersion"));
                if (!o.optString("notes").isEmpty()) sub.append("\n").append(o.optString("notes"));
                if (sub.length() > 0) texts.addView(text(sub.toString(), 11.5f, Color.rgb(107, 114, 128), false));
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView state = text(on ? "켜짐" : "꺼짐", 13, on ? Color.rgb(124, 58, 237) : Color.rgb(148, 163, 184), true);
                row.addView(state);
                row.setOnClickListener(v -> {
                    boolean ok = false;
                    try {
                        ok = ARMSX3.instance.patchSetEnabled(o.optString("hash"), o.optString("name"), serial, o.optString("appVersion", "All"), !on);
                    } catch (Throwable ignored) {
                    }
                    if (!ok) {
                        toast("바꾸지 못했어요");
                    }
                    changed[0] = true;
                    fill[0].run();
                });
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
                rowParams.setMargins(0, dp(8), 0, 0);
                list.addView(row, rowParams);
            }
        };
        fill[0].run();
        LinearLayout buttons = new LinearLayout(this);
        TextView download = pill("최신 목록 받기", Color.rgb(238, 242, 255), Color.rgb(29, 78, 216));
        download.setOnClickListener(v -> {
            toast("패치 목록을 받는 중…");
            new Thread(() -> {
                String result = downloadPatchDatabase();
                main.post(() -> {
                    toast(result);
                    fill[0].run();
                });
            }, "ps3-patches").start();
        });
        buttons.addView(download, new LinearLayout.LayoutParams(0, dp(46), 1f));
        TextView importFile = pill("patch.yml 가져오기", Color.rgb(238, 242, 255), Color.rgb(29, 78, 216));
        importFile.setOnClickListener(v -> {
            this.patchSheet = fill[0];
            this.pickingPatchFile = true;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_IMPORT_PATCH);
            } catch (Exception e) {
                toast("파일을 고르는 화면을 열 수 없어요");
            }
        });
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        importParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(importFile, importParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        TextView note = text("켜고 끈 패치는 게임을 다시 시작하면 적용돼요 (일시정지 › 재시작). "
                + "패치는 PS3 에뮬 커뮤니티가 모은 공개 목록(rpcs3.net)이에요. 60fps 해제 패치는 게임에 따라 게임 속도가 빨라지거나 느려질 수 있어요.", 12, Color.rgb(75, 85, 99), false);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        panel.addView(note);
        TextView close = pill("닫기", Color.rgb(241, 242, 244), Color.rgb(75, 85, 99));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(48));
        closeParams.setMargins(0, dp(10), 0, 0);
        panel.addView(close, closeParams);
        dialog.setContentView(scrollable(panel));
        dialog.setOnDismissListener(d -> {
            this.patchSheet = null;
            if (!this.bootStarted) {
                // Opened from the game list: back to it.
                if (!this.pickingPatchFile) leave();
                return;
            }
            if (!this.leaving) {
                resumeGame();
                if (changed[0]) toast("재시작하면 패치가 적용돼요");
            }
        });
        sizeDialog(dialog);
        dialog.show();
    }

    private Runnable patchSheet;
    private boolean pickingPatchFile;

    private interface Text {
        String get();
    }

    private static String safe(Text text) {
        try {
            String value = text.get();
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    // The patch database from rpcs3.net, as ARMSX3's own app takes it. A message for the player.
    private static String downloadPatchDatabase() {
        String version = safe(() -> ARMSX3.instance.patchEngineVersion());
        if (version.isEmpty()) return "엔진이 패치 형식을 알려 주지 않았어요";
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL("https://rpcs3.net/compatibility?patch&api=v1&v=" + version).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "MiniEmulator");
            if (connection.getResponseCode() != 200) return "받지 못했어요 (서버 " + connection.getResponseCode() + ")";
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
            }
            org.json.JSONObject envelope = new org.json.JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            if (envelope.optInt("return_code", -1) != 0) return "받지 못했어요 (서버 응답 " + envelope.optInt("return_code", -1) + ")";
            if (!version.equals(envelope.optString("version"))) return "서버의 패치 형식이 엔진과 달라요";
            String yaml = envelope.optString("patch");
            if (yaml.isEmpty()) return "받은 목록이 비어 있어요";
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
            if (!hex.toString().equalsIgnoreCase(envelope.optString("sha256"))) return "받은 목록의 검사값이 맞지 않아 쓰지 않았어요";
            int count = ARMSX3.instance.patchesWrite("patch.yml", yaml);
            return count >= 0 ? "패치 목록을 받았어요 (" + count + "개 게임)" : "받은 목록을 엔진이 읽지 못했어요";
        } catch (Throwable t) {
            return "받지 못했어요 (인터넷 연결을 확인해 주세요)";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BG_EDITOR) {
            buildGameScreen();
            resumeGame();
            return;
        }
        if (requestCode == REQUEST_FRAMEGEN_DLL) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importFrameGen(data.getData());
            return;
        }
        if (requestCode == REQUEST_INSTALL_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                runInstall(data.getData());
            } else {
                resumeGame();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT_PATCH) {
            this.pickingPatchFile = false;
        }
        if (requestCode != REQUEST_IMPORT_PATCH || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            String message;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                while (in != null && (n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
                int count = ARMSX3.instance.patchesImport(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                message = count >= 0 ? "패치를 가져왔어요 (" + count + "개)" : "patch.yml 형식이 아니에요";
            } catch (Throwable t) {
                message = "파일을 읽지 못했어요";
            }
            final String shown = message;
            main.post(() -> {
                toast(shown);
                if (this.patchSheet != null) this.patchSheet.run();
            });
        }, "ps3-patch-import").start();
    }

    // ---- 게임패드 (0.0.391; 2P-4P, 0.0.392): each controller is a player's pad (PlayerPads,
    // the order 멀티 패드 sets). Per port: {digital1, digital2, hat, triggers, lx, ly, rx, ry,
    // sticks moved}. Port 0 also takes the touch pad. ----
    private final int[][] ports = new int[PlayerPads.MAX][];
    private final java.util.Set<Integer> knownPads = new java.util.HashSet<>();
    private PlayerPads players;

    {
        for (int k = 0; k < this.ports.length; k++) {
            this.ports[k] = new int[]{0, 0, 0, 0, 128, 128, 128, 128, 0};
        }
    }

    private static boolean fromController(android.view.InputEvent event) {
        int source = event.getSource();
        return (source & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (source & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    // 패드 버튼: 0 Xbox식 (아래 A = ✕), 1 닌텐도식 (아래 B = ✕, 오른쪽 A = ○).
    private int padFaceLayout() {
        return MainActivity.sharedSettings(this).getInt("ps3_pad_face", 0);
    }

    // {digital1 bit, digital2 bit} for a controller key.
    private int[] padBits(int keyCode) {
        boolean nintendo = padFaceLayout() == 1;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A: return new int[]{0, nintendo ? D2_CIRCLE : D2_CROSS};
            case android.view.KeyEvent.KEYCODE_BUTTON_B: return new int[]{0, nintendo ? D2_CROSS : D2_CIRCLE};
            case android.view.KeyEvent.KEYCODE_BUTTON_X: return new int[]{0, nintendo ? D2_TRIANGLE : D2_SQUARE};
            case android.view.KeyEvent.KEYCODE_BUTTON_Y: return new int[]{0, nintendo ? D2_SQUARE : D2_TRIANGLE};
            case android.view.KeyEvent.KEYCODE_BUTTON_L1: return new int[]{0, D2_L1};
            case android.view.KeyEvent.KEYCODE_BUTTON_R1: return new int[]{0, D2_R1};
            case android.view.KeyEvent.KEYCODE_BUTTON_L2: return new int[]{0, D2_L2};
            case android.view.KeyEvent.KEYCODE_BUTTON_R2: return new int[]{0, D2_R2};
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL: return new int[]{D1_L3, 0};
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR: return new int[]{D1_R3, 0};
            case android.view.KeyEvent.KEYCODE_BUTTON_START: return new int[]{D1_START, 0};
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT: return new int[]{D1_SELECT, 0};
            case android.view.KeyEvent.KEYCODE_BUTTON_MODE: return new int[]{D1_PS, 0};
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return new int[]{D1_UP, 0};
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return new int[]{D1_DOWN, 0};
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return new int[]{D1_LEFT, 0};
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return new int[]{D1_RIGHT, 0};
            default: return null;
        }
    }

    private int portOf(android.view.InputEvent event) {
        if (this.players == null) {
            return 0;
        }
        if (this.players.maxPlayers() > 1 && this.knownPads.add(event.getDeviceId())) {
            // A controller not seen yet (connected after the game started): it gets a player.
            this.players.refresh(null);
        }
        int port = this.players.playerOf(event.getDeviceId());
        return Math.max(0, Math.min(PlayerPads.MAX - 1, port));
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (fromController(event) && !this.leaving && this.layoutEditor == null) {
            int[] bits = padBits(event.getKeyCode());
            if (bits != null && (event.getAction() == android.view.KeyEvent.ACTION_DOWN || event.getAction() == android.view.KeyEvent.ACTION_UP)) {
                boolean down = event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                int[] p = this.ports[portOf(event)];
                p[0] = down ? p[0] | bits[0] : p[0] & ~bits[0];
                p[1] = down ? p[1] | bits[1] : p[1] & ~bits[1];
                onPadUsed();
                turboTick(down);
                pushPad();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static int axisByte(android.view.MotionEvent event, int axis, float sensitivity) {
        float value = event.getAxisValue(axis);
        if (Math.abs(value) < 0.12f) value = 0f;
        return Math.max(0, Math.min(255, Math.round(128 + value * sensitivity * 127)));
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (fromController(event) && event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && !this.leaving) {
            int[] p = this.ports[portOf(event)];
            float sens = stickSensitivity(true);
            p[4] = axisByte(event, android.view.MotionEvent.AXIS_X, sens);
            p[5] = axisByte(event, android.view.MotionEvent.AXIS_Y, sens);
            p[6] = axisByte(event, android.view.MotionEvent.AXIS_Z, sens);
            p[7] = axisByte(event, android.view.MotionEvent.AXIS_RZ, sens);
            p[8] = p[4] != 128 || p[5] != 128 || p[6] != 128 || p[7] != 128 ? 1 : 0;
            float hx = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
            p[2] = (hx < -0.5f ? D1_LEFT : 0) | (hx > 0.5f ? D1_RIGHT : 0) | (hy < -0.5f ? D1_UP : 0) | (hy > 0.5f ? D1_DOWN : 0);
            float l2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_BRAKE));
            float r2 = Math.max(event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER), event.getAxisValue(android.view.MotionEvent.AXIS_GAS));
            p[3] = (l2 > 0.5f ? D2_L2 : 0) | (r2 > 0.5f ? D2_R2 : 0);
            turboTick(p[3] != 0);
            pushPad();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    // ---- 패드를 쓰면 화면 버튼 감추기 (0.0.392): with a controller in use the touch controls
    // fade out (the screen stays where it is); a touch on the screen brings them back. ----
    private boolean padMode;

    private boolean hidePadWithController() {
        return MainActivity.sharedSettings(this).getBoolean("ps3_pad_hide", true);
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
        int index = MainActivity.sharedSettings(this).getInt(pad ? "ps3_sens_pad" : "ps3_sens_touch", 2);
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
        return "ps3_turbo:" + this.gamePath;
    }

    private int turboMask() {
        return MainActivity.sharedSettings(this).getInt(turboKey(), 0);
    }

    private int turboRateIndex() {
        return Math.max(0, Math.min(TURBO_RATES.length - 1, MainActivity.sharedSettings(this).getInt("ps3_turbo_rate", 1)));
    }

    private final Runnable turboStep = new Runnable() {
        @Override
        public void run() {
            int mask = turboMask();
            int held = heldBits();
            if (mask == 0 || (held & mask) == 0 || Ps3Activity.this.leaving) {
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

    private int heldBits() {
        int d1 = this.digital1 | this.touchDirections;
        int d2 = this.digital2;
        for (int[] p : this.ports) {
            d1 |= p[0] | p[2];
            d2 |= p[1] | p[3];
        }
        return (d1 << D1_SHIFT) | (d2 & 0xFFFF);
    }

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
    private static final int[] TURBO_IDS = {D2_CROSS, D2_CIRCLE, D2_SQUARE, D2_TRIANGLE, D2_L1, D2_R1, D2_L2, D2_R2, D1_L3 << D1_SHIFT, D1_R3 << D1_SHIFT};
    private static final String[] TURBO_BUTTON_NAMES = {"✕", "○", "□", "△", "L1", "R1", "L2", "R2", "L3", "R3"};

    private void showTurboSheet() {
        ARMSX3.instance.pause();
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
            MainActivity.sharedSettings(this).edit().putInt("ps3_turbo_rate", next).commit();
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

    // ---- 자이로 · 식스액시스 (0.0.392): the phone's tilt as the PS3 pad's motion sensors, as
    // ARMSX3's own app works it out (Sixaxis.kt): held like a handheld, upright and facing
    // the player, is a DualShock 3 lying flat; 0-1023 with 512 at rest, about 113 per g, and
    // the one gyro (yaw, 78.31 per rad/s) the rate about the measured up direction. ----
    private android.hardware.SensorManager sensors;
    private android.hardware.SensorEventListener motionFeed;
    private float accX;
    private float accY;
    private float accZ = android.hardware.SensorManager.GRAVITY_EARTH;
    private float upX;
    private float upY;
    private float upZ = 1f;
    private float yawLeft;

    private String gyroKey() {
        return "ps3_gyro:" + this.gamePath;
    }

    private boolean gyroOn() {
        return MainActivity.sharedSettings(this).getBoolean(gyroKey(), false);
    }

    private static int sixaxis(float g) {
        return Math.max(0, Math.min(1023, Math.round(512 + g * 113f)));
    }

    private void startMotion() {
        stopMotion();
        if (!gyroOn() || !engineReady) {
            return;
        }
        this.sensors = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
        if (this.sensors == null) {
            return;
        }
        this.motionFeed = new android.hardware.SensorEventListener() {
            @Override
            public void onSensorChanged(android.hardware.SensorEvent event) {
                if (event.sensor.getType() == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                    accX = event.values[0];
                    accY = event.values[1];
                    accZ = event.values[2];
                    float len = (float) Math.sqrt(accX * accX + accY * accY + accZ * accZ);
                    if (len > 0.0001f) {
                        upX = accX / len;
                        upY = accY / len;
                        upZ = accZ / len;
                    }
                } else if (event.sensor.getType() == android.hardware.Sensor.TYPE_GYROSCOPE) {
                    yawLeft = event.values[0] * upX + event.values[1] * upY + event.values[2] * upZ;
                } else {
                    return;
                }
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                float right;
                float up;
                switch (rotation) {
                    case android.view.Surface.ROTATION_90: right = -accY; up = accX; break;
                    case android.view.Surface.ROTATION_180: right = -accX; up = -accY; break;
                    case android.view.Surface.ROTATION_270: right = accY; up = -accX; break;
                    default: right = accX; up = accY; break;
                }
                float g = android.hardware.SensorManager.GRAVITY_EARTH;
                if (engineReady && !leaving) {
                    ARMSX3.instance.setPadSensor(0, sixaxis(-right / g), sixaxis(-up / g), sixaxis(-accZ / g),
                            Math.max(0, Math.min(1023, Math.round(512 - yawLeft * 78.31f))));
                }
            }

            @Override
            public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
            }
        };
        android.hardware.Sensor acc = this.sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        android.hardware.Sensor gyro = this.sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
        if (acc != null) this.sensors.registerListener(this.motionFeed, acc, android.hardware.SensorManager.SENSOR_DELAY_GAME);
        if (gyro != null) this.sensors.registerListener(this.motionFeed, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopMotion() {
        if (this.sensors != null && this.motionFeed != null) {
            this.sensors.unregisterListener(this.motionFeed);
            if (engineReady && !this.leaving) {
                // At rest, so the game does not keep reading the last tilt.
                ARMSX3.instance.setPadSensor(0, 512, sixaxis(-1f), 512, 512);
            }
        }
        this.motionFeed = null;
    }

    private interface Choice {
        void picked(int index);
    }

    private void showChoices(int icon, int color, String title, String[] names, String[] notes, int current, String note, Choice onPick) {
        ARMSX3.instance.pause();
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
        dialog.setContentView(scrollable(panel));
        dialog.setOnDismissListener(d -> resumeGame());
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
            ARMSX3.instance.pause();
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        TextView title = text("게임을 나갈까요?", 17, Color.rgb(31, 41, 51), true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = text("PS3 게임은 게임 안에서 저장한 곳부터 이어져요.", 12, Color.rgb(122, 130, 140), false);
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

    // Stops the game (the engine flushes what the game saved) and ends this process.
    private void leave() {
        this.leaving = true;
        new Thread(() -> {
            if (engineReady) {
                try {
                    ARMSX3.instance.kill();
                    for (int i = 0; i < 80 && ARMSX3.instance.getState() != ARMSX3.STATE_STOPPED; i++) {
                        Thread.sleep(100);
                    }
                } catch (Throwable ignored) {
                    // Leaving anyway.
                }
            }
            main.post(() -> {
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            });
        }, "ps3-exit").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engineReady && this.bootStarted && !this.leaving) {
            ARMSX3.instance.pause();
            ARMSX3.instance.setAppBackgrounded(true);
        }
        stopMotion();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engineReady && this.bootStarted && !this.leaving) {
            ARMSX3.instance.setAppBackgrounded(false);
            if (this.pauseDialog == null && this.layoutEditor == null) {
                ARMSX3.instance.resume();
            }
            startMotion();
        }
    }

    // ---- Small views ----

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
}
