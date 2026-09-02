package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Medicine handed over, from a named batch.
 *
 * <p>The batch is on the row because a recall is a question about batches: "who received anything
 * from batch B-2291" has no answer if a dispense records only the drug.
 */
@Entity
@Table(name = "dispenses")
public class Dispense extends BaseEntity {

    @Column(name = "prescription_item_id", nullable = false, updatable = false)
    private UUID prescriptionItemId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "dispensed_by", nullable = false, length = 120, updatable = false)
    private String dispensedBy;

    @Column(name = "dispensed_at", nullable = false, updatable = false)
    private Instant dispensedAt = Instant.now();

    protected Dispense() {
    }

    public Dispense(UUID prescriptionItemId, UUID batchId, int quantity, String dispensedBy) {
        this.prescriptionItemId = prescriptionItemId;
        this.batchId = batchId;
        this.quantity = quantity;
        this.dispensedBy = dispensedBy;
    }

    public UUID getPrescriptionItemId() {
        return prescriptionItemId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getDispensedBy() {
        return dispensedBy;
    }

    public Instant getDispensedAt() {
        return dispensedAt;
    }
}
