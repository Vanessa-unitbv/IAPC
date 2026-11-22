package com.edupulse.ragcommon.chunking;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text page by page with PDFBox.
 *
 * <p>{@code sortByPosition = true} orders text by y then x. It helps when the content stream is in a strange order,
 * but on tables and multi-column pages it can interleave the lines of neighbouring cells/columns.
 * {@code false} keeps the content-stream order, which for most generated PDFs follows the logical reading order.
 * It is configurable so both can be compared on the sample PDFs (a table PDF and a multi-column PDF).
 */
public class PdfTextExtractor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfTextExtractor.class);

    private final boolean sortByPosition;

    /** Default keeps the previous behaviour (sorting enabled). */
    public PdfTextExtractor() {
        this(true);
    }

    public PdfTextExtractor(boolean sortByPosition) {
        this.sortByPosition = sortByPosition;
    }

    /** A PDF file on disk (Story 1). */
    public List<StructureAwareSplitter.PageText> extract(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return extract(doc, pdf.getFileName().toString());
        }
    }

    /** A PDF already in memory, e.g. downloaded from a URL (Story 2). {@code name} is only used in log messages. */
    public List<StructureAwareSplitter.PageText> extract(byte[] pdf, String name) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return extract(doc, name);
        }
    }

    private List<StructureAwareSplitter.PageText> extract(PDDocument doc, String name) throws IOException {
        List<StructureAwareSplitter.PageText> pages = new ArrayList<>();
        int total = doc.getNumberOfPages();
        for (int page = 1; page <= total; page++) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(sortByPosition);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = TextCleaner.clean(stripper.getText(doc));
            pages.add(new StructureAwareSplitter.PageText(page, text));
        }
        PageNoiseFilter.Result filtered = PageNoiseFilter.apply(pages);
        if (filtered.removedLines() > 0) {
            log.info("{}: removed {} repeated header/footer/page-number lines", name, filtered.removedLines());
        }
        return filtered.pages();
    }
}