package com.hms.common.audit;

import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Emits the audit trail every service is required to produce. Audit events go to Kafka and are
 * persisted by identity-service, which owns the {@code identity.audit_log} table — so a service
 * can never quietly skip auditing by not having a table for it.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final EventPublisher publisher;
    private final List<AuditSink> sinks;
    private final String serviceName;

    public AuditService(EventPublisher publisher, List<AuditSink> sinks,
                        @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}")
                        String serviceName) {
        this.publisher = publisher;
        this.sinks = sinks;
        this.serviceName = serviceName;
    }

    /**
     * @param action  what happened, e.g. {@code PATIENT_CREATED}
     * @param entity  the entity type touched, e.g. {@code Patient}
     * @param entityId the entity's id
     * @param detail  human-readable context; must never contain clinical free text
     */
    public void record(String action, String entity, Object entityId, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", serviceName);
        payload.put("action", action);
        payload.put("entity", entity);
        payload.put("entityId", String.valueOf(entityId));
        payload.put("detail", detail);
        payload.put("username", CurrentUser.usernameOrSystem());
        DomainEvent event = DomainEvent.of("audit.recorded", entity, entityId,
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload);
        publisher.publish(Topics.AUDIT, event);
        for (AuditSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException ex) {
                // An audit sink failure is logged but never propagated into the caller's transaction.
                log.error("Audit sink {} rejected event {}", sink.getClass().getSimpleName(), event.eventId(), ex);
            }
        }
    }
}
