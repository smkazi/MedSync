package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A coded diagnosis recorded against an encounter. */
@Entity
@Table(name = "diagnoses")
public class Diagnosis extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false)
    private Encounter encounter;

    @Column(name = "icd10_code", nullable = false, length = 16)
    private String icd10Code;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private SchedulingEnums.DiagnosisCategory category = SchedulingEnums.DiagnosisCategory.SECONDARY;

    @Column(name = "recorded_by", nullable = false, length = 64)
    private String recordedBy;

    protected Diagnosis() {
    }

    public Diagnosis(Encounter encounter, String icd10Code, String description,
                     SchedulingEnums.DiagnosisCategory category, String recordedBy) {
        this.encounter = encounter;
        this.icd10Code = icd10Code;
        this.description = description;
        this.category = category;
        this.recordedBy = recordedBy;
    }

    public String getIcd10Code() {
        return icd10Code;
    }

    public String getDescription() {
        return description;
    }

    public SchedulingEnums.DiagnosisCategory getCategory() {
        return category;
    }

    public void setCategory(SchedulingEnums.DiagnosisCategory category) {
        this.category = category;
    }

    public String getRecordedBy() {
        return recordedBy;
    }
}
