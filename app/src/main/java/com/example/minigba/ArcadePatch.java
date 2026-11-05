package com.example.minigba;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// 아케이드 한글패치 (0.0.293): an FBNeo IPS bundle (a .dat naming, per ROM chip of the set, the
// .ips to lay over it and the chip's original CRC, then [ko_KR] / [en_US] descriptions) for a
// Neo Geo or arcade romset. FBNeo applies such bundles itself when they sit in
// <system>/fbneo/ips/<set>/ and its "fbneo-ips-<set>-<n>" options are on; the ROM zip stays as
// it is. Here the bundle is read, checked against the player's set and put in place.
final class ArcadePatch {
    static final class Line {
        String rom;        // the chip's file name in the set ("004-c1.c1")
        String ips;        // the patch file, relative to the .dat ("004-c1" → 004-c1.ips)
        long crc = -1;     // the original chip's CRC from the .dat, -1 when not given
        long have = -1;    // the CRC of that chip in the player's set, -1 when missing
        boolean ipsFound;
    }

    static final class Plan {
        String title = "";
        String description = "";
        final List<Line> lines = new ArrayList<>();
        final List<String> files = new ArrayList<>();   // patch zip entries to copy (the .dat and its .ips)
        String datDir = "";                              // the .dat's folder in the patch zip
        String generatedDat;                             // a .dat made here for a bundle without one
        File source;                                     // the zip the files come from (an inner zip, maybe)
        String error;
        String innerZip;                                 // a .zip inside the picked zip

        int matching() {
            int n = 0;
            for (Line l : lines) if (l.ipsFound && l.have != -1 && (l.crc == -1 || l.crc == l.have)) n++;
            return n;
        }
    }

    private ArcadePatch() {
    }

