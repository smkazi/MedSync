package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The number a patient is handed at the desk.
 *
 * <p>Deliberately holds no patient identity at all — not an id, not an MRN, not a name. The link to
 * the person is the appointment id and nothing else, and that is what makes the public display
 * possible: the wall board reads these rows directly and there is nothing in them to leak. A token
 * table carrying an MRN would mean the PHI-free rendering depended on the query being careful, and
 * a query is a thing somebody edits.
 */
@Entity
@Table(name = "queue_tokens")
public class QueueToken extends BaseEntity {

    @Column(name = "appointment_id", nullable = false, updatable = false)
    private UUID appointmentId;

    @Column(name = "room_code", nullable = false, length = 24, updatable = false)
    private String roomCode;

    @Column(name = "service_date", nullable = false, updatable = false)
    private LocalDate serviceDate;

    @Column(name = "token_number", nullable = false, updatable = false)
    private int tokenNumber;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "called_at")
    private Instant calledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SchedulingEnums.TokenStatus status;

    protected QueueToken() {
    }

    public QueueToken(UUID appointmentId, String roomCode, LocalDate serviceDate, int tokenNumber) {
        this.appointmentId = appointmentId;
        this.roomCode = roomCode;
        this.serviceDate = serviceDate;
        this.tokenNumber = tokenNumber;
        this.issuedAt = Instant.now();
        this.status = SchedulingEnums.TokenStatus.WAITING;
    }

    /**
     * The number is being called in the corridor.
     *
     * <p>Idempotent: calling an already-called token keeps the first timestamp, because "when was
     * this patient called" has one answer and re-entering a consultation should not rewrite it.
     */
    public void call() {
        if (status == SchedulingEnums.TokenStatus.WAITING) {
            this.status = SchedulingEnums.TokenStatus.CALLED;
            this.calledAt = Instant.now();
        }
    }

    /** The consultation is over, so the number leaves the board. */
    public void finish() {
        if (calledAt == null) {
            // A consultation that completed without the number ever being called still happened -
            // a walk-in seen out of order, a clinician who did not touch the screen. The CHECK
            // constraint requires a time on anything that is not WAITING, so the honest value is
            // now rather than a null that would fail the write.
            this.calledAt = Instant.now();
        }
        this.status = SchedulingEnums.TokenStatus.DONE;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public LocalDate getServiceDate() {
        return serviceDate;
    }

    public int getTokenNumber() {
        return tokenNumber;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public SchedulingEnums.TokenStatus getStatus() {
        return status;
    }
}
