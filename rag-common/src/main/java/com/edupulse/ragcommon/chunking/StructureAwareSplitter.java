package com.edupulse.ragcommon.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Structure-aware, token-based splitter with real overlap.
 *
 * <p>Text is cut into small atomic segments following the document structure
 * (paragraph -> line -> sentence -> word). A segment is never larger than {@code maxSegment} tokens
 * ({@code overlap} when overlap is enabled, otherwise {@code chunkSize}): a block bigger than that is split
 * further. This guarantees that the overlap tail always has at least one whole segment to repeat; with larger
 * atomic paragraphs the tail came out empty and some chunk boundaries had no overlap at all.
 * Segments are packed into chunks of at most {@code chunkSize} tokens, and the last segments of
 * each chunk (up to {@code overlap} tokens) are repeated at the start of the next one.
 * Text is rebuilt with the original separators, so a split paragraph still reads the same inside a chunk.
 * Chunks may span pages; each one records page_start and page_end.
 */
public class StructureAwareSplitter {

    /** Text with the page it came from. */
    public record PageText(int page, String text) {
    }

    /** A produced chunk. */
    public record Chunk(String text, int pageStart, int pageEnd, int tokens) {
    }

    /** {@code sep} is the separator written before this segment when it is not first in its chunk. */
    private record Segment(String text, String sep, int page, int tokens) {
    }

    private static final Pattern PARAGRAPH = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?;])\\s+(?=\\p{Lu}|\\d)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final TokenCounter counter;
    private final int chunkSize;
    private final int overlap;
    /** Largest atomic segment, in tokens. */
    private final int maxSegment;

    public StructureAwareSplitter(TokenCounter counter, int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be >= 0 and < chunkSize");
        }
        this.counter = counter;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxSegment = overlap > 0 ? overlap : chunkSize;
    }

    public List<Chunk> split(List<PageText> pages) {
        List<Segment> segments = new ArrayList<>();
        for (PageText p : pages) {
            if (p.text() == null || p.text().isBlank()) {
                continue;
            }
            for (String paragraph : PARAGRAPH.split(p.text().strip())) {
                if (!paragraph.isBlank()) {
                    addAtomic(paragraph.strip(), "\n\n", p.page(), segments);
                }
            }
        }
        return pack(segments);
    }

    // ---- segmentation ----

    private void addAtomic(String block, String sep, int page, List<Segment> out) {
        int tokens = counter.count(block);
        if (tokens <= maxSegment) {
            out.add(new Segment(block, sep, page, tokens));
            return;
        }
        // too big: lines (keeps table rows intact), then sentences, then words
        String[] lines = block.split("\n");
        if (lines.length > 1) {
            boolean first = true;
            for (String line : lines) {
                if (!line.isBlank()) {
                    addAtomic(line.strip(), first ? sep : "\n", page, out);
                    first = false;
                }
            }
            return;
        }
        String[] sentences = SENTENCE.split(block);
        if (sentences.length > 1) {
            boolean first = true;
            for (String s : sentences) {
                if (!s.isBlank()) {
                    addAtomic(s.strip(), first ? sep : " ", page, out);
                    first = false;
                }
            }
            return;
        }
        String[] words = WHITESPACE.split(block);
        if (words.length > 1) {
            boolean first = true;
            for (String w : words) {
                addAtomic(w, first ? sep : " ", page, out);
                first = false;
            }
            return;
        }
        hardCut(block, sep, page, out); // one giant token run (long URL, garbage)
    }

    private void hardCut(String text, String sep, int page, List<Segment> out) {
        int step = Math.max(1, text.length() * maxSegment / Math.max(1, counter.count(text)) - 1);
        boolean first = true;
        for (int i = 0; i < text.length(); i += step) {
            String piece = text.substring(i, Math.min(text.length(), i + step));
            out.add(new Segment(piece, first ? sep : "", page, counter.count(piece)));
            first = false;
        }
    }

    // ---- packing with overlap ----

    private List<Chunk> pack(List<Segment> segments) {
        List<Chunk> chunks = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        int currentTokens = 0;
        int fresh = 0; // segments added since the last flush (excludes the overlap tail)

        for (Segment seg : segments) {
            if (!current.isEmpty() && currentTokens + seg.tokens() > chunkSize) {
                chunks.add(toChunk(current));
                current = overlapTail(current, seg.tokens());
                currentTokens = sum(current);
                fresh = 0;
            }
            current.add(seg);
            currentTokens += seg.tokens();
            fresh++;
        }
        if (fresh > 0) {
            chunks.add(toChunk(current));
        }
        return chunks;
    }

    /** Last segments of the previous chunk, up to {@code overlap} tokens, leaving room for the next segment. */
    private List<Segment> overlapTail(List<Segment> previous, int nextTokens) {
        int budget = Math.min(overlap, chunkSize - nextTokens);
        List<Segment> tail = new ArrayList<>();
        int total = 0;
        for (int i = previous.size() - 1; i >= 0; i--) {
            Segment s = previous.get(i);
            if (total + s.tokens() > budget) {
                break;
            }
            tail.add(0, s);
            total += s.tokens();
        }
        return tail;
    }

    private Chunk toChunk(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.size(); i++) {
            if (i > 0) {
                sb.append(segs.get(i).sep());
            }
            sb.append(segs.get(i).text());
        }
        return new Chunk(sb.toString(), segs.get(0).page(), segs.get(segs.size() - 1).page(), sum(segs));
    }

    private int sum(List<Segment> segs) {
        int t = 0;
        for (Segment s : segs) {
            t += s.tokens();
        }
        return t;
    }
}