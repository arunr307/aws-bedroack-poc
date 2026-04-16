package com.example.bedrock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the AWS Bedrock POC application.
 *
 * <p>This Spring Boot application demonstrates how to integrate with
 * Amazon Bedrock's Converse API to build a conversational chat interface.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>REST Chat API — stateless and stateful (multi-turn) conversation modes</li>
 *   <li>Configurable model selection via {@code application.yml}</li>
 *   <li>Centralised AWS credential and region management</li>
 * </ul>
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 *   # Update application.yml with your AWS credentials and model ID, then:
 *   mvn spring-boot:run
 * }</pre>
 */
@SpringBootApplication
public class BedrockPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(BedrockPocApplication.class, args);
    }
}
