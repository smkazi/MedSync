package com.hms.billing.domain;

import com.hms.billing.domain.BillingEnums.PaymentMethod;
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
 * Money given back.
 *
 * <p>The mirror of {@link Payment}, and deliberately its own row rather than a payment with a
 * negative amount. A negative payment would be arithmetically convenient and would quietly break
 * three things: the invoice's {@code chk_paid_not_negative} constraint, the single-statement
 * payment guard that makes two cashiers safe (its {@code amount_paid + :amount <= total} test is
 * meaningless for a negative amount), and every report that sums payments to answer what the
 * hospital took today. Money out is a different fact from money in and it gets a different table.
 *
 * <p>A refund cannot exceed what was received, and cannot exceed what a {@link CreditNote} has said
 * is not owed — both enforced in the database as well as in the service. The second is the one that
 * matters: handing money back on a bill still recorded as owed leaves the patient owing it again
 * the next time anybody reads the invoice.
 *
 * <p>The method is the same vocabulary a payment uses, and need not match how the money arrived:
 * cash taken at the desk is often returned by transfer, and a card refund goes back to the card
 * whatever the patient would prefer.
 *
 * <p>Immutable, like every other financial row here. A refund paid to the wrong person is a
 * receivable again, not an edit.
 */
@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "credit_note_id", updatable = false)
    private UUID creditNoteId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    @Column(name = "reference", length = 64, updatable = false)
    private String reference;

    @Column(name = "paid_by", nullable = false, length = 64, updatable = false)
    private String paidBy;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt = Instant.now();

    protected Refund() {
    }

    public Refund(UUID invoiceId, UUID creditNoteId, BigDecimal amount, PaymentMethod method,
                  String reference, String paidBy) {
        this.invoiceId = invoiceId;
        this.creditNoteId = creditNoteId;
        this.amount = Money.scale(amount);
        this.method = method;
        this.reference = reference;
        this.paidBy = paidBy;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public UUID getCreditNoteId() {
        return creditNoteId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
