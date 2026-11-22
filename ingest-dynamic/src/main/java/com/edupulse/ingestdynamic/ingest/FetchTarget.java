package com.edupulse.ingestdynamic.ingest;

/**
 * One document to download.
 *
 * @param fetchUrl  where the bytes are downloaded from
 * @param sourceUrl the URL stored as doc_id, source and source_url (for repository files, the GitHub page of the file)
 */
public record FetchTarget(String fetchUrl, String sourceUrl) {
}
