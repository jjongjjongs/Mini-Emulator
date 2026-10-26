package com.example.minigba;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

// "게임패드 버튼 설정": a picture of the console's pad with, beside each button, the controller
// input that drives it. Tapping a button in the picture waits for a press on the controller
// and keeps that press for the button. Changes go to this game or to every game of the console.
final class PadMappingDialog {
    interface Listener {
        void onChanged();
    }

    private static final int ACCENT = Color.rgb(14, 165, 183);
    private static final int WARN = Color.rgb(245, 165, 36);
    private static final int BODY = Color.rgb(44, 48, 58);
    private static final int BODY_EDGE = Color.rgb(90, 96, 110);
    private static final int BUTTON = Color.rgb(27, 30, 37);
    private static final int MUTED = Color.rgb(154, 163, 178);

    private final Context context;
    private final SharedPreferences prefs;
    private final float density;
    private final List<PadMapping.Target> targets;
    private final int[][] defaults;
    private final String gameScope;
    private final String modeScope;
    private final String consoleName;
    private final String gameName;
    private final Listener listener;
    private final int sticks;
    private String descriptor;
    private String deviceName;
    private boolean thisGame = true;
    private PictureView picture;
    private TextView deviceView;
    private TextView hint;
    private final TextView[] scopes = new TextView[2];

