package com.edupulse.retrievalpost;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Needs no infrastructure: Eureka registration is switched off for the test. */
@SpringBootTest(properties = "eureka.client.enabled=false")
class RetrievalPostApplicationTests {

	@Test
	void contextLoads() {
	}

}
