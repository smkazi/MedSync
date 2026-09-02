package com.hms.notification.domain;

/** The vocabulary of outbound messaging. */
public final class NotificationEnums {

    /**
     * How a message leaves the platform.
     *
     * <p>{@code LOG} is the default and is not a stub: on a deployment with no mail server and no
     * SMS contract it is the honest channel, because a delivery log that records what would have
     * been sent is more useful than a service that refuses to start. Every test asserts against it
     * for the same reason — a test that needed a real SMTP server would be a test nobody runs.
     */
    public enum Channel {
        LOG, EMAIL, SMS
    }

    /**
     * What a message is about — and, because the wording comes from a template keyed on this, the
     * only thing a caller gets to decide about the words.
     */
    public enum Category {
        LAB_REPORT_READY,
        APPOINTMENT_CONFIRMED,
        APPOINTMENT_REMINDER,
        APPOINTMENT_CANCELLED,
        PORTAL_MESSAGE
    }

    /**
     * What happened.
     *
     * <p>There is no PENDING or QUEUED, deliberately. A row is written when the attempt has been
     * made, so a status is a fact rather than an intention; a queue that can hold a message
     * forever in PENDING is a queue that quietly stops delivering and looks fine.
     *
     * <p>{@code SUPPRESSED} is the one worth naming: nothing was sent, on purpose, because there
     * was nowhere to send it or the record says not to. It is recorded rather than skipped, so
     * "the patient was never told" has evidence behind it.
     */
    public enum Status {
        SENT, FAILED, SUPPRESSED
    }

    private NotificationEnums() {
    }
}
