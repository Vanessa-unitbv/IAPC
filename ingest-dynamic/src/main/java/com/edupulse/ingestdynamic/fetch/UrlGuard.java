package com.edupulse.ingestdynamic.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a URL may be fetched. Checked for the first URL and again for every redirect target.
 * <ul>
 *   <li>HTTPS only, no user info in the URL;</li>
 *   <li>the host must be on the allowlist (exact name or a subdomain of it); an empty allowlist allows nothing;</li>
 *   <li>every address the host resolves to must be public: loopback, private, link-local (incl. the cloud metadata
 *       address 169.254.169.254), carrier-grade NAT, multicast and IPv6 unique-local addresses are rejected (SSRF).</li>
 * </ul>
 * Known limit: the address is checked before the connection is made, so DNS rebinding is not covered (Story 2, Task 4).
 */
public class UrlGuard {

    /** The URL is not allowed; the message says why. */
    public static class BlockedUrlException extends FetchException {
        private static final long serialVersionUID = 1L;

        public BlockedUrlException(String message) {
            super(message);
        }
    }

    private final Set<String> allowedDomains;
    private final boolean requireHttps;
    private final boolean blockPrivateNetworks;

    public UrlGuard(Collection<String> allowedDomains) {
        this(allowedDomains, true, true);
    }

    /** Only tests relax HTTPS and private networks (to fetch from a local test server). */
    UrlGuard(Collection<String> allowedDomains, boolean requireHttps, boolean blockPrivateNetworks) {
        this.allowedDomains = allowedDomains == null ? Set.of() : allowedDomains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.requireHttps = requireHttps;
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    /** Returns the parsed URL if it may be fetched, otherwise throws {@link BlockedUrlException}. */
    public URI check(String url) throws BlockedUrlException {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.strip());
        } catch (URISyntaxException e) {
            throw new BlockedUrlException("Malformed URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (requireHttps ? !scheme.equals("https") : !(scheme.equals("https") || scheme.equals("http"))) {
            throw new BlockedUrlException("Only HTTPS URLs are allowed: " + url);
        }
        if (uri.getRawUserInfo() != null) {
            throw new BlockedUrlException("User info is not allowed in URLs: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedUrlException("URL has no host: " + url);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!isAllowedDomain(host)) {
            throw new BlockedUrlException("Domain is not on the allowlist (INGEST_ALLOWED_DOMAINS): " + host);
        }
        if (blockPrivateNetworks) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                throw new BlockedUrlException("Unknown host: " + host);
            }
            for (InetAddress address : addresses) {
                if (isNonPublic(address)) {
                    throw new BlockedUrlException("Host resolves to a non-public address (" + address.getHostAddress() + "): " + host);
                }
            }
        }
        return uri;
    }

    private boolean isAllowedDomain(String host) {
        for (String domain : allowedDomains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // "this network"
                    || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT 100.64.0.0/10
                    || first >= 240;                            // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                       // unique local fc00::/7
        }
        return true;
    }
}
