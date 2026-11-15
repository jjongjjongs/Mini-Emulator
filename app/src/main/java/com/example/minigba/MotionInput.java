package com.example.minigba;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * 모션 (자이로): the phone's accelerometer and gyroscope handed to the core as libretro
 * sensors (Android's axes turned to the way the screen is shown, the accelerometer in g,
 * the gyroscope in rad/s). "가운데로" takes the phone's attitude at that moment as level:
 * readings are turned so that gravity then points straight down the screen's z axis.
 * The sensitivity scales the tilt (x, y of the turned gravity) and the turn rates.
 */
final class MotionInput implements SensorEventListener {
    private static final float G = SensorManager.GRAVITY_EARTH;

    private final SensorManager manager;
    private final WindowManager windows;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private boolean running;
    private float sensitivity = 1.0f;
    // Rotation (row-major 3x3) taking the attitude at "가운데로" to level.
    private final float[] level = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private boolean recenterPending = true;
    // Where gravity points at "가운데로": down the screen's z axis (lying flat: a Wii remote,
    // a GBA) or its y axis (held upright: the Wii U GamePad).
    private final float[] target = {0f, 0f, 1f};
    private final float[] accel = {0f, 0f, 1f};
    private final float[] gyro = {0f, 0f, 0f};

    MotionInput(Context context) {
        this.manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.accelerometer = this.manager == null ? null : this.manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.gyroscope = this.manager == null ? null : this.manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    boolean available() {
        return this.accelerometer != null;
    }

    void setUpright(boolean upright) {
        this.target[0] = 0f;
        this.target[1] = upright ? 1f : 0f;
        this.target[2] = upright ? 0f : 1f;
    }

    void setSensitivity(float value) {
        this.sensitivity = Math.max(0.25f, Math.min(3f, value));
    }

    void start() {
        if (this.running || this.manager == null || this.accelerometer == null) {
            return;
        }
        this.running = true;
        this.manager.registerListener(this, this.accelerometer, SensorManager.SENSOR_DELAY_GAME);
        if (this.gyroscope != null) {
            this.manager.registerListener(this, this.gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        this.manager.unregisterListener(this);
        // The core sees a phone at rest while the game is away.
        NativeGba.setMotion(0f, 0f, 1f, 0f, 0f, 0f);
    }

    /** The next accelerometer reading becomes level. */
    void recenter() {
        this.recenterPending = true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] v = screenAxes(event.values[0], event.values[1], event.values[2]);
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            v[0] /= G;
            v[1] /= G;
            v[2] /= G;
            if (this.recenterPending) {
                this.recenterPending = false;
                levelFrom(v);
            }
            float[] t = turn(v);
            // The tilt away from the resting pose is what the sensitivity scales.
            // (the axes across the resting gravity direction).
            for (int i = 0; i < 3; i++) {
                this.accel[i] = this.target[i] != 0f ? t[i] : t[i] * this.sensitivity;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            float[] t = turn(v);
            this.gyro[0] = t[0] * this.sensitivity;
            this.gyro[1] = t[1] * this.sensitivity;
            this.gyro[2] = t[2] * this.sensitivity;
        }
        NativeGba.setMotion(this.accel[0], this.accel[1], this.accel[2], this.gyro[0], this.gyro[1], this.gyro[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // Android reports in the device's natural (portrait) axes; games want the axes of the
    // screen as it is shown, x right and y up.
    private float[] screenAxes(float x, float y, float z) {
        int rotation = Surface.ROTATION_0;
        try {
            rotation = this.windows.getDefaultDisplay().getRotation();
        } catch (RuntimeException ignored) {
        }
        switch (rotation) {
            case Surface.ROTATION_90: return new float[]{-y, x, z};
            case Surface.ROTATION_180: return new float[]{-x, -y, z};
            case Surface.ROTATION_270: return new float[]{y, -x, z};
            default: return new float[]{x, y, z};
        }
    }

    private float[] turn(float[] v) {
        float[] m = this.level;
        return new float[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    // The rotation taking the direction of the gravity reading g to the target direction
    // (Rodrigues' formula: axis a x b, cos a.b).
    private void levelFrom(float[] g) {
        float n = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (n < 0.3f) {
            return;
        }
        float ax = g[0] / n, ay = g[1] / n, az = g[2] / n;
        float bx = this.target[0], by = this.target[1], bz = this.target[2];
        float kx = ay * bz - az * by, ky = az * bx - ax * bz, kz = ax * by - ay * bx;
        float s = (float) Math.sqrt(kx * kx + ky * ky + kz * kz);
        float c = ax * bx + ay * by + az * bz;
        float[] m = this.level;
        if (s < 1e-4f) {
            if (c >= 0) {
                m[0] = 1; m[1] = 0; m[2] = 0; m[3] = 0; m[4] = 1; m[5] = 0; m[6] = 0; m[7] = 0; m[8] = 1;
            } else {
                // Upside down: half a turn about an axis across the target.
                float px = Math.abs(bx) < 0.9f ? 1f : 0f, py = px == 1f ? 0f : 1f;
                m[0] = 2 * px * px - 1; m[1] = 2 * px * py;     m[2] = 0;
                m[3] = 2 * py * px;     m[4] = 2 * py * py - 1; m[5] = 0;
                m[6] = 0;               m[7] = 0;               m[8] = -1;
            }
            return;
        }
        kx /= s;
        ky /= s;
        kz /= s;
        float t = 1f - c;
        m[0] = c + kx * kx * t;       m[1] = kx * ky * t - kz * s;  m[2] = kx * kz * t + ky * s;
        m[3] = ky * kx * t + kz * s;  m[4] = c + ky * ky * t;       m[5] = ky * kz * t - kx * s;
        m[6] = kz * kx * t - ky * s;  m[7] = kz * ky * t + kx * s;  m[8] = c + kz * kz * t;
    }
}
