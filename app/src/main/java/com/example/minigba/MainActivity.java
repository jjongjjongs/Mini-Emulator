package com.example.minigba;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class MainActivity extends Activity {
    private static final String PREFS_ROMS = "roms";
    private static final String PREFS_SETTINGS = "settings";
    private static final String PREF_CORE_PREFIX = "core:";
    private static final String PREF_FAVORITE_PREFIX = "favorite:";
    private static final String PREF_RECENT_PREFIX = "recent:";
    private static final String PREF_SAVE_PREFIX = "save:";
    private static final String PREF_MODE = "mode";
    private static final String PREF_REMEMBER_MODE = "remember_mode";
    private static final String PREF_ARCADE_CORE = "arcade_default_core";
    private static final String PREF_NEOGEO_CORE = "neogeo_default_core";
    private static final String[] ARCADE_CORE_IDS = {"mame2003plus", "fbneo", "mame2010", "mame2015"};
    private static final String[] ARCADE_CORE_LABELS = {"MAME 2003-Plus (구형 아케이드, 가벼움)", "FinalBurn Neo (Neo Geo · CPS 등)", "MAME 2010 (0.139, ZN·3D 기판 등 더 많은 게임)", "MAME 2015 (ST-V 등 더 최신 지원, 무거움)"};
    private static final String MODE_GBA = "gba";
    private static final String MODE_SNES = "snes";
    private static final String MODE_NES = "nes";
    private static final String MODE_MD = "md";
    private static final String MODE_PCE = "pce";
    private static final int REQUEST_OPEN_SNES_ROM = 1008;
    // Cartridge consoles: mode, engine, ROM extensions, file label, ROM folder, file picker request.
    private static final String[][] CONSOLES = {
            {MODE_SNES, "snes9x", ".sfc,.smc", ".sfc / .smc / .zip", "roms_snes", "1008"},
            {MODE_NES, "fceumm", ".nes,.unf,.unif", ".nes / .zip", "roms_nes", "1014"},
            {MODE_MD, "genesis_plus_gx", ".md,.gen,.smd,.bin,.sms,.gg", ".md / .gen / .bin / .zip", "roms_md", "1015"},
            {MODE_PCE, "pce_fast", ".pce", ".pce / .zip", "roms_pce", "1016"},
    };

    private static String[] consoleFor(String mode) {
        for (String[] console : CONSOLES) {
            if (console[0].equals(mode)) {
                return console;
            }
        }
        return null;
    }

    private static boolean isConsoleMode(String mode) {
        return consoleFor(mode) != null;
    }

    private static boolean isArcadeMode(String mode) {
        return "arcade".equals(mode) || "neogeo".equals(mode);
    }

    private static boolean hasExtension(String lowerCaseName, String extensions) {
        for (String extension : extensions.split(",")) {
            if (lowerCaseName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    // ROM file names an engine of a cartridge console accepts (used by GameActivity too).
    static boolean isConsoleRomNameForCore(String core, String lowerCaseName) {
        for (String[] console : CONSOLES) {
            if (console[1].equals(core)) {
                return hasExtension(lowerCaseName, console[2]);
            }
        }
        return false;
    }
    private static final String STATE_MODE = "current_mode";
    private static final int REQUEST_OPEN_BIOS_FOLDER = 1012;
    private static final int REQUEST_OPEN_ROM_FOLDER = 1013;
    private static final String STATE_FOLDER_MODE = "pending_folder_mode";
;
;
    // BIOS archives MAME 2003-Plus and FBNeo look for, recognised during a folder import.
    private static final String[] KNOWN_ARCADE_BIOS = {"pgm.zip", "skns.zip", "stvbios.zip", "cpzn1.zip", "cpzn2.zip", "tps.zip", "taitofx1.zip", "decocass.zip", "konamigv.zip", "konamigx.zip", "playch10.zip", "nss.zip", "megaplay.zip", "megatech.zip", "cvs.zip", "neocdz.zip", "isgsm.zip", "midssio.zip", "coh1000c.zip", "coh1002m.zip", "coh3002c.zip", "hng64.zip", "naomi.zip", "awbios.zip"};
    // Game modes shown on the picker. Only GBA ships an engine so far.
    private static final String[] MODE_IDS = {MODE_GBA, MODE_SNES, MODE_NES, MODE_MD, MODE_PCE, "neogeo", "arcade"};
    private static final String[] MODE_NAMES = {"GBA · GBC", "슈퍼패미컴", "패미컴", "메가드라이브", "PC엔진", "Neo Geo", "아케이드"};
    private static final String[] MODE_ENGINES = {"Game Boy Advance", "Snes9x", "FCEUmm", "Genesis Plus GX", "Beetle PCE Fast", "FinalBurn Neo", "MAME 2003-Plus · FBNeo · MAME 2010/2015"};
    private static final String[] MODE_FILES = {".gba · .gbc · .gb · .zip", ".sfc · .smc · .zip", ".nes · .zip", ".md · .gen · .bin · .sms · .gg · .zip", ".pce · .zip (HuCard)", "ROM .zip · neogeo.zip BIOS 필요", "ROM .zip"};
    private static final String[] MODE_ICONS = {"GBA", "SFC", "FC", "MD", "PCE", "NEO", "MAME"};
    private static final int[] MODE_COLORS = {Color.rgb(22, 185, 207), Color.rgb(84, 92, 168), Color.rgb(196, 42, 52), Color.rgb(52, 58, 70), Color.rgb(232, 96, 44), Color.rgb(124, 58, 237), Color.rgb(239, 108, 53)};
    private static final String PREF_TITLE_PREFIX = "title:";
    private static final int REQUEST_EXPORT_SAVE = 1002;
    private static final int REQUEST_IMPORT_BIOS = 1004;
    private static final int REQUEST_IMPORT_MAME_BIOS = 1007;
    private static final int REQUEST_IMPORT_NEOGEO_BIOS = 1006;
    private static final int REQUEST_IMPORT_SAVE = 1003;
    private static final int REQUEST_OPEN_ARCADE_ROM = 1005;
    private static final int REQUEST_OPEN_ROM = 1001;
    private TextView biosStatusView;
    private String currentMode;
    private TextView engineStatusView;
    private TextView neoGeoBiosBanner;
    private String pendingBiosSource;
    // Mode the picked ROM folder is imported into, kept while the folder picker is open.
    private String pendingFolderMode;
    private static final String STATE_BIOS_SOURCE = "pending_bios_source";
    private TextView emptyView;
    private TextView mameBiosStatusView;
    private TextView neoGeoBiosStatusView;
    private TextView noteView;
    private File pendingSaveFile;
    private File pendingSaveRom;
    private LinearLayout romList;
    private TextView screenModeStatusView;
    // Values of the "screen_style" setting, in the order the tile cycles through them.
    private static final String[] SCREEN_STYLES = {"frame", "full"};
    private static final String[] SCREEN_MODE_LABELS = {"프레임", "전체 화면"};
    private static final int STATUS_TEXT_COLOR = Color.rgb(101, 112, 124);
    private static final byte[] GBA_LOGO = {36, -1, -82, 81, 105, -102, -94, 33, 61, -124, -126, 10, -124, -28, 9, -83, 17, 36, -117, -104, -64, -127, Byte.MAX_VALUE, 33, -93, 82, -66, 25, -109, 9, -50, 32, 16, 70, 74, 74, -8, 39, 49, -20, 88, -57, -24, 51, -126, -29, -50, -65, -123, -12, -33, -108, -50, 75, 9, -63, -108, 86, -118, -64, 19, 114, -89, -4, -97, -124, 77, 115, -93, -54, -102, 97, 88, -105, -93, 39, -4, 3, -104, 118, 35, 29, -57, 97, 3, 4, -82, 86, -65, 56, -124, 0, 64, -89, 14, -3, -1, 82, -2, 3, 111, -107, 48, -15, -105, -5, -64, -123, 96, -42, Byte.MIN_VALUE, 37, -87, 99, -66, 3, 1, 78, 56, -30, -7, -94, 52, -1, -69, 62, 3, 68, 120, 0, -112, -53, -120, 17, 58, -108, 101, -64, 124, 99, -121, -16, 60, -81, -42, 37, -28, -117, 56, 10, -84, 114, 33, -44, -8, 7};

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // Keep the open mode across rotation and other activity recreation.
        String restoredMode = bundle == null ? null : bundle.getString(STATE_MODE);
        this.pendingBiosSource = bundle == null ? null : bundle.getString(STATE_BIOS_SOURCE);
        this.pendingFolderMode = bundle == null ? null : bundle.getString(STATE_FOLDER_MODE);
        if (restoredMode != null) {
            showHome(restoredMode);
            return;
        }
        String savedMode = settingsPrefs().getString(PREF_MODE, null);
        if (savedMode != null && settingsPrefs().getBoolean(PREF_REMEMBER_MODE, false)) {
            showHome(savedMode);
        } else {
            showModePicker();
        }
        offerPlayStationCleanup();
    }

    // PlayStation mode was removed in 0.0.11; offer to free the space its games used.
    private void offerPlayStationCleanup() {
        final File games = new File(getFilesDir(), "roms_ps");
        final File saves = new File(saveDir(), "ps");
        final List<File> bios = new ArrayList<>();
        File[] systemFiles = systemDir().listFiles(file -> file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".bin") && (file.length() == 524288L || file.length() == 4194304L));
        if (systemFiles != null) {
            bios.addAll(Arrays.asList(systemFiles));
        }
        long size = folderSize(games) + folderSize(saves);
        for (File file : bios) {
            size += file.length();
        }
        if (size == 0) {
            return;
        }
        new AlertDialog.Builder(this).setTitle("PlayStation 파일 정리").setMessage("PlayStation 모드가 없어졌습니다. 남아 있는 PlayStation 게임 · BIOS · 메모리카드 파일(" + formatFileSize(size) + ")을 삭제할까요?").setNegativeButton("나중에", null).setPositiveButton("삭제", (dialog, which) -> {
            deleteRecursively(games);
            deleteRecursively(saves);
            for (File file : bios) {
                deleteRecursively(file);
            }
            showMessage("PlayStation 파일을 삭제했습니다.");
        }).show();
    }

    private static long folderSize(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long size = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                size += folderSize(child);
            }
        }
        return size;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (this.currentMode != null) {
            bundle.putString(STATE_MODE, this.currentMode);
        }
        if (this.pendingBiosSource != null) {
            bundle.putString(STATE_BIOS_SOURCE, this.pendingBiosSource);
        }
        if (this.pendingFolderMode != null) {
            bundle.putString(STATE_FOLDER_MODE, this.pendingFolderMode);
        }
    }

    @Override
    public void onBackPressed() {
        // Without "remember mode" the picker is the app's start screen, so back returns to it.
        if (this.currentMode != null && !settingsPrefs().getBoolean(PREF_REMEMBER_MODE, false)) {
            showModePicker();
            return;
        }
        super.onBackPressed();
    }

    private static int modeIndex(String mode) {
        for (int i = 0; i < MODE_IDS.length; i++) {
            if (MODE_IDS[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isModeAvailable(String mode) {
        return true;
    }

    private void clearHomeViews() {
        this.currentMode = null;
        this.biosStatusView = null;
        this.neoGeoBiosStatusView = null;
        this.mameBiosStatusView = null;
        this.engineStatusView = null;
        this.neoGeoBiosBanner = null;
        this.screenModeStatusView = null;
        this.emptyView = null;
        this.romList = null;
        this.noteView = null;
    }

    private void showModePicker() {
        clearHomeViews();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(236, 243, 248));
        TextView title = new TextView(this);
        title.setText("어떤 게임을 할까요?");
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setTextSize(22.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView desc = new TextView(this);
        desc.setText("모드를 고르면 해당 게임 목록으로 들어갑니다.");
        desc.setTextColor(STATUS_TEXT_COLOR);
        desc.setTextSize(13.0f);
        desc.setPadding(0, dp(2), 0, dp(14));
        root.addView(desc, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < MODE_IDS.length; i++) {
            final String mode = MODE_IDS[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(10));
            root.addView(createModeCard(i, view -> onModePicked(mode)), params);
        }
        CheckBox remember = new CheckBox(this);
        remember.setText("다음부터 마지막 모드로 바로 시작");
        remember.setTextColor(Color.rgb(55, 65, 81));
        remember.setTextSize(13.0f);
        remember.setChecked(settingsPrefs().getBoolean(PREF_REMEMBER_MODE, false));
        remember.setOnCheckedChangeListener((button, checked) -> settingsPrefs().edit().putBoolean(PREF_REMEMBER_MODE, checked).apply());
        root.addView(remember, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(236, 243, 248));
        scrollView.addView(root, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scrollView);
    }

    private LinearLayout createModeCard(int index, View.OnClickListener onClickListener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(0);
        card.setGravity(16);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(rounded(MODE_COLORS[index], dp(16)));
        card.setElevation(dp(2));
        card.setClickable(true);
        card.setOnClickListener(onClickListener);
        TextView icon = new TextView(this);
        icon.setText(MODE_ICONS[index]);
        icon.setGravity(17);
        icon.setTextColor(-1);
        icon.setTextSize(MODE_ICONS[index].length() > 3 ? 11.0f : 13.0f);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setBackground(rounded(Color.argb(56, 255, 255, 255), dp(14)));
        card.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView name = new TextView(this);
        name.setText(MODE_NAMES[index]);
        name.setTextColor(-1);
        name.setTextSize(16.0f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        TextView detail = new TextView(this);
        detail.setText(MODE_ENGINES[index] + "\n" + MODE_FILES[index]);
        detail.setTextColor(Color.argb(235, 255, 255, 255));
        detail.setTextSize(11.0f);
        texts.addView(detail);
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView status = new TextView(this);
        status.setText(modeStatusText(MODE_IDS[index]));
        status.setTextColor(-1);
        status.setTextSize(10.0f);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setBackground(rounded(Color.argb(64, 255, 255, 255), dp(10)));
        card.addView(status, new LinearLayout.LayoutParams(-2, -2));
        return card;
    }

    private String modeStatusText(String mode) {
        if (!isModeAvailable(mode)) {
            return "준비 중";
        }
        if ("neogeo".equals(mode) && !neoGeoBiosFile().exists()) {
            return "BIOS 필요";
        }
        return "엔진 준비됨";
    }

    private void onModePicked(String mode) {
        settingsPrefs().edit().putString(PREF_MODE, mode).apply();
        showHome(mode);
    }

    private void showHome(String mode) {
        clearHomeViews();
        int index = modeIndex(mode);
        this.currentMode = MODE_IDS[index];
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(12), dp(10), dp(12), dp(10));
        linearLayout.setBackgroundColor(Color.rgb(236, 243, 248));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setPadding(dp(14), dp(10), dp(12), dp(10));
        header.setBackground(rounded(MODE_COLORS[index], dp(8)));
        header.setElevation(dp(3));
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        TextView textView = new TextView(this);
        textView.setText("Mini Emulator");
        textView.setTextColor(-1);
        textView.setTextSize(23.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(16);
        TextView textView2 = new TextView(this);
        textView2.setText(MODE_NAMES[index] + " · " + MODE_ENGINES[index]);
        textView2.setTextColor(Color.argb(220, 255, 255, 255));
        textView2.setTextSize(12.0f);
        textView2.setPadding(0, dp(1), 0, 0);
        linearLayout2.addView(textView, new LinearLayout.LayoutParams(-1, -2));
        linearLayout2.addView(textView2, new LinearLayout.LayoutParams(-1, -2));
        header.addView(linearLayout2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView modeChip = new TextView(this);
        modeChip.setText("모드 변경 ▾");
        modeChip.setTextColor(-1);
        modeChip.setTextSize(11.0f);
        modeChip.setTypeface(Typeface.DEFAULT_BOLD);
        modeChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        modeChip.setBackground(rounded(Color.argb(64, 255, 255, 255), dp(14)));
        modeChip.setClickable(true);
        modeChip.setOnClickListener(view -> showModeSwitcher());
        header.addView(modeChip, new LinearLayout.LayoutParams(-2, -2));
        linearLayout.addView(header, new LinearLayout.LayoutParams(-1, -2));
        if (!isModeAvailable(this.currentMode)) {
            linearLayout.addView(createComingSoonView(index), new LinearLayout.LayoutParams(-1, -2));
            setContentView(linearLayout);
            return;
        }
        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(1);
        linearLayout3.setPadding(0, dp(8), 0, dp(4));
        // The four home actions sit in one row as app-style icons.
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(0);
        actionRow.setPadding(0, dp(6), 0, dp(6));
        if ("neogeo".equals(this.currentMode)) {
            actionRow.addView(createActionCard("ROM 추가", "ROM .zip", "NEO", MODE_COLORS[index], view -> showRomAddChooser()), actionTileParams());
            LinearLayout neoBiosTile = createActionCard("Neo Geo BIOS", neoGeoBiosStatusText(), "BIOS", Color.rgb(239, 84, 103), view -> showBiosChooser("neogeo"));
            this.neoGeoBiosStatusView = (TextView) neoBiosTile.getTag();
            actionRow.addView(neoBiosTile, actionTileParams());
        } else if ("arcade".equals(this.currentMode)) {
            actionRow.addView(createActionCard("ROM 추가", "ROM .zip", "MAME", MODE_COLORS[index], view -> showRomAddChooser()), actionTileParams());
            LinearLayout mameBiosTile = createActionCard("MAME BIOS", mameBiosStatus(), "BIOS", Color.rgb(239, 84, 103), view -> showBiosChooser(BIOS_SOURCE_MAME));
            this.mameBiosStatusView = (TextView) mameBiosTile.getTag();
            actionRow.addView(mameBiosTile, actionTileParams());
        } else if (isConsoleMode(this.currentMode)) {
            actionRow.addView(createActionCard("ROM 추가", consoleFor(this.currentMode)[3], MODE_ICONS[index], MODE_COLORS[index], view -> showRomAddChooser()), actionTileParams());
        } else {
            actionRow.addView(createActionCard("GBA · GBC ROM", ".gba / .gbc / .zip", "GBA", Color.rgb(34, 166, 197), view -> showRomAddChooser()), actionTileParams());
            LinearLayout biosTile = createActionCard("GBA BIOS", biosStatusText(), "BIOS", Color.rgb(239, 84, 103), view -> showBiosChooser(MODE_GBA));
            this.biosStatusView = (TextView) biosTile.getTag();
            actionRow.addView(biosTile, actionTileParams());
        }
        if ("arcade".equals(this.currentMode) || "neogeo".equals(this.currentMode)) {
            LinearLayout engineTile = createActionCard("엔진", shortCoreName(defaultCoreFor(this.currentMode)), "ENG", Color.rgb(245, 158, 11), view -> showDefaultCorePicker());
            this.engineStatusView = (TextView) engineTile.getTag();
            actionRow.addView(engineTile, actionTileParams());
        } else {
            actionRow.addView(createActionCard("ROM 검사", "기본 검사", "OK", Color.rgb(98, 111, 204), view -> checkImportedRoms()), actionTileParams());
        }
        LinearLayout screenModeTile = createActionCard("화면 모드", SCREEN_MODE_LABELS[screenMode()], "VIEW", Color.rgb(55, 188, 116), view -> cycleScreenMode(view));
        this.screenModeStatusView = (TextView) screenModeTile.getTag();
        actionRow.addView(screenModeTile, actionTileParams());
        linearLayout3.addView(actionRow, new LinearLayout.LayoutParams(-1, -2));
        if ("neogeo".equals(this.currentMode)) {
            TextView banner = new TextView(this);
            banner.setText("Neo Geo BIOS가 필요합니다. neogeo.zip을 등록해야 게임을 실행할 수 있어요.");
            banner.setTextColor(Color.rgb(76, 29, 149));
            banner.setTextSize(12.0f);
            banner.setPadding(dp(12), dp(10), dp(12), dp(10));
            banner.setBackground(rounded(Color.rgb(237, 233, 254), dp(12)));
            this.neoGeoBiosBanner = banner;
            linearLayout3.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView textView3 = new TextView(this);
        this.emptyView = textView3;
        textView3.setText("아직 가져온 ROM이 없습니다.");
        this.emptyView.setGravity(17);
        this.emptyView.setTextSize(16.0f);
        this.emptyView.setTextColor(Color.rgb(99, 110, 122));
        LinearLayout linearLayout7 = new LinearLayout(this);
        this.romList = linearLayout7;
        linearLayout7.setOrientation(1);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(this.romList, new FrameLayout.LayoutParams(-1, -2));
        TextView textView4 = new TextView(this);
        this.noteView = textView4;
        textView4.setGravity(17);
        this.noteView.setTextSize(13.0f);
        this.noteView.setTextColor(Color.rgb(93, 103, 112));
        this.noteView.setPadding(dp(8), dp(8), dp(8), 0);
        linearLayout.addView(linearLayout3, new LinearLayout.LayoutParams(-1, -2));
        linearLayout.addView(this.emptyView, new LinearLayout.LayoutParams(-1, dp(58)));
        linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        linearLayout.addView(this.noteView, new LinearLayout.LayoutParams(-1, -2));
        setContentView(linearLayout);
        updateHomeStatusCards();
        updateNote();
        refreshLibrary();
    }

    private View createComingSoonView(int index) {
        TextView view = new TextView(this);
        view.setText(MODE_NAMES[index] + " 모드는 준비 중입니다.\n" + MODE_ENGINES[index] + " 엔진이 다음 업데이트에서 추가됩니다.\n\n위의 ‘모드 변경’을 눌러 GBA로 돌아갈 수 있습니다.");
        view.setGravity(17);
        view.setTextSize(14.0f);
        view.setTextColor(Color.rgb(99, 110, 122));
        view.setLineSpacing(0.0f, 1.25f);
        view.setPadding(dp(16), dp(48), dp(16), dp(16));
        return view;
    }

    private void showModeSwitcher() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(1);
        sheet.setPadding(dp(14), dp(16), dp(14), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(-1);
        float radius = dp(20);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0.0f, 0.0f, 0.0f, 0.0f});
        sheet.setBackground(background);
        TextView title = new TextView(this);
        title.setText("모드 변경");
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setTextSize(16.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(6), 0, 0, dp(10));
        sheet.addView(title);
        for (int i = 0; i < MODE_IDS.length; i++) {
            final String mode = MODE_IDS[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(0);
            row.setGravity(16);
            row.setPadding(dp(6), dp(9), dp(6), dp(9));
            if (mode.equals(this.currentMode)) {
                row.setBackground(rounded(Color.rgb(224, 247, 251), dp(10)));
            }
            TextView icon = new TextView(this);
            icon.setText(MODE_ICONS[i]);
            icon.setGravity(17);
            icon.setTextColor(-1);
            icon.setTextSize(9.0f);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setBackground(rounded(MODE_COLORS[i], dp(9)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView name = new TextView(this);
            name.setText(MODE_NAMES[i]);
            name.setTextColor(Color.rgb(31, 41, 51));
            name.setTextSize(14.0f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setPadding(dp(10), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
            TextView status = new TextView(this);
            status.setText(isModeAvailable(mode) ? "게임 " + romCount(mode) + "개" : "준비 중");
            status.setTextColor(STATUS_TEXT_COLOR);
            status.setTextSize(11.0f);
            row.addView(status);
            row.setClickable(true);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onModePicked(mode);
            });
            sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(-1, -2);
            window.setGravity(80);
        }
        dialog.show();
    }

    private int romCount(String mode) {
        File[] files = romDirFor(mode).listFiles(this::isRomFile);
        return files == null ? 0 : files.length;
    }

    private String emptyLibraryText() {
        if ("neogeo".equals(this.currentMode)) {
            return "아직 가져온 Neo Geo 게임이 없습니다.";
        }
        if ("arcade".equals(this.currentMode)) {
            return "아직 가져온 아케이드 게임이 없습니다.";
        }
        if (isConsoleMode(this.currentMode)) {
            return "아직 가져온 " + MODE_NAMES[modeIndex(this.currentMode)] + " 게임이 없습니다.";
        }
        return "아직 가져온 ROM이 없습니다.";
    }

    private void cycleScreenMode(View view) {
        int length = (screenMode() + 1) % SCREEN_STYLES.length;
        String[] strArr = SCREEN_MODE_LABELS;
        settingsPrefs().edit().putString("screen_style", SCREEN_STYLES[length]).commit();
        Object tag = view.getTag();
        if (tag instanceof TextView) {
            ((TextView) tag).setText(strArr[length]);
        }
        showMessage("화면 모드: " + strArr[length]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHomeStatusCards();
        updateNote();
        refreshLibrary();
        showLastCrashReport();
    }

    // The native bridge writes this file when a game's engine crashes.
    private void showLastCrashReport() {
        File file = new File(getFilesDir(), "last_crash.txt");
        if (!file.isFile()) {
            return;
        }
        String report;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 32768)];
            int length = in.read(data);
            report = new String(data, 0, Math.max(length, 0), "UTF-8");
        } catch (IOException e) {
            report = "";
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
        if (report.trim().isEmpty()) {
            return;
        }
        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            version = "?";
        }
        final String text = "Mini Emulator " + version + "\n" + report;
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11.0f);
        view.setTextIsSelectable(true);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("게임이 비정상 종료됐습니다").setMessage("엔진이 멈춘 위치를 기록했습니다. 아래 내용을 복사해서 보내 주시면 원인을 찾는 데 도움이 됩니다.").setView(scroll).setNegativeButton("닫기", null).setPositiveButton("복사", (dialog, which) -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Mini Emulator crash", text));
                showMessage("복사했습니다.");
            }
        }).show();
    }

    private void updateHomeStatusCards() {
        TextView textView = this.biosStatusView;
        if (textView != null) {
            textView.setText(biosStatusText());
            // Missing BIOS is highlighted so it stands out in the icon row.
            textView.setTextColor(biosFile().exists() ? STATUS_TEXT_COLOR : Color.rgb(239, 84, 103));
        }
        TextView textView2 = this.neoGeoBiosStatusView;
        if (textView2 != null) {
            textView2.setText(neoGeoBiosStatusText());
            textView2.setTextColor(neoGeoBiosFile().exists() ? STATUS_TEXT_COLOR : Color.rgb(239, 84, 103));
        }
        if (this.neoGeoBiosBanner != null) {
            this.neoGeoBiosBanner.setVisibility(neoGeoBiosFile().exists() ? 8 : 0);
        }
        if (this.engineStatusView != null) {
            this.engineStatusView.setText(shortCoreName(defaultCoreFor(this.currentMode)));
        }
        TextView textView3 = this.mameBiosStatusView;
        if (textView3 != null) {
            textView3.setText(mameBiosStatus());
        }
        TextView textView4 = this.screenModeStatusView;
        if (textView4 != null) {
            textView4.setText(SCREEN_MODE_LABELS[screenMode()]);
        }
    }

    private LinearLayout createActionCard(String title, String status, String iconLabel, int iconColor, View.OnClickListener onClickListener) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(1);
        tile.setGravity(49);
        tile.setPadding(0, dp(4), 0, dp(4));
        tile.setClickable(true);
        tile.setOnClickListener(onClickListener);
        TextView icon = new TextView(this);
        icon.setText(iconLabel);
        icon.setGravity(17);
        icon.setTextColor(-1);
        icon.setTextSize(iconLabel.length() > 3 ? 12.0f : 14.0f);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setBackground(rounded(iconColor, dp(16)));
        icon.setElevation(dp(2));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconParams.setMargins(0, dp(2), 0, dp(6));
        tile.addView(icon, iconParams);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setGravity(17);
        titleView.setTextColor(Color.rgb(55, 65, 76));
        titleView.setTextSize(12.0f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        tile.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        TextView statusView = new TextView(this);
        statusView.setText(status);
        statusView.setGravity(17);
        statusView.setTextColor(STATUS_TEXT_COLOR);
        statusView.setTextSize(10.0f);
        statusView.setSingleLine(true);
        tile.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        tile.setTag(statusView);
        return tile;
    }

    private LinearLayout.LayoutParams actionTileParams() {
        return new LinearLayout.LayoutParams(0, -2, 1.0f);
    }

    private String neoGeoBiosStatusText() {
        return neoGeoBiosFile().exists() ? "등록됨" : "등록 안 됨";
    }

    private String shortCoreName(String core) {
        if ("fbneo".equals(core)) {
            return "FBNeo";
        }
        if ("mame2015".equals(core)) {
            return "MAME 2015";
        }
        return "mame2010".equals(core) ? "MAME 2010" : "MAME 2003+";
    }

    private String biosStatusText() {
        return biosFile().exists() ? "등록됨" : "등록 안 됨";
    }

    private LinearLayout.LayoutParams biosGridParams(int i) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, dp(94), 1.0f);
        int dp = dp(3);
        int i2 = i == 0 ? 0 : dp;
        if (i == 2) {
            dp = 0;
        }
        layoutParams.setMargins(i2, 0, dp, dp(8));
        return layoutParams;
    }

    private LinearLayout createWideActionCard(String str, String str2, String str3, int i, int i2, View.OnClickListener onClickListener) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(18), dp(10), dp(18), dp(10));
        linearLayout.setBackground(rounded(i, dp(8)));
        linearLayout.setClickable(true);
        linearLayout.setOnClickListener(onClickListener);
        linearLayout.setElevation(dp(2));
        TextView textView = new TextView(this);
        textView.setText(str3);
        textView.setGravity(17);
        textView.setTextColor(-1);
        textView.setTextSize(9.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setBackground(rounded(i2, dp(21)));
        linearLayout.addView(textView, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(dp(14), 0, 0, 0);
        TextView textView2 = new TextView(this);
        textView2.setText(str);
        textView2.setTextColor(Color.rgb(55, 65, 76));
        textView2.setTextSize(14.0f);
        textView2.setTypeface(Typeface.DEFAULT_BOLD);
        linearLayout2.addView(textView2);
        TextView textView3 = new TextView(this);
        textView3.setText(str2);
        textView3.setTextColor(Color.rgb(101, 112, 124));
        textView3.setTextSize(12.0f);
        linearLayout2.addView(textView3);
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(0, -2, 1.0f));
        linearLayout.setTag(textView3);
        return linearLayout;
    }

    private void openRomPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/octet-stream", "application/x-gba-rom", "application/x-gameboy-advance-rom", "application/x-gameboy-color-rom", "application/x-gameboy-rom", "application/zip"});
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        startActivityForResult(intent, 1001);
    }

    private void openConsoleRomPicker(String mode) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        startActivityForResult(intent, Integer.parseInt(consoleFor(mode)[5]));
    }

    private void openArcadeRomPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("application/zip");
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        startActivityForResult(intent, 1005);
    }

    private void openBiosPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/octet-stream", "application/x-bios"});
        startActivityForResult(intent, 1004);
    }

    private void openNeoGeoBiosPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        startActivityForResult(intent, 1006);
    }

    private void openMameBiosPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("application/zip");
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        startActivityForResult(intent, 1007);
    }

    private void showMameBiosManager() {
        final AlertDialog[] alertDialogArr = new AlertDialog[1];
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(20), dp(4), dp(20), dp(8));
        TextView textView = new TextView(this);
        textView.setText("등록 상태: " + mameBiosStatus());
        textView.setTextColor(Color.rgb(205, 211, 218));
        textView.setTextSize(13.0f);
        int i = 10;
        textView.setPadding(0, dp(8), 0, dp(10));
        linearLayout.addView(textView);
        File[] listFiles = mameBiosDir().listFiles(MainActivity::isZipFile);
        if (listFiles == null || listFiles.length == 0) {
            TextView textView2 = new TextView(this);
            textView2.setText("아직 등록된 MAME BIOS가 없습니다.");
            textView2.setTextColor(Color.rgb(190, 197, 204));
            textView2.setTextSize(14.0f);
            textView2.setGravity(17);
            textView2.setPadding(0, dp(24), 0, dp(24));
            linearLayout.addView(textView2);
        } else {
            Arrays.sort(listFiles, Comparator.comparing((File f) -> f.getName().toLowerCase(Locale.US)));
            int length = listFiles.length;
            int i2 = 0;
            while (i2 < length) {
                final File file = listFiles[i2];
                LinearLayout linearLayout2 = new LinearLayout(this);
                linearLayout2.setOrientation(1);
                linearLayout2.setPadding(0, dp(i), 0, dp(i));
                TextView textView3 = new TextView(this);
                textView3.setText(file.getName());
                textView3.setTextColor(Color.rgb(242, 244, 247));
                textView3.setTextSize(15.0f);
                textView3.setTypeface(Typeface.DEFAULT_BOLD);
                linearLayout2.addView(textView3);
                TextView textView4 = new TextView(this);
                textView4.setText(mameBiosDetail(file));
                textView4.setTextColor(Color.rgb(195, 202, 210));
                textView4.setTextSize(12.0f);
                textView4.setPadding(0, dp(2), 0, 0);
                linearLayout2.addView(textView4);
                linearLayout2.setOnLongClickListener(view -> onMameBiosLongClick(file, alertDialogArr, view));
                linearLayout.addView(linearLayout2);
                View view = new View(this);
                view.setBackgroundColor(Color.rgb(105, 110, 116));
                linearLayout.addView(view, new LinearLayout.LayoutParams(-1, dp(1)));
                i2++;
                i = 10;
            }
        }
        TextView textView5 = new TextView(this);
        textView5.setText("기판별 BIOS ZIP을 원본 파일명 그대로 등록하세요.");
        textView5.setTextColor(Color.rgb(185, 192, 200));
        textView5.setTextSize(12.0f);
        textView5.setPadding(0, dp(12), 0, dp(4));
        linearLayout.addView(textView5);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(linearLayout, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog create = new AlertDialog.Builder(this).setTitle("MAME BIOS 관리").setView(scrollView).setNegativeButton("닫기", (DialogInterface.OnClickListener) null).setPositiveButton("BIOS 가져오기", (dialogInterface, i3) -> openMameBiosPicker()).create();
        alertDialogArr[0] = create;
        create.show();
    }

    private static boolean isZipFile(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".zip");
    }

    private boolean onMameBiosLongClick(File file, AlertDialog[] alertDialogArr, View view) {
        confirmDeleteMameBios(file, alertDialogArr[0]);
        return true;
    }

    private void confirmDeleteMameBios(final File file, final AlertDialog alertDialog) {
        new AlertDialog.Builder(this).setTitle("MAME BIOS 삭제").setMessage(file.getName() + "을(를) 삭제할까요?").setNegativeButton("취소", (DialogInterface.OnClickListener) null).setPositiveButton("삭제", (dialogInterface, i) -> deleteMameBiosConfirmed(file, alertDialog, dialogInterface, i)).show();
    }

    private void deleteMameBiosConfirmed(File file, AlertDialog alertDialog, DialogInterface dialogInterface, int i) {
        if (deleteMameBios(file)) {
            showMessage("MAME BIOS를 삭제했습니다.");
            updateHomeStatusCards();
            updateNote();
            if (alertDialog != null) {
                alertDialog.dismiss();
            }
            showMameBiosManager();
            return;
        }
        showError("MAME BIOS를 삭제할 수 없습니다.");
    }

    private boolean deleteMameBios(File file) {
        if (file.getName().equalsIgnoreCase("stvbios.zip")) {
            removeMergedBiosEntries(new File(arcadeRomDir(), "twcup98.zip"), file);
            File file2 = new File(arcadeRomDir(), "stvbios.zip");
            if (file2.exists() && !file2.delete()) {
                return false;
            }
        }
        return !file.exists() || file.delete();
    }

    @Override
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i2 != -1 || intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (i == REQUEST_OPEN_ROM_FOLDER) {
            String mode = this.pendingFolderMode;
            this.pendingFolderMode = null;
            if (data != null && mode != null) {
                scanRomFolder(data, mode);
            }
            return;
        }
        if (i == REQUEST_OPEN_BIOS_FOLDER) {
            String source = this.pendingBiosSource;
            this.pendingBiosSource = null;
            if (data != null) {
                importBiosFolder(data, source);
            }
            return;
        }
        if (i == 1001) {
            importRoms(intent, MODE_GBA);
            return;
        }
        for (String[] console : CONSOLES) {
            if (i == Integer.parseInt(console[5])) {
                importRoms(intent, console[0]);
                return;
            }
        }
        if (i == 1005) {
            importArcadeRoms(intent);
            return;
        }
        if (i == 1002) {
            if (data == null) {
                return;
            }
            exportSave(data);
            return;
        }
        if (i == 1003) {
            if (data == null) {
                return;
            }
            importSave(data);
        } else if (i == 1004) {
            if (data == null) {
                return;
            }
            importBios(data);
        } else if (i == 1006) {
            if (data != null) {
                importNeoGeoBios(data);
            }
        } else if (i == 1007) {
            importMameBios(intent);
        }
    }

    private void importRoms(Intent intent, String mode) {
        List<File> singletonOrEmpty;
        ArrayList<Uri> arrayList = new ArrayList();
        ClipData clipData = intent.getClipData();
        int i = 0;
        if (clipData != null) {
            for (int i2 = 0; i2 < clipData.getItemCount(); i2++) {
                Uri uri = clipData.getItemAt(i2).getUri();
                if (uri != null) {
                    arrayList.add(uri);
                }
            }
        } else if (intent.getData() != null) {
            arrayList.add(intent.getData());
        }
        if (arrayList.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        File file = null;
        int i3 = 0;
        for (Uri uri2 : arrayList) {
            String displayNameFor = displayNameFor(uri2);
            String lowerCase = displayNameFor.toLowerCase(Locale.US);
            if (!isRomNameFor(mode, lowerCase) && !lowerCase.endsWith(".zip")) {
                sb.append(displayNameFor).append(": 지원하지 않는 파일\n");
            } else {
                try {
                    if (lowerCase.endsWith(".zip")) {
                        singletonOrEmpty = copyZippedRomsToAppStorage(uri2, displayNameFor, mode);
                    } else {
                        singletonOrEmpty = singletonOrEmpty(copyRomToAppStorage(uri2, displayNameFor, mode));
                    }
                    if (singletonOrEmpty.isEmpty()) {
                        i3++;
                    }
                    for (File file2 : singletonOrEmpty) {
                        i++;
                        if (file == null) {
                            file = file2;
                        }
                    }
                } catch (IOException e) {
                    sb.append(displayNameFor).append(": ").append(e.getMessage()).append('\n');
                }
            }
        }
        refreshLibrary();
        String str = "가져오기 완료: " + i + "개";
        if (i3 > 0) {
            str = str + "  |  중복 건너뜀: " + i3 + "개";
        }
        if (sb.length() > 0) {
            showError(str + "\n" + ((Object) sb));
        } else {
            showMessage(str);
        }
        if (arrayList.size() == 1 && i == 1 && file != null) {
            launchGame(file);
        }
    }

    private void importArcadeRoms(Intent intent) {
        ArrayList<Uri> arrayList = new ArrayList();
        ClipData clipData = intent.getClipData();
        int i = 0;
        if (clipData != null) {
            for (int i2 = 0; i2 < clipData.getItemCount(); i2++) {
                Uri uri = clipData.getItemAt(i2).getUri();
                if (uri != null) {
                    arrayList.add(uri);
                }
            }
        } else if (intent.getData() != null) {
            arrayList.add(intent.getData());
        }
        StringBuilder sb = new StringBuilder();
        int i3 = 0;
        for (Uri uri2 : arrayList) {
            String displayNameFor = displayNameFor(uri2);
            if (displayNameFor.equalsIgnoreCase("neogeo.zip")) {
                importNeoGeoBios(uri2);
            } else if (displayNameFor.toLowerCase(Locale.US).endsWith(".zip") && RomsetIndex.isBiosName(this, baseNameWithoutExtension(displayNameFor.toLowerCase(Locale.US)))) {
                // Board BIOS archives picked as ROMs go to the MAME BIOS folder.
                try {
                    copyUriToFile(uri2, new File(mameBiosDir(), sanitizeFileName(displayNameFor).toLowerCase(Locale.US)));
                    sb.append(displayNameFor).append(": 기판 BIOS라서 MAME BIOS로 등록했습니다\n");
                } catch (IOException e) {
                    sb.append(displayNameFor).append(": ").append(e.getMessage()).append('\n');
                }
            } else if (!displayNameFor.toLowerCase(Locale.US).endsWith(".zip")) {
                sb.append(displayNameFor).append(": 아케이드 ROM은 ZIP 파일이어야 합니다.\n");
            } else {
                try {
                    File imported = copyArcadeRomToAppStorage(uri2, displayNameFor);
                    if (imported == null) {
                        i3++;
                    } else {
                        i++;
                        RomsetResult romset = RomsetIndex.isAvailable(this) ? resolveRomset(imported) : null;
                        if (romset == null) {
                            continue;
                        }
                        if (romset.match == null) {
                            sb.append(displayNameFor).append(": 엔진이 알아볼 수 없는 ROM입니다 (지원하지 않는 게임이거나 일부 파일만 있음)\n");
                        } else if (romset.renameConflict != null) {
                            sb.append(displayNameFor).append(": 같은 게임(").append(romset.renameConflict).append(")이 이미 있습니다\n");
                        }
                    }
                } catch (IOException e) {
                    sb.append(displayNameFor).append(": ").append(e.getMessage()).append('\n');
                }
            }
        }
        refreshLibrary();
        String str = "아케이드 ROM 가져오기 완료: " + i + "개";
        if (i3 > 0) {
            str = str + "  |  중복 건너뜀: " + i3 + "개";
        }
        if (sb.length() > 0) {
            showError(str + "\n" + ((Object) sb));
        } else {
            showMessage(str);
        }
    }

    private void importBios(Uri uri) {
        try {
            File systemDir = systemDir();
            if (!systemDir.exists() && !systemDir.mkdirs()) {
                throw new IOException("BIOS 폴더를 만들 수 없습니다");
            }
            File file = new File(systemDir, "gba_bios.bin");
            try (InputStream openInputStream = getContentResolver().openInputStream(uri);
                 FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                if (openInputStream == null) {
                    throw new IOException("선택한 BIOS 파일을 열 수 없습니다");
                }
                copy(openInputStream, fileOutputStream);
            }
            if (file.length() != 16384) {
                if (!file.delete()) {
                    file.deleteOnExit();
                }
                throw new IOException("GBA BIOS는 16KB 파일이어야 합니다.");
            }
            showMessage("GBA BIOS를 가져왔습니다.");
            updateHomeStatusCards();
            updateNote();
        } catch (IOException e) {
            showError("BIOS 가져오기 실패: " + e.getMessage());
        }
    }

    private void importNeoGeoBios(Uri uri) {
        try {
            File systemDir = systemDir();
            if (!systemDir.exists() && !systemDir.mkdirs()) {
                throw new IOException("BIOS 폴더를 만들 수 없습니다");
            }
            File neoGeoBiosFile = neoGeoBiosFile();
            try (InputStream openInputStream = getContentResolver().openInputStream(uri);
                 FileOutputStream fileOutputStream = new FileOutputStream(neoGeoBiosFile)) {
                if (openInputStream == null) {
                    throw new IOException("선택한 BIOS 파일을 열 수 없습니다");
                }
                copy(openInputStream, fileOutputStream);
            }
            if (neoGeoBiosFile.length() == 0) {
                if (!neoGeoBiosFile.delete()) {
                    neoGeoBiosFile.deleteOnExit();
                }
                throw new IOException("비어 있는 BIOS 파일입니다");
            }
            File romDir = arcadeRomDir();
            if (!romDir.exists() && !romDir.mkdirs()) {
                throw new IOException("ROM 폴더를 만들 수 없습니다");
            }
            prepareNeoGeoBiosForArcade(neoGeoBiosFile, new File(romDir, "neogeo.zip"));
            showMessage("Neo Geo BIOS를 가져왔습니다.");
            updateHomeStatusCards();
            updateNote();
        } catch (IOException e) {
            showError("Neo Geo BIOS 가져오기 실패: " + e.getMessage());
        }
    }

    private void importMameBios(Intent intent) {
        ArrayList<Uri> arrayList = new ArrayList();
        ClipData clipData = intent.getClipData();
        int i = 0;
        if (clipData != null) {
            for (int i2 = 0; i2 < clipData.getItemCount(); i2++) {
                Uri uri = clipData.getItemAt(i2).getUri();
                if (uri != null) {
                    arrayList.add(uri);
                }
            }
        } else if (intent.getData() != null) {
            arrayList.add(intent.getData());
        }
        StringBuilder sb = new StringBuilder();
        File mameBiosDir = mameBiosDir();
        if (!mameBiosDir.exists() && !mameBiosDir.mkdirs()) {
            showError("MAME BIOS 폴더를 만들 수 없습니다.");
            return;
        }
        for (Uri uri2 : arrayList) {
            String sanitizeFileName = sanitizeFileName(displayNameFor(uri2));
            if (!sanitizeFileName.toLowerCase(Locale.US).endsWith(".zip")) {
                sb.append(sanitizeFileName).append(": BIOS는 ZIP 파일이어야 합니다.\n");
            } else {
                File file = new File(mameBiosDir, sanitizeFileName + ".import");
                File file2 = new File(mameBiosDir, sanitizeFileName);
                try {
                    try (InputStream openInputStream = getContentResolver().openInputStream(uri2);
                         FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                        if (openInputStream == null) {
                            throw new IOException("선택한 파일을 열 수 없습니다");
                        }
                        copy(openInputStream, fileOutputStream);
                    }
                    if (!isReadableZip(file)) {
                        throw new IOException("올바른 ZIP 파일이 아닙니다");
                    }
                    if (sanitizeFileName.equalsIgnoreCase("stvbios.zip") && file2.exists()) {
                        removeMergedBiosEntries(new File(arcadeRomDir(), "twcup98.zip"), file2);
                    }
                    if (!copyFile(file, file2)) {
                        throw new IOException("BIOS를 저장할 수 없습니다");
                    }
                    if (sanitizeFileName.equalsIgnoreCase("stvbios.zip")) {
                        File romDir = arcadeRomDir();
                        if (!romDir.exists() && !romDir.mkdirs()) {
                            throw new IOException("ROM 폴더를 만들 수 없습니다");
                        }
                        if (!copyFile(file2, new File(romDir, "stvbios.zip"))) {
                            throw new IOException("ST-V BIOS를 코어 폴더에 준비할 수 없습니다");
                        }
                    }
                    i++;
                } catch (IOException e) {
                    sb.append(sanitizeFileName).append(": ").append(e.getMessage()).append('\n');
                } finally {
                    if (file.exists() && !file.delete()) {
                        file.deleteOnExit();
                    }
                }
            }
        }
        migrateKnownArcadeRoms();
        updateHomeStatusCards();
        updateNote();
        String str = "MAME BIOS 등록 완료: " + i + "개";
        if (sb.length() > 0) {
            showError(str + "\n" + ((Object) sb));
        } else {
            showMessage(str);
        }
    }

    // ---- Folder imports ----

    private static final String BIOS_SOURCE_MAME = "mame";

    private void openFolderPicker(int requestCode) {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT_TREE");
        try {
            startActivityForResult(intent, requestCode);
        } catch (android.content.ActivityNotFoundException e) {
            showError("폴더를 고를 수 있는 앱이 없습니다.");
        }
    }

    // BIOS tiles offer a single file or a whole folder; a folder is sorted by file type.
    // The card opens with what is registered so far.
    private void showBiosChooser(String source) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(rounded(-1, dp(24)));
        boolean mame = BIOS_SOURCE_MAME.equals(source);
        boolean neoGeo = "neogeo".equals(source);
        TextView title = new TextView(this);
        title.setText(mame ? "MAME BIOS" : neoGeo ? "Neo Geo BIOS" : "GBA BIOS");
        title.setTextSize(17.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(title);

        // Status: green when ready, amber when something is missing, red when nothing is there.
        int state;
        String headline;
        String note = null;
        int count = 0;
        if (mame) {
            File[] files = mameBiosDir().listFiles(MainActivity::isZipFile);
            count = files == null ? 0 : files.length;
            String status = mameBiosStatus();
            if (count == 0) {
                state = 2;
                headline = "등록된 BIOS가 없어요";
                note = "BIOS가 필요한 기판 게임은 실행되지 않아요";
            } else {
                headline = "BIOS " + count + "개 등록됨";
                state = status.startsWith("ST-V 불완전") ? 1 : 0;
                if (state == 1) {
                    note = "ST-V (stvbios.zip) 파일 일부 부족";
                }
            }
        } else if (neoGeo) {
            boolean ok = neoGeoBiosFile().exists();
            state = ok ? 0 : 2;
            headline = ok ? "Neo Geo BIOS 등록됨" : "Neo Geo BIOS가 없어요";
            note = ok ? "neogeo.zip" : "Neo Geo 게임을 실행하려면 neogeo.zip이 필요해요";
        } else {
            boolean ok = biosFile().exists();
            state = ok ? 0 : 1;
            headline = ok ? "GBA BIOS 등록됨" : "GBA BIOS가 없어요";
            note = ok ? "gba_bios.bin" : "없어도 대부분의 게임은 실행돼요";
        }
        int[] fills = {Color.rgb(240, 253, 244), Color.rgb(255, 247, 237), Color.rgb(254, 242, 242)};
        int[] icons = {Color.rgb(34, 179, 90), Color.rgb(245, 158, 11), Color.rgb(229, 72, 77)};
        int[] heads = {Color.rgb(20, 83, 45), Color.rgb(124, 45, 18), Color.rgb(127, 29, 29)};
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(0);
        statusCard.setGravity(16);
        statusCard.setPadding(dp(12), dp(11), dp(12), dp(11));
        statusCard.setBackground(rounded(fills[state], dp(14)));
        TextView mark = new TextView(this);
        mark.setText(state == 0 ? "✓" : "!");
        mark.setTextColor(-1);
        mark.setTextSize(15.0f);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setGravity(17);
        mark.setBackground(rounded(icons[state], dp(15)));
        statusCard.addView(mark, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(1);
        TextView head = new TextView(this);
        head.setText(headline);
        head.setTextSize(13.5f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(heads[state]);
        statusTexts.addView(head);
        if (note != null) {
            TextView noteView = new TextView(this);
            noteView.setText(note);
            noteView.setTextSize(11.5f);
            noteView.setTextColor(state == 0 ? Color.rgb(77, 124, 96) : Color.rgb(180, 83, 9));
            statusTexts.addView(noteView);
        }
        LinearLayout.LayoutParams statusTextsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        statusTextsParams.setMargins(dp(10), 0, 0, 0);
        statusCard.addView(statusTexts, statusTextsParams);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(12), 0, dp(10));
        panel.addView(statusCard, statusParams);

        LinearLayout tiles = new LinearLayout(this);
        int column = 0;
        int last = mame ? 2 : 1;
        if (mame) {
            tiles.addView(createActionTile(dialog, RoundIconView.MULTI, Color.rgb(239, 84, 103), "관리", count + "개", false, this::showMameBiosManager), gridParams(column++, last));
        }
        tiles.addView(createActionTile(dialog, RoundIconView.CARD, RoundIconView.COLOR_LOAD, "파일", null, false, () -> openSingleBiosPicker(source)), gridParams(column++, last));
        tiles.addView(createActionTile(dialog, RoundIconView.FOLDER, RoundIconView.COLOR_SAVE, "폴더째", null, false, () -> {
            this.pendingBiosSource = source;
            openFolderPicker(REQUEST_OPEN_BIOS_FOLDER);
        }), gridParams(column, last));
        panel.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(createCancelButton(dialog, "취소"));
        showCardDialog(dialog, panel, -2);
    }

    private LinearLayout.LayoutParams gridParams(int column, int last) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
        params.setMargins(column == 0 ? 0 : dp(4), 0, column == last ? 0 : dp(4), 0);
        return params;
    }

    private TextView createCancelButton(Dialog dialog, String label) {
        TextView cancel = new TextView(this);
        cancel.setText(label);
        cancel.setTextSize(14.0f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Color.rgb(75, 85, 99));
        cancel.setGravity(17);
        cancel.setBackground(rounded(Color.rgb(238, 240, 243), dp(13)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.setMargins(0, dp(12), 0, 0);
        cancel.setLayoutParams(params);
        return cancel;
    }

    private void openSingleBiosPicker(String source) {
        if ("neogeo".equals(source)) {
            openNeoGeoBiosPicker();
        } else if (BIOS_SOURCE_MAME.equals(source)) {
            openMameBiosPicker();
        } else {
            openBiosPicker();
        }
    }

    // ---- ROM folder import ----

    // "ROM 추가": pick files as before, or a whole folder to preview and pick from.
    private void showRomAddChooser() {
        final String mode = this.currentMode;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(rounded(-1, dp(24)));
        TextView title = new TextView(this);
        title.setText("ROM 추가");
        title.setTextSize(17.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setPadding(dp(4), 0, dp(4), dp(2));
        panel.addView(title);
        String files = isConsoleMode(mode) ? consoleFor(mode)[3] : MODE_GBA.equals(mode) ? ".gba / .gbc / .gb / .zip" : "ZIP";
        panel.addView(createChoiceRow(RoundIconView.CARD, MODE_COLORS[modeIndex(mode)], "파일 고르기", files + " 파일을 하나 또는 여러 개 골라서 가져오기", () -> {
            dialog.dismiss();
            openRomFilePicker(mode);
        }));
        panel.addView(createChoiceRow(RoundIconView.FOLDER, RoundIconView.COLOR_SAVE, "폴더째로 가져오기", "폴더를 고르면 안의 게임을 보여 주고 골라서 가져오기 · 하위 폴더 포함", () -> {
            dialog.dismiss();
            this.pendingFolderMode = mode;
            openFolderPicker(REQUEST_OPEN_ROM_FOLDER);
        }));
        TextView cancel = new TextView(this);
        cancel.setText("취소");
        cancel.setTextSize(14.0f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Color.rgb(75, 85, 99));
        cancel.setGravity(17);
        cancel.setBackground(rounded(Color.rgb(238, 240, 243), dp(13)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, dp(44));
        cancelParams.setMargins(0, dp(12), 0, 0);
        panel.addView(cancel, cancelParams);
        showCardDialog(dialog, panel, -2);
    }

    private void openRomFilePicker(String mode) {
        if (isConsoleMode(mode)) {
            openConsoleRomPicker(mode);
        } else if (MODE_GBA.equals(mode)) {
            openRomPicker();
        } else {
            openArcadeRomPicker();
        }
    }

    private void showCardDialog(Dialog dialog, View content, int height) {
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), height);
        }
        dialog.show();
    }

    private LinearLayout createChoiceRow(int icon, int color, String label, String sub, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        int normal = Color.rgb(246, 247, 249);
        row.setBackground(rounded(normal, dp(16)));
        row.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(15.0f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.rgb(31, 41, 51));
        texts.addView(name);
        TextView detail = new TextView(this);
        detail.setText(sub);
        detail.setTextSize(12.0f);
        detail.setTextColor(Color.rgb(122, 130, 140));
        texts.addView(detail);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(14), 0, 0, 0);
        row.addView(texts, textsParams);
        pressable(row, normal, Color.rgb(228, 231, 236), dp(16));
        row.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    // A file found in the picked folder, as shown in the preview list.
    private static final class FolderItem {
        final PickedFile file;
        final String title;
        final String detail;
        final boolean present;
        boolean selected;

        FolderItem(PickedFile file, String title, String detail, boolean present) {
            this.file = file;
            this.title = title;
            this.detail = detail;
            this.present = present;
            this.selected = !present;
        }
    }

    private boolean isFolderCandidate(String mode, String lowerCaseName) {
        if (isConsoleMode(mode) || MODE_GBA.equals(mode)) {
            return isRomNameFor(mode, lowerCaseName) || lowerCaseName.endsWith(".zip");
        }
        return lowerCaseName.endsWith(".zip");
    }

    private boolean isArcadeBiosName(String lowerCaseName) {
        return lowerCaseName.equals("neogeo.zip") || Arrays.asList(KNOWN_ARCADE_BIOS).contains(lowerCaseName)
                || RomsetIndex.isBiosName(this, baseNameWithoutExtension(lowerCaseName));
    }

    // Lists the folder off the UI thread, then shows what it found.
    @SuppressWarnings("deprecation")
    private void scanRomFolder(Uri treeUri, String mode) {
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle("폴더 살펴보는 중");
        progress.setMessage("파일 목록을 읽고 있습니다…");
        progress.setCancelable(false);
        progress.show();
        new Thread(() -> {
            List<FolderItem> items = new ArrayList<>();
            String error = null;
            try {
                boolean arcade = isArcadeMode(mode);
                File romDir = romDirFor(mode);
                for (PickedFile picked : listTree(treeUri)) {
                    String lower = picked.name.toLowerCase(Locale.US);
                    if (!isFolderCandidate(mode, lower)) {
                        continue;
                    }
                    String base = baseNameWithoutExtension(picked.name);
                    String size = picked.size > 0 ? formatSize(picked.size) : "";
                    if (arcade && isArcadeBiosName(lower)) {
                        items.add(new FolderItem(picked, picked.name, "BIOS · BIOS로 등록", false));
                        continue;
                    }
                    boolean present = new File(romDir, sanitizeFileName(picked.name)).exists();
                    String title = base;
                    if (arcade) {
                        String known = GameTitles.lookup(this, base);
                        if (known != null) {
                            title = known;
                        }
                    }
                    String detail = picked.name + (present ? " · 이미 있음" : size.isEmpty() ? "" : " · " + size);
                    items.add(new FolderItem(picked, title, detail, present));
                }
                Collections.sort(items, (a, b) -> a.present != b.present ? (a.present ? 1 : -1) : a.title.compareToIgnoreCase(b.title));
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progress.dismiss();
                if (failure != null) {
                    showError("폴더를 읽을 수 없습니다: " + failure);
                } else if (items.isEmpty()) {
                    showMessage("이 폴더에서 가져올 수 있는 게임을 찾지 못했습니다.");
                } else {
                    showFolderPreview(treeUri, mode, items);
                }
            });
        }, "scan").start();
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
        }
        return Math.max(1, bytes / 1024) + "KB";
    }

    private void showFolderPreview(Uri treeUri, String mode, List<FolderItem> items) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(14), dp(18), dp(14), dp(14));
        panel.setBackground(rounded(-1, dp(24)));

        int available = 0;
        for (FolderItem item : items) {
            if (!item.present) {
                available++;
            }
        }
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        header.setPadding(dp(4), 0, dp(4), 0);
        header.addView(new RoundIconView(this, RoundIconView.FOLDER, RoundIconView.COLOR_SAVE), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView found = new TextView(this);
        found.setText("게임 " + items.size() + "개를 찾았어요");
        found.setTextSize(16.0f);
        found.setTypeface(Typeface.DEFAULT_BOLD);
        found.setTextColor(Color.rgb(31, 41, 51));
        texts.addView(found);
        TextView where = new TextView(this);
        where.setText(folderLabel(treeUri) + " · 하위 폴더 포함");
        where.setTextSize(12.0f);
        where.setTextColor(Color.rgb(122, 130, 140));
        texts.addView(where);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(12), 0, 0, 0);
        header.addView(texts, textsParams);
        panel.addView(header);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(0);
        bar.setGravity(16);
        bar.setPadding(dp(4), dp(12), dp(4), dp(4));
        final TextView selectAll = new TextView(this);
        selectAll.setTextSize(12.5f);
        selectAll.setTypeface(Typeface.DEFAULT_BOLD);
        selectAll.setTextColor(RoundIconView.COLOR_SAVE);
        bar.addView(selectAll, new LinearLayout.LayoutParams(0, -2, 1.0f));
        final TextView count = new TextView(this);
        count.setTextSize(12.0f);
        count.setTextColor(Color.rgb(122, 130, 140));
        bar.addView(count);
        panel.addView(bar);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(1);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        TextView cancel = new TextView(this);
        cancel.setText("취소");
        cancel.setTextSize(14.0f);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setTextColor(Color.rgb(75, 85, 99));
        cancel.setGravity(17);
        cancel.setBackground(rounded(Color.rgb(238, 240, 243), dp(14)));
        cancel.setOnClickListener(view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(dp(96), -1));
        final TextView go = new TextView(this);
        go.setTextSize(15.0f);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setTextColor(-1);
        go.setGravity(17);
        go.setBackground(rounded(RoundIconView.COLOR_SAVE, dp(14)));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(0, -1, 1.0f);
        goParams.setMargins(dp(9), 0, 0, 0);
        buttons.addView(go, goParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, dp(48));
        buttonsParams.setMargins(0, dp(10), 0, 0);
        panel.addView(buttons, buttonsParams);

        final int availableCount = available;
        final Runnable refresh = () -> {
            int selected = 0;
            for (FolderItem item : items) {
                if (item.selected) {
                    selected++;
                }
            }
            count.setText(selected + "개 선택됨");
            selectAll.setText(selected == availableCount ? "✓ 전체 선택 해제" : "✓ 전체 선택");
            go.setText(selected + "개 가져오기");
            go.setAlpha(selected == 0 ? 0.5f : 1.0f);
        };
        final List<Runnable> redraws = new ArrayList<>();
        for (FolderItem item : items) {
            redraws.add(addFolderRow(list, item, refresh));
        }
        selectAll.setOnClickListener(view -> {
            boolean all = true;
            for (FolderItem item : items) {
                if (!item.present && !item.selected) {
                    all = false;
                }
            }
            for (FolderItem item : items) {
                item.selected = !item.present && !all;
            }
            for (Runnable redraw : redraws) {
                redraw.run();
            }
            refresh.run();
        });
        go.setOnClickListener(view -> {
            List<FolderItem> chosen = new ArrayList<>();
            for (FolderItem item : items) {
                if (item.selected) {
                    chosen.add(item);
                }
            }
            if (chosen.isEmpty()) {
                return;
            }
            dialog.dismiss();
            importFolderItems(mode, chosen);
        });
        refresh.run();
        showCardDialog(dialog, panel, (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
    }

    // One checkbox row; returns a runnable that redraws its box after "select all".
    private Runnable addFolderRow(LinearLayout list, FolderItem item, Runnable refresh) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        final TextView box = new TextView(this);
        box.setGravity(17);
        box.setTextColor(-1);
        box.setTextSize(13.0f);
        box.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(box, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView name = new TextView(this);
        name.setText(item.title);
        name.setTextSize(14.0f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.rgb(31, 41, 51));
        texts.addView(name);
        TextView detail = new TextView(this);
        detail.setText(item.detail);
        detail.setTextSize(11.0f);
        detail.setTextColor(Color.rgb(138, 146, 156));
        texts.addView(detail);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(12), 0, 0, 0);
        row.addView(texts, textsParams);
        final Runnable redraw = () -> {
            if (item.present) {
                box.setText("");
                box.setBackground(rounded(Color.rgb(229, 231, 235), dp(6)));
            } else if (item.selected) {
                box.setText("✓");
                box.setBackground(rounded(RoundIconView.COLOR_SAVE, dp(6)));
            } else {
                box.setText("");
                GradientDrawable empty = new GradientDrawable();
                empty.setColor(-1);
                empty.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), Color.rgb(195, 200, 207));
                empty.setCornerRadius(dp(6));
                box.setBackground(empty);
            }
        };
        redraw.run();
        if (item.present) {
            row.setAlpha(0.5f);
        } else {
            row.setClickable(true);
            row.setOnClickListener(view -> {
                item.selected = !item.selected;
                redraw.run();
                refresh.run();
            });
        }
        list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(240, 241, 243));
        list.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));
        return redraw;
    }

    private String folderLabel(Uri treeUri) {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? "선택한 폴더" : path;
    }

    // Copies the chosen files in the background and sums up what happened.
    private void importFolderItems(String mode, List<FolderItem> items) {
        runImport("폴더에서 가져오기", reporter -> {
            boolean arcade = isArcadeMode(mode);
            int added = 0;
            int duplicates = 0;
            List<String> bios = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            int index = 0;
            for (FolderItem item : items) {
                index++;
                PickedFile picked = item.file;
                String lower = picked.name.toLowerCase(Locale.US);
                reporter.report("가져오는 중 (" + index + "/" + items.size() + ")\n" + item.title);
                try {
                    if (arcade && isArcadeBiosName(lower)) {
                        importArcadeBiosQuietly(picked);
                        bios.add(picked.name);
                        continue;
                    }
                    List<File> imported;
                    if (arcade) {
                        File file = copyArcadeRomToAppStorage(picked.uri, picked.name, mode);
                        imported = singletonOrEmpty(file);
                        if (file != null && RomsetIndex.isAvailable(this)) {
                            RomsetResult romset = resolveRomset(file);
                            if (romset.match == null) {
                                failed.add(picked.name + " · 맞는 롬셋을 찾지 못함 (목록에는 추가됨)");
                            } else if (romset.renameConflict != null) {
                                failed.add(picked.name + " · 같은 게임(" + romset.renameConflict + ")이 이미 있음");
                            }
                        }
                    } else if (lower.endsWith(".zip")) {
                        imported = copyZippedRomsToAppStorage(picked.uri, picked.name, mode);
                    } else {
                        imported = singletonOrEmpty(copyRomToAppStorage(picked.uri, picked.name, mode));
                    }
                    if (imported.isEmpty()) {
                        duplicates++;
                    } else {
                        added += imported.size();
                    }
                } catch (IOException | RuntimeException e) {
                    failed.add(picked.name + " · " + e.getMessage());
                }
            }
            StringBuilder sb = new StringBuilder("새로 추가: " + added + "개  ·  이미 있음: " + duplicates + "개  ·  못 가져옴: " + failed.size() + "개");
            appendList(sb, "BIOS로 등록", bios);
            appendList(sb, "확인이 필요한 파일", failed);
            return sb.toString();
        });
    }

    // Folder imports put BIOS archives where the cores look for them, without dialogs.
    private void importArcadeBiosQuietly(PickedFile picked) throws IOException {
        String lower = sanitizeFileName(picked.name).toLowerCase(Locale.US);
        if (lower.equals("neogeo.zip")) {
            File bios = neoGeoBiosFile();
            copyUriToFile(picked.uri, bios);
            prepareNeoGeoBiosForArcade(bios, new File(arcadeRomDir(), "neogeo.zip"));
        } else {
            copyUriToFile(picked.uri, new File(mameBiosDir(), lower));
        }
    }

    private static final class PickedFile {
        final Uri uri;
        final String name;
        final long size;

        PickedFile(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    // Lists every file below a picked folder, a few levels deep.
    private List<PickedFile> listTree(Uri treeUri) {
        List<PickedFile> files = new ArrayList<>();
        listTreeChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri), 0, files);
        return files;
    }

    private void listTreeChildren(Uri treeUri, String documentId, int depth, List<PickedFile> out) {
        if (depth > 6) {
            return;
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String> directories = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (id == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    directories.add(id);
                } else {
                    out.add(new PickedFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, cursor.isNull(3) ? -1L : cursor.getLong(3)));
                }
            }
        } catch (RuntimeException ignored) {
            // A folder the provider refuses to list is skipped.
        }
        for (String directory : directories) {
            listTreeChildren(treeUri, directory, depth + 1, out);
        }
    }

    private interface ImportJob {
        String run(ProgressReporter reporter) throws IOException;
    }

    private interface ProgressReporter {
        void report(String message);
    }

    // Large disc images are copied off the UI thread behind a progress dialog.
    @SuppressWarnings("deprecation")
    private void runImport(String title, ImportJob job) {
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle(title);
        progress.setMessage("준비 중…");
        progress.setCancelable(false);
        progress.show();
        new Thread(() -> {
            String result;
            boolean failed = false;
            try {
                result = job.run(message -> runOnUiThread(() -> progress.setMessage(message)));
            } catch (IOException | RuntimeException e) {
                result = title + " 실패: " + e.getMessage();
                failed = true;
            }
            final String summary = result;
            final boolean error = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                progress.dismiss();
                updateHomeStatusCards();
                updateNote();
                refreshLibrary();
                new AlertDialog.Builder(this).setTitle(error ? "가져오기 실패" : title).setMessage(summary).setPositiveButton("확인", null).show();
            });
        }, "import").start();
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("폴더를 만들 수 없습니다");
        }
        File temp = new File(target.getPath() + ".import");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                throw new IOException(target.getName() + " 파일을 열 수 없습니다");
            }
            copy(in, out);
        } catch (IOException e) {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
            throw e;
        }
        if (target.exists() && !target.delete()) {
            throw new IOException(target.getName() + "을(를) 덮어쓸 수 없습니다");
        }
        if (!temp.renameTo(target)) {
            throw new IOException(target.getName() + "을(를) 저장할 수 없습니다");
        }
    }

    private static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("\n\n").append(title).append(" (").append(items.size()).append("개)");
        for (int i = 0; i < Math.min(items.size(), 8); i++) {
            sb.append("\n• ").append(items.get(i));
        }
        if (items.size() > 8) {
            sb.append("\n• 외 ").append(items.size() - 8).append("개");
        }
    }

    private void importBiosFolder(Uri treeUri, String source) {
        runImport("BIOS 폴더 등록", reporter -> {
            reporter.report("폴더 살펴보는 중…");
            List<PickedFile> files = listTree(treeUri);
            return importBiosFiles(files, source, reporter);
        });
    }

    // Sorts BIOS files by what they are: GBA, Neo Geo, or MAME board BIOS.
    private String importBiosFiles(List<PickedFile> files, String source, ProgressReporter reporter) throws IOException {
        File systemDir = systemDir();
        if (!systemDir.exists() && !systemDir.mkdirs()) {
            throw new IOException("BIOS 폴더를 만들 수 없습니다");
        }
        List<String> gba = new ArrayList<>();
        List<String> neoGeo = new ArrayList<>();
        List<String> mame = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int ignored = 0;
        int index = 0;
        for (PickedFile picked : files) {
            index++;
            String name = sanitizeFileName(picked.name);
            String lowerCase = name.toLowerCase(Locale.US);
            File target = null;
            List<String> bucket = null;
            if (lowerCase.endsWith(".bin") && (lowerCase.contains("gba") || picked.size == 16384)) {
                target = biosFile();
                bucket = gba;
            } else if (lowerCase.equals("neogeo.zip")) {
                target = neoGeoBiosFile();
                bucket = neoGeo;
            } else if (lowerCase.endsWith(".zip") && (BIOS_SOURCE_MAME.equals(source) || Arrays.asList(KNOWN_ARCADE_BIOS).contains(lowerCase) || RomsetIndex.isBiosName(this, baseNameWithoutExtension(lowerCase)))) {
                target = new File(mameBiosDir(), name);
                bucket = mame;
            }
            if (target == null) {
                ignored++;
                continue;
            }
            reporter.report("복사 중 (" + index + "/" + files.size() + ")\n" + name);
            try {
                copyUriToFile(picked.uri, target);
                if (bucket == gba && target.length() != 16384) {
                    if (!target.delete()) {
                        target.deleteOnExit();
                    }
                    throw new IOException("GBA BIOS는 16KB 파일이어야 합니다");
                }
                if (bucket == mame && !isReadableZip(target)) {
                    if (!target.delete()) {
                        target.deleteOnExit();
                    }
                    throw new IOException("올바른 ZIP 파일이 아닙니다");
                }
                if (bucket == neoGeo) {
                    File romDir = arcadeRomDir();
                    if (!romDir.exists() && !romDir.mkdirs()) {
                        throw new IOException("ROM 폴더를 만들 수 없습니다");
                    }
                    prepareNeoGeoBiosForArcade(target, new File(romDir, "neogeo.zip"));
                }
                bucket.add(name);
            } catch (IOException e) {
                failed.add(name + ": " + e.getMessage());
            }
        }
        int total = gba.size() + neoGeo.size() + mame.size();
        StringBuilder sb = new StringBuilder("등록한 BIOS: " + total + "개");
        appendList(sb, "GBA", gba);
        appendList(sb, "Neo Geo", neoGeo);
        appendList(sb, "MAME 기판", mame);
        if (ignored > 0) {
            sb.append("\n\nBIOS가 아니어서 건너뛴 파일: ").append(ignored).append("개");
        }
        appendList(sb, "실패", failed);
        if (total == 0 && failed.isEmpty()) {
            sb.append("\n\n알아볼 수 있는 BIOS가 없습니다. gba_bios.bin, neogeo.zip, 기판 BIOS ZIP을 원래 파일명 그대로 넣어 주세요.");
        }
        return sb.toString();
    }

    // ---- Arcade romset recognition ----

    private static final class RomsetResult {
        File file;
        RomsetIndex.Match match;
        boolean coreChanged;
        String renameConflict;
    }

    // Finds which romset an arcade ZIP holds, picks an engine that has it and renames
    // the file to the set name, since both cores look games up by file name.
    private RomsetResult resolveRomset(File file) {
        RomsetResult result = new RomsetResult();
        result.file = file;
        File dir = file.getParentFile();
        String preferred = coreFor(file);
        int[] crcs = RomsetIndex.zipCrcs(file);
        RomsetIndex.Match best = null;
        List<String> order = new ArrayList<>();
        order.add(preferred);
        for (String core : ARCADE_CORE_IDS) {
            if (!order.contains(core)) {
                order.add(core);
            }
        }
        for (String core : order) {
            RomsetIndex.Match match = RomsetIndex.identify(this, core, crcs);
            if (match == null) {
                continue;
            }
            // Prefer a complete set, then an engine that has the game marked as working.
            if (best == null
                    || (match.isComplete(dir) && !best.isComplete(dir))
                    || (match.isComplete(dir) && best.isComplete(dir) && match.working && !best.working)
                    || (!match.isComplete(dir) && !best.isComplete(dir) && match.missingOwn < best.missingOwn)) {
                best = match;
            }
        }
        result.match = best;
        if (best == null) {
            return result;
        }
        if (!best.core.equals(preferred)) {
            romPrefs().edit().putString("core:" + file.getName(), best.core).apply();
            result.coreChanged = true;
        }
        File target = new File(dir, best.name + ".zip");
        if (!target.getName().equals(file.getName())) {
            if (target.exists()) {
                result.renameConflict = target.getName();
            } else if (renameRomFile(file, target)) {
                result.file = target;
            }
        }
        return result;
    }

    // Renames an imported ROM and carries its title, save name and settings along.
    private boolean renameRomFile(File from, File to) {
        if (!from.renameTo(to)) {
            return false;
        }
        SharedPreferences prefs = romPrefs();
        SharedPreferences.Editor editor = prefs.edit();
        String[] prefixes = {"title:", "save:", "favorite:", "recent:", "core:"};
        for (String prefix : prefixes) {
            Object value = prefs.getAll().get(prefix + from.getName());
            editor.remove(prefix + from.getName());
            if (value instanceof String) {
                editor.putString(prefix + to.getName(), (String) value);
            } else if (value instanceof Boolean) {
                editor.putBoolean(prefix + to.getName(), (Boolean) value);
            } else if (value instanceof Long) {
                editor.putLong(prefix + to.getName(), (Long) value);
            } else if (value instanceof Integer) {
                editor.putInt(prefix + to.getName(), (Integer) value);
            }
        }
        if (prefs.getString("title:" + from.getName(), null) == null) {
            editor.putString("title:" + to.getName(), baseNameWithoutExtension(from.getName()));
        }
        editor.apply();
        return true;
    }

    private boolean hasBoardBios(RomsetIndex.Match match, File dir) {
        if (match.bios == null) {
            return true;
        }
        if (match.bios.equals("neogeo")) {
            return neoGeoBiosFile().exists();
        }
        String name = match.bios + ".zip";
        return new File(dir, name).isFile() || new File(mameBiosDir(), name).isFile();
    }

    private String romsetProblem(RomsetResult result) {
        File dir = result.file.getParentFile();
        RomsetIndex.Match match = result.match;
        String engine = coreDisplayName(match.core);
        StringBuilder sb = new StringBuilder(engine + "의 '" + match.name + "' 게임으로 인식했지만 실행에 필요한 파일이 부족합니다.\n");
        if (match.missingOwn > 0) {
            sb.append("\n• ROM 파일 ").append(match.missingOwn).append("개가 없거나 다른 버전(롬셋)입니다.");
        }
        if (match.missingShared > 0 && (match.parent == null || !new File(dir, match.parent + ".zip").isFile())) {
            sb.append("\n• 원본 게임 ROM ").append(match.parent == null ? "" : match.parent + ".zip").append("이 같은 목록에 있어야 합니다.");
        }
        sb.append("\n\n").append(engine).append("용 롬셋으로 다시 받아 넣으면 실행됩니다.");
        return sb.toString();
    }

    private String unknownRomsetText(File file) {
        return displayGameNameFor(file) + " (" + formatFileSize(file.length()) + ")\n\nZIP 안의 파일이 FinalBurn Neo · MAME 2003-Plus가 지원하는 게임과 맞지 않습니다.\n\n• 두 엔진이 지원하지 않는 게임이거나\n• 일부 파일만 담긴 ZIP(원본 없이 받은 클론 롬셋 등)일 수 있어요.";
    }

    private RomCheck checkArcadeRomset(File file) {
        if (!RomsetIndex.isAvailable(this)) {
            return isReadableZip(file) ? new RomCheck(true, "ZIP 읽기 가능") : new RomCheck(false, "ZIP 읽기 실패");
        }
        int[] crcs = RomsetIndex.zipCrcs(file);
        if (crcs.length == 0) {
            return new RomCheck(false, "ZIP 읽기 실패");
        }
        File dir = file.getParentFile();
        RomsetIndex.Match best = null;
        for (String core : ARCADE_CORE_IDS) {
            RomsetIndex.Match match = RomsetIndex.identify(this, core, crcs);
            if (match != null && (best == null || (match.isComplete(dir) && !best.isComplete(dir))
                    || (match.isComplete(dir) == best.isComplete(dir) && match.working && !best.working))) {
                best = match;
            }
        }
        if (best == null) {
            return new RomCheck(false, "알 수 없는 롬셋 (지원하지 않는 게임이거나 일부 파일만 있음)");
        }
        if (best.isComplete(dir) && !hasBoardBios(best, dir)) {
            return new RomCheck(false, best.name + " · 기판 BIOS " + best.bios + ".zip 필요");
        }
        if (!best.isComplete(dir)) {
            return new RomCheck(false, best.name + " · 파일 부족" + (best.missingShared > 0 && best.parent != null ? " (" + best.parent + ".zip 필요)" : ""));
        }
        if (!best.working) {
            return new RomCheck(false, best.name + " · " + coreDisplayName(best.core) + "에서 아직 동작하지 않는 게임");
        }
        return new RomCheck(true, best.name + " · " + coreDisplayName(best.core));
    }

    private void launchArcadeGame(File file) {
        if (!RomsetIndex.isAvailable(this)) {
            startGameActivity(file);
            return;
        }
        RomsetResult result = resolveRomset(file);
        final File resolved = result.file;
        if (!resolved.equals(file)) {
            refreshLibrary();
        }
        if (result.match == null) {
            new AlertDialog.Builder(this).setTitle("이 ROM을 알아볼 수 없습니다").setMessage(unknownRomsetText(resolved)).setNegativeButton("취소", null).setPositiveButton("그대로 실행", (dialog, which) -> startGameActivity(resolved)).show();
            return;
        }
        if (result.renameConflict != null) {
            new AlertDialog.Builder(this).setTitle("같은 게임이 이미 있습니다").setMessage(result.match.name + " 게임이 이미 " + result.renameConflict + "로 들어 있어요. 이 파일은 이름이 달라서 엔진이 찾지 못합니다.\n\n목록에서 " + result.renameConflict + " 쪽을 실행하거나, 이 항목을 삭제해 주세요.").setPositiveButton("확인", null).show();
            return;
        }
        if (!result.match.isComplete(resolved.getParentFile())) {
            new AlertDialog.Builder(this).setTitle("ROM 파일이 부족합니다").setMessage(romsetProblem(result)).setNegativeButton("취소", null).setPositiveButton("그대로 실행", (dialog, which) -> startGameActivity(resolved)).show();
            return;
        }
        if (!hasBoardBios(result.match, resolved.getParentFile())) {
            new AlertDialog.Builder(this).setTitle("기판 BIOS가 필요합니다").setMessage(coreDisplayName(result.match.core) + "의 '" + result.match.name + "' 게임은 기판 BIOS " + result.match.bios + ".zip이 있어야 실행됩니다.\n\nMAME BIOS에 " + result.match.bios + ".zip을 등록해 주세요.").setNegativeButton("그대로 실행", (dialog, which) -> startGameActivity(resolved)).setPositiveButton("BIOS 등록", (dialog, which) -> showBiosChooser(BIOS_SOURCE_MAME)).show();
            return;
        }
        if (!result.match.working) {
            new AlertDialog.Builder(this).setTitle("아직 동작하지 않는 게임입니다").setMessage(coreDisplayName(result.match.core) + "의 게임 목록에 '" + result.match.name + "'은(는) 아직 제대로 동작하지 않는 게임(preliminary)으로 표시돼 있습니다.\n\n실행해도 검은 화면에서 멈추거나 진행이 안 될 수 있어요. 다른 엔진에서 동작하는 게임이면 앱이 자동으로 그 엔진을 고릅니다.").setNegativeButton("취소", null).setPositiveButton("그래도 실행", (dialog, which) -> startGameActivity(resolved)).show();
            return;
        }
        if (result.coreChanged) {
            showMessage(coreDisplayName(result.match.core) + "용 롬셋이라 이 엔진으로 실행합니다.");
        }
        startGameActivity(resolved);
    }

    private void refreshLibrary() {
        LinearLayout linearLayout = this.romList;
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        File[] listFiles = currentRomDir().listFiles(this::isRomFile);
        if (listFiles == null || listFiles.length == 0) {
            this.emptyView.setText(emptyLibraryText());
            this.emptyView.setVisibility(0);
            return;
        }
        this.emptyView.setVisibility(8);
        Arrays.sort(listFiles, this::compareRomsForLibrary);
        for (File file : listFiles) {
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.setMargins(0, 0, 0, dp(10));
            this.romList.addView(createRomRow(file), layoutParams);
        }
    }

    private boolean isRomFile(File file) {
        return file.isFile() && isSupportedRom(file);
    }

    private LinearLayout createRomRow(final File file) {
        String str;
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(14), dp(12), dp(14), dp(12));
        linearLayout.setBackground(rounded(-1, dp(8)));
        linearLayout.setClickable(true);
        linearLayout.setElevation(dp(2));
        linearLayout.setOnClickListener(view -> launchGame(file));
        linearLayout.setOnLongClickListener(view -> onRomLongClick(file));
        migrateSaveFileIfNeeded(file);
        String displayGameNameFor = displayGameNameFor(file);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        TextView textView = new TextView(this);
        textView.setText((isFavorite(file) ? "★ " : "") + displayGameNameFor);
        textView.setTextColor(Color.rgb(45, 53, 64));
        textView.setTextSize(16.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setSingleLine(false);
        TextView textView2 = new TextView(this);
        textView2.setText("실행");
        textView2.setGravity(17);
        textView2.setTextColor(-1);
        textView2.setTextSize(13.0f);
        textView2.setTypeface(Typeface.DEFAULT_BOLD);
        textView2.setBackground(rounded(Color.rgb(28, 178, 206), dp(17)));
        textView2.setOnClickListener(view -> launchGame(file));
        textView2.setOnLongClickListener(view -> onRomLongClick(file));
        linearLayout2.addView(textView, new LinearLayout.LayoutParams(0, -2, 1.0f));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(70), dp(34));
        layoutParams.setMargins(dp(10), 0, 0, 0);
        linearLayout2.addView(textView2, layoutParams);
        TextView textView3 = new TextView(this);
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(file);
        StringBuilder sb = new StringBuilder("시스템: ");
        sb.append(coreDisplayName(coreFor(file))).append("  |  ");
        if (saveFileFor.exists() || stateFileFor.exists()) {
            str = "세이브: " + saveBaseNameFor(file);
        } else {
            str = "아직 세이브 없음";
        }
        sb.append(str);
        sb.append("  |  게임: ").append(displayGameNameFor);
        long max = Math.max(saveFileFor.exists() ? saveFileFor.lastModified() : 0L, stateFileFor.exists() ? stateFileFor.lastModified() : 0L);
        if (max > 0) {
            sb.append("\n세이브 수정: ").append(formatTimestamp(max));
        }
        long lastPlayedAt = lastPlayedAt(file);
        if (lastPlayedAt > 0) {
            sb.append("  |  마지막 실행: ").append(formatTimestamp(lastPlayedAt));
        }
        textView3.setText(sb.toString());
        textView3.setTextColor(Color.rgb(92, 103, 115));
        textView3.setPadding(0, dp(7), 0, 0);
        textView3.setTextSize(12.0f);
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(-1, -2));
        linearLayout.addView(textView3, new LinearLayout.LayoutParams(-1, -2));
        return linearLayout;
    }

    private boolean onRomLongClick(File file) {
        showRomActions(file);
        return true;
    }

    // Long-press menu: the game's details, a big launch button, a 3x2 grid of tiles and the
    // two delete actions, in the same style as the in-game pause menu.
    private void showRomActions(final File file) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(rounded(-1, dp(24)));

        String mode = modeForFile(file);
        int modeIndex = modeIndex(mode);
        boolean arcade = "arcade".equals(mode) || "neogeo".equals(mode);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(0);
        header.setGravity(16);
        TextView badge = new TextView(this);
        badge.setText(MODE_ICONS[modeIndex]);
        badge.setTextColor(-1);
        badge.setTextSize(12.0f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(17);
        badge.setBackground(rounded(MODE_COLORS[modeIndex], dp(13)));
        header.addView(badge, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(1);
        TextView name = new TextView(this);
        name.setText((isFavorite(file) ? "★ " : "") + displayGameNameFor(file));
        name.setTextColor(Color.rgb(31, 41, 51));
        name.setTextSize(17.0f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        TextView detail = new TextView(this);
        StringBuilder sb = new StringBuilder(coreDisplayName(coreFor(file)));
        sb.append(saveFileFor(file).exists() || stateFileFor(file).exists() ? " · 세이브 있음" : " · 세이브 없음");
        long lastPlayed = lastPlayedAt(file);
        if (lastPlayed > 0) {
            sb.append(" · ").append(formatTimestamp(lastPlayed));
        }
        detail.setText(sb.toString());
        detail.setTextColor(Color.rgb(122, 130, 140));
        detail.setTextSize(11.5f);
        texts.addView(detail);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        textsParams.setMargins(dp(12), 0, 0, 0);
        header.addView(texts, textsParams);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView launch = new TextView(this);
        launch.setText("▶  실행");
        launch.setTextSize(16.0f);
        launch.setTypeface(Typeface.DEFAULT_BOLD);
        launch.setTextColor(-1);
        launch.setGravity(17);
        launch.setBackground(rounded(Color.rgb(22, 185, 207), dp(14)));
        launch.setOnClickListener(view -> {
            dialog.dismiss();
            launchGame(file);
        });
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(-1, dp(50));
        launchParams.setMargins(0, dp(14), 0, dp(12));
        panel.addView(launch, launchParams);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(createActionTile(dialog, RoundIconView.STAR, RoundIconView.COLOR_STAR, isFavorite(file) ? "즐겨찾기\n해제" : "즐겨찾기", null, false, () -> toggleFavorite(file)), actionGridParams(0));
        row1.addView(createActionTile(dialog, RoundIconView.EDIT, RoundIconView.COLOR_EDIT, "이름 변경", null, false, () -> renameGameAndSave(file)), actionGridParams(1));
        if (arcade) {
            row1.addView(createActionTile(dialog, RoundIconView.GEAR, RoundIconView.COLOR_GEAR, "엔진 변경", shortCoreName(coreFor(file)), false, () -> showCorePicker(file)), actionGridParams(2));
        } else {
            row1.addView(createActionTile(dialog, RoundIconView.ERASE, RoundIconView.COLOR_EXIT, "세이브 삭제", null, true, () -> confirmDeleteSave(file)), actionGridParams(2));
        }
        panel.addView(row1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(createActionTile(dialog, RoundIconView.EXPORT, RoundIconView.COLOR_SAVE, "세이브\n내보내기", null, false, () -> startSaveExport(file)), actionGridParams(0));
        row2.addView(createActionTile(dialog, RoundIconView.IMPORT, RoundIconView.COLOR_LOAD, "세이브\n가져오기", null, false, () -> startSaveImport(file)), actionGridParams(1));
        if (arcade) {
            row2.addView(createActionTile(dialog, RoundIconView.ERASE, RoundIconView.COLOR_EXIT, "세이브 삭제", null, true, () -> confirmDeleteSave(file)), actionGridParams(2));
        } else {
            row2.addView(createActionTile(dialog, RoundIconView.MULTI, RoundIconView.COLOR_EXIT, "여러 개\n삭제", null, true, () -> showMultiDelete(file)), actionGridParams(2));
        }
        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(-1, -2);
        row2Params.setMargins(0, dp(9), 0, 0);
        panel.addView(row2, row2Params);

        LinearLayout deletes = new LinearLayout(this);
        deletes.addView(createOutlineButton(dialog, RoundIconView.TRASH, "ROM 삭제", () -> confirmDeleteRom(file)), actionGridParams(0));
        if (arcade) {
            deletes.addView(createOutlineButton(dialog, RoundIconView.MULTI, "여러 개 삭제", () -> showMultiDelete(file)), actionGridParams(2));
        }
        LinearLayout.LayoutParams deletesParams = new LinearLayout.LayoutParams(-1, dp(44));
        deletesParams.setMargins(0, dp(9), 0, 0);
        panel.addView(deletes, deletesParams);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(400)), -2);
        }
        dialog.show();
    }

    private LinearLayout.LayoutParams actionGridParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1.0f);
        params.setMargins(column == 0 ? 0 : dp(4), 0, column == 2 ? 0 : dp(4), 0);
        return params;
    }

    private LinearLayout createActionTile(Dialog dialog, int icon, int color, String label, String sub, boolean danger, Runnable action) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(1);
        tile.setGravity(1);
        tile.setPadding(dp(6), dp(13), dp(6), dp(11));
        final int normal = danger ? Color.rgb(255, 241, 241) : Color.rgb(246, 247, 249);
        final int pressed = danger ? Color.rgb(252, 222, 222) : Color.rgb(228, 231, 236);
        tile.setBackground(rounded(normal, dp(16)));
        tile.addView(new RoundIconView(this, icon, color), new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(12.5f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(danger ? Color.rgb(217, 61, 66) : Color.rgb(31, 41, 51));
        text.setGravity(1);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(0, dp(7), 0, 0);
        tile.addView(text, textParams);
        if (sub != null) {
            TextView subText = new TextView(this);
            subText.setText(sub);
            subText.setTextSize(10.5f);
            subText.setTextColor(Color.rgb(138, 146, 156));
            subText.setGravity(1);
            tile.addView(subText, new LinearLayout.LayoutParams(-2, -2));
        }
        pressable(tile, normal, pressed, dp(16));
        tile.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        return tile;
    }

    private LinearLayout createOutlineButton(Dialog dialog, int icon, String label, Runnable action) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(0);
        button.setGravity(17);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(-1);
        outline.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density), Color.rgb(243, 199, 200));
        outline.setCornerRadius(dp(13));
        button.setBackground(outline);
        RoundIconView iconView = new RoundIconView(this, icon, Color.rgb(229, 72, 77));
        button.addView(iconView, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13.5f);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextColor(Color.rgb(229, 72, 77));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.setMargins(dp(6), 0, 0, 0);
        button.addView(text, textParams);
        button.setClickable(true);
        button.setOnClickListener(view -> {
            dialog.dismiss();
            action.run();
        });
        return button;
    }

    // Darkens a rounded tile while it is held down.
    private void pressable(View view, int normal, int pressed, int radius) {
        view.setClickable(true);
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.setBackground(rounded(pressed, radius));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.setBackground(rounded(normal, radius));
            }
            return false;
        });
    }

    private void showCorePicker(final File file) {
        String[] strArr = ARCADE_CORE_LABELS;
        final String[] strArr2 = ARCADE_CORE_IDS;
        int i = Math.max(0, Arrays.asList(ARCADE_CORE_IDS).indexOf(coreFor(file)));
        new AlertDialog.Builder(this).setTitle("실행 엔진 선택").setSingleChoiceItems(strArr, i, (dialogInterface, i2) -> onCoreSelected(file, strArr2, dialogInterface, i2)).show();
    }

    // Speed and how many games each engine runs, out of 5, for the engine cards.
    private static final int[] ENGINE_SPEED = {5, 5, 3, 1};
    private static final int[] ENGINE_GAMES = {2, 3, 4, 5};
    private static final String[] ENGINE_NAMES = {"MAME 2003-Plus", "FinalBurn Neo", "MAME 2010", "MAME 2015"};
    private static final String[] ENGINE_NOTES = {"구형 아케이드 · 가벼움", "Neo Geo · CPS 계열", "0.139 · ZN·3D 기판", "ST-V 등 최신 지원 · 무거움"};

    // Engine for games in this mode; a game whose romset fits only the other engine still switches to it.
    private void showDefaultCorePicker() {
        final String key = "neogeo".equals(this.currentMode) ? PREF_NEOGEO_CORE : PREF_ARCADE_CORE;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(rounded(-1, dp(24)));
        TextView title = new TextView(this);
        title.setText("기본 엔진");
        title.setTextSize(17.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(MODE_NAMES[modeIndex(this.currentMode)] + " · 게임을 길게 누르면 게임별로 바꿀 수 있어요");
        sub.setTextSize(12.0f);
        sub.setTextColor(Color.rgb(122, 130, 140));
        sub.setPadding(dp(4), dp(2), dp(4), dp(4));
        panel.addView(sub);
        String current = defaultCoreFor(this.currentMode);
        for (int i = 0; i < ARCADE_CORE_IDS.length; i++) {
            final String core = ARCADE_CORE_IDS[i];
            View card = createEngineCard(i, core.equals(current));
            card.setOnClickListener(view -> {
                dialog.dismiss();
                settingsPrefs().edit().putString(key, core).apply();
                updateHomeStatusCards();
                refreshLibrary();
                showMessage("기본 엔진: " + coreDisplayName(core) + "\n게임별 엔진은 게임을 길게 눌러 바꿀 수 있습니다.");
            });
            panel.addView(card);
        }
        LinearLayout buttons = new LinearLayout(this);
        TextView check = createCancelButton(dialog, "ROM 검사");
        check.setOnClickListener(view -> {
            dialog.dismiss();
            checkImportedRoms();
        });
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        checkParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(check, checkParams);
        TextView close = createCancelButton(dialog, "닫기");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(44), 1.0f);
        closeParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(close, closeParams);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.setMargins(0, dp(12), 0, 0);
        panel.addView(buttons, buttonsParams);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        showCardDialog(dialog, scroll, -2);
    }

    // One engine: name, note and two 5-step bars; the engine in use is a blue card.
    private View createEngineCard(int index, boolean selected) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(1);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        card.setBackground(rounded(selected ? RoundIconView.COLOR_SAVE : Color.rgb(246, 247, 249), dp(14)));
        int text = selected ? -1 : Color.rgb(31, 41, 51);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(0);
        top.setGravity(16);
        TextView name = new TextView(this);
        name.setText(ENGINE_NAMES[index]);
        name.setTextSize(14.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(text);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1.0f));
        if (selected) {
            TextView badge = new TextView(this);
            badge.setText("사용 중");
            badge.setTextSize(11.0f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(-1);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            badge.setBackground(rounded(Color.argb(64, 255, 255, 255), dp(8)));
            top.addView(badge);
        }
        card.addView(top);
        TextView note = new TextView(this);
        note.setText(ENGINE_NOTES[index]);
        note.setTextSize(11.5f);
        note.setTextColor(selected ? Color.argb(200, 255, 255, 255) : Color.rgb(122, 130, 140));
        card.addView(note);
        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(0);
        bars.setGravity(16);
        bars.setPadding(0, dp(7), 0, 0);
        addEngineBar(bars, "속도", ENGINE_SPEED[index], selected ? -1 : Color.rgb(34, 179, 90), text);
        addEngineBar(bars, "지원 게임", ENGINE_GAMES[index], selected ? -1 : Color.rgb(245, 158, 11), text);
        card.addView(bars);
        card.setClickable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(params);
        return card;
    }

    private void addEngineBar(LinearLayout row, String label, int value, int on, int text) {
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(11.0f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(text);
        row.addView(name);
        for (int i = 0; i < 5; i++) {
            View step = new View(this);
            step.setBackground(rounded(i < value ? on : (on == -1 ? Color.argb(70, 255, 255, 255) : Color.rgb(229, 231, 235)), dp(3)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(14), dp(6));
            params.setMargins(i == 0 ? dp(6) : dp(3), 0, 0, 0);
            row.addView(step, params);
        }
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));
    }

    private void onCoreSelected(File file, String[] strArr, DialogInterface dialogInterface, int i) {
        romPrefs().edit().putString("core:" + file.getName(), strArr[i]).apply();
        dialogInterface.dismiss();
        refreshLibrary();
        showMessage("실행 코어: " + coreDisplayName(strArr[i]));
    }

    private void launchGame(File file) {
        if (!file.exists()) {
            showError("ROM 실행 실패: 가져온 ROM 파일이 없습니다");
            refreshLibrary();
            return;
        }
        String mode = modeForFile(file);
        if ("neogeo".equals(mode) && !neoGeoBiosFile().exists()) {
            new AlertDialog.Builder(this).setTitle("Neo Geo BIOS가 필요합니다").setMessage("neogeo.zip을 등록해야 Neo Geo 게임을 실행할 수 있어요.").setNegativeButton("취소", null).setPositiveButton("BIOS 등록", (dialog, which) -> openNeoGeoBiosPicker()).show();
            return;
        }
        if ("arcade".equals(mode) || "neogeo".equals(mode)) {
            launchArcadeGame(file);
            return;
        }
        startGameActivity(file);
    }

    private void startGameActivity(File file) {
        String mode = modeForFile(file);
        if ("arcade".equals(mode)) {
            syncArcadeBiosFiles();
        } else if ("neogeo".equals(mode)) {
            syncNeoGeoBiosInto(file.getParentFile());
        }
        migrateSaveFileIfNeeded(file);
        markRecentlyPlayed(file);
        Intent intent = new Intent(this, (Class<?>) GameActivity.class);
        intent.putExtra("com.example.minigba.ROM_PATH", file.getAbsolutePath());
        intent.putExtra("com.example.minigba.SAVE_BASE_NAME", saveBaseNameFor(file));
        intent.putExtra("com.example.minigba.CORE_ID", coreFor(file));
        intent.putExtra(GameActivity.EXTRA_MODE, mode);
        intent.putExtra(GameActivity.EXTRA_TITLE, displayGameNameFor(file));
        startActivity(intent);
    }

    private void startSaveExport(File file) {
        migrateSaveFileIfNeeded(file);
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(file);
        if (!saveFileFor.exists() && !stateFileFor.exists()) {
            showError("이 ROM의 세이브 파일이 없습니다.");
            return;
        }
        this.pendingSaveRom = file;
        this.pendingSaveFile = saveFileFor;
        Intent intent = new Intent("android.intent.action.CREATE_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("application/zip");
        intent.putExtra("android.intent.extra.TITLE", displayGameNameFor(file) + " 세이브 백업.zip");
        startActivityForResult(intent, 1002);
    }

    private void startSaveImport(File file) {
        migrateSaveFileIfNeeded(file);
        this.pendingSaveRom = file;
        this.pendingSaveFile = saveFileFor(file);
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        startActivityForResult(intent, 1003);
    }

    private void exportSave(Uri uri) {
        File file = this.pendingSaveRom;
        if (file == null) {
            showError("세이브 내보내기 실패: 선택된 세이브가 없습니다");
            return;
        }
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(this.pendingSaveRom);
        if (!saveFileFor.exists() && !stateFileFor.exists()) {
            showError("세이브 내보내기 실패: 세이브 파일이 없습니다");
            return;
        }
        try (OutputStream openOutputStream = getContentResolver().openOutputStream(uri);
             ZipOutputStream zipOutputStream = openOutputStream == null ? null : new ZipOutputStream(openOutputStream)) {
            if (zipOutputStream == null) {
                throw new IOException("저장할 위치를 열 수 없습니다");
            }
            if (saveFileFor.exists()) {
                writeZipEntry(zipOutputStream, saveFileFor, saveFileFor.getName());
            }
            if (stateFileFor.exists()) {
                writeZipEntry(zipOutputStream, stateFileFor, stateFileFor.getName());
            }
            showMessage("세이브 백업을 내보냈습니다.");
        } catch (IOException e) {
            showError("세이브 내보내기 실패: " + e.getMessage());
        }
    }

    private void importSave(Uri uri) {
        File file = this.pendingSaveFile;
        if (file == null) {
            showError("세이브 가져오기 실패: 선택된 ROM이 없습니다");
            return;
        }
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
            showError("세이브 가져오기 실패: 세이브 폴더를 만들 수 없습니다");
            return;
        }
        if (displayNameFor(uri).toLowerCase(Locale.US).endsWith(".zip")) {
            importSaveBackup(uri);
            return;
        }
        try (InputStream openInputStream = getContentResolver().openInputStream(uri);
             FileOutputStream fileOutputStream = new FileOutputStream(this.pendingSaveFile)) {
            if (openInputStream == null) {
                throw new IOException("선택한 세이브 파일을 열 수 없습니다");
            }
            copy(openInputStream, fileOutputStream);
            showMessage("게임 내 세이브를 가져왔습니다.");
            refreshLibrary();
        } catch (IOException e) {
            showError("세이브 가져오기 실패: " + e.getMessage());
        }
    }

    private void importSaveBackup(Uri uri) {
        File file2 = this.pendingSaveRom;
        if (file2 == null) {
            showError("세이브 가져오기 실패: 선택된 ROM이 없습니다");
            return;
        }
        File saveFileFor = saveFileFor(file2);
        File stateFileFor = stateFileFor(this.pendingSaveRom);
        try (InputStream openInputStream = getContentResolver().openInputStream(uri);
             ZipInputStream zipInputStream = openInputStream == null ? null : new ZipInputStream(openInputStream)) {
            if (zipInputStream == null) {
                throw new IOException("선택한 백업 파일을 열 수 없습니다");
            }
            int i = 0;
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (nextEntry.isDirectory()) {
                    continue;
                }
                String lowerCase = new File(nextEntry.getName()).getName().toLowerCase(Locale.US);
                File file;
                if (lowerCase.endsWith(".sav")) {
                    file = saveFileFor;
                } else {
                    file = lowerCase.endsWith(".state") ? stateFileFor : null;
                }
                if (file != null) {
                    File parentFile = file.getParentFile();
                    if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
                        throw new IOException("세이브 폴더를 만들 수 없습니다");
                    }
                    try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                        copy(zipInputStream, fileOutputStream);
                    }
                    i++;
                }
            }
            if (i == 0) {
                throw new IOException("백업 안에 .sav 또는 .state 파일이 없습니다");
            }
            showMessage("세이브 백업을 가져왔습니다.");
            refreshLibrary();
        } catch (IOException e) {
            showError("세이브 가져오기 실패: " + e.getMessage());
        }
    }

    private void confirmDeleteSave(final File file) {
        new AlertDialog.Builder(this).setTitle("세이브를 삭제할까요?").setMessage("이 ROM의 .sav 파일과 빠른 저장 파일을 삭제합니다.").setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, (dialogInterface, i) -> deleteSave(file)).show();
    }

    private void deleteSave(File file) {
        migrateSaveFileIfNeeded(file);
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(file);
        if (!saveFileFor.exists() && !stateFileFor.exists()) {
            showMessage("삭제할 세이브가 없습니다.");
            return;
        }
        boolean z = !saveFileFor.exists() || saveFileFor.delete();
        boolean z2 = !stateFileFor.exists() || stateFileFor.delete();
        if (z && z2) {
            showMessage("세이브를 삭제했습니다.");
            refreshLibrary();
        } else {
            showError("세이브를 삭제할 수 없습니다.");
        }
    }

    private void toggleFavorite(File file) {
        boolean z = !isFavorite(file);
        romPrefs().edit().putBoolean("favorite:" + file.getName(), z).apply();
        showMessage(z ? "즐겨찾기에 추가했습니다." : "즐겨찾기에서 해제했습니다.");
        refreshLibrary();
    }

    private void renameGameAndSave(final File file) {
        final EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(8193);
        editText.setText(displayGameNameFor(file));
        editText.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("게임/세이브 이름").setView(editText).setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, (dialogInterface, i) -> applyRename(editText, file, dialogInterface, i)).show();
    }

    private void applyRename(EditText editText, File file, DialogInterface dialogInterface, int i) {
        String trim = editText.getText().toString().trim();
        String sanitizeSaveBaseName = sanitizeSaveBaseName(trim);
        if (trim.isEmpty()) {
            showError("이름을 비워둘 수 없습니다.");
            return;
        }
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(file);
        // Saves live in a per-core folder; see saveFileFor().
        File file2 = new File(saveDir(), coreFor(file));
        File file3 = new File(file2, sanitizeSaveBaseName + ".sav");
        File file4 = new File(file2, sanitizeSaveBaseName + ".state");
        if ((!saveFileFor.equals(file3) && file3.exists()) || (!stateFileFor.equals(file4) && file4.exists())) {
            showError("같은 이름의 세이브가 이미 있습니다.");
            return;
        }
        SharedPreferences.Editor edit = romPrefs().edit();
        edit.putString("title:" + file.getName(), trim);
        edit.putString("save:" + file.getName(), sanitizeSaveBaseName);
        edit.apply();
        migrateNamedSave(saveFileFor, file3);
        migrateNamedSave(stateFileFor, file4);
        showMessage("세이브 이름: " + sanitizeSaveBaseName + ".sav");
        refreshLibrary();
    }

    private void migrateNamedSave(File file, File file2) {
        if (file.equals(file2) || !file.exists()) {
            return;
        }
        File parentFile = file2.getParentFile();
        if ((parentFile == null || parentFile.exists() || parentFile.mkdirs()) && !file.renameTo(file2) && copyFile(file, file2) && !file.delete()) {
            file.deleteOnExit();
        }
    }

    public int compareRomsForLibrary(File file, File file2) {
        boolean isFavorite = isFavorite(file);
        if (isFavorite != isFavorite(file2)) {
            return isFavorite ? -1 : 1;
        }
        long lastPlayedAt = lastPlayedAt(file);
        long lastPlayedAt2 = lastPlayedAt(file2);
        if (lastPlayedAt != lastPlayedAt2) {
            return Long.compare(lastPlayedAt2, lastPlayedAt);
        }
        return file.getName().toLowerCase(Locale.US).compareTo(file2.getName().toLowerCase(Locale.US));
    }

    private boolean isFavorite(File file) {
        return romPrefs().getBoolean("favorite:" + file.getName(), false);
    }

    private long lastPlayedAt(File file) {
        return romPrefs().getLong("recent:" + file.getName(), 0L);
    }

    private void markRecentlyPlayed(File file) {
        romPrefs().edit().putLong("recent:" + file.getName(), System.currentTimeMillis()).apply();
    }

    private String formatTimestamp(long j) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(j));
    }

    // Pick several games of this mode at once, then delete their saves or the ROMs themselves.
    private void showMultiDelete(File preselected) {
        final File[] files = currentRomDir().listFiles(this::isRomFile);
        if (files == null || files.length == 0) {
            showMessage("삭제할 게임이 없습니다.");
            return;
        }
        Arrays.sort(files, this::compareRomsForLibrary);
        String[] labels = new String[files.length];
        final boolean[] checked = new boolean[files.length];
        for (int i = 0; i < files.length; i++) {
            boolean hasSave = saveFileFor(files[i]).exists() || stateFileFor(files[i]).exists();
            labels[i] = displayGameNameFor(files[i]) + (hasSave ? "  · 세이브 있음" : "");
            checked[i] = files[i].equals(preselected);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("삭제할 게임 선택")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton("취소", null)
                .setNeutralButton("세이브 삭제", (d, which) -> confirmMultiDelete(selectedFiles(files, checked), false))
                .setPositiveButton("ROM 삭제", (d, which) -> confirmMultiDelete(selectedFiles(files, checked), true))
                .create();
        dialog.show();
    }

    private static List<File> selectedFiles(File[] files, boolean[] checked) {
        List<File> selected = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            if (checked[i]) {
                selected.add(files[i]);
            }
        }
        return selected;
    }

    private void confirmMultiDelete(final List<File> files, final boolean roms) {
        if (files.isEmpty()) {
            showMessage("선택한 게임이 없습니다.");
            return;
        }
        String message = roms
                ? "선택한 " + files.size() + "개 게임의 ROM을 삭제합니다. 원본 파일과 세이브는 그대로 둡니다."
                : "선택한 " + files.size() + "개 게임의 .sav 파일과 빠른 저장 파일을 삭제합니다.";
        new AlertDialog.Builder(this).setTitle(roms ? "ROM을 삭제할까요?" : "세이브를 삭제할까요?").setMessage(message)
                .setNegativeButton(17039360, null)
                .setPositiveButton(17039370, (d, which) -> deleteMany(files, roms)).show();
    }

    private void deleteMany(List<File> files, boolean roms) {
        int done = 0;
        int failed = 0;
        for (File file : files) {
            boolean ok;
            if (roms) {
                ok = file.delete();
                if (ok) {
                    romPrefs().edit().remove("title:" + file.getName()).remove("save:" + file.getName()).remove("favorite:" + file.getName()).remove("recent:" + file.getName()).remove("core:" + file.getName()).apply();
                }
            } else {
                migrateSaveFileIfNeeded(file);
                File save = saveFileFor(file);
                File state = stateFileFor(file);
                if (!save.exists() && !state.exists()) {
                    continue;
                }
                ok = (!save.exists() || save.delete()) & (!state.exists() || state.delete());
            }
            if (ok) {
                done++;
            } else {
                failed++;
            }
        }
        refreshLibrary();
        String what = roms ? "ROM" : "세이브";
        if (failed > 0) {
            showError(what + " " + done + "개 삭제, " + failed + "개는 삭제하지 못했습니다.");
        } else {
            showMessage(done == 0 ? "삭제할 " + (roms ? "ROM이" : "세이브가") + " 없습니다." : what + " " + done + "개를 삭제했습니다.");
        }
    }

    private void confirmDeleteRom(final File file) {
        new AlertDialog.Builder(this).setTitle("ROM을 삭제할까요?").setMessage("Mini Emulator 안에 가져온 복사본만 삭제합니다. 원본 파일은 그대로 둡니다.").setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, (dialogInterface, i) -> deleteRomConfirmed(file, dialogInterface, i)).show();
    }

    private void deleteRomConfirmed(File file, DialogInterface dialogInterface, int i) {
        if (file.delete()) {
            romPrefs().edit().remove("title:" + file.getName()).remove("save:" + file.getName()).remove("favorite:" + file.getName()).remove("recent:" + file.getName()).remove("core:" + file.getName()).apply();
            showMessage("ROM을 삭제했습니다.");
            refreshLibrary();
            return;
        }
        showError("ROM을 삭제할 수 없습니다.");
    }

    private File copyRomToAppStorage(Uri uri, String str, String mode) throws IOException {
        File romDir = romDirFor(mode);
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        File uniqueFile = uniqueFile(romDir, sanitizeFileName(str));
        try (InputStream openInputStream = getContentResolver().openInputStream(uri);
             FileOutputStream fileOutputStream = new FileOutputStream(uniqueFile)) {
            if (openInputStream == null) {
                throw new IOException("선택한 파일을 열 수 없습니다");
            }
            copy(openInputStream, fileOutputStream);
        }
        return finalizeImportedRom(uniqueFile, baseNameWithoutExtension(str));
    }

    private File copyArcadeRomToAppStorage(Uri uri, String str) throws IOException {
        return copyArcadeRomToAppStorage(uri, str, this.currentMode);
    }

    private File copyArcadeRomToAppStorage(Uri uri, String str, String mode) throws IOException {
        File romDir = romDirFor(mode);
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        File uniqueFile = uniqueFile(romDir, sanitizeFileName(str));
        try (InputStream openInputStream = getContentResolver().openInputStream(uri);
             FileOutputStream fileOutputStream = new FileOutputStream(uniqueFile)) {
            if (openInputStream == null) {
                throw new IOException("선택한 ZIP 파일을 열 수 없습니다");
            }
            copy(openInputStream, fileOutputStream);
        }
        if (uniqueFile.length() == 0) {
            if (!uniqueFile.delete()) {
                uniqueFile.deleteOnExit();
            }
            throw new IOException("비어 있는 ZIP 파일입니다");
        }
        File finalizeImportedRom = finalizeImportedRom(uniqueFile, baseNameWithoutExtension(str));

        return finalizeImportedRom;
    }

    private List<File> copyZippedRomsToAppStorage(Uri uri, String str, String mode) throws IOException {
        File romDir = romDirFor(mode);
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        // The ZIP is copied first so its UTF-8 flags can be cleared before reading the names.
        File temp = File.createTempFile("import", ".zip", getCacheDir());
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) {
                    throw new IOException("선택한 ZIP 파일을 열 수 없습니다");
                }
                copy(in, out);
            }
            clearZipUtf8Flags(temp);
            List<File> arrayList = new ArrayList<>();
            boolean foundRom = false;
            try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(temp), StandardCharsets.ISO_8859_1)) {
                while (true) {
                    ZipEntry entry = zipInputStream.getNextEntry();
                    if (entry == null) {
                        break;
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = new File(decodeZipEntryName(entry.getName())).getName();
                    if (!isRomNameFor(mode, name.toLowerCase(Locale.US))) {
                        continue;
                    }
                    foundRom = true;
                    File uniqueFile = uniqueFile(romDir, sanitizeFileName(name));
                    try (FileOutputStream out = new FileOutputStream(uniqueFile)) {
                        copy(zipInputStream, out);
                    }
                    File imported = finalizeImportedRom(uniqueFile, baseNameWithoutExtension(name));
                    if (imported != null) {
                        arrayList.add(imported);
                    }
                }
            }
            if (!foundRom) {
                throw new IOException(str + (isConsoleMode(mode) ? " 안에서 " + MODE_NAMES[modeIndex(mode)] + " ROM 파일을 찾지 못했습니다" : " 안에서 .gba / .gbc / .gb 파일을 찾지 못했습니다"));
            }
            return arrayList;
        } finally {
            temp.delete();
        }
    }

    // Clears the UTF-8 flag on every entry of a copied ZIP. Some tools set it on CP949 names,
    // which java.util.zip then garbles or rejects; without it every name comes through
    // ISO-8859-1 as its raw bytes for decodeZipEntryName. Best effort: ZIP64 or damaged
    // archives are left as they are.
    static void clearZipUtf8Flags(File zip) {
        try (RandomAccessFile raf = new RandomAccessFile(zip, "rw")) {
            long length = raf.length();
            int tail = (int) Math.min(length, 65557);
            byte[] end = new byte[tail];
            raf.seek(length - tail);
            raf.readFully(end);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (end[i] == 0x50 && end[i + 1] == 0x4B && end[i + 2] == 0x05 && end[i + 3] == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return;
            }
            int count = le16(end, eocd + 10);
            long offset = le32(end, eocd + 16);
            if (count == 0xFFFF || offset == 0xFFFFFFFFL || offset >= length) {
                return;
            }
            byte[] header = new byte[46];
            long position = offset;
            for (int n = 0; n < count; n++) {
                raf.seek(position);
                raf.readFully(header);
                if (le32(header, 0) != 0x02014B50L) {
                    return;
                }
                long local = le32(header, 42);
                long next = position + 46 + le16(header, 28) + le16(header, 30) + le16(header, 32);
                if ((header[9] & 0x08) != 0) {
                    raf.seek(position + 9);
                    raf.write(header[9] & ~0x08);
                }
                if (local + 30 <= length) {
                    raf.seek(local);
                    if (raf.readInt() == 0x504B0304) {
                        raf.seek(local + 7);
                        int flag = raf.read();
                        raf.seek(local + 7);
                        raf.write(flag & ~0x08);
                    }
                }
                position = next;
            }
        } catch (IOException e) {
            // leave the archive as it is
        }
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 255) | ((b[at + 1] & 255) << 8);
    }

    private static long le32(byte[] b, int at) {
        return (le16(b, at) | ((long) le16(b, at + 2) << 16)) & 0xFFFFFFFFL;
    }

    // UTF-8 when the bytes are valid UTF-8, otherwise the Korean Windows code page (CP949).
    static String decodeZipNameBytes(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            for (String name : new String[]{"MS949", "x-windows-949", "EUC-KR"}) {
                try {
                    return new String(bytes, Charset.forName(name));
                } catch (RuntimeException unsupported) {
                    // try the next name for the Korean code page
                }
            }
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    // Names without the UTF-8 flag come through ISO-8859-1 as their raw bytes and are decoded again.
    static String decodeZipEntryName(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) > 0xFF) {
                // The entry had the UTF-8 flag and is already decoded.
                return raw;
            }
        }
        return decodeZipNameBytes(raw.getBytes(StandardCharsets.ISO_8859_1));
    }

    private File finalizeImportedRom(File file, String str) throws IOException {
        String crc32Hex = crc32Hex(file);
        File existing = findRomByCrc(crc32Hex, file);
        if (existing != null && str != null && str.indexOf('\uFFFD') < 0 && displayGameNameFor(existing).indexOf('\uFFFD') >= 0) {
            // Importing again repairs a title that an older version garbled; saves keep their name.
            romPrefs().edit().putString("title:" + existing.getName(), str.trim()).apply();
        }
        if (existing != null) {
            if (file.delete()) {
                return null;
            }
            file.deleteOnExit();
            return null;
        }
        storeRomMetadata(file, str, crc32Hex);
        return file;
    }

    private File findRomByCrc(String str, File file) {
        File[] listFiles = file.getParentFile() == null ? null : file.getParentFile().listFiles(this::isRomFile);
        if (listFiles == null) {
            return null;
        }
        for (File file2 : listFiles) {
            if (!file2.equals(file) && str.equals(safeCrc32Hex(file2))) {
                return file2;
            }
        }
        return null;
    }

    private void storeRomMetadata(File file, String str, String str2) {
        String displayGameNameFor;
        if (str == null || str.trim().isEmpty()) {
            displayGameNameFor = displayGameNameFor(file);
        } else {
            displayGameNameFor = str.trim();
        }
        romPrefs().edit().putString("title:" + file.getName(), displayGameNameFor).putString("save:" + file.getName(), sanitizeSaveBaseName(displayGameNameFor + " [" + str2 + "]")).apply();
    }

    private File uniqueFile(File file, String str) {
        File file2 = new File(file, str);
        if (!file2.exists()) {
            return file2;
        }
        String baseNameWithoutExtension = baseNameWithoutExtension(str);
        String extensionOf = extensionOf(str);
        int i = 2;
        while (true) {
            File file3 = new File(file, baseNameWithoutExtension + " (" + i + ")" + extensionOf);
            if (!file3.exists()) {
                return file3;
            }
            i++;
        }
    }

    private void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] bArr = new byte[65536];
        while (true) {
            int read = inputStream.read(bArr);
            if (read == -1) {
                return;
            } else {
                outputStream.write(bArr, 0, read);
            }
        }
    }

    private void writeZipEntry(ZipOutputStream zipOutputStream, File file, String str) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(str));
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            copy(fileInputStream, zipOutputStream);
        }
        zipOutputStream.closeEntry();
    }

    private List<File> singletonOrEmpty(File file) {
        ArrayList arrayList = new ArrayList();
        if (file != null) {
            arrayList.add(file);
        }
        return arrayList;
    }

    private String displayNameFor(Uri uri) {
        try (Cursor query = getContentResolver().query(uri, null, null, null, null)) {
            if (query != null && query.moveToFirst()) {
                int columnIndex = query.getColumnIndex("_display_name");
                if (columnIndex >= 0) {
                    String string = query.getString(columnIndex);
                    if (string != null && !string.isEmpty()) {
                        return string;
                    }
                }
            }
        }
        String lastPathSegment = uri.getLastPathSegment();
        return lastPathSegment == null ? "선택한파일.gba" : lastPathSegment;
    }

    private void checkImportedRoms() {
        File[] listFiles = currentRomDir().listFiles(this::isRomFile);
        if (listFiles == null || listFiles.length == 0) {
            showMessage("검사할 ROM이 없습니다.");
            return;
        }
        Arrays.sort(listFiles, Comparator.comparing((File f) -> f.getName().toLowerCase(Locale.US)));
        HashMap<String, File> seen = new HashMap<>();
        List<String[]> issues = new ArrayList<>();
        int ok = 0;
        int duplicates = 0;
        for (File file : listFiles) {
            RomCheck check = checkRomFile(file);
            String crc = safeCrc32Hex(file);
            File first = seen.get(crc);
            if (first != null) {
                duplicates++;
                issues.add(new String[]{displayGameNameFor(file), "중복", "중복 ROM - " + displayGameNameFor(first)});
            } else {
                seen.put(crc, file);
                if (check.ok) {
                    ok++;
                } else {
                    issues.add(new String[]{displayGameNameFor(file), shortIssue(check.detail), check.detail});
                }
            }
        }
        showRomCheckResult(listFiles.length, ok, duplicates, issues);
    }

    // Short badge text for a check result.
    private static String shortIssue(String detail) {
        if (detail.contains("체크섬")) {
            return "체크섬";
        }
        if (detail.contains("로고")) {
            return "로고 불일치";
        }
        if (detail.contains("파일 부족")) {
            return "파일 부족";
        }
        if (detail.contains("읽기")) {
            return "읽기 실패";
        }
        if (detail.contains("찾지 못")) {
            return "모르는 롬셋";
        }
        return detail.length() > 8 ? detail.substring(0, 8) + "…" : detail;
    }

    private void showRomCheckResult(int total, int ok, int duplicates, List<String[]> issues) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(1);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(1);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(rounded(-1, dp(24)));
        TextView title = new TextView(this);
        title.setText("ROM 검사");
        title.setTextSize(17.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setPadding(dp(4), 0, dp(4), 0);
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(MODE_NAMES[modeIndex(this.currentMode)] + " · " + total + "개 검사");
        sub.setTextSize(12.0f);
        sub.setTextColor(Color.rgb(122, 130, 140));
        sub.setPadding(dp(4), dp(2), dp(4), 0);
        panel.addView(sub);

        int warn = total - ok - duplicates;
        RingView ring = new RingView(this, ok, warn, duplicates);
        ring.setCenter(String.valueOf(ok), "/ " + total + " 정상");
        LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        ringParams.gravity = 1;
        ringParams.setMargins(0, dp(10), 0, 0);
        panel.addView(ring, ringParams);
        TextView legend = new TextView(this);
        String legendText = "● 정상 " + ok + "    ● 확인 필요 " + warn + (duplicates > 0 ? "    ● 중복 " + duplicates : "");
        android.text.SpannableString spans = new android.text.SpannableString(legendText);
        colorSpan(spans, legendText, "● 정상 " + ok, RingView.GREEN);
        colorSpan(spans, legendText, "● 확인 필요 " + warn, RingView.AMBER);
        if (duplicates > 0) {
            colorSpan(spans, legendText, "● 중복 " + duplicates, RingView.GREY);
        }
        legend.setText(spans);
        legend.setTextSize(12.0f);
        legend.setTypeface(Typeface.DEFAULT_BOLD);
        legend.setGravity(1);
        legend.setPadding(0, dp(8), 0, dp(4));
        panel.addView(legend, new LinearLayout.LayoutParams(-1, -2));

        if (!issues.isEmpty()) {
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(1);
            for (String[] issue : issues) {
                list.addView(createIssueRow(issue[0], issue[1], issue[2]));
            }
            ScrollView scroll = new ScrollView(this);
            scroll.addView(list);
            int height = issues.size() > 5 ? dp(250) : -2;
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, height));
            TextView hint = new TextView(this);
            boolean arcade = "arcade".equals(this.currentMode) || "neogeo".equals(this.currentMode);
            hint.setText(arcade ? "파일이 부족한 롬셋은 실행되지 않을 수 있어요" : "한글 패치 롬은 헤더가 달라도 대부분 실행돼요");
            hint.setTextSize(11.0f);
            hint.setTextColor(Color.rgb(138, 146, 156));
            hint.setGravity(1);
            hint.setPadding(0, dp(8), 0, 0);
            panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView done = new TextView(this);
        done.setText("확인");
        done.setTextSize(15.0f);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setTextColor(-1);
        done.setGravity(17);
        done.setBackground(rounded(Color.rgb(22, 185, 207), dp(14)));
        done.setOnClickListener(view -> dialog.dismiss());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, dp(48));
        doneParams.setMargins(0, dp(12), 0, 0);
        panel.addView(done, doneParams);
        showCardDialog(dialog, panel, -2);
    }

    private static void colorSpan(android.text.SpannableString spans, String text, String part, int color) {
        int start = text.indexOf(part);
        if (start >= 0) {
            spans.setSpan(new android.text.style.ForegroundColorSpan(color), start, start + part.length(), 0);
        }
    }

    // A problem ROM: its name and a coloured badge; the full reason is shown on tap.
    private View createIssueRow(String name, String badge, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(0);
        row.setGravity(16);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(rounded(Color.rgb(246, 247, 249), dp(12)));
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(13.0f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        int color = "중복".equals(badge) ? RingView.GREY : "체크섬".equals(badge) ? RingView.AMBER : RoundIconView.COLOR_EXIT;
        TextView tag = new TextView(this);
        tag.setText(badge);
        tag.setTextSize(10.5f);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(color);
        tag.setPadding(dp(7), dp(3), dp(7), dp(3));
        tag.setBackground(rounded(Color.argb(31, Color.red(color), Color.green(color), Color.blue(color)), dp(7)));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(-2, -2);
        tagParams.setMargins(dp(8), 0, 0, 0);
        row.addView(tag, tagParams);
        row.setClickable(true);
        row.setOnClickListener(view -> showMessage(name + "\n" + detail));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(params);
        return row;
    }

    private RomCheck checkRomFile(File file) {
        if (!file.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            return checkRomHeader(file);
        }
        if (isArcadeMode(modeForFile(file))) {
            return checkArcadeRomset(file);
        }
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry nextEntry;
            do {
                nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    return new RomCheck(false, "비어 있는 ZIP 파일");
                }
            } while (nextEntry.isDirectory());
            return new RomCheck(true, coreDisplayName(coreFor(file)) + " ZIP 읽기 가능");
        } catch (IOException unused) {
            return new RomCheck(false, "ZIP 읽기 실패");
        }
    }

    // GBA ROMs and Game Boy / Game Boy Color ROMs share the GBA mode and the mGBA engine.
    static boolean isHandheldRomName(String lowerCaseName) {
        return lowerCaseName.endsWith(".gba") || isGameBoyRomName(lowerCaseName);
    }

    static boolean isSnesRomName(String lowerCaseName) {
        return lowerCaseName.endsWith(".sfc") || lowerCaseName.endsWith(".smc");
    }

    private static boolean isRomNameFor(String mode, String lowerCaseName) {
        String[] console = consoleFor(mode);
        return console != null ? hasExtension(lowerCaseName, console[2]) : isHandheldRomName(lowerCaseName);
    }

    static boolean isGameBoyRomName(String lowerCaseName) {
        return lowerCaseName.endsWith(".gbc") || lowerCaseName.endsWith(".gb");
    }

    // Game Boy header: Nintendo logo at 0x104 and a checksum over 0x134-0x14C at 0x14D.
    private RomCheck checkGameBoyHeader(File file) {
        if (file.length() < 0x150) {
            return new RomCheck(false, "파일이 너무 작음");
        }
        if (file.length() > 8388608) {
            return new RomCheck(false, "표준 8MB Game Boy ROM보다 큼");
        }
        byte[] header = new byte[0x150];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    return new RomCheck(false, "Game Boy 헤더를 끝까지 읽을 수 없음");
                }
                read += n;
            }
        } catch (IOException unused) {
            return new RomCheck(false, "읽기 실패");
        }
        // The first 24 bytes of the logo are the same in every licensed cartridge.
        int[] logo = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E};
        boolean logoOk = true;
        for (int i = 0; i < logo.length; i++) {
            if ((header[0x104 + i] & 255) != logo[i]) {
                logoOk = false;
                break;
            }
        }
        int sum = 0;
        for (int i = 0x134; i <= 0x14C; i++) {
            sum = (sum - (header[i] & 255) - 1) & 255;
        }
        boolean checksumOk = (header[0x14D] & 255) == sum;
        boolean color = (header[0x143] & 255) == 0x80 || (header[0x143] & 255) == 0xC0;
        if (logoOk && checksumOk) {
            return new RomCheck(true, color ? "GBC 헤더 정상" : "Game Boy 헤더 정상");
        }
        return new RomCheck(false, !logoOk ? "Nintendo 로고/헤더 불일치" : "헤더 체크섬 불일치");
    }

    // SNES internal header: a checksum and its complement at 0x7FDC (LoROM) or 0xFFDC
    // (HiROM), after an optional 512-byte copier header.
    private RomCheck checkSnesHeader(File file) {
        long length = file.length();
        if (length < 0x8000) {
            return new RomCheck(false, "파일이 너무 작음");
        }
        if (length > 12 * 1024 * 1024) {
            return new RomCheck(false, "표준 SNES ROM보다 큼");
        }
        int skip = (length % 1024) == 512 ? 512 : 0;
        int[] bases = {0x7FC0, 0xFFC0};
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (int base : bases) {
                long at = skip + base + 0x1C;
                if (at + 4 > length) {
                    continue;
                }
                raf.seek(at);
                int complement = raf.read() | (raf.read() << 8);
                int checksum = raf.read() | (raf.read() << 8);
                if ((complement ^ checksum) == 0xFFFF) {
                    return new RomCheck(true, (base == 0x7FC0 ? "LoROM" : "HiROM") + " 헤더 정상");
                }
            }
        } catch (IOException unused) {
            return new RomCheck(false, "읽기 실패");
        }
        return new RomCheck(false, "SNES 헤더를 찾지 못함 (실행은 될 수 있음)");
    }

    // iNES / NES 2.0 files start with "NES" and 0x1A; UNIF files with "UNIF".
    private RomCheck checkNesHeader(File file) {
        byte[] head = readHead(file, 16);
        if (head == null) {
            return new RomCheck(false, "읽기 실패");
        }
        if (head[0] == 'N' && head[1] == 'E' && head[2] == 'S' && head[3] == 0x1A) {
            return new RomCheck(true, ((head[7] & 0x0C) == 0x08 ? "NES 2.0" : "iNES") + " 헤더 정상");
        }
        if (head[0] == 'U' && head[1] == 'N' && head[2] == 'I' && head[3] == 'F') {
            return new RomCheck(true, "UNIF 헤더 정상");
        }
        return new RomCheck(false, "NES 헤더 없음");
    }

    // Mega Drive ROMs carry "SEGA" at 0x100; .smd dumps are interleaved, Master System and
    // Game Gear ROMs have no such header, so those are only size-checked.
    private RomCheck checkMegaDriveHeader(File file) {
        String lower = file.getName().toLowerCase(Locale.US);
        if (file.length() < 0x200) {
            return new RomCheck(false, "파일이 너무 작음");
        }
        if (lower.endsWith(".sms") || lower.endsWith(".gg") || lower.endsWith(".smd")) {
            return new RomCheck(true, "크기 정상");
        }
        byte[] head = readHead(file, 0x110);
        if (head == null) {
            return new RomCheck(false, "읽기 실패");
        }
        String sega = new String(head, 0x100, 16, StandardCharsets.ISO_8859_1);
        return sega.contains("SEGA") ? new RomCheck(true, "메가드라이브 헤더 정상") : new RomCheck(false, "SEGA 헤더 없음");
    }

    private static byte[] readHead(File file, int length) {
        byte[] head = new byte[length];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < length) {
                int n = in.read(head, read, length - read);
                if (n < 0) {
                    return null;
                }
                read += n;
            }
            return head;
        } catch (IOException e) {
            return null;
        }
    }

    private RomCheck checkRomHeader(File file) {
        String mode = modeForFile(file);
        if (MODE_NES.equals(mode)) {
            return checkNesHeader(file);
        }
        if (MODE_MD.equals(mode)) {
            return checkMegaDriveHeader(file);
        }
        if (MODE_PCE.equals(mode)) {
            // HuCards have no header; dumps are whole 8KB banks, some with a 512-byte copier header.
            long size = file.length() % 8192;
            return size == 0 || size == 512 ? new RomCheck(true, "크기 정상") : new RomCheck(false, "HuCard 크기가 아님");
        }
        if (isSnesRomName(file.getName().toLowerCase(Locale.US))) {
            return checkSnesHeader(file);
        }
        if (isGameBoyRomName(file.getName().toLowerCase(Locale.US))) {
            return checkGameBoyHeader(file);
        }
        if (file.length() < 192) {
            return new RomCheck(false, "파일이 너무 작음");
        }
        if (file.length() > 33554432) {
            return new RomCheck(false, "표준 32MB GBA ROM보다 큼");
        }
        byte[] bArr = new byte[192];
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            if (fileInputStream.read(bArr) != 192) {
                return new RomCheck(false, "GBA 헤더를 끝까지 읽을 수 없음");
            }
        } catch (IOException unused) {
            return new RomCheck(false, "읽기 실패");
        }
        boolean z = true;
        for (int i = 0; i < GBA_LOGO.length; i++) {
            if (bArr[i + 4] != GBA_LOGO[i]) {
                z = false;
                break;
            }
        }
        int i2 = 0;
        for (int i3 = 160; i3 <= 188; i3++) {
            i2 = (i2 - (bArr[i3] & 255)) & 255;
        }
        boolean z2 = (bArr[189] & 255) == ((i2 + (-25)) & 255);
        if (z && z2) {
            return new RomCheck(true, "헤더 정상");
        }
        if (!z) {
            return new RomCheck(false, "Nintendo 로고/헤더 불일치");
        }
        return new RomCheck(false, "헤더 체크섬 불일치");
    }

    private void updateNote() {
        if (this.noteView == null) {
            return;
        }
        String bios;
        if ("neogeo".equals(this.currentMode)) {
            bios = "Neo Geo BIOS: " + neoGeoBiosStatusText();
        } else if ("arcade".equals(this.currentMode)) {
            bios = "MAME BIOS: " + mameBiosStatus() + " · 게임을 길게 누르면 실행 엔진을 바꿀 수 있습니다.";
        } else if (isConsoleMode(this.currentMode)) {
            bios = MODE_NAMES[modeIndex(this.currentMode)] + " 게임은 BIOS 없이 실행됩니다.";
        } else {
            bios = "GBA BIOS: " + biosStatusText();
        }
        this.noteView.setText("상업용 ROM/BIOS는 포함되어 있지 않습니다. ROM을 길게 누르면 세이브를 관리할 수 있습니다.\n세이브 파일 경로: " + saveDir().getAbsolutePath() + "\n" + bios);
    }

    private void updateScreenModeButton(Button button) {
        button.setText("화면 모드: " + SCREEN_MODE_LABELS[screenMode()]);
    }

    private int screenMode() {
        int i = Arrays.asList(SCREEN_STYLES).indexOf(settingsPrefs().getString("screen_style", SCREEN_STYLES[0]));
        if (i < 0 || i >= SCREEN_MODE_LABELS.length) {
            return 0;
        }
        return i;
    }

    private File romDirFor(String mode) {
        String[] console = consoleFor(mode);
        if (console != null) {
            return new File(getFilesDir(), console[4]);
        }
        if ("neogeo".equals(mode)) {
            return new File(getFilesDir(), "roms_neogeo");
        }
        if ("arcade".equals(mode)) {
            return arcadeRomDir();
        }
        return romDir();
    }

    private File currentRomDir() {
        return romDirFor(this.currentMode);
    }

    private File arcadeRomDir() {
        return new File(getFilesDir(), "roms_arcade");
    }

    private String modeForFile(File file) {
        File parent = file.getParentFile();
        if (romDirFor("neogeo").equals(parent)) {
            return "neogeo";
        }
        for (String[] console : CONSOLES) {
            if (romDirFor(console[0]).equals(parent)) {
                return console[0];
            }
        }
        if (arcadeRomDir().equals(parent)) {
            return "arcade";
        }
        return MODE_GBA;
    }

    private File romDir() {
        return new File(getFilesDir(), "roms");
    }

    private File systemDir() {
        return new File(getFilesDir(), "system");
    }

    private File biosFile() {
        return new File(systemDir(), "gba_bios.bin");
    }

    private File neoGeoBiosFile() {
        return new File(systemDir(), "neogeo.zip");
    }

    private File mameBiosDir() {
        return new File(new File(systemDir(), "mame"), "bios");
    }

    private String mameBiosStatus() {
        File[] listFiles = mameBiosDir().listFiles(MainActivity::isZipFile);
        int length = listFiles == null ? 0 : listFiles.length;
        if (length == 0) {
            return "등록 안 됨";
        }
        File file = new File(mameBiosDir(), "stvbios.zip");
        if (file.exists()) {
            if (zipContainsEntry(file, "epr-23603.ic8") && zipContainsEntry(file, "epr-18022.ic2")) {
                return "ST-V 준비됨 · " + length + "개";
            }
            return "ST-V 불완전 · " + length + "개";
        }
        return length + "개 등록됨";
    }

    private String mameBiosDetail(File file) {
        String str;
        if (file.getName().equalsIgnoreCase("stvbios.zip")) {
            str = (zipContainsEntry(file, "epr-23603.ic8") && zipContainsEntry(file, "epr-18022.ic2")) ? "ST-V 정상" : "ST-V 불완전";
        } else if (!file.getName().equalsIgnoreCase("neogeo.zip")) {
            str = "등록됨";
        } else {
            str = "Neo Geo BIOS";
        }
        return formatFileSize(file.length()) + " · " + str;
    }

    private String formatFileSize(long j) {
        if (j >= 1048576) {
            return String.format(Locale.US, "%.1f MB", Double.valueOf(j / 1048576.0d));
        }
        if (j >= 1024) {
            return String.format(Locale.US, "%.1f KB", Double.valueOf(j / 1024.0d));
        }
        return j + " B";
    }

    private boolean isReadableZip(File file) {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            return zipInputStream.getNextEntry() != null;
        } catch (IOException unused) {
            return false;
        }
    }

    private boolean zipContainsEntry(File file, String str) {
        if (!file.isFile()) {
            return false;
        }
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    return false;
                }
                if (new File(nextEntry.getName().replace('\\', '/')).getName().equalsIgnoreCase(str)) {
                    return true;
                }
            }
        } catch (IOException unused) {
            return false;
        }
    }

    private File saveDir() {
        return new File(getFilesDir(), "saves");
    }

    private File saveFileFor(File file) {
        return new File(new File(saveDir(), coreFor(file)), saveBaseNameFor(file) + ".sav");
    }

    private File stateFileFor(File file) {
        return new File(new File(saveDir(), coreFor(file)), saveBaseNameFor(file) + ".state");
    }

    private File legacySaveFileFor(File file) {
        return new File(saveDir(), baseNameWithoutExtension(file.getName()) + ".sav");
    }

    private File legacyStateFileFor(File file) {
        return new File(saveDir(), baseNameWithoutExtension(file.getName()) + ".state");
    }

    private void migrateSaveFileIfNeeded(File file) {
        if (coreFor(file).equals("gba")) {
            migrateLegacyFile(legacySaveFileFor(file), saveFileFor(file));
            migrateLegacyFile(legacyStateFileFor(file), stateFileFor(file));
        }
        // Renaming in 0.0.1 moved saves to the top-level saves folder instead of
        // the per-core one, so the game no longer found them. Move them back.
        String saveBaseName = saveBaseNameFor(file);
        migrateLegacyFile(new File(saveDir(), saveBaseName + ".sav"), saveFileFor(file));
        migrateLegacyFile(new File(saveDir(), saveBaseName + ".state"), stateFileFor(file));
    }

    private void migrateLegacyFile(File file, File file2) {
        if (file.equals(file2) || !file.exists()) {
            return;
        }
        File parentFile = file2.getParentFile();
        if (parentFile == null || parentFile.exists() || parentFile.mkdirs()) {
            if (file2.exists()) {
                if (file.lastModified() > file2.lastModified()) {
                    copyFile(file, file2);
                }
                if (file.delete()) {
                    return;
                }
                file.deleteOnExit();
                return;
            }
            if (file.renameTo(file2) || !copyFile(file, file2) || file.delete()) {
                return;
            }
            file.deleteOnExit();
        }
    }

    private boolean copyFile(File file, File file2) {
        try (FileInputStream fileInputStream = new FileInputStream(file);
             FileOutputStream fileOutputStream = new FileOutputStream(file2)) {
            copy(fileInputStream, fileOutputStream);
            return true;
        } catch (IOException unused) {
            return false;
        }
    }

    // MAME 2003-Plus needs neogeo.zip next to the game; FBNeo also finds it there.
    private void syncNeoGeoBiosInto(File dir) {
        File bios = neoGeoBiosFile();
        File target = new File(dir, "neogeo.zip");
        if (!bios.exists() || (target.exists() && target.lastModified() >= bios.lastModified() && zipContainsEntry(target, "mamelo.lo"))) {
            return;
        }
        try {
            prepareNeoGeoBiosForArcade(bios, target);
        } catch (IOException ignored) {
            // The core reports the missing BIOS itself.
        }
    }

    private void syncNeoGeoBiosForArcade() {
        File neoGeoBiosFile = neoGeoBiosFile();
        if (neoGeoBiosFile.exists()) {
            File romDir = arcadeRomDir();
            if (romDir.exists() || romDir.mkdirs()) {
                try {
                    prepareNeoGeoBiosForArcade(neoGeoBiosFile, new File(romDir, "neogeo.zip"));
                } catch (IOException unused) {
                }
            }
        }
    }

    // MAME 2003-Plus only looks for BIOS archives next to the game, so copy the
    // registered ones into the arcade folder before launching.
    private void syncArcadeBiosFiles() {
        File dir = arcadeRomDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File[] bios = mameBiosDir().listFiles(MainActivity::isZipFile);
        if (bios != null) {
            for (File file : bios) {
                File target = new File(dir, file.getName());
                if (!target.exists() || target.lastModified() < file.lastModified()) {
                    copyFile(file, target);
                }
            }
        }
        syncNeoGeoBiosForArcade();
    }

    private void syncMameBiosStorage() {
        File file = new File(arcadeRomDir(), "stvbios.zip");
        if (file.isFile()) {
            File mameBiosDir = mameBiosDir();
            if (mameBiosDir.exists() || mameBiosDir.mkdirs()) {
                File file2 = new File(mameBiosDir, "stvbios.zip");
                if (file2.exists()) {
                    return;
                }
                copyFile(file, file2);
            }
        }
    }

    private void migrateKnownArcadeRoms() {
        File romDir = arcadeRomDir();
        File[] listFiles = romDir.listFiles(MainActivity::isZipFile);
        if (listFiles == null) {
            return;
        }
        for (File file : listFiles) {
            String replaceAll = baseNameWithoutExtension(file.getName()).toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            boolean z = true;
            if (replaceAll.equals("twcup98") || replaceAll.startsWith("tecmoworldcup98")) {
                File file2 = new File(romDir, "twcup98.zip");
                if (!file2.equals(file) && !file2.exists() && !file.renameTo(file2)) {
                    z = false;
                }
                if (!z && copyFile(file, file2)) {
                    z = file.delete();
                }
                if (z) {
                    normalizeTecmoWorldCup98Package(file2);
                    romPrefs().edit().putString("title:" + file2.getName(), "Tecmo World Cup 98").putString("core:" + file2.getName(), "mame2010").apply();
                }
            } else if (replaceAll.equals("bloodroar2") || replaceAll.equals("bloodyroar2") || replaceAll.equals("bldyror2")) {
                File file3 = new File(romDir, "bldyror2.zip");
                if (!file3.exists() && !file.renameTo(file3)) {
                    z = false;
                }
                if (!z && copyFile(file, file3)) {
                    z = file.delete();
                }
                if (z) {
                    normalizeBloodRoar2Package(file3);
                    romPrefs().edit().putString("title:" + file3.getName(), "동물철권2 (Blood Roar 2)").putString("core:" + file3.getName(), "mame2010").apply();
                }
            }
        }
    }

    private void normalizeTecmoWorldCup98Package(File file) {
        byte[] bArr = null;
        byte[] bArr2 = null;
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (nextEntry.isDirectory()) {
                    continue;
                }
                String lowerCase = new File(nextEntry.getName().replace('\\', '/')).getName().toLowerCase(Locale.US);
                if (lowerCase.equals("twcup98.zip") || lowerCase.equals("stvbios.zip")) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    copy(zipInputStream, byteArrayOutputStream);
                    if (lowerCase.equals("twcup98.zip")) {
                        bArr = byteArrayOutputStream.toByteArray();
                    } else {
                        bArr2 = byteArrayOutputStream.toByteArray();
                    }
                }
            }
        } catch (IOException unused) {
            return;
        }
        if (bArr != null) {
            writeBytesAtomically(file, bArr);
        }
        if (bArr2 != null) {
            writeBytesAtomically(new File(file.getParentFile(), "stvbios.zip"), bArr2);
        }
    }

    private void removeMergedBiosEntries(File file, File file2) {
        if (!file.isFile() || !file2.isFile()) {
            return;
        }
        List<String> biosEntries = new ArrayList<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file2))) {
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (!nextEntry.isDirectory()) {
                    biosEntries.add(new File(nextEntry.getName().replace('\\', '/')).getName().toLowerCase(Locale.US));
                }
            }
        } catch (IOException unused) {
            return;
        }
        if (biosEntries.isEmpty()) {
            return;
        }
        File file3 = new File(file.getParentFile(), file.getName() + ".clean");
        try {
            try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
                 ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3))) {
                while (true) {
                    ZipEntry nextEntry2 = zipInputStream.getNextEntry();
                    if (nextEntry2 == null) {
                        break;
                    }
                    if (!biosEntries.contains(new File(nextEntry2.getName().replace('\\', '/')).getName().toLowerCase(Locale.US))) {
                        zipOutputStream.putNextEntry(new ZipEntry(nextEntry2.getName()));
                        if (!nextEntry2.isDirectory()) {
                            copy(zipInputStream, zipOutputStream);
                        }
                        zipOutputStream.closeEntry();
                    }
                }
            }
            if (copyFile(file3, file) && !file3.delete()) {
                file3.deleteOnExit();
            }
        } catch (IOException unused) {
            if (file3.exists() && !file3.delete()) {
                file3.deleteOnExit();
            }
        }
    }

    private void mergeArcadeBiosIntoRom(File file, File file2) {
        if (!file.exists() || !file2.exists()) {
            return;
        }
        File file3 = new File(file.getParentFile(), file.getName() + ".merged");
        List<String> arrayList = new ArrayList<>();
        try {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3))) {
                copyZipEntries(file, zipOutputStream, arrayList);
                copyZipEntries(file2, zipOutputStream, arrayList);
            }
            if (file3.length() == 0 || (file.exists() && !file.delete())) {
                if (!file3.delete()) {
                    file3.deleteOnExit();
                }
                return;
            }
            if (file3.renameTo(file)) {
                return;
            }
            copyFile(file3, file);
            if (!file3.delete()) {
                file3.deleteOnExit();
            }
        } catch (IOException unused) {
            if (!file3.delete()) {
                file3.deleteOnExit();
            }
        }
    }

    private void copyZipEntries(File file, ZipOutputStream zipOutputStream, List<String> list) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    return;
                }
                if (nextEntry.isDirectory()) {
                    continue;
                }
                String name = new File(nextEntry.getName().replace('\\', '/')).getName();
                String lowerCase = name.toLowerCase(Locale.US);
                if (!name.isEmpty() && !list.contains(lowerCase)) {
                    list.add(lowerCase);
                    zipOutputStream.putNextEntry(new ZipEntry(name));
                    copy(zipInputStream, zipOutputStream);
                    zipOutputStream.closeEntry();
                }
            }
        }
    }

    private void writeBytesAtomically(File file, byte[] bArr) {
        File file2 = new File(file.getParentFile(), file.getName() + ".importing");
        try {
            try (FileOutputStream fileOutputStream = new FileOutputStream(file2)) {
                fileOutputStream.write(bArr);
            }
            if (file.exists() && !file.delete()) {
                if (!file2.delete()) {
                    file2.deleteOnExit();
                }
                return;
            }
            if (file2.renameTo(file)) {
                return;
            }
            copyFile(file2, file);
            if (!file2.delete()) {
                file2.deleteOnExit();
            }
        } catch (IOException unused) {
            if (!file2.delete()) {
                file2.deleteOnExit();
            }
        }
    }

    private void normalizeBloodRoar2Package(File file) {
        byte[] nestedZip = null;
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
            try {
                while (true) {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        break;
                    }
                    String lowerCase = nextEntry.getName().replace('\\', '/').toLowerCase(Locale.US);
                    if (!nextEntry.isDirectory() && lowerCase.endsWith("/bldyror2.zip")) {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        copy(zipInputStream, byteArrayOutputStream);
                        nestedZip = byteArrayOutputStream.toByteArray();
                        break;
                    }
                }
            } finally {
                zipInputStream.close();
            }
        } catch (IOException unused) {
            return;
        }
        File file2 = new File(file.getParentFile(), file.getName() + ".normalized");
        try {
            InputStream inputStream = nestedZip == null ? new FileInputStream(file) : new ByteArrayInputStream(nestedZip);
            try (ZipInputStream zipInputStream2 = new ZipInputStream(inputStream);
                 ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file2))) {
                boolean hasBios = false;
                boolean hasEt01 = false;
                boolean hasEt03 = false;
                boolean hasAt28c16 = false;
                while (true) {
                    ZipEntry nextEntry2 = zipInputStream2.getNextEntry();
                    if (nextEntry2 == null) {
                        break;
                    }
                    if (nextEntry2.isDirectory()) {
                        continue;
                    }
                    String name = new File(nextEntry2.getName()).getName();
                    String lowerCase2 = name.toLowerCase(Locale.US);
                    ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream();
                    copy(zipInputStream2, byteArrayOutputStream2);
                    byte[] byteArray = byteArrayOutputStream2.toByteArray();
                    writeZipBytes(zipOutputStream, name, byteArray);
                    if (lowerCase2.equals("coh-1002e.353") || lowerCase2.equals("m27c402cz-54.ic353")) {
                        if (!lowerCase2.equals("m27c402cz-54.ic353")) {
                            writeZipBytes(zipOutputStream, "m27c402cz-54.ic353", byteArray);
                        }
                        hasBios = true;
                    } else if (lowerCase2.equals("et01.ic652")) {
                        hasEt01 = true;
                    } else if (lowerCase2.equals("et03")) {
                        hasEt03 = true;
                    } else if (lowerCase2.equals("at28c16_world")) {
                        hasAt28c16 = true;
                    }
                }
                if (!hasBios) {
                    throw new IOException("동물철권2 시스템 BIOS가 없습니다");
                }
                if (!hasEt01) {
                    writeZipBytes(zipOutputStream, "et01.ic652", new byte[]{2, 8, 24, 28, -3, -63, 64, Byte.MIN_VALUE});
                }
                if (!hasEt03) {
                    writeZipBytes(zipOutputStream, "et03", new byte[]{-64, 8, -6, -30, -31, -3, 124, Byte.MIN_VALUE});
                }
                if (!hasAt28c16) {
                    writeZipBytes(zipOutputStream, "at28c16_world", new byte[2048]);
                }
            } finally {
                inputStream.close();
            }
            if (copyFile(file2, file) && !file2.delete()) {
                file2.deleteOnExit();
            }
        } catch (IOException unused2) {
            if (!file2.delete()) {
                file2.deleteOnExit();
            }
        }
    }

    private void writeZipBytes(ZipOutputStream zipOutputStream, String str, byte[] bArr) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(str));
        zipOutputStream.write(bArr);
        zipOutputStream.closeEntry();
    }

    // MAME 2003-Plus expects the old MAME names mame.sm1 and mamelo.lo (the first 64KB of
    // 000-lo.lo), which current neogeo.zip sets no longer include.
    private void prepareNeoGeoBiosForArcade(File file, File file2) throws IOException {
        boolean z = false;
        boolean hasMameLo = false;
        byte[] zoomTable = null;
        byte[] bArr = null;
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
            while (true) {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (nextEntry.isDirectory()) {
                    continue;
                }
                String lowerCase = new File(nextEntry.getName()).getName().toLowerCase(Locale.US);
                if (lowerCase.equals("mame.sm1")) {
                    z = true;
                } else if (lowerCase.equals("mamelo.lo")) {
                    hasMameLo = true;
                } else if (zoomTable == null && (lowerCase.equals("000-lo.lo") || lowerCase.equals("ng-lo.rom") || lowerCase.equals("lo.lo"))) {
                    ByteArrayOutputStream zoom = new ByteArrayOutputStream();
                    copy(zipInputStream, zoom);
                    zoomTable = zoom.toByteArray();
                } else if (bArr == null && (lowerCase.equals("sm1.sm1") || lowerCase.equals("sm1.bin") || lowerCase.equals("ng-sm1.rom"))) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    copy(zipInputStream, byteArrayOutputStream);
                    bArr = byteArrayOutputStream.toByteArray();
                }
            }
        }
        File file3 = new File(file2.getParentFile(), file2.getName() + ".tmp");
        try {
            try (ZipInputStream zipInputStream2 = new ZipInputStream(new FileInputStream(file));
                 ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3))) {
                while (true) {
                    ZipEntry nextEntry2 = zipInputStream2.getNextEntry();
                    if (nextEntry2 == null) {
                        break;
                    }
                    zipOutputStream.putNextEntry(new ZipEntry(nextEntry2.getName()));
                    if (!nextEntry2.isDirectory()) {
                        copy(zipInputStream2, zipOutputStream);
                    }
                    zipOutputStream.closeEntry();
                }
                if (!z && bArr != null) {
                    zipOutputStream.putNextEntry(new ZipEntry("mame.sm1"));
                    zipOutputStream.write(bArr);
                    zipOutputStream.closeEntry();
                }
                if (!hasMameLo && zoomTable != null && zoomTable.length >= 65536) {
                    zipOutputStream.putNextEntry(new ZipEntry("mamelo.lo"));
                    zipOutputStream.write(zoomTable, 0, 65536);
                    zipOutputStream.closeEntry();
                }
            }
            if (!copyFile(file3, file2)) {
                throw new IOException("BIOS를 아케이드 ROM 폴더에 준비할 수 없습니다");
            }
            if (!file3.delete()) {
                file3.deleteOnExit();
            }
        } catch (IOException e) {
            if (!file3.delete()) {
                file3.deleteOnExit();
            }
            throw e;
        }
    }

    private String saveBaseNameFor(File file) {
        SharedPreferences romPrefs = romPrefs();
        String string = romPrefs.getString("save:" + file.getName(), null);
        if (string != null && !string.trim().isEmpty()) {
            return repairGarbledSaveName(file, string);
        }
        String sanitizeSaveBaseName = sanitizeSaveBaseName(displayGameNameFor(file) + " [" + safeCrc32Hex(file) + "]");
        romPrefs.edit().putString("save:" + file.getName(), sanitizeSaveBaseName).apply();
        return sanitizeSaveBaseName;
    }

    // Older versions named saves after a title garbled from a CP949 ZIP. Once the title has been
    // repaired, the save files are renamed after it too; the CRC part stays the same.
    private String repairGarbledSaveName(File file, String base) {
        if (base.indexOf('\uFFFD') < 0) {
            return base;
        }
        String title = displayGameNameFor(file);
        if (title.indexOf('\uFFFD') >= 0) {
            return base;
        }
        int bracket = base.lastIndexOf(" [");
        String crc = bracket >= 0 && base.endsWith("]") ? base.substring(bracket + 2, base.length() - 1) : safeCrc32Hex(file);
        String repaired = sanitizeSaveBaseName(title + " [" + crc + "]");
        File dir = new File(saveDir(), coreFor(file));
        String[] suffixes = {".sav", ".state", ".state.failed", ".srm"};
        for (String suffix : suffixes) {
            if (new File(dir, base + suffix).exists() && new File(dir, repaired + suffix).exists()) {
                return base;
            }
        }
        List<String> moved = new ArrayList<>();
        for (String suffix : suffixes) {
            File from = new File(dir, base + suffix);
            if (!from.exists()) {
                continue;
            }
            if (!from.renameTo(new File(dir, repaired + suffix))) {
                // Put back what was already moved so the game keeps one complete set of saves.
                for (String done : moved) {
                    new File(dir, repaired + done).renameTo(new File(dir, base + done));
                }
                return base;
            }
            moved.add(suffix);
        }
        romPrefs().edit().putString("save:" + file.getName(), repaired).apply();
        return repaired;
    }

    private String displayGameNameFor(File file) {
        String readGbaHeaderTitle;
        String string = romPrefs().getString("title:" + file.getName(), null);
        String baseNameWithoutExtension = baseNameWithoutExtension(file.getName());
        String mode = modeForFile(file);
        if ("arcade".equals(mode) || "neogeo".equals(mode)) {
            // Arcade ZIPs are named after their romset; show the game's title unless the
            // player gave it a name of their own. A stored name that is itself a romset name
            // is automatic too: it is left over when an import renamed the ZIP to the set
            // its contents matched (kof98 -> kof98h).
            String stored = string == null ? "" : string.trim();
            if (stored.isEmpty() || stored.equalsIgnoreCase(baseNameWithoutExtension) || GameTitles.lookup(this, stored) != null) {
                String title = GameTitles.lookup(this, baseNameWithoutExtension);
                if (title == null && !stored.isEmpty()) {
                    title = GameTitles.lookup(this, stored);
                }
                return title != null ? title : baseNameWithoutExtension;
            }
            return string;
        }
        if (string != null && !string.trim().isEmpty()) {
            return string;
        }
        if (!MODE_GBA.equals(mode)) {
            return baseNameWithoutExtension;
        }
        return (hasDisplayableName(baseNameWithoutExtension) || (readGbaHeaderTitle = readGbaHeaderTitle(file)) == null) ? baseNameWithoutExtension : readGbaHeaderTitle;
    }

    private boolean isSupportedRom(File file) {
        String lowerCase = file.getName().toLowerCase(Locale.US);
        String mode = modeForFile(file);
        if (isConsoleMode(mode)) {
            return isRomNameFor(mode, lowerCase);
        }
        if (MODE_GBA.equals(mode)) {
            return isHandheldRomName(lowerCase) || !(!lowerCase.endsWith(".zip") || lowerCase.equals("neogeo.zip") || lowerCase.equals("stvbios.zip"));
        }
        // Arcade folders also hold copies of the BIOS archives the cores look for.
        return lowerCase.endsWith(".zip") && !lowerCase.equals("neogeo.zip") && !new File(mameBiosDir(), file.getName()).exists() && !RomsetIndex.isBiosName(this, baseNameWithoutExtension(lowerCase));
    }

    private String coreFor(File file) {
        String mode = modeForFile(file);
        if ("neogeo".equals(mode)) {
            return knownCore(romPrefs().getString("core:" + file.getName(), null), defaultCoreFor("neogeo"));
        }
        if ("arcade".equals(mode)) {
            return knownCore(romPrefs().getString("core:" + file.getName(), null), defaultArcadeCore());
        }
        String[] console = consoleFor(mode);
        if (console != null) {
            return console[1];
        }
        return MODE_GBA;
    }

    private String defaultCoreFor(String mode) {
        return "neogeo".equals(mode) ? knownCore(settingsPrefs().getString(PREF_NEOGEO_CORE, null), "fbneo") : defaultArcadeCore();
    }

    // Settings may name an engine the app does not ship (e.g. one removed in an earlier version).
    private static String knownCore(String core, String fallback) {
        return core != null && Arrays.asList(ARCADE_CORE_IDS).contains(core) ? core : fallback;
    }

    private String defaultArcadeCore() {
        return knownCore(settingsPrefs().getString(PREF_ARCADE_CORE, null), "mame2003plus");
    }

    private String coreDisplayName(String str) {
        if ("mame2003plus".equals(str)) {
            return "MAME 2003-Plus";
        }
        if ("mame2010".equals(str)) {
            return "MAME 2010";
        }
        if ("mame2015".equals(str)) {
            return "MAME 2015";
        }
        if ("mamearcade".equals(str)) {
            return "MAME Arcade";
        }
        if ("mame139".equals(str)) {
            return "MAME4droid 0.139u1";
        }
        if ("fbneo".equals(str)) {
            return "FinalBurn Neo";
        }
        if ("snes9x".equals(str)) {
            return "Snes9x";
        }
        if ("fceumm".equals(str)) {
            return "FCEUmm";
        }
        if ("genesis_plus_gx".equals(str)) {
            return "Genesis Plus GX";
        }
        if ("pce_fast".equals(str)) {
            return "Beetle PCE Fast";
        }
        return "Game Boy Advance";
    }

    private boolean hasDisplayableName(String str) {
        for (int i = 0; i < str.length(); i++) {
            char charAt = str.charAt(i);
            if (charAt != '_' && charAt != '-' && charAt != '.' && !Character.isWhitespace(charAt)) {
                return true;
            }
        }
        return false;
    }

    private String readGbaHeaderTitle(File file) {
        // GBA titles are 12 bytes at 0xA0; Game Boy titles up to 15 bytes at 0x134.
        boolean gameBoy = isGameBoyRomName(file.getName().toLowerCase(Locale.US));
        int offset = gameBoy ? 0x134 : 160;
        int length = gameBoy ? 15 : 12;
        byte[] bArr = new byte[length];
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            if (fileInputStream.skip(offset) != offset || fileInputStream.read(bArr) != length) {
                return null;
            }
        } catch (IOException unused) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int i2 = bArr[i] & 255;
            if (i2 == 0 || i2 == 255) {
                break;
            }
            if (i2 >= 32 && i2 <= 126) {
                sb.append((char) i2);
            }
        }
        String trim = sb.toString().trim();
        return trim.isEmpty() ? null : trim;
    }

    private SharedPreferences romPrefs() {
        return getSharedPreferences("roms", 0);
    }

    private SharedPreferences settingsPrefs() {
        return getSharedPreferences("settings", 0);
    }

    private String baseNameWithoutExtension(String str) {
        int lastIndexOf = str.lastIndexOf(46);
        return lastIndexOf >= 0 ? str.substring(0, lastIndexOf) : str;
    }

    private String extensionOf(String str) {
        int lastIndexOf = str.lastIndexOf(46);
        return lastIndexOf >= 0 ? str.substring(lastIndexOf) : "";
    }

    private String sanitizeFileName(String str) {
        String trim = str.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return trim.isEmpty() ? "selected.gba" : trim;
    }

    private String sanitizeSaveBaseName(String str) {
        String trim = str.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return trim.isEmpty() ? "game" : trim;
    }

    private String safeCrc32Hex(File file) {
        try {
            return crc32Hex(file);
        } catch (IOException unused) {
            return "unknown";
        }
    }

    private String crc32Hex(File file) throws IOException {
        CRC32 crc32 = new CRC32();
        byte[] bArr = new byte[65536];
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            while (true) {
                int read = fileInputStream.read(bArr);
                if (read == -1) {
                    break;
                }
                crc32.update(bArr, 0, read);
            }
        }
        return String.format(Locale.US, "%08X", Long.valueOf(crc32.getValue()));
    }

    private static final class RomCheck {
        final String detail;
        final boolean ok;

        RomCheck(boolean z, String str) {
            this.ok = z;
            this.detail = str;
        }
    }

    private void showMessage(String str) {
        Toast.makeText(this, str, 0).show();
    }

    private void showError(String str) {
        Toast.makeText(this, str, 1).show();
    }

    private int dp(int i) {
        return (int) ((i * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private GradientDrawable rounded(int i, int i2) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(i2);
        return gradientDrawable;
    }
}
