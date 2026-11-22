package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ingestdynamic.config.DynamicIngestProperties;
import com.edupulse.ingestdynamic.extract.HtmlTextExtractor;
import com.edupulse.ingestdynamic.extract.MarkdownText;
import com.edupulse.ingestdynamic.fetch.ResourceFetcher;
import com.edupulse.ingestdynamic.fetch.ResourceFetcher.FetchedResource;
import com.edupulse.ragcommon.Checksums;
import com.edupulse.ragcommon.ChunkMetadata;
import com.edupulse.ragcommon.VersionSelector;
import com.edupulse.ragcommon.chunking.JTokkitTokenCounter;
import com.edupulse.ragcommon.chunking.PdfTextExtractor;
import com.edupulse.ragcommon.chunking.StructureAwareSplitter;
import com.edupulse.ragcommon.chunking.StructureAwareSplitter.PageText;
import com.edupulse.ragcommon.chunking.TextCleaner;
import com.edupulse.ragcommon.chunking.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Story 2: ingests web pages, remote PDFs and repository docs (Task 1) and keeps their versions (Task 2).
 * Each source is fetched safely, reduced to clean text, split into chunks and stored with its source URL and fetch
 * date. A failing source is logged and reported, and the rest of the batch continues.
 *
 * <p>Versions: when the content of a source changes, the new content becomes the latest version and the old one
 * stays in the store ({@code is_latest=false}, {@code valid_to}). When only the processing settings change, the
 * latest version is re-indexed in place. Queries see only latest versions unless a version is asked for.
 */
