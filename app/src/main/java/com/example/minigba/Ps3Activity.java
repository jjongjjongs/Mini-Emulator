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
import net.rpcsx.ProgressRepository;
import net.rpcsx.RPCSX;

// PS3 games on the ARMSX3 engine (RPCS3 for Android), and its firmware install. The engine is
// not a libretro core: it draws to the Surface itself, plays its own sound and takes the whole
// pad state on every change. It runs in its own process (":ps3", see the manifest), which ends
// when the game is left, so every launch starts the engine fresh.
public final class Ps3Activity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_ACTION = "com.example.minigba.PS3_ACTION";
    static final String EXTRA_PATH = "com.example.minigba.PS3_PATH";
    static final String EXTRA_TITLE = "com.example.minigba.PS3_TITLE";
    static final String ACTION_BOOT = "boot";
    static final String ACTION_FIRMWARE = "firmware";

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

    static File rootDir(Context context) {
        return new File(context.getFilesDir(), "ps3");
    }

    static boolean firmwareInstalled(Context context) {
        return new File(rootDir(context), "config/dev_flash/vsh/module/vsh.self").isFile();
    }

    // Why this phone cannot run the PS3 engine, or null. It is built for Android 11+ and
    // ARMv8.2 (LSE atomics and RDMA, which ARMv8.0 cores fault on).
    static String unsupportedReason(Context context) {
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
            if (!RPCSX.instance.openLibrary(core.getAbsolutePath())) {
                engineError = "PS3 엔진을 불러오지 못했어요.";
                return false;
            }
            File root = rootDir(context);
            root.mkdirs();
            new File(root, ".nomedia").createNewFile();
            stageOverlayIcons(context, new File(root, "config/Icons"));
            String soc = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null ? Build.SOC_MODEL : "";
            if (!RPCSX.instance.initialize(root.getAbsolutePath() + "/", "00000001", soc)) {
                engineError = "PS3 엔진을 시작하지 못했어요.";
                return false;
            }
            // Two loops the engine needs a thread each for, for the life of the process: work
            // posted to its "main thread" (save data, state saves) and PPU/SPU compilation.
            startDaemon("ps3-main-thread", () -> RPCSX.instance.startMainThreadProcessor());
            startDaemon("ps3-compile", () -> RPCSX.instance.processCompilationQueue());
            engineReady = true;
            return true;
        } catch (Throwable t) {
            engineError = "PS3 엔진 오류: " + t.getMessage();
            return false;
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
                    ok = RPCSX.instance.installFw(pfd.dup().detachFd(), 1);
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

    private void buildGameScreen() {
        FrameLayout root = new FrameLayout(this);
        int style = MainActivity.sharedSettings(this).getInt("shell_style", ClearShellView.STYLE_CLEAR);
        int body = Color.rgb(24, 26, 34);
        ClearShellView shell = new ClearShellView(this, ClearShellView.TINT_SMOKE, "MINI EMULATOR · PS3", false, style, INDIGO, body);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        int labelColor = ClearShellView.darkBackground(style, body) ? Color.rgb(230, 232, 238) : Color.rgb(58, 63, 75);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(4));
        top.addView(iconButton(RoundIconView.MENU, RoundIconView.COLOR_MENU, "메뉴", labelColor, v -> showExitDialog()));
        TextView title = text(getIntent().getStringExtra(EXTRA_TITLE), 13, labelColor, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(10), 0, dp(10), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // 16:9 screen, the width of the phone with a small margin.
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        this.statusView = text("PS3 엔진을 시작하는 중…\n처음에는 셰이더와 코드를 준비하느라 오래 걸릴 수 있어요.", 13, Color.rgb(205, 214, 244), false);
        this.statusView.setGravity(Gravity.CENTER);
        screen.addView(this.statusView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        int width = getResources().getDisplayMetrics().widthPixels - dp(16);
        LinearLayout.LayoutParams screenParams = new LinearLayout.LayoutParams(width, width * 9 / 16);
        screenParams.gravity = Gravity.CENTER_HORIZONTAL;
        screenParams.setMargins(0, dp(10), 0, dp(30));
        column.addView(screen, screenParams);

        FrameLayout pad = new FrameLayout(this);
        buildPad(pad);
        column.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        shell.track(screen, pad);
        setContentView(root);
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
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(true, x, y)), 24, 18, 116, 116, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new AnalogStickView(this, (x, y) -> onStick(false, x, y)), 24, 18, 116, 116, Gravity.BOTTOM | Gravity.END);
        addAt(pad, new TouchButton(this, "L3", D1_L3 << D1_SHIFT, buttons, TouchButton.STYLE_DARK), 146, 106, 40, 40, Gravity.BOTTOM | Gravity.START);
        addAt(pad, new TouchButton(this, "R3", D1_R3 << D1_SHIFT, buttons, TouchButton.STYLE_DARK), 146, 106, 40, 40, Gravity.BOTTOM | Gravity.END);
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
        ps.bottomMargin = dp(66);
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
        RPCSX.instance.overlayPadData(0, this.digital1 | this.touchDirections, this.digital2, this.leftX, this.leftY, this.rightX, this.rightY);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!engineReady) {
            return;
        }
        RPCSX.instance.surfaceSizeChanged(width, height);
        RPCSX.instance.surfaceEvent(holder.getSurface(), this.surfaceSeen ? RPCSX.SURFACE_CHANGED : RPCSX.SURFACE_CREATED);
        this.surfaceSeen = true;
        if (!this.bootStarted) {
            this.bootStarted = true;
            bootGame();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (engineReady && this.surfaceSeen) {
            RPCSX.instance.surfaceEvent(holder.getSurface(), RPCSX.SURFACE_DESTROYED);
            this.surfaceSeen = false;
        }
    }

    private void bootGame() {
        final String path = this.gamePath;
        new Thread(() -> {
            int result = path == null ? 2 : RPCSX.instance.boot(path);
            if (result != RPCSX.BOOT_OK && result != RPCSX.BOOT_ALREADY_ADDED) {
                main.post(() -> showMessage("게임을 시작하지 못했어요", bootError(result), true));
                return;
            }
            // Hide the starting note once the engine reports the game running.
            for (int i = 0; i < 600 && !this.leaving; i++) {
                if (RPCSX.instance.getState() == RPCSX.STATE_RUNNING) {
                    main.post(() -> this.statusView.setVisibility(View.GONE));
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

    private static String bootError(int result) {
        switch (result) {
            case 2: return "실행할 게임이 없어요.";
            case 4: case 5: return "PS3 게임 파일(ISO)이 아니거나 손상됐어요.";
            case 7: return "이 게임은 복호화 키(.rap 등)가 있어야 실행돼요.";
            case 9: return "PS3 펌웨어가 설치되지 않았어요. PS3 화면의 'PS3 펌웨어'에서 설치해 주세요.";
            case 10: return "이 게임은 더 새로운 PS3 펌웨어가 필요해요.";
            case 11: return "지원하지 않는 디스크 형식이에요.";
            default: return "PS3 엔진이 오류를 알렸어요 (코드 " + result + ").";
        }
    }

    // ---- Leaving ----

    @Override
    public void onBackPressed() {
        showExitDialog();
    }

    private void showExitDialog() {
        if (engineReady) {
            RPCSX.instance.pause();
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
            if (engineReady && !this.leaving) {
                RPCSX.instance.resume();
            }
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
                    RPCSX.instance.kill();
                    for (int i = 0; i < 80 && RPCSX.instance.getState() != RPCSX.STATE_STOPPED; i++) {
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
            RPCSX.instance.pause();
            RPCSX.instance.setAppBackgrounded(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engineReady && this.bootStarted && !this.leaving) {
            RPCSX.instance.setAppBackgrounded(false);
            RPCSX.instance.resume();
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
