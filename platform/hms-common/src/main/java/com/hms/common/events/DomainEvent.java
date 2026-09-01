package com.hms.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope for everything published to Kafka. Payloads are maps rather than typed classes so a
 * consumer never fails to deserialize because a producer added a field.
 *
 * @param eventId       unique id, usable by consumers for idempotency
 * @param type          dotted event name, e.g. {@code patient.created}
 * @param aggregateType the owning aggregate, e.g. {@code Patient}
 * @param aggregateId   the aggregate instance the event concerns (also the Kafka message key)
 * @param occurredAt    when the state change happened
 * @param actorId       the user who caused it, or the system id
 * @param correlationId the originating request's correlation id
 * @param payload       event-specific data
 */
public record DomainEvent(UUID eventId, String type, String aggregateType, String aggregateId, Instant occurredAt,
                          String actorId, String correlationId, Map<String, Object> payload) {

    public static DomainEvent of(String type, String aggregateType, Object aggregateId, String actorId,
                                 String correlationId, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), type, aggregateType, String.valueOf(aggregateId), Instant.now(),
                actorId, correlationId, payload == null ? Map.of() : payload);
    }
}
