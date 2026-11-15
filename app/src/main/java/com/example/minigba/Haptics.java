package com.example.minigba;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

// Short buzz when a touch button goes down. Level 0 off, 1 weak, 2 normal, 3 strong.
final class Haptics {
    private static volatile int level;
    private static Vibrator vibrator;

    private Haptics() {
    }

    static void setLevel(Context context, int value) {
        level = value < 0 || value > 3 ? 0 : value;
        if (vibrator == null) {
            vibrator = (Vibrator) context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    static int level() {
        return level;
    }

    static void tap() {
        int current = level;
        Vibrator target = vibrator;
        if (current == 0 || target == null) {
            return;
        }
        try {
            int ms = current == 1 ? 10 : current == 2 ? 18 : 28;
            if (Build.VERSION.SDK_INT >= 26) {
                int amplitude = current == 1 ? 60 : current == 2 ? 140 : 255;
                target.vibrate(VibrationEffect.createOneShot(ms, target.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                target.vibrate(ms);
            }
        } catch (RuntimeException ignored) {
            // No vibrator or no permission: buttons still work.
        }
    }
}
