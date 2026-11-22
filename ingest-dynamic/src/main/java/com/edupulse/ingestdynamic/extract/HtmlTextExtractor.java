package com.edupulse.ingestdynamic.extract;

import com.edupulse.ragcommon.chunking.TextCleaner;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a web page into clean text: removes boilerplate (navigation, headers, footers, sidebars, cookie banners,
 * scripts and forms), keeps the main content and preserves its structure as paragraphs, list items and table rows,
 * so the structure-aware splitter can cut it at natural boundaries.
 */
public final class HtmlTextExtractor {

    /** Title and cleaned text of a page. {@code title} may be empty. */
    public record HtmlText(String title, String text) {
    }

    /** Never content, wherever they appear. Scripts and other active content are dropped here. */
    private static final String ALWAYS_REMOVE = String.join(", ",
            "script", "style", "noscript", "template", "iframe", "object", "embed", "svg", "canvas",
            "form", "button", "input", "select", "textarea", "dialog", "nav", "aside", "menu",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
            "[role=search]", "[role=dialog]", "[role=alertdialog]",
            "[hidden]", "[aria-hidden=true]", "[style*=display:none]", "[style*=display: none]",
            "sup.reference", ".mw-editsection");

    /** id or class tokens that mark boilerplate blocks, matched on whole "-"/"_"-separated parts. */
    private static final Pattern NOISE_TOKEN = Pattern.compile(
            "(?:^|[-_])(?:cookies?|consent|gdpr|banner|navbar|navbox|breadcrumbs?|sidebar|footer|header|menu"
                    + "|newsletter|subscribe|share|social|popup|modal|overlay|advert|ads|promo|noprint|catlinks|toc"
                    // wiki message boxes (ambox, ombox, mbox-small, ...), hatnotes ("For other uses, see ...")
                    + "|[a-z]?mbox|hatnote|metadata)"
                    + "(?:[-_]|$)",
            Pattern.CASE_INSENSITIVE);

    /** Cookie and consent banners are often named in camelCase ("cookieBanner"), so these match anywhere. */
    private static final Pattern CONSENT_ANYWHERE = Pattern.compile("cookie|consent|gdpr", Pattern.CASE_INSENSITIVE);

    /** Blocks separated by a blank line, so they become separate paragraphs for the splitter. */
    private static final Set<String> PARAGRAPH_TAGS = Set.of(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "dl", "table", "blockquote", "pre",
            "section", "article", "figure");

    private static final Set<String> CELL_TAGS = Set.of("td", "th");

    private static final Pattern SPACES = Pattern.compile("[ \\t]{2,}");

    /**
     * @param html    raw bytes of the page
     * @param charset charset from the Content-Type header, or {@code null} to let jsoup detect it
     * @param baseUri URL of the page, used to resolve relative links
     */
    public HtmlText extract(byte[] html, String charset, String baseUri) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(html), charset, baseUri);
        String title = doc.title().strip();

        doc.select(ALWAYS_REMOVE).remove();
        // page headers and footers go, but an article's own header (title, date) stays
        for (Element el : doc.select("header, footer")) {
            if (el.closest("article") == null) {
                el.remove();
            }
        }

        Element root = contentRoot(doc);
        List<Element> noise = new ArrayList<>();
        for (Element el : root.select("[id], [class]")) {
            if (el != root && looksLikeBoilerplate(el)) {
                noise.add(el);
            }
        }
        noise.forEach(Element::remove);

        return new HtmlText(title, toText(root));
    }

    /** The element that holds the page's main content: main, then a single article, then the body. */
    private static Element contentRoot(Document doc) {
        Element main = doc.selectFirst("main, [role=main]");
        if (main != null) {
            return main;
        }
        Elements articles = doc.select("article");
        return articles.size() == 1 ? articles.first() : doc.body();
    }

    private static boolean looksLikeBoilerplate(Element el) {
        if (matchesNoise(el.id())) {
            return true;
        }
        for (String cls : el.classNames()) {
            if (matchesNoise(cls)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesNoise(String name) {
        return !name.isEmpty() && (NOISE_TOKEN.matcher(name).find() || CONSENT_ANYWHERE.matcher(name).find());
    }

    private static String toText(Element root) {
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    sb.append(text.text());
                } else if (node instanceof Element el) {
                    String tag = el.normalName();
                    switch (tag) {
                        case "br", "tr" -> breakLine(sb, 1);
                        case "li" -> {
                            breakLine(sb, 1);
                            sb.append("- ");
                        }
                        case "td", "th" -> {
                            // no separator after an empty cell (e.g. an icon), so no line is left with a lone "|"
                            if (el.elementSiblingIndex() > 0 && currentLineHasText(sb)) {
                                sb.append(" | ");
                            }
                        }
                        default -> {
                            if (el.isBlock()) {
                                breakLine(sb, PARAGRAPH_TAGS.contains(tag) ? 2 : 1);
                            }
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el && el.isBlock() && !CELL_TAGS.contains(el.normalName())) {
                    breakLine(sb, PARAGRAPH_TAGS.contains(el.normalName()) ? 2 : 1);
                }
            }
        }, root);

        StringBuilder tidy = new StringBuilder(sb.length());
        for (String line : sb.toString().split("\n", -1)) {
            tidy.append(SPACES.matcher(line.strip()).replaceAll(" ")).append('\n');
        }
        return TextCleaner.clean(tidy.toString());
    }

    private static boolean currentLineHasText(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0 && sb.charAt(i) != '\n'; i--) {
            if (!Character.isWhitespace(sb.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Ends the text with at least {@code newlines} line breaks (none at the very start). */
    private static void breakLine(StringBuilder sb, int newlines) {
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') {
            end--;
        }
        sb.setLength(end);
        if (end == 0) {
            return;
        }
        int existing = 0;
        for (int i = end - 1; i >= 0 && sb.charAt(i) == '\n'; i--) {
            existing++;
        }
        for (int i = existing; i < newlines; i++) {
            sb.append('\n');
        }
    }
}
