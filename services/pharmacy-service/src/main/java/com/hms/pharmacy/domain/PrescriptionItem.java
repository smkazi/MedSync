package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One medicine on a prescription.
 *
 * <p>{@code drugName} is snapshotted rather than joined from the formulary — the same decision an
 * invoice line makes about a price. Renaming a formulary entry must not rewrite what a
 * prescription written last year said, because what it said is a clinical record.
 */
@Entity
@Table(name = "prescription_items")
public class PrescriptionItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @Column(name = "drug_code", nullable = false, length = 32, updatable = false)
    private String drugCode;

    @Column(name = "drug_name", nullable = false, length = 160, updatable = false)
    private String drugName;

    @Column(name = "dose", nullable = false, length = 48)
    private String dose;

    @Column(name = "frequency", nullable = false, length = 48)
    private String frequency;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "instructions", length = 500)
    private String instructions;

    @Column(name = "quantity_dispensed", nullable = false)
    private int quantityDispensed;

    protected PrescriptionItem() {
    }

    public PrescriptionItem(String drugCode, String drugName, String dose, String frequency,
                            int durationDays, int quantity, String instructions) {
        this.drugCode = drugCode;
        this.drugName = drugName;
        this.dose = dose;
        this.frequency = frequency;
        this.durationDays = durationDays;
        this.quantity = quantity;
        this.instructions = instructions;
    }

    public int outstanding() {
        return quantity - quantityDispensed;
    }

    public boolean fullyDispensed() {
        return quantityDispensed >= quantity;
    }

    public void recordDispensed(int units) {
        quantityDispensed += units;
    }

    void setPrescription(Prescription prescription) {
        this.prescription = prescription;
    }

    public Prescription getPrescription() {
        return prescription;
    }

    public String getDrugCode() {
        return drugCode;
    }

    public String getDrugName() {
        return drugName;
    }

    public String getDose() {
        return dose;
    }

    public String getFrequency() {
        return frequency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getInstructions() {
        return instructions;
    }

    public int getQuantityDispensed() {
        return quantityDispensed;
    }
}
