package com.edupulse.ragcommon.chunking;

import java.util.regex.Pattern;

/**
 * Normalizes text extracted from PDFs so that chunks contain no broken or garbled characters.
 */
public final class TextCleaner {

    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern CONCATENATED_HEADING = Pattern.compile("(\\p{Lu}{2,})(?=\\p{Lu}\\p{Ll})");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    /** UTF-8 text decoded as Latin-1 shows up as pairs like "Ã¢", "â\u0080\u0099", "È\u009B". */
    private static final Pattern MOJIBAKE = Pattern.compile("[\\u00C2\\u00C3\\u00C4\\u00C5\\u00C8\\u00E2][\\u0080-\\u00BF]");

    private TextCleaner() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw
                .replace("­", "")          // soft hyphen
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace(' ', ' ')
                // ligatures
                .replace("ﬀ", "ff").replace("ﬁ", "fi").replace("ﬂ", "fl")
                .replace("ﬃ", "ffi").replace("ﬄ", "ffl")
                // Romanian: cedilla variants -> comma-below (the correct Unicode forms)
                .replace('ş', 'ș').replace('Ş', 'Ș')
                .replace('ţ', 'ț').replace('Ţ', 'Ț');

        // any other control character (NUL, C1 controls, ...) is noise
        t = CONTROL_CHARS.matcher(t).replaceAll("");

        t = joinHyphenatedLines(t);
        t = CONCATENATED_HEADING.matcher(t).replaceAll("$1 ");

        StringBuilder sb = new StringBuilder(t.length());
        for (String line : t.split("\n", -1)) {
            // collapse runs of spaces/tabs. Wide gaps are NOT turned into " | ": a gap alone does not prove
            // a table, and the guess produced stray pipes inside ordinary text and table rows.
            String normalized = line.replaceAll("[ \\t]+", " ").stripTrailing();
            sb.append(normalized).append('\n');
        }
        return MANY_BLANK_LINES.matcher(sb).replaceAll("\n\n").strip();
    }

    /**
     * Handles a line that ends with "letter-" and is followed by a line starting in lowercase.
     * <ul>
     *   <li>"exam-\nple" -> "example" (hyphen dropped, it was only a line-break hyphen)</li>
     *   <li>"e-\nmoney" -> "e-money" (prefix shorter than 3 letters: real hyphen, kept)</li>
     *   <li>URLs / e-mail addresses wrapped at a hyphen keep the hyphen</li>
     * </ul>
     * Known limit: a real compound split at the line end ("well-\nknown") is joined as "wellknown".
     */
    static String joinHyphenatedLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            while (i + 1 < lines.length && endsWithLetterHyphen(line) && startsWithLowercase(lines[i + 1])) {
                boolean urlLike = line.contains("/") || line.contains("@");
                boolean dropHyphen = !urlLike && lettersBeforeFinalHyphen(line) >= 3;
                line = (dropHyphen ? line.substring(0, line.length() - 1) : line) + lines[i + 1];
                i++;
            }
            out.append(line);
            if (i + 1 < lines.length) {
                out.append('\n');
            }
            i++;
        }
        return out.toString();
    }

    private static boolean endsWithLetterHyphen(String line) {
        int n = line.length();
        return n >= 2 && line.charAt(n - 1) == '-' && Character.isLetter(line.charAt(n - 2));
    }

    private static boolean startsWithLowercase(String line) {
        return !line.isEmpty() && Character.isLowerCase(line.charAt(0));
    }

    private static int lettersBeforeFinalHyphen(String line) {
        int count = 0;
        for (int k = line.length() - 2; k >= 0 && Character.isLetter(line.charAt(k)); k--) {
            count++;
        }
        return count;
    }

    /** Number of Unicode replacement characters (U+FFFD), a sign of a broken font encoding. */
    public static int garbledCount(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '�') {
                n++;
            }
        }
        return n;
    }

    public static boolean hasControlChars(String text) {
        return text != null && CONTROL_CHARS.matcher(text).find();
    }

    public static boolean looksMojibake(String text) {
        return text != null && MOJIBAKE.matcher(text).find();
    }
}