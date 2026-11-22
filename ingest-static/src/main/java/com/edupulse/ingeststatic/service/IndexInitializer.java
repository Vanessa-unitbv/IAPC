package com.edupulse.ingeststatic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes the ingestion relies on. ingest-static is the only service that creates vector_store,
 * so it also owns these indexes.
 *
 * <p>The unique index includes the version, so Story 2 (versioning) can keep several versions of one
 * document with the same chunk_index. It is the safety net against duplicate chunks.
 */
@Component
public class IndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public IndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_vector_store_doc_id "
                    + "ON vector_store ((metadata->>'doc_id'))");
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_vector_store_doc_version_chunk "
                    + "ON vector_store ((metadata->>'doc_id'), ((metadata->>'version')::int), ((metadata->>'chunk_index')::int))");
            log.info("Ingestion indexes are in place");
        } catch (Exception e) {
            log.error("Could not create ingestion indexes (is the vector_store table created yet?): {}", e.getMessage());
        }
    }
}
