package com.edupulse.ingestdynamic.ingest;

/** What happened to a source in the vector store. */
public enum StoreOutcome {
    /** First time this source is stored: version 1. */
    INGESTED,
    /** The content changed: stored as a new latest version; the previous version is kept. */
    NEW_VERSION,
    /** Same content, processed again with new settings (chunking, model, pipeline): the latest version is replaced in place. */
    REINDEXED,
    /** Same content and settings: nothing to do. */
    UNCHANGED
}
