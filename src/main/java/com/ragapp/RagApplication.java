package com.ragapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the RAG (Retrieval-Augmented Generation) Application.
 *
 * <p>Production-ready Spring Boot 3.x application leveraging:
 * <ul>
 *   <li>Spring AI with a local Ollama LLM (llama3.2:3b) and nomic-embed-text embeddings</li>
 *   <li>PostgreSQL + pgvector for vector storage</li>
 *   <li>Redis for caching and rate-limiting</li>
 *   <li>JWT-based authentication with role-based access control</li>
 *   <li>Clean Architecture: Controller → Service → Repository / AI Layer</li>
 * </ul>
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
