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

    static boolean isShort(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.heightPixels < 560 * metrics.density;
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
