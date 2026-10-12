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
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GameActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_CORE_ID = "com.example.minigba.CORE_ID";
    static final String EXTRA_ROM_PATH = "com.example.minigba.ROM_PATH";
    static final String EXTRA_SAVE_BASE_NAME = "com.example.minigba.SAVE_BASE_NAME";
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREF_ORIENTATION_MODE = "orientation_mode";
    private static final String PREF_SCREEN_MODE = "screen_mode";
    private SurfaceHolder activeSurfaceHolder;
    private AudioSink audioSink;
    private int buttonMask;
    private String coreId;
    private SpeedButtonView fastForwardButton;
    private boolean landscapeMode;
    private boolean menuExiting;
    private long nativeHandle;
    private String romPath;
    private String saveBaseName;
    private int speedMultiplier = 1;
    private boolean surfaceReady;
    private SurfaceView surfaceView;

    @Override // android.app.Activity
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
        String str = this.romPath;
        String lowerCase = str == null ? "" : str.toLowerCase(Locale.US);
        if (((!this.coreId.equals("gba") || !lowerCase.endsWith(".gba")) && (this.coreId.equals("gba") || !lowerCase.endsWith(".zip"))) || (!this.coreId.equals("gba") && !this.coreId.equals("fbneo") && !this.coreId.equals("mame2003plus") && !this.coreId.equals("mame139") && !this.coreId.equals("mamearcade"))) {
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
        frameLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda18
            @Override // android.view.View.OnLayoutChangeListener
            public final void onLayoutChange(View view, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9) {
                GameActivity.this.m8lambda$createLandscapeContent$0$comexampleminigbaGameActivity(i, frameLayout2, view, i2, i3, i4, i5, i6, i7, i8, i9);
            }
        });
        linearLayout.addView(frameLayout, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(linearLayout);
    }

    /* synthetic */ void m8lambda$createLandscapeContent$0$comexampleminigbaGameActivity(int i, FrameLayout frameLayout, View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
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
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(this.coreId.equals("gba") ? 150 : 166), dp(this.coreId.equals("gba") ? 150 : 166));
        layoutParams.gravity = 8388627;
        layoutParams.setMargins(dp(this.coreId.equals("gba") ? 12 : 8), 0, 0, 0);
        frameLayout.addView(createDirectionControl, layoutParams);
        if (this.coreId.equals("gba")) {
            addButton(frameLayout, "L", 512, 8388659, dp(18), dp(14), dp(104), dp(40), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(18), dp(14), dp(104), dp(40), 2);
            addLandscapeActionButton(frameLayout, "A", 1, dp(26), -dp(42), 3);
            addLandscapeActionButton(frameLayout, "B", 2, dp(92), dp(42), 3);
        } else {
            addLandscapeArcadeButton(frameLayout, "C", 1024, 130, -24, 5);
            addLandscapeArcadeButton(frameLayout, "D", 2048, 70, -32, 5);
            addLandscapeArcadeButton(frameLayout, "E", 4096, 10, -40, 5);
            addLandscapeArcadeButton(frameLayout, "A", 1, 130, 40, 4);
            addLandscapeArcadeButton(frameLayout, "B", 2, 70, 32, 4);
            addLandscapeArcadeButton(frameLayout, "F", 8192, 10, 24, 4);
        }
        addButton(frameLayout, this.coreId.equals("gba") ? "SELECT" : "COIN", 4, 8388691, dp(42), dp(18), dp(94), dp(34));
        addButton(frameLayout, "START", 8, 8388693, dp(42), dp(18), dp(94), dp(34));
    }

    private View createDirectionControl() {
        if (!this.coreId.equals("gba")) {
            return new ArcadeJoystickView(this, new ArcadeJoystickView.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda10
                @Override // com.example.minigba.ArcadeJoystickView.Listener
                public final void onDirectionMaskChanged(int i) {
                    GameActivity.this.m6lambda$createDirectionControl$1$comexampleminigbaGameActivity(i);
                }
            });
        }
        return new DPadView(this, new DPadView.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda12
            @Override // com.example.minigba.DPadView.Listener
            public final void onDirectionMaskChanged(int i) {
                GameActivity.this.m7lambda$createDirectionControl$2$comexampleminigbaGameActivity(i);
            }
        });
    }

    /* renamed from: lambda$createDirectionControl$1$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m6lambda$createDirectionControl$1$comexampleminigbaGameActivity(int i) {
        this.buttonMask = i | (this.buttonMask & (-241));
        updateInput();
    }

    /* renamed from: lambda$createDirectionControl$2$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m7lambda$createDirectionControl$2$comexampleminigbaGameActivity(int i) {
        this.buttonMask = i | (this.buttonMask & (-241));
        updateInput();
    }

    private void addLandscapeActionButton(FrameLayout frameLayout, String str, int i, int i2, int i3) {
        addLandscapeActionButton(frameLayout, str, i, i2, i3, 3);
    }

    private void addLandscapeActionButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4) {
        TouchButton touchButton = new TouchButton(this, str, i, new TouchButton.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda19
            @Override // com.example.minigba.TouchButton.Listener
            public final void onButtonChanged(int i5, boolean z) {
                GameActivity.this.m3x1fe3fe04(i5, z);
            }
        }, i4);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(66), dp(66));
        layoutParams.gravity = 8388629;
        layoutParams.setMargins(0, 0, i2, 0);
        touchButton.setTranslationY(i3);
        frameLayout.addView(touchButton, layoutParams);
    }

    /* renamed from: lambda$addLandscapeActionButton$3$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m3x1fe3fe04(int i, boolean z) {
        if (z) {
            this.buttonMask = i | this.buttonMask;
        } else {
            this.buttonMask = (~i) & this.buttonMask;
        }
        updateInput();
    }

    private void addLandscapeArcadeButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4) {
        TouchButton touchButton = new TouchButton(this, str, i, new TouchButton.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda20
            @Override // com.example.minigba.TouchButton.Listener
            public final void onButtonChanged(int i5, boolean z) {
                GameActivity.this.m4xc2963889(i5, z);
            }
        }, i4);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(54), dp(54));
        layoutParams.gravity = 8388629;
        layoutParams.setMargins(0, 0, dp(i2), 0);
        touchButton.setTranslationY(dp(i3));
        frameLayout.addView(touchButton, layoutParams);
    }

    /* renamed from: lambda$addLandscapeArcadeButton$4$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m4xc2963889(int i, boolean z) {
        if (z) {
            this.buttonMask = i | this.buttonMask;
        } else {
            this.buttonMask = (~i) & this.buttonMask;
        }
        updateInput();
    }

    @Override // android.app.Activity
    protected void onPause() {
        super.onPause();
        if (isMame139()) {
            Emulator.pause();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, true);
            NativeGba.saveNow(this.nativeHandle);
        }
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, false);
        }
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        if (isMame139() && isFinishing()) {
            Emulator.stop();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.destroy(j);
                this.nativeHandle = 0L;
            }
        }
        AudioSink audioSink = this.audioSink;
        if (audioSink != null) {
            audioSink.release();
        }
        super.onDestroy();
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        showBackExitDialog();
    }

    @Override // android.view.SurfaceHolder.Callback
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

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i2, int i3) {
        if (this.surfaceReady) {
            return;
        }
        startCore(surfaceHolder.getSurface());
    }

    @Override // android.view.SurfaceHolder.Callback
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

    @Override // android.app.Activity, android.content.ComponentCallbacks
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
        if ((isMame139() || this.nativeHandle == 0) && surface.isValid()) {
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
            try {
                long create = NativeGba.create(surface, this.romPath, file.getAbsolutePath(), file2.getAbsolutePath(), this.saveBaseName, this.coreId, this.audioSink);
                this.nativeHandle = create;
                NativeGba.setInput(create, this.buttonMask);
                NativeGba.setSpeedMultiplier(this.nativeHandle, this.speedMultiplier);
            } catch (RuntimeException e3) {
                showFatalError(e3.getMessage() == null ? "ROM 실행 실패" : e3.getMessage());
            } catch (UnsatisfiedLinkError e4) {
                showFatalError("코어 로드 실패: " + e4.getMessage());
            }
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
        if (file != null && file.isFile()) {
            try {
                ZipFile zipFile = new ZipFile(file);
                try {
                    if (zipFile.getEntry(str) == null) {
                        boolean anyMatch = zipFile.stream().anyMatch(new Predicate() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda16
                            @Override // java.util.function.Predicate
                            public final boolean test(Object obj) {
                                boolean equalsIgnoreCase;
                                equalsIgnoreCase = new File(((ZipEntry) obj).getName()).getName().equalsIgnoreCase(str);
                                return equalsIgnoreCase;
                            }
                        });
                        zipFile.close();
                        return anyMatch;
                    }
                    zipFile.close();
                    return true;
                } finally {
                }
            } catch (IOException unused) {
            }
        }
        return false;
    }

    private LinearLayout createTopBar() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(16), dp(4), dp(16), dp(4));
        linearLayout.setBackgroundColor(Color.rgb(218, 215, 207));
        addTopButton(linearLayout, 0, "저장", new View.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                GameActivity.this.m9lambda$createTopBar$6$comexampleminigbaGameActivity(view);
            }
        });
        addTopButton(linearLayout, 1, "재시작", new View.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                GameActivity.this.m10lambda$createTopBar$7$comexampleminigbaGameActivity(view);
            }
        });
        if (this.landscapeMode) {
            addTopSpeedButton(linearLayout);
        }
        addTopButton(linearLayout, 3, "화면 회전", new View.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                GameActivity.this.m11lambda$createTopBar$8$comexampleminigbaGameActivity(view);
            }
        });
        addTopButton(linearLayout, 2, "메뉴", new View.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                GameActivity.this.m12lambda$createTopBar$9$comexampleminigbaGameActivity(view);
            }
        });
        return linearLayout;
    }

    /* renamed from: lambda$createTopBar$6$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m9lambda$createTopBar$6$comexampleminigbaGameActivity(View view) {
        if (isMame139()) {
            Emulator.saveState();
            Toast.makeText(this, "MAME 0.139 빠른 저장을 요청했습니다.", 0).show();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                Toast.makeText(this, NativeGba.saveNow(j) ? "빠른 저장과 게임 내 세이브를 저장했습니다." : "저장 실패.", 0).show();
            }
        }
    }

    /* renamed from: lambda$createTopBar$7$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m10lambda$createTopBar$7$comexampleminigbaGameActivity(View view) {
        if (isMame139()) {
            Toast.makeText(this, "MAME 0.139 재시작은 ROM 목록에서 다시 실행하세요.", 0).show();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.reset(j);
            Toast.makeText(this, "게임을 재시작했습니다.", 0).show();
        }
    }

    /* renamed from: lambda$createTopBar$8$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m11lambda$createTopBar$8$comexampleminigbaGameActivity(View view) {
        toggleOrientation();
    }

    /* renamed from: lambda$createTopBar$9$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m12lambda$createTopBar$9$comexampleminigbaGameActivity(View view) {
        showGameMenu();
    }

    private void addTopSpeedButton(LinearLayout linearLayout) {
        SpeedButtonView speedButtonView = new SpeedButtonView(this, new SpeedButtonView.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda1
            @Override // com.example.minigba.SpeedButtonView.Listener
            public final void onChanged(int i) {
                GameActivity.this.m5lambda$addTopSpeedButton$10$comexampleminigbaGameActivity(i);
            }
        });
        this.fastForwardButton = speedButtonView;
        speedButtonView.setMultiplier(this.speedMultiplier);
        this.fastForwardButton.setContentDescription("배속");
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        layoutParams.setMargins(dp(10), 0, dp(10), 0);
        linearLayout.addView(this.fastForwardButton, layoutParams);
    }

    /* renamed from: lambda$addTopSpeedButton$10$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m5lambda$addTopSpeedButton$10$comexampleminigbaGameActivity(int i) {
        this.speedMultiplier = i;
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.setSpeedMultiplier(j, i);
        }
    }

    private void toggleOrientation() {
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.saveNow(j);
        }
        int i = !this.landscapeMode ? 1 : 0;
        getSharedPreferences("settings", 0).edit().putInt("orientation_mode", i).apply();
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
        int i = this.coreId.equals("gba") ? 166 : 180;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(i), dp(i));
        layoutParams.gravity = 8388691;
        layoutParams.setMargins(dp(this.coreId.equals("gba") ? 24 : 16), 0, 0, dp(this.coreId.equals("gba") ? 154 : 144));
        frameLayout.addView(createDirectionControl, layoutParams);
        if (this.coreId.equals("gba")) {
            addButton(frameLayout, "L", 512, 8388659, dp(24), dp(34), dp(112), dp(46), 2);
            addButton(frameLayout, "R", 256, 8388661, dp(24), dp(34), dp(112), dp(46), 2);
        }
        addFastForwardButton(frameLayout);
        if (this.coreId.equals("gba")) {
            addButton(frameLayout, "A", 1, 8388693, dp(44), dp(243), dp(72), dp(72), 3);
            addButton(frameLayout, "B", 2, 8388693, dp(78), dp(159), dp(72), dp(72), 3);
        } else {
            addButton(frameLayout, "C", 1024, 8388693, dp(140), dp(224), dp(58), dp(58), 5);
            addButton(frameLayout, "D", 2048, 8388693, dp(78), dp(236), dp(58), dp(58), 5);
            addButton(frameLayout, "E", 4096, 8388693, dp(16), dp(248), dp(58), dp(58), 5);
            addButton(frameLayout, "A", 1, 8388693, dp(140), dp(154), dp(58), dp(58), 4);
            addButton(frameLayout, "B", 2, 8388693, dp(78), dp(166), dp(58), dp(58), 4);
            addButton(frameLayout, "F", 8192, 8388693, dp(16), dp(178), dp(58), dp(58), 4);
        }
        addCenterButton(frameLayout, this.coreId.equals("gba") ? "SELECT" : "COIN", 4, -66);
        addCenterButton(frameLayout, "START", 8, 66);
    }

    private void addButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4, int i5, int i6) {
        addButton(frameLayout, str, i, i2, i3, i4, i5, i6, 0);
    }

    private void addButton(FrameLayout frameLayout, String str, int i, int i2, int i3, int i4, int i5, int i6, int i7) {
        View touchButton = new TouchButton(this, str, i, new TouchButton.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda0
            @Override // com.example.minigba.TouchButton.Listener
            public final void onButtonChanged(int i8, boolean z) {
                GameActivity.this.m0lambda$addButton$11$comexampleminigbaGameActivity(i8, z);
            }
        }, i7);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(i5, i6);
        layoutParams.gravity = i2;
        int max = Math.max(0, i3);
        layoutParams.setMargins(max, i4, max, i4);
        frameLayout.addView(touchButton, layoutParams);
    }

    /* renamed from: lambda$addButton$11$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m0lambda$addButton$11$comexampleminigbaGameActivity(int i, boolean z) {
        if (z) {
            this.buttonMask = i | this.buttonMask;
        } else {
            this.buttonMask = (~i) & this.buttonMask;
        }
        updateInput();
    }

    private void addFastForwardButton(FrameLayout frameLayout) {
        SpeedButtonView speedButtonView = new SpeedButtonView(this, new SpeedButtonView.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda17
            @Override // com.example.minigba.SpeedButtonView.Listener
            public final void onChanged(int i) {
                GameActivity.this.m2lambda$addFastForwardButton$12$comexampleminigbaGameActivity(i);
            }
        });
        this.fastForwardButton = speedButtonView;
        speedButtonView.setMultiplier(this.speedMultiplier);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(42));
        layoutParams.gravity = 49;
        layoutParams.setMargins(0, dp(37), 0, 0);
        frameLayout.addView(this.fastForwardButton, layoutParams);
    }

    /* renamed from: lambda$addFastForwardButton$12$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m2lambda$addFastForwardButton$12$comexampleminigbaGameActivity(int i) {
        this.speedMultiplier = i;
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.setSpeedMultiplier(j, i);
        }
    }

    private void addCenterButton(FrameLayout frameLayout, String str, int i, int i2) {
        TouchButton touchButton = new TouchButton(this, str, i, new TouchButton.Listener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda9
            @Override // com.example.minigba.TouchButton.Listener
            public final void onButtonChanged(int i3, boolean z) {
                GameActivity.this.m1lambda$addCenterButton$13$comexampleminigbaGameActivity(i3, z);
            }
        });
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dp(92), dp(34));
        layoutParams.gravity = 81;
        layoutParams.setMargins(0, 0, 0, dp(72));
        touchButton.setTranslationX(dp(i2));
        frameLayout.addView(touchButton, layoutParams);
    }

    /* renamed from: lambda$addCenterButton$13$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m1lambda$addCenterButton$13$comexampleminigbaGameActivity(int i, boolean z) {
        if (z) {
            this.buttonMask = i | this.buttonMask;
        } else {
            this.buttonMask = (~i) & this.buttonMask;
        }
        updateInput();
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
        String[] strArr = new String[7];
        strArr[0] = "계속하기";
        strArr[1] = "빠른 저장";
        strArr[2] = "빠른 저장 불러오기";
        strArr[3] = "게임 내 세이브 저장";
        strArr[4] = "재시작";
        strArr[5] = this.landscapeMode ? "세로 화면으로 전환" : "가로 화면으로 전환";
        strArr[6] = "ROM 목록으로 나가기";
        AlertDialog create = new AlertDialog.Builder(this).setTitle(this.coreId.equals("gba") ? "Mini GBA" : "Mini Arcade").setItems(strArr, new DialogInterface.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda7
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                GameActivity.this.m18lambda$showGameMenu$14$comexampleminigbaGameActivity(dialogInterface, i);
            }
        }).create();
        create.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda8
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                GameActivity.this.m19lambda$showGameMenu$15$comexampleminigbaGameActivity(dialogInterface);
            }
        });
        create.show();
    }

    /* renamed from: lambda$showGameMenu$14$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m18lambda$showGameMenu$14$comexampleminigbaGameActivity(DialogInterface dialogInterface, int i) {
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
                long j = this.nativeHandle;
                if (j != 0) {
                    Toast.makeText(this, NativeGba.saveStateNow(j) ? "빠른 저장을 완료했습니다." : "빠른 저장 실패.", 0).show();
                    return;
                }
                return;
            }
        }
        if (i == 2) {
            if (isMame139()) {
                Emulator.loadState();
                Toast.makeText(this, "MAME 0.139 빠른 저장 불러오기를 요청했습니다.", 0).show();
                return;
            } else {
                long j2 = this.nativeHandle;
                if (j2 != 0) {
                    Toast.makeText(this, NativeGba.loadStateNow(j2) ? "빠른 저장을 불러왔습니다." : "빠른 저장 파일이 없습니다.", 0).show();
                    return;
                }
                return;
            }
        }
        if (i == 3) {
            if (isMame139()) {
                Emulator.saveState();
                Toast.makeText(this, "MAME 0.139 상태를 저장했습니다.", 0).show();
                return;
            } else {
                long j3 = this.nativeHandle;
                if (j3 != 0) {
                    Toast.makeText(this, NativeGba.saveSramNow(j3) ? "게임 내 세이브 파일을 저장했습니다." : "게임 내 세이브 데이터가 없습니다.", 0).show();
                    return;
                }
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
        long j4 = this.nativeHandle;
        if (j4 != 0) {
            NativeGba.reset(j4);
            Toast.makeText(this, "게임을 재시작했습니다.", 0).show();
        }
    }

    /* renamed from: lambda$showGameMenu$15$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m19lambda$showGameMenu$15$comexampleminigbaGameActivity(DialogInterface dialogInterface) {
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
        AlertDialog create = new AlertDialog.Builder(this).setTitle("게임으로 돌아갈까요?").setMessage("아니오를 누르면 현재 게임을 저장하고 ROM 목록으로 나갑니다.").setNegativeButton("예", new DialogInterface.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda11
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                GameActivity.this.m13lambda$showBackExitDialog$16$comexampleminigbaGameActivity(dialogInterface, i);
            }
        }).setPositiveButton("아니오", new DialogInterface.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda13
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                GameActivity.this.m14lambda$showBackExitDialog$17$comexampleminigbaGameActivity(dialogInterface, i);
            }
        }).create();
        create.setOnCancelListener(new DialogInterface.OnCancelListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda14
            @Override // android.content.DialogInterface.OnCancelListener
            public final void onCancel(DialogInterface dialogInterface) {
                GameActivity.this.m15lambda$showBackExitDialog$18$comexampleminigbaGameActivity(dialogInterface);
            }
        });
        create.setOnDismissListener(new DialogInterface.OnDismissListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda15
            @Override // android.content.DialogInterface.OnDismissListener
            public final void onDismiss(DialogInterface dialogInterface) {
                GameActivity.this.m16lambda$showBackExitDialog$19$comexampleminigbaGameActivity(dialogInterface);
            }
        });
        create.show();
    }

    /* renamed from: lambda$showBackExitDialog$16$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m13lambda$showBackExitDialog$16$comexampleminigbaGameActivity(DialogInterface dialogInterface, int i) {
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, false);
        }
    }

    /* renamed from: lambda$showBackExitDialog$17$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m14lambda$showBackExitDialog$17$comexampleminigbaGameActivity(DialogInterface dialogInterface, int i) {
        this.menuExiting = true;
        if (isMame139()) {
            Emulator.stop();
        } else {
            long j = this.nativeHandle;
            if (j != 0) {
                NativeGba.saveNow(j);
            }
        }
        finish();
    }

    /* renamed from: lambda$showBackExitDialog$18$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m15lambda$showBackExitDialog$18$comexampleminigbaGameActivity(DialogInterface dialogInterface) {
        if (isMame139()) {
            Emulator.resume();
            return;
        }
        long j = this.nativeHandle;
        if (j != 0) {
            NativeGba.pause(j, false);
        }
    }

    /* renamed from: lambda$showBackExitDialog$19$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m16lambda$showBackExitDialog$19$comexampleminigbaGameActivity(DialogInterface dialogInterface) {
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

    private void showFatalError(String str) {
        new AlertDialog.Builder(this).setTitle(this.coreId.equals("gba") ? "Mini GBA" : "Mini Arcade").setMessage(str).setPositiveButton(17039370, new DialogInterface.OnClickListener() { // from class: com.example.minigba.GameActivity$$ExternalSyntheticLambda6
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                GameActivity.this.m17lambda$showFatalError$20$comexampleminigbaGameActivity(dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$showFatalError$20$com-example-minigba-GameActivity, reason: not valid java name */
    /* synthetic */ void m17lambda$showFatalError$20$comexampleminigbaGameActivity(DialogInterface dialogInterface, int i) {
        finish();
    }

    private int dp(int i) {
        return (int) ((i * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private boolean isMame139() {
        return "mame139".equals(this.coreId);
    }

    private String baseNameWithoutExtension(String str) {
        int lastIndexOf = str.lastIndexOf(46);
        return lastIndexOf >= 0 ? str.substring(0, lastIndexOf) : str;
    }
}