    // Zip names in UTF-8, or the old Korean CP949 when they aren't.
    private static ZipFile open(File file) throws IOException {
        try {
            ZipFile zip = new ZipFile(file);
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) e.nextElement();
            return zip;
        } catch (IllegalArgumentException | java.util.zip.ZipException bad) {
            return new ZipFile(file, java.nio.charset.Charset.forName("MS949"));
        }
    }

    private static String readText(ZipFile zip, ZipEntry e) throws IOException {
        byte[] bytes;
        try (InputStream in = zip.getInputStream(e)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0 && out.size() < (1 << 20)) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (Exception notUtf8) {
            return new String(bytes, java.nio.charset.Charset.forName("MS949"));
        }
    }

    // Splits a .dat line into fields: quoted ("rom name.bin") or separated by spaces / tabs.
    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int end = line.indexOf('"', i + 1);
                if (end < 0) end = line.length();
                out.add(line.substring(i + 1, end));
                i = end + 1;
            } else {
                int end = i;
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
                out.add(line.substring(i, end));
                i = end;
            }
        }
        return out;
    }

    static Plan analyze(File game, File patch) {
        Plan plan = analyzeOne(game, patch);
        if (plan.error == null || plan.innerZip == null) return plan;
        // The bundle is a zip inside the zip: look in that one.
        File inner = new File(patch.getParentFile(), "arc_patch_inner.zip");
        try (ZipFile p = open(patch)) {
            ZipEntry e = p.getEntry(plan.innerZip);
            try (InputStream in = p.getInputStream(e); FileOutputStream out = new FileOutputStream(inner)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            return plan;
        }
        Plan nested = analyzeOne(game, inner);
        return nested.error == null ? nested : plan;
    }

    private static Plan analyzeOne(File game, File patch) {
        Plan plan = new Plan();
        plan.source = patch;
        try (ZipFile p = open(patch); ZipFile g = open(game)) {
            Map<String, ZipEntry> chips = new HashMap<>();
            Enumeration<? extends ZipEntry> ge = g.entries();
            while (ge.hasMoreElements()) {
                ZipEntry e = ge.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                chips.put(name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT), e);
            }
            Map<String, ZipEntry> patchFiles = new HashMap<>();
            ZipEntry dat = null;
            List<ZipEntry> ipses = new ArrayList<>();
            int sameAsGame = 0;
            Enumeration<? extends ZipEntry> pe = p.entries();
            while (pe.hasMoreElements()) {
                ZipEntry e = pe.nextElement();
                if (e.isDirectory()) continue;
                String lower = e.getName().toLowerCase(Locale.ROOT);
                patchFiles.put(lower, e);
                if (dat == null && lower.endsWith(".dat") && e.getSize() < (1 << 20)) dat = e;
                if (lower.endsWith(".ips")) ipses.add(e);
                if (lower.endsWith(".zip") && plan.innerZip == null) plan.innerZip = e.getName();
                ZipEntry chip = chips.get(lower.substring(lower.lastIndexOf('/') + 1));
                if (chip != null && chip.getCrc() == e.getCrc()) sameAsGame++;
            }
            if (dat == null && !ipses.isEmpty()) {
                // .ips files without the list: each one goes on the chip of the same name
                // ("004-c1.ips" on "004-c1.c1"), without original CRCs to check against.
                String dir = ipses.get(0).getName().substring(0, ipses.get(0).getName().lastIndexOf('/') + 1);
                StringBuilder made = new StringBuilder();
                for (ZipEntry ips : ipses) {
                    String name = ips.getName();
                    if (!name.startsWith(dir) || name.substring(dir.length()).indexOf('/') >= 0) continue;
                    String base = name.substring(dir.length(), name.length() - 4).toLowerCase(Locale.ROOT);
                    for (Map.Entry<String, ZipEntry> chip : chips.entrySet()) {
                        String chipName = chip.getKey();
                        int dot = chipName.lastIndexOf('.');
                        if ((dot > 0 ? chipName.substring(0, dot) : chipName).equals(base) || chipName.equals(base)) {
                            String rom = chip.getValue().getName().substring(chip.getValue().getName().lastIndexOf('/') + 1);
                            made.append('"').append(rom).append("\" \"").append(name.substring(dir.length(), name.length() - 4)).append("\"\r\n");
                        }
                    }
                }
                if (made.length() == 0) {
                    plan.error = "패치 zip의 .ips 파일 이름이 이 롬의 칩 이름과 맞지 않아서 어디에 붙일지 모르겠어요. 같은 게임의 패치인지 확인해 주세요.";
                    return plan;
                }
                plan.generatedDat = made.toString();
                plan.datDir = dir;
            } else if (dat == null) {
                plan.error = plan.innerZip != null ? "패치 zip 안에 또 다른 zip이 있는데, 그 안에서도 패치(.dat · .ips)를 찾지 못했어요."
                        : sameAsGame > 0 ? "고른 zip은 패치가 아니라 롬 같아요 (게임 롬과 같은 파일이 들어 있어요). 한글패치 zip(.dat · .ips가 든 것)을 골라 주세요."
                        : "패치 zip에 .dat(어느 칩에 어느 .ips를 붙일지 적은 목록)도 .ips도 없어요. FBNeo용 IPS 패치 묶음만 넣을 수 있어요.";
                return plan;
            }
            String datName = dat != null ? dat.getName() : plan.datDir + "Korean_Translation.dat";
            String text;
            if (dat != null) {
                plan.datDir = datName.substring(0, datName.lastIndexOf('/') + 1);
                plan.files.add(datName);
                text = readText(p, dat);
            } else {
                text = plan.generatedDat + "\r\n[ko_KR]\r\n" + patch.getName() + " (.ips " + ipses.size() + "개)\r\n";
                plan.generatedDat = text;
            }
            String section = null;
            Map<String, StringBuilder> sections = new HashMap<>();
            List<String> order = new ArrayList<>();
            for (String raw : text.split("\r?\n")) {
                String line = raw.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    sections.put(section, new StringBuilder());
                    order.add(section);
                    continue;
                }
                if (section != null) {
                    sections.get(section).append(raw).append('\n');
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("CPU") || line.startsWith("CRC")) continue;
                List<String> f = fields(line);
                if (f.size() < 2) continue;
                Line l = new Line();
                l.rom = f.get(0);
                l.ips = f.get(1);
                for (String x : f) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)crc\\(([0-9a-f]{1,8})\\)").matcher(x);
                    if (m.find()) l.crc = Long.parseLong(m.group(1), 16);
                }
                ZipEntry chip = chips.get(l.rom.toLowerCase(Locale.ROOT));
                if (chip != null) l.have = chip.getCrc();
                String ipsPath = plan.datDir + l.ips + (l.ips.toLowerCase(Locale.ROOT).endsWith(".ips") ? "" : ".ips");
                ZipEntry ips = patchFiles.get(ipsPath.toLowerCase(Locale.ROOT));
                l.ipsFound = ips != null;
                if (ips != null && !plan.files.contains(ips.getName())) plan.files.add(ips.getName());
                plan.lines.add(l);
            }
            String pick = sections.containsKey("ko_KR") ? "ko_KR" : sections.containsKey("en_US") ? "en_US" : order.isEmpty() ? null : order.get(0);
            if (pick != null) {
                String body = sections.get(pick).toString().trim();
                int nl = body.indexOf('\n');
                plan.title = (nl < 0 ? body : body.substring(0, nl)).trim();
                plan.description = nl < 0 ? "" : body.substring(nl + 1).trim();
            }
            if (plan.title.isEmpty()) plan.title = datName.substring(datName.lastIndexOf('/') + 1);
            if (plan.lines.isEmpty()) {
                plan.error = "패치 목록(.dat)에서 칩과 패치 줄을 찾지 못했어요.";
            } else if (plan.matching() == 0) {
                plan.error = "패치가 붙을 칩이 이 롬에 없거나 원본과 달라요. 같은 게임 · 같은 판의 롬인지 확인해 주세요.";
            }
        } catch (IOException e) {
            plan.error = "zip을 읽지 못했어요: " + e.getMessage();
        }
        return plan;
    }

    // Puts the .dat and its .ips files into <dir> (replacing a bundle put there before).
    static void install(Plan plan, File patch, File dir) throws IOException {
        File temp = new File(dir.getParentFile(), dir.getName() + ".part");
        deleteTree(temp);
        if (!temp.mkdirs()) throw new IOException("폴더를 만들 수 없어요");
        if (plan.generatedDat != null) {
            try (FileOutputStream o = new FileOutputStream(new File(temp, "Korean_Translation.dat"))) {
                o.write(("\uFEFF" + plan.generatedDat).getBytes(StandardCharsets.UTF_8));
            }
        }
        try (ZipFile p = open(plan.source != null ? plan.source : patch)) {
            byte[] buffer = new byte[1 << 16];
            for (String name : plan.files) {
                ZipEntry e = p.getEntry(name);
                if (e == null) continue;
                String rel = name.substring(plan.datDir.length());
                File out = new File(temp, rel);
                if (!out.getCanonicalPath().startsWith(temp.getCanonicalPath() + File.separator)) continue;
                out.getParentFile().mkdirs();
                try (InputStream in = p.getInputStream(e); FileOutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) o.write(buffer, 0, n);
                }
            }
        } catch (IOException e) {
            deleteTree(temp);
            throw e;
        }
        deleteTree(dir);
        if (!temp.renameTo(dir)) {
            deleteTree(temp);
            throw new IOException("패치를 넣지 못했어요");
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        file.delete();
    }
}
