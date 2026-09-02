package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.pharmacy.domain.PharmacyEnums.AdministrationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One dose, at the bedside.
 *
 * <p>The row that closes the loop, and the only one in the platform written against two scans: a
 * wristband and a drug label. The scans are stored verbatim rather than as a "verified" boolean
 * because the question asked after a wrong dose is *which barcode was actually scanned*, and a
 * boolean cannot answer it.
 *
 * <p>{@code scheduledFor} plus the item is a unique key — one dose, one record. Two nurses at one
 * bedside, each believing the other had not given it, is the failure that constraint exists for.
 */
@Entity
@Table(name = "administrations")
public class Administration extends BaseEntity {

    @Column(name = "prescription_item_id", nullable = false, updatable = false)
    private UUID prescriptionItemId;

    @Column(name = "scheduled_for", nullable = false, updatable = false)
    private Instant scheduledFor;

    @Column(name = "administered_at")
    private Instant administeredAt;

    @Column(name = "administered_by", nullable = false, length = 120, updatable = false)
    private String administeredBy;

    @Column(name = "patient_scan", length = 64, updatable = false)
    private String patientScan;

    @Column(name = "drug_scan", length = 64, updatable = false)
    private String drugScan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdministrationStatus status;

    @Column(name = "refusal_reason", length = 255)
    private String refusalReason;

    protected Administration() {
    }

    /** A dose that was given: both scans matched, and the time is now. */
    public static Administration given(UUID itemId, Instant scheduledFor, String by,
                                       String patientScan, String drugScan) {
        Administration record = new Administration();
        record.prescriptionItemId = itemId;
        record.scheduledFor = scheduledFor;
        record.administeredBy = by;
        record.patientScan = patientScan;
        record.drugScan = drugScan;
        record.status = AdministrationStatus.GIVEN;
        record.administeredAt = Instant.now();
        return record;
    }

    /**
     * A dose that was not given, with the reason.
     *
     * <p>No scans, and {@code administeredAt} stays null: nothing was administered, and stamping a
     * time on a dose that did not happen is how a record starts lying. The row exists because the
     * absence of a dose is a clinical fact — the next shift needs to know the patient declined it,
     * rather than finding a gap and guessing.
     */
    public static Administration notGiven(UUID itemId, Instant scheduledFor, String by,
                                          AdministrationStatus status, String reason) {
        Administration record = new Administration();
        record.prescriptionItemId = itemId;
        record.scheduledFor = scheduledFor;
        record.administeredBy = by;
        record.status = status;
        record.refusalReason = reason;
        return record;
    }

    public UUID getPrescriptionItemId() {
        return prescriptionItemId;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public Instant getAdministeredAt() {
        return administeredAt;
    }

    public String getAdministeredBy() {
        return administeredBy;
    }

    public String getPatientScan() {
        return patientScan;
    }

    public String getDrugScan() {
        return drugScan;
    }

    public AdministrationStatus getStatus() {
        return status;
    }

    public String getRefusalReason() {
        return refusalReason;
    }
}
