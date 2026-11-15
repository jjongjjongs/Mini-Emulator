package com.example.minigba;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

// DOS 한글패치 덮어쓰기 (0.0.291): a patch that is the game's own files in their translated
// form (CM.EXE, CODE.VOL, a Hangul font, a RUN.BAT ...), in a zip. The patch's files are laid
// over the game folder they belong to (found by the names they share with the game), and the
// result is written as a new game zip; the original stays as it is.
//
// Names are read and written as their raw bytes (ISO-8859-1 here), so a Korean folder name in
// the old CP949 form comes out byte for byte the same and DOSBox Pure gives it the same DOS
// name.
final class DosPatch {
    static final class Change {
        String name;        // the file's path in the new zip (raw bytes as ISO-8859-1 chars)
        long oldSize = -1;  // -1: a new file
        long newSize;
    }

    static final class Plan {
        File game;
        File patch;
        String gameDir = "";        // where the patch goes, "" or "FOLDER/" (raw)
        String patchPrefix = "";    // the patch's own folder left out, "" or "FOLDER/" (raw)
        final List<Change> replaced = new ArrayList<>();
        final List<Change> added = new ArrayList<>();
        int identical;
        int untouched;
        String startName;           // a new .BAT / .EXE / .COM at the patch's top, or null
        String error;               // why it can't be used, or null
    }

