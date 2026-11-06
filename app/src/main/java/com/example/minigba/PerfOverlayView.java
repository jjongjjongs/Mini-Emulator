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
// game window and draws the line along the bottom edge of the game picture (0.0.286): in a
// small box, or as bare text where the picture fills the window (full-screen and controller
// styles). Touches pass through. GameActivity feeds it frame counts once a second.
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
    // The last few readings (one a second): FPS and speed are over the oldest of them, about
    // 4 s back. Over 1 s they jumped about (17% then 137%) where an engine hands its sound or
    // its pictures over in uneven bursts (Dreamcast), though the game ran evenly.
    private static final int WINDOW = 5;
    private final double[] pastFrames = new double[WINDOW];
    private final double[] pastPictures = new double[WINDOW];
    private final double[] pastAudio = new double[WINDOW];
    private final long[] pastTime = new long[WINDOW];
    private int pastCount;
    private int pastNext;

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

    // stats: {frames run, pictures shown, the game's own frame rate, audio made, its sample
    // rate} from NativeGba.frameStats. Cores paced by their audio (Dreamcast, N64, PSP,
    // GameCube · Wii) run a varying stretch of the game per frame, so their speed is the
    // audio made per second against the sample rate (frames while the game is silent).
    void update(double[] stats) {
        long now = System.nanoTime();
        if (stats != null && stats.length >= 3) {
            double audio = stats.length >= 5 ? stats[3] : 0;
            double rate = stats.length >= 5 ? stats[4] : 0;
            int oldest = this.pastCount < WINDOW ? 0 : this.pastNext;
            if (this.pastCount > 0 && stats[0] < this.pastFrames[(this.pastNext + WINDOW - 1) % WINDOW]) {
                this.pastCount = 0;   // the engine's counts started again
                this.pastNext = 0;
                oldest = 0;
            }
            if (this.pastCount > 0 && now > this.pastTime[oldest]) {
                double seconds = (now - this.pastTime[oldest]) / 1e9;
                double fps = (stats[1] - this.pastPictures[oldest]) / seconds;
                double speed = (stats[0] - this.pastFrames[oldest]) / seconds / Math.max(1, stats[2]) * 100;
                if (rate > 0 && audio > this.pastAudio[oldest]) {
                    speed = (audio - this.pastAudio[oldest]) / seconds / rate * 100;
                }
                this.line1 = Math.round(fps) + " FPS · 속도 " + Math.round(speed) + "%";
                this.fpsColor = speed >= 95 ? Color.rgb(74, 222, 128) : speed >= 80 ? Color.rgb(251, 191, 36) : Color.rgb(248, 113, 113);
            }
            this.pastFrames[this.pastNext] = stats[0];
            this.pastPictures[this.pastNext] = stats[1];
            this.pastAudio[this.pastNext] = audio;
            this.pastTime[this.pastNext] = now;
            this.pastNext = (this.pastNext + 1) % WINDOW;
            this.pastCount = Math.min(this.pastCount + 1, WINDOW);
        }
        if (this.line1.isEmpty()) {
            this.line1 = "FPS 재는 중…";
        }
        this.line2 = temperature() + " · " + battery();
        invalidate();
    }

    // Restarts the FPS count (after a pause, so it doesn't count the paused time).
    void restart() {
        this.pastCount = 0;
        this.pastNext = 0;
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
        // Where the picture really is on screen: its parents may scale it (a stretched or
        // zoomed screen), which the view's own height doesn't show.
        android.graphics.Rect picture = new android.graphics.Rect();
        android.graphics.Rect own = new android.graphics.Rect();
        if (!this.target.getGlobalVisibleRect(picture) || !getGlobalVisibleRect(own)) {
            return;
        }
        float left = picture.left - own.left;
        float bottom = picture.bottom - own.top;
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
            // Along the bottom edge of the picture, bare text with a shadow.
            x = left + pad + 2 * this.density;
            y = bottom - 4 * this.density - (lineHeight - baseline);
        } else {
            // In a small box along the bottom edge of the picture.
            float width = text.measureText(head) + text.measureText(heat) + text.measureText(tail) + text.measureText(this.drain) + 2 * pad;
            float boxTop = bottom - 3 * this.density - (lineHeight + 2 * this.density);
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
