package com.edupulse.ingeststatic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes the chunks of one document inside a single database transaction.
 * It is a separate bean on purpose: {@code @Transactional} only works when the method is called
 * through the Spring proxy, which does not happen for calls inside the same class.
 *
 * <p>Requirement: the VectorStore must use the same DataSource as the JdbcTemplate (the default
 * Spring Boot setup), so that delete + insert share one transaction.
 */
@Component
public class ChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChunkWriter.class);

    /** Outcome of a write attempt. */
    public enum Result { INGESTED, UPDATED, UNCHANGED }

    /** Another instance/request is already ingesting the same document. */
    public static class DocumentBusyException extends RuntimeException {
        public DocumentBusyException(String docId) {
            super("Document is being ingested by another request: " + docId);
        }
    }

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public ChunkWriter(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Replaces all chunks of {@code docId} with {@code chunks} atomically. If anything fails, the transaction
     * rolls back and the previous chunks stay intact.
     */
    @Transactional
    public Result replace(String docId, String fingerprint, List<Document> chunks) {
        Boolean locked = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, docId);
        if (!Boolean.TRUE.equals(locked)) {
            throw new DocumentBusyException(docId);
        }

        // re-check under the lock: a concurrent request may have just ingested the same content
        String existing = currentFingerprint(docId);
        if (fingerprint.equals(existing)) {
            return Result.UNCHANGED;
        }

        int deleted = jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'doc_id' = ?", docId);
        vectorStore.add(chunks);
        log.info("doc_id={}: deleted {} old chunks, stored {} new chunks", docId, deleted, chunks.size());
        return existing == null ? Result.INGESTED : Result.UPDATED;
    }

    public String currentFingerprint(String docId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT metadata->>'ingest_fingerprint' FROM vector_store WHERE metadata->>'doc_id' = ? LIMIT 1",
                    String.class, docId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // the table does not exist yet on a first run
            log.warn("Could not read fingerprint for {}: {}", docId, e.getMessage());
            return null;
        }
    }
}
