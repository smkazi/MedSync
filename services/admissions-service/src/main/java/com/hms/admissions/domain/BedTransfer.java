package com.hms.admissions.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One move, with the reason.
 *
 * <p>Its own row rather than an update to the admission's bed, because a ward move is a fact with a
 * time: "how many times was this patient moved overnight" is an infection-control question and a
 * quality one, and overwriting a bed code answers neither.
 */
@Entity
@Table(name = "bed_transfers")
public class BedTransfer extends BaseEntity {

    @Column(name = "admission_id", nullable = false, updatable = false)
    private UUID admissionId;

    @Column(name = "from_bed_id", nullable = false, updatable = false)
    private UUID fromBedId;

    @Column(name = "from_bed_code", nullable = false, length = 24, updatable = false)
    private String fromBedCode;

    @Column(name = "to_bed_id", nullable = false, updatable = false)
    private UUID toBedId;

    @Column(name = "to_bed_code", nullable = false, length = 24, updatable = false)
    private String toBedCode;

    @Column(name = "moved_at", nullable = false, updatable = false)
    private Instant movedAt = Instant.now();

    @Column(name = "moved_by", nullable = false, length = 64, updatable = false)
    private String movedBy;

    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    protected BedTransfer() {
    }

    public BedTransfer(UUID admissionId, UUID fromBedId, String fromBedCode, UUID toBedId,
                       String toBedCode, String movedBy, String reason) {
        this.admissionId = admissionId;
        this.fromBedId = fromBedId;
        this.fromBedCode = fromBedCode;
        this.toBedId = toBedId;
        this.toBedCode = toBedCode;
        this.movedBy = movedBy;
        this.reason = reason;
    }

    public UUID getAdmissionId() {
        return admissionId;
    }

    public String getFromBedCode() {
        return fromBedCode;
    }

    public String getToBedCode() {
        return toBedCode;
    }

    public Instant getMovedAt() {
        return movedAt;
    }

    public String getMovedBy() {
        return movedBy;
    }

    public String getReason() {
        return reason;
    }
}
