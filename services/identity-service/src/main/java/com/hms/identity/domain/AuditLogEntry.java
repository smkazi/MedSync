package com.hms.identity.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A persisted audit event. Append-only: there is no setter and no update path. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry extends BaseEntity {

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "service", nullable = false, length = 64)
    private String service;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "entity", nullable = false, length = 64)
    private String entity;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(name = "username", length = 64)
    private String username;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(UUID eventId, String service, String action, String entity, String entityId, String detail,
                         String actorId, String username, String correlationId, Instant occurredAt) {
        this.eventId = eventId;
        this.service = service;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.detail = detail;
        this.actorId = actorId;
        this.username = username;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getService() {
        return service;
    }

    public String getAction() {
        return action;
    }

    public String getEntity() {
        return entity;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getDetail() {
        return detail;
    }

    public String getActorId() {
        return actorId;
    }

    public String getUsername() {
        return username;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
