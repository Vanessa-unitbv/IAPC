package com.edupulse.ingestdynamic.config;

import com.edupulse.ingestdynamic.fetch.SafeHttpFetcher;
import com.edupulse.ingestdynamic.fetch.UrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class FetchConfig {

    private static final Logger log = LoggerFactory.getLogger(FetchConfig.class);

    @Bean
    public SafeHttpFetcher safeHttpFetcher(DynamicIngestProperties props) {
        UrlGuard guard = new UrlGuard(props.getAllowedDomains());
        if (guard.allowedDomains().isEmpty()) {
            log.warn("INGEST_ALLOWED_DOMAINS is empty: no source can be fetched until domains are allowed");
        } else {
            log.info("Allowed domains for dynamic ingestion: {}", guard.allowedDomains());
        }
        return new SafeHttpFetcher(guard, props.getMaxSizeMb() * 1024L * 1024L,
                Duration.ofSeconds(props.getTimeoutSeconds()), props.getMaxRedirects());
    }
}
