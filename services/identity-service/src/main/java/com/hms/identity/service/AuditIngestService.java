package com.hms.identity.service;

import com.hms.common.events.DomainEvent;
import com.hms.identity.domain.AuditLogEntry;
import com.hms.identity.repo.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit events emitted by every service. Ingestion is idempotent on {@code eventId}, so a
 * Kafka redelivery cannot duplicate a trail entry.
 */
@Service
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);
    private static final int DETAIL_MAX = 1000;

    private final AuditLogRepository repository;

    public AuditIngestService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes in its own transaction: audit entries are produced on paths that then fail
     * deliberately (rejected logins, refresh-token reuse), and joining the caller's transaction
     * would roll the record back with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ingest(DomainEvent event) {
        if (repository.existsByEventId(event.eventId())) {
            return false;
        }
        Map<String, Object> payload = event.payload();
        repository.save(new AuditLogEntry(
                event.eventId(),
                string(payload.get("service"), "unknown"),
                string(payload.get("action"), "UNKNOWN"),
                string(payload.get("entity"), event.aggregateType()),
                truncate(string(payload.get("entityId"), event.aggregateId()), 64),
                truncate(string(payload.get("detail"), null), DETAIL_MAX),
                truncate(event.actorId(), 64),
                truncate(string(payload.get("username"), null), 64),
                truncate(event.correlationId(), 64),
                event.occurredAt()));
        return true;
    }

    /** Convenience path for identity's own audit records, which never leave the process. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entity, Object entityId, String detail, String actorId, String username,
                       String correlationId) {
        repository.save(new AuditLogEntry(UUID.randomUUID(), "identity-service", action, entity,
                truncate(String.valueOf(entityId), 64), truncate(detail, DETAIL_MAX), truncate(actorId, 64),
                truncate(username, 64), truncate(correlationId, 64), java.time.Instant.now()));
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
