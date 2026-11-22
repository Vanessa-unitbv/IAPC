package com.edupulse.ragcommon.chunking;

import com.edupulse.ragcommon.chunking.StructureAwareSplitter.Chunk;
import com.edupulse.ragcommon.chunking.StructureAwareSplitter.PageText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureAwareSplitterTest {

    /** One token per word: makes the expected chunk boundaries easy to compute by hand. */
    private static final TokenCounter WORDS = text -> text == null || text.isBlank() ? 0 : text.strip().split("\\s+").length;

    /** One token per character: forces the hard-cut path for a single very long "word". */
    private static final TokenCounter CHARS = text -> text == null ? 0 : text.length();

    /** Paragraph k has 4 words and is unique, e.g. "para3 alpha beta gamma". */
    private static String paragraphs(int count) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            out.add("para" + k + " alpha beta gamma");
        }
        return String.join("\n\n", out);
    }

    // ---- failure paths ----

    @Test
    void rejectsOverlapNotSmallerThanChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> new StructureAwareSplitter(WORDS, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new StructureAwareSplitter(WORDS, 10, 15));
    }

    @Test
    void rejectsNonPositiveChunkSizeAndNegativeOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new StructureAwareSplitter(WORDS, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new StructureAwareSplitter(WORDS, 10, -1));
    }

    @Test
    void blankPagesProduceNoChunks() {
        var splitter = new StructureAwareSplitter(WORDS, 10, 2);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, ""), new PageText(2, "   \n  "), new PageText(3, null)));
        assertTrue(chunks.isEmpty());
    }

    // ---- success paths ----

    @Test
    void smallDocumentIsASingleChunk() {
        var splitter = new StructureAwareSplitter(WORDS, 50, 5);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, "Hello world.")));
        assertEquals(1, chunks.size());
        assertEquals("Hello world.", chunks.get(0).text());
        assertEquals(1, chunks.get(0).pageStart());
        assertEquals(1, chunks.get(0).pageEnd());
    }

    @Test
    void chunksNeverExceedChunkSize() {
        var splitter = new StructureAwareSplitter(WORDS, 12, 4);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, paragraphs(40))));
        assertTrue(chunks.size() > 1);
        for (Chunk c : chunks) {
            assertTrue(WORDS.count(c.text()) <= 12, "chunk too large: " + c.text());
            assertEquals(WORDS.count(c.text()), c.tokens());
        }
    }

    @Test
    void consecutiveChunksShareTheOverlap() {
        var splitter = new StructureAwareSplitter(WORDS, 12, 4);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, paragraphs(10))));
        assertTrue(chunks.size() > 1);
        for (int i = 0; i + 1 < chunks.size(); i++) {
            String[] current = chunks.get(i).text().split("\n\n");
            String[] next = chunks.get(i + 1).text().split("\n\n");
            assertEquals(current[current.length - 1], next[0], "chunk " + (i + 1) + " should start with the tail of chunk " + i);
        }
    }

    @Test
    void noTextIsLostOrDuplicatedWithoutOverlap() {
        var splitter = new StructureAwareSplitter(WORDS, 12, 0);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, paragraphs(10))));
        List<String> seen = new ArrayList<>();
        for (Chunk c : chunks) {
            seen.addAll(List.of(c.text().split("\n\n")));
        }
        assertEquals(List.of(paragraphs(10).split("\n\n")), seen);
    }

    @Test
    void chunkSpanningPagesRecordsBothPages() {
        var splitter = new StructureAwareSplitter(WORDS, 100, 10);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, "First page text."), new PageText(2, "Second page text.")));
        assertEquals(1, chunks.size());
        assertEquals(1, chunks.get(0).pageStart());
        assertEquals(2, chunks.get(0).pageEnd());
    }

    @Test
    void oversizedParagraphIsSplitAndStaysWithinLimit() {
        String longParagraph = "One two three four five. Six seven eight nine ten. Eleven twelve thirteen fourteen fifteen. "
                + "Sixteen seventeen eighteen nineteen twenty.";
        var splitter = new StructureAwareSplitter(WORDS, 6, 0);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, longParagraph)));
        assertTrue(chunks.size() >= 4);
        for (Chunk c : chunks) {
            assertTrue(WORDS.count(c.text()) <= 6, "chunk too large: " + c.text());
        }
    }

    @Test
    void singleHugeTokenRunIsHardCutWithoutLosingCharacters() {
        String run = "x".repeat(50);
        var splitter = new StructureAwareSplitter(CHARS, 10, 0);
        List<Chunk> chunks = splitter.split(List.of(new PageText(1, run)));
        StringBuilder joined = new StringBuilder();
        for (Chunk c : chunks) {
            assertTrue(c.text().length() <= 10, "piece too large: " + c.text().length());
            joined.append(c.text());
        }
        assertEquals(run, joined.toString());
    }

    @Test
    void splittingIsDeterministic() {
        // re-ingesting the same file must produce identical chunks (same chunk_index -> no duplicates)
        var splitter = new StructureAwareSplitter(WORDS, 12, 4);
        List<PageText> pages = List.of(new PageText(1, paragraphs(25)), new PageText(2, paragraphs(7)));
        assertEquals(splitter.split(pages), splitter.split(pages));
    }
}
