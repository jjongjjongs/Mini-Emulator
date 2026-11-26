package com.example.minigba;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Wii 친구끼리: the room server a host phone runs while its game is on. It is WiiLink WFC's
// server (wfc-server, AGPL-3.0) with an in-memory database, built by cores/build-wfcfriends.sh
// as libwfcserver.so (an executable, so it lands in the app's native library folder, the one
// place Android lets an app start programs from). Its files (the payloads the games download
// and the key that signs them, the game list) come from assets/wfc_room.zip.
// Online communications credit to WiiLink WFC - https://wfc.wiilink.ca
// Android 8+ only, like the GameCube / Wii mode itself.
@android.annotation.TargetApi(26)
final class WfcRoom {
    private static final String TAG = "WfcRoom";
    // The game's port 80 becomes this one (Dolphin moves it, cores/patches/dolphin-mini-wfc.patch):
    // apps cannot listen below 1024.
    static final int HTTP_PORT = 28080;

    private static Process process;

    private WfcRoom() {
    }

    static File roomDir(Context context) {
        return new File(context.getFilesDir(), "wfc_room");
    }

    static File logFile(Context context) {
        return new File(roomDir(context), "server.log");
    }

    static boolean shipped(Context context) {
        if (!new File(context.getApplicationInfo().nativeLibraryDir, "libwfcserver.so").isFile()) {
            return false;
        }
        try (InputStream in = context.getAssets().open("wfc_room.zip")) {
            return in.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    // Starts the server (once; a second call while it runs does nothing). False when it could
    // not start; the reason is in the log.
    static synchronized boolean start(Context context) {
        if (process != null && process.isAlive()) {
            return true;
        }
        File dir = roomDir(context);
        try {
            unpack(context, dir);
            writeConfig(dir);
            stopStale(dir);
            File exe = new File(context.getApplicationInfo().nativeLibraryDir, "libwfcserver.so");
            ProcessBuilder builder = new ProcessBuilder(exe.getAbsolutePath());
            builder.directory(dir);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(logFile(context)));
            process = builder.start();
            writePid(dir, process);
            // Its services are up within a moment; a failure to listen shows in the log.
            Thread.sleep(800);
            if (!process.isAlive()) {
                Log.e(TAG, "room server exited: " + process.exitValue());
                process = null;
                return false;
            }
            return true;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Log.e(TAG, "room server failed to start", e);
            return false;
        }
    }

    static synchronized void stop() {
        Process running = process;
        process = null;
        if (running != null) {
            // SIGTERM: the server stops its backend process and closes the ports.
            running.destroy();
        }
    }

    static synchronized boolean running() {
        return process != null && process.isAlive();
    }

    // The last lines of the server's log, for 접속 기록.
    static String tail(Context context, int lines) {
        File file = logFile(context);
        if (!file.isFile()) {
            return "";
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            String text = new String(data, StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;]*m", "");
            String[] all = text.split("\n");
            StringBuilder out = new StringBuilder();
            for (int k = Math.max(0, all.length - lines); k < all.length; k++) {
                out.append(all[k]).append('\n');
            }
            return out.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    // assets/wfc_room.zip into the room folder, again whenever the app was updated.
    private static void unpack(Context context, File dir) throws IOException {
        String version;
        try {
            version = String.valueOf(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (Exception e) {
            version = "0";
        }
        File marker = new File(dir, ".version");
        if (marker.isFile() && version.equals(new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String root = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("wfc_room.zip"))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(dir, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream stream = new FileOutputStream(out)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        stream.write(buffer, 0, read);
                    }
                }
            }
        }
        try (OutputStream stream = new FileOutputStream(marker)) {
            stream.write(version.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Every service on all of the phone's addresses (Wi-Fi, Wi-Fi Direct, VPN); the two
    // processes of the server talk over the loopback only. Friends only: no database, no bans,
    // Dolphin's default console keys are fine.
    private static void writeConfig(File dir) throws IOException {
        String config = "<Config>\n"
                + "  <address>0.0.0.0</address>\n"
                + "  <nasPort>" + HTTP_PORT + "</nasPort>\n"
                + "  <nasPortHttps>28443</nasPortHttps>\n"
                + "  <enableHttps>false</enableHttps>\n"
                + "  <payloadServerAddress></payloadServerAddress>\n"
                + "  <frontendAddress>127.0.0.1:29998</frontendAddress>\n"
                + "  <frontendBackendAddress>127.0.0.1:29999</frontendBackendAddress>\n"
                + "  <backendAddress>127.0.0.1:29999</backendAddress>\n"
                + "  <backendFrontendAddress>127.0.0.1:29998</backendFrontendAddress>\n"
                + "  <enableHttpsExploitWii>false</enableHttpsExploitWii>\n"
                + "  <enableHttpsExploitDS>false</enableHttpsExploitDS>\n"
                + "  <allowDefaultDolphinKeys>true</allowDefaultDolphinKeys>\n"
                + "  <allowMultipleDeviceIDs>always</allowMultipleDeviceIDs>\n"
                + "  <allowConnectWithoutDeviceID>true</allowConnectWithoutDeviceID>\n"
                + "  <logLevel>4</logLevel>\n"
                + "  <logOutput>StdOut</logOutput>\n"
                + "  <apiSecret>" + Long.toHexString(new java.security.SecureRandom().nextLong()) + "</apiSecret>\n"
                + "  <serverName>Mini Emulator</serverName>\n"
                + "  <eventReporting><logToDatabase>false</logToDatabase></eventReporting>\n"
                + "</Config>\n";
        try (OutputStream stream = new FileOutputStream(new File(dir, "config.xml"))) {
            stream.write(config.getBytes(StandardCharsets.UTF_8));
        }
    }

    // A server left over from a game that ended without stopping it would hold the ports.
    private static void stopStale(File dir) {
        File pidFile = new File(dir, "server.pid");
        if (!pidFile.isFile()) {
            return;
        }
        try {
            int pid = Integer.parseInt(new String(java.nio.file.Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim());
            if (pid > 0) {
                android.os.Process.sendSignal(pid, 15);
                Thread.sleep(300);
            }
        } catch (IOException | NumberFormatException | InterruptedException | SecurityException e) {
            // Gone already.
        }
        pidFile.delete();
    }

    private static void writePid(File dir, Process started) {
        try {
            java.lang.reflect.Field field = started.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            int pid = field.getInt(started);
            try (OutputStream stream = new FileOutputStream(new File(dir, "server.pid"))) {
                stream.write(String.valueOf(pid).getBytes(StandardCharsets.UTF_8));
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            // Without it a stale server is not stopped; it exits with its process group anyway.
        }
    }
}
