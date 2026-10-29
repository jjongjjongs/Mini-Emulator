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
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The photo background editor. Up to six photos lie on the screen as layers: the first
 * covers it, the others sit on top; the selected one is moved with one finger, zoomed and
 * turned with two. The controls (tabs, sliders, tools) sit in a panel where the game's
 * screen will be, so the whole case around it stays visible. Portrait and landscape are
 * edited and applied separately (the activity turns with the tab); the result is rendered
 * into PhotoBackground's files.
 */
public final class BgEditorActivity extends Activity {
    static final String EXTRA_MODE = "com.example.minigba.BG_MODE";
    static final String EXTRA_MODE_NAME = "com.example.minigba.BG_MODE_NAME";
    private static final int REQUEST_ADD = 1;
    private static final int REQUEST_REPLACE = 2;
    private static final int ACCENT = Color.rgb(225, 29, 72);

    private String mode;
    private String modeName;
    private String scope;
    private boolean landscape;
    private boolean pickedOnce;

    private FrameLayout root;
    private EditView editView;
    private FrameLayout panel;
    private LinearLayout thumbs;
    private TextView hint;
    private LinearLayout shapesRow;
    private TextView shapeButton;
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

    private final List<PhotoBackground.Layer> layers = new ArrayList<>();
    private int selected = -1;
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
        loadAll();
        if (this.layers.isEmpty()) {
            pickPhoto(REQUEST_ADD);
        }
    }

    // ---- UI ----

    private void buildUi() {
        this.root = new FrameLayout(this);
        this.root.setBackgroundColor(Color.BLACK);
        this.editView = new EditView();
        this.root.addView(this.editView, new FrameLayout.LayoutParams(-1, -1));

        this.panel = new FrameLayout(this);
        this.panel.setBackground(rounded(Color.argb(245, 255, 255, 255), dp(14)));
        this.panel.setClickable(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(1);
        column.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(0);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(rounded(Color.rgb(241, 242, 245), dp(10)));
        this.portTab = chip("세로", () -> switchTab(false));
        this.landTab = chip("가로", () -> switchTab(true));
        this.scopeAll = chip("모든 게임기", () -> setScope("all"));
        this.scopeMode = chip(this.modeName + "만", () -> setScope(this.mode));
        for (TextView tab : new TextView[]{this.portTab, this.landTab, this.scopeAll, this.scopeMode}) {
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(28), 1));
        }
        column.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(0);
        this.dimLabel = label();
        this.blurLabel = label();
        this.buttonLabel = label();
        this.blurLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        this.buttonLabel.setGravity(Gravity.END);
        labels.addView(this.dimLabel, new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(this.blurLabel, new LinearLayout.LayoutParams(0, -2, 1));
        labels.addView(this.buttonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(-1, -2);
        labelsParams.setMargins(0, dp(6), 0, 0);
        column.addView(labels, labelsParams);
        this.dimBar = slider(90, value -> {
            this.dim = value;
            this.dimLabel.setText(labelText("어둡게", value + "%"));
            this.editView.invalidate();
        });
        this.blurBar = slider(100, value -> {
            this.blur = value;
            this.blurLabel.setText(labelText("흐림", value == 0 ? "꺼짐" : value + "%"));
            this.editView.invalidate();
        });
        this.buttonBar = slider(90, value -> {
            this.buttonAlpha = value;
            this.buttonLabel.setText(labelText("버튼 투명도", value + "%"));
            this.editView.invalidate();
        });
        for (SeekBar bar : new SeekBar[]{this.dimBar, this.blurBar, this.buttonBar}) {
            column.addView(bar, new LinearLayout.LayoutParams(-1, dp(22)));
        }

        // The photos as thumbnails, and the selected one's readout, in one row.
        LinearLayout photosRow = new LinearLayout(this);
        photosRow.setOrientation(0);
        photosRow.setGravity(Gravity.CENTER_VERTICAL);
        this.thumbs = new LinearLayout(this);
        this.thumbs.setOrientation(0);
        this.thumbs.setPadding(dp(3), dp(3), dp(3), dp(3));
        this.thumbs.setBackground(rounded(Color.rgb(241, 242, 245), dp(8)));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(this.thumbs);
        photosRow.addView(scroll, new LinearLayout.LayoutParams(-2, -2));
        this.hint = new TextView(this);
        this.hint.setTextColor(Color.rgb(75, 85, 99));
        this.hint.setTextSize(10.5f);
        this.hint.setTypeface(Typeface.DEFAULT_BOLD);
        this.hint.setPadding(dp(8), 0, 0, 0);
        photosRow.addView(this.hint, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams photosParams = new LinearLayout.LayoutParams(-1, -2);
        photosParams.setMargins(0, dp(6), 0, 0);
        column.addView(photosRow, photosParams);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(0);
        tools.addView(button("＋ 사진 추가", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> {
            if (this.layers.size() >= PhotoBackground.MAX_LAYERS) {
                Toast.makeText(this, "사진은 " + PhotoBackground.MAX_LAYERS + "장까지예요.", Toast.LENGTH_SHORT).show();
                return;
            }
            pickPhoto(REQUEST_ADD);
        }), buttonParams(1, 0));
        tools.addView(button("↻ 90°", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> {
            PhotoBackground.Layer layer = selectedLayer();
            if (layer != null) {
                layer.rotation = (layer.rotation + 90) % 360;
                this.editView.invalidate();
                updateHint();
            }
        }), buttonParams(0.7f, dp(6)));
        this.shapeButton = button("테두리", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> {
            boolean show = this.shapesRow.getVisibility() != View.VISIBLE;
            this.shapesRow.setVisibility(show ? View.VISIBLE : View.GONE);
            this.shapeButton.setBackground(rounded(show ? ACCENT : Color.rgb(241, 242, 245), dp(10)));
            this.shapeButton.setTextColor(show ? Color.WHITE : Color.rgb(75, 85, 99));
        });
        tools.addView(this.shapeButton, buttonParams(0.8f, dp(6)));
        tools.addView(button("맨 앞", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> {
            PhotoBackground.Layer layer = selectedLayer();
            if (layer != null && this.selected > 0 && this.selected < this.layers.size() - 1) {
                this.layers.remove(this.selected);
                this.layers.add(layer);
                this.selected = this.layers.size() - 1;
                refreshThumbs();
                this.editView.invalidate();
            }
        }), buttonParams(1, dp(6)));
        tools.addView(button("지우기", Color.rgb(255, 241, 242), ACCENT, this::removeSelected), buttonParams(0.8f, dp(6)));
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(-1, dp(32));
        toolsParams.setMargins(0, dp(6), 0, 0);
        column.addView(tools, toolsParams);

        // The shapes, shown by the "테두리" button; the selected photo takes the one tapped.
        this.shapesRow = new LinearLayout(this);
        this.shapesRow.setOrientation(0);
        this.shapesRow.setVisibility(View.GONE);
        for (int i = 0; i < PhotoBackground.SHAPE_NAMES.length; i++) {
            final int shape = i;
            TextView chip = chip(PhotoBackground.SHAPE_NAMES[i], () -> {
                PhotoBackground.Layer layer = selectedLayer();
                if (layer != null) {
                    layer.shape = shape;
                    refreshShapes();
                    updateHint();
                    this.editView.invalidate();
                }
            });
            chip.setTextSize(10.0f);
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(26), 1);
            chipParams.setMargins(i == 0 ? 0 : dp(4), 0, 0, 0);
            this.shapesRow.addView(chip, chipParams);
        }
        LinearLayout.LayoutParams shapesParams = new LinearLayout.LayoutParams(-1, -2);
        shapesParams.setMargins(0, dp(6), 0, 0);
        column.addView(this.shapesRow, shapesParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(0);
        actions.addView(button("초기화", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> {
            PhotoBackground.Layer layer = selectedLayer();
            if (layer != null) {
                layer.zoom = 1;
                layer.offsetX = 0;
                layer.offsetY = 0;
                layer.rotation = 0;
                this.editView.invalidate();
                updateHint();
            }
        }), buttonParams(0.8f, 0));
        actions.addView(button("다른 사진", Color.rgb(241, 242, 245), Color.rgb(75, 85, 99), () -> pickPhoto(REQUEST_REPLACE)), buttonParams(1, dp(6)));
        this.applyButton = button("세로 적용", ACCENT, Color.WHITE, this::apply);
        actions.addView(this.applyButton, buttonParams(1.1f, dp(6)));
        actions.addView(button("완료", Color.rgb(31, 41, 51), Color.WHITE, this::finish), buttonParams(0.7f, dp(6)));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(34));
        actionsParams.setMargins(0, dp(6), 0, 0);
        column.addView(actions, actionsParams);
        this.panel.addView(column, new FrameLayout.LayoutParams(-1, -2));
        this.root.addView(this.panel, new FrameLayout.LayoutParams(-2, -2));
        this.root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placePanel(r - l, b - t));
        setContentView(this.root);
        refreshTabs();
    }

    /** The panel takes the screen's place in the guide (growing past it only if it must). */
    private void placePanel(int width, int height) {
        if (width <= 0 || height <= 0) return;
        RectF screen = this.editView.screenRect(width, height);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) this.panel.getLayoutParams();
        int left = Math.round(screen.left);
        int top = Math.round(screen.top);
        int w = Math.round(screen.width());
        if (params.leftMargin != left || params.topMargin != top || params.width != w) {
            params.leftMargin = left;
            params.topMargin = top;
            params.width = w;
            params.height = -2;
            this.panel.setMinimumHeight(Math.round(screen.height()));
            this.panel.post(() -> this.panel.setLayoutParams(params));
        }
    }

    private void refreshShapes() {
        PhotoBackground.Layer layer = selectedLayer();
        for (int i = 0; i < this.shapesRow.getChildCount(); i++) {
            TextView chip = (TextView) this.shapesRow.getChildAt(i);
            boolean on = layer != null && layer.shape == i;
            chip.setBackground(rounded(on ? ACCENT : Color.rgb(241, 242, 245), dp(8)));
            chip.setTextColor(on ? Color.WHITE : Color.rgb(107, 114, 128));
        }
    }

    private LinearLayout.LayoutParams buttonParams(float weight, int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight);
        params.setMargins(left, 0, 0, 0);
        return params;
    }

    private TextView label() {
        TextView view = new TextView(this);
        view.setTextSize(11.0f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.rgb(75, 85, 99));
        return view;
    }

    private CharSequence labelText(String name, String value) {
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(name + " ");
        int start = text.length();
        text.append(value);
        text.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), start, text.length(), 0);
        return text;
    }

    private interface IntListener {
        void on(int value);
    }

    private SeekBar slider(int max, IntListener listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setPadding(dp(6), 0, dp(6), 0);
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

    private TextView chip(String text, Runnable action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11.0f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(17);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView button(String text, int background, int color, Runnable action) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(color);
        view.setGravity(17);
        view.setBackground(rounded(background, dp(10)));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private void refreshTabs() {
        style(this.portTab, !this.landscape, ACCENT);
        style(this.landTab, this.landscape, ACCENT);
        boolean all = "all".equals(this.scope);
        style(this.scopeAll, all, Color.rgb(31, 41, 51));
        style(this.scopeMode, !all, Color.rgb(31, 41, 51));
        this.applyButton.setText(this.landscape ? "가로 적용" : "세로 적용");
    }

    private void style(TextView chip, boolean on, int color) {
        chip.setBackground(rounded(on ? color : 0, dp(8)));
        chip.setTextColor(on ? Color.WHITE : Color.rgb(107, 114, 128));
    }

    private void refreshThumbs() {
        this.thumbs.removeAllViews();
        for (int i = 0; i < this.layers.size(); i++) {
            final int index = i;
            PhotoBackground.Layer layer = this.layers.get(i);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (layer.bitmap != null) {
                int w = 88;
                int h = Math.max(1, Math.round(88f * layer.bitmap.getHeight() / layer.bitmap.getWidth()));
                image.setImageBitmap(Bitmap.createScaledBitmap(layer.bitmap, w, h, true));
            }
            GradientDrawable border = new GradientDrawable();
            border.setColor(0);
            border.setCornerRadius(dp(6));
            border.setStroke(dp(2), i == this.selected ? ACCENT : Color.TRANSPARENT);
            image.setBackground(border);
            image.setPadding(dp(2), dp(2), dp(2), dp(2));
            image.setOnClickListener(v -> {
                this.selected = index;
                refreshThumbs();
                this.editView.invalidate();
                updateHint();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(30));
            params.setMargins(i == 0 ? 0 : dp(3), 0, 0, 0);
            this.thumbs.addView(image, params);
        }
        refreshShapes();
        updateHint();
    }

    private void updateHint() {
        PhotoBackground.Layer layer = selectedLayer();
        if (layer == null) {
            this.hint.setText("사진을 골라 주세요");
            return;
        }
        String rotation = layer.rotation == 0 ? "" : " · 회전 " + Math.round(layer.rotation) + "°";
        String shape = layer.shape == PhotoBackground.SHAPE_NONE ? "" : " · " + PhotoBackground.SHAPE_NAMES[layer.shape];
        this.hint.setText("사진 " + (this.selected + 1) + " · " + Math.round(layer.zoom * 100) + "%" + rotation + shape + (this.selected == 0 ? " · 바탕" : ""));
    }

    private PhotoBackground.Layer selectedLayer() {
        return this.selected >= 0 && this.selected < this.layers.size() ? this.layers.get(this.selected) : null;
    }

    // ---- state ----

    private void switchTab(boolean toLandscape) {
        if (toLandscape == this.landscape) return;
        saveLayersQuietly();
        this.landscape = toLandscape;
        setRequestedOrientation(toLandscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        buildUi();
        loadAll();
        if (this.layers.isEmpty()) {
            // Nothing for this orientation yet: start from the other one's base photo.
            File other = PhotoBackground.source(this, this.scope, !this.landscape, 0);
            if (other.isFile()) {
                try {
                    PhotoBackground.copy(other, PhotoBackground.source(this, this.scope, this.landscape, 0));
                    MainActivity.sharedSettings(this).edit().putInt("bg:" + this.scope + ":" + PhotoBackground.orient(this.landscape) + ":count", 1).commit();
                    loadAll();
                } catch (IOException ignored) {
                    // Picking below.
                }
            }
            if (this.layers.isEmpty()) {
                pickPhoto(REQUEST_ADD);
            }
        }
    }

    private void setScope(String scope) {
        if (scope.equals(this.scope)) return;
        saveLayersQuietly();
        List<PhotoBackground.Layer> keep = new ArrayList<>(this.layers);
        this.scope = scope;
        loadAll();
        if (this.layers.isEmpty() && !keep.isEmpty()) {
            // The new scope has no photos yet: carry the ones on screen over to it.
            for (PhotoBackground.Layer layer : keep) {
                PhotoBackground.Layer copy = new PhotoBackground.Layer(null);
                copy.bitmap = layer.bitmap;
                copy.zoom = layer.zoom;
                copy.offsetX = layer.offsetX;
                copy.offsetY = layer.offsetY;
                copy.rotation = layer.rotation;
                copy.shape = layer.shape;
                this.layers.add(copy);
            }
            this.selected = 0;
            saveLayersQuietly();
            refreshThumbs();
            this.editView.invalidate();
        }
        refreshTabs();
    }

    private void loadAll() {
        this.dim = PhotoBackground.setting(this, this.scope, "dim", PhotoBackground.DEFAULT_DIM);
        this.blur = PhotoBackground.setting(this, this.scope, "blur", PhotoBackground.DEFAULT_BLUR);
        this.buttonAlpha = PhotoBackground.setting(this, this.scope, "btn", PhotoBackground.DEFAULT_BUTTON_ALPHA);
        this.dimBar.setProgress(this.dim);
        this.blurBar.setProgress(this.blur);
        this.buttonBar.setProgress(this.buttonAlpha);
        this.dimLabel.setText(labelText("어둡게", this.dim + "%"));
        this.blurLabel.setText(labelText("흐림", this.blur == 0 ? "꺼짐" : this.blur + "%"));
        this.buttonLabel.setText(labelText("버튼 투명도", this.buttonAlpha + "%"));
        this.layers.clear();
        this.layers.addAll(PhotoBackground.loadLayers(this, this.scope, this.landscape));
        this.selected = this.layers.isEmpty() ? -1 : 0;
        refreshThumbs();
        refreshTabs();
        this.editView.refreshBlur();
    }

    private void saveLayersQuietly() {
        try {
            PhotoBackground.saveLayers(this, this.scope, this.landscape, this.layers);
        } catch (IOException ignored) {
            // Applying reports failures.
        }
    }

    private void pickPhoto(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, request);
        } catch (Exception e) {
            Toast.makeText(this, "사진을 고르는 화면을 열 수 없어요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ADD && requestCode != REQUEST_REPLACE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (this.layers.isEmpty() && !this.pickedOnce) finish();
            return;
        }
        this.pickedOnce = true;
        Uri uri = data.getData();
        try {
            Bitmap bitmap = PhotoBackground.decodePicked(getContentResolver(), uri);
            PhotoBackground.Layer target = requestCode == REQUEST_REPLACE ? selectedLayer() : null;
            if (target == null) {
                target = new PhotoBackground.Layer(null);
                this.layers.add(target);
                this.selected = this.layers.size() - 1;
            } else if (target.bitmap != null) {
                target.bitmap.recycle();
            }
            target.bitmap = bitmap;
            target.faded = null;
            target.zoom = 1;
            target.offsetX = 0;
            target.offsetY = 0;
            target.rotation = 0;
            saveLayersQuietly();
            refreshThumbs();
            this.editView.refreshBlur();
        } catch (IOException | OutOfMemoryError e) {
            Toast.makeText(this, "사진을 읽지 못했어요: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (this.layers.isEmpty()) finish();
        }
    }

    private void removeSelected() {
        PhotoBackground.Layer layer = selectedLayer();
        if (layer == null) return;
        this.layers.remove(this.selected);
        if (layer.bitmap != null) layer.bitmap.recycle();
        this.selected = this.layers.isEmpty() ? -1 : Math.min(this.selected, this.layers.size() - 1);
        saveLayersQuietly();
        refreshThumbs();
        this.editView.refreshBlur();
        if (this.layers.isEmpty()) {
            pickPhoto(REQUEST_ADD);
        }
    }

    private void apply() {
        if (this.layers.isEmpty() || this.editView.getWidth() == 0) {
            Toast.makeText(this, "먼저 사진을 골라 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int width = this.editView.getWidth();
        final int height = this.editView.getHeight();
        final boolean orientation = this.landscape;
        final String scopeNow = this.scope;
        final int dimNow = this.dim;
        final int blurNow = this.blur;
        final int buttonNow = this.buttonAlpha;
        final List<PhotoBackground.Layer> snapshot = new ArrayList<>(this.layers);
        this.applyButton.setEnabled(false);
        this.applyButton.setText("적용 중…");
        new Thread(() -> {
            String error = null;
            try {
                PhotoBackground.saveLayers(this, scopeNow, orientation, snapshot);
                Bitmap rendered = PhotoBackground.render(snapshot, width, height, dimNow, blurNow);
                PhotoBackground.save(rendered, PhotoBackground.renderedFile(this, scopeNow, orientation));
                rendered.recycle();
                // The other orientation gets the base photo covering the screen when it has nothing yet.
                File otherRendered = PhotoBackground.renderedFile(this, scopeNow, !orientation);
                if (!otherRendered.isFile() && snapshot.get(0).bitmap != null) {
                    PhotoBackground.Layer base = new PhotoBackground.Layer(null);
                    base.bitmap = snapshot.get(0).bitmap;
                    List<PhotoBackground.Layer> one = new ArrayList<>();
                    one.add(base);
                    Bitmap other = PhotoBackground.render(one, height, width, dimNow, blurNow);
                    PhotoBackground.save(other, otherRendered);
                    other.recycle();
                    PhotoBackground.saveLayers(this, scopeNow, !orientation, one);
                }
                SharedPreferences.Editor edit = MainActivity.sharedSettings(this).edit();
                String key = "bg:" + scopeNow + ":";
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
        private Bitmap blurredBase;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Gesture state
        private float lastX;
        private float lastY;
        private boolean dragging;
        private float startDistance;
        private float startAngle;
        private float startZoom;
        private float startRotation;
        private float startMidX;
        private float startMidY;
        private float startOffsetX;
        private float startOffsetY;
        private boolean pinching;

        EditView() {
            super(BgEditorActivity.this);
            this.guide.setStyle(Paint.Style.STROKE);
            this.guide.setStrokeWidth(dp(2));
            this.guide.setColor(Color.argb(150, 255, 255, 255));
            this.guide.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));
            this.outline.setStyle(Paint.Style.STROKE);
            this.outline.setStrokeWidth(dp(2.5f));
            this.outline.setColor(ACCENT);
            this.text.setColor(Color.WHITE);
            this.text.setTextSize(dp(11));
            this.text.setFakeBoldText(true);
        }

        /** Where the game's screen will be: the panel goes there. */
        RectF screenRect(int w, int h) {
            float d = getResources().getDisplayMetrics().density;
            if (!landscape) {
                float top = 74 * d;
                return new RectF(18 * d, top, w - 18 * d, top + (w - 36 * d) * 0.75f);
            }
            float screenHeight = h - 40 * d;
            float screenWidth = Math.min(screenHeight * 4f / 3f, w - 2 * 180 * d);
            float left = (w - screenWidth) / 2f;
            return new RectF(left, 20 * d, left + screenWidth, 20 * d + screenHeight);
        }

        void refreshBlur() {
            if (this.blurredBase != null) {
                this.blurredBase.recycle();
                this.blurredBase = null;
            }
            invalidate();
        }

        private Matrix matrixFor(int index, int w, int h) {
            PhotoBackground.Layer layer = layers.get(index);
            return PhotoBackground.placement(layer.bitmap, w, h, index == 0, layer.zoom, layer.offsetX, layer.offsetY, layer.rotation);
        }

        private int layerAt(float x, float y) {
            int w = getWidth();
            int h = getHeight();
            for (int i = layers.size() - 1; i >= 0; i--) {
                PhotoBackground.Layer layer = layers.get(i);
                if (layer.bitmap == null) continue;
                Matrix inverse = new Matrix();
                if (!matrixFor(i, w, h).invert(inverse)) continue;
                float[] point = {x, y};
                inverse.mapPoints(point);
                if (point[0] >= 0 && point[1] >= 0 && point[0] <= layer.bitmap.getWidth() && point[1] <= layer.bitmap.getHeight()) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            PhotoBackground.Layer layer = selectedLayer();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int hit = layerAt(event.getX(), event.getY());
                    if (hit >= 0 && hit != selected) {
                        selected = hit;
                        refreshThumbs();
                        invalidate();
                    }
                    this.lastX = event.getX();
                    this.lastY = event.getY();
                    this.dragging = true;
                    this.pinching = false;
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2 && layer != null) {
                        this.dragging = false;
                        this.pinching = true;
                        float dx = event.getX(1) - event.getX(0);
                        float dy = event.getY(1) - event.getY(0);
                        this.startDistance = Math.max(1, (float) Math.hypot(dx, dy));
                        this.startAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
                        this.startZoom = layer.zoom;
                        this.startRotation = layer.rotation;
                        this.startMidX = (event.getX(0) + event.getX(1)) / 2f;
                        this.startMidY = (event.getY(0) + event.getY(1)) / 2f;
                        this.startOffsetX = layer.offsetX;
                        this.startOffsetY = layer.offsetY;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (layer == null) break;
                    if (this.pinching && event.getPointerCount() >= 2) {
                        float dx = event.getX(1) - event.getX(0);
                        float dy = event.getY(1) - event.getY(0);
                        float distance = Math.max(1, (float) Math.hypot(dx, dy));
                        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                        layer.zoom = Math.max(0.3f, Math.min(5.0f, this.startZoom * distance / this.startDistance));
                        layer.rotation = (this.startRotation + angle - this.startAngle) % 360;
                        float midX = (event.getX(0) + event.getX(1)) / 2f;
                        float midY = (event.getY(0) + event.getY(1)) / 2f;
                        layer.offsetX = this.startOffsetX + midX - this.startMidX;
                        layer.offsetY = this.startOffsetY + midY - this.startMidY;
                        updateHint();
                        invalidate();
                    } else if (this.dragging && event.getPointerCount() == 1) {
                        layer.offsetX += event.getX() - this.lastX;
                        layer.offsetY += event.getY() - this.lastY;
                        this.lastX = event.getX();
                        this.lastY = event.getY();
                        invalidate();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    this.pinching = false;
                    this.dragging = false;
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    this.dragging = false;
                    this.pinching = false;
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
            if (!layers.isEmpty() && layers.get(0).bitmap != null) {
                if (this.blurredBase == null) {
                    Bitmap base = layers.get(0).bitmap;
                    int bw = Math.max(1, base.getWidth() / 8);
                    int bh = Math.max(1, base.getHeight() / 8);
                    Bitmap small = Bitmap.createScaledBitmap(base, bw, bh, true);
                    this.blurredBase = PhotoBackground.blur(small, 80);
                    if (this.blurredBase != small) small.recycle();
                }
                float scale = Math.max(w / (float) this.blurredBase.getWidth(), h / (float) this.blurredBase.getHeight());
                Matrix matrix = new Matrix();
                matrix.postScale(scale, scale);
                matrix.postTranslate((w - this.blurredBase.getWidth() * scale) / 2f, (h - this.blurredBase.getHeight() * scale) / 2f);
                canvas.drawBitmap(this.blurredBase, matrix, this.paint);
                canvas.drawColor(Color.argb(110, 0, 0, 0));
            }
            for (int i = 0; i < layers.size(); i++) {
                PhotoBackground.Layer layer = layers.get(i);
                if (layer.bitmap == null) continue;
                PhotoBackground.drawLayer(canvas, layer, matrixFor(i, w, h), this.paint);
            }
            if (dim > 0) {
                canvas.drawColor(Color.argb(Math.round(255 * dim / 100f), 0, 0, 0));
            }
            if (blur > 0) {
                // The preview only hints at the blur; the real one is baked in on apply.
                canvas.drawColor(Color.argb(Math.round(60 * blur / 100f), 255, 255, 255));
            }
            // The selected photo's edge
            if (selected >= 0 && selected < layers.size() && layers.get(selected).bitmap != null) {
                Bitmap bitmap = layers.get(selected).bitmap;
                float[] corners = {0, 0, bitmap.getWidth(), 0, bitmap.getWidth(), bitmap.getHeight(), 0, bitmap.getHeight()};
                matrixFor(selected, w, h).mapPoints(corners);
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(corners[0], corners[1]);
                for (int i = 2; i < corners.length; i += 2) path.lineTo(corners[i], corners[i + 1]);
                path.close();
                canvas.drawPath(path, this.outline);
            }
            Paint grid = new Paint();
            grid.setColor(Color.argb(50, 255, 255, 255));
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3f, 0, w * i / 3f, h, grid);
                canvas.drawLine(0, h * i / 3f, w, h * i / 3f, grid);
            }
            drawGuide(canvas, w, h);
        }

        // The console's buttons as a guide, filled as they will be so the transparency shows.
        private void drawGuide(Canvas canvas, int w, int h) {
            this.fill.setColor(Color.argb(Math.round(230 * (1 - buttonAlpha / 100f)), 27, 30, 37));
            float d = getResources().getDisplayMetrics().density;
            RectF screen = screenRect(w, h);
            if (!landscape) {
                float y = screen.bottom + 20 * d;
                roundButton(canvas, new RectF(18 * d, y, 122 * d, y + 34 * d), 17 * d, "L");
                roundButton(canvas, new RectF(w - 122 * d, y, w - 18 * d, y + 34 * d), 17 * d, "R");
                float padTop = y + 70 * d;
                roundButton(canvas, new RectF(22 * d, padTop, 162 * d, padTop + 140 * d), 30 * d, "");
                circleButton(canvas, w - 57 * d, padTop + 40 * d, 31 * d, "A");
                circleButton(canvas, w - 131 * d, padTop + 90 * d, 31 * d, "B");
                float bottom = h - 60 * d;
                roundButton(canvas, new RectF(w * 0.2f, bottom, w * 0.2f + 84 * d, bottom + 30 * d), 15 * d, "SELECT");
                roundButton(canvas, new RectF(w * 0.8f - 84 * d, bottom, w * 0.8f, bottom + 30 * d), 15 * d, "START");
            } else {
                roundButton(canvas, new RectF(14 * d, 60 * d, 110 * d, 94 * d), 17 * d, "L");
                roundButton(canvas, new RectF(w - 110 * d, 60 * d, w - 14 * d, 94 * d), 17 * d, "R");
                roundButton(canvas, new RectF(30 * d, 120 * d, 160 * d, 250 * d), 28 * d, "");
                circleButton(canvas, w - 57 * d, 159 * d, 29 * d, "A");
                circleButton(canvas, w - 129 * d, 205 * d, 29 * d, "B");
                roundButton(canvas, new RectF(40 * d, h - 52 * d, 124 * d, h - 22 * d), 15 * d, "SELECT");
                roundButton(canvas, new RectF(w - 124 * d, h - 52 * d, w - 40 * d, h - 22 * d), 15 * d, "START");
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
