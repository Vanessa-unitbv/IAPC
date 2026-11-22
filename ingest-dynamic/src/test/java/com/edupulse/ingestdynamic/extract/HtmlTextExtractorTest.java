package com.edupulse.ingestdynamic.extract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTextExtractorTest {

    private final HtmlTextExtractor extractor = new HtmlTextExtractor();

    private HtmlTextExtractor.HtmlText extract(String html) throws IOException {
        return extractor.extract(html.getBytes(StandardCharsets.UTF_8), "UTF-8", "https://example.com/page");
    }

    private static final String BANK_PAGE = """
            <html><head><title>Deposit guarantee</title>
              <script>var tracking = 1;</script><style>.x { color: red }</style></head>
            <body>
              <div id="cookie-consent">We use cookies. <button>Accept all cookies</button></div>
              <header class="site-header"><a href="/">Home</a> <a href="/about">About us</a></header>
              <nav><ul><li>Menu item one</li><li>Menu item two</li></ul></nav>
              <main>
                <h1>Deposit guarantee scheme</h1>
                <p>Deposits are protected up to <b>100,000 EUR</b> per depositor.</p>
                <div class="share-buttons">Share on social media</div>
                <ul><li>Current accounts</li><li>Savings accounts</li></ul>
                <table><tr><th>Product</th><th>Fee</th></tr><tr><td>Card</td><td>5 lei</td></tr></table>
                <aside>Related articles sidebar</aside>
              </main>
              <footer>Copyright 2026 Example Bank. All rights reserved.</footer>
              <div class="newsletter-popup">Subscribe to our newsletter</div>
            </body></html>
            """;

    // ---- success paths ----

    @Test
    void keepsTheMainContentWithItsStructure() throws IOException {
        var page = extract(BANK_PAGE);
        assertEquals("Deposit guarantee", page.title());
        String text = page.text();
        assertTrue(text.contains("Deposit guarantee scheme\n\nDeposits are protected up to 100,000 EUR per depositor."), text);
        assertTrue(text.contains("- Current accounts\n- Savings accounts"), text);
        assertTrue(text.contains("Product | Fee\nCard | 5 lei"), text);
    }

    @Test
    void removesNavigationFootersCookieBannersAndScripts() throws IOException {
        String text = extract(BANK_PAGE).text();
        for (String boilerplate : new String[] {"cookies", "Home", "About us", "Menu item", "Share on social",
                "sidebar", "Copyright", "newsletter", "tracking", "color"}) {
            assertFalse(text.contains(boilerplate), "should be removed: " + boilerplate + "\n" + text);
        }
    }

    @Test
    void withoutMainElementUsesTheBodyAndStillDropsBoilerplate() throws IOException {
        String text = extract("""
                <html><body>
                  <div id="cookieBanner">This site uses cookies</div>
                  <nav>Products | Contact</nav>
                  <div class="content"><p>Monthly account fee: 10 lei.</p></div>
                  <div role="contentinfo">Legal footer</div>
                </body></html>
                """).text();
        assertEquals("Monthly account fee: 10 lei.", text);
    }

    @Test
    void keepsTheHeaderOfAnArticle() throws IOException {
        String text = extract("""
                <body><article><header><h1>Fee changes 2026</h1></header><p>New fees apply from March.</p></article>
                <footer>Site footer</footer></body>
                """).text();
        assertEquals("Fee changes 2026\n\nNew fees apply from March.", text);
    }

    @Test
    void detectsTheCharsetWhenTheServerDoesNotSendOne() throws IOException {
        byte[] html = "<html><head><meta charset=\"utf-8\"></head><body><p>Comisioane în lei și euro</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        assertEquals("Comisioane în lei și euro", extractor.extract(html, null, "https://example.com/").text());
    }

    @Test
    void removesWikiMaintenanceBoxesAndHatnotes() throws IOException {
        String text = extract("""
                <html><body><main>
                  <div class="hatnote navigation-not-searchable">For other uses, see Deposit (disambiguation).</div>
                  <table class="box-Multiple_issues plainlinks metadata ambox ambox-content"><tr>
                    <td class="mbox-image"><img src="warning.png"></td>
                    <td class="mbox-text"><div>This article has multiple issues.</div></td>
                  </tr></table>
                  <p>Deposit insurance protects depositors.</p>
                </main></body></html>
                """).text();
        assertEquals("Deposit insurance protects depositors.", text);
    }

    @Test
    void emptyCellsDoNotLeaveColumnSeparators() throws IOException {
        String text = extract("""
                <main><table>
                  <tr><td><img src="icon.png"></td><td>Only text cell</td></tr>
                  <tr><td></td><td>Second</td> <td>Third</td></tr>
                </table></main>
                """).text();
        assertEquals("Only text cell\nSecond | Third", text);
    }

    // ---- failure path ----

    @Test
    void pageWithOnlyBoilerplateHasNoText() throws IOException {
        var page = extract("<html><head><title>Menu</title></head><body><nav>Home</nav><footer>Contact</footer></body></html>");
        assertEquals("", page.text());
    }
}
