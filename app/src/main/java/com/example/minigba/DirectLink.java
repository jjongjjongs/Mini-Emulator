package com.example.minigba;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Wi-Fi Direct for "와이파이 멀티" and "DS 통신": phones link straight to each other with no
// router. The host makes a group (it is the group owner, at 192.168.49.1) and advertises a
// "Mini Emulator" service naming its game; guests look for that service, connect, and get the
// host's address. Everything else is the same as on a shared Wi-Fi.
final class DirectLink {
    static final String OWNER_ADDRESS = "192.168.49.1";
    private static final String SERVICE = "MiniEmulator";
    private static final String TYPE = "_miniemu._udp";
    static final int PERMISSION_REQUEST = 1061;

    static final class Room {
        final WifiP2pDevice device;
        final String name;
        final String game;

        Room(WifiP2pDevice device, String name, String game) {
            this.device = device;
            this.name = name;
            this.game = game;
        }
    }

    interface Rooms {
        void onRooms(List<Room> rooms);
    }

    interface Connected {
        void onConnected(String hostAddress);

        void onFailed(String why);
    }

    private final Context context;
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private final Map<String, Room> found = new HashMap<>();
    private Rooms rooms;
    private Connected connected;
    private int clients;
    private boolean hosting;

    DirectLink(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
    }

    boolean available() {
        return this.manager != null && this.context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    // The permission finding nearby phones needs: nearby devices from Android 13, location before.
    static String permission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.NEARBY_WIFI_DEVICES" : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    static boolean permitted(Context context) {
        return context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED;
    }

    static void ask(Activity activity) {
        activity.requestPermissions(new String[]{permission()}, PERMISSION_REQUEST);
    }

    private boolean open() {
        if (!available()) {
            return false;
        }
        if (this.channel == null) {
            this.channel = this.manager.initialize(this.context, Looper.getMainLooper(), null);
        }
        if (this.receiver == null) {
            this.receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    String action = intent.getAction();
                    if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                        try {
                            DirectLink.this.manager.requestConnectionInfo(DirectLink.this.channel, DirectLink.this::onConnection);
                            DirectLink.this.manager.requestGroupInfo(DirectLink.this.channel, DirectLink.this::onGroup);
                        } catch (SecurityException ignored) {
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            this.context.registerReceiver(this.receiver, filter);
        }
        return this.channel != null;
    }

    private void onConnection(WifiP2pInfo info) {
        if (info == null || !info.groupFormed) {
            return;
        }
        Connected waiting = this.connected;
        if (waiting != null && !info.isGroupOwner && info.groupOwnerAddress != null) {
            this.connected = null;
            waiting.onConnected(info.groupOwnerAddress.getHostAddress());
        }
    }

    private void onGroup(WifiP2pGroup group) {
        this.clients = group == null ? 0 : group.getClientList().size();
    }

    int clientCount() {
        return this.clients;
    }

    boolean hosting() {
        return this.hosting;
    }

    // Makes this phone the group owner and advertises the room. game: what guests must run.
    void host(String game, String name) {
        if (!open() || !permitted(this.context)) {
            return;
        }
        this.hosting = true;
        try {
            Map<String, String> record = new HashMap<>();
            record.put("game", game);
            record.put("name", name);
            final WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance(SERVICE, TYPE, record);
            this.manager.clearLocalServices(this.channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    DirectLink.this.manager.addLocalService(DirectLink.this.channel, service, null);
                }

                @Override
                public void onFailure(int reason) {
                    DirectLink.this.manager.addLocalService(DirectLink.this.channel, service, null);
                }
            });
            // A group may be left from the last game; creating it again just fails, which is fine.
            this.manager.createGroup(this.channel, null);
            this.manager.requestGroupInfo(this.channel, this::onGroup);
        } catch (SecurityException ignored) {
        }
    }

    // Looks for rooms nearby, reporting the list as it grows.
    void discover(Rooms listener) {
        if (!open() || !permitted(this.context)) {
            listener.onRooms(new ArrayList<>());
            return;
        }
        this.rooms = listener;
        this.found.clear();
        try {
            this.manager.setDnsSdResponseListeners(this.channel,
                    (instance, type, device) -> {
                        if (!SERVICE.equalsIgnoreCase(instance) || device == null) {
                            return;
                        }
                        if (!this.found.containsKey(device.deviceAddress)) {
                            this.found.put(device.deviceAddress, new Room(device, device.deviceName, ""));
                            report();
                        }
                    },
                    (domain, record, device) -> {
                        if (device == null || record == null) {
                            return;
                        }
                        String game = record.get("game");
                        String name = record.get("name");
                        this.found.put(device.deviceAddress, new Room(device, name == null || name.isEmpty() ? device.deviceName : name, game == null ? "" : game));
                        report();
                    });
            this.manager.clearServiceRequests(this.channel, null);
            this.manager.addServiceRequest(this.channel, WifiP2pDnsSdServiceRequest.newInstance(), null);
            this.manager.discoverServices(this.channel, null);
        } catch (SecurityException ignored) {
        }
    }

    private void report() {
        Rooms listener = this.rooms;
        if (listener != null) {
            listener.onRooms(new ArrayList<>(this.found.values()));
        }
    }

    void stopDiscovery() {
        this.rooms = null;
        if (this.channel != null) {
            try {
                this.manager.clearServiceRequests(this.channel, null);
                this.manager.stopPeerDiscovery(this.channel, null);
            } catch (SecurityException ignored) {
            }
        }
    }

    // Joins a room; the host's address comes back once the group owner lets this phone in.
    void connect(Room room, Connected listener) {
        if (!open() || !permitted(this.context)) {
            listener.onFailed("권한이 없어요");
            return;
        }
        this.connected = listener;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = room.device.deviceAddress;
        config.groupOwnerIntent = 0;
        try {
            this.manager.connect(this.channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    // Waiting for the owner's acceptance; the connection broadcast finishes it.
                    DirectLink.this.manager.requestConnectionInfo(DirectLink.this.channel, DirectLink.this::onConnection);
                }

                @Override
                public void onFailure(int reason) {
                    DirectLink.this.connected = null;
                    listener.onFailed(reason == WifiP2pManager.BUSY ? "Wi-Fi Direct가 바빠요. 잠시 후 다시 해 주세요" : "연결하지 못했어요");
                }
            });
        } catch (SecurityException e) {
            this.connected = null;
            listener.onFailed("권한이 없어요");
        }
    }

    // Leaves the group and stops advertising; the phone returns to its usual Wi-Fi.
    void release() {
        this.hosting = false;
        this.connected = null;
        this.rooms = null;
        if (this.channel != null) {
            try {
                this.manager.clearLocalServices(this.channel, null);
                this.manager.clearServiceRequests(this.channel, null);
                this.manager.removeGroup(this.channel, null);
            } catch (SecurityException ignored) {
            }
        }
        if (this.receiver != null) {
            try {
                this.context.unregisterReceiver(this.receiver);
            } catch (Exception ignored) {
            }
            this.receiver = null;
        }
    }

    // Lets go of the callbacks without leaving the group (the game is restarting).
    void detach() {
        this.connected = null;
        this.rooms = null;
        if (this.receiver != null) {
            try {
                this.context.unregisterReceiver(this.receiver);
            } catch (Exception ignored) {
            }
            this.receiver = null;
        }
    }
}
