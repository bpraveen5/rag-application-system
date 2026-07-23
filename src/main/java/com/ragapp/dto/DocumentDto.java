package com.ragapp.dto;

import com.ragapp.entity.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTOs for Document upload, indexing, and retrieval operations.
 */
public final class DocumentDto {

    private DocumentDto() {}

    // ─── Requests ─────────────────────────────────────────────────────────────

    public record IndexRequest(
        @NotBlank(message = "Document ID is required")
        String documentId,

        String chunkingStrategy,    // fixed | recursive | semantic  (overrides yml)
        Integer chunkSize,
        Integer chunkOverlap
    ) {}

    public record MetadataUpdateRequest(
        @Size(max = 512)
        String category,

        @Size(max = 255)
        String author,

        @Size(max = 1000)
        String description,

        Map<String, Object> additionalMetadata
    ) {}

    // ─── Responses ────────────────────────────────────────────────────────────

    public record DocumentResponse(
        UUID id,
        String filename,
        String originalFilename,
        String contentType,
        Long fileSize,
        Document.DocumentStatus status,
        Integer version,
        Integer chunkCount,
        Map<String, Object> metadata,
        Instant uploadDate,
        Instant indexedAt,
        String errorMessage
    ) {}

    public record DocumentListResponse(
        java.util.List<DocumentResponse> documents,
        long totalElements,
        int totalPages,
        int currentPage,
        int pageSize
    ) {}

    public record IndexResponse(
        UUID documentId,
        String status,
        int chunksCreated,
        long processingTimeMs
    ) {}

    public record UploadResponse(
        UUID documentId,
        String filename,
        String contentType,
        Long fileSize,
        String status,
        String message
    ) {}
}
