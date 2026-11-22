package com.edupulse.ingestdynamic.extract;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTextTest {

    private static final String README = """
            <!-- generated, do not edit -->
            # pgvector

            [![Build Status](https://github.com/pgvector/pgvector/actions/workflows/build.yml/badge.svg)](https://github.com/pgvector/pgvector/actions)

            Open-source **vector similarity search** for [Postgres](https://www.postgresql.org/).

            ![diagram](docs/diagram.png)

            ## Installation

            ```sh
            make install
            ```

            | Type | Index |
            |------|-------|
            | vector | `hnsw` |

            <p align="center"><b>Bold html</b></p>

            [ref]: https://example.com/reference
            """;

    @Test
    void keepsTheReadableText() {
        String text = MarkdownText.toPlainText(README);
        assertTrue(text.startsWith("pgvector"), text);
        assertTrue(text.contains("Open-source vector similarity search for Postgres."), text);
        assertTrue(text.contains("Installation"), text);
        assertTrue(text.contains("make install"), text);
        assertTrue(text.contains("| vector | hnsw |"), text);
        assertTrue(text.contains("Bold html"), text);
    }

    @Test
    void removesBadgesImagesLinkTargetsHtmlAndMarkup() {
        String text = MarkdownText.toPlainText(README);
        for (String noise : new String[] {"<!--", "badge.svg", "](", "diagram.png", "```", "|---", "<p", "<b>",
                "[ref]", "https://example.com/reference", "**", "#"}) {
            assertFalse(text.contains(noise), "should be removed: " + noise + "\n" + text);
        }
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", MarkdownText.toPlainText(null));
    }

    @Test
    void adversarialInputIsProcessedQuickly() {
        String hostile = "[".repeat(50_000) + "\n" + "<!--".repeat(20_000) + "\n" + " ".repeat(50_000) + "x\n"
                + "![".repeat(20_000) + "\n" + "|" + " -".repeat(20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> MarkdownText.toPlainText(hostile));
    }
}
