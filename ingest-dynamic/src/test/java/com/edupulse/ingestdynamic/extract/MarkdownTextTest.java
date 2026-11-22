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
    void keepsPlaceholdersGenericsAndAutolinks() {
        String text = MarkdownText.toPlainText("""
                Call `http://localhost:8080/<module>/run` with <your-token>.
                It returns a `List<String>` or a `Map<K, V>`.
                Docs: <https://example.com/docs>.
                """);
        assertTrue(text.contains("http://localhost:8080/<module>/run with <your-token>."), text);
        assertTrue(text.contains("List<String> or a Map<K, V>."), text);
        assertTrue(text.contains("Docs: https://example.com/docs."), text);
    }

    @Test
    void removesHtmlElementsWithAttributesAndSelfClosingTags() {
        String text = MarkdownText.toPlainText("""
                <details><summary>Show more</summary>
                Line one<br/>line two <img src="logo.png" alt="logo" />
                <DIV class="x">Upper case</DIV>
                </details>
                """);
        assertTrue(text.contains("Show more"), text);
        assertTrue(text.contains("Line one\nline two"), text);
        assertTrue(text.contains("Upper case"), text);
        for (String noise : new String[] {"<details", "<summary", "</summary", "<br", "<img", "logo.png", "<DIV",
                "</DIV", "</details"}) {
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
