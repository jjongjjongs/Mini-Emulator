package com.example.minigba;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * The photo background editor: the picked photo full screen with the console's frame
 * drawn over it as a guide, moved with one finger and zoomed with two; portrait and
 * landscape tabs (the activity turns with the tab); dim, blur and button-opacity
 * sliders; applied per orientation into PhotoBackground's rendered files.
 */
public final class BgEditorActivity extends Activity {
    static final String EXTRA_MODE = "com.example.minigba.BG_MODE";
    static final String EXTRA_MODE_NAME = "com.example.minigba.BG_MODE_NAME";
    private static final int REQUEST_PICK = 1;

    private String mode;
    private String modeName;
    private String scope;
    private boolean landscape;
    private boolean pickedOnce;

    private EditView editView;
    private TextView portTab;
    private TextView landTab;
    private TextView scopeAll;
    private TextView scopeMode;
    private TextView dimLabel;
    private TextView blurLabel;
    private TextView buttonLabel;
    private SeekBar dimBar;
    private SeekBar blurBar;
    private SeekBar buttonBar;
    private TextView applyButton;
    private TextView hint;

    private int dim = PhotoBackground.DEFAULT_DIM;
    private int blur = PhotoBackground.DEFAULT_BLUR;
    private int buttonAlpha = PhotoBackground.DEFAULT_BUTTON_ALPHA;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mode = getIntent().getStringExtra(EXTRA_MODE);
        this.modeName = getIntent().getStringExtra(EXTRA_MODE_NAME);
        if (this.mode == null) this.mode = "all";
        if (this.modeName == null) this.modeName = this.mode;
        this.scope = PhotoBackground.scopeInForce(this, this.mode);
        this.landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(this.landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        buildUi();
        loadSettings();
        if (!loadSource()) {
            pickPhoto();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        this.editView = new EditView();
        root.addView(this.editView, new FrameLayout.LayoutParams(-1, -1));

        // Tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(0);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(rounded(Color.argb(150, 0, 0, 0), dp(14)));
        this.portTab = tab("세로 화면", () -> switchTab(false));
        this.landTab = tab("가로 화면", () -> switchTab(true));
        tabs.addView(this.portTab, new LinearLayout.LayoutParams(0, dp(36), 1));
        tabs.addView(this.landTab, new LinearLayout.LayoutParams(0, dp(36), 1));
        FrameLayout.LayoutParams tabParams = new FrameLayout.LayoutParams(dp(240), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tabParams.topMargin = dp(12);
        root.addView(tabs, tabParams);

        this.hint = new TextView(this);
        this.hint.setTextColor(Color.WHITE);
        this.hint.setTextSize(11.0f);
        this.hint.setTypeface(Typeface.DEFAULT_BOLD);
        this.hint.setPadding(dp(10), dp(5), dp(10), dp(5));
        this.hint.setBackground(rounded(Color.argb(140, 0, 0, 0), dp(999)));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hintParams.setMargins(dp(12), dp(58), 0, 0);
        root.addView(this.hint, hintParams);

        // Bottom sheet
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(1);
        sheet.setPadding(dp(16), dp(10), dp(16), dp(12));
        GradientDrawable sheetBackground = rounded(Color.WHITE, dp(22));
        float r = dp(22);
        sheetBackground.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sheetBackground);

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(this.landscape ? 0 : 1);
        this.dimLabel = new TextView(this);
        this.dimBar = slider(90, value -> {
            this.dim = value;
            this.dimLabel.setText(labelText("어둡게", value + "%"));
            this.editView.setDim(value);
        });
        this.blurLabel = new TextView(this);
        this.blurBar = slider(100, value -> {
            this.blur = value;
            this.blurLabel.setText(labelText("흐림", value == 0 ? "꺼짐" : value + "%"));
            this.editView.setBlur(value);
        });
        this.buttonLabel = new TextView(this);
        this.buttonBar = slider(90, value -> {
            this.buttonAlpha = value;
            this.buttonLabel.setText(labelText("버튼 투명도", value + "%"));
            this.editView.setButtonAlpha(value);
        });
        sliders.addView(sliderBlock(this.dimLabel, this.dimBar), sliderParams());
        sliders.addView(sliderBlock(this.blurLabel, this.blurBar), sliderParams());
        sliders.addView(sliderBlock(this.buttonLabel, this.buttonBar), sliderParams());
        sheet.addView(sliders, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout scopes = new LinearLayout(this);
        scopes.setOrientation(0);
        TextView scopeLabel = new TextView(this);
        scopeLabel.setText("적용 범위");
        scopeLabel.setTextSize(12.0f);
        scopeLabel.setTypeface(Typeface.DEFAULT_BOLD);
        scopeLabel.setTextColor(Color.rgb(75, 85, 99));
        scopeLabel.setGravity(Gravity.CENTER_VERTICAL);
        scopes.addView(scopeLabel, new LinearLayout.LayoutParams(-2, -1));
        this.scopeAll = chip("모든 게임기", () -> setScope("all"));
        this.scopeMode = chip(this.modeName + "만", () -> setScope(this.mode));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(34), 1);
        chipParams.setMargins(dp(8), 0, 0, 0);
        scopes.addView(this.scopeAll, chipParams);
        scopes.addView(this.scopeMode, chipParams);
        LinearLayout.LayoutParams scopesParams = new LinearLayout.LayoutParams(-1, -2);
        scopesParams.setMargins(0, dp(6), 0, 0);
        sheet.addView(scopes, scopesParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(0);
        TextView reset = button("초기화", Color.rgb(240, 241, 245), Color.rgb(75, 85, 99));
        reset.setOnClickListener(v -> this.editView.resetPlacement());
        TextView other = button("다른 사진", Color.rgb(240, 241, 245), Color.rgb(75, 85, 99));
        other.setOnClickListener(v -> pickPhoto());
        this.applyButton = button("세로 적용", Color.rgb(225, 29, 72), Color.WHITE);
        this.applyButton.setOnClickListener(v -> apply());
        TextView done = button("완료", Color.rgb(31, 41, 51), Color.WHITE);
        done.setOnClickListener(v -> finish());
        buttons.addView(reset, buttonParams(0.8f, 0));
        buttons.addView(other, buttonParams(1, dp(8)));
        buttons.addView(this.applyButton, buttonParams(1.3f, dp(8)));
        buttons.addView(done, buttonParams(0.8f, dp(8)));
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, dp(44));
        buttonsParams.setMargins(0, dp(10), 0, 0);
        sheet.addView(buttons, buttonsParams);

        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        refreshTabs();
    }

    private LinearLayout.LayoutParams sliderParams() {
        LinearLayout.LayoutParams params = this.landscape ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        if (this.landscape) params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams(float weight, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(left, 0, 0, 0);
        return params;
    }

    private LinearLayout sliderBlock(TextView label, SeekBar bar) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(1);
        label.setTextSize(12.0f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Color.rgb(75, 85, 99));
        block.addView(label, new LinearLayout.LayoutParams(-1, -2));
        block.addView(bar, new LinearLayout.LayoutParams(-1, dp(28)));
        return block;
    }

    private CharSequence labelText(String name, String value) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(name + "  ");
        int start = text.length();
        text.append(value);
        text.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(225, 29, 72)), start, text.length(), 0);
        return text;
    }

    private interface IntListener {
        void on(int value);
    }

    private SeekBar slider(int max, IntListener listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.on(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                editView.refreshBlur();
            }
        });
        return bar;
    }

    private TextView tab(String text, Runnable action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(17);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView chip(String text, Runnable action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12.0f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(17);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView button(String text, int background, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(color);
        view.setGravity(17);
        view.setBackground(rounded(background, dp(14)));
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void refreshTabs() {
        this.portTab.setBackground(rounded(this.landscape ? 0 : Color.rgb(225, 29, 72), dp(10)));
        this.portTab.setTextColor(this.landscape ? Color.rgb(207, 211, 220) : Color.WHITE);
        this.landTab.setBackground(rounded(this.landscape ? Color.rgb(225, 29, 72) : 0, dp(10)));
        this.landTab.setTextColor(this.landscape ? Color.WHITE : Color.rgb(207, 211, 220));
        this.applyButton.setText(this.landscape ? "가로 적용" : "세로 적용");
        boolean all = "all".equals(this.scope);
        this.scopeAll.setBackground(rounded(all ? Color.rgb(225, 29, 72) : Color.rgb(241, 242, 245), dp(10)));
        this.scopeAll.setTextColor(all ? Color.WHITE : Color.rgb(107, 114, 128));
        this.scopeMode.setBackground(rounded(all ? Color.rgb(241, 242, 245) : Color.rgb(225, 29, 72), dp(10)));
        this.scopeMode.setTextColor(all ? Color.rgb(107, 114, 128) : Color.WHITE);
    }

    private void switchTab(boolean toLandscape) {
        if (toLandscape == this.landscape) return;
        this.landscape = toLandscape;
        setRequestedOrientation(toLandscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        // The window turns; the views are rebuilt for the new orientation in onConfigurationChanged.
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        this.landscape = nowLandscape;
        buildUi();
        loadSettings();
        if (!loadSource()) {
            // No photo for this orientation yet: start from the other one's photo when there is one.
            File other = PhotoBackground.source(this, this.scope, !this.landscape);
            if (other.isFile()) {
                try {
                    copy(other, PhotoBackground.source(this, this.scope, this.landscape));
                    loadSource();
                } catch (IOException ignored) {
                    // Picking below.
                }
            }
            if (this.editView.source == null) {
                pickPhoto();
            }
        }
    }

    private void setScope(String scope) {
        if (scope.equals(this.scope)) return;
        this.scope = scope;
        loadSettings();
        if (!loadSource()) {
            // The new scope has no photo yet: keep the one on screen so it can be applied there.
            Bitmap keep = this.editView.source;
            if (keep != null) {
                try {
                    PhotoBackground.save(keep, PhotoBackground.source(this, scope, this.landscape));
                } catch (IOException ignored) {
                    // Applying saves again.
                }
            }
        }
        refreshTabs();
    }

    private void loadSettings() {
        this.dim = PhotoBackground.setting(this, this.scope, "dim", PhotoBackground.DEFAULT_DIM);
        this.blur = PhotoBackground.setting(this, this.scope, "blur", PhotoBackground.DEFAULT_BLUR);
        this.buttonAlpha = PhotoBackground.setting(this, this.scope, "btn", PhotoBackground.DEFAULT_BUTTON_ALPHA);
        this.dimBar.setProgress(this.dim);
        this.blurBar.setProgress(this.blur);
        this.buttonBar.setProgress(this.buttonAlpha);
        this.dimLabel.setText(labelText("어둡게", this.dim + "%"));
        this.blurLabel.setText(labelText("흐림", this.blur == 0 ? "꺼짐" : this.blur + "%"));
        this.buttonLabel.setText(labelText("버튼 투명도", this.buttonAlpha + "%"));
        this.editView.setDim(this.dim);
        this.editView.setBlur(this.blur);
        this.editView.setButtonAlpha(this.buttonAlpha);
        String key = PhotoBackground.orient(this.landscape);
        this.editView.setPlacement(PhotoBackground.setting(this, this.scope, key + ":zoom", 1.0f),
                PhotoBackground.setting(this, this.scope, key + ":ox", 0.0f), PhotoBackground.setting(this, this.scope, key + ":oy", 0.0f));
        refreshTabs();
    }

    private boolean loadSource() {
        File file = PhotoBackground.source(this, this.scope, this.landscape);
        Bitmap bitmap = PhotoBackground.load(file, 0, 0);
        this.editView.setSource(bitmap);
        return bitmap != null;
    }

    private void pickPhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "사진을 고르는 화면을 열 수 없어요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (this.editView.source == null && !this.pickedOnce) {
                finish();
            }
            return;
        }
        this.pickedOnce = true;
        Uri uri = data.getData();
        try {
            Bitmap bitmap = PhotoBackground.decodePicked(getContentResolver(), uri);
            PhotoBackground.save(bitmap, PhotoBackground.source(this, this.scope, this.landscape));
            File other = PhotoBackground.source(this, this.scope, !this.landscape);
            if (!other.isFile()) {
                // One photo serves both orientations until the other tab picks its own.
                PhotoBackground.save(bitmap, other);
            }
            this.editView.setSource(bitmap);
            this.editView.resetPlacement();
        } catch (IOException | OutOfMemoryError e) {
            Toast.makeText(this, "사진을 읽지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (this.editView.source == null) finish();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }

    private void apply() {
        if (this.editView.source == null || this.editView.getWidth() == 0) {
            Toast.makeText(this, "먼저 사진을 골라 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        final Bitmap source = this.editView.source;
        final int width = this.editView.getWidth();
        final int height = this.editView.getHeight();
        final float zoom = this.editView.zoom;
        final float ox = this.editView.offsetX;
        final float oy = this.editView.offsetY;
        final boolean orientation = this.landscape;
        final String scopeNow = this.scope;
        final int dimNow = this.dim;
        final int blurNow = this.blur;
        final int buttonNow = this.buttonAlpha;
        this.applyButton.setEnabled(false);
        this.applyButton.setText("적용 중…");
        new Thread(() -> {
            String error = null;
            try {
                Bitmap rendered = PhotoBackground.render(source, width, height, zoom, ox, oy, dimNow, blurNow);
                PhotoBackground.save(rendered, PhotoBackground.renderedFile(this, scopeNow, orientation));
                rendered.recycle();
                // The other orientation gets the same photo, covering the screen, when it has none yet.
                File otherRendered = PhotoBackground.renderedFile(this, scopeNow, !orientation);
                if (!otherRendered.isFile()) {
                    File otherSource = PhotoBackground.source(this, scopeNow, !orientation);
                    Bitmap otherBitmap = otherSource.isFile() ? PhotoBackground.load(otherSource, 0, 0) : source;
                    if (otherBitmap != null) {
                        Bitmap other = PhotoBackground.render(otherBitmap, height, width, 1.0f, 0, 0, dimNow, blurNow);
                        PhotoBackground.save(other, otherRendered);
                        other.recycle();
                        if (otherBitmap != source) otherBitmap.recycle();
                    }
                }
                SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                String key = "bg:" + scopeNow + ":";
                String orient = PhotoBackground.orient(orientation);
                edit.putFloat(key + orient + ":zoom", zoom).putFloat(key + orient + ":ox", ox).putFloat(key + orient + ":oy", oy);
                edit.putInt(key + "dim", dimNow).putInt(key + "blur", blurNow).putInt(key + "btn", buttonNow);
                edit.putInt("shell_style", ClearShellView.STYLE_PHOTO);
                edit.commit();
            } catch (IOException | OutOfMemoryError e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                this.applyButton.setEnabled(true);
                this.applyButton.setText(this.landscape ? "가로 적용" : "세로 적용");
                if (failure != null) {
                    Toast.makeText(this, "적용 실패: " + failure, Toast.LENGTH_LONG).show();
                    return;
                }
                setResult(RESULT_OK);
                Toast.makeText(this, (orientation ? "가로" : "세로") + " 배경을 적용했어요" + ("all".equals(scopeNow) ? " · 모든 게임기" : " · " + this.modeName), Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ---- The picture, the guide and the gestures ----
    private final class EditView extends View {
        Bitmap source;
        Bitmap blurred;
        float zoom = 1.0f;
        float offsetX;
        float offsetY;
        private int dim = PhotoBackground.DEFAULT_DIM;
        private int blur;
        private int buttonAlpha;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaler;
        private float lastX;
        private float lastY;
        private boolean dragging;

        EditView() {
            super(BgEditorActivity.this);
            this.guide.setStyle(Paint.Style.STROKE);
            this.guide.setStrokeWidth(dp(2));
            this.guide.setColor(Color.argb(150, 255, 255, 255));
            this.guide.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));
            this.fill.setColor(Color.argb(90, 27, 30, 37));
            this.text.setColor(Color.WHITE);
            this.text.setTextSize(dp(11));
            this.text.setFakeBoldText(true);
            this.scaler = new ScaleGestureDetector(BgEditorActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float before = zoom;
                    zoom = Math.max(0.5f, Math.min(4.0f, zoom * detector.getScaleFactor()));
                    // Zoom about the fingers: keep the point under them still.
                    float k = zoom / before;
                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    offsetX = (offsetX + cx - detector.getFocusX()) * k - (cx - detector.getFocusX());
                    offsetY = (offsetY + cy - detector.getFocusY()) * k - (cy - detector.getFocusY());
                    updateHint();
                    invalidate();
                    return true;
                }
            });
        }

        void setSource(Bitmap bitmap) {
            if (this.source != null && this.source != bitmap) this.source.recycle();
            this.source = bitmap;
            refreshBlur();
            invalidate();
        }

        void setPlacement(float zoom, float ox, float oy) {
            this.zoom = zoom;
            this.offsetX = ox;
            this.offsetY = oy;
            updateHint();
            invalidate();
        }

        void resetPlacement() {
            setPlacement(1.0f, 0, 0);
        }

        void setDim(int value) {
            this.dim = value;
            invalidate();
        }

        void setBlur(int value) {
            this.blur = value;
            invalidate();
        }

        void setButtonAlpha(int value) {
            this.buttonAlpha = value;
            invalidate();
        }

        /** A small blurred copy for the preview; the real render blurs at full size. */
        void refreshBlur() {
            if (this.blurred != null) {
                this.blurred.recycle();
                this.blurred = null;
            }
            if (this.source != null && this.blur > 0) {
                int w = Math.max(1, this.source.getWidth() / 4);
                int h = Math.max(1, this.source.getHeight() / 4);
                Bitmap small = Bitmap.createScaledBitmap(this.source, w, h, true);
                this.blurred = PhotoBackground.blur(small, this.blur);
                if (this.blurred != small) small.recycle();
            }
            invalidate();
        }

        private void updateHint() {
            hint.setText(Math.round(this.zoom * 100) + "% · 끌어서 옮기기 · 두 손가락으로 확대");
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateHint();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            this.scaler.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    this.lastX = event.getX();
                    this.lastY = event.getY();
                    this.dragging = true;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    this.dragging = false;
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() == 2) {
                        int remaining = event.getActionIndex() == 0 ? 1 : 0;
                        this.lastX = event.getX(remaining);
                        this.lastY = event.getY(remaining);
                        this.dragging = true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (this.dragging && event.getPointerCount() == 1) {
                        this.offsetX += event.getX() - this.lastX;
                        this.offsetY += event.getY() - this.lastY;
                        this.lastX = event.getX();
                        this.lastY = event.getY();
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    this.dragging = false;
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            canvas.drawColor(Color.BLACK);
            Bitmap shown = this.blur > 0 && this.blurred != null ? this.blurred : this.source;
            if (shown != null && this.source != null) {
                float k = this.source.getWidth() / (float) shown.getWidth();
                android.graphics.Matrix matrix = PhotoBackground.placement(this.source, w, h, this.zoom, this.offsetX, this.offsetY);
                matrix.preScale(k, k);
                canvas.drawBitmap(shown, matrix, this.paint);
            }
            if (this.dim > 0) {
                canvas.drawColor(Color.argb(Math.round(255 * this.dim / 100f), 0, 0, 0));
            }
            // Thirds grid
            Paint grid = new Paint();
            grid.setColor(Color.argb(60, 255, 255, 255));
            grid.setStrokeWidth(1);
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3f, 0, w * i / 3f, h, grid);
                canvas.drawLine(0, h * i / 3f, w, h * i / 3f, grid);
            }
            drawGuide(canvas, w, h);
        }

        // The console's frame as a guide: where the screen and the main buttons sit. The
        // buttons are filled as they will be, showing the transparency setting.
        private void drawGuide(Canvas canvas, int w, int h) {
            this.fill.setColor(Color.argb(Math.round(230 * (1 - this.buttonAlpha / 100f)), 27, 30, 37));
            float d = getResources().getDisplayMetrics().density;
            if (!landscape) {
                float screenTop = 74 * d;
                RectF screen = new RectF(18 * d, screenTop, w - 18 * d, screenTop + (w - 36 * d) * 0.75f);
                canvas.drawRoundRect(screen, 10 * d, 10 * d, this.guide);
                canvas.drawText("게임 화면 자리", screen.left + 12 * d, screen.top + 20 * d, this.text);
                float y = screen.bottom + 20 * d;
                roundButton(canvas, new RectF(18 * d, y, 122 * d, y + 34 * d), 17 * d, "L");
                roundButton(canvas, new RectF(w - 122 * d, y, w - 18 * d, y + 34 * d), 17 * d, "R");
                float padTop = y + 70 * d;
                roundButton(canvas, new RectF(22 * d, padTop, 162 * d, padTop + 140 * d), 30 * d, "");
                circleButton(canvas, w - 57 * d, padTop + 40 * d, 31 * d, "A");
                circleButton(canvas, w - 131 * d, padTop + 90 * d, 31 * d, "B");
                float bottom = h - 120 * d;
                roundButton(canvas, new RectF(w * 0.2f, bottom, w * 0.2f + 84 * d, bottom + 30 * d), 15 * d, "SELECT");
                roundButton(canvas, new RectF(w * 0.8f - 84 * d, bottom, w * 0.8f, bottom + 30 * d), 15 * d, "START");
            } else {
                float screenHeight = h - 140 * d;
                float screenWidth = screenHeight * 4f / 3f;
                float left = (w - screenWidth) / 2f;
                RectF screen = new RectF(left, 16 * d, left + screenWidth, 16 * d + screenHeight);
                canvas.drawRoundRect(screen, 10 * d, 10 * d, this.guide);
                canvas.drawText("게임 화면 자리", screen.left + 12 * d, screen.top + 20 * d, this.text);
                roundButton(canvas, new RectF(14 * d, 60 * d, 110 * d, 94 * d), 17 * d, "L");
                roundButton(canvas, new RectF(w - 110 * d, 60 * d, w - 14 * d, 94 * d), 17 * d, "R");
                roundButton(canvas, new RectF(30 * d, 120 * d, 160 * d, 250 * d), 28 * d, "");
                circleButton(canvas, w - 57 * d, 159 * d, 29 * d, "A");
                circleButton(canvas, w - 129 * d, 205 * d, 29 * d, "B");
            }
        }

        private void roundButton(Canvas canvas, RectF rect, float radius, String label) {
            canvas.drawRoundRect(rect, radius, radius, this.fill);
            canvas.drawRoundRect(rect, radius, radius, this.guide);
            if (!label.isEmpty()) {
                canvas.drawText(label, rect.centerX() - this.text.measureText(label) / 2, rect.centerY() + this.text.getTextSize() / 3, this.text);
            }
        }

        private void circleButton(Canvas canvas, float cx, float cy, float radius, String label) {
            canvas.drawCircle(cx, cy, radius, this.fill);
            canvas.drawCircle(cx, cy, radius, this.guide);
            canvas.drawText(label, cx - this.text.measureText(label) / 2, cy + this.text.getTextSize() / 3, this.text);
        }
    }
}
