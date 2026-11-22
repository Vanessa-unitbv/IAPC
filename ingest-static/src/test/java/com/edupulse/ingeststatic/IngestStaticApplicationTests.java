package com.edupulse.ingeststatic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the full context, so it needs PostgreSQL (pgvector) and an OpenAI key.
 * Skipped by default so that `mvn test` passes without infrastructure; run it with RUN_INTEGRATION_TESTS=true.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class IngestStaticApplicationTests {

	@Test
	void contextLoads() {
	}

}
