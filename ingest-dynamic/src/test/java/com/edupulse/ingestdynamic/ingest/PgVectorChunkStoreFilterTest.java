package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ragcommon.ChunkMetadata;
import com.edupulse.ragcommon.VersionSelector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The filter sent to pgvector must express the same rule as VersionSelector#matches. */
class PgVectorChunkStoreFilterTest {

    private final FilterExpressionBuilder b = new FilterExpressionBuilder();

    @Test
    void defaultFilterKeepsOnlyLatestVersions() {
        assertEquals(b.eq(ChunkMetadata.IS_LATEST, true).build(),
                PgVectorChunkStore.filterFor(VersionSelector.latest()));
    }

    @Test
    void latestOfOneDocument() {
        assertEquals(b.and(b.eq(ChunkMetadata.DOC_ID, "https://example.com/a"), b.eq(ChunkMetadata.IS_LATEST, true)).build(),
                PgVectorChunkStore.filterFor(VersionSelector.latest("https://example.com/a")));
    }

    @Test
    void explicitVersionOfOneDocument() {
        assertEquals(b.and(b.eq(ChunkMetadata.DOC_ID, "https://example.com/a"), b.eq(ChunkMetadata.VERSION, 1)).build(),
                PgVectorChunkStore.filterFor(VersionSelector.version("https://example.com/a", 1)));
    }
}
