package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ragcommon.ChunkMetadata;
import com.edupulse.ragcommon.VersionSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Stores the chunks of dynamic sources in pgvector and keeps every version (Story 2, Task 2).
 * The vector_store table is created by ingest-static. Writes run in one transaction under a per-document advisory
 * lock, so concurrent requests cannot create two latest versions.
 */
@Component
public class PgVectorChunkStore implements DynamicChunkStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorChunkStore.class);

    private static final String DOC = "metadata->>'" + ChunkMetadata.DOC_ID + "'";
    private static final String VERSION = "(metadata->>'" + ChunkMetadata.VERSION + "')::int";
    private static final String IS_LATEST = "metadata->>'" + ChunkMetadata.IS_LATEST + "' = 'true'";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    /** json or jsonb, read from the table on first use (Spring AI creates the column; we do not assume its type). */
    private volatile String metadataType;

    public PgVectorChunkStore(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LatestVersion latest(String docId) {
        List<LatestVersion> rows = jdbcTemplate.query(
                "SELECT " + VERSION + " AS version, metadata->>'" + ChunkMetadata.CHECKSUM + "' AS checksum, "
                        + "metadata->>'" + ChunkMetadata.INGEST_FINGERPRINT + "' AS fingerprint, "
                        + "metadata->>'" + ChunkMetadata.VALID_FROM + "' AS valid_from "
                        + "FROM vector_store WHERE " + DOC + " = ? AND " + IS_LATEST + " LIMIT 1",
                (rs, i) -> new LatestVersion(rs.getInt("version"), rs.getString("checksum"),
                        rs.getString("fingerprint"), rs.getString("valid_from")),
                docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional
    public void addVersion(String docId, Integer previousVersion, String supersededAt, List<ChunkRecord> chunks) {
        lock(docId);
        LatestVersion current = latest(docId);
        Integer currentVersion = current == null ? null : current.version();
        if (!java.util.Objects.equals(currentVersion, previousVersion)) {
            throw new IllegalStateException("Document changed by another request (expected version "
                    + previousVersion + ", found " + currentVersion + "): " + docId);
        }
        int superseded = 0;
        if (previousVersion != null) {
            String type = metadataType();
            superseded = jdbcTemplate.update(
                    "UPDATE vector_store SET metadata = (metadata::jsonb || jsonb_build_object('"
                            + ChunkMetadata.IS_LATEST + "', false, '" + ChunkMetadata.VALID_TO + "', ?::text))::" + type
                            + " WHERE " + DOC + " = ? AND " + VERSION + " = ?",
                    supersededAt, docId, previousVersion);
        }
        vectorStore.add(toDocuments(chunks));
        log.info("doc_id={}: stored {} chunks as the latest version; {} chunks of version {} kept as history",
                docId, chunks.size(), superseded, previousVersion);
    }

    @Override
    @Transactional
    public void replaceVersion(String docId, int version, List<ChunkRecord> chunks) {
        lock(docId);
        int deleted = jdbcTemplate.update(
                "DELETE FROM vector_store WHERE " + DOC + " = ? AND " + VERSION + " = ?", docId, version);
        vectorStore.add(toDocuments(chunks));
        log.info("doc_id={}: re-indexed version {} ({} old chunks replaced by {})", docId, version, deleted, chunks.size());
    }

    @Override
    public List<ChunkHit> search(String query, int topK, VersionSelector selector) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterFor(selector))
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(d -> new ChunkHit(d.getText(), d.getMetadata(), d.getScore()))
                .toList();
    }

    /** The same rule as {@link VersionSelector#matches}, as a filter the vector store applies before ranking. */
    static Filter.Expression filterFor(VersionSelector selector) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op versionRule = selector.latestOnly()
                ? b.eq(ChunkMetadata.IS_LATEST, true)
                : b.eq(ChunkMetadata.VERSION, selector.version());
        return selector.docId() == null
                ? versionRule.build()
                : b.and(b.eq(ChunkMetadata.DOC_ID, selector.docId()), versionRule).build();
    }

    private void lock(String docId) {
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, docId);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("Source is being ingested by another request: " + docId);
        }
    }

    private String metadataType() {
        if (metadataType == null) {
            String type = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema() "
                            + "AND table_name = 'vector_store' AND column_name = 'metadata'",
                    String.class);
            metadataType = "jsonb".equals(type == null ? "" : type.toLowerCase(Locale.ROOT)) ? "jsonb" : "json";
        }
        return metadataType;
    }

    private static List<Document> toDocuments(List<ChunkRecord> chunks) {
        return chunks.stream().map(c -> new Document(c.text(), c.metadata())).toList();
    }
}
