package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A lot of vaccine, because expiry is a property of a lot and not of a vaccine.
 *
 * <p>Deliberately the same shape as {@code pharmacy.stock_batches}: a lot number, an expiry that
 * cannot be null, a non-negative quantity, and a first-expiry-first-out index. A second,
 * differently shaped inventory table in a second service is how two services come to disagree
 * about what a batch is.
 */
@Entity
@Table(name = "vaccine_lots")
public class VaccineLot extends BaseEntity {

    @Column(name = "product_code", nullable = false, updatable = false, length = 32)
    private String productCode;

    @Column(name = "lot_no", nullable = false, updatable = false, length = 48)
    private String lotNo;

    @Column(name = "expires_on", nullable = false, updatable = false)
    private LocalDate expiresOn;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    /**
     * The vial monitor stage as read at receipt, 1 to 4, or null if nobody read one.
     *
     * <p>Recorded and not enforced. This platform has no cold-chain telemetry: nothing here knows
     * what a fridge did overnight, and a column claiming a chain it does not monitor would be worse
     * than an honest one. Stage 3 and 4 vials must be discarded and that judgement is a person's,
     * at the fridge, with the vial in their hand.
     */
    @Column(name = "vvm_stage")
    private Short vvmStage;

    @Column(name = "withdrawn_reason", length = 255)
    private String withdrawnReason;

    protected VaccineLot() {
    }

    public VaccineLot(String productCode, String lotNo, LocalDate expiresOn, int quantityOnHand,
                      LocalDate receivedOn, Short vvmStage) {
        this.productCode = productCode;
        this.lotNo = lotNo;
        this.expiresOn = expiresOn;
        this.quantityOnHand = quantityOnHand;
        this.receivedOn = receivedOn;
        this.vvmStage = vvmStage;
    }

    /** True when this lot has expired as at {@code today}. Expiry is a date comparison, not a job. */
    public boolean hasExpired(LocalDate today) {
        return expiresOn.isBefore(today);
    }

    /** True when this lot may still be drawn from: in date, in stock, and not withdrawn. */
    public boolean isUsable(LocalDate today) {
        return !hasExpired(today) && quantityOnHand > 0 && withdrawnReason == null;
    }

    /** Takes one dose out of the vial. The non-negative CHECK is the backstop, not this. */
    public void draw() {
        quantityOnHand = Math.max(0, quantityOnHand - 1);
    }

    public String getProductCode() {
        return productCode;
    }

    public String getLotNo() {
        return lotNo;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public int getQuantityOnHand() {
        return quantityOnHand;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public Short getVvmStage() {
        return vvmStage;
    }

    public String getWithdrawnReason() {
        return withdrawnReason;
    }

    /** Takes a lot out of use, with the reason it went: expired, recalled, or a broken cold chain. */
    public void withdraw(String reason) {
        this.withdrawnReason = reason;
    }
}
