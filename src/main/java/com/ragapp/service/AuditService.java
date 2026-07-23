package com.ragapp.service;

import com.ragapp.entity.AuditLog;
import com.ragapp.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists immutable audit events asynchronously so they never slow down
 * the calling request. Uses REQUIRES_NEW so audit entries survive even if
 * the calling transaction rolls back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async("ragTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID userId, String tenantId, String action,
                    String resourceType, String resourceId, String outcome) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .outcome(AuditLog.Outcome.valueOf(outcome))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            // Audit failures must never crash the main flow
            log.error("Failed to persist audit log [action={}, resource={}]: {}",
                    action, resourceId, ex.getMessage());
        }
    }

    @Async("ragTaskExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logWithDetails(UUID userId, String tenantId, String action,
                                String resourceType, String resourceId,
                                String outcome, String details, String ipAddress) {
        try {
            AuditLog entry = AuditLog.builder()
                    .userId(userId)
                    .tenantId(tenantId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .outcome(AuditLog.Outcome.valueOf(outcome))
                    .details(details)
                    .ipAddress(ipAddress)
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception ex) {
            log.error("Failed to persist detailed audit log: {}", ex.getMessage());
        }
    }
}
