package com.edupulse.ingestdynamic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Needs no infrastructure: Eureka registration is switched off for the test. */
@SpringBootTest(properties = "eureka.client.enabled=false")
class IngestDynamicApplicationTests {

	@Test
	void contextLoads() {
	}

}
