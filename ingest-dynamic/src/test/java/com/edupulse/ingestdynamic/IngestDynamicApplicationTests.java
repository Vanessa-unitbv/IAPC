package com.edupulse.ingestdynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the full context, so it needs PostgreSQL (pgvector) and an LLM key.
 * Skipped by default so that `mvn verify` passes without infrastructure; run it with RUN_INTEGRATION_TESTS=true.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class IngestDynamicApplicationTests {

	@Test
	void contextLoads() {
	}

}
