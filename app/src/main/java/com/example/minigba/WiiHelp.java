package com.example.minigba;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

// Wii 온라인 help shared by the app and the game: whether the Wii here has its own console
// (a NAND imported from the player's Wii, whose keys.bin Dolphin's importer writes), and
// "오류 코드 안내", what the numbers the online games show mean.
final class WiiHelp {
    static final String[][] CODES = {
            {"22001", "WiiLink WFC · Dolphin 추가 설정 필요", "모든 Dolphin이 같이 쓰는 기본 본체 정보라 서버가 막았어요.", "내 Wii의 NAND 가져오기 · 또는 친구끼리 방"},
            {"23934", "Wiimmfi · 본체 정보가 막힘", "여러 사람이 같이 쓰는 기본 본체 정보라 Wiimmfi가 막았어요.", "내 Wii의 NAND 가져오기 · 또는 친구끼리 방"},
            {"20100", "인증서 없음", "온라인에 필요한 Wii 인증서가 없어요.", "Wii 온라인 → Wii 시스템 업데이트"},
            {"60000", "처음 접속", "이 Wii로는 처음 접속해서 프로필이 아직 없어요.", "게임 안 Wi-Fi 메뉴에서 다시 접속하거나 새 라이선스 만들기"},
            {"23904", "Wiimmfi · 보안 패치 없음", "이 게임은 Wiimmfi가 요구하는 보안 패치가 없어서 막혀요.", "WiiLink WFC나 친구끼리 방 쓰기"},
            {"22002", "WiiLink WFC · 계정 정지", "WiiLink 이용 규칙 위반으로 이 계정이 막혔어요.", "WiiLink 운영진에게 문의 (wfc.wiilink.ca)"},
    };

    private WiiHelp() {
    }

    static File wiiRoot(android.content.Context context) {
        return new File(new File(new File(new File(context.getFilesDir(), "saves"), "dolphin"), "User"), "Wii");
    }

    // A NAND from the player's own Wii is in (Dolphin's default console has no keys.bin).
    static boolean ownConsole(android.content.Context context) {
        return new File(wiiRoot(context), "keys.bin").isFile();
    }

    static String[] lookup(String code) {
        String digits = code == null ? "" : code.replaceAll("[^0-9]", "");
        for (String[] entry : CODES) {
            if (entry[0].equals(digits)) {
                return entry;
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Theme.bg(color));
        shape.setCornerRadius(radius);
        return shape;
    }

    // "오류 코드 안내": type the number the game showed.
    static void show(Activity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(1);
        panel.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), 0);
        final EditText input = new EditText(activity);
        input.setHint("게임에 나온 숫자 (예: 22001)");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextSize(16.0f);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(input);
        final TextView result = new TextView(activity);
        result.setTextSize(13.0f);
        result.setLineSpacing(0, 1.2f);
        result.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        result.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(-1, -2);
        resultParams.setMargins(0, dp(activity, 8), 0, 0);
        panel.addView(result, resultParams);
        TextView list = new TextView(activity);
        StringBuilder text = new StringBuilder("자주 나오는 코드\n");
        for (String[] entry : CODES) {
            text.append(entry[0]).append("  ").append(entry[1]).append('\n');
        }
        list.setText(text.toString().trim());
        list.setTextSize(12.0f);
        list.setTextColor(Theme.text(Color.rgb(75, 85, 99)));
        list.setLineSpacing(0, 1.25f);
        list.setPadding(0, dp(activity, 12), 0, 0);
        panel.addView(list);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String typed = s.toString().trim();
                if (typed.length() < 5) {
                    result.setVisibility(android.view.View.GONE);
                    return;
                }
                String[] entry = lookup(typed);
                result.setVisibility(android.view.View.VISIBLE);
                if (entry == null) {
                    result.setText(typed + " · 앱에 정리해 둔 코드가 아니에요. 게임 이름과 함께 검색해 보세요 (예: \"마리오 카트 Wii " + typed + "\").");
                    result.setTextColor(Theme.text(Color.rgb(55, 65, 81)));
                    result.setBackground(rounded(Color.rgb(246, 247, 249), dp(activity, 12)));
                } else {
                    result.setText(entry[1] + "\n" + entry[2] + "\n해결: " + entry[3]);
                    result.setTextColor(Theme.text(Color.rgb(127, 29, 29)));
                    result.setBackground(rounded(Color.rgb(254, 242, 242), dp(activity, 12)));
                }
            }
        });
        new AppDialog.Builder(activity).setTitle("오류 코드 안내").setView(panel).setPositiveButton("닫기", null).show();
    }
}
