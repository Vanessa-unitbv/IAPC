package com.edupulse.ingestdynamic.fetch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UrlGuardTest {

    /** Same rules as production, without DNS lookups, so the test runs offline. */
    private final UrlGuard noDns = new UrlGuard(List.of("example.com", "brd.ro"), true, false);

    // ---- success paths ----

    @Test
    void allowsHttpsOnAllowedDomainsAndTheirSubdomains() throws Exception {
        assertEquals("example.com", noDns.check("https://example.com/page").getHost());
        assertEquals("docs.example.com", noDns.check("https://docs.example.com/a/b?x=1").getHost());
        assertEquals("www.brd.ro", noDns.check("https://WWW.BRD.RO/_files/pdf/x.pdf").getHost().toLowerCase());
    }

    @Test
    void allowsAPublicAddressWithTheFullChecks() throws Exception {
        UrlGuard guard = new UrlGuard(List.of("8.8.8.8"));
        assertEquals("8.8.8.8", guard.check("https://8.8.8.8/").getHost());
    }

    // ---- failure paths: one per control ----

    @Test
    void rejectsPlainHttpAndOtherSchemes() {
        for (String url : List.of("http://example.com/", "ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)")) {
            assertThrows(UrlGuard.BlockedUrlException.class, () -> noDns.check(url), url);
        }
    }

    @Test
    void rejectsDomainsOutsideTheAllowlist() {
        for (String url : List.of("https://evil.com/", "https://example.com.evil.com/", "https://notexample.com/")) {
            assertThrows(UrlGuard.BlockedUrlException.class, () -> noDns.check(url), url);
        }
    }

    @Test
    void emptyAllowlistAllowsNothing() {
        UrlGuard empty = new UrlGuard(List.of(" ", ""));
        assertThrows(UrlGuard.BlockedUrlException.class, () -> empty.check("https://example.com/"));
    }

    @Test
    void rejectsMalformedUrlsAndUserInfo() {
        for (String url : List.of("https://user:pass@example.com/", "https://", "not a url", "https:///path")) {
            assertThrows(UrlGuard.BlockedUrlException.class, () -> noDns.check(url), url);
        }
        assertThrows(UrlGuard.BlockedUrlException.class, () -> noDns.check(null));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalAndMetadataAddresses() {
        List<String> hosts = List.of("localhost", "127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.1.10",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1");
        UrlGuard guard = new UrlGuard(hosts);
        for (String host : hosts) {
            String url = host.contains(":") ? "https://[" + host + "]/" : "https://" + host + "/";
            assertThrows(UrlGuard.BlockedUrlException.class, () -> guard.check(url), url);
        }
    }
}
