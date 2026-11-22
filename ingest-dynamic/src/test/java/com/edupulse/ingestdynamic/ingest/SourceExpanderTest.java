package com.edupulse.ingestdynamic.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceExpanderTest {

    @Test
    void urlSourceIsFetchedAndStoredUnderItsOwnUrl() {
        assertEquals(List.of(new FetchTarget("https://example.com/a", "https://example.com/a")),
                SourceExpander.expand(SourceSpec.ofUrl("  https://example.com/a ")));
    }

    @Test
    void repositoryDefaultsToTheReadmeOnMain() {
        assertEquals(List.of(new FetchTarget(
                        "https://raw.githubusercontent.com/pgvector/pgvector/main/README.md",
                        "https://github.com/pgvector/pgvector/blob/main/README.md")),
                SourceExpander.expand(SourceSpec.ofRepo("pgvector/pgvector", null, null)));
    }

    @Test
    void repositoryWithRefAndPathsGivesOneTargetPerFile() {
        List<FetchTarget> targets = SourceExpander.expand(
                SourceSpec.ofRepo("spring-projects/spring-ai", "v2.0.1", List.of("README.md", "docs/getting-started.md")));
        assertEquals(2, targets.size());
        assertEquals("https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.1/docs/getting-started.md", targets.get(1).fetchUrl());
        assertEquals("https://github.com/spring-projects/spring-ai/blob/v2.0.1/docs/getting-started.md", targets.get(1).sourceUrl());
    }

    @Test
    void invalidSourcesAreRejected() {
        List<SourceSpec> invalid = List.of(
                new SourceSpec("https://example.com", "owner/repo", null, null), // both
                new SourceSpec(" ", null, null, null),                         // neither
                SourceSpec.ofRepo("owner", null, null),                          // no name
                SourceSpec.ofRepo("../etc/passwd", null, null),
                SourceSpec.ofRepo("owner/repo", "a..b", null),
                SourceSpec.ofRepo("owner/repo", null, List.of("../secret.md")),
                SourceSpec.ofRepo("owner/repo", null, List.of("/absolute.md")),
                SourceSpec.ofRepo("owner/repo", null, List.of("docs/a b.md")),
                SourceSpec.ofRepo("owner/repo", null, List.of("README.md?token=x")));
        for (SourceSpec spec : invalid) {
            assertThrows(IllegalArgumentException.class, () -> SourceExpander.expand(spec), String.valueOf(spec));
        }
        assertThrows(IllegalArgumentException.class, () -> SourceExpander.expand(null));
    }
}
