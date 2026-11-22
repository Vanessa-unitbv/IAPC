package com.edupulse.ragcommon;

import java.util.Map;

/**
 * Which version of the documents a query may see. Every retrieval uses it, so old versions never leak into
 * answers by accident:
 * <ul>
 *   <li>by default only the latest version ({@code is_latest = true});</li>
 *   <li>with an explicit {@code version}, exactly that version (e.g. to read what a page said before it changed).</li>
 * </ul>
 * {@code docId} optionally restricts the query to one document.
 */
public record VersionSelector(String docId, Integer version) {

    public VersionSelector {
        if (docId != null && docId.isBlank()) {
            docId = null;
        }
        if (version != null && version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
    }

    /** The default: latest version of every document. */
    public static VersionSelector latest() {
        return new VersionSelector(null, null);
    }

    public static VersionSelector latest(String docId) {
        return new VersionSelector(docId, null);
    }

    public static VersionSelector version(String docId, int version) {
        return new VersionSelector(docId, version);
    }

    public boolean latestOnly() {
        return version == null;
    }

    /** True if a chunk with this metadata may be returned. Used by in-memory stores and tests. */
    public boolean matches(Map<String, ?> metadata) {
        if (docId != null && !docId.equals(String.valueOf(metadata.get(ChunkMetadata.DOC_ID)))) {
            return false;
        }
        if (version == null) {
            return isTrue(metadata.get(ChunkMetadata.IS_LATEST));
        }
        return version.equals(asInt(metadata.get(ChunkMetadata.VERSION)));
    }

    private static boolean isTrue(Object value) {
        return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
