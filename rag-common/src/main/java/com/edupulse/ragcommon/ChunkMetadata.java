package com.edupulse.ragcommon;

/**
 * Metadata keys stored on every chunk in {@code vector_store.metadata}. This is the contract between the stories:
 * ingestion (Stories 1 and 2) writes these keys, pre-retrieval filters on them (Story 3) and post-retrieval
 * stitches and cites with them (Story 4). Add a key here before using it anywhere else.
 */
public final class ChunkMetadata {

    // ---- identity and position (all chunks) ----

    /** Stable document id. Static PDFs use the file name. */
    public static final String DOC_ID = "doc_id";
    /** Document version, starting at 1. */
    public static final String VERSION = "version";
    /** True only for the chunks of the newest version; retrieval filters on it by default. */
    public static final String IS_LATEST = "is_latest";
    /** 0-based position of the chunk inside its document version; used for neighbor stitching. */
    public static final String CHUNK_INDEX = "chunk_index";
    /** Number of chunks in this document version. */
    public static final String TOTAL_CHUNKS = "total_chunks";
    /** First page the chunk comes from (1-based). */
    public static final String PAGE = "page";
    /** Last page the chunk comes from (1-based). */
    public static final String PAGE_END = "page_end";

    // ---- source ----

    /** Human-readable source shown in citations (file name or URL). */
    public static final String SOURCE = "source";
    /** Document title when the source has one (e.g. the HTML title of a web page). */
    public static final String TITLE = "title";
    /** {@code static} (Story 1) or {@code dynamic} (Story 2). */
    public static final String SOURCE_TYPE = "source_type";
    /** Original URL of a document fetched from the Internet (Story 2). */
    public static final String SOURCE_URL = "source_url";
    /** When a dynamic document was fetched, ISO-8601 (Story 2). */
    public static final String FETCHED_AT = "fetched_at";
    /** When this version became valid, ISO-8601 (Story 2 versioning). */
    public static final String VALID_FROM = "valid_from";
    /** When a newer version replaced this one, ISO-8601; absent on the latest version (Story 2 versioning). */
    public static final String VALID_TO = "valid_to";

    // ---- ingestion bookkeeping ----

    /** SHA-256 of the source file or fetched content. */
    public static final String CHECKSUM = "checksum";
    /** Checksum plus every setting that changes the chunks or embeddings; a different value forces re-ingestion. */
    public static final String INGEST_FINGERPRINT = "ingest_fingerprint";
    public static final String EMBEDDING_MODEL = "embedding_model";
    public static final String CHUNK_SIZE = "chunk_size";
    public static final String CHUNK_OVERLAP = "chunk_overlap";
    /** When the chunk was written, ISO-8601. */
    public static final String INGESTED_AT = "ingested_at";

    public static final String SOURCE_TYPE_STATIC = "static";
    public static final String SOURCE_TYPE_DYNAMIC = "dynamic";

    private ChunkMetadata() {
    }
}
