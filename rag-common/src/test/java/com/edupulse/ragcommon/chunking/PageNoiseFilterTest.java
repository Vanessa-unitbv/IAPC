package com.edupulse.ragcommon.chunking;

import com.edupulse.ragcommon.chunking.StructureAwareSplitter.PageText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageNoiseFilterTest {

    private static final String FOOTER = "Banca Exemplu SA - document public";

    private static List<PageText> pagesWithFooter(int count) {
        List<PageText> pages = new ArrayList<>();
        for (int p = 1; p <= count; p++) {
            pages.add(new PageText(p, "Body text of page " + p + "\nFee table row " + p + "\n" + FOOTER));
        }
        return pages;
    }

    // ---- success paths ----

    @Test
    void footerRepeatedOnMostPagesIsRemoved() {
        PageNoiseFilter.Result result = PageNoiseFilter.apply(pagesWithFooter(5));
        assertEquals(5, result.removedLines());
        for (PageText page : result.pages()) {
            assertFalse(page.text().contains(FOOTER));
            assertTrue(page.text().startsWith("Body text of page " + page.page()));
        }
    }

    @Test
    void standalonePageNumbersAreRemoved() {
        List<PageText> pages = List.of(
                new PageText(1, "12\nIntroduction to the fee schedule"),
                new PageText(2, "Page 3\nConditions for card payments"),
                new PageText(3, "Exchange rates apply\n3 / 40"),
                new PageText(4, "Terms and conditions\n— 8 —"),
                new PageText(5, "Rates\n– 12 –"),
                new PageText(6, "Fees\n- 9 -"));
        PageNoiseFilter.Result result = PageNoiseFilter.apply(pages);
        assertEquals(6, result.removedLines());
        assertEquals("Introduction to the fee schedule", result.pages().get(0).text());
        assertEquals("Conditions for card payments", result.pages().get(1).text());
        assertEquals("Exchange rates apply", result.pages().get(2).text());
        assertEquals("Terms and conditions", result.pages().get(3).text());
        assertEquals("Rates", result.pages().get(4).text());
        assertEquals("Fees", result.pages().get(5).text());
    }

    // ---- failure / edge paths: content must never be removed by mistake ----

    @Test
    void repeatedLineIsKeptWhenThereAreTooFewPages() {
        PageNoiseFilter.Result result = PageNoiseFilter.apply(pagesWithFooter(2));
        assertEquals(0, result.removedLines());
        assertTrue(result.pages().get(0).text().contains(FOOTER));
    }

    @Test
    void repeatedLineInTheBodyOfThePageIsKept() {
        String repeatedBodyLine = "Comision administrare cont curent";
        List<PageText> pages = new ArrayList<>();
        for (int p = 1; p <= 4; p++) {
            StringBuilder sb = new StringBuilder();
            for (int line = 1; line <= 9; line++) {
                sb.append(line == 5 ? repeatedBodyLine : "Line " + line + " of page " + p).append('\n');
            }
            pages.add(new PageText(p, sb.toString().strip()));
        }
        PageNoiseFilter.Result result = PageNoiseFilter.apply(pages);
        assertEquals(0, result.removedLines());
        for (PageText page : result.pages()) {
            assertTrue(page.text().contains(repeatedBodyLine));
        }
    }

    @Test
    void tableRowsThatDifferOnlyByANumberAreKept() {
        List<PageText> pages = new ArrayList<>();
        for (int p = 1; p <= 4; p++) {
            pages.add(new PageText(p, "Retragere numerar ATM " + p + " lei\nOther content " + p));
        }
        assertEquals(0, PageNoiseFilter.apply(pages).removedLines());
    }
}
