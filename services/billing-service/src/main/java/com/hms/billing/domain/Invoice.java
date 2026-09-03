package com.hms.billing.domain;

import com.hms.billing.domain.BillingEnums.InvoiceStatus;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One bill.
 *
 * <p><strong>The money is not managed here.</strong> {@code amountPaid} has no setter and this
 * class has no {@code pay} method, deliberately: a payment is one conditional UPDATE in the
 * repository, because two cashiers taking the same balance both read the same number and a
 * read-modify-write would let the second silently undo the first. The field is mapped so it can be
 * read; changing it goes through SQL.
 *
 * <p>The totals <em>are</em> computed here, from the lines, in {@link #recompute()}. That is safe
 * because a line is only added to a DRAFT invoice by one request at a time, and it keeps the
 * arithmetic in one readable place rather than spread across the SQL.
 */
@Entity
@Table(name = "invoices")
public class Invoice extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "payer_code", length = 32)
    private String payerCode;

    @Column(name = "number", nullable = false, length = 24, updatable = false)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "subtotal", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal = Money.scale(BigDecimal.ZERO);

    @Column(name = "discount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discount = Money.scale(BigDecimal.ZERO);

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal = Money.scale(BigDecimal.ZERO);

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total = Money.scale(BigDecimal.ZERO);

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2, insertable = false,
            updatable = false)
    private BigDecimal amountPaid = Money.scale(BigDecimal.ZERO);

    /**
     * How much of this invoice has been credited — said in writing not to be owed.
     *
     * <p>Not managed here, exactly like {@link #amountPaid}: it moves only through the single
     * statement in {@code InvoiceRepository.applyCredit}, which is what makes two administrators
     * crediting at once safe. Neither insertable nor updatable through this entity, so a mapping
     * mistake cannot route around the guard.
     *
     * <p>A credit note deliberately leaves {@code total} alone — see {@link CreditNote} — so what
     * is still owed is arithmetic over four columns rather than a mutation of one. {@link
     * #outstanding()} and {@link #refundable()} are that arithmetic.
     */
    @Column(name = "credited", nullable = false, precision = 14, scale = 2, insertable = false,
            updatable = false)
    private BigDecimal credited = Money.scale(BigDecimal.ZERO);

    /** How much has been paid back out, on exactly the same terms as {@link #credited}. */
    @Column(name = "refunded", nullable = false, precision = 14, scale = 2, insertable = false,
            updatable = false)
    private BigDecimal refunded = Money.scale(BigDecimal.ZERO);

    /**
     * The date this invoice belongs to, and no default.
     *
     * <p>Deliberately not {@code LocalDate.now()}: what "today" is belongs to the deployment's own
     * zone and is answered by {@code BillingClock}, not by whichever zone a container happens to
     * run in. An entity that quietly dated itself would put the JVM's opinion on a financial
     * record, and the column is NOT NULL so a caller that forgets fails loudly instead.
     */
    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 255)
    private String cancelledReason;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(UUID patientId, String patientMrn, UUID encounterId, String payerCode,
                   String number, LocalDate invoiceDate) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.encounterId = encounterId;
        this.payerCode = payerCode;
        this.number = number;
        this.invoiceDate = invoiceDate;
    }

    public void addLine(InvoiceLine line) {
        lines.add(line);
        line.setInvoice(this);
        recompute();
    }

    public void removeLine(InvoiceLine line) {
        lines.remove(line);
        recompute();
    }

    /**
     * Recomputes the totals from the lines.
     *
     * <p>Summed from the lines rather than accumulated as each is added, so a removed line cannot
     * leave a total that no longer matches what is on the invoice. Every amount is a
     * {@code BigDecimal} scaled to two places with an explicit rounding mode — see
     * {@link Money#scale}.
     */
    public void recompute() {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal taxes = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            gross = gross.add(line.gross());
            discounts = discounts.add(line.getDiscount());
            taxes = taxes.add(line.getTaxAmount());
        }
        this.subtotal = Money.scale(gross);
        this.discount = Money.scale(discounts);
        this.taxTotal = Money.scale(taxes);
        this.total = Money.scale(gross.subtract(discounts).add(taxes));
    }

    public void issue() {
        this.status = InvoiceStatus.ISSUED;
        this.issuedAt = Instant.now();
    }

    public void cancel(String reason) {
        this.status = InvoiceStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelledReason = reason;
    }

    public boolean isDraft() {
        return status == InvoiceStatus.DRAFT;
    }

    public boolean isOpen() {
        return status == InvoiceStatus.DRAFT || status == InvoiceStatus.ISSUED;
    }

    /** What was charged, less what has been credited: the amount actually payable. */
    public BigDecimal payable() {
        return Money.scale(total.subtract(credited));
    }

    /** What has been received and kept: paid in, less paid back out. */
    public BigDecimal received() {
        return Money.scale(amountPaid.subtract(refunded));
    }

    /**
     * What the patient still owes, and never a negative number.
     *
     * <p>Credited amounts come off the payable side rather than off {@code total}, so an invoice
     * fully credited before payment reads as nothing owed while still showing what was originally
     * charged and why it was withdrawn. When the hospital has been paid more than is now payable,
     * this is zero and {@link #refundable()} carries the difference — an amount owed *back* is not
     * a negative debt, and reporting it as one is how a receivables total silently comes up short.
     */
    public BigDecimal outstanding() {
        BigDecimal owed = payable().subtract(received());
        return owed.signum() > 0 ? Money.scale(owed) : Money.scale(BigDecimal.ZERO);
    }

    /**
     * What the hospital owes back, and never a negative number.
     *
     * <p>The exact complement of {@link #outstanding()}: at most one of the two is ever positive.
     * This is money held against a charge that has since been credited, and it is what a refund
     * draws on.
     */
    public BigDecimal refundable() {
        BigDecimal back = received().subtract(payable());
        return back.signum() > 0 ? Money.scale(back) : Money.scale(BigDecimal.ZERO);
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public String getPayerCode() {
        return payerCode;
    }

    public String getNumber() {
        return number;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public BigDecimal getCredited() {
        return credited;
    }

    public BigDecimal getRefunded() {
        return refunded;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledReason() {
        return cancelledReason;
    }

    public List<InvoiceLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
