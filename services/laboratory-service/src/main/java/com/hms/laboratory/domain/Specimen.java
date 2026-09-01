package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A collected sample, identified by the accession number the lab labels the tube with. */
@Entity
@Table(name = "specimens")
public class Specimen extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private LabOrder order;

    @Column(name = "accession_no", nullable = false, unique = true, length = 24)
    private String accessionNo;

    @Column(name = "specimen_type", nullable = false, length = 32)
    private String specimenType = "WHOLE_BLOOD";

    @Column(name = "collected_at")
    private Instant collectedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "collected_by", length = 64)
    private String collectedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LabEnums.SpecimenStatus status = LabEnums.SpecimenStatus.PENDING;

    protected Specimen() {
    }

    public Specimen(LabOrder order, String accessionNo, String specimenType) {
        this.order = order;
        this.accessionNo = accessionNo;
        this.specimenType = specimenType;
    }

    public LabOrder getOrder() {
        return order;
    }

    public String getAccessionNo() {
        return accessionNo;
    }

    public String getSpecimenType() {
        return specimenType;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getCollectedBy() {
        return collectedBy;
    }

    public LabEnums.SpecimenStatus getStatus() {
        return status;
    }

    public void markCollected(String collectedBy) {
        this.collectedAt = Instant.now();
        this.collectedBy = collectedBy;
        this.status = LabEnums.SpecimenStatus.COLLECTED;
    }

    public void markReceived() {
        this.receivedAt = Instant.now();
        this.status = LabEnums.SpecimenStatus.RECEIVED;
    }

    public void reject() {
        this.status = LabEnums.SpecimenStatus.REJECTED;
    }
}
