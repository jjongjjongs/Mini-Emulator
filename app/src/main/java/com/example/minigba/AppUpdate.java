package com.example.minigba;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

// 앱 업데이트: the newest release on GitHub (the same signed APK the releases page has),
// downloaded with the system's download manager (its own notification, carries on in the
// background) and handed to Android's installer. Android only installs it over this app when
// it is signed with the same key and has a higher versionCode.
final class AppUpdate {
    static final String RELEASES = "https://api.github.com/repos/jjongjjongs/Mini-Emulator/releases?per_page=10";
    private static final String PREFS_LAST_CHECK = "update_last_check";
    private static final String PREFS_SKIP = "update_skip";
    private static final String PREFS_AUTO = "update_auto";
    private static final String PREFS_DOWNLOAD = "update_download_id";
    private static final String PREFS_DOWNLOAD_VERSION = "update_download_version";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    static final class Release {
        final String version;   // "0.0.230"
        final String notes;
        final String url;       // the APK
        final long size;

        Release(String version, String notes, String url, long size) {
            this.version = version;
            this.notes = notes;
            this.url = url;
            this.size = size;
        }
    }

    private AppUpdate() {
    }

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    // -1, 0, 1 for dotted numbers ("0.0.229" < "0.0.230").
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            long p = k < x.length ? parse(x[k]) : 0;
            long q = k < y.length ? parse(y[k]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static long parse(String part) {
        try {
            return Long.parseLong(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // The newest published release with its APK, or null (none, or no network). Off the main thread.
    static Release fetchLatest() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Mini-Emulator");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("GitHub " + connection.getResponseCode());
            }
            String text;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray releases = new JSONArray(text);
            Release best = null;
            for (int k = 0; k < releases.length(); k++) {
                JSONObject release = releases.getJSONObject(k);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                if (version.isEmpty() || (best != null && compare(version, best.version) <= 0)) {
                    continue;
                }
                JSONArray assets = release.optJSONArray("assets");
                String wanted = "Mini-Emulator-" + version + ".apk";
                for (int a = 0; assets != null && a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    if (wanted.equals(asset.optString("name"))) {
                        String notes = release.isNull("body") ? "" : release.optString("body", "");
                        best = new Release(version, notes.trim(), asset.optString("browser_download_url"), asset.optLong("size"));
                        break;
                    }
                }
            }
            return best;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            connection.disconnect();
        }
    }

    static boolean autoCheck(SharedPreferences prefs) {
        return prefs.getBoolean(PREFS_AUTO, true);
    }

    static void setAutoCheck(SharedPreferences prefs, boolean on) {
        prefs.edit().putBoolean(PREFS_AUTO, on).apply();
    }

    // Once a day at most, when auto check is on.
    static boolean dueForCheck(SharedPreferences prefs) {
        return autoCheck(prefs) && System.currentTimeMillis() - prefs.getLong(PREFS_LAST_CHECK, 0) > DAY_MS;
    }

    static void markChecked(SharedPreferences prefs) {
        prefs.edit().putLong(PREFS_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    static boolean skipped(SharedPreferences prefs, String version) {
        return version.equals(prefs.getString(PREFS_SKIP, ""));
    }

    static void skip(SharedPreferences prefs, String version) {
        prefs.edit().putString(PREFS_SKIP, version).apply();
    }

    static String sizeText(long bytes) {
        return bytes <= 0 ? "" : String.format(Locale.US, "약 %dMB", Math.round(bytes / 1048576.0));
    }

    // ---- download ----

    static File apkFile(Context context, String version) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return new File(dir, "Mini-Emulator-" + version + ".apk");
    }

    static long startDownload(Context context, SharedPreferences prefs, Release release) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        File target = apkFile(context, release.version);
        if (target.exists()) {
            target.delete();
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url));
        request.setTitle("Mini Emulator " + release.version);
        request.setDescription("업데이트 받는 중");
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, target.getName());
        long id = manager.enqueue(request);
        prefs.edit().putLong(PREFS_DOWNLOAD, id).putString(PREFS_DOWNLOAD_VERSION, release.version).apply();
        return id;
    }

    static long downloadId(SharedPreferences prefs) {
        return prefs.getLong(PREFS_DOWNLOAD, -1);
    }

    static String downloadVersion(SharedPreferences prefs) {
        return prefs.getString(PREFS_DOWNLOAD_VERSION, "");
    }

    static void clearDownload(SharedPreferences prefs) {
        prefs.edit().remove(PREFS_DOWNLOAD).remove(PREFS_DOWNLOAD_VERSION).apply();
    }

    // {status, bytes so far, total bytes}; status is a DownloadManager.STATUS_* (0 when unknown).
    static long[] progress(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return new long[]{0, 0, 0};
            }
            return new long[]{
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))};
        }
    }

    static void cancel(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        manager.remove(id);
    }

    // The finished APK as a content URI the installer may read.
    static Uri downloadedUri(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        return manager.getUriForDownloadedFile(id);
    }
}