    private PadMappingDialog(Context context, List<PadMapping.Target> targets, int[][] defaults, String gameScope, String modeScope,
                             String consoleName, String gameName, int sticks, InputDevice device, Listener listener) {
        this.context = context;
        this.sticks = sticks;
        this.prefs = MainActivity.sharedSettings(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.targets = targets;
        this.defaults = defaults;
        this.gameScope = gameScope;
        this.modeScope = modeScope;
        this.consoleName = consoleName;
        this.gameName = gameName;
        this.listener = listener;
        this.descriptor = PadMapping.descriptor(device);
        this.deviceName = device == null ? "" : device.getName();
    }

    static void show(Context context, List<PadMapping.Target> targets, int[][] defaults, String gameScope, String modeScope,
                     String consoleName, String gameName, int sticks, InputDevice device, Listener listener) {
        new PadMappingDialog(context, targets, defaults, gameScope, modeScope, consoleName, gameName, sticks, device, listener).open();
    }

    // The console (or game) table for the controller, as it is in force: the console's
    // changes with this game's laid over.
    static SparseIntArray customFor(SharedPreferences prefs, String gameScope, String modeScope, String descriptor) {
        SparseIntArray table = PadMapping.load(prefs, PadMapping.key(modeScope, descriptor));
        SparseIntArray game = PadMapping.load(prefs, PadMapping.key(gameScope, descriptor));
        for (int k = 0; k < game.size(); k++) {
            table.put(game.keyAt(k), game.valueAt(k));
        }
        return table;
    }

    static boolean hasCustom(SharedPreferences prefs, String gameScope, String modeScope, String descriptor) {
        return customFor(prefs, gameScope, modeScope, descriptor).size() > 0;
    }

    private int dp(float value) {
        return Math.round(value * this.density);
    }

    private String scopeKey() {
        return PadMapping.key(this.thisGame ? this.gameScope : this.modeScope, this.descriptor);
    }

    // What the picture shows for the chosen scope.
    private SparseIntArray shownCustom() {
        return this.thisGame ? customFor(this.prefs, this.gameScope, this.modeScope, this.descriptor)
                : PadMapping.load(this.prefs, PadMapping.key(this.modeScope, this.descriptor));
    }

    private void open() {
        final Dialog dialog = new Dialog(this.context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this.context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(22, 25, 32));
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout header = new LinearLayout(this.context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 24, Color.WHITE, true);
        back.setPadding(0, 0, dp(10), dp(2));
        back.setOnClickListener(v -> dialog.dismiss());
        header.addView(back);
        LinearLayout titles = new LinearLayout(this.context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("게임패드 버튼 설정", 16, Color.WHITE, true));
        titles.addView(text(this.consoleName + " · " + this.gameName, 12, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        this.deviceView = text("", 12, MUTED, false);
        this.deviceView.setGravity(Gravity.END);
        header.addView(this.deviceView, new LinearLayout.LayoutParams(dp(150), -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        this.picture = new PictureView(this.context);
        root.addView(this.picture, new LinearLayout.LayoutParams(-1, 0, 1));

        this.hint = text("", 12, MUTED, false);
        this.hint.setGravity(Gravity.CENTER);
        root.addView(this.hint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this.context);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout scopeRow = new LinearLayout(this.context);
        GradientDrawable scopeFrame = new GradientDrawable();
        scopeFrame.setCornerRadius(dp(12));
        scopeFrame.setColor(Color.rgb(36, 40, 50));
        scopeRow.setBackground(scopeFrame);
        scopeRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] scopeNames = {"이 게임만", "모든 " + this.consoleName + " 게임"};
        for (int k = 0; k < 2; k++) {
            final boolean forThisGame = k == 0;
            TextView option = text(scopeNames[k], 12.5f, Color.WHITE, true);
            option.setGravity(Gravity.CENTER);
            option.setPadding(dp(12), 0, dp(12), 0);
            option.setOnClickListener(v -> {
                this.thisGame = forThisGame;
                refresh();
            });
            this.scopes[k] = option;
            scopeRow.addView(option, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        bottom.addView(scopeRow, new LinearLayout.LayoutParams(-2, -2));
        bottom.addView(new View(this.context), new LinearLayout.LayoutParams(0, 1, 1));
        TextView reset = button("기본값으로", Color.rgb(240, 241, 245), Color.rgb(27, 31, 39));
        reset.setOnClickListener(v -> {
            PadMapping.save(this.prefs, scopeKey(), new SparseIntArray());
            this.listener.onChanged();
            refresh();
            Toast.makeText(this.context, (this.thisGame ? "이 게임의 바꾼 버튼을 지웠어요" : "모든 " + this.consoleName + " 게임의 바꾼 버튼을 지웠어요"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-2, dp(40));
        resetParams.setMargins(0, 0, dp(10), 0);
        bottom.addView(reset, resetParams);
        TextView done = button("완료", ACCENT, Color.WHITE);
        done.setOnClickListener(v -> dialog.dismiss());
        bottom.addView(done, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(-1, -2);
        bottomParams.setMargins(0, dp(8), 0, 0);
        root.addView(bottom, bottomParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.rgb(22, 25, 32)));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        refresh();
        dialog.show();
    }

    private void refresh() {
        for (int k = 0; k < 2; k++) {
            boolean on = (k == 0) == this.thisGame;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(10));
            background.setColor(on ? Color.rgb(22, 25, 32) : Color.TRANSPARENT);
            this.scopes[k].setBackground(background);
            this.scopes[k].setTextColor(on ? ACCENT : MUTED);
        }
        this.deviceView.setText(this.deviceName.isEmpty() ? "게임패드를 연결해 주세요" : "연결됨: " + this.deviceName);
        this.hint.setText(this.thisGame ? "바꿀 버튼을 그림에서 눌러요 · 바뀐 버튼은 파란색, 빈 버튼은 노란색"
                : "모든 " + this.consoleName + " 게임에 적용돼요 · 이 게임만의 설정이 있으면 그게 우선이에요");
        this.picture.setTable(shownCustom());
    }

    // Keeps a controller press for one target, then returns to the picture.
    private void capture(PadMapping.Target target) {
        SparseIntArray custom = shownCustom();
        int current = PadMapping.shownSource(this.defaults, custom, target.bit);
        final Dialog dialog = new Dialog(this.context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (isController(event) && event.getAction() == KeyEvent.ACTION_DOWN && PadMapping.isMappableKey(event.getKeyCode())) {
                    assign(target, event.getKeyCode(), event.getDevice());
                    dismiss();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    dismiss();
                    return true;
                }
                return isController(event) || super.dispatchKeyEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(MotionEvent event) {
                if (!isController(event) || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                    return super.dispatchGenericMotionEvent(event);
                }
                int[] axes = {MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
                        MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ};
                for (int axis : axes) {
                    float value = event.getAxisValue(axis);
                    if (Math.abs(value) > 0.7f) {
                        assign(target, PadMapping.axisSource(PadMapping.canonicalAxis(axis), value < 0), event.getDevice());
                        dismiss();
                        return true;
                    }
                }
                return true;
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this.context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(22));
        panel.setBackground(background);
        panel.addView(text(target.label + " 버튼을 어디에 둘까요?", 18, Color.rgb(27, 31, 39), true));
        TextView note = text("게임패드에서 원하는 버튼을 눌러 주세요. 지금은 " + PadMapping.sourceName(current) + (current == PadMapping.SOURCE_NONE ? "이에요." : "이(가) " + target.label + "을(를) 맡고 있어요."),
                13.5f, Color.rgb(85, 95, 110), false);
        note.setPadding(0, dp(6), 0, 0);
        panel.addView(note);
        TextView pulse = text(target.label, 28, ACCENT, true);
        pulse.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.rgb(232, 251, 253));
        ring.setStroke(dp(3), ACCENT, dp(8), dp(6));
        pulse.setBackground(ring);
        LinearLayout.LayoutParams pulseParams = new LinearLayout.LayoutParams(dp(84), dp(84));
        pulseParams.gravity = Gravity.CENTER_HORIZONTAL;
        pulseParams.setMargins(0, dp(14), 0, 0);
        panel.addView(pulse, pulseParams);
        TextView waiting = text("기다리는 중…", 24, ACCENT, true);
        waiting.setGravity(Gravity.CENTER);
        waiting.setPadding(0, dp(10), 0, 0);
        panel.addView(waiting);
        TextView sub = text("스틱 방향 · L2/R2 트리거도 눌러서 지정할 수 있어요", 12, Color.rgb(122, 130, 140), false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(2), 0, dp(16));
        panel.addView(sub);
        LinearLayout buttons = new LinearLayout(this.context);
        TextView clear = button("해제", Color.WHITE, Color.rgb(229, 72, 77));
        GradientDrawable clearBackground = new GradientDrawable();
        clearBackground.setColor(Color.WHITE);
        clearBackground.setCornerRadius(dp(14));
        clearBackground.setStroke(dp(1), Color.rgb(241, 213, 214));
        clear.setBackground(clearBackground);
        clear.setOnClickListener(v -> {
            assign(target, PadMapping.SOURCE_NONE, null);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        clearParams.setMargins(0, 0, dp(5), 0);
        buttons.addView(clear, clearParams);
        TextView cancel = button("취소", Color.rgb(240, 241, 245), Color.rgb(27, 31, 39));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelParams.setMargins(dp(5), 0, 0, 0);
        buttons.addView(cancel, cancelParams);
        panel.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(this.context.getResources().getDisplayMetrics().widthPixels - dp(40), dp(380)), -2);
        }
        dialog.show();
    }

    private static boolean isController(android.view.InputEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private void assign(PadMapping.Target target, int source, InputDevice device) {
        if (device != null) {
            String descriptor = PadMapping.descriptor(device);
            if (!descriptor.equals(this.descriptor)) {
                this.descriptor = descriptor;
                this.deviceName = device.getName();
            }
        }
        SparseIntArray table = PadMapping.load(this.prefs, scopeKey());
        table.put(target.bit, source);
        PadMapping.save(this.prefs, scopeKey(), table);
        this.listener.onChanged();
        refresh();
        // The target that had this source by default is empty now; say so.
        String freed = "";
        SparseIntArray custom = shownCustom();
        for (PadMapping.Target other : this.targets) {
            if (other != target && PadMapping.shownSource(this.defaults, custom, other.bit) == PadMapping.SOURCE_NONE
                    && custom.indexOfKey(other.bit) < 0) {
                freed = other.label;
                break;
            }
        }
        String message = source == PadMapping.SOURCE_NONE ? target.label + " 버튼을 비웠어요" : target.label + " → " + PadMapping.sourceName(source) + " 로 바꿨어요";
        if (!freed.isEmpty()) {
            message += " · " + freed + "는 비었으니 눌러서 지정해 주세요";
        }
        Toast.makeText(this.context, message, Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this.context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return view;
    }

    private TextView button(String value, int background, int color) {
        TextView view = text(value, 14, color, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(14));
        view.setBackground(drawable);
        return view;
    }

    // ---- The picture ----

    private final class PictureView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();
        private SparseIntArray custom = new SparseIntArray();
        // Where each target sits, in picture pixels, and how big its tap area is.
        private final float[] cx;
        private final float[] cy;
        private final float[] radius;
        private final int[] kind;

        PictureView(Context context) {
            super(context);
            int n = PadMappingDialog.this.targets.size();
            this.cx = new float[n];
            this.cy = new float[n];
            this.radius = new float[n];
            this.kind = new int[n];
            this.stroke.setStyle(Paint.Style.STROKE);
            this.label.setTypeface(Typeface.DEFAULT_BOLD);
            this.label.setTextAlign(Paint.Align.CENTER);
        }

        void setTable(SparseIntArray table) {
            this.custom = table;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            // The pad body is twice as wide as tall, fitted into the view with room for labels.
            float bodyWidth = Math.min(width - dp(24), (height - dp(44)) * 2f);
            float bodyHeight = bodyWidth / 2f;
            float left = (width - bodyWidth) / 2f;
            float top = (height - bodyHeight) / 2f + dp(8);
            float u = bodyHeight;
            layout(left, top, bodyWidth, bodyHeight);

            // Body: a rounded slab with two grips.
            this.body.reset();
            RectF slab = new RectF(left + 0.1f * bodyWidth, top + 0.12f * u, left + 0.9f * bodyWidth, top + 0.72f * u);
            this.body.addRoundRect(slab, 0.18f * u, 0.18f * u, Path.Direction.CW);
            this.body.addRoundRect(new RectF(left + 0.1f * bodyWidth, top + 0.3f * u, left + 0.36f * bodyWidth, top + u), 0.14f * u, 0.14f * u, Path.Direction.CW);
            this.body.addRoundRect(new RectF(left + 0.64f * bodyWidth, top + 0.3f * u, left + 0.9f * bodyWidth, top + u), 0.14f * u, 0.14f * u, Path.Direction.CW);
            this.fill.setColor(BODY);
            canvas.drawPath(this.body, this.fill);
            this.stroke.setColor(BODY_EDGE);
            this.stroke.setStrokeWidth(dp(1.5f));
            canvas.drawPath(this.body, this.stroke);

            // Sticks under the face and D-pad, when the console has them.
            if (PadMappingDialog.this.sticks >= 1) {
                drawStick(canvas, left + 0.37f * bodyWidth, top + 0.74f * u, 0.12f * u);
            }
            if (PadMappingDialog.this.sticks >= 2) {
                drawStick(canvas, left + 0.63f * bodyWidth, top + 0.74f * u, 0.12f * u);
            }

            this.label.setTextSize(dp(10.5f));
            for (int k = 0; k < this.cx.length; k++) {
                PadMapping.Target target = PadMappingDialog.this.targets.get(k);
                int source = PadMapping.shownSource(PadMappingDialog.this.defaults, this.custom, target.bit);
                boolean changed = this.custom.indexOfKey(target.bit) >= 0;
                boolean empty = source == PadMapping.SOURCE_NONE;
                int edge = empty ? WARN : changed ? ACCENT : BODY_EDGE;
                float x = this.cx[k];
                float y = this.cy[k];
                float r = this.radius[k];
                this.fill.setColor(BUTTON);
                this.stroke.setColor(edge);
                this.stroke.setStrokeWidth(dp(changed || empty ? 2.5f : 1.2f));
                switch (this.kind[k]) {
                    case 1: // shoulder pill
                        canvas.drawRoundRect(new RectF(x - r * 1.6f, y - r * 0.55f, x + r * 1.6f, y + r * 0.55f), r * 0.4f, r * 0.4f, this.fill);
                        canvas.drawRoundRect(new RectF(x - r * 1.6f, y - r * 0.55f, x + r * 1.6f, y + r * 0.55f), r * 0.4f, r * 0.4f, this.stroke);
                        break;
                    case 2: // middle pill
                        canvas.drawRoundRect(new RectF(x - r * 1.3f, y - r * 0.5f, x + r * 1.3f, y + r * 0.5f), r * 0.4f, r * 0.4f, this.fill);
                        canvas.drawRoundRect(new RectF(x - r * 1.3f, y - r * 0.5f, x + r * 1.3f, y + r * 0.5f), r * 0.4f, r * 0.4f, this.stroke);
                        break;
                    case 3: // D-pad arm
                        canvas.drawRoundRect(new RectF(x - r * 0.75f, y - r * 0.75f, x + r * 0.75f, y + r * 0.75f), r * 0.2f, r * 0.2f, this.fill);
                        canvas.drawRoundRect(new RectF(x - r * 0.75f, y - r * 0.75f, x + r * 0.75f, y + r * 0.75f), r * 0.2f, r * 0.2f, this.stroke);
                        break;
                    case 4: // stick press: ring on the stick
                        canvas.drawCircle(x, y, r, this.stroke);
                        break;
                    default:
                        canvas.drawCircle(x, y, r, this.fill);
                        canvas.drawCircle(x, y, r, this.stroke);
                }
                // The console's own label inside, the controller input in a chip below.
                this.label.setColor(Color.rgb(229, 231, 235));
                this.label.setTextSize(Math.max(dp(9), Math.min(dp(13), r * 0.75f)));
                if (this.kind[k] != 4) {
                    canvas.drawText(target.label, x, y + this.label.getTextSize() * 0.36f, this.label);
                }
                String chip = (this.kind[k] == 4 ? target.label + " · " : "") + PadMapping.sourceName(source);
                this.label.setTextSize(dp(10));
                float chipWidth = this.label.measureText(chip) + dp(12);
                float chipTop = y + (this.kind[k] == 1 || this.kind[k] == 2 ? r * 0.7f : r * 1.05f) + dp(2);
                RectF chipRect = new RectF(x - chipWidth / 2, chipTop, x + chipWidth / 2, chipTop + dp(16));
                this.fill.setColor(empty ? WARN : changed ? ACCENT : Color.rgb(16, 18, 24));
                canvas.drawRoundRect(chipRect, dp(8), dp(8), this.fill);
                this.label.setColor(empty || changed ? Color.WHITE : MUTED);
                canvas.drawText(chip, x, chipTop + dp(11.5f), this.label);
            }
        }

        private void drawStick(Canvas canvas, float x, float y, float r) {
            this.fill.setColor(BUTTON);
            canvas.drawCircle(x, y, r, this.fill);
            this.stroke.setColor(BODY_EDGE);
            this.stroke.setStrokeWidth(dp(1.2f));
            canvas.drawCircle(x, y, r, this.stroke);
            this.fill.setColor(Color.rgb(60, 65, 78));
            canvas.drawCircle(x, y, r * 0.62f, this.fill);
        }

        // Places every target: shoulders along the top, D-pad left, face right, middle pair in
        // the centre, stick presses on the sticks.
        private void layout(float left, float top, float width, float u) {
            List<PadMapping.Target> targets = PadMappingDialog.this.targets;
            int face = 0;
            int shoulderLeft = 0;
            int shoulderRight = 0;
            int middle = 0;
            int c = 0;
            for (PadMapping.Target target : targets) {
                if (target.group == PadMapping.GROUP_FACE) face++;
                else if (target.group == PadMapping.GROUP_SHOULDER_LEFT) shoulderLeft++;
                else if (target.group == PadMapping.GROUP_SHOULDER_RIGHT) shoulderRight++;
                else if (target.group == PadMapping.GROUP_MIDDLE) middle++;
                else if (target.group == PadMapping.GROUP_C) c++;
            }
            float faceX = left + (c > 0 ? 0.72f : 0.78f) * width;
            float faceY = top + 0.42f * u;
            float small = 0.085f * u;
            int faceIndex = 0;
            int leftIndex = 0;
            int rightIndex = 0;
            int middleIndex = 0;
            int cIndex = 0;
            for (int k = 0; k < targets.size(); k++) {
                PadMapping.Target target = targets.get(k);
                this.kind[k] = 0;
                switch (target.group) {
                    case PadMapping.GROUP_FACE: {
                        float gap = 0.16f * u;
                        if (face <= 2) {
                            // Right-upper, then left-lower, like A/B on the GBA.
                            this.cx[k] = faceX + (faceIndex == 0 ? gap * 0.55f : -gap * 0.55f);
                            this.cy[k] = faceY + (faceIndex == 0 ? -gap * 0.4f : gap * 0.4f);
                            this.radius[k] = 0.1f * u;
                        } else if (face <= 4) {
                            int[][] diamond = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                            this.cx[k] = faceX + diamond[faceIndex][0] * gap;
                            this.cy[k] = faceY + diamond[faceIndex][1] * gap;
                            this.radius[k] = small;
                        } else {
                            int column = faceIndex % 3;
                            boolean topRow = faceIndex >= 3;
                            this.cx[k] = faceX + (column - 1) * 0.17f * u;
                            this.cy[k] = faceY + (topRow ? -0.1f : 0.1f) * u - column * 0.03f * u;
                            this.radius[k] = 0.072f * u;
                        }
                        faceIndex++;
                        break;
                    }
                    case PadMapping.GROUP_C: {
                        int[][] diamond = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                        float gap = 0.1f * u;
                        this.cx[k] = left + 0.9f * width + diamond[cIndex][0] * gap;
                        this.cy[k] = top + 0.3f * u + diamond[cIndex][1] * gap;
                        this.radius[k] = 0.05f * u;
                        cIndex++;
                        break;
                    }
                    case PadMapping.GROUP_SHOULDER_LEFT:
                        this.kind[k] = 1;
                        this.cx[k] = left + 0.2f * width;
                        this.cy[k] = top + (0.12f - leftIndex * 0.12f) * u;
                        this.radius[k] = 0.07f * u;
                        leftIndex++;
                        break;
                    case PadMapping.GROUP_SHOULDER_RIGHT:
                        this.kind[k] = 1;
                        this.cx[k] = left + 0.8f * width;
                        this.cy[k] = top + (0.12f - rightIndex * 0.12f) * u;
                        this.radius[k] = 0.07f * u;
                        rightIndex++;
                        break;
                    case PadMapping.GROUP_MIDDLE:
                        this.kind[k] = 2;
                        this.cx[k] = left + (middle <= 1 ? 0.5f : middleIndex == 0 ? 0.43f : 0.57f) * width;
                        this.cy[k] = top + 0.4f * u;
                        this.radius[k] = 0.065f * u;
                        middleIndex++;
                        break;
                    case PadMapping.GROUP_DPAD: {
                        this.kind[k] = 3;
                        float dx = target.bit == PadMapping.DPAD_LEFT ? -1 : target.bit == PadMapping.DPAD_RIGHT ? 1 : 0;
                        float dy = target.bit == PadMapping.DPAD_UP ? -1 : target.bit == PadMapping.DPAD_DOWN ? 1 : 0;
                        this.cx[k] = left + 0.22f * width + dx * 0.15f * u;
                        this.cy[k] = top + 0.42f * u + dy * 0.15f * u;
                        this.radius[k] = 0.08f * u;
                        break;
                    }
                    case PadMapping.GROUP_STICK_LEFT:
                        this.kind[k] = 4;
                        this.cx[k] = left + 0.37f * width;
                        this.cy[k] = top + 0.74f * u;
                        this.radius[k] = 0.12f * u;
                        break;
                    case PadMapping.GROUP_STICK_RIGHT:
                        this.kind[k] = 4;
                        this.cx[k] = left + 0.63f * width;
                        this.cy[k] = top + 0.74f * u;
                        this.radius[k] = 0.12f * u;
                        break;
                    default:
                        break;
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            int best = -1;
            float bestDistance = Float.MAX_VALUE;
            for (int k = 0; k < this.cx.length; k++) {
                float dx = event.getX() - this.cx[k];
                float dy = event.getY() - this.cy[k];
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float reach = Math.max(this.radius[k] * 1.5f, dp(22));
                if (distance <= reach && distance < bestDistance) {
                    best = k;
                    bestDistance = distance;
                }
            }
            if (best >= 0) {
                performClick();
                capture(PadMappingDialog.this.targets.get(best));
            }
            return true;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
