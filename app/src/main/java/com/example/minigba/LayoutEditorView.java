package com.example.minigba;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 버튼 배치 편집: covers the game; drag a control to move it, pinch (or −/＋) to resize it.
 * The game screen's frame is one of the controls too: it moves the same way, keeps inside
 * the phone screen, snaps to the horizontal centre, and can be sized on each axis alone
 * when "비율 잠금" is off. Offsets are in dp, sizes scale factors (x and y); the activity
 * applies them to the real views.
 */
final class LayoutEditorView extends FrameLayout {
    interface Listener {
        void apply(int index, float dxDp, float dyDp, float scaleX, float scaleY);

        void onSave(float[][] edits);

        void onCancel();

        // A finger held still on a control (DOS: take a key out of its row, or put it back).
        // True when the activity handled it (it may rebuild the controls and this editor).
        default boolean onHold(int index, float rawX, float rawY) {
            return false;
        }
    }

    private int holdIndex = -1;
    private float holdX;
    private float holdY;
    private float downX;
    private float downY;
    private Listener listenerRef;
    private final Runnable hold = this::onHoldFired;

    private void onHoldFired() {
        int index = holdIndex;
        holdIndex = -1;
        if (index < 0 || listenerRef == null) return;
        if (listenerRef.onHold(index, holdX, holdY)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        // (0.0.428) A button of the group, held: that one alone until the next tap elsewhere.
        if (inGroup(index)) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            single = index;
            groupMode = false;
            selected = index;
            updateSizeText();
            invalidate();
        }
    }

    // ---- (0.0.428) 격자 · 오른쪽 버튼 묶음 ----
    // The grid: a guide drawn over the editor (24 dp squares, a stronger line every 96 dp),
    // on or off with "격자" (kept for every console and both directions).
    private boolean showGrid;
    private final Paint gridMinor = new Paint();
    private final Paint gridMajor = new Paint();
    private TextView gridButton;
    // The group: the face buttons on the right (A B X Y, △ ○ × □, A B C X Y Z ...), moved
    // and sized together; a held one goes on its own (single) until a tap elsewhere.
    private boolean grouping = true;
    private List<Integer> group;
    private boolean groupMode;
    private int single = -1;
    private final Paint groupLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groupDash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[][] pinchStart;
    private static final java.util.Set<String> FACE_LABELS = new java.util.HashSet<>(java.util.Arrays.asList(
            "A", "B", "C", "D", "E", "F", "X", "Y", "Z", "I", "II", "Ⅰ", "Ⅱ", "△", "○", "×", "✕", "□"));

    /** Off for screens whose keys aren't a console's buttons (DOS keyboards). */
    void setGrouping(boolean on) {
        grouping = on;
        group = null;
    }

    private List<Integer> group() {
        if (group != null) return group;
        List<Integer> found = new java.util.ArrayList<>();
        if (grouping && getWidth() > 0) {
            for (int k = 0; k < controls.size(); k++) {
                View view = controls.get(k);
                if (k == screenIndex || !(view instanceof TouchButton) || view.getVisibility() != VISIBLE || !view.isAttachedToWindow()) continue;
                TouchButton button = (TouchButton) view;
                if (button.isShoulder() || !FACE_LABELS.contains(button.label())) continue;
                RectF box = boxOf(view);
                // Round or square buttons on the right half (not keyboard keys, not the left side).
                if (box.width() > box.height() * 1.3f || box.height() > box.width() * 1.3f) continue;
                if (box.centerX() < getWidth() / 2f) continue;
                found.add(k);
            }
        }
        group = found.size() >= 2 ? found : new java.util.ArrayList<>();
        return group;
    }

    private boolean inGroup(int index) {
        return index >= 0 && group().contains(index);
    }

    private RectF groupBox() {
        RectF union = null;
        for (int k : group()) {
            RectF box = boxOf(controls.get(k));
            if (union == null) union = box;
            else union.union(box);
        }
        if (union != null) union.inset(-dp(7), -dp(7));
        return union;
    }

