package com.hms.billing.domain;

import com.hms.billing.domain.BillingEnums.ClaimStatus;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the hospital has asked a payer for, and what came back.
 *
 * <p>One per invoice, by unique constraint: two claims for one invoice is how a hospital claims
 * twice for the same treatment, which is fraud however accidental. A rejected claim is re-argued on
 * the same row — the status moves and the reason is kept — rather than by raising a second one.
 */
@Entity
@Table(name = "claims")
public class Claim extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "payer_code", nullable = false, length = 32, updatable = false)
    private String payerCode;

    @Column(name = "preauth_no", length = 64)
    private String preauthNo;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.DRAFT;

    @Column(name = "claimed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "settled_amount", precision = 14, scale = 2)
    private BigDecimal settledAmount;

    @Column(name = "denial_reason", length = 255)
    private String denialReason;

    protected Claim() {
    }

    public Claim(UUID invoiceId, String payerCode, String preauthNo, BigDecimal claimedAmount) {
        this.invoiceId = invoiceId;
        this.payerCode = payerCode;
        this.preauthNo = preauthNo;
        this.claimedAmount = Money.scale(claimedAmount);
    }

    public void submit() {
        this.status = ClaimStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    /**
     * Records what the payer paid.
     *
     * <p>The status is derived from the amount rather than passed in: settled in full and settled
     * short are different facts with different consequences — the shortfall goes back to the
     * patient or is written off — and letting a caller assert "SETTLED" while paying half would
     * hide the decision somebody has to make.
     */
    public void settle(BigDecimal amount) {
        this.settledAmount = Money.scale(amount);
        this.status = settledAmount.compareTo(claimedAmount) >= 0
                ? ClaimStatus.SETTLED : ClaimStatus.PARTIALLY_SETTLED;
    }

    public void deny(String reason) {
        this.status = ClaimStatus.DENIED;
        this.denialReason = reason;
        this.settledAmount = Money.scale(BigDecimal.ZERO);
    }

    /** What the payer did not pay, and somebody has to decide about. */
    public BigDecimal shortfall() {
        return settledAmount == null ? Money.scale(claimedAmount)
                : Money.scale(claimedAmount.subtract(settledAmount));
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getPayerCode() {
        return payerCode;
    }

    public String getPreauthNo() {
        return preauthNo;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public String getDenialReason() {
        return denialReason;
    }
}
