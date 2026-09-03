package com.hms.interop.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.interop.domain.InteropEnums.DisclosureKind;
import com.hms.interop.domain.InteropEnums.HiType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One release of a patient's information, and what authorised it.
 *
 * <p>The accounting of disclosures, written in the same transaction as the release rather than
 * reconstructed from logs afterwards — which is the difference between a record and an
 * archaeological exercise. A patient is entitled to ask who has seen their record, and "we would
 * have to grep six services' logs" is not an answer.
 *
 * <p><strong>What it does not hold is the bundle.</strong> Size and resource count, not content: a
 * disclosure log carrying the payload would be a second copy of the medical record with none of
 * its protections, sitting in the one table auditors are given broad access to.
 */
@Entity
@Table(name = "disclosures")
public class Disclosure extends BaseEntity {

    /** Null only for a {@code PATIENT_EXPORT}, which the database's own CHECK enforces. */
    @Column(name = "consent_id", updatable = false)
    private UUID consentId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Enumerated(EnumType.STRING)
    @Column(name = "hi_type", nullable = false, length = 32, updatable = false)
    private HiType hiType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private DisclosureKind kind;

    @Column(name = "recipient", nullable = false, length = 160, updatable = false)
    private String recipient;

    @Column(name = "resource_count", nullable = false, updatable = false)
    private int resourceCount;

    @Column(name = "byte_count", nullable = false, updatable = false)
    private int byteCount;

    @Column(name = "released_by", nullable = false, length = 120, updatable = false)
    private String releasedBy;

    @Column(name = "released_at", nullable = false, updatable = false)
    private Instant releasedAt = Instant.now();

    protected Disclosure() {
    }

    public Disclosure(UUID consentId, UUID patientId, String patientMrn, HiType hiType,
                      DisclosureKind kind, String recipient, int resourceCount, int byteCount,
                      String releasedBy) {
        this.consentId = consentId;
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.hiType = hiType;
        this.kind = kind;
        this.recipient = recipient;
        this.resourceCount = resourceCount;
        this.byteCount = byteCount;
        this.releasedBy = releasedBy;
    }

    public UUID getConsentId() {
        return consentId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public HiType getHiType() {
        return hiType;
    }

    public DisclosureKind getKind() {
        return kind;
    }

    public String getRecipient() {
        return recipient;
    }

    public int getResourceCount() {
        return resourceCount;
    }

    public int getByteCount() {
        return byteCount;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