    // Whether the group runs into the top bar, a corner or the game screen.
    private boolean groupBlocked() {
        RectF screen = screenIndex >= 0 ? boxOf(controls.get(screenIndex)) : null;
        if (screen != null) screen.inset(dp(1), dp(1));
        for (int k : group()) {
            if (hitsGuard(k)) return true;
            if (screen != null) {
                RectF box = boxOf(controls.get(k));
                box.inset(dp(1), dp(1));
                if (RectF.intersects(box, screen)) return true;
            }
        }
        return false;
    }

    // Moves the whole group; a direction that would put it on the top bar, a corner or the
    // game screen is dropped (unless it was already there).
    private void moveGroup(float dx, float dy) {
        List<Integer> members = group();
        float[][] before = new float[members.size()][];
        for (int m = 0; m < members.size(); m++) before[m] = edits[members.get(m)].clone();
        boolean wasClear = !groupBlocked();
        float[][] tries = wasClear ? new float[][]{{dx, dy}, {dx, 0}, {0, dy}, {0, 0}} : new float[][]{{dx, dy}};
        for (float[] step : tries) {
            for (int m = 0; m < members.size(); m++) {
                int k = members.get(m);
                System.arraycopy(before[m], 0, edits[k], 0, 4);
                edits[k][0] += step[0];
                edits[k][1] += step[1];
                listener.apply(k, edits[k][0], edits[k][1], edits[k][2], edits[k][3]);
            }
            if (!wasClear || !groupBlocked()) break;
        }
        updateSizeText();
        invalidate();
    }

    // Sizes the whole group by ratio around its centre: each button's size and its distance
    // from the centre.
    private void scaleGroup(float[][] from, float ratio) {
        List<Integer> members = group();
        RectF centre = groupBox();
        if (centre == null) return;
        // Back to where the gesture began, then the ratio from there.
        for (int m = 0; m < members.size(); m++) {
            int k = members.get(m);
            System.arraycopy(from[m], 0, edits[k], 0, 4);
            listener.apply(k, edits[k][0], edits[k][1], edits[k][2], edits[k][3]);
        }
        RectF start = groupBox();
        if (start == null) return;
        float minRatio = 0;
        float maxRatio = Float.MAX_VALUE;
        for (int m = 0; m < members.size(); m++) {
            minRatio = Math.max(minRatio, MIN_SCALE / from[m][2]);
            maxRatio = Math.min(maxRatio, MAX_SCALE / from[m][2]);
        }
        float r = Math.max(minRatio, Math.min(maxRatio, ratio));
        for (int m = 0; m < members.size(); m++) {
            int k = members.get(m);
            RectF box = boxOf(controls.get(k));
            float cx = box.centerX() - start.centerX();
            float cy = box.centerY() - start.centerY();
            // The control grows around its own top left as its scale changes (pivot 0, 0) or
            // its centre: the centre moves by the size change times the pivot's share.
            View view = controls.get(k);
            float pivotShiftX = view.getPivotX() == 0 ? -view.getWidth() * from[m][2] * (r - 1) / 2f : 0;
            float pivotShiftY = view.getPivotY() == 0 ? -view.getHeight() * from[m][3] * (r - 1) / 2f : 0;
            edits[k][2] = from[m][2] * r;
            edits[k][3] = from[m][3] * r;
            edits[k][0] = from[m][0] + (cx * (r - 1) + pivotShiftX) / density;
            edits[k][1] = from[m][1] + (cy * (r - 1) + pivotShiftY) / density;
            listener.apply(k, edits[k][0], edits[k][1], edits[k][2], edits[k][3]);
        }
        updateSizeText();
        invalidate();
    }

    private float[][] groupEdits() {
        List<Integer> members = group();
        float[][] copy = new float[members.size()][];
        for (int m = 0; m < members.size(); m++) copy[m] = edits[members.get(m)].clone();
        return copy;
    }

