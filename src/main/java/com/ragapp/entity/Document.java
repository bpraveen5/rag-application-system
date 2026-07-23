package com.ragapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an uploaded document stored in the system.
 * Contains metadata and references to its parsed chunks.
 */
@Entity
@Table(name = "documents",
        indexes = {
            @Index(name = "idx_documents_user_id",   columnList = "user_id"),
            @Index(name = "idx_documents_tenant_id", columnList = "tenant_id"),
            @Index(name = "idx_documents_status",    columnList = "status"),
            @Index(name = "idx_documents_filename",  columnList = "filename")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "original_filename", nullable = false, length = 512)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_path", length = 1024)
    private String filePath;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private DocumentStatus status = DocumentStatus.UPLOADED;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    // Metadata stored as JSONB
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "upload_date", nullable = false, updatable = false)
    private Instant uploadDate;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Chunk> chunks = new ArrayList<>();

    public enum DocumentStatus {
        UPLOADED,      // File received, not yet parsed
        PROCESSING,    // Currently being indexed
        INDEXED,       // Successfully indexed
        FAILED,        // Indexing failed
        DELETED        // Soft-deleted
    }

    public void addChunk(Chunk chunk) {
        chunks.add(chunk);
        chunk.setDocument(this);
    }
}
