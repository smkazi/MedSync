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
 * Money received.
 *
 * <p>Immutable once written. A payment that turns out to be wrong is corrected by another row — a
 * refund, when this service grows one — and never by editing this one: a receipt has been given
 * out by the time anybody notices, and the receipt is the record.
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    @Column(name = "reference", length = 64, updatable = false)
    private String reference;

    @Column(name = "received_by", nullable = false, length = 64, updatable = false)
    private String receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    /**
     * The drawer this went through, when one was open for the person who took it.
     *
     * <p>Stamped as the money moves rather than inferred later from timestamps, which looks
     * equivalent and is not: a window query reassigns whatever was taken between one shift closing
     * and the next opening. Null is legitimate and means exactly what it says — money taken with no
     * shift open, which the cash-up reports rather than absorbs.
     */
    @Column(name = "cash_session_id", updatable = false)
    private UUID cashSessionId;

    protected Payment() {
    }

    public Payment(UUID invoiceId, BigDecimal amount, PaymentMethod method, String reference,
                   String receivedBy) {
        this.invoiceId = invoiceId;
        this.amount = Money.scale(amount);
        this.method = method;
        this.reference = reference;
        this.receivedBy = receivedBy;
    }

public UUID getCashSessionId() {
        return cashSessionId;
    }

    public void setCashSessionId(UUID cashSessionId) {
        this.cashSessionId = cashSessionId;
    }

    public UUID getInvoiceId() {
        return invoiceId;
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

    public String getReceivedBy() {
        return receivedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
