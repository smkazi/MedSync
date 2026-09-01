package com.hms.patient.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "patient_allergies")
public class PatientAllergy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "substance", nullable = false, length = 120)
    private String substance;

    @Column(name = "reaction")
    private String reaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AllergySeverity severity;

    @Column(name = "recorded_by", length = 64)
    private String recordedBy;

    protected PatientAllergy() {
    }

    public PatientAllergy(Patient patient, String substance, String reaction, AllergySeverity severity,
                          String recordedBy) {
        this.patient = patient;
        this.substance = substance;
        this.reaction = reaction;
        this.severity = severity;
        this.recordedBy = recordedBy;
    }

    public Patient getPatient() {
        return patient;
    }

    public String getSubstance() {
        return substance;
    }

    public String getReaction() {
        return reaction;
    }

    public AllergySeverity getSeverity() {
        return severity;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    /** Whether this allergy warrants the chart's prominent warning banner. */
    public boolean isCritical() {
        return severity == AllergySeverity.SEVERE || severity == AllergySeverity.LIFE_THREATENING;
    }
}
