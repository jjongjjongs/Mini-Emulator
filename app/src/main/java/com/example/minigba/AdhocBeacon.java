package com.example.minigba;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

// "와이파이 멀티": finding the host on the local network. The host answers a UDP broadcast
// with its name, game and address; a guest broadcasts the question and lists the answers.
// The game traffic itself is PPSSPP's ad-hoc emulation, pointed at the host's address.
final class AdhocBeacon {
    static final int PORT = 27401;
    private static final String ASK = "MINI-EMULATOR-ADHOC?";
    private static final String REPLY = "MINI-EMULATOR-ADHOC!";

    static final class Host {
        final String address;
        final String name;
        final String game;

        Host(String address, String name, String game) {
            this.address = address;
            this.name = name;
            this.game = game;
        }
    }

    interface Found {
        void onFound(List<Host> hosts, boolean finished);
    }

    private Thread thread;
    private DatagramSocket socket;
    private WifiManager.MulticastLock lock;

    // This phone's address on the Wi-Fi (or hotspot), or null when there is none.
    static String localAddress() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String name = iface.getName();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                        return address.getHostAddress();
                    }
                    if (fallback == null && address.isSiteLocalAddress()) {
                        fallback = address.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    static String deviceName(Context context) {
        String name = null;
        try {
            name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        return name == null || name.isEmpty() ? Build.MODEL : name;
    }

    // Answers guests while this phone hosts. game: the title both sides must be running.
    void startHost(Context context, String game) {
        stop();
        final String name = deviceName(context);
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                this.lock = wifi.createMulticastLock("mini-emulator-adhoc");
                this.lock.setReferenceCounted(false);
                this.lock.acquire();
            }
        } catch (Exception ignored) {
        }
        this.thread = new Thread(() -> {
            try (DatagramSocket server = new DatagramSocket(null)) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                this.socket = server;
                byte[] buffer = new byte[256];
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    server.receive(packet);
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (!text.startsWith(ASK)) {
                        continue;
                    }
                    String address = localAddress();
                    byte[] reply = (REPLY + (address == null ? "" : address) + "|" + name.replace('|', ' ') + "|" + game.replace('|', ' ')).getBytes(StandardCharsets.UTF_8);
                    server.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                }
            } catch (Exception ignored) {
                // Closed from stop(), or the port is taken: hosting still works by address.
            }
        }, "adhoc-beacon");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    void stop() {
        Thread running = this.thread;
        this.thread = null;
        if (running != null) {
            running.interrupt();
        }
        DatagramSocket open = this.socket;
        this.socket = null;
        if (open != null) {
            open.close();
        }
        if (this.lock != null) {
            try {
                this.lock.release();
            } catch (Exception ignored) {
            }
            this.lock = null;
        }
    }

    // Asks the network for hosts for about three seconds, reporting on the main thread as
    // answers arrive and once more when done.
    static Thread discover(Found found) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            List<Host> hosts = new ArrayList<>();
            try (DatagramSocket client = new DatagramSocket()) {
                client.setBroadcast(true);
                client.setSoTimeout(400);
                byte[] ask = ASK.getBytes(StandardCharsets.UTF_8);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                long until = System.currentTimeMillis() + 3200;
                int sent = 0;
                byte[] buffer = new byte[256];
                while (System.currentTimeMillis() < until && !Thread.currentThread().isInterrupted()) {
                    if (sent < 4) {
                        client.send(new DatagramPacket(ask, ask.length, broadcast, PORT));
                        sent++;
                    }
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        client.receive(packet);
                        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        if (!text.startsWith(REPLY)) {
                            continue;
                        }
                        String[] parts = text.substring(REPLY.length()).split("\\|", -1);
                        String address = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : packet.getAddress().getHostAddress();
                        boolean known = false;
                        for (Host host : hosts) {
                            known |= host.address.equals(address);
                        }
                        if (!known) {
                            hosts.add(new Host(address, parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : ""));
                            final List<Host> snapshot = new ArrayList<>(hosts);
                            main.post(() -> found.onFound(snapshot, false));
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            final List<Host> result = Collections.unmodifiableList(new ArrayList<>(hosts));
            main.post(() -> found.onFound(result, true));
        }, "adhoc-discover");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
