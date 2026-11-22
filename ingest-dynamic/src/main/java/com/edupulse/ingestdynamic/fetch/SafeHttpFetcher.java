package com.edupulse.ingestdynamic.fetch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Downloads a source with the {@link UrlGuard} checks, a timeout, a size limit and a content-type allowlist.
 * Redirects are followed by hand (at most {@code maxRedirects}) so that every target is checked by the guard too.
 */
public class SafeHttpFetcher implements ResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeHttpFetcher.class);

    static final Set<String> ALLOWED_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "application/pdf", "text/plain", "text/markdown", "text/x-markdown");
    /** Some hosts (e.g. raw.githubusercontent.com) serve PDFs as octet-stream; accepted only if the bytes are a PDF. */
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final String USER_AGENT = "IAPC-RAG-ingest/1.0 (+https://github.com/Vanessa-unitbv/IAPC)";

    private final UrlGuard guard;
    private final long maxBytes;
    private final Duration timeout;
    private final int maxRedirects;
    private final HttpClient client;

    public SafeHttpFetcher(UrlGuard guard, long maxBytes, Duration timeout, int maxRedirects) {
        this.guard = guard;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.maxRedirects = maxRedirects;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public FetchedResource fetch(String url) throws IOException, InterruptedException {
        URI current = guard.check(url);
        for (int redirects = 0; ; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/pdf,text/markdown,text/plain;q=0.9,*/*;q=0.1")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (REDIRECTS.contains(status)) {
                response.body().close();
                if (redirects >= maxRedirects) {
                    throw new FetchException("Too many redirects (more than " + maxRedirects + ") for " + url);
                }
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new FetchException("Redirect without a Location header from " + response.uri()));
                URI next = current.resolve(location);
                log.debug("Redirect {} -> {}", current, next);
                current = guard.check(next.toString());
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new FetchException("HTTP " + status + " from " + current);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String mediaType = mediaType(contentType);
            if (!ALLOWED_TYPES.contains(mediaType) && !mediaType.equals(OCTET_STREAM)) {
                response.body().close();
                throw new FetchException("Content type not allowed (" + (mediaType.isEmpty() ? "missing" : mediaType) + ") from " + current);
            }
            OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
            if (declared.isPresent() && declared.getAsLong() > maxBytes) {
                response.body().close();
                throw new FetchException("Too large (" + declared.getAsLong() + " bytes, limit " + maxBytes + ") from " + current);
            }
            byte[] body = readLimited(response.body(), current);
            if (mediaType.equals(OCTET_STREAM)) {
                if (!isPdf(body)) {
                    throw new FetchException("Content type not allowed (" + OCTET_STREAM + ", not a PDF) from " + current);
                }
                mediaType = "application/pdf";
            }
            return new FetchedResource(current, mediaType, charset(contentType), body, Instant.now());
        }
    }

    private byte[] readLimited(InputStream in, URI uri) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) {
                    throw new FetchException("Too large (more than " + maxBytes + " bytes) from " + uri);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.strip().toLowerCase(Locale.ROOT);
    }

    static String charset(String contentType) {
        for (String part : contentType.split(";")) {
            String p = part.strip();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String value = p.substring("charset=".length()).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static boolean isPdf(byte[] body) {
        return body.length >= 5 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F' && body[4] == '-';
    }
}
