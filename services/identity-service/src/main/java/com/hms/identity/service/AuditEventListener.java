package com.hms.identity.service;

import com.hms.common.events.DomainEvent;
import com.hms.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code hms.audit.events} and persists each entry.
 *
 * <p>Only active when the platform runs with Kafka. With {@code hms.events.transport=log} the
 * services still emit the same audit envelopes, but to their logs — see docs/architecture.md.
 */
@Component
@ConditionalOnProperty(name = "hms.events.transport", havingValue = "kafka")
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final AuditIngestService ingest;
    private final ObjectMapper objectMapper;

    public AuditEventListener(AuditIngestService ingest, ObjectMapper objectMapper) {
        this.ingest = ingest;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = Topics.AUDIT, groupId = "identity-audit-writer")
    public void onAuditEvent(String message) {
        try {
            DomainEvent event = objectMapper.readValue(message, DomainEvent.class);
            if (!ingest.ingest(event)) {
                log.debug("Skipped duplicate audit event {}", event.eventId());
            }
        } catch (RuntimeException ex) {
            // A malformed audit message must not stall the consumer group.
            log.error("Discarding unreadable audit event: {}", ex.getMessage());
        }
    }
}
