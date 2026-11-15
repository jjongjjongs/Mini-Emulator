package com.example.minigba;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * PS2 레시피형 패치 (0.0.383): a patch made as "turn the .bin into an .iso, then put these
 * pieces in it" (patch.json, format "…-bin-to-iso-v1", with patch.bin holding the pieces),
 * like the Time Crisis 2 Korean patch. The json names the original .bin (size, SHA-256), the
 * finished .iso (size, SHA-256) and each piece: where in the .iso, where in patch.bin, how
 * long, its SHA-256. The finished .iso must hash to the json's value, so the result is the
 * same, byte for byte, as the patch's own Windows tool makes.
 */
final class Ps2Recipe {
    interface Opener {
        InputStream open() throws IOException;
    }

    interface Progress {
        void at(long done, long total);
    }

    String format;
    long sourceSize;
    String sourceSha;
    long targetSize;
    String targetSha;
    long payloadSize;
    String payloadSha;
    long[] isoOffset;
    long[] payloadOffset;
    long[] length;
    String[] sha;
    /** Where patch.bin is read from. */
    Opener payload;
    /** What the player picked or what was found (a zip, a folder), for the screens. */
    String sourceName;

    static boolean isRecipeFormat(String format) {
        return format != null && format.toLowerCase(Locale.ROOT).endsWith("-bin-to-iso-v1");
    }

    /** The recipe in a patch.json's text, or null when it is not one. */
    static Ps2Recipe parse(String json) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            Ps2Recipe r = new Ps2Recipe();
            r.format = root.optString("format", "");
            if (!isRecipeFormat(r.format)) {
                return null;
            }
            r.sourceSize = root.getLong("source_size");
            r.sourceSha = root.getString("source_sha256").toLowerCase(Locale.ROOT);
            r.targetSize = root.getLong("target_size");
            r.targetSha = root.getString("target_sha256").toLowerCase(Locale.ROOT);
            r.payloadSize = root.optLong("payload_size", -1);
            r.payloadSha = root.optString("payload_sha256", "").toLowerCase(Locale.ROOT);
            org.json.JSONArray segments = root.getJSONArray("segments");
            int n = segments.length();
            Integer[] order = new Integer[n];
            long[] iso = new long[n];
            long[] pay = new long[n];
            long[] len = new long[n];
            String[] sha = new String[n];
            for (int k = 0; k < n; k++) {
                org.json.JSONObject s = segments.getJSONObject(k);
                iso[k] = s.getLong("iso_offset");
                pay[k] = s.getLong("payload_offset");
                len[k] = s.getLong("length");
                sha[k] = s.optString("sha256", "").toLowerCase(Locale.ROOT);
                order[k] = k;
                if (iso[k] < 0 || len[k] < 0 || pay[k] < 0 || iso[k] + len[k] > r.targetSize) {
                    return null;
                }
            }
            // patch.bin is read once, front to back.
            Arrays.sort(order, (a, b) -> Long.compare(pay[a], pay[b]));
            r.isoOffset = new long[n];
            r.payloadOffset = new long[n];
            r.length = new long[n];
            r.sha = new String[n];
            for (int k = 0; k < n; k++) {
                r.isoOffset[k] = iso[order[k]];
                r.payloadOffset[k] = pay[order[k]];
                r.length[k] = len[order[k]];
                r.sha[k] = sha[order[k]];
            }
            return r;
        } catch (org.json.JSONException | RuntimeException e) {
            return null;
        }
    }

    int pieces() {
        return this.length.length;
    }

    long payloadBytes() {
        long sum = 0;
        for (long l : this.length) sum += l;
        return sum;
    }

    static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return out.toString();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes the pieces into the .iso (already the original, converted). */
    void apply(RandomAccessFile iso, Progress progress) throws IOException {
        if (iso.length() != this.targetSize) {
            throw new IOException(".iso 크기가 패치와 맞지 않아요");
        }
        MessageDigest whole = sha256();
        byte[] buffer = new byte[1 << 20];
        long read = 0;
        long total = payloadBytes();
        try (InputStream in = this.payload.open()) {
            if (in == null) throw new IOException("patch.bin을 열 수 없어요");
            for (int k = 0; k < pieces(); k++) {
                while (read < this.payloadOffset[k]) {
                    long skip = Math.min(buffer.length, this.payloadOffset[k] - read);
                    int n = in.read(buffer, 0, (int) skip);
                    if (n < 0) throw new IOException("patch.bin이 짧아요");
                    whole.update(buffer, 0, n);
                    read += n;
                }
                if (read != this.payloadOffset[k]) {
                    throw new IOException("patch.json의 조각 목록이 겹쳐요");
                }
                MessageDigest piece = sha256();
                long left = this.length[k];
                iso.seek(this.isoOffset[k]);
                while (left > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) throw new IOException("patch.bin이 짧아요");
                    piece.update(buffer, 0, n);
                    whole.update(buffer, 0, n);
                    iso.write(buffer, 0, n);
                    left -= n;
                    read += n;
                    if (progress != null) progress.at(read, total);
                }
                if (!this.sha[k].isEmpty() && !this.sha[k].equals(hex(piece.digest()))) {
                    throw new IOException("patch.bin의 " + (k + 1) + "번째 조각이 손상됐어요");
                }
            }
            int n;
            while ((n = in.read(buffer)) > 0) {
                whole.update(buffer, 0, n);
                read += n;
            }
        }
        if (this.payloadSize >= 0 && read != this.payloadSize) {
            throw new IOException("patch.bin 크기가 patch.json과 달라요");
        }
        if (!this.payloadSha.isEmpty() && !this.payloadSha.equals(hex(whole.digest()))) {
            throw new IOException("patch.bin이 손상됐거나 다른 버전이에요");
        }
    }

    static String sha256Of(File file, Progress progress) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[1 << 20];
        long done = 0;
        long total = file.length();
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                done += n;
                if (progress != null) progress.at(done, total);
            }
        }
        return hex(digest.digest());
    }

    /** "(한글패치)" when the patch's name says Korean, else "(패치)". */
    String suffix() {
        String name = this.sourceName == null ? "" : this.sourceName;
        return name.matches("(?is).*(한글|korean|(^|[^a-z])ko([^a-z]|$)|(^|[^a-z])kor([^a-z]|$)).*") ? "(한글패치)" : "(패치)";
    }
}
