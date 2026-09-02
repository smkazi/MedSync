package com.hms.notification.service;

import com.hms.notification.channel.Recipient;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a patient can be reached.
 *
 * <p>A port because this is the only place the module touches patient data at all, and keeping that
 * surface exactly one file wide is worth an interface on its own. It also leaves room for the
 * deployment that keeps contact details somewhere else entirely, which is common enough.
 *
 * <p>Answering {@link Optional#empty()} is a normal outcome, not an error: a patient with no phone
 * number and no email on file, or a deployment with no directory configured, both lead to a
 * {@code SUPPRESSED} row carrying {@link #unavailableReason()} — a message that was composed,
 * recorded, and deliberately not sent. The alternative, throwing, would mean a released report
 * rolling back because nobody had set up an SMS gateway.
 */
public interface ContactDirectory {

    Optional<Recipient> find(UUID patientId);

    /** What the delivery log says when this directory cannot answer. Its own words, not a guess. */
    String unavailableReason();
}
