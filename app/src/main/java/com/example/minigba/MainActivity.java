package com.example.minigba;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
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
    private static final String PREF_SCREEN_MODE = "screen_mode";
    private static final String PREF_TITLE_PREFIX = "title:";
    private static final int REQUEST_EXPORT_SAVE = 1002;
    private static final int REQUEST_IMPORT_BIOS = 1004;
    private static final int REQUEST_IMPORT_MAME_BIOS = 1007;
    private static final int REQUEST_IMPORT_NEOGEO_BIOS = 1006;
    private static final int REQUEST_IMPORT_SAVE = 1003;
    private static final int REQUEST_OPEN_ARCADE_ROM = 1005;
    private static final int REQUEST_OPEN_ROM = 1001;
    private TextView biosStatusView;
    private TextView emptyView;
    private TextView mameBiosStatusView;
    private TextView neoGeoBiosStatusView;
    private TextView noteView;
    private File pendingSaveFile;
    private File pendingSaveRom;
    private LinearLayout romList;
    private TextView screenModeStatusView;
    private static final String[] SCREEN_MODE_LABELS = {"전체 화면", "프레임", "컴팩트"};
    private static final byte[] GBA_LOGO = {36, -1, -82, 81, 105, -102, -94, 33, 61, -124, -126, 10, -124, -28, 9, -83, 17, 36, -117, -104, -64, -127, Byte.MAX_VALUE, 33, -93, 82, -66, 25, -109, 9, -50, 32, 16, 70, 74, 74, -8, 39, 49, -20, 88, -57, -24, 51, -126, -29, -50, -65, -123, -12, -33, -108, -50, 75, 9, -63, -108, 86, -118, -64, 19, 114, -89, -4, -97, -124, 77, 115, -93, -54, -102, 97, 88, -105, -93, 39, -4, 3, -104, 118, 35, 29, -57, 97, 3, 4, -82, 86, -65, 56, -124, 0, 64, -89, 14, -3, -1, 82, -2, 3, 111, -107, 48, -15, -105, -5, -64, -123, 96, -42, Byte.MIN_VALUE, 37, -87, 99, -66, 3, 1, 78, 56, -30, -7, -94, 52, -1, -69, 62, 3, 68, 120, 0, -112, -53, -120, 17, 58, -108, 101, -64, 124, 99, -121, -16, 60, -81, -42, 37, -28, -117, 56, 10, -84, 114, 33, -44, -8, 7};

    @Override // android.app.Activity
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setPadding(dp(12), dp(10), dp(12), dp(10));
        linearLayout.setBackgroundColor(Color.rgb(236, 243, 248));
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(1);
        linearLayout2.setPadding(dp(14), dp(10), dp(14), dp(10));
        linearLayout2.setBackground(rounded(Color.rgb(22, 185, 207), dp(8)));
        linearLayout2.setElevation(dp(3));
        TextView textView = new TextView(this);
        textView.setText("Mini GBA");
        textView.setTextColor(-1);
        textView.setTextSize(23.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(16);
        TextView textView2 = new TextView(this);
        textView2.setText("Game Boy Advance");
        textView2.setTextColor(Color.argb(220, 255, 255, 255));
        textView2.setTextSize(12.0f);
        textView2.setPadding(0, dp(1), 0, 0);
        linearLayout2.addView(textView, new LinearLayout.LayoutParams(-1, -2));
        linearLayout2.addView(textView2, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(1);
        linearLayout3.setPadding(0, dp(8), 0, dp(4));
        LinearLayout linearLayout4 = new LinearLayout(this);
        linearLayout4.setOrientation(0);
        LinearLayout linearLayout5 = new LinearLayout(this);
        linearLayout5.setOrientation(0);
        LinearLayout linearLayout6 = new LinearLayout(this);
        linearLayout6.setOrientation(0);
        linearLayout4.addView(createActionCard("GBA ROM", ".gba / .zip", "GBA", Color.rgb(224, 247, 251), Color.rgb(34, 166, 197), new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m30lambda$onCreate$0$comexampleminigbaMainActivity(view);
            }
        }), fullCardParams());
        LinearLayout createActionCard = createActionCard("GBA BIOS", biosFile().exists() ? "등록됨" : "등록 안 됨", "BIOS", Color.rgb(255, 232, 232), Color.rgb(239, 84, 103), new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m31lambda$onCreate$1$comexampleminigbaMainActivity(view);
            }
        });
        this.biosStatusView = (TextView) createActionCard.getTag();
        linearLayout5.addView(createActionCard, fullCardParams());
        linearLayout6.addView(createActionCard("ROM 검사", "파일 기본 검사", "OK", Color.rgb(229, 234, 255), Color.rgb(98, 111, 204), new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m32lambda$onCreate$2$comexampleminigbaMainActivity(view);
            }
        }), cardGridParams(true));
        LinearLayout createActionCard2 = createActionCard("화면 모드", SCREEN_MODE_LABELS[screenMode()], "VIEW", Color.rgb(232, 247, 235), Color.rgb(55, 188, 116), new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda8
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m33lambda$onCreate$3$comexampleminigbaMainActivity(view);
            }
        });
        this.screenModeStatusView = (TextView) createActionCard2.getTag();
        linearLayout6.addView(createActionCard2, cardGridParams(false));
        linearLayout3.addView(linearLayout4, new LinearLayout.LayoutParams(-1, -2));
        linearLayout3.addView(linearLayout5, new LinearLayout.LayoutParams(-1, -2));
        linearLayout3.addView(linearLayout6, new LinearLayout.LayoutParams(-1, -2));
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
        updateNote();
        linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(-1, -2));
        linearLayout.addView(linearLayout3, new LinearLayout.LayoutParams(-1, -2));
        linearLayout.addView(this.emptyView, new LinearLayout.LayoutParams(-1, dp(58)));
        linearLayout.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        linearLayout.addView(this.noteView, new LinearLayout.LayoutParams(-1, -2));
        setContentView(linearLayout);
    }

    /* renamed from: lambda$onCreate$0$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m30lambda$onCreate$0$comexampleminigbaMainActivity(View view) {
        openRomPicker();
    }

    /* renamed from: lambda$onCreate$1$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m31lambda$onCreate$1$comexampleminigbaMainActivity(View view) {
        openBiosPicker();
    }

    /* renamed from: lambda$onCreate$2$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m32lambda$onCreate$2$comexampleminigbaMainActivity(View view) {
        checkImportedRoms();
    }

    /* renamed from: lambda$onCreate$3$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m33lambda$onCreate$3$comexampleminigbaMainActivity(View view) {
        int screenMode = screenMode() + 1;
        String[] strArr = SCREEN_MODE_LABELS;
        int length = screenMode % strArr.length;
        settingsPrefs().edit().putInt("screen_mode", length).apply();
        Object tag = view.getTag();
        if (tag instanceof TextView) {
            ((TextView) tag).setText(strArr[length]);
        }
        showMessage("화면 모드: " + strArr[length]);
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        updateHomeStatusCards();
        updateNote();
        refreshLibrary();
    }

    private void updateHomeStatusCards() {
        TextView textView = this.biosStatusView;
        if (textView != null) {
            textView.setText(biosFile().exists() ? "등록됨" : "등록 안 됨");
        }
        TextView textView2 = this.neoGeoBiosStatusView;
        if (textView2 != null) {
            textView2.setText(neoGeoBiosFile().exists() ? "등록됨" : "등록 안 됨");
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

    private LinearLayout createActionCard(String str, String str2, String str3, int i, int i2, View.OnClickListener onClickListener) {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setGravity(17);
        linearLayout.setPadding(dp(7), dp(7), dp(7), dp(7));
        linearLayout.setBackground(rounded(i, dp(8)));
        linearLayout.setClickable(true);
        linearLayout.setOnClickListener(onClickListener);
        linearLayout.setElevation(dp(2));
        TextView textView = new TextView(this);
        textView.setText(str3);
        textView.setGravity(17);
        textView.setTextColor(-1);
        textView.setTextSize(str3.length() > 3 ? 8.0f : 11.0f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setBackground(rounded(i2, dp(18)));
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        layoutParams.setMargins(0, 0, 0, dp(4));
        linearLayout.addView(textView, layoutParams);
        TextView textView2 = new TextView(this);
        textView2.setText(str);
        textView2.setGravity(17);
        textView2.setTextColor(Color.rgb(55, 65, 76));
        textView2.setTextSize(13.0f);
        textView2.setTypeface(Typeface.DEFAULT_BOLD);
        textView2.setSingleLine(false);
        linearLayout.addView(textView2, new LinearLayout.LayoutParams(-1, -2));
        TextView textView3 = new TextView(this);
        textView3.setText(str2);
        textView3.setGravity(17);
        textView3.setTextColor(Color.rgb(101, 112, 124));
        textView3.setTextSize(11.0f);
        textView3.setSingleLine(false);
        LinearLayout.LayoutParams layoutParams2 = new LinearLayout.LayoutParams(-1, -2);
        layoutParams2.setMargins(0, dp(1), 0, 0);
        linearLayout.addView(textView3, layoutParams2);
        linearLayout.setTag(textView3);
        return linearLayout;
    }

    private LinearLayout.LayoutParams cardGridParams(boolean z) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, dp(94), 1.0f);
        layoutParams.setMargins(z ? 0 : dp(4), 0, z ? dp(4) : 0, dp(8));
        return layoutParams;
    }

    private LinearLayout.LayoutParams fullCardParams() {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, dp(94));
        layoutParams.setMargins(0, 0, 0, dp(8));
        return layoutParams;
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
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/octet-stream", "application/x-gba-rom", "application/x-gameboy-advance-rom", "application/zip"});
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        startActivityForResult(intent, 1001);
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
        File[] listFiles = mameBiosDir().listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda22
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                return MainActivity.lambda$showMameBiosManager$4(file);
            }
        });
        if (listFiles == null || listFiles.length == 0) {
            TextView textView2 = new TextView(this);
            textView2.setText("아직 등록된 MAME BIOS가 없습니다.");
            textView2.setTextColor(Color.rgb(190, 197, 204));
            textView2.setTextSize(14.0f);
            textView2.setGravity(17);
            textView2.setPadding(0, dp(24), 0, dp(24));
            linearLayout.addView(textView2);
        } else {
            Arrays.sort(listFiles, Comparator.comparing(new Function() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda23
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    String lowerCase;
                    lowerCase = ((File) obj).getName().toLowerCase(Locale.US);
                    return lowerCase;
                }
            }));
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
                linearLayout2.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda24
                    @Override // android.view.View.OnLongClickListener
                    public final boolean onLongClick(View view) {
                        return MainActivity.this.m37lambda$showMameBiosManager$6$comexampleminigbaMainActivity(file, alertDialogArr, view);
                    }
                });
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
        AlertDialog create = new AlertDialog.Builder(this).setTitle("MAME BIOS 관리").setView(scrollView).setNegativeButton("닫기", (DialogInterface.OnClickListener) null).setPositiveButton("BIOS 가져오기", new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda1
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i3) {
                MainActivity.this.m38lambda$showMameBiosManager$7$comexampleminigbaMainActivity(dialogInterface, i3);
            }
        }).create();
        alertDialogArr[0] = create;
        create.show();
    }

    static /* synthetic */ boolean lambda$showMameBiosManager$4(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".zip");
    }

    /* renamed from: lambda$showMameBiosManager$6$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m37lambda$showMameBiosManager$6$comexampleminigbaMainActivity(File file, AlertDialog[] alertDialogArr, View view) {
        confirmDeleteMameBios(file, alertDialogArr[0]);
        return true;
    }

    /* renamed from: lambda$showMameBiosManager$7$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m38lambda$showMameBiosManager$7$comexampleminigbaMainActivity(DialogInterface dialogInterface, int i) {
        openMameBiosPicker();
    }

    private void confirmDeleteMameBios(final File file, final AlertDialog alertDialog) {
        new AlertDialog.Builder(this).setTitle("MAME BIOS 삭제").setMessage(file.getName() + "을(를) 삭제할까요?").setNegativeButton("취소", (DialogInterface.OnClickListener) null).setPositiveButton("삭제", new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda9
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m22lambda$confirmDeleteMameBios$8$comexampleminigbaMainActivity(file, alertDialog, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$confirmDeleteMameBios$8$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m22lambda$confirmDeleteMameBios$8$comexampleminigbaMainActivity(File file, AlertDialog alertDialog, DialogInterface dialogInterface, int i) {
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
            removeMergedBiosEntries(new File(romDir(), "twcup98.zip"), file);
            File file2 = new File(romDir(), "stvbios.zip");
            if (file2.exists() && !file2.delete()) {
                return false;
            }
        }
        return !file.exists() || file.delete();
    }

    @Override // android.app.Activity
    protected void onActivityResult(int i, int i2, Intent intent) {
        super.onActivityResult(i, i2, intent);
        if (i2 != -1 || intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (i == 1001) {
            importRoms(intent);
            return;
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

    private void importRoms(Intent intent) {
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
            if (!lowerCase.endsWith(".gba") && !lowerCase.endsWith(".zip")) {
                sb.append(displayNameFor).append(": 지원하지 않는 파일\n");
            } else {
                try {
                    if (lowerCase.endsWith(".zip")) {
                        singletonOrEmpty = copyZippedRomsToAppStorage(uri2, displayNameFor);
                    } else {
                        singletonOrEmpty = singletonOrEmpty(copyRomToAppStorage(uri2, displayNameFor));
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
            if (!displayNameFor.toLowerCase(Locale.US).endsWith(".zip")) {
                sb.append(displayNameFor).append(": 아케이드 ROM은 ZIP 파일이어야 합니다.\n");
            } else {
                try {
                    if (copyArcadeRomToAppStorage(uri2, displayNameFor) == null) {
                        i3++;
                    } else {
                        i++;
                    }
                } catch (IOException e) {
                    sb.append(displayNameFor).append(": ").append(e.getMessage()).append('\n');
                }
            }
        }
        migrateKnownArcadeRoms();
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
        showMameBiosManager();
    }

    private void importBios(Uri uri) {
        try {
            File systemDir = systemDir();
            if (!systemDir.exists() && !systemDir.mkdirs()) {
                throw new IOException("BIOS 폴더를 만들 수 없습니다");
            }
            File file = new File(systemDir, "gba_bios.bin");
            InputStream openInputStream = getContentResolver().openInputStream(uri);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                try {
                    if (openInputStream == null) {
                        throw new IOException("선택한 BIOS 파일을 열 수 없습니다");
                    }
                    copy(openInputStream, fileOutputStream);
                    fileOutputStream.close();
                    if (openInputStream != null) {
                        openInputStream.close();
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
                } finally {
                }
            } finally {
            }
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
            InputStream openInputStream = getContentResolver().openInputStream(uri);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(neoGeoBiosFile);
                try {
                    if (openInputStream == null) {
                        throw new IOException("선택한 BIOS 파일을 열 수 없습니다");
                    }
                    copy(openInputStream, fileOutputStream);
                    fileOutputStream.close();
                    if (openInputStream != null) {
                        openInputStream.close();
                    }
                    if (neoGeoBiosFile.length() == 0) {
                        if (!neoGeoBiosFile.delete()) {
                            neoGeoBiosFile.deleteOnExit();
                        }
                        throw new IOException("비어 있는 BIOS 파일입니다");
                    }
                    File romDir = romDir();
                    if (!romDir.exists() && !romDir.mkdirs()) {
                        throw new IOException("ROM 폴더를 만들 수 없습니다");
                    }
                    prepareNeoGeoBiosForArcade(neoGeoBiosFile, new File(romDir, "neogeo.zip"));
                    showMessage("Neo Geo BIOS를 가져왔습니다.");
                    updateHomeStatusCards();
                    updateNote();
                } finally {
                }
            } finally {
            }
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
                        removeMergedBiosEntries(new File(romDir(), "twcup98.zip"), file2);
                    }
                    if (!copyFile(file, file2)) {
                        throw new IOException("BIOS를 저장할 수 없습니다");
                    }
                    if (sanitizeFileName.equalsIgnoreCase("stvbios.zip")) {
                        File romDir = romDir();
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

    private void refreshLibrary() {
        LinearLayout linearLayout = this.romList;
        if (linearLayout == null) {
            return;
        }
        linearLayout.removeAllViews();
        File[] listFiles = romDir().listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda14
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                return MainActivity.this.m34lambda$refreshLibrary$9$comexampleminigbaMainActivity(file);
            }
        });
        if (listFiles == null || listFiles.length == 0) {
            this.emptyView.setVisibility(0);
            return;
        }
        this.emptyView.setVisibility(8);
        Arrays.sort(listFiles, new Comparator() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda15
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                int compareRomsForLibrary;
                compareRomsForLibrary = MainActivity.this.compareRomsForLibrary((File) obj, (File) obj2);
                return compareRomsForLibrary;
            }
        });
        for (File file : listFiles) {
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.setMargins(0, 0, 0, dp(10));
            this.romList.addView(createRomRow(file), layoutParams);
        }
    }

    /* renamed from: lambda$refreshLibrary$9$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m34lambda$refreshLibrary$9$comexampleminigbaMainActivity(File file) {
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
        linearLayout.setOnClickListener(new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda18
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m25lambda$createRomRow$10$comexampleminigbaMainActivity(file, view);
            }
        });
        linearLayout.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda19
            @Override // android.view.View.OnLongClickListener
            public final boolean onLongClick(View view) {
                return MainActivity.this.m26lambda$createRomRow$11$comexampleminigbaMainActivity(file, view);
            }
        });
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
        textView2.setOnClickListener(new View.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda20
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                MainActivity.this.m27lambda$createRomRow$12$comexampleminigbaMainActivity(file, view);
            }
        });
        textView2.setOnLongClickListener(new View.OnLongClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda21
            @Override // android.view.View.OnLongClickListener
            public final boolean onLongClick(View view) {
                return MainActivity.this.m28lambda$createRomRow$13$comexampleminigbaMainActivity(file, view);
            }
        });
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

    /* renamed from: lambda$createRomRow$10$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m25lambda$createRomRow$10$comexampleminigbaMainActivity(File file, View view) {
        launchGame(file);
    }

    /* renamed from: lambda$createRomRow$11$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m26lambda$createRomRow$11$comexampleminigbaMainActivity(File file, View view) {
        showRomActions(file);
        return true;
    }

    /* renamed from: lambda$createRomRow$12$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m27lambda$createRomRow$12$comexampleminigbaMainActivity(File file, View view) {
        launchGame(file);
    }

    /* renamed from: lambda$createRomRow$13$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m28lambda$createRomRow$13$comexampleminigbaMainActivity(File file, View view) {
        showRomActions(file);
        return true;
    }

    private void showRomActions(final File file) {
        ArrayList arrayList = new ArrayList();
        arrayList.add("실행");
        arrayList.add(isFavorite(file) ? "즐겨찾기 해제" : "즐겨찾기 추가");
        arrayList.add("게임/세이브 이름 변경");
        if (!coreFor(file).equals("gba")) {
            arrayList.add("아케이드 코어 변경");
        }
        arrayList.add("세이브 백업 내보내기");
        arrayList.add("세이브 백업 가져오기");
        arrayList.add("세이브 삭제");
        arrayList.add("ROM 삭제");
        final String[] strArr = (String[]) arrayList.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(displayGameNameFor(file)).setItems(strArr, new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda10
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m39lambda$showRomActions$14$comexampleminigbaMainActivity(strArr, file, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$showRomActions$14$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m39lambda$showRomActions$14$comexampleminigbaMainActivity(String[] strArr, File file, DialogInterface dialogInterface, int i) {
        String str = strArr[i];
        if (str.equals("실행")) {
            launchGame(file);
            return;
        }
        if (str.startsWith("즐겨찾기")) {
            toggleFavorite(file);
            return;
        }
        if (str.equals("게임/세이브 이름 변경")) {
            renameGameAndSave(file);
            return;
        }
        if (str.equals("아케이드 코어 변경")) {
            showCorePicker(file);
            return;
        }
        if (str.equals("세이브 백업 내보내기")) {
            startSaveExport(file);
            return;
        }
        if (str.equals("세이브 백업 가져오기")) {
            startSaveImport(file);
        } else if (str.equals("세이브 삭제")) {
            confirmDeleteSave(file);
        } else if (str.equals("ROM 삭제")) {
            confirmDeleteRom(file);
        }
    }

    private void showCorePicker(final File file) {
        int i = 0;
        String[] strArr = {"FinalBurn Neo (Neo Geo 우선)", "MAME 2003-Plus (구형 아케이드)", "MAME4droid 0.139u1 (0.139 ROM 세트)", "MAME Arcade (현행·후기 아케이드)"};
        final String[] strArr2 = {"fbneo", "mame2003plus", "mame139", "mamearcade"};
        String coreFor = coreFor(file);
        if (coreFor.equals("mamearcade")) {
            i = 3;
        } else if (coreFor.equals("mame139")) {
            i = 2;
        } else if (coreFor.equals("mame2003plus")) {
            i = 1;
        }
        new AlertDialog.Builder(this).setTitle("실행 코어 선택").setSingleChoiceItems(strArr, i, new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda11
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i2) {
                MainActivity.this.m36lambda$showCorePicker$15$comexampleminigbaMainActivity(file, strArr2, dialogInterface, i2);
            }
        }).show();
    }

    /* renamed from: lambda$showCorePicker$15$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m36lambda$showCorePicker$15$comexampleminigbaMainActivity(File file, String[] strArr, DialogInterface dialogInterface, int i) {
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
        migrateSaveFileIfNeeded(file);
        markRecentlyPlayed(file);
        Intent intent = new Intent(this, (Class<?>) GameActivity.class);
        intent.putExtra("com.example.minigba.ROM_PATH", file.getAbsolutePath());
        intent.putExtra("com.example.minigba.SAVE_BASE_NAME", saveBaseNameFor(file));
        intent.putExtra("com.example.minigba.CORE_ID", coreFor(file));
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
        ZipOutputStream zipOutputStream;
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
        try {
            OutputStream openOutputStream = getContentResolver().openOutputStream(uri);
            if (openOutputStream == null) {
                zipOutputStream = null;
            } else {
                try {
                    zipOutputStream = new ZipOutputStream(openOutputStream);
                } finally {
                }
            }
            try {
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
                if (zipOutputStream != null) {
                    zipOutputStream.close();
                }
                if (openOutputStream != null) {
                    openOutputStream.close();
                }
            } finally {
            }
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
        try {
            InputStream openInputStream = getContentResolver().openInputStream(uri);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(this.pendingSaveFile);
                try {
                    if (openInputStream == null) {
                        throw new IOException("선택한 세이브 파일을 열 수 없습니다");
                    }
                    copy(openInputStream, fileOutputStream);
                    showMessage("게임 내 세이브를 가져왔습니다.");
                    refreshLibrary();
                    fileOutputStream.close();
                    if (openInputStream != null) {
                        openInputStream.close();
                    }
                } finally {
                }
            } finally {
            }
        } catch (IOException e) {
            showError("세이브 가져오기 실패: " + e.getMessage());
        }
    }

    private void importSaveBackup(Uri uri) {
        ZipInputStream zipInputStream;
        File file;
        File file2 = this.pendingSaveRom;
        if (file2 == null) {
            showError("세이브 가져오기 실패: 선택된 ROM이 없습니다");
            return;
        }
        File saveFileFor = saveFileFor(file2);
        File stateFileFor = stateFileFor(this.pendingSaveRom);
        try {
            InputStream openInputStream = getContentResolver().openInputStream(uri);
            if (openInputStream == null) {
                zipInputStream = null;
            } else {
                try {
                    zipInputStream = new ZipInputStream(openInputStream);
                } finally {
                }
            }
            try {
                if (zipInputStream == null) {
                    throw new IOException("선택한 백업 파일을 열 수 없습니다");
                }
                int i = 0;
                while (true) {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        if (i == 0) {
                            throw new IOException("백업 안에 .sav 또는 .state 파일이 없습니다");
                        }
                        showMessage("세이브 백업을 가져왔습니다.");
                        refreshLibrary();
                        if (zipInputStream != null) {
                            zipInputStream.close();
                        }
                        if (openInputStream != null) {
                            openInputStream.close();
                            return;
                        }
                        return;
                    }
                    if (!nextEntry.isDirectory()) {
                        String lowerCase = new File(nextEntry.getName()).getName().toLowerCase(Locale.US);
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
                            FileOutputStream fileOutputStream = new FileOutputStream(file);
                            try {
                                copy(zipInputStream, fileOutputStream);
                                fileOutputStream.close();
                                i++;
                            } finally {
                            }
                        }
                    }
                }
            } finally {
            }
        } catch (IOException e) {
            showError("세이브 가져오기 실패: " + e.getMessage());
        }
    }

    private void confirmDeleteSave(final File file) {
        new AlertDialog.Builder(this).setTitle("세이브를 삭제할까요?").setMessage("이 ROM의 .sav 파일과 빠른 저장 파일을 삭제합니다.").setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda2
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m24lambda$confirmDeleteSave$16$comexampleminigbaMainActivity(file, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$confirmDeleteSave$16$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m24lambda$confirmDeleteSave$16$comexampleminigbaMainActivity(File file, DialogInterface dialogInterface, int i) {
        deleteSave(file);
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
        new AlertDialog.Builder(this).setTitle("게임/세이브 이름").setView(editText).setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda4
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m35lambda$renameGameAndSave$17$comexampleminigbaMainActivity(editText, file, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$renameGameAndSave$17$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m35lambda$renameGameAndSave$17$comexampleminigbaMainActivity(EditText editText, File file, DialogInterface dialogInterface, int i) {
        String trim = editText.getText().toString().trim();
        String sanitizeSaveBaseName = sanitizeSaveBaseName(trim);
        if (trim.isEmpty()) {
            showError("이름을 비워둘 수 없습니다.");
            return;
        }
        File saveFileFor = saveFileFor(file);
        File stateFileFor = stateFileFor(file);
        File file2 = new File(getFilesDir(), "saves");
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

    /* JADX INFO: Access modifiers changed from: private */
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

    private void confirmDeleteRom(final File file) {
        new AlertDialog.Builder(this).setTitle("ROM을 삭제할까요?").setMessage("Mini Emulator 안에 가져온 복사본만 삭제합니다. 원본 파일은 그대로 둡니다.").setNegativeButton(17039360, (DialogInterface.OnClickListener) null).setPositiveButton(17039370, new DialogInterface.OnClickListener() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda16
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                MainActivity.this.m23lambda$confirmDeleteRom$18$comexampleminigbaMainActivity(file, dialogInterface, i);
            }
        }).show();
    }

    /* renamed from: lambda$confirmDeleteRom$18$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ void m23lambda$confirmDeleteRom$18$comexampleminigbaMainActivity(File file, DialogInterface dialogInterface, int i) {
        if (file.delete()) {
            romPrefs().edit().remove("title:" + file.getName()).remove("save:" + file.getName()).remove("favorite:" + file.getName()).remove("recent:" + file.getName()).remove("core:" + file.getName()).apply();
            showMessage("ROM을 삭제했습니다.");
            refreshLibrary();
            return;
        }
        showError("ROM을 삭제할 수 없습니다.");
    }

    private File copyRomToAppStorage(Uri uri, String str) throws IOException {
        File romDir = romDir();
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        File uniqueFile = uniqueFile(romDir, sanitizeFileName(str));
        InputStream openInputStream = getContentResolver().openInputStream(uri);
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(uniqueFile);
            try {
                if (openInputStream == null) {
                    throw new IOException("선택한 파일을 열 수 없습니다");
                }
                copy(openInputStream, fileOutputStream);
                fileOutputStream.close();
                if (openInputStream != null) {
                    openInputStream.close();
                }
                return finalizeImportedRom(uniqueFile, baseNameWithoutExtension(str));
            } finally {
            }
        } catch (Throwable th) {
            if (openInputStream != null) {
                try {
                    openInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private File copyArcadeRomToAppStorage(Uri uri, String str) throws IOException {
        File romDir = romDir();
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        File uniqueFile = uniqueFile(romDir, sanitizeFileName(str));
        InputStream openInputStream = getContentResolver().openInputStream(uri);
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(uniqueFile);
            try {
                if (openInputStream == null) {
                    throw new IOException("선택한 ZIP 파일을 열 수 없습니다");
                }
                copy(openInputStream, fileOutputStream);
                fileOutputStream.close();
                if (openInputStream != null) {
                    openInputStream.close();
                }
                if (uniqueFile.length() == 0) {
                    if (!uniqueFile.delete()) {
                        uniqueFile.deleteOnExit();
                    }
                    throw new IOException("비어 있는 ZIP 파일입니다");
                }
                File finalizeImportedRom = finalizeImportedRom(uniqueFile, baseNameWithoutExtension(str));
                if (finalizeImportedRom != null) {
                    romPrefs().edit().putString("core:" + finalizeImportedRom.getName(), "fbneo").apply();
                }
                return finalizeImportedRom;
            } finally {
            }
        } catch (Throwable th) {
            if (openInputStream != null) {
                try {
                    openInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private List<File> copyZippedRomsToAppStorage(Uri uri, String str) throws IOException {
        File romDir = romDir();
        if (!romDir.exists() && !romDir.mkdirs()) {
            throw new IOException("ROM 폴더를 만들 수 없습니다");
        }
        InputStream openInputStream = getContentResolver().openInputStream(uri);
        if (openInputStream == null) {
            throw new IOException("선택한 ZIP 파일을 열 수 없습니다");
        }
        ArrayList arrayList = new ArrayList();
        ZipInputStream zipInputStream = new ZipInputStream(openInputStream);
        while (true) {
            try {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (!nextEntry.isDirectory()) {
                    String name = new File(nextEntry.getName()).getName();
                    if (name.toLowerCase(Locale.US).endsWith(".gba")) {
                        File uniqueFile = uniqueFile(romDir, sanitizeFileName(name));
                        FileOutputStream fileOutputStream = new FileOutputStream(uniqueFile);
                        try {
                            copy(zipInputStream, fileOutputStream);
                            fileOutputStream.close();
                            File finalizeImportedRom = finalizeImportedRom(uniqueFile, baseNameWithoutExtension(name));
                            if (finalizeImportedRom != null) {
                                arrayList.add(finalizeImportedRom);
                            }
                        } finally {
                        }
                    }
                }
            } catch (Throwable th) {
                try {
                    zipInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
        zipInputStream.close();
        if (arrayList.isEmpty()) {
            throw new IOException(str + " 안에서 새 .gba 파일을 찾지 못했습니다");
        }
        return arrayList;
    }

    private File finalizeImportedRom(File file, String str) throws IOException {
        String crc32Hex = crc32Hex(file);
        if (findRomByCrc(crc32Hex, file) != null) {
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
        File[] listFiles = romDir().listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda0
            @Override // java.io.FileFilter
            public final boolean accept(File file2) {
                return MainActivity.this.m29lambda$findRomByCrc$19$comexampleminigbaMainActivity(file2);
            }
        });
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

    /* renamed from: lambda$findRomByCrc$19$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m29lambda$findRomByCrc$19$comexampleminigbaMainActivity(File file) {
        return file.isFile() && isSupportedRom(file);
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
        FileInputStream fileInputStream = new FileInputStream(file);
        try {
            copy(fileInputStream, zipOutputStream);
            fileInputStream.close();
            zipOutputStream.closeEntry();
        } catch (Throwable th) {
            try {
                fileInputStream.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
            throw th;
        }
    }

    private List<File> singletonOrEmpty(File file) {
        ArrayList arrayList = new ArrayList();
        if (file != null) {
            arrayList.add(file);
        }
        return arrayList;
    }

    private String displayNameFor(Uri uri) {
        int columnIndex;
        String string;
        Cursor query = getContentResolver().query(uri, null, null, null, null);
        if (query != null) {
            try {
                if (query.moveToFirst() && (columnIndex = query.getColumnIndex("_display_name")) >= 0 && (string = query.getString(columnIndex)) != null) {
                    if (!string.isEmpty()) {
                        if (query != null) {
                            query.close();
                        }
                        return string;
                    }
                }
            } catch (Throwable th) {
                if (query != null) {
                    try {
                        query.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
        if (query != null) {
            query.close();
        }
        String lastPathSegment = uri.getLastPathSegment();
        return lastPathSegment == null ? "선택한파일.gba" : lastPathSegment;
    }

    private void checkImportedRoms() {
        File[] listFiles = romDir().listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda12
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                return MainActivity.this.m21lambda$checkImportedRoms$20$comexampleminigbaMainActivity(file);
            }
        });
        if (listFiles == null || listFiles.length == 0) {
            showMessage("검사할 ROM이 없습니다.");
            return;
        }
        Arrays.sort(listFiles, Comparator.comparing(new Function() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda13
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                String lowerCase;
                lowerCase = ((File) obj).getName().toLowerCase(Locale.US);
                return lowerCase;
            }
        }));
        StringBuilder sb = new StringBuilder();
        HashMap hashMap = new HashMap();
        int i = 0;
        for (File file : listFiles) {
            RomCheck checkRomFile = checkRomFile(file);
            String safeCrc32Hex = safeCrc32Hex(file);
            File file2 = (File) hashMap.get(safeCrc32Hex);
            if (file2 != null) {
                sb.append(displayGameNameFor(file)).append(": 중복 ROM - ").append(displayGameNameFor(file2)).append('\n');
            } else {
                hashMap.put(safeCrc32Hex, file);
                if (checkRomFile.ok) {
                    i++;
                } else {
                    sb.append(displayGameNameFor(file)).append(": ").append(checkRomFile.detail).append('\n');
                }
            }
        }
        String str = "검사한 ROM: " + listFiles.length + "개\n파일 기본 검사 통과: " + i + "개\n직접 실행 확인 필요: " + (listFiles.length - i) + "개";
        if (sb.length() > 0) {
            str = str + "\n\n" + ((Object) sb);
        }
        new AlertDialog.Builder(this).setTitle("ROM 호환성 검사").setMessage(str).setPositiveButton(17039370, (DialogInterface.OnClickListener) null).show();
    }

    /* renamed from: lambda$checkImportedRoms$20$com-example-minigba-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m21lambda$checkImportedRoms$20$comexampleminigbaMainActivity(File file) {
        return file.isFile() && isSupportedRom(file);
    }

    private RomCheck checkRomFile(File file) {
        ZipEntry nextEntry;
        if (file.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            try {
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
                do {
                    try {
                        nextEntry = zipInputStream.getNextEntry();
                        if (nextEntry == null) {
                            RomCheck romCheck = new RomCheck(false, "비어 있는 ZIP 파일");
                            zipInputStream.close();
                            return romCheck;
                        }
                    } finally {
                    }
                } while (nextEntry.isDirectory());
                RomCheck romCheck2 = new RomCheck(true, coreDisplayName(coreFor(file)) + " ZIP 읽기 가능");
                zipInputStream.close();
                return romCheck2;
            } catch (IOException unused) {
                return new RomCheck(false, "ZIP 읽기 실패");
            }
        }
        return checkRomHeader(file);
    }

    private RomCheck checkRomHeader(File file) {
        boolean z;
        if (file.length() < 192) {
            return new RomCheck(false, "파일이 너무 작음");
        }
        if (file.length() > 33554432) {
            return new RomCheck(false, "표준 32MB GBA ROM보다 큼");
        }
        byte[] bArr = new byte[192];
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            try {
                if (fileInputStream.read(bArr) != 192) {
                    RomCheck romCheck = new RomCheck(false, "GBA 헤더를 끝까지 읽을 수 없음");
                    fileInputStream.close();
                    return romCheck;
                }
                fileInputStream.close();
                int i = 0;
                while (true) {
                    byte[] bArr2 = GBA_LOGO;
                    if (i >= bArr2.length) {
                        z = true;
                        break;
                    }
                    if (bArr[i + 4] != bArr2[i]) {
                        z = false;
                        break;
                    }
                    i++;
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
            } finally {
            }
        } catch (IOException unused) {
            return new RomCheck(false, "읽기 실패");
        }
    }

    private void updateNote() {
        if (this.noteView == null) {
            return;
        }
        this.noteView.setText("상업용 ROM/BIOS는 포함되어 있지 않습니다. ROM을 길게 누르면 세이브를 관리할 수 있습니다.\n세이브 파일 경로: " + saveDir().getAbsolutePath() + "\nGBA BIOS: " + (biosFile().exists() ? "등록됨" : "등록 안 됨"));
    }

    private void updateScreenModeButton(Button button) {
        button.setText("화면 모드: " + SCREEN_MODE_LABELS[screenMode()]);
    }

    private int screenMode() {
        int i = settingsPrefs().getInt("screen_mode", 0);
        if (i < 0 || i >= SCREEN_MODE_LABELS.length) {
            return 0;
        }
        return i;
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

    static /* synthetic */ boolean lambda$mameBiosStatus$22(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".zip");
    }

    private String mameBiosStatus() {
        File[] listFiles = mameBiosDir().listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda3
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                return MainActivity.lambda$mameBiosStatus$22(file);
            }
        });
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
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
            try {
                boolean z = zipInputStream.getNextEntry() != null;
                zipInputStream.close();
                return z;
            } finally {
            }
        } catch (IOException unused) {
            return false;
        }
    }

    private boolean zipContainsEntry(File file, String str) {
        ZipEntry nextEntry;
        if (!file.isFile()) {
            return false;
        }
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
            do {
                try {
                    nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        zipInputStream.close();
                        return false;
                    }
                } finally {
                }
            } while (!new File(nextEntry.getName().replace('\\', '/')).getName().equalsIgnoreCase(str));
            zipInputStream.close();
            return true;
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
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(file2);
                try {
                    copy(fileInputStream, fileOutputStream);
                    fileOutputStream.close();
                    fileInputStream.close();
                    return true;
                } finally {
                }
            } finally {
            }
        } catch (IOException unused) {
            return false;
        }
    }

    private void syncNeoGeoBiosForArcade() {
        File neoGeoBiosFile = neoGeoBiosFile();
        if (neoGeoBiosFile.exists()) {
            File romDir = romDir();
            if (romDir.exists() || romDir.mkdirs()) {
                try {
                    prepareNeoGeoBiosForArcade(neoGeoBiosFile, new File(romDir, "neogeo.zip"));
                } catch (IOException unused) {
                }
            }
        }
    }

    private void syncMameBiosStorage() {
        File file = new File(romDir(), "stvbios.zip");
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
        File romDir = romDir();
        File[] listFiles = romDir.listFiles(new FileFilter() { // from class: com.example.minigba.MainActivity$$ExternalSyntheticLambda17
            @Override // java.io.FileFilter
            public final boolean accept(File file) {
                return MainActivity.lambda$migrateKnownArcadeRoms$23(file);
            }
        });
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
                    romPrefs().edit().putString("title:" + file2.getName(), "Tecmo World Cup 98").putString("core:" + file2.getName(), "mamearcade").apply();
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
                    romPrefs().edit().putString("title:" + file3.getName(), "동물철권2 (Blood Roar 2)").putString("core:" + file3.getName(), "mamearcade").apply();
                }
            }
        }
    }

    static /* synthetic */ boolean lambda$migrateKnownArcadeRoms$23(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".zip");
    }

    private void normalizeTecmoWorldCup98Package(File file) {
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
            byte[] bArr = null;
            byte[] bArr2 = null;
            while (true) {
                try {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry == null) {
                        break;
                    }
                    if (!nextEntry.isDirectory()) {
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
                } finally {
                }
            }
            zipInputStream.close();
            if (bArr != null) {
                writeBytesAtomically(file, bArr);
            }
            if (bArr2 != null) {
                writeBytesAtomically(new File(file.getParentFile(), "stvbios.zip"), bArr2);
            }
        } catch (IOException unused) {
        }
    }

    private void removeMergedBiosEntries(File file, File file2) {
        if (file.isFile() && file2.isFile()) {
            ArrayList arrayList = new ArrayList();
            try {
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file2));
                while (true) {
                    try {
                        ZipEntry nextEntry = zipInputStream.getNextEntry();
                        if (nextEntry == null) {
                            break;
                        } else if (!nextEntry.isDirectory()) {
                            arrayList.add(new File(nextEntry.getName().replace('\\', '/')).getName().toLowerCase(Locale.US));
                        }
                    } finally {
                        try {
                            zipInputStream.close();
                        } catch (Throwable th) {
                            th.addSuppressed(th);
                        }
                    }
                }
                zipInputStream.close();
                if (arrayList.isEmpty()) {
                    return;
                }
                File file3 = new File(file.getParentFile(), file.getName() + ".clean");
                try {
                    zipInputStream = new ZipInputStream(new FileInputStream(file));
                    try {
                        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3));
                        while (true) {
                            try {
                                ZipEntry nextEntry2 = zipInputStream.getNextEntry();
                                if (nextEntry2 == null) {
                                    break;
                                }
                                if (!arrayList.contains(new File(nextEntry2.getName().replace('\\', '/')).getName().toLowerCase(Locale.US))) {
                                    zipOutputStream.putNextEntry(new ZipEntry(nextEntry2.getName()));
                                    if (!nextEntry2.isDirectory()) {
                                        copy(zipInputStream, zipOutputStream);
                                    }
                                    zipOutputStream.closeEntry();
                                }
                            } finally {
                            }
                        }
                        zipOutputStream.close();
                        zipInputStream.close();
                        if (!copyFile(file3, file) || file3.delete()) {
                            return;
                        }
                        file3.deleteOnExit();
                    } finally {
                    }
                } catch (IOException unused) {
                    if (!file3.exists() || file3.delete()) {
                        return;
                    }
                    file3.deleteOnExit();
                }
            } catch (IOException unused2) {
            }
        }
    }

    private void mergeArcadeBiosIntoRom(File file, File file2) {
        if (file.exists() && file2.exists()) {
            File file3 = new File(file.getParentFile(), file.getName() + ".merged");
            ArrayList arrayList = new ArrayList();
            try {
                ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3));
                try {
                    copyZipEntries(file, zipOutputStream, arrayList);
                    copyZipEntries(file2, zipOutputStream, arrayList);
                    zipOutputStream.close();
                    if (file3.length() == 0 || (file.exists() && !file.delete())) {
                        if (file3.delete()) {
                            return;
                        }
                        file3.deleteOnExit();
                    } else {
                        if (file3.renameTo(file)) {
                            return;
                        }
                        copyFile(file3, file);
                        if (file3.delete()) {
                            return;
                        }
                        file3.deleteOnExit();
                    }
                } finally {
                }
            } catch (IOException unused) {
                if (file3.delete()) {
                    return;
                }
                file3.deleteOnExit();
            }
        }
    }

    private void copyZipEntries(File file, ZipOutputStream zipOutputStream, List<String> list) throws IOException {
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
        while (true) {
            try {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry != null) {
                    if (!nextEntry.isDirectory()) {
                        String name = new File(nextEntry.getName().replace('\\', '/')).getName();
                        String lowerCase = name.toLowerCase(Locale.US);
                        if (!name.isEmpty() && !list.contains(lowerCase)) {
                            list.add(lowerCase);
                            zipOutputStream.putNextEntry(new ZipEntry(name));
                            copy(zipInputStream, zipOutputStream);
                            zipOutputStream.closeEntry();
                        }
                    }
                } else {
                    zipInputStream.close();
                    return;
                }
            } catch (Throwable th) {
                try {
                    zipInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
    }

    private void writeBytesAtomically(File file, byte[] bArr) {
        File file2 = new File(file.getParentFile(), file.getName() + ".importing");
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file2);
            try {
                fileOutputStream.write(bArr);
                fileOutputStream.close();
                if (file.exists() && !file.delete()) {
                    if (file2.delete()) {
                        return;
                    }
                    file2.deleteOnExit();
                } else {
                    if (file2.renameTo(file)) {
                        return;
                    }
                    copyFile(file2, file);
                    if (file2.delete()) {
                        return;
                    }
                    file2.deleteOnExit();
                }
            } finally {
            }
        } catch (IOException unused) {
            if (file2.delete()) {
                return;
            }
            file2.deleteOnExit();
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

    private void prepareNeoGeoBiosForArcade(File file, File file2) throws IOException {
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));
        boolean z = false;
        byte[] bArr = null;
        while (true) {
            try {
                ZipEntry nextEntry = zipInputStream.getNextEntry();
                if (nextEntry == null) {
                    break;
                }
                if (!nextEntry.isDirectory()) {
                    String lowerCase = new File(nextEntry.getName()).getName().toLowerCase(Locale.US);
                    if (lowerCase.equals("mame.sm1")) {
                        z = true;
                    } else if (bArr == null && (lowerCase.equals("sm1.sm1") || lowerCase.equals("sm1.bin") || lowerCase.equals("ng-sm1.rom"))) {
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        copy(zipInputStream, byteArrayOutputStream);
                        bArr = byteArrayOutputStream.toByteArray();
                    }
                }
            } catch (Throwable th) {
                try {
                    zipInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
        zipInputStream.close();
        File file3 = new File(file2.getParentFile(), file2.getName() + ".tmp");
        try {
            ZipInputStream zipInputStream2 = new ZipInputStream(new FileInputStream(file));
            try {
                ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(file3));
                while (true) {
                    try {
                        ZipEntry nextEntry2 = zipInputStream2.getNextEntry();
                        if (nextEntry2 == null) {
                            break;
                        }
                        zipOutputStream.putNextEntry(new ZipEntry(nextEntry2.getName()));
                        if (!nextEntry2.isDirectory()) {
                            copy(zipInputStream2, zipOutputStream);
                        }
                        zipOutputStream.closeEntry();
                    } finally {
                    }
                }
                if (!z && bArr != null) {
                    zipOutputStream.putNextEntry(new ZipEntry("mame.sm1"));
                    zipOutputStream.write(bArr);
                    zipOutputStream.closeEntry();
                }
                zipOutputStream.close();
                zipInputStream2.close();
                if (!copyFile(file3, file2)) {
                    throw new IOException("BIOS를 아케이드 ROM 폴더에 준비할 수 없습니다");
                }
                if (file3.delete()) {
                    return;
                }
                file3.deleteOnExit();
            } finally {
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
            return string;
        }
        String sanitizeSaveBaseName = sanitizeSaveBaseName(displayGameNameFor(file) + " [" + safeCrc32Hex(file) + "]");
        romPrefs.edit().putString("save:" + file.getName(), sanitizeSaveBaseName).apply();
        return sanitizeSaveBaseName;
    }

    private String displayGameNameFor(File file) {
        String readGbaHeaderTitle;
        String string = romPrefs().getString("title:" + file.getName(), null);
        if (string != null && !string.trim().isEmpty()) {
            return string;
        }
        String baseNameWithoutExtension = baseNameWithoutExtension(file.getName());
        return (hasDisplayableName(baseNameWithoutExtension) || (readGbaHeaderTitle = readGbaHeaderTitle(file)) == null) ? baseNameWithoutExtension : readGbaHeaderTitle;
    }

    private boolean isSupportedRom(File file) {
        String lowerCase = file.getName().toLowerCase(Locale.US);
        return lowerCase.endsWith(".gba") || !(!lowerCase.endsWith(".zip") || lowerCase.equals("neogeo.zip") || lowerCase.equals("stvbios.zip"));
    }

    private String coreFor(File file) {
        return "gba";
    }

    private String coreDisplayName(String str) {
        if ("mame2003plus".equals(str)) {
            return "MAME 2003-Plus";
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
        byte[] bArr = new byte[12];
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            try {
                if (fileInputStream.skip(160L) == 160) {
                    if (fileInputStream.read(bArr) == 12) {
                        fileInputStream.close();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 12; i++) {
                            int i2 = bArr[i] & 255;
                            if (i2 == 0 || i2 == 255) {
                                break;
                            }
                            if (i2 >= 32 && i2 <= 126) {
                                sb.append((char) i2);
                            }
                        }
                        String trim = sb.toString().trim();
                        if (trim.isEmpty()) {
                            return null;
                        }
                        return trim;
                    }
                }
                fileInputStream.close();
                return null;
            } finally {
            }
        } catch (IOException unused) {
            return null;
        }
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
        FileInputStream fileInputStream = new FileInputStream(file);
        while (true) {
            try {
                int read = fileInputStream.read(bArr);
                if (read != -1) {
                    crc32.update(bArr, 0, read);
                } else {
                    fileInputStream.close();
                    return String.format(Locale.US, "%08X", Long.valueOf(crc32.getValue()));
                }
            } catch (Throwable th) {
                try {
                    fileInputStream.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
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
