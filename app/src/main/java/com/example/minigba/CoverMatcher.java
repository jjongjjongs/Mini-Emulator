package com.example.minigba;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Picks the libretro box-art name that best fits a ROM's file name. Plain Java so it can be
// tested off the device.
final class CoverMatcher {
    private CoverMatcher() {
    }

    // "Pokemon - Emerald Version (USA, Europe)" -> "pokemon emerald version"
    static String title(String name) {
        String text = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        text = text.replaceAll("\\([^)]*\\)|\\[[^]]*\\]|\\{[^}]*\\}", " ");
        text = text.toLowerCase(Locale.US).replace("&", " and ").replace("'", "");
        text = text.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        // "Legend of Zelda, The - ..." and "The Legend of Zelda - ..." read the same.
        text = text.replaceAll("^the ", "").replaceAll(" the( |$)", " ").trim();
        return text;
    }

    private static Set<String> words(String title) {
        Set<String> set = new HashSet<>();
        for (String word : title.split(" ")) {
            if (!word.isEmpty() && !word.equals("version") && !word.equals("edition") && !word.equals("of")) {
                set.add(word);
            }
        }
        return set;
    }

    // Regions named in the file's tags, in its short ("(U)") or full ("(USA)") spelling.
    private static List<String> regions(String name) {
        List<String> result = new ArrayList<>();
        String lower = name.toLowerCase(Locale.US);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(lower);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[,\\s]+")) {
                switch (part) {
                    case "u": case "usa": case "us": result.add("usa"); break;
                    case "e": case "europe": case "eu": result.add("europe"); break;
                    case "j": case "japan": case "jp": result.add("japan"); break;
                    case "k": case "korea": case "kr": result.add("korea"); break;
                    case "w": case "world": result.add("world"); break;
                    default: break;
                }
            }
        }
        return result;
    }

    // Small bonus for the cover of the same region, then the common ones, and against
    // betas, demos and the like.
    private static int regionScore(String candidate, List<String> wanted) {
        String lower = candidate.toLowerCase(Locale.US);
        int score = 0;
        for (String region : wanted) {
            if (lower.contains(region)) {
                score += 40;
            }
        }
        if (lower.contains("usa")) {
            score += 12;
        } else if (lower.contains("world")) {
            score += 10;
        } else if (lower.contains("europe")) {
            score += 8;
        } else if (lower.contains("japan")) {
            score += 5;
        } else if (lower.contains("korea")) {
            score += 4;
        }
        if (lower.matches(".*\\((beta|proto|demo|sample|kiosk|pirate|unl|hack)[^)]*\\).*")) {
            score -= 30;
        }
        // Fewer tags: the plain release rather than a special one.
        score -= lower.split("\\(").length;
        return score;
    }

    // The best box-art name for this ROM name, or null when nothing is close enough.
    static String match(String romName, List<String> candidates) {
        List<String> titles = new ArrayList<>();
        for (String candidate : candidates) {
            titles.add(title(candidate));
        }
        return match(romName, candidates, titles);
    }

    // As above with the candidates' titles worked out once (see title()).
    static String match(String romName, List<String> candidates, List<String> titles) {
        // Release-list numbers in front ("1234 - New Super Mario Bros (K)").
        romName = romName.replaceFirst("^\\d{3,5}\\s*-\\s+", "");
        String wanted = title(romName);
        if (wanted.isEmpty()) {
            return null;
        }
        Set<String> wantedWords = words(wanted);
        List<String> regions = regions(romName);
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int k = 0; k < candidates.size(); k++) {
            String candidate = candidates.get(k);
            String have = titles.get(k);
            int score;
            if (have.equals(wanted)) {
                score = 1000;
            } else if (have.replace(" ", "").equals(wanted.replace(" ", ""))) {
                score = 950;
            } else {
                Set<String> haveWords = words(have);
                if (haveWords.isEmpty() || wantedWords.isEmpty()) {
                    continue;
                }
                int common = 0;
                for (String word : wantedWords) {
                    if (haveWords.contains(word)) {
                        common++;
                    }
                }
                int union = wantedWords.size() + haveWords.size() - common;
                // Every word of the file's title must be there, and most of the cover's.
                if (common < wantedWords.size() || common * 100 / union < 60) {
                    continue;
                }
                score = 500 + common * 300 / union;
            }
            score += regionScore(candidate, regions);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    // libretro's file names stand in '_' for characters file systems don't allow.
    static String fileName(String name) {
        return name.replaceAll("[&*/:`<>?\\\\|\"]", "_");
    }
}
