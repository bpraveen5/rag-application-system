package com.ragapp.repository;

import com.ragapp.entity.Chunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    long countByDocumentId(UUID documentId);

    /**
     * Hybrid search: combines cosine similarity (vector) with text-search rank (keyword).
     * The weights are passed from {@link com.ragapp.config.AppProperties.Retrieval}.
     *
     * @param queryEmbedding  serialised float array as PostgreSQL literal, e.g. "[0.1, 0.2, …]"
     * @param queryText       full-text query string (tsquery format handled by to_tsquery)
     * @param limit           max rows to return
     * @param minSimilarity   minimum cosine similarity threshold
     * @param vectorWeight    weight for the vector similarity component
     * @param keywordWeight   weight for the BM25/tsvector component
     */
    @Query(value = """
            SELECT
                c.id,
                c.document_id,
                c.chunk_text,
                c.chunk_index,
                c.page_number,
                c.metadata,
                c.embedding,
                c.token_count,
                c.char_count,
                (
                    :vectorWeight  * (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) +
                    :keywordWeight * ts_rank(to_tsvector('english', c.chunk_text),
                                            plainto_tsquery('english', :queryText))
                ) AS combined_score
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.status = 'INDEXED'
              AND (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) >= :minSimilarity
            ORDER BY combined_score DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> hybridSearch(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("queryText")      String queryText,
            @Param("limit")          int    limit,
            @Param("minSimilarity")  double minSimilarity,
            @Param("vectorWeight")   double vectorWeight,
            @Param("keywordWeight")  double keywordWeight
    );

    /**
     * Pure cosine-similarity vector search.
     */
    @Query(value = """
            SELECT
                c.id, c.document_id, c.chunk_text, c.chunk_index,
                c.page_number, c.metadata, c.embedding, c.token_count, c.char_count,
                (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) AS similarity
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.status = 'INDEXED'
              AND (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) >= :minSimilarity
            ORDER BY similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> vectorSearch(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("limit")          int    limit,
            @Param("minSimilarity")  double minSimilarity
    );

    /**
     * Vector search filtered to a specific set of document IDs.
     */
    @Query(value = """
            SELECT
                c.id, c.document_id, c.chunk_text, c.chunk_index,
                c.page_number, c.metadata, c.embedding, c.token_count, c.char_count,
                (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) AS similarity
            FROM chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.status = 'INDEXED'
              AND d.id = ANY(CAST(:documentIds AS uuid[]))
              AND (1 - (c.embedding <=> CAST(:queryEmbedding AS vector))) >= :minSimilarity
            ORDER BY similarity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> vectorSearchWithDocumentFilter(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("documentIds")    String documentIds,
            @Param("limit")          int    limit,
            @Param("minSimilarity")  double minSimilarity
    );

    @Modifying
    @Query("DELETE FROM Chunk c WHERE c.document.id = :documentId")
    void deleteAllByDocumentId(@Param("documentId") UUID documentId);
}
