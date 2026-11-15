package com.example.minigba;

import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.InputDevice;
import java.util.ArrayList;
import java.util.List;

// "플레이어 · 패드": which connected controller plays as 1P-4P. The touch buttons are always
// 1P; the first controller joins them there and the next ones become 2P, 3P, 4P in the order
// they were connected. The order is remembered by controller, so the same pad takes the same
// place next time. Past the console's number of players, the extra pads play as 1P too.
final class PlayerPads {
    static final int MAX = 4;
    private static final String KEY = "player_pads";

    private final SharedPreferences prefs;
    // Player (0-3) for each connected controller, by device id.
    private final SparseIntArray slots = new SparseIntArray();
    private int maxPlayers = 1;

    PlayerPads(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // Connected game controllers, oldest first (device ids grow as devices are connected).
    static List<InputDevice> controllers() {
        List<InputDevice> list = new ArrayList<>();
        int[] ids = InputDevice.getDeviceIds();
        java.util.Arrays.sort(ids);
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                list.add(device);
            }
        }
        return list;
    }

    int maxPlayers() {
        return this.maxPlayers;
    }

    // The console's number of players changed (another game, the 멀티탭): pads past it go back
    // to 1P and are placed again.
    void setMaxPlayers(int max) {
        int clamped = Math.max(1, Math.min(MAX, max));
        if (clamped == this.maxPlayers) {
            return;
        }
        this.maxPlayers = clamped;
        for (int k = this.slots.size() - 1; k >= 0; k--) {
            if (this.slots.valueAt(k) >= clamped) {
                this.slots.removeAt(k);
            }
        }
    }

    int playerOf(InputDevice device) {
        return device == null ? 0 : this.slots.get(device.getId(), 0);
    }

    int playerOf(int deviceId) {
        return this.slots.get(deviceId, 0);
    }

    // Highest player with a pad, plus one; 1 with no pads.
    int players() {
        int top = 0;
        for (int k = 0; k < this.slots.size(); k++) {
            top = Math.max(top, this.slots.valueAt(k));
        }
        return top + 1;
    }

    // Follows the connected controllers. Returns the players whose pad went away (2P-4P) and,
    // through joined, the pads that just took a place as {device id, player}.
    List<Integer> refresh(List<int[]> joined) {
        List<InputDevice> now = controllers();
        List<Integer> lost = new ArrayList<>();
        for (int k = this.slots.size() - 1; k >= 0; k--) {
            int id = this.slots.keyAt(k);
            boolean present = false;
            for (InputDevice device : now) {
                if (device.getId() == id) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                if (this.slots.valueAt(k) > 0) {
                    lost.add(this.slots.valueAt(k));
                }
                this.slots.removeAt(k);
            }
        }
        List<String> saved = savedOrder();
        for (InputDevice device : now) {
            if (this.slots.indexOfKey(device.getId()) >= 0) {
                continue;
            }
            int player = -1;
            int remembered = saved.indexOf(PadMapping.descriptor(device));
            if (remembered >= 0 && remembered < this.maxPlayers && isFree(remembered)) {
                player = remembered;
            }
            for (int p = 0; player < 0 && p < this.maxPlayers; p++) {
                if (isFree(p)) {
                    player = p;
                }
            }
            if (player < 0) {
                player = 0;
            }
            this.slots.put(device.getId(), player);
            if (joined != null) {
                joined.add(new int[]{device.getId(), player});
            }
        }
        save();
        return lost;
    }

    private boolean isFree(int player) {
        for (int k = 0; k < this.slots.size(); k++) {
            if (this.slots.valueAt(k) == player) {
                return false;
            }
        }
        return true;
    }

    // "순서 다시 정하기": the pads in the order their buttons were pressed are 1P, 2P, …;
    // the others follow in connection order, then 1P.
    void setOrder(List<Integer> deviceIds) {
        this.slots.clear();
        int next = 0;
        for (int id : deviceIds) {
            this.slots.put(id, next < this.maxPlayers ? next : 0);
            next++;
        }
        for (InputDevice device : controllers()) {
            if (this.slots.indexOfKey(device.getId()) < 0) {
                this.slots.put(device.getId(), next < this.maxPlayers ? next : 0);
                next++;
            }
        }
        save();
    }

    // "바꾸기": puts a pad in a player's place; the pad there (if any) takes its old place.
    void move(int deviceId, int player) {
        int old = this.slots.get(deviceId, 0);
        for (int k = 0; k < this.slots.size(); k++) {
            if (this.slots.valueAt(k) == player && this.slots.keyAt(k) != deviceId) {
                this.slots.setValueAt(k, old);
            }
        }
        this.slots.put(deviceId, player);
        save();
    }

    // Device ids playing as a player.
    List<Integer> devicesOf(int player) {
        List<Integer> ids = new ArrayList<>();
        for (int k = 0; k < this.slots.size(); k++) {
            if (this.slots.valueAt(k) == player) {
                ids.add(this.slots.keyAt(k));
            }
        }
        return ids;
    }

    private List<String> savedOrder() {
        List<String> order = new ArrayList<>();
        String value = this.prefs.getString(KEY, "");
        if (!value.isEmpty()) {
            for (String part : value.split("\n", -1)) {
                order.add(part);
            }
        }
        return order;
    }

    // Remembers each place's pad (the first one there), keeping places of pads not connected now.
    private void save() {
        List<String> order = savedOrder();
        while (order.size() < MAX) {
            order.add("");
        }
        for (int p = 0; p < this.maxPlayers; p++) {
            List<Integer> ids = devicesOf(p);
            if (ids.isEmpty()) {
                continue;
            }
            String descriptor = PadMapping.descriptor(InputDevice.getDevice(ids.get(0)));
            if (descriptor.isEmpty()) {
                continue;
            }
            for (int q = 0; q < order.size(); q++) {
                if (q != p && descriptor.equals(order.get(q))) {
                    order.set(q, "");
                }
            }
            order.set(p, descriptor);
        }
        this.prefs.edit().putString(KEY, android.text.TextUtils.join("\n", order.subList(0, MAX))).apply();
    }
}
