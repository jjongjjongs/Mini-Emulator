package com.example.minigba;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

// Names to show for arcade romsets instead of their short ZIP names, from assets/titles.bin
// (built by cores/gen-titles.py): the Korean title when there is one, else the DAT's
// English description. Clones get their set name added so versions stay apart.
final class GameTitles {
    private static Map<String, String> titles;
    // English DAT descriptions and parent sets, for finding box art by name.
    private static Map<String, String> descriptions;
    private static Map<String, String> parents;

    private GameTitles() {
    }

    // The title for this set name, or null when the set is unknown.
    static String lookup(Context context, String setName) {
        return load(context).get(setName.toLowerCase(java.util.Locale.US));
    }

    // The DAT descriptions of this set and of its parent (when a clone), or an empty array.
    static String[] descriptions(Context context, String setName) {
        String name = setName.toLowerCase(java.util.Locale.US);
        load(context);
        String own = descriptions.get(name);
        if (own == null) {
            return new String[0];
        }
        String parent = parents.get(name);
        String parentDescription = parent == null ? null : descriptions.get(parent);
        return parentDescription == null ? new String[]{own} : new String[]{own, parentDescription};
    }

    private static synchronized Map<String, String> load(Context context) {
        if (titles == null) {
            Map<String, String> map = new HashMap<>();
            Map<String, String> descriptionMap = new HashMap<>();
            Map<String, String> parentMap = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(context.getAssets().open("titles.bin")), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length < 4) {
                        continue;
                    }
                    String name = fields[0];
                    boolean clone = !"-".equals(fields[1]);
                    String korean = fields[3];
                    if (!fields[2].isEmpty()) {
                        descriptionMap.put(name, fields[2]);
                    }
                    if (clone) {
                        parentMap.put(name, fields[1]);
                    }
                    if (!"-".equals(korean)) {
                        map.put(name, clone ? korean + " (" + name + ")" : korean);
                    } else if (!fields[2].isEmpty()) {
                        map.put(name, fields[2]);
                    }
                }
            } catch (IOException e) {
                // Without the table the ZIP names are shown as before.
            }
            descriptions = descriptionMap;
            parents = parentMap;
            titles = map;
        }
        return titles;
    }
}
