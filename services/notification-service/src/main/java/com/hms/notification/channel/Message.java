package com.hms.notification.channel;

/**
 * What is being sent, already rendered.
 *
 * <p>A channel receives this and nothing else. It has no access to the patient, the order, the
 * result, or the template — so a channel cannot put anything into a message that
 * {@code MessageComposer} did not already decide to put there. That is not a convenience; it is
 * what makes "no outbound message carries PHI" checkable in one place instead of three.
 *
 * @param subject where the channel has one. SMS does not.
 */
public record Message(String subject, String body) {
}
