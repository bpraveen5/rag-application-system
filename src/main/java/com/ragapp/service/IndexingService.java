package com.ragapp.service;

import com.ragapp.ai.embedding.EmbeddingService;
import com.ragapp.chunking.ChunkingService;
import com.ragapp.dto.DocumentDto;
import com.ragapp.entity.Chunk;
import com.ragapp.entity.Document;
import com.ragapp.exception.DocumentNotFoundException;
import com.ragapp.exception.InvalidFileException;
import com.ragapp.parser.DocumentParserService;
import com.ragapp.repository.ChunkRepository;
import com.ragapp.repository.DocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the full document indexing pipeline:
 * Upload → Parse → Chunk → Embed → Store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final DocumentRepository  documentRepository;
    private final ChunkRepository     chunkRepository;
    private final DocumentParserService parserService;
    private final ChunkingService     chunkingService;
    private final EmbeddingService    embeddingService;
    private final AuditService        auditService;
    private final MeterRegistry       meterRegistry;

    // ─── Upload ────────────────────────────────────────────────────────────────

    @Transactional
    public DocumentDto.UploadResponse upload(MultipartFile file, UUID userId, String tenantId) {
        validateFile(file);

        Document doc = Document.builder()
                .filename(sanitizeFilename(file.getOriginalFilename()))
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .userId(userId)
                .tenantId(tenantId)
                .status(Document.DocumentStatus.UPLOADED)
                .metadata(Map.of(
                        "uploadedBy", userId.toString(),
                        "originalName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown"
                ))
                .build();

        doc = documentRepository.save(doc);
        auditService.log(userId, tenantId, "DOCUMENT_UPLOAD", "Document", doc.getId().toString(), "SUCCESS");

        log.info("Document uploaded: id={}, filename={}, size={}", doc.getId(), doc.getFilename(), doc.getFileSize());

        return new DocumentDto.UploadResponse(
                doc.getId(), doc.getOriginalFilename(), doc.getContentType(),
                doc.getFileSize(), doc.getStatus().name(),
                "Document uploaded successfully. Trigger indexing via POST /documents/index");
    }

    // ─── Indexing (Async) ─────────────────────────────────────────────────────

    @Async("ragTaskExecutor")
    @Transactional
    public void indexAsync(UUID documentId, String strategyOverride,
                           Integer chunkSizeOverride, Integer overlapOverride, UUID userId) {
        try {
            indexInternal(documentId, strategyOverride, chunkSizeOverride, overlapOverride);
            auditService.log(userId, null, "DOCUMENT_INDEX", "Document", documentId.toString(), "SUCCESS");
        } catch (Exception ex) {
            log.error("Async indexing failed for document {}: {}", documentId, ex.getMessage(), ex);
            documentRepository.updateStatus(documentId, Document.DocumentStatus.FAILED, ex.getMessage());
        }
    }

    @Transactional
    public DocumentDto.IndexResponse indexSync(UUID documentId, String strategyOverride,
                                               Integer chunkSizeOverride, Integer overlapOverride) {
        return indexInternal(documentId, strategyOverride, chunkSizeOverride, overlapOverride);
    }

    // ─── Core Pipeline ────────────────────────────────────────────────────────

    private DocumentDto.IndexResponse indexInternal(UUID documentId, String strategyOverride,
                                                     Integer chunkSizeOverride, Integer overlapOverride) {
        long startMs = System.currentTimeMillis();

        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (doc.getStatus() == Document.DocumentStatus.INDEXED) {
            log.warn("Document {} is already indexed, re-indexing", documentId);
            chunkRepository.deleteAllByDocumentId(documentId);
        }

        documentRepository.updateStatus(documentId, Document.DocumentStatus.PROCESSING, null);

        // 1. Parse — load raw bytes from original upload (stored in metadata)
        String rawText = extractTextFromDocument(doc);
        if (rawText == null || rawText.isBlank()) {
            throw new InvalidFileException("Document does not contain extractable text");
        }

        // 2. Chunk
        List<String> textChunks = chunkingService.chunk(rawText, strategyOverride, chunkSizeOverride, overlapOverride);
        if (textChunks.isEmpty()) {
            throw new InvalidFileException("Document did not produce any indexable text chunks");
        }
        log.info("Document {} chunked into {} pieces", documentId, textChunks.size());

        // 3. Batch embed
        List<float[]> embeddings = embeddingService.embedBatch(textChunks);

        // 4. Persist chunks
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            Chunk chunk = Chunk.builder()
                    .document(doc)
                    .chunkText(textChunks.get(i))
                    .embedding(embeddings.get(i))
                    .chunkIndex(i)
                    .charCount(textChunks.get(i).length())
                    .tokenCount(textChunks.get(i).length() / 4)  // heuristic
                    .metadata(Map.of("documentId", documentId.toString(), "chunkIndex", i))
                    .build();
            chunks.add(chunk);
        }
        chunkRepository.saveAll(chunks);

        // 5. Mark as indexed
        documentRepository.markAsIndexed(documentId, Document.DocumentStatus.INDEXED, chunks.size());

        long elapsed = System.currentTimeMillis() - startMs;
        meterRegistry.counter("rag.indexing.documents").increment();
        meterRegistry.counter("rag.indexing.chunks").increment(chunks.size());

        log.info("Document {} indexed: {} chunks in {}ms", documentId, chunks.size(), elapsed);
        return new DocumentDto.IndexResponse(documentId, "INDEXED", chunks.size(), elapsed);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String extractTextFromDocument(Document doc) {
        // In production store the file bytes/path; here we re-parse via stored metadata
        // This is called post-upload where the content should be available.
        // For now return a placeholder — real implementation stores file to disk on upload.
        String storedText = (String) doc.getMetadata().getOrDefault("parsedText", "");
        if (storedText.isBlank()) {
            log.warn("No pre-parsed text for document {}. Returning empty.", doc.getId());
        }
        return storedText;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File is empty or missing");
        }
        if (file.getSize() > 100L * 1024 * 1024) {
            throw new InvalidFileException("File exceeds maximum size of 100 MB");
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
