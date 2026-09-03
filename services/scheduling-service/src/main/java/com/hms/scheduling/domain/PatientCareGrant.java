package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A time-boxed relationship with a patient, granted by exception.
 *
 * <p>The second half of the care-team mechanism. Membership of an encounter's team answers "is this
 * your patient" for a chart; this answers it for everything else about the patient — their
 * laboratory orders, their prescriptions — which a covering clinician may need without ever having
 * charted them, and which a walk-in blood test has no encounter behind at all.
 *
 * <p>Every row is an exception, so every row carries a reason and an expiry. There is no
 * platform-created grant here, which is why neither column is nullable: the ordinary path onto a
 * patient's record is looking after them, and this is the other one.
 */
@Entity
@Table(name = "patient_care_grants")
public class PatientCareGrant extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected PatientCareGrant() {
    }

    public PatientCareGrant(UUID patientId, UUID userId, String reason, Instant expiresAt) {
        this.patientId = patientId;
        this.userId = userId;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public boolean isLive(Instant now) {
        return expiresAt.isAfter(now);
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
