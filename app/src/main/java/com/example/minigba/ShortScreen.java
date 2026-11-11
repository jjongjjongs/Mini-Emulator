package com.example.minigba;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

// (0.0.408) Sheets built as a header, a list that takes the room left (a ScrollView with weight)
// and buttons, in a window of a fixed share of the screen. On a short screen (landscape) the
// list's share is a sliver no one can scroll, so there the list is laid out in full and the
// whole sheet scrolls instead, header and buttons with it.
final class ShortScreen {
    private ShortScreen() {
    }

    // (0.0.409) Landscape, as the player asked: these sheets change with the screen's direction.
    static boolean isShort(Context context) {
        return twoColumns(context);
    }

    static boolean twoColumns(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.widthPixels > metrics.heightPixels;
    }

    static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    /** A left column: content scrolls if it must, bottom (the buttons) always shows under it. */
    static LinearLayout leftWithBottom(Context context, LinearLayout content, View bottom) {
        ViewGroup.LayoutParams bottomParams = bottom == null ? null : bottom.getLayoutParams();
        detach(bottom);
        detach(content);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        if (bottom != null) {
            LinearLayout.LayoutParams params = bottomParams instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) bottomParams : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            column.addView(bottom, params);
        }
        return column;
    }

    /**
     * (0.0.409) A sheet in two columns on a landscape screen: left (fixed width) and right (the
     * list, scrolling on its own), on one card with the sheet's background, as wide as the screen
     * allows and nearly as tall. The dialog gets it as its content.
     */
    static void showColumns(android.app.Dialog dialog, View left, View right, android.graphics.drawable.Drawable background, int leftDp) {
        Context context = dialog.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        detach(left);
        detach(right);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(background);
        root.addView(left, new LinearLayout.LayoutParams(Math.round(leftDp * density), ViewGroup.LayoutParams.MATCH_PARENT));
        View list = right;
        if (!(right instanceof ScrollView)) {
            ScrollView scroll = new ScrollView(context);
            scroll.addView(right, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list = scroll;
        }
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        listParams.setMargins(pad, 0, 0, 0);
        root.addView(list, listParams);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            window.setLayout(Math.min(metrics.widthPixels - Math.round(32 * density), Math.round(760 * density)), Math.round(metrics.heightPixels * 0.92f));
        }
    }

    /** The view to show: content itself, or on a short screen content in a scroll of its own. */
    static View fit(Context context, View content) {
        if (!isShort(context) || !(content instanceof LinearLayout)) {
            return content;
        }
        LinearLayout panel = (LinearLayout) content;
        boolean changed = false;
        for (int k = 0; k < panel.getChildCount(); k++) {
            View child = panel.getChildAt(k);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (child instanceof ScrollView && raw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) raw;
                if (params.weight > 0 && params.height == 0) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    params.weight = 0;
                    child.setLayoutParams(params);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return content;
        }
        ScrollView outer = new ScrollView(context);
        outer.setVerticalScrollBarEnabled(true);
        outer.addView(panel, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    /** The window height for what fit gave: as wanted, or as tall as it needs when it scrolls. */
    static int height(View shown, View content, int wanted) {
        return shown != content ? ViewGroup.LayoutParams.WRAP_CONTENT : wanted;
    }
}
