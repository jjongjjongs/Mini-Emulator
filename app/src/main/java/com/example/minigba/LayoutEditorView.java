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

// 버튼 배치 편집: covers the game; drag a control to move it, pinch (or −/＋) to resize it.
// Offsets are in dp, sizes a scale factor; the activity applies them to the real views.
final class LayoutEditorView extends FrameLayout {
    interface Listener {
        void apply(int index, float dxDp, float dyDp, float scale);

        void onSave(float[][] edits);

        void onCancel();
    }

    private static final float MIN_SCALE = 0.6f;
    private static final float MAX_SCALE = 1.8f;

    private final List<View> controls;
    private final float[][] edits;
    private final float[][] original;
    private final Listener listener;
    private final float density;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView sizeText;
    private final int[] here = new int[2];
    private final int[] there = new int[2];
    private int selected = -1;
    private float lastX;
    private float lastY;
    private float startDistance;
    private float startScale;

    LayoutEditorView(Context context, List<View> controls, float[][] edits, Listener listener) {
        super(context);
        this.controls = controls;
        this.edits = edits;
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

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(rounded(Color.argb(235, 17, 24, 39), dp(16)));
        TextView hint = new TextView(context);
        hint.setText("편집 중 · 끌어서 옮기기, 두 손가락으로 크기");
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
        row.addView(button("−", Color.rgb(55, 65, 81), view -> stepScale(-0.1f)), buttonParams(40));
        row.addView(button("＋", Color.rgb(55, 65, 81), view -> stepScale(0.1f)), buttonParams(40));
        row.addView(button("초기화", Color.rgb(75, 85, 99), view -> resetAll()), buttonParams(62));
        row.addView(button("취소", Color.rgb(75, 85, 99), view -> cancel()), buttonParams(52));
        row.addView(button("저장", Color.rgb(22, 185, 207), view -> listener.onSave(this.edits)), buttonParams(52));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-2, -2);
        rowParams.setMargins(0, dp(6), 0, 0);
        card.addView(row, rowParams);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardParams.setMargins(0, dp(8), 0, 0);
        addView(card, cardParams);
        // Touches on the card stay on the card.
        card.setClickable(true);
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
        button.setTextSize(13.0f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(color, dp(10)));
        button.setOnClickListener(onClick);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(36));
        params.setMargins(dp(6), 0, 0, 0);
        return params;
    }

    private void updateSizeText() {
        sizeText.setText(selected < 0 ? "버튼 선택" : "크기 " + Math.round(edits[selected][2] * 100) + "%");
    }

    private void stepScale(float step) {
        if (selected < 0) {
            return;
        }
        setScale(selected, Math.round((edits[selected][2] + step) * 10) / 10.0f);
    }

    private void setScale(int index, float scale) {
        edits[index][2] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        applyEdit(index);
    }

    private void applyEdit(int index) {
        listener.apply(index, edits[index][0], edits[index][1], edits[index][2]);
        updateSizeText();
        invalidate();
    }

    private void resetAll() {
        for (int k = 0; k < edits.length; k++) {
            edits[k][0] = 0;
            edits[k][1] = 0;
            edits[k][2] = 1;
            listener.apply(k, 0, 0, 1);
        }
        updateSizeText();
        invalidate();
    }

    private void cancel() {
        for (int k = 0; k < edits.length; k++) {
            System.arraycopy(original[k], 0, edits[k], 0, 3);
            listener.apply(k, edits[k][0], edits[k][1], edits[k][2]);
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
            // The smallest box under the finger: a button over a big stick wins.
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
            if (view.getVisibility() == VISIBLE && view.isAttachedToWindow()) {
                RectF box = boxOf(view);
                canvas.drawRoundRect(box, dp(8), dp(8), k == selected ? outline : frame);
            }
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
                updateSizeText();
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selected >= 0 && event.getPointerCount() == 2) {
                    startDistance = distance(event);
                    startScale = edits[selected][2];
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selected < 0) {
                    return true;
                }
                if (event.getPointerCount() >= 2 && startDistance > dp(10)) {
                    setScale(selected, startScale * distance(event) / startDistance);
                } else if (event.getPointerCount() == 1) {
                    edits[selected][0] += (event.getX() - lastX) / density;
                    edits[selected][1] += (event.getY() - lastY) / density;
                    lastX = event.getX();
                    lastY = event.getY();
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
            default:
                return true;
        }
    }

    private static float distance(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }
}