    private void toggleGrid() {
        showGrid = !showGrid;
        MainActivity.sharedSettings(getContext()).edit().putBoolean("layout_grid", showGrid).apply();
        gridButton.setBackground(rounded(showGrid ? Color.rgb(124, 58, 237) : Color.rgb(75, 85, 99), dp(10)));
        invalidate();
    }

    /** The edits as they stand (dx dp, dy dp, scale x, scale y per control). */
    float[][] currentEdits() {
        return edits;
    }

    private static final float MIN_SCALE = 0.6f;
    private static final float MAX_SCALE = 1.8f;
    private static final float MIN_SCREEN = 0.5f;
    private static final float MAX_SCREEN = 1.5f;

    private final List<View> controls;
    /** Per control: dx dp, dy dp, scale x, scale y. */
    private final float[][] edits;
    private final float[][] original;
    private final Listener listener;
    private final int screenIndex;
    private final float density;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screenOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView sizeText;
    private final LinearLayout screenRow;
    private final TextView lockButton;
    private final int[] here = new int[2];
    private final int[] there = new int[2];
    private int selected = -1;
    private float lastX;
    private float lastY;
    private float startDistance;
    private float startScaleX;
    private float startScaleY;
    private boolean aspectLocked = true;
    private boolean snapped;
    // The app's own buttons the controls may not cover: the top bar in portrait, the two
    // top corners in landscape.
    private List<View> guards = new java.util.ArrayList<>();
    private final Paint guardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guardLine = new Paint(Paint.ANTI_ALIAS_FLAG);

