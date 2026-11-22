package com.edupulse.ingestdynamic.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Turns a {@link SourceSpec} into the documents to download, validating it on the way. */
public final class SourceExpander {

    static final String RAW_GITHUB = "https://raw.githubusercontent.com/";
    static final String GITHUB = "https://github.com/";

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9._/-]{1,100}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._/-]{1,300}");

    private SourceExpander() {
    }

    /** @throws IllegalArgumentException if the spec is invalid */
    public static List<FetchTarget> expand(SourceSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Empty source");
        }
        boolean hasUrl = notBlank(spec.url());
        boolean hasRepo = notBlank(spec.repo());
        if (hasUrl == hasRepo) {
            throw new IllegalArgumentException("A source needs exactly one of 'url' or 'repo'");
        }
        if (hasUrl) {
            String url = spec.url().strip();
            return List.of(new FetchTarget(url, url));
        }

        String repo = spec.repo().strip();
        if (!REPO.matcher(repo).matches() || repo.contains("..")) {
            throw new IllegalArgumentException("Invalid repository, expected owner/name: " + repo);
        }
        String ref = notBlank(spec.ref()) ? spec.ref().strip() : "main";
        if (!REF.matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("Invalid ref: " + ref);
        }
        List<String> paths = spec.paths() == null || spec.paths().isEmpty() ? List.of("README.md") : spec.paths();
        List<FetchTarget> targets = new ArrayList<>();
        for (String p : paths) {
            String path = p == null ? "" : p.strip();
            if (!PATH.matcher(path).matches() || path.startsWith("/") || path.contains("..")) {
                throw new IllegalArgumentException("Invalid path in " + repo + ": " + p);
            }
            targets.add(new FetchTarget(
                    RAW_GITHUB + repo + "/" + ref + "/" + path,
                    GITHUB + repo + "/blob/" + ref + "/" + path));
        }
        return targets;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
