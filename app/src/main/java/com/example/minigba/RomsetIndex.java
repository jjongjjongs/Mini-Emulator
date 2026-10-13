package com.example.minigba;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Recognises arcade ROM ZIPs by the CRC32s of their contents. FBNeo and
// MAME 2003-Plus find a game by its ZIP file name ("neobombe.zip"), so a ROM
// saved under another name has to be renamed to its romset name first.
// The index is built from the cores' DAT files by cores/gen-romsets.py.
final class RomsetIndex {
    static final class Match {
        final String core;
        final String name;
        final String parent;
        final int matched;
        final int missingOwn;
        final int missingShared;

        Match(String core, String name, String parent, int matched, int missingOwn, int missingShared) {
            this.core = core;
            this.name = name;
            this.parent = parent;
            this.matched = matched;
            this.missingOwn = missingOwn;
            this.missingShared = missingShared;
        }

        // Shared files may live in the parent ZIP next to it.
        boolean isComplete(File dir) {
            if (missingOwn > 0) {
                return false;
            }
            return missingShared == 0 || (parent != null && new File(dir, parent + ".zip").isFile());
        }
    }

    static final class CoreSets {
        final List<String> names = new ArrayList<>();
        final List<String> parents = new ArrayList<>();
        final List<int[]> own = new ArrayList<>();
        final List<int[]> shared = new ArrayList<>();
        int[] bios = new int[0];
    }

    private static Map<String, CoreSets> cores;

    private RomsetIndex() {
    }

    private static synchronized Map<String, CoreSets> load(Context context) {
        if (cores == null) {
            try {
                cores = parse(context.getAssets().open("romsets.bin"));
            } catch (IOException e) {
                // Without the index every ROM is simply left as it is.
                cores = new HashMap<>();
            }
        }
        return cores;
    }

    static Map<String, CoreSets> parse(InputStream gzipped) {
        Map<String, CoreSets> loaded = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(gzipped), StandardCharsets.UTF_8))) {
            CoreSets current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    current = new CoreSets();
                    loaded.put(line.substring(1), current);
                    continue;
                }
                if (current == null) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields[0].equals("!bios")) {
                    current.bios = parseCrcs(fields[1]);
                    Arrays.sort(current.bios);
                } else if (fields.length >= 4) {
                    current.names.add(fields[0]);
                    current.parents.add(fields[1].equals("-") ? null : fields[1]);
                    current.own.add(parseCrcs(fields[2]));
                    current.shared.add(parseCrcs(fields[3]));
                }
            }
        } catch (IOException e) {
            // A damaged index recognises nothing.
        }
        return loaded;
    }

    private static int[] parseCrcs(String field) {
        if (field.isEmpty()) {
            return new int[0];
        }
        String[] parts = field.split(",");
        int[] crcs = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            crcs[i] = (int) Long.parseLong(parts[i], 16);
        }
        return crcs;
    }

    // False when the index could not be read; ROMs are then launched unchecked.
    static boolean isAvailable(Context context) {
        return !load(context).isEmpty();
    }

    // CRCs come from the ZIP's central directory, so nothing is decompressed.
    static int[] zipCrcs(File zip) {
        List<Integer> list = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getCrc() != -1 && entry.getSize() > 0) {
                    list.add((int) entry.getCrc());
                }
            }
        } catch (IOException | RuntimeException e) {
            return new int[0];
        }
        int[] crcs = new int[list.size()];
        for (int i = 0; i < crcs.length; i++) {
            crcs[i] = list.get(i);
        }
        Arrays.sort(crcs);
        return crcs;
    }

    // Best romset for the ZIP contents, or null when too little of it matches.
    static Match identify(Context context, String core, int[] zipCrcs) {
        return identify(load(context), core, zipCrcs);
    }

    static Match identify(Map<String, CoreSets> index, String core, int[] zipCrcs) {
        CoreSets sets = index.get(core);
        if (sets == null || zipCrcs.length == 0) {
            return null;
        }
        int gameFiles = 0;
        for (int crc : zipCrcs) {
            if (Arrays.binarySearch(sets.bios, crc) < 0) {
                gameFiles++;
            }
        }
        if (gameFiles == 0) {
            return null;
        }
        int best = -1;
        int bestMatched = 0;
        int bestMissing = 0;
        for (int i = 0; i < sets.names.size(); i++) {
            int[] own = sets.own.get(i);
            int[] shared = sets.shared.get(i);
            int matched = count(own, zipCrcs) + count(shared, zipCrcs);
            if (matched == 0) {
                continue;
            }
            int missing = own.length + shared.length - matched;
            boolean better = matched > bestMatched
                    || (matched == bestMatched && missing < bestMissing)
                    || (matched == bestMatched && missing == bestMissing && sets.parents.get(i) == null && sets.parents.get(best) != null);
            if (better) {
                best = i;
                bestMatched = matched;
                bestMissing = missing;
            }
        }
        // Most of the ZIP has to belong to the set, so a shared sound ROM alone does not count.
        if (best < 0 || bestMatched * 2 < Math.min(gameFiles, sets.own.get(best).length + sets.shared.get(best).length)) {
            return null;
        }
        int[] own = sets.own.get(best);
        int[] shared = sets.shared.get(best);
        int ownMatched = count(own, zipCrcs);
        return new Match(core, sets.names.get(best), sets.parents.get(best), bestMatched, own.length - ownMatched, shared.length - (bestMatched - ownMatched));
    }

    private static int count(int[] crcs, int[] sortedZipCrcs) {
        int n = 0;
        for (int crc : crcs) {
            if (Arrays.binarySearch(sortedZipCrcs, crc) >= 0) {
                n++;
            }
        }
        return n;
    }
}
