package com.example.minigba;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * "화면의 버튼을 눌러 고르기": lies over the whole game screen, shows a hint card where the
 * game picture is and hands every touch to a hit test instead of the buttons; the control
 * under the finger is reported so its colour can be picked. "그만" ends it.
 */
final class ButtonPickOverlay extends FrameLayout {
    interface HitTest {
        /** The control id under a window point, or null. */
        String controlAt(float x, float y);
    }

    interface Listener {
        void onPicked(String controlId, String controlName);

        void onStop();
    }

    private final HitTest hitTest;
    private final Listener listener;
    private final LinearLayout card;
    private final TextView status;

    ButtonPickOverlay(Context context, Rect screen, HitTest hitTest, Listener listener) {
        super(context);
        this.hitTest = hitTest;
        this.listener = listener;
        setClickable(true);

        LinearLayout card = new LinearLayout(context);
        this.card = card;
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(242, 255, 255, 255));
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        TextView title = new TextView(context);
        title.setText("색을 바꿀 버튼을 눌러 주세요");
        title.setTextSize(15.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(31, 41, 51));
        card.addView(title);
        TextView body = new TextView(context);
        body.setText("누르면 그 버튼의 색 고르기가 열려요. 방향키 · 스틱 · L · R · SELECT · START도 돼요.\n누르는 동안 게임에는 입력이 가지 않아요.");
        body.setTextSize(12.0f);
        body.setTextColor(Color.rgb(85, 95, 110));
        body.setPadding(0, dp(4), 0, 0);
        card.addView(body);
        TextView status = new TextView(context);
        this.status = status;
        status.setTextSize(12.0f);
        status.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        status.setTextColor(Color.rgb(225, 29, 72));
        status.setPadding(0, dp(6), 0, 0);
        card.addView(status);
        TextView stop = new TextView(context);
        stop.setText("그만 · 창으로 돌아가기");
        stop.setTextSize(13.5f);
        stop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        stop.setTextColor(Color.WHITE);
        stop.setGravity(Gravity.CENTER);
        GradientDrawable stopBackground = new GradientDrawable();
        stopBackground.setColor(Color.rgb(225, 29, 72));
        stopBackground.setCornerRadius(dp(12));
        stop.setBackground(stopBackground);
        stop.setOnClickListener(view -> listener.onStop());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, dp(42));
        stopParams.setMargins(0, dp(12), 0, 0);
        card.addView(stop, stopParams);

        FrameLayout.LayoutParams params;
        if (screen != null && screen.width() > dp(200)) {
            params = new FrameLayout.LayoutParams(Math.min(screen.width(), dp(440)), -2);
            params.leftMargin = screen.left + (screen.width() - params.width) / 2;
            params.topMargin = screen.top + dp(8);
        } else {
            params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
            params.setMargins(dp(16), dp(40), dp(16), 0);
        }
        addView(card, params);
    }

    void setStatus(String text) {
        this.status.setText(text);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // The card's own button still works; everything else is a pick.
        float x = event.getX();
        float y = event.getY();
        boolean onCard = x >= this.card.getLeft() && x <= this.card.getRight() && y >= this.card.getTop() && y <= this.card.getBottom();
        return !onCard;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int[] origin = new int[2];
            getLocationInWindow(origin);
            String id = this.hitTest.controlAt(event.getX() + origin[0], event.getY() + origin[1]);
            if (id != null) {
                this.listener.onPicked(id, null);
            } else {
                setStatus("버튼이 아닌 곳이에요. 버튼을 눌러 주세요.");
            }
        }
        return true;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
