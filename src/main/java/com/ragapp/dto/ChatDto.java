package com.ragapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for chat and semantic search operations.
 */
public final class ChatDto {

    private ChatDto() {}

    // ─── Requests ─────────────────────────────────────────────────────────────

    public record ChatRequest(
        @NotBlank(message = "Question is required")
        @Size(max = 4000, message = "Question must be at most 4000 characters")
        String question,

        UUID conversationId,        // null → create new conversation

        Integer topK,               // override retrieval top-k

        Double minSimilarity,       // override min similarity threshold

        List<String> documentIds,   // filter retrieval to specific docs

        Boolean stream,             // enable streaming response

        String model,               // override chat model

        Double temperature          // override temperature
    ) {}

    public record SearchRequest(
        @NotBlank(message = "Query is required")
        @Size(max = 2000)
        String query,

        Integer topK,

        Double minSimilarity,

        List<String> documentIds,

        MetadataFilter filter
    ) {}

    public record MetadataFilter(
        String author,
        String category,
        String documentName,
        List<String> tags,
        Instant uploadedAfter,
        Instant uploadedBefore
    ) {}

    // ─── Responses ────────────────────────────────────────────────────────────

    public record ChatResponse(
        UUID conversationId,
        UUID messageId,
        String answer,
        List<RetrievedChunk> sources,
        int sourcesUsed,
        long retrievalLatencyMs,
        long llmLatencyMs,
        int inputTokens,
        int outputTokens,
        String model
    ) {}

    public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String documentName,
        String chunkText,
        double similarityScore,
        int chunkIndex,
        Integer pageNumber,
        java.util.Map<String, Object> metadata
    ) {}

    public record SearchResponse(
        String query,
        List<RetrievedChunk> results,
        int totalResults,
        long latencyMs
    ) {}

    public record ConversationResponse(
        UUID conversationId,
        String title,
        List<MessageResponse> messages,
        Instant createdAt
    ) {}

    public record MessageResponse(
        UUID messageId,
        String role,
        String content,
        List<RetrievedChunk> sources,
        Instant timestamp
    ) {}
}
