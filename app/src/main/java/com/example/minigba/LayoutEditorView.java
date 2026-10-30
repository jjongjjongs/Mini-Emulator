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
        } else {
            sizeText.setText("크기 " + Math.round(edits[selected][2] * 100) + "%");
        }
    }

    private void stepScale(float step, boolean x, boolean y) {
        if (selected < 0) {
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
        edits[index][2] = Math.max(min, Math.min(max, scaleX));
        edits[index][3] = Math.max(min, Math.min(max, scaleY));
        if (index == screenIndex) {
            keepScreenInside();
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
        new android.app.AlertDialog.Builder(getContext()).setTitle("초기화")
                .setItems(new String[]{"게임 화면만 원래 자리로", "버튼까지 모두 원래대로"}, (d, which) -> resetAll(which == 1, true))
                .setNegativeButton("취소", null).show();
    }

    private void resetAll(boolean buttons, boolean screen) {
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
                canvas.drawRoundRect(box, dp(8), dp(8), k == selected ? outline : frame);
            }
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
                lastX = event.getX();
                lastY = event.getY();
                snapped = false;
                refreshScreenRow();
                updateSizeText();
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selected >= 0 && event.getPointerCount() == 2) {
                    startDistance = distance(event);
                    startScaleX = edits[selected][2];
                    startScaleY = edits[selected][3];
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selected < 0) {
                    return true;
                }
                if (event.getPointerCount() >= 2 && startDistance > dp(10)) {
                    float factor = distance(event) / startDistance;
                    setScale(selected, startScaleX * factor, startScaleY * factor);
                } else if (event.getPointerCount() == 1) {
                    edits[selected][0] += (event.getX() - lastX) / density;
                    edits[selected][1] += (event.getY() - lastY) / density;
                    lastX = event.getX();
                    lastY = event.getY();
                    if (screenSelected()) {
                        snapScreen();
                        keepScreenInside();
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
