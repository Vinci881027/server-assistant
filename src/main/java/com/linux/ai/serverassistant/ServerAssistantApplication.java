package com.linux.ai.serverassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Enable scheduled tasks (SecureCredentialStore automatically cleans up expired passwords)
public class ServerAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServerAssistantApplication.class, args);
	}

}
