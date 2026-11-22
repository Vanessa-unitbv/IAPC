package com.edupulse.ingestdynamic.fetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against a local HTTP server, so HTTPS and private-network checks are relaxed for 127.0.0.1 only. */
class SafeHttpFetcherTest {

    private static final int MAX_BYTES = 1000;
    private static final byte[] PAGE = "<html><body><p>Fees</p></body></html>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PDF = "%PDF-1.7 fake pdf body".getBytes(StandardCharsets.US_ASCII);

    private HttpServer server;
    private SafeHttpFetcher fetcher;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", ex -> respond(ex, 200, "text/html; charset=UTF-8", PAGE));
        server.createContext("/redirect", ex -> redirect(ex, "/page"));
        server.createContext("/redirect-out", ex -> redirect(ex, "https://evil.example.org/steal"));
        server.createContext("/loop", ex -> redirect(ex, "/loop"));
        server.createContext("/missing", ex -> respond(ex, 404, "text/html", "not found".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/image", ex -> respond(ex, 200, "image/png", new byte[] {1, 2, 3}));
        server.createContext("/no-type", ex -> respond(ex, 200, null, PAGE));
        server.createContext("/declared-big", ex -> respond(ex, 200, "text/plain", new byte[MAX_BYTES + 1]));
        server.createContext("/streamed-big", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0); // chunked: no Content-Length
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[MAX_BYTES * 3]);
            } catch (IOException ignored) {
                // the client stops reading at the limit
            }
        });
        server.createContext("/octet-pdf", ex -> respond(ex, 200, "application/octet-stream", PDF));
        server.createContext("/octet-other", ex -> respond(ex, 200, "application/octet-stream", new byte[] {0, 1, 2, 3, 4, 5}));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        UrlGuard guard = new UrlGuard(List.of("127.0.0.1"), false, false);
        fetcher = new SafeHttpFetcher(guard, MAX_BYTES, Duration.ofSeconds(5), 3);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            ex.getResponseHeaders().add("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        } catch (IOException ignored) {
            // the client may close early when it rejects the response
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    // ---- success paths ----

    @Test
    void fetchesAnAllowedPage() throws Exception {
        var res = fetcher.fetch(base + "/page");
        assertEquals("text/html", res.contentType());
        assertEquals("UTF-8", res.charset());
        assertArrayEquals(PAGE, res.body());
        assertTrue(res.finalUrl().toString().endsWith("/page"));
    }

    @Test
    void followsRedirectsAndReportsTheFinalUrl() throws Exception {
        var res = fetcher.fetch(base + "/redirect");
        assertTrue(res.finalUrl().toString().endsWith("/page"));
        assertArrayEquals(PAGE, res.body());
    }

    @Test
    void acceptsAPdfServedAsOctetStream() throws Exception {
        assertEquals("application/pdf", fetcher.fetch(base + "/octet-pdf").contentType());
    }

    // ---- failure paths ----

    @Test
    void firstUrlIsCheckedByTheGuard() {
        assertThrows(UrlGuard.BlockedUrlException.class, () -> fetcher.fetch("http://evil.example.org/"));
    }

    @Test
    void redirectOutsideTheAllowlistIsBlocked() {
        assertThrows(UrlGuard.BlockedUrlException.class, () -> fetcher.fetch(base + "/redirect-out"));
    }

    @Test
    void redirectLoopStopsAfterTheLimit() {
        FetchException e = assertThrows(FetchException.class, () -> fetcher.fetch(base + "/loop"));
        assertTrue(e.getMessage().contains("Too many redirects"), e.getMessage());
    }

    @Test
    void httpErrorFails() {
        FetchException e = assertThrows(FetchException.class, () -> fetcher.fetch(base + "/missing"));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
    }

    @Test
    void disallowedOrMissingContentTypeFails() {
        for (String path : List.of("/image", "/no-type", "/octet-other")) {
            FetchException e = assertThrows(FetchException.class, () -> fetcher.fetch(base + path), path);
            assertTrue(e.getMessage().contains("Content type not allowed"), e.getMessage());
        }
    }

    @Test
    void bodiesOverTheSizeLimitFail() {
        for (String path : List.of("/declared-big", "/streamed-big")) {
            FetchException e = assertThrows(FetchException.class, () -> fetcher.fetch(base + path), path);
            assertTrue(e.getMessage().contains("Too large"), e.getMessage());
        }
    }

    @Test
    void parsesMediaTypeAndCharset() {
        assertEquals("text/html", SafeHttpFetcher.mediaType("Text/HTML; charset=\"utf-8\""));
        assertEquals("utf-8", SafeHttpFetcher.charset("Text/HTML; charset=\"utf-8\""));
        assertEquals(null, SafeHttpFetcher.charset("application/pdf"));
    }
}
