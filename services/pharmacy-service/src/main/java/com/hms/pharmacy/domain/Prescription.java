package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.pharmacy.domain.PharmacyEnums.PrescriptionStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One prescriber's order, on one occasion, for one patient.
 *
 * <p>The items travel with it because they were checked together: an interaction is a property of
 * a *set* of medicines, so a prescription written as three separate orders would have been checked
 * three times against nothing. That is the whole reason this aggregate has children rather than
 * being a flat table of orders.
 *
 * <p>{@code overrideReason} is the record of a decision, not a flag. A prescriber who goes ahead
 * despite a warning has to say why in a sentence, and it is stored on the prescription rather than
 * in an audit detail because it is clinical content — the pharmacist reads it before dispensing,
 * and {@code AuditService}'s contract forbids clinical free text in its detail field.
 */
@Entity
@Table(name = "prescriptions")
public class Prescription extends BaseEntity {

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "prescriber_id", nullable = false, updatable = false)
    private UUID prescriberId;

    @Column(name = "prescriber_name", nullable = false, length = 160, updatable = false)
    private String prescriberName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PrescriptionStatus status = PrescriptionStatus.ACTIVE;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<PrescriptionItem> items = new ArrayList<>();

    protected Prescription() {
    }

    public Prescription(UUID encounterId, UUID patientId, String patientMrn, UUID prescriberId,
                        String prescriberName, String overrideReason) {
        this.encounterId = encounterId;
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.prescriberId = prescriberId;
        this.prescriberName = prescriberName;
        this.overrideReason = overrideReason;
    }

    public void addItem(PrescriptionItem item) {
        items.add(item);
        item.setPrescription(this);
    }

    /**
     * Everything on this prescription has been handed over.
     *
     * <p>Asked of the numbers rather than tracked as a status somebody has to remember to set: a
     * flag maintained alongside a sum is a flag that eventually disagrees with it.
     */
    public boolean fullyDispensed() {
        return !items.isEmpty() && items.stream().allMatch(PrescriptionItem::fullyDispensed);
    }

    public void markCompletedIfFullyDispensed() {
        if (status == PrescriptionStatus.ACTIVE && fullyDispensed()) {
            status = PrescriptionStatus.COMPLETED;
        }
    }

    public void cancel() {
        status = PrescriptionStatus.CANCELLED;
        cancelledAt = Instant.now();
    }

    public boolean isActive() {
        return status == PrescriptionStatus.ACTIVE;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public UUID getPrescriberId() {
        return prescriberId;
    }

    public String getPrescriberName() {
        return prescriberName;
    }

    public PrescriptionStatus getStatus() {
        return status;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<PrescriptionItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
