package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ragcommon.ChunkMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stores the chunks of dynamic sources in pgvector. The vector_store table is created by ingest-static.
 * Replacement runs in one transaction (delete + insert), so a query never sees a mix of old and new chunks.
 * Story 2, Task 2 (versioning) will keep the old version instead of deleting it.
 */
@Component
public class PgVectorChunkStore implements DynamicChunkStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorChunkStore.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public PgVectorChunkStore(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String currentFingerprint(String docId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT metadata->>'" + ChunkMetadata.INGEST_FINGERPRINT + "' FROM vector_store WHERE metadata->>'"
                        + ChunkMetadata.DOC_ID + "' = ? LIMIT 1",
                String.class, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional
    public StoreOutcome replace(String docId, String fingerprint, List<ChunkRecord> chunks) {
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, docId);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("Source is being ingested by another request: " + docId);
        }
        // re-check under the lock: a concurrent request may have just stored the same content
        String existing = currentFingerprint(docId);
        if (fingerprint.equals(existing)) {
            return StoreOutcome.UNCHANGED;
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE metadata->>'" + ChunkMetadata.DOC_ID + "' = ?", docId);
        vectorStore.add(chunks.stream().map(c -> new Document(c.text(), c.metadata())).toList());
        log.info("doc_id={}: deleted {} old chunks, stored {} new chunks", docId, deleted, chunks.size());
        return existing == null ? StoreOutcome.INGESTED : StoreOutcome.UPDATED;
    }
}
