package com.edupulse.ingestdynamic.ingest;

import java.util.List;

/** Where the chunks of dynamic sources are stored. Implemented by {@link PgVectorChunkStore}; tests use a fake. */
public interface DynamicChunkStore {

    /** Fingerprint of what is stored for this document, or {@code null} if nothing is stored. */
    String currentFingerprint(String docId);

    /** Replaces the stored chunks of {@code docId} atomically, unless the fingerprint is unchanged. */
    StoreOutcome replace(String docId, String fingerprint, List<ChunkRecord> chunks);
}