    private DosPatch() {
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    private static boolean plainNames(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            for (int k = 0; k < name.length(); k++) {
                if (name.charAt(k) > 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash + 1) : "";
    }

    static Plan analyze(File game, File patch) {
        Plan plan = new Plan();
        plan.game = game;
        plan.patch = patch;
        try (ZipFile g = new ZipFile(game, StandardCharsets.ISO_8859_1); ZipFile p = new ZipFile(patch, StandardCharsets.ISO_8859_1)) {
            if (!plainNames(g) || !plainNames(p)) {
                plan.error = "이 zip의 파일 이름 형식(UTF-8 표시)은 아직 덮어쓰기를 못 해요.";
                return plan;
            }
            Map<String, ZipEntry> gameFiles = new LinkedHashMap<>();
            java.util.Set<String> gameDirs = new java.util.LinkedHashSet<>();
            gameDirs.add("");
            Enumeration<? extends ZipEntry> ge = g.entries();
            while (ge.hasMoreElements()) {
                ZipEntry e = ge.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) {
                    continue;
                }
                gameFiles.put(upper(name), e);
                String dir = parentOf(name);
                while (!dir.isEmpty()) {
                    gameDirs.add(dir);
                    dir = parentOf(dir.substring(0, dir.length() - 1));
                }
            }
            List<ZipEntry> patchFiles = new ArrayList<>();
            java.util.Set<String> patchPrefixes = new java.util.LinkedHashSet<>();
            patchPrefixes.add("");
            Enumeration<? extends ZipEntry> pe = p.entries();
            boolean onlyPrograms = true;
            while (pe.hasMoreElements()) {
                ZipEntry e = pe.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                patchFiles.add(e);
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".exe") || lower.endsWith(".com") || lower.endsWith(".txt") || lower.endsWith(".doc") || lower.endsWith(".hwp"))) {
                    onlyPrograms = false;
                }
                String dir = parentOf(name);
                while (!dir.isEmpty()) {
                    patchPrefixes.add(dir);
                    dir = parentOf(dir.substring(0, dir.length() - 1));
                }
            }
            if (patchFiles.isEmpty()) {
                plan.error = "패치 zip이 비어 있어요.";
                return plan;
            }
            // The game folder and the patch's own top folder that line up the most names.
            int best = 0;
            for (String prefix : patchPrefixes) {
                for (String dir : gameDirs) {
                    int matches = 0;
                    for (ZipEntry e : patchFiles) {
                        String name = e.getName().replace('\\', '/');
                        if (!upper(name).startsWith(upper(prefix))) {
                            continue;
                        }
                        if (gameFiles.containsKey(upper(dir + name.substring(prefix.length())))) {
                            matches++;
                        }
                    }
                    if (matches > best) {
                        best = matches;
                        plan.gameDir = dir;
                        plan.patchPrefix = prefix;
                    }
                }
            }
            if (best == 0) {
                plan.error = onlyPrograms
                        ? "게임 파일과 같은 이름의 파일이 없어요. 패치 프로그램(.exe)을 실행해야 하는 방식은 덮어쓰기로 할 수 없어요."
                        : "게임 파일과 같은 이름의 파일이 없어서 어디에 덮어쓸지 모르겠어요. 같은 게임의 패치인지 확인해 주세요.";
                return plan;
            }
            java.util.Set<String> replacedKeys = new java.util.HashSet<>();
            for (ZipEntry e : patchFiles) {
                String name = e.getName().replace('\\', '/');
                if (!upper(name).startsWith(upper(plan.patchPrefix))) {
                    continue;   // outside the patch folder that lines up: left out
                }
                String rel = name.substring(plan.patchPrefix.length());
                String key = upper(plan.gameDir + rel);
                ZipEntry old = gameFiles.get(key);
                Change change = new Change();
                change.newSize = e.getSize();
                if (old != null) {
                    if (old.getSize() == e.getSize() && old.getCrc() == e.getCrc() && old.getCrc() != -1) {
                        plan.identical++;
                        continue;
                    }
                    change.name = old.getName().replace('\\', '/');
                    change.oldSize = old.getSize();
                    plan.replaced.add(change);
                    replacedKeys.add(key);
                } else {
                    change.name = plan.gameDir + rel;
                    plan.added.add(change);
                    String lower = rel.toLowerCase(Locale.ROOT);
                    if (rel.indexOf('/') < 0 && (lower.endsWith(".bat") || ((lower.endsWith(".exe") || lower.endsWith(".com")) && plan.startName == null))) {
                        if (plan.startName == null || lower.endsWith(".bat")) {
                            plan.startName = rel;
                        }
                    }
                }
            }
            plan.untouched = gameFiles.size() - replacedKeys.size();
            if (plan.replaced.isEmpty() && plan.added.isEmpty()) {
                plan.error = "패치의 파일이 게임 파일과 모두 똑같아요. 이미 적용된 게임인 것 같아요.";
            }
        } catch (IOException e) {
            plan.error = "zip을 읽지 못했어요: " + e.getMessage();
        }
        return plan;
    }

    // Writes the patched game: every game file, the patch's in place of the same names, and
    // the patch's new files in the game folder.
    static void build(Plan plan, File out) throws IOException {
        File temp = new File(out.getParentFile(), out.getName() + ".part");
        try (ZipFile g = new ZipFile(plan.game, StandardCharsets.ISO_8859_1); ZipFile p = new ZipFile(plan.patch, StandardCharsets.ISO_8859_1);
             ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temp), StandardCharsets.ISO_8859_1)) {
            Map<String, ZipEntry> fromPatch = new HashMap<>();
            Enumeration<? extends ZipEntry> pe = p.entries();
            while (pe.hasMoreElements()) {
                ZipEntry e = pe.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !upper(name).startsWith(upper(plan.patchPrefix))) {
                    continue;
                }
                fromPatch.put(upper(plan.gameDir + name.substring(plan.patchPrefix.length())), e);
            }
            byte[] buffer = new byte[1 << 16];
            java.util.Set<String> written = new java.util.HashSet<>();
            Enumeration<? extends ZipEntry> ge = g.entries();
            while (ge.hasMoreElements()) {
                ZipEntry e = ge.nextElement();
                String name = e.getName();
                String key = upper(name.replace('\\', '/'));
                ZipEntry replacement = e.isDirectory() ? null : fromPatch.get(key);
                ZipEntry copy = new ZipEntry(name);
                copy.setTime((replacement != null ? replacement : e).getTime());
                zip.putNextEntry(copy);
                if (!e.isDirectory()) {
                    try (InputStream in = replacement != null ? p.getInputStream(replacement) : g.getInputStream(e)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, n);
                        }
                    }
                }
                zip.closeEntry();
                written.add(key);
            }
            for (Map.Entry<String, ZipEntry> entry : fromPatch.entrySet()) {
                if (written.contains(entry.getKey())) {
                    continue;
                }
                ZipEntry e = entry.getValue();
                String name = e.getName().replace('\\', '/');
                ZipEntry copy = new ZipEntry(plan.gameDir + name.substring(plan.patchPrefix.length()));
                copy.setTime(e.getTime());
                zip.putNextEntry(copy);
                try (InputStream in = p.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (!temp.renameTo(out)) {
            temp.delete();
            throw new IOException("새 게임 파일을 만들지 못했어요");
        }
    }

    // The raw (ISO-8859-1) name as the zip's own text would show it (CP949, Korean).
    static String shown(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        try {
            return java.nio.charset.Charset.forName("MS949").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return raw;
        }
    }
}
