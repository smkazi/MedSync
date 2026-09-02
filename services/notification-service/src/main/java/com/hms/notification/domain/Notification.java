package com.hms.notification.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to tell somebody something.
 *
 * <p>A delivery log entry first and a queue entry second. The question asked afterwards is almost
 * always "was the patient told?", and a queue that deletes what it has processed cannot answer it.
 *
 * <p>The row carries the rendered body, which is the point of keeping it: knowing that the platform
 * sent a `LAB_REPORT_READY` is not the same as knowing what the patient actually read, and a
 * template gets reworded.
 */
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationEnums.Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private NotificationEnums.Category category;

    @Column(name = "recipient", length = 255)
    private String recipient;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationEnums.Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "failed_reason", length = 500)
    private String failedReason;

    protected Notification() {
    }

    public Notification(NotificationEnums.Channel channel, NotificationEnums.Category category,
                        String idempotencyKey, String body) {
        this.channel = channel;
        this.category = category;
        this.idempotencyKey = idempotencyKey;
        this.body = body;
        this.status = NotificationEnums.Status.SUPPRESSED;
    }

    /** Delivered. */
    public void sent(String to, int attemptCount) {
        this.recipient = to;
        this.status = NotificationEnums.Status.SENT;
        this.attempts = attemptCount;
        this.sentAt = Instant.now();
        this.failedReason = null;
    }

    /** The channel tried and could not. The reason is the channel's own words. */
    public void failed(String to, int attemptCount, String reason) {
        this.recipient = to;
        this.status = NotificationEnums.Status.FAILED;
        this.attempts = attemptCount;
        this.failedReason = truncate(reason);
    }

    /**
     * Nothing was sent, and that was the right answer.
     *
     * <p>Recorded rather than skipped: "no phone number on file" is a fact somebody needs to be
     * able to find, and a message that silently never existed leaves the front desk believing the
     * patient was told.
     */
    public void suppressed(String reason) {
        this.status = NotificationEnums.Status.SUPPRESSED;
        this.failedReason = truncate(reason);
    }

    public void setSubject(String value) {
        this.subject = value;
    }

    public void setPatientId(UUID value) {
        this.patientId = value;
    }

    public void setReference(String value) {
        this.reference = value;
    }

    public NotificationEnums.Channel getChannel() {
        return channel;
    }

    public NotificationEnums.Category getCategory() {
        return category;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationEnums.Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getReference() {
        return reference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getFailedReason() {
        return failedReason;
    }

    /**
     * A channel's error message is not length-controlled — an SMTP server or an HTTP gateway can
     * answer with a page of text — and a column overflow while recording a failure would turn one
     * failed message into a failed transaction.
     */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
    }
}
