package com.edupulse.ingeststatic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class IngestStaticApplication {

	public static void main(String[] args) {
		SpringApplication.run(IngestStaticApplication.class, args);
	}

}