    LayoutEditorView(Context context, List<View> controls, float[][] edits, int screenIndex, Listener listener) {
        super(context);
        this.controls = controls;
        this.edits = edits;
        this.screenIndex = screenIndex;
        this.original = new float[edits.length][];
        for (int k = 0; k < edits.length; k++) {
            this.original[k] = edits[k].clone();
        }
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setBackgroundColor(Color.argb(70, 0, 0, 0));
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2.5f));
        outline.setColor(Color.rgb(250, 204, 21));
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(1));
        frame.setColor(Color.argb(150, 255, 255, 255));
        frame.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4), dp(4)}, 0));
        screenOutline.setStyle(Paint.Style.STROKE);
        screenOutline.setStrokeWidth(dp(2.5f));
        screenOutline.setColor(Color.rgb(34, 185, 207));
        screenOutline.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(8), dp(5)}, 0));
        handle.setStyle(Paint.Style.FILL);
        handle.setColor(Color.rgb(34, 185, 207));
        handleRim.setStyle(Paint.Style.STROKE);
        handleRim.setStrokeWidth(dp(2));
        handleRim.setColor(Color.WHITE);
        snapLine.setStyle(Paint.Style.STROKE);
        snapLine.setStrokeWidth(dp(1.5f));
        snapLine.setColor(Color.rgb(245, 158, 11));
        snapLine.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));
        guardFill.setStyle(Paint.Style.FILL);
        guardFill.setColor(Color.argb(70, 245, 158, 11));
        guardLine.setStyle(Paint.Style.STROKE);
        guardLine.setStrokeWidth(dp(2));
        guardLine.setColor(Color.rgb(245, 158, 11));
        guardLine.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));
        gridMinor.setColor(Color.argb(30, 255, 255, 255));
        gridMinor.setStrokeWidth(Math.max(1, dp(0.5f)));
        gridMajor.setColor(Color.argb(70, 255, 255, 255));
        gridMajor.setStrokeWidth(Math.max(1, dp(1)));
        groupLine.setStyle(Paint.Style.STROKE);
        groupLine.setStrokeWidth(dp(2.5f));
        groupLine.setColor(Color.rgb(250, 204, 21));
        groupDash.setStyle(Paint.Style.STROKE);
        groupDash.setStrokeWidth(dp(1.5f));
        groupDash.setColor(Color.argb(150, 250, 204, 21));
        groupDash.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));
        showGrid = MainActivity.sharedSettings(context).getBoolean("layout_grid", true);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(rounded(Color.argb(235, 17, 24, 39), dp(16)));
        TextView hint = new TextView(context);
        hint.setText(screenIndex >= 0 ? "편집 중 · 버튼과 게임 화면을 끌어서 옮기기, 두 손가락으로 크기" : "편집 중 · 끌어서 옮기기, 두 손가락으로 크기");
        hint.setTextColor(Color.rgb(253, 230, 138));
        hint.setTextSize(12.0f);
        card.addView(hint);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        sizeText = new TextView(context);
        sizeText.setTextColor(Color.WHITE);
        sizeText.setTextSize(13.0f);
        sizeText.setMinWidth(dp(78));
        row.addView(sizeText);
        row.addView(button("−", Color.rgb(55, 65, 81), view -> stepScale(-0.1f, true, true)), buttonParams(40));
        row.addView(button("＋", Color.rgb(55, 65, 81), view -> stepScale(0.1f, true, true)), buttonParams(40));
        gridButton = button("격자", showGrid ? Color.rgb(124, 58, 237) : Color.rgb(75, 85, 99), view -> toggleGrid());
        row.addView(gridButton, buttonParams(46));
        row.addView(button("초기화", Color.rgb(75, 85, 99), view -> askReset()), buttonParams(62));
        row.addView(button("취소", Color.rgb(75, 85, 99), view -> cancel()), buttonParams(52));
        row.addView(button("저장", Color.rgb(22, 185, 207), view -> listener.onSave(this.edits)), buttonParams(52));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-2, -2);
        rowParams.setMargins(0, dp(6), 0, 0);
        card.addView(row, rowParams);

        // The game screen's own row: aspect lock, each axis alone, ratio presets, centring.
        screenRow = new LinearLayout(context);
        screenRow.setGravity(Gravity.CENTER_VERTICAL);
        lockButton = button("비율 잠금", Color.rgb(22, 185, 207), view -> {
            aspectLocked = !aspectLocked;
            refreshScreenRow();
        });
        screenRow.addView(lockButton, buttonParams(74));
        screenRow.addView(button("가로 −", Color.rgb(55, 65, 81), view -> stepScale(-0.05f, true, false)), buttonParams(56));
        screenRow.addView(button("가로 ＋", Color.rgb(55, 65, 81), view -> stepScale(0.05f, true, false)), buttonParams(56));
        screenRow.addView(button("세로 −", Color.rgb(55, 65, 81), view -> stepScale(-0.05f, false, true)), buttonParams(56));
        screenRow.addView(button("세로 ＋", Color.rgb(55, 65, 81), view -> stepScale(0.05f, false, true)), buttonParams(56));
        LinearLayout.LayoutParams screenRowParams = new LinearLayout.LayoutParams(-2, -2);
        screenRowParams.setMargins(0, dp(6), 0, 0);
        card.addView(screenRow, screenRowParams);
        LinearLayout presets = new LinearLayout(context);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        presets.addView(button("원래 비율", Color.rgb(75, 85, 99), view -> applyRatio(0)), buttonParams(70));
        presets.addView(button("4:3", Color.rgb(75, 85, 99), view -> applyRatio(4 / 3f)), buttonParams(44));
        presets.addView(button("16:9", Color.rgb(75, 85, 99), view -> applyRatio(16 / 9f)), buttonParams(48));
        presets.addView(button("꽉 채움", Color.rgb(75, 85, 99), view -> fillScreen()), buttonParams(62));
        presets.addView(button("가운데", Color.rgb(75, 85, 99), view -> centreScreen()), buttonParams(56));
        LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(-2, -2);
        presetParams.setMargins(0, dp(6), 0, 0);
        screenRow.setTag(presets);
        card.addView(presets, presetParams);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardParams.setMargins(0, dp(8), 0, 0);
        addView(card, cardParams);
        // Touches on the card stay on the card.
        card.setClickable(true);
        refreshScreenRow();
        updateSizeText();
    }

    void setGuards(List<View> guards) {
        this.guards = guards;
        invalidate();
    }

    private List<RectF> guardBoxes() {
        List<RectF> boxes = new java.util.ArrayList<>();
        for (View guard : guards) {
            if (guard != null && guard.getVisibility() == VISIBLE && guard.isAttachedToWindow() && guard.getWidth() > 0) {
                boxes.add(boxOf(guard));
            }
        }
        return boxes;
    }

    // Whether a control's box runs into the top bar or a corner.
    private boolean hitsGuard(int index) {
        RectF box = boxOf(controls.get(index));
        box.inset(dp(1), dp(1));
        for (RectF guard : guardBoxes()) {
            if (RectF.intersects(box, guard)) {
                return true;
            }
        }
        return false;
    }

    // Moves a control down below the guards it runs into; false when it then leaves the
    // phone screen at the bottom.
    private boolean pushBelowGuards(int index) {
        for (int pass = 0; pass < 3; pass++) {
            RectF box = boxOf(controls.get(index));
            box.inset(dp(1), dp(1));
            float down = 0;
            for (RectF guard : guardBoxes()) {
                if (RectF.intersects(box, guard)) {
                    down = Math.max(down, guard.bottom - box.top + dp(1));
                }
            }
            if (down <= 0) {
                return boxOf(controls.get(index)).bottom <= getHeight() + dp(1);
            }
            edits[index][1] += down / density;
            listener.apply(index, edits[index][0], edits[index][1], edits[index][2], edits[index][3]);
        }
        return !hitsGuard(index);
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView button(String label, int color, OnClickListener onClick) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMaxLines(1);
        button.setBackground(rounded(color, dp(10)));
        button.setOnClickListener(onClick);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(34));
        params.setMargins(dp(6), 0, 0, 0);
        return params;
    }

    private boolean screenSelected() {
        return selected >= 0 && selected == screenIndex;
    }

    private void refreshScreenRow() {
        boolean show = screenSelected();
        screenRow.setVisibility(show ? VISIBLE : GONE);
        ((View) screenRow.getTag()).setVisibility(show ? VISIBLE : GONE);
        lockButton.setText(aspectLocked ? "비율 잠금 켬" : "비율 잠금 끔");
        lockButton.setBackground(rounded(aspectLocked ? Color.rgb(22, 185, 207) : Color.rgb(107, 114, 128), dp(10)));
        for (int k = 1; k < screenRow.getChildCount(); k++) {
            screenRow.getChildAt(k).setAlpha(aspectLocked ? 0.4f : 1.0f);
            screenRow.getChildAt(k).setEnabled(!aspectLocked);
        }
    }

    private void updateSizeText() {
        if (selected < 0) {
            sizeText.setText(screenIndex >= 0 ? "버튼 · 화면 선택" : "버튼 선택");
        } else if (screenSelected()) {
            float sx = edits[selected][2];
            float sy = edits[selected][3];
            sizeText.setText(Math.abs(sx - sy) < 0.005f ? "화면 " + Math.round(sx * 100) + "%" : "화면 " + Math.round(sx * 100) + "×" + Math.round(sy * 100) + "%");
        } else if (groupMode) {
            sizeText.setText("묶음 " + Math.round(edits[selected][2] * 100) + "%");
        } else if (single == selected && controls.get(selected) instanceof TouchButton) {
            sizeText.setText(((TouchButton) controls.get(selected)).label() + " " + Math.round(edits[selected][2] * 100) + "%");
        } else {
            sizeText.setText("크기 " + Math.round(edits[selected][2] * 100) + "%");
        }
    }

    private void stepScale(float step, boolean x, boolean y) {
        if (selected < 0) {
            return;
        }
        if (groupMode) {
            float now = edits[selected][2];
            float target = Math.round((now + step) * 20) / 20.0f;
            scaleGroup(groupEdits(), target / now);
            return;
        }
        float sx = edits[selected][2];
        float sy = edits[selected][3];
        if (!screenSelected() || aspectLocked) {
            float scale = Math.round((sx + step) * 20) / 20.0f;
            setScale(selected, scale, scale);
            return;
        }
        setScale(selected, x ? Math.round((sx + step) * 20) / 20.0f : sx, y ? Math.round((sy + step) * 20) / 20.0f : sy);
    }

    private void setScale(int index, float scaleX, float scaleY) {
        float min = index == screenIndex ? MIN_SCREEN : MIN_SCALE;
        float max = index == screenIndex ? MAX_SCREEN : MAX_SCALE;
        float[] before = edits[index].clone();
        boolean wasClear = !hitsGuard(index);
        edits[index][2] = Math.max(min, Math.min(max, scaleX));
        edits[index][3] = Math.max(min, Math.min(max, scaleY));
        if (index == screenIndex) {
            keepScreenInside();
        }
        listener.apply(index, edits[index][0], edits[index][1], edits[index][2], edits[index][3]);
        // Growing into the top bar or a corner: down below it if there is room, else the
        // size stops where it was.
        if (wasClear && hitsGuard(index) && !pushBelowGuards(index)) {
            System.arraycopy(before, 0, edits[index], 0, 4);
        }
        applyEdit(index);
    }

    private void applyEdit(int index) {
        listener.apply(index, edits[index][0], edits[index][1], edits[index][2], edits[index][3]);
        updateSizeText();
        invalidate();
    }

    // ---- the game screen ----

    // Width over height the screen shows at (its frame's unscaled size times the scales).
    private void applyRatio(float ratio) {
        if (!screenSelected()) return;
        View screen = controls.get(selected);
        if (screen.getWidth() == 0 || screen.getHeight() == 0) return;
        float sx = edits[selected][2];
        if (ratio <= 0) {
            setScale(selected, sx, sx);
            return;
        }
        float sy = screen.getWidth() * sx / (ratio * screen.getHeight());
        aspectLocked = false;
        refreshScreenRow();
        setScale(selected, sx, sy);
    }

    // Portrait: as wide as the phone, height following the ratio. Landscape: the whole phone.
    private void fillScreen() {
        if (!screenSelected()) return;
        View screen = controls.get(selected);
        if (screen.getWidth() == 0 || screen.getHeight() == 0 || getWidth() == 0) return;
        boolean landscape = getWidth() > getHeight();
        float sx = getWidth() / (float) screen.getWidth();
        float sy = landscape ? getHeight() / (float) screen.getHeight() : sx;
        if (landscape) {
            aspectLocked = false;
            refreshScreenRow();
        }
        setScale(selected, sx, sy);
        centreScreen();
    }

    private void centreScreen() {
        if (!screenSelected()) return;
        RectF box = boxOf(controls.get(selected));
        edits[selected][0] += (getWidth() / 2f - box.centerX()) / density;
        if (getWidth() > getHeight()) {
            edits[selected][1] += (getHeight() / 2f - box.centerY()) / density;
        }
        keepScreenInside();
        listener.apply(selected, edits[selected][0], edits[selected][1], edits[selected][2], edits[selected][3]);
        if (hitsGuard(selected)) {
            pushBelowGuards(selected);
        }
        applyEdit(selected);
    }

    // The screen's frame stays within the phone screen; the edits are shifted when needed.
    private void keepScreenInside() {
        if (screenIndex < 0 || getWidth() == 0) return;
        View screen = controls.get(screenIndex);
        // Where it would be after the pending edit: measure from the current box, which
        // reflects the last applied edit, and the change in offsets and scales since.
        listener.apply(screenIndex, edits[screenIndex][0], edits[screenIndex][1], edits[screenIndex][2], edits[screenIndex][3]);
        RectF box = boxOf(screen);
        float shiftX = 0;
        float shiftY = 0;
        if (box.width() <= getWidth()) {
            if (box.left < 0) shiftX = -box.left;
            else if (box.right > getWidth()) shiftX = getWidth() - box.right;
        } else {
            shiftX = (getWidth() - box.width()) / 2f - box.left;
        }
        if (box.height() <= getHeight()) {
            if (box.top < 0) shiftY = -box.top;
            else if (box.bottom > getHeight()) shiftY = getHeight() - box.bottom;
        } else {
            shiftY = -box.top;
        }
        edits[screenIndex][0] += shiftX / density;
        edits[screenIndex][1] += shiftY / density;
    }

    private void askReset() {
        if (screenIndex < 0) {
            resetAll(true, true);
            return;
        }
        new AppDialog.Builder(getContext()).setTitle("초기화")
                .setItems(new String[]{"게임 화면만 원래 자리로", "버튼까지 모두 원래대로"}, (d, which) -> resetAll(which == 1, true))
                .setNegativeButton("취소", null).show();
    }

    private void resetAll(boolean buttons, boolean screen) {
        single = -1;
        for (int k = 0; k < edits.length; k++) {
            if (k == screenIndex ? !screen : !buttons) continue;
            edits[k][0] = 0;
            edits[k][1] = 0;
            edits[k][2] = 1;
            edits[k][3] = 1;
            listener.apply(k, 0, 0, 1, 1);
        }
        updateSizeText();
        invalidate();
    }

    private void cancel() {
        for (int k = 0; k < edits.length; k++) {
            System.arraycopy(original[k], 0, edits[k], 0, 4);
            listener.apply(k, edits[k][0], edits[k][1], edits[k][2], edits[k][3]);
        }
        listener.onCancel();
    }

    // The control's box on screen, relative to this view (translation and scale included).
    private RectF boxOf(View view) {
        getLocationOnScreen(here);
        view.getLocationOnScreen(there);
        float left = there[0] - here[0];
        float top = there[1] - here[1];
        return new RectF(left, top, left + view.getWidth() * view.getScaleX(), top + view.getHeight() * view.getScaleY());
    }

    private int controlAt(float x, float y) {
        int best = -1;
        float bestArea = Float.MAX_VALUE;
        for (int k = 0; k < controls.size(); k++) {
            View view = controls.get(k);
            if (view.getVisibility() != VISIBLE || !view.isAttachedToWindow()) {
                continue;
            }
            RectF box = boxOf(view);
            box.inset(-dp(6), -dp(6));
            // The smallest box under the finger: a button over a big stick (or the screen) wins.
            float area = box.width() * box.height();
            if (box.contains(x, y) && area < bestArea) {
                best = k;
                bestArea = area;
            }
        }
        return best;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (showGrid) {
            float step = dp(24);
            int n = 0;
            for (float x = 0; x <= getWidth(); x += step, n++) {
                canvas.drawLine(x, 0, x, getHeight(), n % 4 == 0 ? gridMajor : gridMinor);
            }
            n = 0;
            for (float y = 0; y <= getHeight(); y += step, n++) {
                canvas.drawLine(0, y, getWidth(), y, n % 4 == 0 ? gridMajor : gridMinor);
            }
        }
        // The top bar / corner buttons: shaded, with the line the controls stop at.
        for (RectF guard : guardBoxes()) {
            canvas.drawRect(guard, guardFill);
            canvas.drawLine(guard.left, guard.bottom, guard.right, guard.bottom, guardLine);
            if (guard.left > dp(2)) canvas.drawLine(guard.left, guard.top, guard.left, guard.bottom, guardLine);
            if (guard.right < getWidth() - dp(2)) canvas.drawLine(guard.right, guard.top, guard.right, guard.bottom, guardLine);
        }
        for (int k = 0; k < controls.size(); k++) {
            View view = controls.get(k);
            if (view.getVisibility() != VISIBLE || !view.isAttachedToWindow()) {
                continue;
            }
            RectF box = boxOf(view);
            if (k == screenIndex) {
                canvas.drawRoundRect(box, dp(8), dp(8), k == selected ? screenOutline : frame);
                if (k == selected) {
                    float r = dp(7);
                    float[][] corners = {{box.left, box.top}, {box.right, box.top}, {box.left, box.bottom}, {box.right, box.bottom}};
                    for (float[] corner : corners) {
                        canvas.drawCircle(corner[0], corner[1], r, handle);
                        canvas.drawCircle(corner[0], corner[1], r, handleRim);
                    }
                }
            } else {
                boolean lit = k == selected && !groupMode;
                canvas.drawRoundRect(box, dp(8), dp(8), lit ? outline : frame);
            }
        }
        if (groupMode || (single >= 0 && single == selected)) {
            RectF union = groupBox();
            if (union != null) canvas.drawRoundRect(union, dp(14), dp(14), groupMode ? groupLine : groupDash);
        }
        if (snapped && screenSelected()) {
            canvas.drawLine(getWidth() / 2f, 0, getWidth() / 2f, getHeight(), snapLine);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selected = controlAt(event.getX(), event.getY());
                // A group button: the whole group, unless it is the one taken out by a hold.
                if (selected >= 0 && selected == single) {
                    groupMode = false;
                } else {
                    single = -1;
                    groupMode = inGroup(selected);
                }
                lastX = event.getX();
                lastY = event.getY();
                downX = event.getX();
                downY = event.getY();
                listenerRef = listener;
                removeCallbacks(hold);
                if (selected >= 0) {
                    holdIndex = selected;
                    holdX = event.getRawX();
                    holdY = event.getRawY();
                    postDelayed(hold, 600);
                }
                snapped = false;
                refreshScreenRow();
                updateSizeText();
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(hold);
                if (selected >= 0 && event.getPointerCount() == 2) {
                    startDistance = distance(event);
                    startScaleX = edits[selected][2];
                    startScaleY = edits[selected][3];
                    pinchStart = groupMode ? groupEdits() : null;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(10)) {
                    removeCallbacks(hold);
                }
                if (selected < 0) {
                    return true;
                }
                if (event.getPointerCount() >= 2 && startDistance > dp(10)) {
                    float factor = distance(event) / startDistance;
                    if (groupMode && pinchStart != null) {
                        scaleGroup(pinchStart, factor);
                    } else {
                        setScale(selected, startScaleX * factor, startScaleY * factor);
                    }
                } else if (event.getPointerCount() == 1 && groupMode) {
                    float dx = (event.getX() - lastX) / density;
                    float dy = (event.getY() - lastY) / density;
                    lastX = event.getX();
                    lastY = event.getY();
                    moveGroup(dx, dy);
                } else if (event.getPointerCount() == 1) {
                    float[] before = edits[selected].clone();
                    boolean wasClear = !hitsGuard(selected);
                    float dx = (event.getX() - lastX) / density;
                    float dy = (event.getY() - lastY) / density;
                    lastX = event.getX();
                    lastY = event.getY();
                    // A control stops at the top bar and the corner buttons: the move along
                    // the blocked direction is dropped, the other one kept.
                    float[][] tries = wasClear ? new float[][]{{dx, dy}, {dx, 0}, {0, dy}, {0, 0}} : new float[][]{{dx, dy}};
                    for (float[] step : tries) {
                        System.arraycopy(before, 0, edits[selected], 0, 4);
                        edits[selected][0] += step[0];
                        edits[selected][1] += step[1];
                        if (screenSelected()) {
                            snapScreen();
                            keepScreenInside();
                        }
                        listener.apply(selected, edits[selected][0], edits[selected][1], edits[selected][2], edits[selected][3]);
                        if (!wasClear || !hitsGuard(selected)) {
                            break;
                        }
                    }
                    applyEdit(selected);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                startDistance = 0;
                // Carry on dragging with the finger that stays.
                int stay = event.getActionIndex() == 0 ? 1 : 0;
                lastX = event.getX(stay);
                lastY = event.getY(stay);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                snapped = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    // Near the horizontal centre the screen sticks to it.
    private void snapScreen() {
        listener.apply(screenIndex, edits[screenIndex][0], edits[screenIndex][1], edits[screenIndex][2], edits[screenIndex][3]);
        RectF box = boxOf(controls.get(screenIndex));
        float off = getWidth() / 2f - box.centerX();
        snapped = Math.abs(off) < dp(8);
        if (snapped) {
            edits[screenIndex][0] += off / density;
        }
    }

    private static float distance(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }
}
