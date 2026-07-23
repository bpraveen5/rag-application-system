package com.ragapp.repository;

import com.ragapp.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

    Page<AuditLog> findByActionAndCreatedAtBetween(String action, Instant from, Instant to, Pageable pageable);

    long countByUserIdAndOutcome(UUID userId, AuditLog.Outcome outcome);
}
