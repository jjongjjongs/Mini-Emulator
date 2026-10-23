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

// "성능 표시": FPS and game speed, temperature and battery, in a small box at the top left of
// the game screen. It covers its parent and draws only over the target view; touches pass
// through. GameActivity feeds it frame counts once a second.
final class PerfOverlayView extends View {
    private final View target;
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

    PerfOverlayView(Context context, View target) {
        super(context);
        this.target = target;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        box.setColor(Color.argb(158, 0, 0, 0));
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setTextSize(10.5f * this.density);
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
        float pad = 6 * this.density;
        float lineHeight = 14 * this.density;
        String second = this.drain.isEmpty() ? this.line2 : this.line2 + " ";
        float width = Math.max(text.measureText(this.line1), text.measureText(second) + text.measureText(this.drain)) + 2 * pad;
        float left = this.target.getLeft() + pad;
        float top = this.target.getTop() + pad;
        RectF rect = new RectF(left, top, left + width, top + 2 * lineHeight + pad);
        canvas.drawRoundRect(rect, 8 * this.density, 8 * this.density, box);
        float x = left + pad;
        float y = top + pad / 2 + lineHeight - 3 * this.density;
        text.setColor(this.fpsColor);
        canvas.drawText(this.line1, x, y, text);
        y += lineHeight;
        text.setColor(Color.rgb(229, 231, 235));
        canvas.drawText(second, x, y, text);
        if (!this.drain.isEmpty()) {
            text.setColor(Color.rgb(251, 191, 36));
            canvas.drawText(this.drain, x + text.measureText(second), y, text);
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
