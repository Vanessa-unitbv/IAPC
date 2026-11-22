package com.edupulse.ragcommon;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSelectorTest {

    private static final Map<String, Object> V1_OLD = Map.of(
            ChunkMetadata.DOC_ID, "https://example.com/a", ChunkMetadata.VERSION, 1, ChunkMetadata.IS_LATEST, false);
    private static final Map<String, Object> V2_LATEST = Map.of(
            ChunkMetadata.DOC_ID, "https://example.com/a", ChunkMetadata.VERSION, 2, ChunkMetadata.IS_LATEST, true);
    private static final Map<String, Object> OTHER_DOC = Map.of(
            ChunkMetadata.DOC_ID, "https://example.com/b", ChunkMetadata.VERSION, 1, ChunkMetadata.IS_LATEST, true);

    @Test
    void defaultSelectsOnlyTheLatestVersion() {
        VersionSelector latest = VersionSelector.latest();
        assertTrue(latest.latestOnly());
        assertTrue(latest.matches(V2_LATEST));
        assertTrue(latest.matches(OTHER_DOC));
        assertFalse(latest.matches(V1_OLD));
    }

    @Test
    void explicitVersionSelectsExactlyThatVersion() {
        VersionSelector v1 = VersionSelector.version("https://example.com/a", 1);
        assertTrue(v1.matches(V1_OLD));
        assertFalse(v1.matches(V2_LATEST));
        assertFalse(v1.matches(OTHER_DOC));
    }

    @Test
    void acceptsValuesStoredAsTextOrOtherNumberTypes() {
        Map<String, Object> fromJson = Map.of(ChunkMetadata.DOC_ID, "d", ChunkMetadata.VERSION, 3L, ChunkMetadata.IS_LATEST, "true");
        assertTrue(VersionSelector.latest("d").matches(fromJson));
        assertTrue(VersionSelector.version("d", 3).matches(fromJson));
    }

    @Test
    void rejectsVersionsBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> VersionSelector.version("d", 0));
    }
}
