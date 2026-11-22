package com.edupulse.retrievalpre;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class RetrievalPreApplication {

	public static void main(String[] args) {
		SpringApplication.run(RetrievalPreApplication.class, args);
	}

}
