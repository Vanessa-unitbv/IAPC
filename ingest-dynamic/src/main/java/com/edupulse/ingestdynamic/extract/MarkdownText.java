package com.edupulse.ingestdynamic.extract;

import com.edupulse.ragcommon.chunking.TextCleaner;

import java.util.regex.Pattern;

/**
 * Turns Markdown (README and docs files of repositories) into plain text: badges, images, HTML tags, link targets
 * and formatting marks are removed, while headings, paragraphs, lists, tables and code stay readable.
 * Every repetition is bounded and comments are removed without a regex, so adversarial files cannot cause ReDoS.
 */
public final class MarkdownText {

    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\[\\]\\n]{0,500}\\]\\([^()\\n]{0,2000}\\)");
    /** What is left of a badge after its image is removed: "[](https://ci...)". */
    private static final Pattern EMPTY_LINK = Pattern.compile("\\[[ \\t]{0,10}\\]\\([^()\\n]{0,2000}\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\n]{1,500})\\]\\([^()\\n]{0,2000}\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile("(?m)^ {0,3}\\[[^\\[\\]\\n]{1,500}\\]:[ \\t]{0,10}\\S.*$");
    /**
     * Only real HTML elements are removed, so placeholders and generics such as {@code <module>},
     * {@code <your-token>} or {@code List<String>} stay in the text.
     */
    private static final Pattern HTML_TAG = Pattern.compile("(?i)</?(?:"
            + "a|abbr|article|aside|audio|b|bdi|bdo|big|blockquote|body|br|button|center|cite|code|col|colgroup|dd|del"
            + "|details|dfn|div|dl|dt|em|embed|figcaption|figure|font|footer|form|h[1-6]|head|header|hr|html|i|iframe"
            + "|img|input|ins|kbd|label|li|link|main|mark|meta|nav|noscript|ol|p|picture|pre|q|rp|rt|ruby|s|samp"
            + "|section|small|source|span|strike|strong|sub|summary|sup|svg|table|tbody|td|tfoot|th|thead|time|tr|tt"
            + "|u|ul|var|video|wbr)(?:[ \\t/][^<>\\n]{0,500})?>");
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br[ \\t]{0,10}/?>");
    /** {@code <https://example.com>} is a link whose text is the URL itself: keep the URL. */
    private static final Pattern AUTOLINK = Pattern.compile("<((?:https?|ftp|mailto):[^<>\\s]{1,2000})>");
    private static final Pattern HEADING_MARK = Pattern.compile("(?m)^ {0,3}#{1,6}[ \\t]+");
    private static final Pattern CODE_FENCE = Pattern.compile("(?m)^ {0,3}(?:```|~~~).*$");
    private static final Pattern TABLE_RULE = Pattern.compile(
            "(?m)^[ \\t]*(?:\\|[ \\t]*)?:?-{3,}:?[ \\t]*(?:\\|[ \\t]*:?-{3,}:?[ \\t]*)*(?:\\|[ \\t]*)?$");
    private static final Pattern STRONG_MARK = Pattern.compile("\\*\\*|__");
    private static final Pattern INLINE_CODE_MARK = Pattern.compile("`");
    /** Runs of spaces are collapsed (indentation included) so later regexes only ever see single spaces. */
    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    private MarkdownText() {
    }

    public static String toPlainText(String markdown) {
        if (markdown == null) {
            return "";
        }
        String t = markdown.replace("\r\n", "\n");
        t = removeHtmlComments(t);
        t = IMAGE.matcher(t).replaceAll("");
        t = EMPTY_LINK.matcher(t).replaceAll("");
        t = LINK.matcher(t).replaceAll("$1");
        t = REFERENCE_DEFINITION.matcher(t).replaceAll("");
        t = LINE_BREAK.matcher(t).replaceAll("\n");
        t = HTML_TAG.matcher(t).replaceAll("");
        t = AUTOLINK.matcher(t).replaceAll("$1");
        t = CODE_FENCE.matcher(t).replaceAll("");
        t = TABLE_RULE.matcher(t).replaceAll("");
        t = HEADING_MARK.matcher(t).replaceAll("");
        t = STRONG_MARK.matcher(t).replaceAll("");
        t = INLINE_CODE_MARK.matcher(t).replaceAll("");
        t = SPACES.matcher(t).replaceAll(" ");
        return TextCleaner.clean(t);
    }

    /** Removes {@code <!-- ... -->}; an unclosed comment runs to the end, as in HTML. Linear, unlike a lazy regex. */
    static String removeHtmlComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (true) {
            int start = text.indexOf("<!--", from);
            if (start < 0) {
                out.append(text, from, text.length());
                return out.toString();
            }
            out.append(text, from, start);
            int end = text.indexOf("-->", start + 4);
            if (end < 0) {
                return out.toString();
            }
            from = end + 3;
        }
    }
}
