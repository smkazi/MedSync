package com.hms.common.audit;

import com.hms.common.events.DomainEvent;

/**
 * An additional destination for audit events, beyond the event topic.
 *
 * <p>identity-service registers one that writes straight to {@code identity.audit_log}, so its own
 * security events (logins, password changes, role changes) are always persisted even when the
 * platform runs without a broker.
 */
public interface AuditSink {

    void accept(DomainEvent event);
}
