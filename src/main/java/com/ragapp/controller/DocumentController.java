package com.ragapp.controller;

import com.ragapp.dto.DocumentDto;
import com.ragapp.security.RagUserPrincipal;
import com.ragapp.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Upload, index, list, and delete documents")
public class DocumentController {

    private final DocumentService documentService;

    /**
     * POST /documents/upload
     * Accepts multipart/form-data. Optional ?index=true triggers background indexing.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document (PDF, DOCX, TXT, Markdown, HTML)")
    public ResponseEntity<DocumentDto.UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean index,
            @AuthenticationPrincipal RagUserPrincipal principal,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId)
            throws IOException {

        DocumentDto.UploadResponse response = documentService.upload(
                file, principal.getId(), tenantId, index);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /documents/index
     * Trigger (re-)indexing for an already-uploaded document.
     */
    @PostMapping("/index")
    @Operation(summary = "Index (or re-index) an uploaded document")
    public ResponseEntity<DocumentDto.IndexResponse> index(
            @Valid @RequestBody DocumentDto.IndexRequest request,
            @AuthenticationPrincipal RagUserPrincipal principal) {

        DocumentDto.IndexResponse response = documentService.index(request, principal.getId());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /documents
     * Returns a paginated list of the authenticated user's documents.
     */
    @GetMapping
    @Operation(summary = "List all documents for the authenticated user")
    public ResponseEntity<DocumentDto.DocumentListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal RagUserPrincipal principal,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        return ResponseEntity.ok(documentService.list(principal.getId(), tenantId, page, size));
    }

    /**
     * DELETE /documents/{id}
     * Soft-deletes a document and removes its embeddings.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document and its embeddings")
    public ResponseEntity<Void> delete(
            @PathVariable @Parameter(description = "Document UUID") UUID id,
            @AuthenticationPrincipal RagUserPrincipal principal) {

        documentService.delete(id, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
