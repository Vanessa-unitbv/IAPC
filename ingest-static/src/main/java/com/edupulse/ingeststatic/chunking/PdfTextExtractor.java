package com.edupulse.ingeststatic.chunking;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text page by page with PDFBox. Sorting by position keeps multi-column layouts and table rows
 * in reading order instead of the raw content-stream order.
 */
public class PdfTextExtractor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PdfTextExtractor.class);

    public List<StructureAwareSplitter.PageText> extract(Path pdf) throws IOException {
        List<StructureAwareSplitter.PageText> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int total = doc.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = TextCleaner.clean(stripper.getText(doc));
                pages.add(new StructureAwareSplitter.PageText(page, text));
            }
        }
        PageNoiseFilter.Result filtered = PageNoiseFilter.apply(pages);
        if (filtered.removedLines() > 0) {
            log.info("{}: removed {} repeated header/footer/page-number lines", pdf.getFileName(), filtered.removedLines());
        }
        return filtered.pages();
    }
}
