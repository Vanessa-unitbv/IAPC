package com.edupulse.ingestdynamic.ingest;

import com.edupulse.ragcommon.VersionSelector;

import java.util.List;

/**
 * Where the chunks of dynamic sources are stored, with every version kept. Implemented by {@link PgVectorChunkStore};
 * tests use an in-memory fake. Both write operations are atomic: a query never sees two latest versions of a
 * document, none, or a mix of old and new chunks.
 */
public interface DynamicChunkStore {

    /** The latest stored version of a document. */
    record LatestVersion(int version, String checksum, String fingerprint, String validFrom) {
    }

    /** The latest version of {@code docId}, or {@code null} if the document has never been stored. */
    LatestVersion latest(String docId);

    /**
     * Stores {@code chunks} as the new latest version. If {@code previousVersion} is not null, that version stays in
     * the store with {@code is_latest=false} and {@code valid_to=supersededAt}.
     *
     * @throws IllegalStateException if another request changed the document in the meantime
     */
    void addVersion(String docId, Integer previousVersion, String supersededAt, List<ChunkRecord> chunks);

    /** Replaces the chunks of an existing version in place (same content, new processing settings). */
    void replaceVersion(String docId, int version, List<ChunkRecord> chunks);

    /** Semantic search restricted by {@code selector}: by default only latest versions. */
    List<ChunkHit> search(String query, int topK, VersionSelector selector);
}
