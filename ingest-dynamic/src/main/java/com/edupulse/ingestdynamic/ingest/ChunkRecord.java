package com.edupulse.ingestdynamic.ingest;

import java.util.Map;

/** A chunk ready to be embedded and stored: its text and its metadata (keys from {@code ChunkMetadata}). */
public record ChunkRecord(String text, Map<String, Object> metadata) {
}
