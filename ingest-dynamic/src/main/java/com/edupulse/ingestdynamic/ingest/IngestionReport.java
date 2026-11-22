package com.edupulse.ingestdynamic.ingest;

import java.util.List;

/** Result of a batch: which sources were stored, versioned, re-indexed, skipped or failed (with the reason). */
public record IngestionReport(List<String> ingested, List<String> newVersions, List<String> reindexed,
                              List<String> unchanged, List<Failure> failed, String embeddingModel, String timestamp) {

    public record Failure(String source, String error) {
    }
}
