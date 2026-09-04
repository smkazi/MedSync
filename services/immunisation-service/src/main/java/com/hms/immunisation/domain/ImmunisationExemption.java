package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Why a child is not going to be vaccinated.
 *
 * <p>A null {@code antigenCode} means every antigen. A blanket exemption is a real clinical
 * situation — severe immunodeficiency — and forcing one row per antigen would produce a list
 * somebody eventually leaves incomplete, which is a child counted as due for something they must
 * not have.
 */
@Entity
@Table(name = "immunisation_exemptions")
public class ImmunisationExemption extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, updatable = false, length = 24)
    private String patientMrn;

    @Column(name = "antigen_code", updatable = false, length = 32)
    private String antigenCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private ExemptionKind kind;

    @Column(name = "reason", nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(name = "recorded_by", nullable = false, updatable = false, length = 120)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false, insertable = false)
    private Instant recordedAt;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    protected ImmunisationExemption() {
    }

    public ImmunisationExemption(UUID patientId, String patientMrn, String antigenCode,
                                 ExemptionKind kind, String reason, LocalDate expiresOn,
                                 String recordedBy) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.antigenCode = antigenCode;
        this.kind = kind;
        this.reason = reason;
        this.expiresOn = expiresOn;
        this.recordedBy = recordedBy;
    }

    /** True when this exemption still stands as at {@code on}. A lapsed one exempts nobody. */
    public boolean isLiveOn(LocalDate on) {
        return expiresOn == null || !on.isAfter(expiresOn);
    }

    /** True when this exemption covers {@code antigen} — which a blanket one always does. */
    public boolean covers(String antigen) {
        return antigenCode == null || antigenCode.equals(antigen);
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getAntigenCode() {
        return antigenCode;
    }

    public ExemptionKind getKind() {
        return kind;
    }

    public String getReason() {
        return reason;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    /** Ending an exemption early: a deferral until a course of steroids finishes, finished. */
    public void endOn(LocalDate on) {
        this.expiresOn = on;
    }
}
