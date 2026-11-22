package com.edupulse.ingestdynamic.ingest;

import java.util.List;

/**
 * One source to ingest, from configuration ({@code app.ingest.sources}) or from the request body. Exactly one of:
 * <ul>
 *   <li>{@code url}: a web page, a PDF or a text/Markdown file;</li>
 *   <li>{@code repo} ("owner/name" on GitHub) with an optional {@code ref} (default {@code main}) and
 *       {@code paths} of documentation files (default {@code README.md}).</li>
 * </ul>
 */
public record SourceSpec(String url, String repo, String ref, List<String> paths) {

    public static SourceSpec ofUrl(String url) {
        return new SourceSpec(url, null, null, null);
    }

    public static SourceSpec ofRepo(String repo, String ref, List<String> paths) {
        return new SourceSpec(null, repo, ref, paths);
    }
}
