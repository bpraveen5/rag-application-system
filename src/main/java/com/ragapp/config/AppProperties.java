package com.ragapp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Strongly-typed configuration properties bound from application.yml (app.*).
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private Security security = new Security();
    private Retrieval retrieval = new Retrieval();
    private Chunking chunking = new Chunking();
    private Document document = new Document();
    private Conversation conversation = new Conversation();
    private RateLimiting rateLimiting = new RateLimiting();
    private Async async = new Async();
    private MultiTenant multiTenant = new MultiTenant();
    private Prompts prompts = new Prompts();

    @Data
    public static class Security {
        private Jwt jwt = new Jwt();

        @Data
        public static class Jwt {
            private String secret;
            private long expirationMs = 86_400_000L;
            private long refreshExpirationMs = 604_800_000L;
        }
    }

    @Data
    public static class Retrieval {
        private int topK = 5;
        private double minSimilarity = 0.4;
        private boolean hybridSearchEnabled = true;
        private double keywordWeight = 0.3;
        private double vectorWeight = 0.7;
        private boolean rerankingEnabled = false;
        private boolean contextCompressionEnabled = true;
        private int maxContextTokens = 6000;
    }

    @Data
    public static class Chunking {
        private String strategy = "recursive";
        private int chunkSize = 1000;
        private int chunkOverlap = 200;
        private int minChunkSize = 100;
        private int maxChunkSize = 4000;
        private List<String> separators = List.of("\n\n", "\n", ". ", " ", "");
    }

    @Data
    public static class Document {
        private String storagePath = "/tmp/rag-documents";
        private List<String> allowedTypes = List.of(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain",
                "text/markdown",
                "text/html"
        );
        private int maxFileSizeMb = 100;
    }

    @Data
    public static class Conversation {
        private int maxHistoryMessages = 20;
        private int ttlHours = 24;
    }

    @Data
    public static class RateLimiting {
        private boolean enabled = true;
        private Bucket chat = new Bucket(60, 60, 60);
        private Bucket upload = new Bucket(10, 10, 60);

        public record Bucket(int capacity, int refillTokens, int refillDurationSeconds) {}
    }

    @Data
    public static class Async {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 500;
        private String threadNamePrefix = "rag-async-";
    }

    @Data
    public static class MultiTenant {
        private boolean enabled = false;
        private String headerName = "X-Tenant-ID";
    }

    @Data
    public static class Prompts {
        private String system = """
                You are a knowledgeable AI assistant. Use the provided context to answer questions accurately.
                If the answer cannot be found in the context, say so honestly.
                Always cite the source document when possible.
                Be concise, clear, and factual. Do not make up information.
                """;
        private String noContext = """
                I could not find relevant information in the knowledge base to answer your question.
                Please try rephrasing your question or uploading relevant documents.
                """;
    }
}
