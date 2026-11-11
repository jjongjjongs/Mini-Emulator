package com.example.minigba;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// (0.0.417) 엔진 기록: the Xbox engines (Xbox 360 on XenDroid, the original Xbox on HakuX) run in
// processes of their own (":x360", ":xbox"). Each run is marked (game, start, stage: 준비 중 ·
// 실행 중 · 끝남); a run that never reached 끝남 ended suddenly, and the home screen says so
// next time, with what Android kept about that process's end (ApplicationExitInfo: the reason,
// a native crash's tombstone) and the engine's own log. "기록 보내기" puts it all in one .txt
// in Download/Mini Emulator.
final class EngineLog {
    static final String X360 = "x360";
    static final String XBOX = "xbox";
    static final int PREPARING = 1;
    static final int RUNNING = 2;
    static final int ENDED = 3;

    private EngineLog() {
    }

    // The run marks are written by the engine's process and read by the home screen's, so
    // they are a small file each (SharedPreferences are cached per process).
    private static File runFile(Context context, String mode) {
        return new File(context.getFilesDir(), "engine_run_" + mode + ".properties");
    }

    private static synchronized java.util.Properties load(Context context, String mode) {
        java.util.Properties props = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(runFile(context, mode))) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // none yet
        }
        return props;
    }

    private static synchronized void store(Context context, String mode, java.util.Properties props) {
        File file = runFile(context, mode);
        File temp = new File(file.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            props.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), null);
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(file);
    }

    private static void put(Context context, String mode, String key, String value) {
        java.util.Properties props = load(context, mode);
        props.setProperty(key, value);
        store(context, mode, props);
    }

    static void markStart(Context context, String mode, String title) {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("title", title == null ? "" : title);
        props.setProperty("start", String.valueOf(System.currentTimeMillis()));
        props.setProperty("stage", String.valueOf(PREPARING));
        props.setProperty("shown", "false");
        store(context, mode, props);
    }

    static void markRunning(Context context, String mode) {
        put(context, mode, "stage", String.valueOf(RUNNING));
    }

    static void markEnded(Context context, String mode) {
        put(context, mode, "stage", String.valueOf(ENDED));
    }

    static String title(Context context, String mode) {
        return load(context, mode).getProperty("title", "");
    }

    static long start(Context context, String mode) {
        try {
            return Long.parseLong(load(context, mode).getProperty("start", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String stageName(int stage) {
        return stage == PREPARING ? "엔진 준비 중" : stage == RUNNING ? "게임 실행 중" : stage == ENDED ? "정상 종료" : "기록 없음";
    }

    // The last run ended suddenly (and the process is gone, not still running).
    static boolean endedSuddenly(Context context, String mode) {
        int stage = stage(context, mode);
        return (stage == PREPARING || stage == RUNNING) && !processAlive(context, mode);
    }

    static int stage(Context context, String mode) {
        try {
            return Integer.parseInt(load(context, mode).getProperty("stage", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // Shown once per sudden end.
    static boolean takeUnshownCrash(Context context, String mode) {
        if (!endedSuddenly(context, mode) || "true".equals(load(context, mode).getProperty("shown"))) return false;
        put(context, mode, "shown", "true");
        return true;
    }

    private static boolean processAlive(Context context, String mode) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null || am.getRunningAppProcesses() == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
            if (p.processName != null && p.processName.endsWith(":" + mode)) return true;
        }
        return false;
    }

    // ---- Android's record of the engine process's end ----

    static final class Exit {
        int code;
        String reason = "";
        String detail = "";
        long time;
        int pid;
        String trace = "";
    }

    static Exit lastExit(Context context, String mode) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 20);
            long since = start(context, mode) - 5000;
            for (ApplicationExitInfo info : list) {
                if (info.getProcessName() == null || !info.getProcessName().endsWith(":" + mode) || info.getTimestamp() < since) continue;
                Exit exit = new Exit();
                exit.code = info.getReason();
                exit.reason = reasonName(info.getReason());
                exit.detail = (info.getDescription() == null ? "" : info.getDescription()) + " (상태 " + info.getStatus() + ", 메모리 "
                        + info.getPss() / 1024 + "MB)";
                exit.time = info.getTimestamp();
                exit.pid = info.getPid();
                try (InputStream trace = info.getTraceInputStream()) {
                    if (trace != null) exit.trace = printable(trace, 400);
                } catch (Exception ignored) {
                    // no trace kept
                }
                return exit;
            }
        } catch (Exception ignored) {
            // not readable
        }
        return null;
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "엔진 오류 (네이티브 충돌)";
            case ApplicationExitInfo.REASON_CRASH: return "앱 오류 (자바 충돌)";
            case ApplicationExitInfo.REASON_ANR: return "응답 없음 (ANR)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "메모리 부족 (시스템이 종료)";
            case ApplicationExitInfo.REASON_SIGNALED: return "시스템이 종료 (신호)";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "엔진이 스스로 종료";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "사용자가 종료";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "자원을 너무 많이 씀";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "시작 실패";
            default: return "기타 (" + reason + ")";
        }
    }

    // The readable lines of a trace (a native crash's tombstone is binary; its text parts
    // carry the signal, the backtrace's libraries and functions).
    private static String printable(InputStream in, int maxLines) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0 && bytes.size() < (2 << 20); ) bytes.write(buffer, 0, n);
        byte[] data = bytes.toByteArray();
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        int lines = 0;
        for (int k = 0; k <= data.length && lines < maxLines; k++) {
            int c = k < data.length ? data[k] & 0xFF : 0;
            if (c >= 0x20 && c < 0x7F || c == '\t') {
                run.append((char) c);
            } else if (c == '\n' && run.length() > 0) {
                out.append(run).append('\n');
                run.setLength(0);
                lines++;
            } else {
                if (run.length() >= 6) {
                    out.append(run).append('\n');
                    lines++;
                }
                run.setLength(0);
            }
        }
        return out.toString();
    }

    // ---- The engine's own log ----

    static File engineLogFile(Context context, String mode) {
        return X360.equals(mode) ? new File(X360Activity.rootDir(context), "xe.log") : null;
    }

    static List<String> tail(File file, int lines) {
        List<String> out = new ArrayList<>();
        if (file == null || !file.isFile()) return out;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            long from = Math.max(0, length - 256 * 1024);
            byte[] data = new byte[(int) (length - from)];
            in.seek(from);
            in.readFully(data);
            String[] all = new String(data, StandardCharsets.UTF_8).split("\r?\n");
            for (int k = Math.max(0, all.length - lines); k < all.length; k++) out.add(all[k]);
        } catch (Exception ignored) {
            // unreadable
        }
        return out;
    }

    // This app's log lines kept by Android (an app can read its own); those of the engine's
    // process when its pid is known.
    private static String logcat(int pid) {
        StringBuilder out = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", "6000"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String pidText = pid > 0 ? " " + pid + " " : null;
                List<String> kept = new ArrayList<>();
                for (String line; (line = reader.readLine()) != null; ) {
                    if (pidText == null || line.contains(pidText)) kept.add(line);
                }
                for (int k = Math.max(0, kept.size() - 1500); k < kept.size(); k++) out.append(kept.get(k)).append('\n');
            }
            process.destroy();
        } catch (Exception e) {
            out.append("(읽지 못함: ").append(e.getMessage()).append(")\n");
        }
        return out.toString();
    }

    static String when(long time) {
        return time <= 0 ? "-" : new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new java.util.Date(time));
    }

    // The whole report, as text.
    static String report(Context context, String mode) {
        StringBuilder text = new StringBuilder();
        String version = "?";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        text.append("Mini Emulator ").append(version).append(" · ").append(X360.equals(mode) ? "Xbox 360 (XenDroid)" : "초대 Xbox (HakuX)").append(" 엔진 기록\n");
        text.append("폰: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(Build.VERSION.SDK_INT >= 31 ? " · " + Build.SOC_MODEL : "").append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
        text.append("마지막 실행: ").append(title(context, mode)).append(" · ").append(when(start(context, mode)))
                .append(" · ").append(stageName(stage(context, mode))).append(endedSuddenly(context, mode) ? " (갑자기 꺼짐)" : "").append('\n');
        if (X360.equals(mode)) {
            android.content.SharedPreferences s = MainActivity.sharedSettings(context);
            text.append("설정: 해상도 ").append(s.getInt("x360_res", 0)).append(" · FPS ").append(s.getInt("x360_fps", 0)).append(" · 언어 ")
                    .append(s.getInt("x360_lang", 0)).append(" · 게이머태그 ").append(X360Activity.gamertag(context)).append('\n');
        } else {
            android.content.SharedPreferences s = MainActivity.sharedSettings(context);
            text.append("설정: 배율 ").append(s.getInt("xbox_scale", 0) + 1).append(" · 4:3 ").append(s.getBoolean("xbox_aspect43", true))
                    .append(" · FPS 제한 풀기 ").append(s.getBoolean("xbox_uncap", false)).append('\n');
        }
        Exit exit = lastExit(context, mode);
        text.append("\n== 안드로이드가 남긴 종료 이유 ==\n");
        if (exit == null) {
            text.append(Build.VERSION.SDK_INT < 30 ? "(안드로이드 11 이상에서만 볼 수 있어요)\n" : "(이번 실행의 기록이 없어요)\n");
        } else {
            text.append(exit.reason).append(" · ").append(when(exit.time)).append(" · pid ").append(exit.pid).append('\n').append(exit.detail).append('\n');
            if (!exit.trace.isEmpty()) text.append("\n-- 충돌 기록 --\n").append(exit.trace);
        }
        File log = engineLogFile(context, mode);
        if (log != null) {
            text.append("\n== 엔진 기록 (").append(log.getName()).append(", 마지막 800줄) ==\n");
            for (String line : tail(log, 800)) text.append(line).append('\n');
        }
        text.append("\n== 앱 로그 ==\n").append(logcat(exit == null ? 0 : exit.pid));
        return text.toString();
    }

    // Saves the report in Download/Mini Emulator; the message for the player.
    static String save(Context context, String mode) {
        String name = (X360.equals(mode) ? "Xbox360" : "Xbox") + " 엔진 기록 "
                + new java.text.SimpleDateFormat("MMdd_HHmm", Locale.ROOT).format(new java.util.Date()) + ".txt";
        try {
            String text = report(context, mode);
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/" + MainActivity.DOWNLOAD_FOLDER);
            Uri uri = context.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("다운로드 폴더에 파일을 만들 수 없어요");
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("다운로드 폴더에 파일을 만들 수 없어요");
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return "다운로드/" + MainActivity.DOWNLOAD_FOLDER + "/" + name + "에 저장했어요.\n이 파일을 채팅에 올려 주시면 원인을 볼 수 있어요.";
        } catch (Exception e) {
            return "저장하지 못했어요: " + e.getMessage();
        }
    }

    // ---- 셰이더 캐시 지우기 ----

    static long clearShaderCache(Context context, String mode) {
        long freed = 0;
        if (X360.equals(mode)) {
            File root = X360Activity.rootDir(context);
            for (String name : new String[]{"cache", "cache0", "cache1", "shaders"}) {
                File dir = new File(root, name);
                freed += sizeOf(dir);
                deleteTree(dir);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File external = context.getExternalCacheDir();
            File[] kids = external == null ? null : external.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    String n = kid.getName().toLowerCase(Locale.ROOT);
                    if (n.contains("shader") || n.contains("vulkan") || n.contains("xenia") || n.contains("pipeline")) {
                        freed += sizeOf(kid);
                        deleteTree(kid);
                    }
                }
            }
        } else {
            freed += deleteMatching(XboxActivity.hddDir(context), 4);
            freed += deleteMatching(XboxActivity.rootDir(context), 3);
            freed += deleteMatching(context.getCacheDir(), 2);
        }
        return freed;
    }

    private static long deleteMatching(File dir, int depth) {
        long freed = 0;
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.contains("pipeline_cache") || n.contains("shader")) {
                freed += sizeOf(f);
                deleteTree(f);
            } else if (f.isDirectory() && depth > 0) {
                freed += deleteMatching(f, depth - 1);
            }
        }
        return freed;
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
