package com.example.minigba;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.view.View;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

// "성능 표시": FPS and game speed, temperature and battery, in one line. It covers the whole
// game window and draws the line on the case just under the game picture, so nothing of the
// game is hidden; where the picture fills the window (full-screen and controller styles) it
// draws the bare text along the top edge instead. Touches pass through. GameActivity feeds
// it frame counts once a second.
final class PerfOverlayView extends View {
    // The system's thermal status (GameActivity): 0 보통, 1 약간, 2 뜨거움, 3 심각; -1 unknown.
    static volatile int thermalLevel = -1;
    private static final String[] THERMAL_NAMES = {"보통", "약간", "뜨거움", "심각"};
    private static final int[] THERMAL_COLORS = {Color.rgb(74, 222, 128), Color.rgb(250, 204, 21), Color.rgb(251, 146, 60), Color.rgb(248, 113, 113)};

    private final View target;
    private final boolean flat;
    private final int[] mine = new int[2];
    private final int[] theirs = new int[2];
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final File[] cpuZones;
    private String line1 = "";
    private String line2 = "";
    private int fpsColor = Color.rgb(74, 222, 128);
    private String drain = "";
    private double lastFrames = -1;
    private double lastPictures;
    private long lastTime;

    PerfOverlayView(Context context, View target, boolean flat) {
        super(context);
        this.target = target;
        this.flat = flat;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        box.setColor(Color.argb(158, 0, 0, 0));
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextSize(9.5f * this.density);
        if (flat) {
            text.setShadowLayer(2 * this.density, 0, this.density, Color.argb(220, 0, 0, 0));
        }
        this.cpuZones = findCpuZones();
        target.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> invalidate());
    }

    // stats: {frames run, pictures shown, the game's own frame rate} from NativeGba.frameStats.
    void update(double[] stats) {
        long now = System.nanoTime();
        if (stats != null && stats.length == 3) {
            if (this.lastFrames >= 0 && now > this.lastTime && stats[0] >= this.lastFrames) {
                double seconds = (now - this.lastTime) / 1e9;
                double fps = (stats[1] - this.lastPictures) / seconds;
                double speed = (stats[0] - this.lastFrames) / seconds / Math.max(1, stats[2]) * 100;
                this.line1 = Math.round(fps) + " FPS · 속도 " + Math.round(speed) + "%";
                this.fpsColor = speed >= 95 ? Color.rgb(74, 222, 128) : speed >= 80 ? Color.rgb(251, 191, 36) : Color.rgb(248, 113, 113);
            }
            this.lastFrames = stats[0];
            this.lastPictures = stats[1];
            this.lastTime = now;
        }
        if (this.line1.isEmpty()) {
            this.line1 = "FPS 재는 중…";
        }
        this.line2 = temperature() + " · " + battery();
        invalidate();
    }

    // Restarts the FPS count (after a pause, so it doesn't count the paused time).
    void restart() {
        this.lastFrames = -1;
    }

    private String temperature() {
        int best = Integer.MIN_VALUE;
        for (File zone : this.cpuZones) {
            int milli = readInt(new File(zone, "temp"));
            if (milli > best) {
                best = milli;
            }
        }
        // Some phones (Samsung) keep the real sensors private and answer with a fixed junk
        // value instead; only a plausible reading is shown.
        float celsius = best == Integer.MIN_VALUE ? -1 : best >= 1000 ? best / 1000f : best;
        if (celsius >= 10 && celsius <= 95) {
            return "CPU " + Math.round(celsius) + "°C";
        }
        // Many phones keep CPU sensors private; the battery's is always there.
        Intent battery = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int tenths = battery == null ? Integer.MIN_VALUE : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return tenths == Integer.MIN_VALUE ? "온도 ?" : "배터리 " + Math.round(tenths / 10f) + "°C";
    }

    private String battery() {
        BatteryManager manager = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
        if (manager == null) {
            return "배터리 ?";
        }
        int percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        String result = "배터리 " + percent + "%";
        this.drain = "";
        boolean charging = android.os.Build.VERSION.SDK_INT >= 23 && manager.isCharging();
        long current = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        long charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (!charging && percent > 0 && current != Long.MIN_VALUE && current != 0 && charge > 0 && charge != Long.MIN_VALUE) {
            // Microamps against microamp-hours of a full battery; some phones give milliamps.
            double amps = Math.abs((double) current);
            if (amps < 20000) {
                amps *= 1000;
            }
            double full = charge / (percent / 100.0);
            double perHour = amps / full * 100;
            if (perHour > 0.5 && perHour < 100) {
                this.drain = "−" + Math.round(perHour) + "%/시간";
            }
        }
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (this.target.getVisibility() != VISIBLE || this.target.getWidth() <= 0 || this.line1.isEmpty()) {
            return;
        }
        getLocationInWindow(this.mine);
        this.target.getLocationInWindow(this.theirs);
        float left = this.theirs[0] - this.mine[0];
        float top = this.theirs[1] - this.mine[1];
        float bottom = top + this.target.getHeight();
        float pad = 5 * this.density;
        String tail = this.drain.isEmpty() ? this.line2 : this.line2 + " ";
        String head = this.line1 + "   ";
        int level = thermalLevel;
        String heat = level >= 0 && level <= 3 ? "● " + THERMAL_NAMES[level] + " " : "";
        float baseline = text.getTextSize() * 0.95f;
        float lineHeight = text.getTextSize() + 4 * this.density;
        float x;
        float y;
        if (this.flat) {
            // Along the top edge of the picture, bare text with a shadow.
            x = left + pad + 2 * this.density;
            y = top + 2 * this.density + baseline;
        } else {
            // On the case just under the picture.
            float width = text.measureText(head) + text.measureText(heat) + text.measureText(tail) + text.measureText(this.drain) + 2 * pad;
            float boxTop = bottom + 3 * this.density;
            float boxLeft = Math.max(2 * this.density, left + 2 * this.density);
            RectF rect = new RectF(boxLeft, boxTop, boxLeft + width, boxTop + lineHeight + 2 * this.density);
            canvas.drawRoundRect(rect, 6 * this.density, 6 * this.density, box);
            x = boxLeft + pad;
            y = boxTop + this.density + baseline;
        }
        text.setColor(this.fpsColor);
        canvas.drawText(head, x, y, text);
        x += text.measureText(head);
        if (!heat.isEmpty()) {
            text.setColor(THERMAL_COLORS[level]);
            canvas.drawText(heat, x, y, text);
            x += text.measureText(heat);
        }
        text.setColor(Color.rgb(229, 231, 235));
        canvas.drawText(tail, x, y, text);
        if (!this.drain.isEmpty()) {
            text.setColor(Color.rgb(251, 191, 36));
            canvas.drawText(this.drain, x + text.measureText(tail), y, text);
        }
    }

    private static File[] findCpuZones() {
        java.util.ArrayList<File> zones = new java.util.ArrayList<>();
        File[] all = new File("/sys/class/thermal").listFiles();
        if (all != null) {
            for (File zone : all) {
                if (!zone.getName().startsWith("thermal_zone")) {
                    continue;
                }
                String type = readLine(new File(zone, "type")).toLowerCase(java.util.Locale.ROOT);
                if ((type.startsWith("cpu") || type.contains("cpuss") || type.contains("cpu-")) && readInt(new File(zone, "temp")) > 0) {
                    zones.add(zone);
                }
            }
        }
        return zones.toArray(new File[0]);
    }

    private static String readLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static int readInt(File file) {
        try {
            return Integer.parseInt(readLine(file));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
