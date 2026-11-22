package com.edupulse.ingeststatic.chunking;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/** Token counter backed by jtokkit (cl100k_base, the encoding used by text-embedding-3-*). */
public class JTokkitTokenCounter implements TokenCounter {

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
    }
}