@Service
public class DynamicIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DynamicIngestionService.class);

    private final ResourceFetcher fetcher;
    private final DynamicChunkStore store;
    private final DynamicIngestProperties props;
    private final HtmlTextExtractor htmlExtractor = new HtmlTextExtractor();
    private final PdfTextExtractor pdfExtractor = new PdfTextExtractor();
    private final TokenCounter tokenCounter = new JTokkitTokenCounter();

    public DynamicIngestionService(ResourceFetcher fetcher, DynamicChunkStore store, DynamicIngestProperties props) {
        this.fetcher = fetcher;
        this.store = store;
        this.props = props;
    }

    /** Extracted content of one source. {@code title} may be null. */
    private record Extracted(String title, List<PageText> pages) {
    }

    public IngestionReport ingest(List<SourceSpec> sources) {
        List<String> ingested = new ArrayList<>();
        List<String> newVersions = new ArrayList<>();
        List<String> reindexed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<IngestionReport.Failure> failed = new ArrayList<>();

        batch:
        for (SourceSpec spec : sources) {
            List<FetchTarget> targets;
            try {
                targets = SourceExpander.expand(spec);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid source {}: {}", spec, e.getMessage());
                failed.add(new IngestionReport.Failure(String.valueOf(spec), e.getMessage()));
                continue;
            }
            for (FetchTarget target : targets) {
                try {
                    switch (ingestOne(target)) {
                        case INGESTED -> ingested.add(target.sourceUrl());
                        case NEW_VERSION -> newVersions.add(target.sourceUrl());
                        case REINDEXED -> reindexed.add(target.sourceUrl());
                        case UNCHANGED -> unchanged.add(target.sourceUrl());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Dynamic ingestion interrupted at {}", target.sourceUrl());
                    failed.add(new IngestionReport.Failure(target.sourceUrl(), "Interrupted"));
                    break batch;
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.error("Ingestion failed for {}: {}", target.sourceUrl(), reason, e);
                    failed.add(new IngestionReport.Failure(target.sourceUrl(), reason));
                }
            }
        }

        log.info("Dynamic ingestion finished: {} new, {} new versions, {} re-indexed, {} unchanged, {} failed",
                ingested.size(), newVersions.size(), reindexed.size(), unchanged.size(), failed.size());
        return new IngestionReport(ingested, newVersions, reindexed, unchanged, failed, props.getEmbeddingModel(),
                Instant.now().toString());
    }

    StoreOutcome ingestOne(FetchTarget target) throws IOException, InterruptedException {
        log.info("Fetching {}", target.fetchUrl());
        FetchedResource resource = fetcher.fetch(target.fetchUrl());
        String docId = target.sourceUrl();

        String checksum = Checksums.sha256(resource.body());
        String fingerprint = Checksums.sha256(checksum + "|" + props.getChunkSize() + "|" + props.getChunkOverlap()
                + "|" + props.getEmbeddingModel() + "|" + props.getPipelineVersion());
        DynamicChunkStore.LatestVersion latest = store.latest(docId);
        if (latest != null && fingerprint.equals(latest.fingerprint())) {
            log.info("Skipping unchanged source: {} (version {}, checksum={})", docId, latest.version(), checksum);
            return StoreOutcome.UNCHANGED;
        }

        Extracted content = extract(resource, target);
        var splitter = new StructureAwareSplitter(tokenCounter, props.getChunkSize(), props.getChunkOverlap());
        List<StructureAwareSplitter.Chunk> chunks = splitter.split(content.pages());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No extractable text in " + docId + " (" + resource.contentType() + ")");
        }

        String fetchedAt = resource.fetchedAt().toString();
        String ingestedAt = Instant.now().toString();

        // new document -> version 1; new content -> next version; same content, new settings -> same version again
        StoreOutcome outcome;
        int version;
        String validFrom;
        if (latest == null) {
            outcome = StoreOutcome.INGESTED;
            version = 1;
            validFrom = fetchedAt;
        } else if (checksum.equals(latest.checksum())) {
            outcome = StoreOutcome.REINDEXED;
            version = latest.version();
            validFrom = latest.validFrom() == null ? fetchedAt : latest.validFrom();
        } else {
            outcome = StoreOutcome.NEW_VERSION;
            version = latest.version() + 1;
            validFrom = fetchedAt;
        }
        List<ChunkRecord> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            Map<String, Object> md = new HashMap<>();
            md.put(ChunkMetadata.DOC_ID, docId);
            md.put(ChunkMetadata.VERSION, version);
            md.put(ChunkMetadata.IS_LATEST, true);
            md.put(ChunkMetadata.SOURCE, docId);
            md.put(ChunkMetadata.SOURCE_TYPE, ChunkMetadata.SOURCE_TYPE_DYNAMIC);
            md.put(ChunkMetadata.SOURCE_URL, docId);
            if (content.title() != null && !content.title().isBlank()) {
                md.put(ChunkMetadata.TITLE, content.title());
            }
            md.put(ChunkMetadata.FETCHED_AT, fetchedAt);
            md.put(ChunkMetadata.VALID_FROM, validFrom);
            md.put(ChunkMetadata.PAGE, c.pageStart());
            md.put(ChunkMetadata.PAGE_END, c.pageEnd());
            md.put(ChunkMetadata.CHUNK_INDEX, i);
            md.put(ChunkMetadata.TOTAL_CHUNKS, chunks.size());
            md.put(ChunkMetadata.CHECKSUM, checksum);
            md.put(ChunkMetadata.INGEST_FINGERPRINT, fingerprint);
            md.put(ChunkMetadata.EMBEDDING_MODEL, props.getEmbeddingModel());
            md.put(ChunkMetadata.CHUNK_SIZE, props.getChunkSize());
            md.put(ChunkMetadata.CHUNK_OVERLAP, props.getChunkOverlap());
            md.put(ChunkMetadata.INGESTED_AT, ingestedAt);
            records.add(new ChunkRecord(c.text(), md));
        }

        switch (outcome) {
            case INGESTED -> store.addVersion(docId, null, null, records);
            case NEW_VERSION -> store.addVersion(docId, latest.version(), fetchedAt, records);
            case REINDEXED -> store.replaceVersion(docId, version, records);
            default -> throw new IllegalStateException("Unexpected outcome " + outcome);
        }
        log.info("{}: {} version {} ({} chunks from {} bytes of {})", docId, outcome, version, records.size(),
                resource.body().length, resource.contentType());
        return outcome;
    }

    /**
     * Semantic search over the stored chunks. By default only latest versions are searched; with {@code version}
     * (optionally with {@code docId}) exactly that version is searched, e.g. to see what a page said before.
     */
    public List<ChunkHit> search(String query, int topK, String docId, Integer version) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("The query (q) must not be empty");
        }
        if (topK < 1 || topK > 50) {
            throw new IllegalArgumentException("k must be between 1 and 50");
        }
        VersionSelector selector = version == null ? VersionSelector.latest(docId) : VersionSelector.version(docId, version);
        List<ChunkHit> hits = store.search(query.strip(), topK, selector);
        log.info("Search \"{}\" ({}): {} hits", query.strip(), selector, hits.size());
        return hits;
    }

    private Extracted extract(FetchedResource resource, FetchTarget target) throws IOException {
        return switch (resource.contentType()) {
            case "application/pdf" -> new Extracted(null, pdfExtractor.extract(resource.body(), target.sourceUrl()));
            case "text/html", "application/xhtml+xml" -> {
                var html = htmlExtractor.extract(resource.body(), resource.charset(), resource.finalUrl().toString());
                yield new Extracted(html.title(), List.of(new PageText(1, html.text())));
            }
            default -> {
                String text = new String(resource.body(), charsetOrUtf8(resource.charset()));
                text = isMarkdown(resource) ? MarkdownText.toPlainText(text) : TextCleaner.clean(text);
                yield new Extracted(null, List.of(new PageText(1, text)));
            }
        };
    }

    private static boolean isMarkdown(FetchedResource resource) {
        String path = resource.finalUrl().getPath() == null ? "" : resource.finalUrl().getPath().toLowerCase(Locale.ROOT);
        return resource.contentType().contains("markdown") || path.endsWith(".md") || path.endsWith(".markdown");
    }

    private static Charset charsetOrUtf8(String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
