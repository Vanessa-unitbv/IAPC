package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ingestdynamic.config.DynamicIngestProperties;
import com.edupulse.ingestdynamic.fetch.FetchException;
import com.edupulse.ingestdynamic.fetch.ResourceFetcher;
import com.edupulse.ragcommon.ChunkMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole pipeline (fetch, extract, split, metadata, store) with a fake fetcher and an in-memory store. */
class DynamicIngestionServiceTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-11-22T18:00:00Z");
    private static final String PAGE_URL = "https://example.com/deposit-guarantee";
    private static final String BROKEN_URL = "https://example.com/missing";
    private static final String README_RAW = "https://raw.githubusercontent.com/owner/repo/main/README.md";
    private static final String README_PAGE = "https://github.com/owner/repo/blob/main/README.md";

    private static final String PAGE = """
            <html><head><title>Deposit guarantee</title></head><body>
              <nav>Home | Products</nav>
              <main><h1>Deposit guarantee</h1><p>Deposits are protected up to 100,000 EUR.</p></main>
              <footer>Copyright Example Bank</footer>
            </body></html>
            """;
    private static final String README = """
            # Example library

            [![CI](https://example.com/badge.svg)](https://example.com/ci)

            Install it with **one command**.
            """;

    /** Serves canned responses; any other URL fails like an HTTP 404. */
    static class FakeFetcher implements ResourceFetcher {
        final Map<String, FetchedResource> responses = new HashMap<>();

        void add(String url, String contentType, String body) {
            responses.put(url, new FetchedResource(URI.create(url), contentType, "UTF-8",
                    body.getBytes(StandardCharsets.UTF_8), FETCHED_AT));
        }

        @Override
        public FetchedResource fetch(String url) throws IOException {
            FetchedResource res = responses.get(url);
            if (res == null) {
                throw new FetchException("HTTP 404 from " + url);
            }
            return res;
        }
    }

    /** Keeps the last stored chunks per doc_id. */
    static class InMemoryStore implements DynamicChunkStore {
        final Map<String, String> fingerprints = new HashMap<>();
        final Map<String, List<ChunkRecord>> chunks = new HashMap<>();
        int writes;

        @Override
        public String currentFingerprint(String docId) {
            return fingerprints.get(docId);
        }

        @Override
        public StoreOutcome replace(String docId, String fingerprint, List<ChunkRecord> records) {
            String existing = fingerprints.put(docId, fingerprint);
            chunks.put(docId, new ArrayList<>(records));
            writes++;
            return existing == null ? StoreOutcome.INGESTED : StoreOutcome.UPDATED;
        }
    }

    private final FakeFetcher fetcher = new FakeFetcher();
    private final InMemoryStore store = new InMemoryStore();
    private final DynamicIngestProperties props = new DynamicIngestProperties();
    private final DynamicIngestionService service = new DynamicIngestionService(fetcher, store, props);

    {
        fetcher.add(PAGE_URL, "text/html", PAGE);
        fetcher.add(README_RAW, "text/plain", README);
    }

    private static final List<SourceSpec> BATCH = List.of(
            SourceSpec.ofUrl(PAGE_URL),
            SourceSpec.ofUrl(BROKEN_URL),
            SourceSpec.ofRepo("owner/repo", null, null));

    // ---- success paths ----

    @Test
    void ingestsAPageAndRepositoryDocsAndContinuesAfterAFailedSource() {
        IngestionReport report = service.ingest(BATCH);

        assertEquals(List.of(PAGE_URL, README_PAGE), report.ingested());
        assertEquals(1, report.failed().size());
        assertEquals(BROKEN_URL, report.failed().get(0).source());
        assertTrue(report.failed().get(0).error().contains("HTTP 404"));
        assertEquals(2, store.chunks.size());
    }

    @Test
    void storesSourceUrlFetchDateAndTheMetadataContract() {
        service.ingest(BATCH);
        List<ChunkRecord> chunks = store.chunks.get(PAGE_URL);
        assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> md = chunks.get(i).metadata();
            assertEquals(PAGE_URL, md.get(ChunkMetadata.DOC_ID));
            assertEquals(PAGE_URL, md.get(ChunkMetadata.SOURCE_URL));
            assertEquals(FETCHED_AT.toString(), md.get(ChunkMetadata.FETCHED_AT));
            assertEquals(FETCHED_AT.toString(), md.get(ChunkMetadata.VALID_FROM));
            assertEquals(ChunkMetadata.SOURCE_TYPE_DYNAMIC, md.get(ChunkMetadata.SOURCE_TYPE));
            assertEquals("Deposit guarantee", md.get(ChunkMetadata.TITLE));
            assertEquals(1, md.get(ChunkMetadata.VERSION));
            assertEquals(true, md.get(ChunkMetadata.IS_LATEST));
            assertEquals(i, md.get(ChunkMetadata.CHUNK_INDEX));
            assertEquals(chunks.size(), md.get(ChunkMetadata.TOTAL_CHUNKS));
            assertTrue(md.containsKey(ChunkMetadata.CHECKSUM));
            assertTrue(md.containsKey(ChunkMetadata.INGEST_FINGERPRINT));
        }
    }

    @Test
    void storedTextIsCleanOfBoilerplateAndMarkdown() {
        service.ingest(BATCH);
        String page = store.chunks.get(PAGE_URL).get(0).text();
        assertTrue(page.contains("Deposits are protected up to 100,000 EUR."), page);
        assertFalse(page.contains("Home") || page.contains("Copyright"), page);

        String readme = store.chunks.get(README_PAGE).get(0).text();
        assertTrue(readme.contains("Install it with one command."), readme);
        assertFalse(readme.contains("badge.svg") || readme.contains("**"), readme);
    }

    @Test
    void unchangedSourceIsSkippedOnTheNextRun() {
        service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        IngestionReport second = service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        assertEquals(List.of(PAGE_URL), second.unchanged());
        assertEquals(1, store.writes);
    }

    @Test
    void changedSourceIsReplaced() {
        service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        fetcher.add(PAGE_URL, "text/html", PAGE.replace("100,000", "200,000"));
        IngestionReport second = service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        assertEquals(List.of(PAGE_URL), second.updated());
        assertTrue(store.chunks.get(PAGE_URL).get(0).text().contains("200,000"));
    }

    @Test
    void newPipelineVersionReprocessesUnchangedSources() {
        service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        props.setPipelineVersion(props.getPipelineVersion() + 1);
        IngestionReport second = service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
        assertEquals(List.of(PAGE_URL), second.updated());
        assertEquals(2, store.writes);
    }

    // ---- failure paths ----

    @Test
    void invalidSourceIsReportedAndTheRestOfTheBatchRuns() {
        IngestionReport report = service.ingest(List.of(
                new SourceSpec(PAGE_URL, "owner/repo", null, null),
                SourceSpec.ofUrl(PAGE_URL)));
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).error().contains("exactly one of"));
        assertEquals(List.of(PAGE_URL), report.ingested());
    }

    @Test
    void sourceWithoutTextIsReportedAsFailed() {
        fetcher.add("https://example.com/empty", "text/html", "<html><body><nav>Only a menu</nav></body></html>");
        IngestionReport report = service.ingest(List.of(SourceSpec.ofUrl("https://example.com/empty")));
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).error().startsWith("No extractable text"), report.failed().get(0).error());
        assertTrue(store.chunks.isEmpty());
    }
}
