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
        recordAs(action, entity, entityId, detail,
                CurrentUser.usernameOrSystem(), CurrentUser.idOrSystem().toString());
    }

    /**
     * Records an action against a named account when the request carries no session.
     *
     * <p>This exists because the four-argument method reads the actor off the security context,
     * and the rows an auditor asks about first — who signed in, whose sign-in failed, which
     * account was locked out, whose session was burned for a replayed token — all happen
     * <em>before</em> there is a session to read. Every one of them was therefore attributed to
     * {@code system} with the all-zero actor id, which made the report's "who" filter useless for
     * exactly the actions it exists to answer questions about. The caller on those paths knows
     * perfectly well whose account it is and now says so.
     *
     * @param username the account the action is about, or the username as typed when no such
     *                 account exists — a failed sign-in against a name nobody holds is worth
     *                 recording under that name, because credential stuffing is what a hundred of
     *                 them in a row looks like
     * @param actorId  that account's id, or null when there is no account to point at
     */
    public void recordAs(String action, String entity, Object entityId, String detail,
                         String username, String actorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", serviceName);
        payload.put("action", action);
        payload.put("entity", entity);
        payload.put("entityId", String.valueOf(entityId));
        payload.put("detail", detail);
        payload.put("username", username);
        DomainEvent event = DomainEvent.of("audit.recorded", entity, entityId,
                actorId, CorrelationId.current(), payload);
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
