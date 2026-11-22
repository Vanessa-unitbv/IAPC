package com.edupulse.ragcommon.chunking;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfTextExtractorTest {

    /** Builds a small PDF in memory with one line of text per page. */
    private static byte[] pdfWithPages(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsEveryPageOfAPdfInMemory() throws IOException {
        byte[] pdf = pdfWithPages("Deposits are guaranteed up to 100000 EUR", "Card fees are listed on the second page");
        List<StructureAwareSplitter.PageText> pages = new PdfTextExtractor().extract(pdf, "remote.pdf");
        assertEquals(2, pages.size());
        assertEquals(1, pages.get(0).page());
        assertEquals("Deposits are guaranteed up to 100000 EUR", pages.get(0).text());
        assertEquals(2, pages.get(1).page());
        assertEquals("Card fees are listed on the second page", pages.get(1).text());
    }

    @Test
    void rejectsBytesThatAreNotAPdf() {
        byte[] html = "<html><body>Not a PDF</body></html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new PdfTextExtractor().extract(html, "fake.pdf"));
    }
}
