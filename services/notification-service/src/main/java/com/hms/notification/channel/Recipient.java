package com.hms.notification.channel;

import java.util.UUID;

/**
 * Where a message goes.
 *
 * <p>An address and, when the message is about a patient, their id. No name: the channels are the
 * one place a name would end up in an outbound message, so they never receive one.
 */
public record Recipient(UUID patientId, String phone, String email) {

    public boolean hasPhone() {
        return phone != null && !phone.isBlank();
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /** Nowhere to send. A real state, and the reason {@code SUPPRESSED} exists. */
    public boolean isUnreachable() {
        return !hasPhone() && !hasEmail();
    }
}
