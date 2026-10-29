package com.example.minigba;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 충돌 보고: every abnormal end (an engine signal the bridge wrote to last_crash.txt, an
 * uncaught Java exception, or a game process the system ended) becomes one folder under
 * files/crash/reports/&lt;yyyyMMdd-HHmmss&gt;/ holding summary.txt, crash.txt, log.txt,
 * tombstone.txt, exit.txt and settings.txt. A report is "unseen" until the player dismisses
 * or exports it, and the list screen shows a card while any is. The newest ten are kept.
 * Exporting zips the reports into 다운로드/Mini Emulator.
 */
final class CrashReports {
    static final int KEEP = 10;
    private static final String UNSEEN = ".unseen";

    static final class Report {
        File dir;
        String id;
        String kind = "";
        String when = "";
        String game = "";
        String cause = "";
        boolean unseen;

        String title() {
            return kind.isEmpty() ? "비정상 종료" : kind;
        }
    }

    private CrashReports() {
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "crash");
    }

    static File reportsDir(Context context) {
        return new File(dir(context), "reports");
    }

    // ---- Java side: uncaught exceptions in either process ----

    /** Writes any uncaught exception to files/crash/pending-java-&lt;time&gt;.txt before the process dies. */
    static void installJavaHandler(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Handler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new Handler(app, previous));
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Context app, Thread.UncaughtExceptionHandler previous) {
            this.app = app;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                File folder = dir(app);
                //noinspection ResultOfMethodCallIgnored
                folder.mkdirs();
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                String process = processName(app);
                String text = "java exception in " + process + " thread " + thread.getName() + "\n"
                        + "time " + stamp(System.currentTimeMillis()) + "\n"
                        + error.getClass().getName() + ": " + error.getMessage() + "\n" + trace;
                writeText(new File(folder, "pending-java-" + System.currentTimeMillis() + ".txt"), text);
            } catch (Throwable ignored) {
                // Nothing more can be done while dying.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    private static String processName(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return android.app.Application.getProcessName();
        }
        int pid = android.os.Process.myPid();
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
        if (processes != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == pid) return process.processName;
            }
        }
        return "?";
    }

    // ---- collecting on the list screen ----

    /**
     * Turns what the last session left (last_crash.txt from the bridge, pending Java
     * traces) into reports. Returns how many were made; sets vulkanCore[0] when the engine
     * crashed drawing with Vulkan so the caller can turn it off for that core.
     */
    static int collectCrashFiles(Context context, long sessionStart, String exitText, InputStream tombstone, String[] vulkanCore) {
        int made = 0;
        File nativeCrash = new File(context.getFilesDir(), "last_crash.txt");
        if (nativeCrash.isFile()) {
            String text = readText(nativeCrash, 256 * 1024);
            //noinspection ResultOfMethodCallIgnored
            nativeCrash.delete();
            if (!text.trim().isEmpty()) {
                java.util.regex.Matcher vulkan = java.util.regex.Pattern.compile("core (\\S+), rom .*, renderer vulkan").matcher(text);
                if (vulkan.find() && vulkanCore != null) {
                    vulkanCore[0] = vulkan.group(1);
                }
                create(context, "엔진 오류", nativeCrashCause(text), text, sessionStart, exitText, tombstone, nativeCrash.lastModified());
                made++;
                tombstone = null;
            }
        }
        File[] javaCrashes = dir(context).listFiles((d, name) -> name.startsWith("pending-java-") && name.endsWith(".txt"));
        if (javaCrashes != null) {
            for (File file : javaCrashes) {
                String text = readText(file, 256 * 1024);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                if (text.trim().isEmpty()) continue;
                String[] lines = text.split("\n");
                String cause = lines.length > 2 ? lines[2] : "자바 오류";
                create(context, "앱 오류", "자바 오류 · " + cause, text, sessionStart, exitText, null, file.lastModified());
                made++;
            }
        }
        return made;
    }

    /** The game process ended without a crash file: the system's record says why. */
    static void collectUnexpectedExit(Context context, long sessionStart, String exitText, InputStream tombstone) {
        String cause = "갑자기 종료";
        if (exitText != null) {
            if (exitText.contains("LOW_MEMORY")) cause = "메모리 부족으로 시스템이 종료";
            else if (exitText.contains("CRASH_NATIVE")) cause = "엔진 오류 (시스템 기록)";
            else if (exitText.contains("ANR")) cause = "응답 없음 (ANR)";
            else if (exitText.contains("SIGNALED")) cause = "신호로 종료";
        }
        create(context, "갑작스러운 종료", cause, "", sessionStart, exitText, tombstone, System.currentTimeMillis());
    }

    private static String nativeCrashCause(String text) {
        java.util.regex.Matcher signal = java.util.regex.Pattern.compile("signal (\\d+)").matcher(text);
        String name = "엔진 오류";
        if (signal.find()) {
            switch (Integer.parseInt(signal.group(1))) {
                case 11: name = "SIGSEGV (잘못된 메모리 접근)"; break;
                case 7: name = "SIGBUS"; break;
                case 4: name = "SIGILL"; break;
                case 8: name = "SIGFPE"; break;
                case 6: name = "SIGABRT (엔진이 스스로 중단)"; break;
                case 5: name = "SIGTRAP"; break;
                default: name = "signal " + signal.group(1);
            }
        }
        java.util.regex.Matcher pc = java.util.regex.Pattern.compile("\npc (\\S+)").matcher(text);
        if (pc.find()) {
            String where = pc.group(1);
            int plus = where.indexOf('+');
            name += " · " + (plus > 0 ? where.substring(0, plus) : where);
        }
        return name;
    }

    private static void create(Context context, String kind, String cause, String crashText, long sessionStart, String exitText,
                               InputStream tombstone, long at) {
        SharedPreferences prefs = MainActivity.sharedSettings(context);
        File folder = new File(reportsDir(context), new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(at)));
        for (int i = 1; folder.exists() && i < 100; i++) {
            folder = new File(reportsDir(context), folder.getName().replaceAll("-\\d+$", "") + "-" + i);
        }
        if (!folder.mkdirs()) {
            return;
        }
        String coreId = "";
        String rom = "";
        java.util.regex.Matcher ctx = java.util.regex.Pattern.compile("core (\\S+), rom ([^,\\n]*)").matcher(crashText);
        if (ctx.find()) {
            coreId = ctx.group(1);
            rom = ctx.group(2).trim();
        }
        if (rom.isEmpty()) {
            rom = prefs.getString("crash:last_game", "");
            coreId = prefs.getString("crash:last_core", coreId);
        }
        String console = prefs.getString("crash:last_mode", "");
        long played = sessionStart > 0 && at > sessionStart ? (at - sessionStart) / 1000 : -1;

        StringBuilder summary = new StringBuilder();
        summary.append("Mini Emulator 오류 보고\n");
        summary.append("종류 ").append(kind).append('\n');
        summary.append("원인 ").append(cause).append('\n');
        summary.append("시각 ").append(stamp(at)).append('\n');
        summary.append("앱 ").append(appVersion(context)).append('\n');
        summary.append("기기 ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" (").append(android.os.Build.DEVICE).append(") · Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        summary.append("CPU ").append(String.join(",", android.os.Build.SUPPORTED_ABIS)).append('\n');
        String gpu = prefs.getString("crash:gpu", "");
        if (!gpu.isEmpty()) summary.append("GPU ").append(gpu).append('\n');
        summary.append("기종 ").append(console).append(coreId.isEmpty() ? "" : " (" + coreId + ")").append('\n');
        summary.append("게임 ").append(rom).append('\n');
        if (played >= 0) summary.append("플레이 ").append(played / 60).append("분 ").append(played % 60).append("초\n");
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(memory);
            summary.append("메모리 ").append(memory.totalMem / (1024 * 1024)).append(" MB 중 ").append(memory.availMem / (1024 * 1024)).append(" MB 여유 (지금)\n");
        }
        writeTextQuietly(new File(folder, "summary.txt"), summary.toString());
        if (!crashText.isEmpty()) {
            // The bridge's file ends with its log lines; they go to log.txt, the rest stays.
            int log = crashText.indexOf("\nlog:\n");
            writeTextQuietly(new File(folder, "crash.txt"), log >= 0 ? crashText.substring(0, log + 1) : crashText);
            if (log >= 0) {
                writeTextQuietly(new File(folder, "log.txt"), crashText.substring(log + 6));
            }
        }
        if (!new File(folder, "log.txt").isFile()) {
            String log = readGameLog(context);
            if (!log.isEmpty()) writeTextQuietly(new File(folder, "log.txt"), log);
        }
        if (exitText != null && !exitText.isEmpty()) {
            writeTextQuietly(new File(folder, "exit.txt"), exitText);
        }
        if (tombstone != null) {
            try (InputStream in = tombstone; FileOutputStream out = new FileOutputStream(new File(folder, "tombstone.txt"))) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0 && total < 2L * 1024 * 1024) {
                    out.write(buffer, 0, n);
                    total += n;
                }
            } catch (IOException ignored) {
                // The trace is a bonus.
            }
        }
        writeTextQuietly(new File(folder, "settings.txt"), settingsDump(prefs));
        writeTextQuietly(new File(folder, UNSEEN), "1");
        trim(context);
    }

    // The settings in force, so a report says what the game ran with (long values cut short).
    private static String settingsDump(SharedPreferences prefs) {
        Map<String, Object> sorted = new TreeMap<>(prefs.getAll());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("guide:") || key.startsWith("crash:")) continue;
            String value = String.valueOf(entry.getValue());
            if (value.length() > 120) value = value.substring(0, 120) + "…";
            sb.append(key).append(" = ").append(value).append('\n');
        }
        return sb.toString();
    }

    /** Remembers what is being played, so a report can name it even when the bridge wrote nothing. */
    static void noteGame(Context context, String mode, String coreId, String game) {
        MainActivity.sharedSettings(context).edit().putString("crash:last_mode", mode == null ? "" : mode)
                .putString("crash:last_core", coreId == null ? "" : coreId).putString("crash:last_game", game == null ? "" : game).apply();
    }

    // files/game_log.bin: a little-endian line count, then 160 slots of 240 bytes.
    static String readGameLog(Context context) {
        File file = new File(context.getFilesDir(), "game_log.bin");
        if (!file.isFile()) return "";
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = new byte[(int) Math.min(file.length(), 4 + 160 * 240)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
        } catch (IOException e) {
            return "";
        }
        if (data.length < 4) return "";
        long count = (data[0] & 255) | ((data[1] & 255) << 8) | ((data[2] & 255) << 16) | ((long) (data[3] & 255) << 24);
        StringBuilder sb = new StringBuilder();
        for (long i = Math.max(0, count - 160); i < count; i++) {
            int at = 4 + (int) (i % 160) * 240;
            int end = at;
            while (end < Math.min(data.length, at + 240) && data[end] != 0) end++;
            sb.append(new String(data, at, end - at, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    // ---- reports ----

    static List<Report> list(Context context) {
        List<Report> out = new ArrayList<>();
        File[] dirs = reportsDir(context).listFiles(File::isDirectory);
        if (dirs != null) {
            for (File folder : dirs) {
                Report report = read(folder);
                if (report != null) out.add(report);
            }
        }
        Collections.sort(out, (x, y) -> y.id.compareTo(x.id));
        return out;
    }

    static Report read(File folder) {
        File summary = new File(folder, "summary.txt");
        if (!summary.isFile()) return null;
        Report report = new Report();
        report.dir = folder;
        report.id = folder.getName();
        report.unseen = new File(folder, UNSEEN).isFile();
        for (String line : readText(summary, 8192).split("\n")) {
            if (line.startsWith("종류 ")) report.kind = line.substring(3);
            else if (line.startsWith("원인 ")) report.cause = line.substring(3);
            else if (line.startsWith("시각 ")) report.when = line.substring(3);
            else if (line.startsWith("게임 ")) report.game = line.substring(3);
            else if (line.startsWith("기종 ") && !line.substring(3).trim().isEmpty()) report.game = line.substring(3) + " · " + report.game;
        }
        return report;
    }

    static int unseenCount(Context context) {
        int n = 0;
        for (Report report : list(context)) {
            if (report.unseen) n++;
        }
        return n;
    }

    static void markSeen(Context context) {
        for (Report report : list(context)) {
            //noinspection ResultOfMethodCallIgnored
            new File(report.dir, UNSEEN).delete();
        }
    }

    static void delete(Context context, Report report) {
        N3dsCia.deleteTree(report.dir);
    }

    static void deleteAll(Context context) {
        for (Report report : list(context)) {
            N3dsCia.deleteTree(report.dir);
        }
    }

    private static void trim(Context context) {
        List<Report> all = list(context);
        for (int i = KEEP; i < all.size(); i++) {
            N3dsCia.deleteTree(all.get(i).dir);
        }
    }

    /** The whole report as one text, for the detail view and the clipboard. */
    static String fullText(Report report) {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"summary.txt", "crash.txt", "exit.txt", "log.txt", "tombstone.txt", "settings.txt"}) {
            File file = new File(report.dir, name);
            if (!file.isFile()) continue;
            sb.append("===== ").append(name).append(" =====\n").append(readText(file, 64 * 1024)).append('\n');
        }
        return sb.toString();
    }

    // ---- export ----

    /** Zips the reports into 다운로드/Mini Emulator (Android 10+) or the app's external files; returns the file name. */
    static String export(Context context, List<Report> reports) throws IOException {
        String name = "오류보고-" + new SimpleDateFormat("MMdd-HHmm", Locale.US).format(new Date()) + ".zip";
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER + "/");
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            android.net.Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("다운로드 폴더에 파일을 만들 수 없어요");
            try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("파일을 열 수 없어요");
                zip(reports, out);
            }
            values.clear();
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) name = cursor.getString(0);
            } catch (RuntimeException ignored) {
                // The requested name will do.
            }
            return "다운로드 › " + MainActivity.DOWNLOAD_FOLDER + " › " + name;
        }
        File folder = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) throw new IOException("외부 저장 폴더를 쓸 수 없어요");
        File file = new File(folder, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            zip(reports, out);
        }
        return file.getAbsolutePath();
    }

    private static void zip(List<Report> reports, java.io.OutputStream out) throws IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(out))) {
            for (Report report : reports) {
                File[] files = report.dir.listFiles(File::isFile);
                if (files == null) continue;
                for (File file : files) {
                    if (file.getName().startsWith(".")) continue;
                    zip.putNextEntry(new java.util.zip.ZipEntry(report.id + "/" + file.getName()));
                    try (FileInputStream in = new FileInputStream(file)) {
                        byte[] buffer = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    // ---- small helpers ----

    static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    static String stamp(long time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(time));
    }

    static String readText(File file, int max) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), max)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeTextQuietly(File file, String text) {
        try {
            writeText(file, text);
        } catch (IOException ignored) {
            // A missing part leaves the rest of the report usable.
        }
    }
}
