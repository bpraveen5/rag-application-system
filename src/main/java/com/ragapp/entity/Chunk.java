package com.ragapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * A text chunk extracted from a {@link Document}.
 * The {@code embedding} column is a pgvector {@code vector} type
 * stored via the native column definition.
 */
@Entity
@Table(name = "chunks",
        indexes = {
            @Index(name = "idx_chunks_document_id",  columnList = "document_id"),
            @Index(name = "idx_chunks_chunk_index",  columnList = "document_id, chunk_index")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    /**
     * pgvector column. Spring AI PgVectorStore manages its own embeddings table,
     * but we also store a reference here for custom hybrid-search queries.
     * Dimensions are configured in application.yml (768 for the Ollama
     * "nomic-embed-text" embedding model). If the embedding model is changed
     * to one with a different output size, this column (and the matching
     * spring.ai.vectorstore.pgvector.dimensions property) must be updated together.
     */
    @Column(name = "embedding", columnDefinition = "vector(768)")
    private float[] embedding;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "char_count")
    private Integer charCount;

    /** Page number (for PDFs), slide number (for PPT), etc. */
    @Column(name = "page_number")
    private Integer pageNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
