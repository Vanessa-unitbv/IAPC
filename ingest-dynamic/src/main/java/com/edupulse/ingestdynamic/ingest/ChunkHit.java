package com.edupulse.ingestdynamic.ingest;

import java.util.Map;

/** A chunk returned by a search, with its similarity score (higher is closer; may be null). */
public record ChunkHit(String text, Map<String, Object> metadata, Double score) {
}
