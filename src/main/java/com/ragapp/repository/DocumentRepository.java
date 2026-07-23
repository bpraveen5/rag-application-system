package com.ragapp.repository;

import com.ragapp.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByUserIdAndStatusNot(UUID userId, Document.DocumentStatus status, Pageable pageable);

    Page<Document> findByTenantIdAndStatusNot(String tenantId, Document.DocumentStatus status, Pageable pageable);

    List<Document> findByUserIdAndIdIn(UUID userId, List<UUID> ids);

    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("UPDATE Document d SET d.status = :status, d.errorMessage = :errorMessage WHERE d.id = :id")
    void updateStatus(@Param("id") UUID id,
                      @Param("status") Document.DocumentStatus status,
                      @Param("errorMessage") String errorMessage);

    @Modifying
    @Query("UPDATE Document d SET d.status = :status, d.chunkCount = :chunkCount, " +
           "d.indexedAt = CURRENT_TIMESTAMP WHERE d.id = :id")
    void markAsIndexed(@Param("id") UUID id, @Param("status") Document.DocumentStatus status,
                       @Param("chunkCount") int chunkCount);

    @Query("SELECT d FROM Document d WHERE d.status = com.ragapp.entity.Document.DocumentStatus.UPLOADED " +
           "ORDER BY d.uploadDate ASC")
    List<Document> findPendingDocuments(Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) FROM documents
            WHERE user_id = :userId
              AND status != 'DELETED'
            """, nativeQuery = true)
    long countByUserId(@Param("userId") UUID userId);
}
