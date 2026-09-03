package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A bill, corrected: this much of the invoice is not owed.
 *
 * <p>Not a refund, and the difference is the whole reason there are two classes. A credit note says
 * what is <em>owed</em> — the wrong item was billed, the procedure was not performed, a payer's
 * tariff should have applied. A {@link Refund} says what happened to <em>cash</em>. A bill can be
 * credited with no money having moved at all, which is the ordinary case: the patient has not paid
 * yet and now owes less.
 *
 * <p>Immutable once written, like {@link Payment} and for the same reason: it is a numbered tax
 * document that has been handed to somebody by the time anybody notices it was wrong. A credit note
 * issued in error is corrected by billing the item again, not by editing the note.
 *
 * <p>It does not touch the invoice's total. The platform's rule is that a financial record does not
 * change after the fact — prices are snapshotted onto lines rather than joined — and the invoice's
 * {@code chk_not_overpaid} constraint depends on it: reducing the total of an invoice that has been
 * paid in full would make the amount paid exceed it. What the credit moves is the invoice's
 * {@code credited} column, and what is still owed is arithmetic over both.
 */
@Entity
@Table(name = "credit_notes")
public class CreditNote extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "number", nullable = false, length = 24, updatable = false)
    private String number;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "issued_by", nullable = false, length = 64, updatable = false)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt = Instant.now();

    protected CreditNote() {
    }

    public CreditNote(UUID invoiceId, String number, BigDecimal amount, String reason,
                      String issuedBy) {
        this.invoiceId = invoiceId;
        this.number = number;
        this.amount = Money.scale(amount);
        this.reason = reason;
        this.issuedBy = issuedBy;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public String getNumber() {
        return number;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
