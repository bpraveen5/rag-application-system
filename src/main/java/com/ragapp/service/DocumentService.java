package com.ragapp.service;

import com.ragapp.dto.DocumentDto;
import com.ragapp.config.AppProperties;
import com.ragapp.entity.Document;
import com.ragapp.exception.DocumentNotFoundException;
import com.ragapp.exception.InvalidFileException;
import com.ragapp.parser.DocumentParserService;
import com.ragapp.repository.ChunkRepository;
import com.ragapp.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * High-level document management: upload, retrieve, delete, update metadata.
 * Delegates actual indexing pipeline to {@link IndexingService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ChunkRepository    chunkRepository;
    private final IndexingService    indexingService;
    private final DocumentParserService parserService;
    private final AuditService       auditService;
    private final AppProperties      appProperties;

    // ─── Upload + optional immediate index ────────────────────────────────────

    @Transactional
    public DocumentDto.UploadResponse upload(MultipartFile file, UUID userId,
                                              String tenantId, boolean indexImmediately)
            throws IOException {

        validateFile(file);

        // Parse text eagerly so it is available for indexing.
        DocumentParserService.ParseResult parsed = parserService.parse(file);
        if (parsed.text() == null || parsed.text().isBlank()) {
            throw new InvalidFileException("The uploaded file does not contain extractable text");
        }

        // Build metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("parsedText",    parsed.text());           // stored for later indexing
        metadata.put("parsedChars",   parsed.text().length());
        metadata.put("uploadedBy",    userId.toString());
        metadata.put("originalName",  file.getOriginalFilename());

        // Extract Tika metadata
        String[] tikaNames = parsed.tikaMetadata().names();
        for (String name : tikaNames) {
            metadata.put("tika_" + name.replace(":", "_"), parsed.tikaMetadata().get(name));
        }

        Document doc = Document.builder()
                .filename(sanitize(file.getOriginalFilename()))
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .userId(userId)
                .tenantId(tenantId)
                .status(Document.DocumentStatus.UPLOADED)
                .metadata(metadata)
                .build();

        doc = documentRepository.save(doc);
        auditService.log(userId, tenantId, "DOCUMENT_UPLOAD", "Document", doc.getId().toString(), "SUCCESS");

        log.info("Document uploaded: id={}, filename={}, chars={}", doc.getId(),
                doc.getFilename(), parsed.text().length());

        if (indexImmediately) {
            UUID documentId = doc.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexingService.indexAsync(documentId, null, null, null, userId);
                }
            });
        }

        return new DocumentDto.UploadResponse(
                doc.getId(), doc.getOriginalFilename(), doc.getContentType(),
                doc.getFileSize(), doc.getStatus().name(),
                indexImmediately
                        ? "Document uploaded and indexing started in background."
                        : "Document uploaded. Call POST /documents/index to index.");
    }

    // ─── Index trigger ────────────────────────────────────────────────────────

    @Transactional
    public DocumentDto.IndexResponse index(DocumentDto.IndexRequest req, UUID userId) {
        UUID docId = UUID.fromString(req.documentId());
        Document doc = documentRepository.findByIdAndUserId(docId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(docId));

        return indexingService.indexSync(docId, req.chunkingStrategy(), req.chunkSize(), req.chunkOverlap());
    }

    // ─── List documents ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DocumentDto.DocumentListResponse list(UUID userId, String tenantId,
                                                  int page, int size) {
        Page<Document> resultPage;
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "uploadDate"));

        resultPage = documentRepository.findByUserIdAndStatusNot(
                userId, Document.DocumentStatus.DELETED, pr);

        List<DocumentDto.DocumentResponse> docs = resultPage.getContent().stream()
                .map(this::toResponse).toList();

        return new DocumentDto.DocumentListResponse(
                docs, resultPage.getTotalElements(), resultPage.getTotalPages(),
                page, size);
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID documentId, UUID userId) {
        Document doc = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        // Delete embeddings
        chunkRepository.deleteAllByDocumentId(documentId);

        // Soft-delete the document
        doc.setStatus(Document.DocumentStatus.DELETED);
        documentRepository.save(doc);

        auditService.log(userId, doc.getTenantId(), "DOCUMENT_DELETE", "Document",
                documentId.toString(), "SUCCESS");
        log.info("Document deleted: id={}, userId={}", documentId, userId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    public DocumentDto.DocumentResponse toResponse(Document doc) {
        return new DocumentDto.DocumentResponse(
                doc.getId(), doc.getFilename(), doc.getOriginalFilename(),
                doc.getContentType(), doc.getFileSize(), doc.getStatus(),
                doc.getVersion(), doc.getChunkCount(), doc.getMetadata(),
                doc.getUploadDate(), doc.getIndexedAt(), doc.getErrorMessage());
    }

    private String sanitize(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("File is empty or missing");
        }
        long maxBytes = appProperties.getDocument().getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new InvalidFileException("File exceeds the maximum allowed size of "
                    + appProperties.getDocument().getMaxFileSizeMb() + " MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !appProperties.getDocument().getAllowedTypes().contains(contentType)) {
            throw new InvalidFileException("Unsupported file type: "
                    + (contentType == null ? "unknown" : contentType));
        }
    }
}