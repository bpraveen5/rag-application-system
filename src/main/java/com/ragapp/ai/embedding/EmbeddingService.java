package com.ragapp.ai.embedding;

import com.ragapp.config.RedisConfig;
import com.ragapp.exception.EmbeddingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wraps Spring AI's {@link EmbeddingModel} with caching, metrics, and error handling.
 *
 * <p>Cache key: SHA-256 of the input text. Embeddings are deterministic for a given
 * model + text pair so it is safe to cache indefinitely (TTL governed by Redis config).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final MeterRegistry  meterRegistry;
    private final Environment env;
    private final java.util.concurrent.ExecutorService aiExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
    // timeout for AI calls (ms) - keep small so failures surface quickly
    private final long aiCallTimeoutMs = Long.parseLong(System.getProperty("app.ai.call-timeout-ms", "10000"));

    /**
     * Generate embedding for a single text string.
     * Result is cached in Redis to avoid redundant API calls.
     */
    @Cacheable(value = RedisConfig.EMBEDDINGS_CACHE, key = "#text.hashCode()")
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new EmbeddingException("Cannot embed empty text");
        }

        // Dev-mode: return deterministic mock embeddings when enabled via env or property
        boolean mockFlag = false;
        String mockEnv = System.getenv("APP_AI_MOCK_EMBEDDINGS");
        if (mockEnv != null) mockFlag = "true".equalsIgnoreCase(mockEnv);
        if (!mockFlag) mockFlag = Boolean.parseBoolean(env.getProperty("app.ai.mock-embeddings", "false"));
        if (mockFlag) {
            log.debug("Mock embeddings enabled - returning deterministic vector");
            return mockEmbedding(text);
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.debug("Generating embedding for text of length {}", text.length());
            EmbeddingRequest  request  = new EmbeddingRequest(List.of(text), embeddingOptions());
            java.util.concurrent.Future<EmbeddingResponse> fut = aiExecutor.submit(() -> embeddingModel.call(request));
            EmbeddingResponse response;
            try {
                response = fut.get(aiCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                fut.cancel(true);
                throw new EmbeddingException("Embedding call timed out after " + aiCallTimeoutMs + "ms", te);
            }

            // Embedding.getOutput() returns float[] directly in this Spring AI version.
            float[] embedding = response.getResult().getOutput();
            log.debug("Embedding generated, dimensions={}", embedding.length);

            sample.stop(meterRegistry.timer("rag.embedding.latency", "status", "success"));
            return embedding;
        } catch (Exception ex) {
            sample.stop(meterRegistry.timer("rag.embedding.latency", "status", "error"));
            throw new EmbeddingException("Failed to generate embedding: " + ex.getMessage(), ex);
        }
    }

    /**
     * Batch-embed multiple texts in a single API call for efficiency.
     * Returns a list of embeddings in the same order as the input.
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        boolean mockFlagBatch = false;
        String mockEnvBatch = System.getenv("APP_AI_MOCK_EMBEDDINGS");
        if (mockEnvBatch != null) mockFlagBatch = "true".equalsIgnoreCase(mockEnvBatch);
        if (!mockFlagBatch) mockFlagBatch = Boolean.parseBoolean(env.getProperty("app.ai.mock-embeddings", "false"));
        if (mockFlagBatch) {
            return texts.stream().map(this::mockEmbedding).toList();
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.debug("Batch embedding {} texts", texts.size());
            EmbeddingRequest  request  = new EmbeddingRequest(texts, embeddingOptions());
            java.util.concurrent.Future<EmbeddingResponse> fut = aiExecutor.submit(() -> embeddingModel.call(request));
            EmbeddingResponse response;
            try {
                response = fut.get(aiCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                fut.cancel(true);
                throw new EmbeddingException("Batch embedding timed out after " + aiCallTimeoutMs + "ms", te);
            }

            // Embedding.getOutput() returns float[] directly in this Spring AI version.
            List<float[]> results = response.getResults().stream()
                    .map(r -> r.getOutput())
                    .toList();

            meterRegistry.counter("rag.embedding.batch.count").increment(texts.size());
            sample.stop(meterRegistry.timer("rag.embedding.batch.latency", "status", "success"));
            return results;
        } catch (Exception ex) {
            sample.stop(meterRegistry.timer("rag.embedding.batch.latency", "status", "error"));
            throw new EmbeddingException("Failed to batch embed texts: " + ex.getMessage(), ex);
        }
    }

    private float[] mockEmbedding(String text) {
        int dim = Integer.parseInt(env.getProperty("spring.ai.vectorstore.pgvector.dimensions", "768"));
        float[] v = new float[dim];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] seed = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < dim; i++) {
                int b = seed[i % seed.length] & 0xFF;
                // simple deterministic mapping into [-1,1]
                v[i] = ((b / 255.0f) * 2.0f) - 1.0f;
            }
        } catch (NoSuchAlgorithmException e) {
            // fallback - fill zeros
            for (int i = 0; i < dim; i++) v[i] = 0f;
        }
        return v;
    }

    /**
     * Serialise a float[] embedding to the PostgreSQL vector literal format,
     * e.g. "[0.1, 0.2, -0.3, …]".
     */
    public static String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Cosine similarity between two embedding vectors. */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embedding dimensions mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0.0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Builds the embedding call options. Ollama's embedding model implementation calls
     * {@code request.getOptions().getModel()} without a null check, so a non-null
     * options object must always be supplied (never {@code null} or {@code EmbeddingOptions.EMPTY}).
     */
    /**
     * NOTE: keepAlive is intentionally NOT set here. Spring AI's Ollama embeddings
     * path has a known bug (spring-projects/spring-ai#4619) where the keep-alive
     * value is serialized as an ISO-8601 duration (e.g. "PT30M") instead of the
     * Go-style duration Ollama expects (e.g. "30m"), causing every embed call to
     * fail with HTTP 400 "invalid duration". Chat's keep-alive is unaffected.
     */
    private OllamaOptions embeddingOptions() {
        String model = env.getProperty("spring.ai.ollama.embedding.options.model", "nomic-embed-text");
        return OllamaOptions.builder()
                .model(model)
                .build();
    }

}

