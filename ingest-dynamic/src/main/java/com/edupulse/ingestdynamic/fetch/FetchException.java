package com.edupulse.ingestdynamic.fetch;

import java.io.IOException;

/** A source could not be fetched (blocked, HTTP error, wrong content type, too large, ...). */
public class FetchException extends IOException {

    private static final long serialVersionUID = 1L;

    public FetchException(String message) {
        super(message);
    }
}
