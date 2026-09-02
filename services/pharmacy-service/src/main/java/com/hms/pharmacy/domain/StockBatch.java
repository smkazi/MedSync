package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Stock, by batch.
 *
 * <p>By batch and not by drug, because expiry is a property of a batch: "we have 400 paracetamol"
 * is not a fact a pharmacy can act on, and a single quantity column per drug makes an expiry date
 * impossible to hold at all.
 *
 * <p>The quantity is decremented by one SQL statement in the repository rather than by reading,
 * subtracting and saving here — two dispenses of the last box both read the same number, and only
 * a conditional UPDATE can decide between them. This class deliberately has no setter for it.
 */
@Entity
@Table(name = "stock_batches")
public class StockBatch extends BaseEntity {

    @Column(name = "drug_code", nullable = false, length = 32, updatable = false)
    private String drugCode;

    @Column(name = "batch_no", nullable = false, length = 48, updatable = false)
    private String batchNo;

    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn = LocalDate.now();

    protected StockBatch() {
    }

    public StockBatch(String drugCode, String batchNo, LocalDate expiresOn, int quantityOnHand) {
        this.drugCode = drugCode;
        this.batchNo = batchNo;
        this.expiresOn = expiresOn;
        this.quantityOnHand = quantityOnHand;
    }

    /**
     * Expired on or before {@code on}.
     *
     * <p>A batch expiring today is expired. Pharmacy convention is the opposite in some places —
     * usable to the end of the stated month — and this platform takes the stricter reading, once,
     * here, rather than leaving each caller to decide.
     */
    public boolean expiredOn(LocalDate on) {
        return !expiresOn.isAfter(on);
    }

    public String getDrugCode() {
        return drugCode;
    }

    public String getBatchNo() {
        return batchNo;
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
}
