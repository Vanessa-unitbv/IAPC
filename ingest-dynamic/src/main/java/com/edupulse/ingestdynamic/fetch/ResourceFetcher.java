package com.edupulse.ingestdynamic.fetch;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/** Downloads one source. Implemented by {@link SafeHttpFetcher}; tests use a fake. */
public interface ResourceFetcher {

    /**
     * A downloaded source.
     *
     * @param finalUrl    URL after redirects
     * @param contentType media type without parameters, lower case (e.g. {@code text/html}, {@code application/pdf})
     * @param charset     charset from the Content-Type header, or {@code null}
     */
    record FetchedResource(URI finalUrl, String contentType, String charset, byte[] body, Instant fetchedAt) {
    }

    FetchedResource fetch(String url) throws IOException, InterruptedException;
}
