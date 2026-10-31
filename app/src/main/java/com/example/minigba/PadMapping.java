package com.example.minigba;

import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.List;

// "게임패드 버튼 설정": which physical controller input drives each button of the console pad.
// A target is one console button (its libretro bit); a source is one controller input, a key
// code or an axis direction. The saved table holds only the targets the player changed; the
// effective table is the console's defaults with those changes laid over.
final class PadMapping {
    static final int GROUP_FACE = 0;
    static final int GROUP_SHOULDER_LEFT = 1;
    static final int GROUP_SHOULDER_RIGHT = 2;
    static final int GROUP_MIDDLE = 3;
    static final int GROUP_DPAD = 4;
    static final int GROUP_STICK_LEFT = 5;
    static final int GROUP_STICK_RIGHT = 6;
    static final int GROUP_C = 7;

    static final int DPAD_RIGHT = 16;
    static final int DPAD_LEFT = 32;
    static final int DPAD_UP = 64;
    static final int DPAD_DOWN = 128;
    static final int DPAD_BITS = 0xF0;

    static final class Target {
        final int bit;
        final String label;
        final int group;

        Target(int bit, String label, int group) {
            this.bit = bit;
            this.label = label;
            this.group = group;
        }
    }

    static final int SOURCE_NONE = 0;
    private static final int AXIS_BASE = 1000;

    static int axisSource(int axis, boolean negative) {
        return AXIS_BASE + axis * 2 + (negative ? 1 : 0);
    }

    static boolean isAxisSource(int source) {
        return source >= AXIS_BASE;
    }

    static int axisOf(int source) {
        return (source - AXIS_BASE) / 2;
    }

    static boolean axisNegative(int source) {
        return (source - AXIS_BASE) % 2 == 1;
    }

    // Trigger axes come in pairs (LTRIGGER/BRAKE, RTRIGGER/GAS) that pads use interchangeably.
    static int canonicalAxis(int axis) {
        if (axis == MotionEvent.AXIS_BRAKE) return MotionEvent.AXIS_LTRIGGER;
        if (axis == MotionEvent.AXIS_GAS) return MotionEvent.AXIS_RTRIGGER;
        return axis;
    }

    static String sourceName(int source) {
        if (source == SOURCE_NONE) {
            return "비어 있음";
        }
        if (isAxisSource(source)) {
            boolean negative = axisNegative(source);
            switch (axisOf(source)) {
                case MotionEvent.AXIS_LTRIGGER: return "LT";
                case MotionEvent.AXIS_RTRIGGER: return "RT";
                case MotionEvent.AXIS_X: return negative ? "L스틱 ◀" : "L스틱 ▶";
                case MotionEvent.AXIS_Y: return negative ? "L스틱 ▲" : "L스틱 ▼";
                case MotionEvent.AXIS_Z: return negative ? "R스틱 ◀" : "R스틱 ▶";
                case MotionEvent.AXIS_RZ: return negative ? "R스틱 ▲" : "R스틱 ▼";
                case MotionEvent.AXIS_HAT_X: return negative ? "십자 ◀" : "십자 ▶";
                case MotionEvent.AXIS_HAT_Y: return negative ? "십자 ▲" : "십자 ▼";
                default: return "축 " + axisOf(source) + (negative ? "−" : "+");
            }
        }
        switch (source) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_C: return "C";
            case KeyEvent.KEYCODE_BUTTON_Z: return "Z";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L스틱 누름";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R스틱 누름";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "HOME";
            case KeyEvent.KEYCODE_DPAD_UP: return "십자 ▲";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "십자 ▼";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "십자 ◀";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "십자 ▶";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "십자 가운데";
            default:
                if (source >= KeyEvent.KEYCODE_BUTTON_1 && source <= KeyEvent.KEYCODE_BUTTON_16) {
                    return "버튼 " + (source - KeyEvent.KEYCODE_BUTTON_1 + 1);
                }
                String name = KeyEvent.keyCodeToString(source);
                return name.startsWith("KEYCODE_") ? name.substring(8) : name;
        }
    }

    // Controller keys a mapping may use. Everything else (volume, back…) stays with the system.
    static boolean isMappableKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            return false;
        }
        return KeyEvent.isGamepadButton(keyCode) || (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER);
    }

    // ---- Saved tables: "bit=source;bit=source" per console (or game) and controller ----

    static String key(String scope, String descriptor) {
        return "padmap:" + scope + ":" + descriptor;
    }

    static SparseIntArray load(SharedPreferences prefs, String key) {
        SparseIntArray table = new SparseIntArray();
        String text = prefs.getString(key, "");
        if (text == null || text.isEmpty()) {
            return table;
        }
        for (String entry : text.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                table.put(Integer.parseInt(entry.substring(0, eq)), Integer.parseInt(entry.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return table;
    }

    static void save(SharedPreferences prefs, String key, SparseIntArray table) {
        if (table.size() == 0) {
            prefs.edit().remove(key).apply();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < table.size(); k++) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(table.keyAt(k)).append('=').append(table.valueAt(k));
        }
        prefs.edit().putString(key, text.toString()).apply();
    }

    static String descriptor(InputDevice device) {
        if (device == null) {
            return "";
        }
        String descriptor = device.getDescriptor();
        return descriptor == null || descriptor.isEmpty() ? String.valueOf(device.getName()) : descriptor;
    }

    // The first connected controller, for the settings row and the picture before any key is pressed.
    static InputDevice firstController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return device;
            }
        }
        return null;
    }

    // ---- Effective table ----

    // defaults: {source, bit} pairs the console uses when nothing is changed. A changed target
    // drops its default sources; a source given to a changed target is taken away from the
    // targets that had it by default (one press, one button) unless they were changed too.
    static SparseIntArray effective(int[][] defaults, SparseIntArray custom) {
        SparseIntArray result = new SparseIntArray();
        int changedBits = 0;
        for (int k = 0; k < custom.size(); k++) {
            changedBits |= custom.keyAt(k);
        }
        for (int[] pair : defaults) {
            int source = pair[0];
            int bit = pair[1];
            if ((bit & changedBits) != 0 || custom.indexOfValue(source) >= 0) {
                continue;
            }
            result.put(source, result.get(source) | bit);
        }
        for (int k = 0; k < custom.size(); k++) {
            int source = custom.valueAt(k);
            if (source != SOURCE_NONE) {
                result.put(source, result.get(source) | custom.keyAt(k));
            }
        }
        return result;
    }

    // The source shown beside a target: its change, else its first default key (axes second).
    static int shownSource(int[][] defaults, SparseIntArray custom, int bit) {
        int index = custom.indexOfKey(bit);
        if (index >= 0) {
            return custom.valueAt(index);
        }
        int axis = SOURCE_NONE;
        for (int[] pair : defaults) {
            if (pair[1] != bit || custom.indexOfValue(pair[0]) >= 0) {
                continue;
            }
            if (!isAxisSource(pair[0])) {
                return pair[0];
            }
            if (axis == SOURCE_NONE) {
                axis = pair[0];
            }
        }
        return axis;
    }

    // Sources an axis motion event is currently pressing, from the effective table.
    static List<Integer> axisSources(SparseIntArray effective) {
        List<Integer> sources = new ArrayList<>();
        for (int k = 0; k < effective.size(); k++) {
            if (isAxisSource(effective.keyAt(k))) {
                sources.add(effective.keyAt(k));
            }
        }
        return sources;
    }
}
