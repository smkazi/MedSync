package com.hms.identity.service;

import com.hms.common.audit.AuditSink;
import com.hms.common.events.DomainEvent;
import org.springframework.stereotype.Component;

/**
 * Writes identity-service's own audit events directly to {@code identity.audit_log}.
 *
 * <p>Other services reach the same table over the {@code hms.audit.events} topic. Identity writes
 * locally as well so that authentication events — the ones an auditor asks for first — are never
 * lost to a broker outage or a broker-less local setup.
 */
@Component
public class DatabaseAuditSink implements AuditSink {

    private final AuditIngestService ingest;

    public DatabaseAuditSink(AuditIngestService ingest) {
        this.ingest = ingest;
    }

    @Override
    public void accept(DomainEvent event) {
        ingest.ingest(event);
    }
}
