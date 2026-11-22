package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ingestdynamic.config.DynamicIngestProperties;
import com.edupulse.ingestdynamic.fetch.FetchException;
import com.edupulse.ingestdynamic.fetch.ResourceFetcher;
import com.edupulse.ragcommon.ChunkMetadata;
import com.edupulse.ragcommon.VersionSelector;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole pipeline (fetch, extract, split, metadata, versions, search) with a fake fetcher and an in-memory store. */
class DynamicIngestionServiceTest {

    private static final Instant T1 = Instant.parse("2026-11-22T18:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-29T18:00:00Z");
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
    private static final String PAGE_V2 = PAGE.replace("100,000", "200,000");
    private static final String README = """
            # Example library

            [![CI](https://example.com/badge.svg)](https://example.com/ci)

            Install it with **one command**.
            """;

    /** Serves canned responses; any other URL fails like an HTTP 404. */
    static class FakeFetcher implements ResourceFetcher {
        final Map<String, FetchedResource> responses = new HashMap<>();

        void add(String url, String contentType, String body, Instant fetchedAt) {
            responses.put(url, new FetchedResource(URI.create(url), contentType, "UTF-8",
                    body.getBytes(StandardCharsets.UTF_8), fetchedAt));
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

    /** Keeps every version, with the same rules as PgVectorChunkStore; search ranks by shared words. */
    static class InMemoryStore implements DynamicChunkStore {
        final List<ChunkRecord> all = new ArrayList<>();
        int writes;

        List<ChunkRecord> chunks(String docId, int version) {
            return all.stream().filter(c -> docId.equals(c.metadata().get(ChunkMetadata.DOC_ID))
                    && Objects.equals(version, c.metadata().get(ChunkMetadata.VERSION))).toList();
        }

        List<ChunkRecord> latestChunks(String docId) {
            return all.stream().filter(c -> VersionSelector.latest(docId).matches(c.metadata())).toList();
        }

        @Override
        public LatestVersion latest(String docId) {
            return latestChunks(docId).stream().findFirst().map(c -> new LatestVersion(
                    (Integer) c.metadata().get(ChunkMetadata.VERSION),
                    (String) c.metadata().get(ChunkMetadata.CHECKSUM),
                    (String) c.metadata().get(ChunkMetadata.INGEST_FINGERPRINT),
                    (String) c.metadata().get(ChunkMetadata.VALID_FROM))).orElse(null);
        }

        @Override
        public void addVersion(String docId, Integer previousVersion, String supersededAt, List<ChunkRecord> chunks) {
            LatestVersion current = latest(docId);
            if (!Objects.equals(current == null ? null : current.version(), previousVersion)) {
                throw new IllegalStateException("Document changed by another request: " + docId);
            }
            if (previousVersion != null) {
                for (int i = 0; i < all.size(); i++) {
                    ChunkRecord c = all.get(i);
                    if (docId.equals(c.metadata().get(ChunkMetadata.DOC_ID))
                            && previousVersion.equals(c.metadata().get(ChunkMetadata.VERSION))) {
                        Map<String, Object> md = new HashMap<>(c.metadata());
                        md.put(ChunkMetadata.IS_LATEST, false);
                        md.put(ChunkMetadata.VALID_TO, supersededAt);
                        all.set(i, new ChunkRecord(c.text(), md));
                    }
                }
            }
            all.addAll(chunks);
            writes++;
        }

        @Override
        public void replaceVersion(String docId, int version, List<ChunkRecord> chunks) {
            all.removeAll(chunks(docId, version));
            all.addAll(chunks);
            writes++;
        }

        @Override
        public List<ChunkHit> search(String query, int topK, VersionSelector selector) {
            List<String> words = List.of(query.toLowerCase(Locale.ROOT).split("\\W+"));
            return all.stream()
                    .filter(c -> selector.matches(c.metadata()))
                    .map(c -> new ChunkHit(c.text(), c.metadata(),
                            (double) words.stream().filter(w -> c.text().toLowerCase(Locale.ROOT).contains(w)).count()))
                    .sorted(Comparator.comparing(ChunkHit::score).reversed())
                    .limit(topK)
                    .toList();
        }
    }

    private final FakeFetcher fetcher = new FakeFetcher();
    private final InMemoryStore store = new InMemoryStore();
    private final DynamicIngestProperties props = new DynamicIngestProperties();
    private final DynamicIngestionService service = new DynamicIngestionService(fetcher, store, props);

    {
        fetcher.add(PAGE_URL, "text/html", PAGE, T1);
        fetcher.add(README_RAW, "text/plain", README, T1);
    }

    private static final List<SourceSpec> BATCH = List.of(
            SourceSpec.ofUrl(PAGE_URL),
            SourceSpec.ofUrl(BROKEN_URL),
            SourceSpec.ofRepo("owner/repo", null, null));

    private IngestionReport ingestPage() {
        return service.ingest(List.of(SourceSpec.ofUrl(PAGE_URL)));
    }

    // ---- Task 1: ingestion ----

    @Test
    void ingestsAPageAndRepositoryDocsAndContinuesAfterAFailedSource() {
        IngestionReport report = service.ingest(BATCH);

        assertEquals(List.of(PAGE_URL, README_PAGE), report.ingested());
        assertEquals(1, report.failed().size());
        assertEquals(BROKEN_URL, report.failed().get(0).source());
        assertTrue(report.failed().get(0).error().contains("HTTP 404"));
        assertFalse(store.latestChunks(PAGE_URL).isEmpty());
        assertFalse(store.latestChunks(README_PAGE).isEmpty());
    }

    @Test
    void storesSourceUrlFetchDateAndTheMetadataContract() {
        service.ingest(BATCH);
        List<ChunkRecord> chunks = store.latestChunks(PAGE_URL);
        assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> md = chunks.get(i).metadata();
            assertEquals(PAGE_URL, md.get(ChunkMetadata.DOC_ID));
            assertEquals(PAGE_URL, md.get(ChunkMetadata.SOURCE_URL));
            assertEquals(T1.toString(), md.get(ChunkMetadata.FETCHED_AT));
            assertEquals(T1.toString(), md.get(ChunkMetadata.VALID_FROM));
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
        String page = store.latestChunks(PAGE_URL).get(0).text();
        assertTrue(page.contains("Deposits are protected up to 100,000 EUR."), page);
        assertFalse(page.contains("Home") || page.contains("Copyright"), page);

        String readme = store.latestChunks(README_PAGE).get(0).text();
        assertTrue(readme.contains("Install it with one command."), readme);
        assertFalse(readme.contains("badge.svg") || readme.contains("**"), readme);
    }

    @Test
    void unchangedSourceIsSkippedOnTheNextRun() {
        ingestPage();
        IngestionReport second = ingestPage();
        assertEquals(List.of(PAGE_URL), second.unchanged());
        assertEquals(1, store.writes);
    }

    // ---- Task 2: versioning ----

    @Test
    void changedContentBecomesANewVersionAndTheOldVersionIsKept() {
        ingestPage();
        fetcher.add(PAGE_URL, "text/html", PAGE_V2, T2);
        IngestionReport second = ingestPage();

        assertEquals(List.of(PAGE_URL), second.newVersions());
        for (ChunkRecord old : store.chunks(PAGE_URL, 1)) {
            assertEquals(false, old.metadata().get(ChunkMetadata.IS_LATEST));
            assertEquals(T1.toString(), old.metadata().get(ChunkMetadata.VALID_FROM));
            assertEquals(T2.toString(), old.metadata().get(ChunkMetadata.VALID_TO));
            assertTrue(old.text().contains("100,000"));
        }
        List<ChunkRecord> latest = store.chunks(PAGE_URL, 2);
        assertFalse(latest.isEmpty());
        for (ChunkRecord c : latest) {
            assertEquals(true, c.metadata().get(ChunkMetadata.IS_LATEST));
            assertEquals(T2.toString(), c.metadata().get(ChunkMetadata.VALID_FROM));
            assertNull(c.metadata().get(ChunkMetadata.VALID_TO));
            assertTrue(c.text().contains("200,000"));
        }
        assertFalse(store.chunks(PAGE_URL, 1).isEmpty());
    }

    @Test
    void twoVersionsDefaultQueryReturnsTheLatestAndExplicitVersionReturnsTheOld() {
        ingestPage();
        fetcher.add(PAGE_URL, "text/html", PAGE_V2, T2);
        ingestPage();

        List<ChunkHit> latest = service.search("deposits protected EUR", 5, null, null);
        assertFalse(latest.isEmpty());
        for (ChunkHit hit : latest) {
            assertEquals(2, hit.metadata().get(ChunkMetadata.VERSION));
            assertTrue(hit.text().contains("200,000"), hit.text());
        }

        List<ChunkHit> old = service.search("deposits protected EUR", 5, PAGE_URL, 1);
        assertFalse(old.isEmpty());
        for (ChunkHit hit : old) {
            assertEquals(1, hit.metadata().get(ChunkMetadata.VERSION));
            assertTrue(hit.text().contains("100,000"), hit.text());
        }
    }

    @Test
    void newSettingsReindexTheSameVersionInsteadOfCreatingANewOne() {
        ingestPage();
        props.setPipelineVersion(props.getPipelineVersion() + 1);
        fetcher.add(PAGE_URL, "text/html", PAGE, T2); // same content, fetched later
        IngestionReport second = ingestPage();

        assertEquals(List.of(PAGE_URL), second.reindexed());
        assertTrue(store.chunks(PAGE_URL, 2).isEmpty());
        for (ChunkRecord c : store.chunks(PAGE_URL, 1)) {
            assertEquals(true, c.metadata().get(ChunkMetadata.IS_LATEST));
            assertEquals(T1.toString(), c.metadata().get(ChunkMetadata.VALID_FROM)); // validity of the content unchanged
        }
        assertEquals(2, store.writes);
    }

    @Test
    void thirdVersionKeepsTheWholeHistory() {
        ingestPage();
        fetcher.add(PAGE_URL, "text/html", PAGE_V2, T2);
        ingestPage();
        fetcher.add(PAGE_URL, "text/html", PAGE.replace("100,000", "300,000"), T2.plusSeconds(60));
        IngestionReport third = ingestPage();

        assertEquals(List.of(PAGE_URL), third.newVersions());
        assertEquals(3, store.latest(PAGE_URL).version());
        assertFalse(store.chunks(PAGE_URL, 1).isEmpty());
        assertFalse(store.chunks(PAGE_URL, 2).isEmpty());
        assertEquals(1, store.latestChunks(PAGE_URL).stream()
                .map(c -> c.metadata().get(ChunkMetadata.VERSION)).distinct().count());
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
        fetcher.add("https://example.com/empty", "text/html", "<html><body><nav>Only a menu</nav></body></html>", T1);
        IngestionReport report = service.ingest(List.of(SourceSpec.ofUrl("https://example.com/empty")));
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).error().startsWith("No extractable text"), report.failed().get(0).error());
        assertTrue(store.all.isEmpty());
    }

    @Test
    void searchRejectsAnEmptyQueryOrAnInvalidK() {
        assertThrows(IllegalArgumentException.class, () -> service.search("  ", 5, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.search("fees", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.search("fees", 5, PAGE_URL, 0));
    }
}
