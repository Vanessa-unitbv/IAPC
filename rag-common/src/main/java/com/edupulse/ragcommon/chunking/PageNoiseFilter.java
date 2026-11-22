package com.edupulse.ragcommon.chunking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes running headers/footers and page numbers.
 *
 * <p>Only the first and last few lines of each page are considered, and a line is removed only if
 * it repeats exactly on at least 60% of the pages (and at least 3). Standalone page numbers ("12", "Page 12",
 * "12 / 40") in those zones are removed too. Lines in the body of a page are never touched, so table headers
 * and numbers are safe.
 */
public final class PageNoiseFilter {

    public record Result(List<StructureAwareSplitter.PageText> pages, int removedLines) {
    }

    private static final int ZONE_TOP = 3;
    private static final int ZONE_BOTTOM = 4;
    private static final int MIN_LINE_LENGTH = 8;
    private static final int MIN_PAGES = 3;
    private static final double MIN_SHARE = 0.6;
    private static final Pattern PAGE_NUMBER =
            Pattern.compile("^[\\s\\-–—]*(page\\s+)?\\d{1,4}(\\s*(/|of)\\s*\\d{1,4})?[\\s\\-–—]*$",
                    Pattern.CASE_INSENSITIVE);

    private PageNoiseFilter() {
    }

    public static Result apply(List<StructureAwareSplitter.PageText> pages) {
        // 1) count in how many pages each candidate line appears (in the header/footer zones)
        Map<String, Integer> pagesWithLine = new HashMap<>();
        List<String[]> allLines = new ArrayList<>();
        List<Set<Integer>> zones = new ArrayList<>();
        for (var page : pages) {
            String[] lines = page.text().split("\n", -1);
            allLines.add(lines);
            Set<Integer> zone = zoneIndexes(lines);
            zones.add(zone);
            Set<String> seen = new HashSet<>();
            for (int idx : zone) {
                String key = key(lines[idx]);
                if (key != null && seen.add(key)) {
                    pagesWithLine.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(MIN_PAGES, (int) Math.ceil(pages.size() * MIN_SHARE));

        // 2) rebuild the pages without the noise
        List<StructureAwareSplitter.PageText> result = new ArrayList<>();
        int removed = 0;
        for (int p = 0; p < pages.size(); p++) {
            String[] lines = allLines.get(p);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                boolean drop = false;
                if (zones.get(p).contains(i)) {
                    String stripped = lines[i].strip();
                    String key = key(lines[i]);
                    drop = PAGE_NUMBER.matcher(stripped).matches()
                            || (key != null && pagesWithLine.getOrDefault(key, 0) >= threshold);
                }
                if (drop) {
                    removed++;
                } else {
                    sb.append(lines[i]).append('\n');
                }
            }
            result.add(new StructureAwareSplitter.PageText(pages.get(p).page(), sb.toString().strip()));
        }
        return new Result(result, removed);
    }

    private static Set<Integer> zoneIndexes(String[] lines) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                nonBlank.add(i);
            }
        }
        Set<Integer> zone = new HashSet<>();
        for (int k = 0; k < Math.min(ZONE_TOP, nonBlank.size()); k++) {
            zone.add(nonBlank.get(k));
        }
        for (int k = Math.max(0, nonBlank.size() - ZONE_BOTTOM); k < nonBlank.size(); k++) {
            zone.add(nonBlank.get(k));
        }
        return zone;
    }

    /**
     * Comparison key: trimmed and lowercase, EXACT otherwise (digits are not normalized, so table rows that differ
     * only by a number, like fees, are never mistaken for a repeated header). Null if the line is too short.
     */
    private static String key(String line) {
        String s = line.strip();
        if (s.length() < MIN_LINE_LENGTH || !s.chars().anyMatch(Character::isLetter)) {
            return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
