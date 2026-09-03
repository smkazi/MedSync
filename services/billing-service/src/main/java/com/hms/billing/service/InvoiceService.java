package com.hms.billing.service;

import com.hms.billing.domain.BillingEnums;
import com.hms.billing.domain.BillingEnums.InvoiceStatus;
import com.hms.billing.domain.ChargeItem;
import com.hms.billing.domain.CreditNote;
import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceLine;
import com.hms.billing.domain.Money;
import com.hms.billing.domain.Payer;
import com.hms.billing.domain.Payment;
import com.hms.billing.domain.PostedCharge;
import com.hms.billing.domain.Refund;
import com.hms.billing.repo.CreditNoteRepository;
import com.hms.billing.repo.InvoiceCounterRepository;
import com.hms.billing.repo.InvoiceRepository;
import com.hms.billing.repo.PaymentRepository;
import com.hms.billing.repo.PostedChargeRepository;
import com.hms.billing.repo.RefundRepository;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoices, charges, payments, credit notes and refunds.
 *
 * <p>Three things here are database rules rather than code, and each one is the answer to a way
 * hospitals actually lose or double-charge money:
 *
 * <ul>
 *   <li><strong>A charge cannot post twice.</strong> {@code posted_charges} is keyed by where the
 *       charge came from, so a redelivered event collides and is reported as already-posted rather
 *       than billing the patient again.</li>
 *   <li><strong>An invoice cannot be overpaid.</strong> The payment is one conditional UPDATE, so
 *       two cashiers taking the same balance cannot both succeed.</li>
 *   <li><strong>An invoice number cannot be issued twice.</strong> One statement, the counter
 *       shape used three times already on this platform.</li>
 * </ul>
 */
@Service
public class InvoiceService {

    private final BillingClock clock;
    private final InvoiceRepository invoices;
    private final InvoiceCounterRepository counters;
    private final PaymentRepository payments;
    private final CreditNoteRepository creditNotes;
    private final RefundRepository refunds;
    private final PostedChargeRepository posted;
    private final BillingConfigService config;
    private final AuditService audit;
    private final EventPublisher events;
    private final String prefix;
    private final String creditPrefix;

    public InvoiceService(BillingClock clock, InvoiceRepository invoices,
                          InvoiceCounterRepository counters,
                          PaymentRepository payments, CreditNoteRepository creditNotes,
                          RefundRepository refunds, PostedChargeRepository posted,
                          BillingConfigService config, AuditService audit, EventPublisher events,
                          @Value("${hms.billing.invoice-prefix:INV}") String prefix,
                          @Value("${hms.billing.credit-note-prefix:CRN}") String creditPrefix) {
        this.clock = clock;
        this.invoices = invoices;
        this.counters = counters;
        this.payments = payments;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
        this.posted = posted;
        this.config = config;
        this.audit = audit;
        this.events = events;
        this.prefix = prefix;
        this.creditPrefix = creditPrefix;
    }

    // ---- invoices ------------------------------------------------------------

    @Transactional
    public BillingDtos.InvoiceResponse create(BillingDtos.CreateInvoiceRequest request) {
        LocalDate date = request.invoiceDate() == null ? clock.today() : request.invoiceDate();
        String payerCode = normalisePayer(request.payerCode());
        Invoice invoice = new Invoice(request.patientId(), request.patientMrn().trim(),
                request.encounterId(), payerCode, nextNumber(date), date);
        Invoice saved = invoices.save(invoice);
        audit.record("INVOICE_CREATED", "Invoice", saved.getId(),
                "%s for %s".formatted(saved.getNumber(), saved.getPatientMrn()));
        return toResponse(saved);
    }

    /**
     * The next number in the financial year's series.
     *
     * <p>Indian financial years run April to March, so an invoice dated 2026-02-14 belongs to the
     * 2025-26 series. Deriving the series from the invoice's own date rather than from today means
     * a back-dated invoice takes a number from the right year — which is the only thing an auditor
     * reading a numbered sequence cares about.
     */
    private String nextNumber(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        String series = "%d-%02d".formatted(startYear, (startYear + 1) % 100);
        int number = counters.issueNext(series);
        return "%s/%s/%05d".formatted(prefix, series, number);
    }

    @Transactional(readOnly = true)
    public BillingDtos.InvoiceResponse read(UUID id) {
        return toResponse(require(id));
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.InvoiceResponse> forPatient(UUID patientId) {
        return invoices.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.InvoiceResponse> open() {
        return invoices.findByStatusInOrderByInvoiceDateAsc(
                        List.of(InvoiceStatus.DRAFT, InvoiceStatus.ISSUED)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Adds a line, priced as of the invoice's own date.
     *
     * <p>Only to a draft. An issued invoice is a document somebody has been given, and adding a
     * line to one would change what they were asked to pay after they were asked — a credit note
     * and a second invoice is the honest way to do that, and neither exists here yet.
     */
    @Transactional
    public BillingDtos.InvoiceResponse addLine(UUID id, BillingDtos.AddLineRequest request) {
        Invoice invoice = require(id);
        if (!invoice.isDraft()) {
            throw new BadRequestException(
                    ("Invoice %s has been %s, so a line cannot be added to it. Raise a new invoice "
                            + "for further charges.").formatted(invoice.getNumber(),
                            invoice.getStatus().name().toLowerCase(java.util.Locale.ROOT)));
        }
        InvoiceLine line = priceLine(invoice, request.chargeItemCode(), request.qty(),
                request.discount(), request.description());
        invoice.addLine(line);
        invoices.save(invoice);
        audit.record("INVOICE_LINE_ADDED", "Invoice", invoice.getId(),
                "%s: %s x%s".formatted(invoice.getNumber(), line.getChargeItemCode(),
                        line.getQty()));
        return toResponse(invoice);
    }

    /** Prices one line against the charge list, the payer's tariff and the tax in force. */
    private InvoiceLine priceLine(Invoice invoice, String chargeItemCode, BigDecimal qty,
                                  BigDecimal discount, String description) {
        ChargeItem item = config.require(chargeItemCode);
        if (!item.isActive()) {
            throw new BadRequestException(
                    "'%s' (%s) is no longer charged for.".formatted(item.getName(), item.getCode()));
        }
        Payer payer = invoice.getPayerCode() == null ? null
                : config.requirePayer(invoice.getPayerCode());
        Optional<BigDecimal> tariff = config.tariffFor(invoice.getPayerCode(), item.getCode());
        Optional<BigDecimal> percent = item.isTaxable()
                ? config.percentOn(item.getTaxRateCode(), invoice.getInvoiceDate())
                : Optional.empty();

        Pricer.Priced priced = Pricer.price(item, payer, tariff, percent);
        String text = description == null || description.isBlank() ? item.getName()
                : description.trim();
        return new InvoiceLine(item.getCode(), text, qty, priced.unitPrice(),
                discount == null ? BigDecimal.ZERO : discount, priced.taxPercent());
    }

    @Transactional
    public BillingDtos.InvoiceResponse issue(UUID id) {
        Invoice invoice = require(id);
        if (!invoice.isDraft()) {
            throw new BadRequestException("Invoice %s is already %s.".formatted(invoice.getNumber(),
                    invoice.getStatus().name().toLowerCase(java.util.Locale.ROOT)));
        }
        if (invoice.getLines().isEmpty()) {
            throw new BadRequestException(
                    "An invoice with no lines is not a bill. Add what is being charged for first.");
        }
        invoice.issue();
        invoices.save(invoice);
        audit.record("INVOICE_ISSUED", "Invoice", invoice.getId(),
                "%s, total %s".formatted(invoice.getNumber(), invoice.getTotal()));
        publish("billing.invoice-issued", invoice, Map.of("total", invoice.getTotal().toString()));
        return toResponse(invoice);
    }

    @Transactional
    public BillingDtos.InvoiceResponse cancel(UUID id, String reason) {
        Invoice invoice = require(id);
        if (invoice.getAmountPaid().signum() > 0) {
            // Money has changed hands. Cancelling would make the invoice say the treatment was
            // never billed while the payment says it was paid for, and no reconciliation recovers
            // from that. Reversing a paid bill is a credit note and a refund: two documents that
            // leave the charge, the withdrawal and the payout all readable afterwards, which is
            // exactly what cancelling the invoice would destroy.
            throw new ConflictException(
                    ("%s has %s against it, so it cannot be cancelled. Reverse a paid invoice by "
                            + "crediting it and refunding what was taken — POST "
                            + "/invoices/%s/credit-notes, then /refunds.")
                            .formatted(invoice.getNumber(), invoice.getAmountPaid(),
                                    invoice.getId()));
        }
        invoice.cancel(reason);
        invoices.save(invoice);
        audit.record("INVOICE_CANCELLED", "Invoice", invoice.getId(),
                "%s: %s".formatted(invoice.getNumber(), reason));
        return toResponse(invoice);
    }

    // ---- charge capture ------------------------------------------------------

    /**
     * Posts a charge that came from somewhere else, exactly once.
     *
     * <p>Runs in its own transaction so a duplicate can be caught and reported without poisoning a
     * caller's — a Kafka consumer replaying a day of events posts hundreds of these, and one
     * duplicate must not roll back the rest.
     *
     * <p>The invoice is found or created: an in-patient's charges accumulate onto one draft for the
     * length of the stay, because a stay is one bill. Without an encounter there is nothing to
     * group by, so each charge gets its own draft — which is right for an outpatient consultation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillingDtos.PostChargeResponse postCharge(BillingDtos.PostChargeRequest request) {
        PostedCharge.Key key = new PostedCharge.Key(request.sourceType(), request.sourceId(),
                request.chargeItemCode().trim().toUpperCase(java.util.Locale.ROOT));
        Optional<PostedCharge> already = posted.findById(key);
        if (already.isPresent()) {
            // The ordinary case on a replay, and not an error. Reported so a consumer can count
            // duplicates rather than being told nothing happened.
            return new BillingDtos.PostChargeResponse(null, null,
                    already.get().getInvoiceLineId(), true,
                    "Already posted; nothing charged again.");
        }

        Invoice invoice = draftFor(request);
        InvoiceLine line = priceLine(invoice, request.chargeItemCode(), request.qty(), null,
                request.description());
        invoice.addLine(line);
        invoices.saveAndFlush(invoice);

        try {
            posted.saveAndFlush(new PostedCharge(request.sourceType(), request.sourceId(),
                    line.getChargeItemCode(), line.getId()));
        } catch (DataIntegrityViolationException ex) {
            // Somebody else posted the same charge between the check and the insert. The whole
            // transaction rolls back, which is what makes the line disappear with it, and the
            // caller is told the charge exists rather than that something went wrong.
            throw new ConflictException(
                    ("This charge has already been posted for %s %s. Nothing has been charged "
                            + "again.").formatted(request.sourceType(), request.sourceId()));
        }

        audit.record("CHARGE_POSTED", "Invoice", invoice.getId(),
                "%s from %s %s".formatted(line.getChargeItemCode(), request.sourceType(),
                        request.sourceId()));
        return new BillingDtos.PostChargeResponse(invoice.getId(), invoice.getNumber(),
                line.getId(), false,
                "%s charged to %s.".formatted(line.getDescription(), invoice.getNumber()));
    }

    private Invoice draftFor(BillingDtos.PostChargeRequest request) {
        if (request.encounterId() != null) {
            Optional<Invoice> existing = invoices.openDraftsFor(request.encounterId()).stream()
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        LocalDate today = clock.today();
        return invoices.save(new Invoice(request.patientId(), request.patientMrn().trim(),
                request.encounterId(), normalisePayer(request.payerCode()), nextNumber(today),
                today));
    }

    // ---- payments ------------------------------------------------------------

    /**
     * Takes money against an invoice.
     *
     * <p>The UPDATE is the control. Zero rows means one of three things and the message says which:
     * the invoice is not open, or the amount would overpay it. Both are refusals a cashier can act
     * on, and neither is a 500.
     */
    @Transactional
    public BillingDtos.InvoiceResponse pay(UUID id, BillingDtos.RecordPaymentRequest request) {
        Invoice invoice = require(id);
        BigDecimal amount = Money.scale(request.amount());
        if (!invoice.isOpen()) {
            throw new ConflictException("Invoice %s is %s and takes no payment."
                    .formatted(invoice.getNumber(),
                            invoice.getStatus().name().toLowerCase(java.util.Locale.ROOT)));
        }

        int applied = invoices.applyPayment(id, amount);
        if (applied == 0) {
            // Re-read, because the reason is a number somebody else may have just changed.
            Invoice now = require(id);
            throw new ConflictException(
                    ("%s cannot take %s: %s is outstanding of %s. Somebody may have just paid it."
                            .formatted(now.getNumber(), amount, now.outstanding(), now.getTotal())));
        }

        Payment payment = payments.save(new Payment(id, amount, request.method(),
                request.reference(), CurrentUser.usernameOrSystem()));
        // Ids are application-assigned in BaseEntity, so this holds — and it is asserted rather
        // than assumed because the alternative is an event carrying the string "null" as the
        // payment a reconciliation is supposed to find. The same guard OrderSetService needed.
        String paymentId = Objects.requireNonNull(payment.getId(),
                "a saved payment must have an id").toString();
        Invoice after = require(id);
        audit.record("PAYMENT_RECEIVED", "Invoice", id,
                "%s %s by %s".formatted(after.getNumber(), amount, request.method()));
        publish("billing.payment-received", after,
                Map.of("amount", amount.toString(), "method", request.method().name(),
                        "paymentId", paymentId));
        return toResponse(after);
    }

    // ---- credit notes and refunds --------------------------------------------

    /**
     * Credits part or all of an invoice: this much is not owed.
     *
     * <p>Allowed on a paid invoice, which is the whole point — over-billing is discovered after the
     * money has been taken far more often than before it. The result is a refundable balance rather
     * than an edit to anything already recorded, and paying that balance back is a separate act by
     * a separate role.
     *
     * <p>Refused on a cancelled invoice: there is nothing to forgive on a bill that was withdrawn
     * whole, and a credit note against one would be a document with no charge behind it.
     */
    @Transactional
    public BillingDtos.CreditNoteResponse credit(UUID id, BillingDtos.IssueCreditNoteRequest request) {
        Invoice invoice = require(id);
        BigDecimal amount = Money.scale(request.amount());
        if (invoice.getStatus() == BillingEnums.InvoiceStatus.CANCELLED) {
            throw new ConflictException(("%s was cancelled, so there is nothing on it to credit. A "
                    + "cancelled invoice was never money the hospital was owed.")
                    .formatted(invoice.getNumber()));
        }

        int applied = invoices.applyCredit(id, amount);
        if (applied == 0) {
            // Re-read: the reason is a number another administrator may have just moved.
            Invoice now = require(id);
            throw new ConflictException(("%s cannot be credited %s: %s of its %s is already "
                    + "credited, leaving %s that could be. Somebody may have just credited it.")
                    .formatted(now.getNumber(), amount, now.getCredited(), now.getTotal(),
                            Money.scale(now.getTotal().subtract(now.getCredited()))));
        }

        CreditNote note = creditNotes.save(new CreditNote(id, nextCreditNoteNumber(), amount,
                request.reason().trim(), CurrentUser.usernameOrSystem()));
        String noteId = Objects.requireNonNull(note.getId(),
                "a saved credit note must have an id").toString();
        Invoice after = require(id);
        audit.record("CREDIT_NOTE_ISSUED", "Invoice", id,
                "%s credits %s of %s".formatted(note.getNumber(), amount, after.getNumber()));
        publish("billing.credit-note-issued", after,
                Map.of("amount", amount.toString(), "creditNote", note.getNumber(),
                        "creditNoteId", noteId));
        return toCreditNoteResponse(note, after);
    }

    /**
     * Pays money back.
     *
     * <p>The UPDATE is the control, and its two conditions answer different questions: money cannot
     * go back that never arrived, and money cannot go back beyond what a credit note has said is not
     * owed. The second is why the refusal quotes the credited figure — the fix for the usual refusal
     * is not a smaller refund but a credit note somebody has to authorise.
     */
    @Transactional
    public BillingDtos.RefundResponse refund(UUID id, BillingDtos.PayRefundRequest request) {
        require(id);
        BigDecimal amount = Money.scale(request.amount());

        int applied = invoices.applyRefund(id, amount);
        if (applied == 0) {
            Invoice now = require(id);
            throw new ConflictException(("%s cannot refund %s: %s is refundable. Money goes back "
                    + "only once a credit note says it is not owed — %s credited, %s received, %s "
                    + "already paid back.")
                    .formatted(now.getNumber(), amount, now.refundable(), now.getCredited(),
                            now.getAmountPaid(), now.getRefunded()));
        }

        Refund refund = refunds.save(new Refund(id, request.creditNoteId(), amount,
                request.method(), request.reference(), CurrentUser.usernameOrSystem()));
        String refundId = Objects.requireNonNull(refund.getId(),
                "a saved refund must have an id").toString();
        Invoice after = require(id);
        audit.record("REFUND_PAID", "Invoice", id,
                "%s refunded %s by %s".formatted(after.getNumber(), amount, request.method()));
        publish("billing.refund-paid", after,
                Map.of("amount", amount.toString(), "method", request.method().name(),
                        "refundId", refundId));
        return toRefundResponse(refund, after);
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.CreditNoteResponse> creditNotesFor(UUID id) {
        Invoice invoice = require(id);
        return creditNotes.findByInvoiceIdOrderByIssuedAt(id).stream()
                .map(note -> toCreditNoteResponse(note, invoice))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.RefundResponse> refundsFor(UUID id) {
        Invoice invoice = require(id);
        return refunds.findByInvoiceIdOrderByPaidAt(id).stream()
                .map(refund -> toRefundResponse(refund, invoice))
                .toList();
    }

    /**
     * The next credit-note number, in its own series.
     *
     * <p>Shares the counter table with invoices but not the counter *row*: the series carries its
     * own prefix, so credit notes count 1, 2, 3 within the financial year independently. Putting
     * them on the invoice sequence would leave gaps in the invoice numbering, which is precisely
     * what an auditor reads that sequence to detect.
     *
     * <p>Numbered from today rather than from the invoice's date, deliberately: a credit note is a
     * document of the day it was issued, whatever the bill it corrects belongs to.
     */
    private String nextCreditNoteNumber() {
        LocalDate today = clock.today();
        int startYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String year = "%d-%02d".formatted(startYear, (startYear + 1) % 100);
        int number = counters.issueNext("%s-%s".formatted(creditPrefix, year));
        return "%s/%s/%05d".formatted(creditPrefix, year, number);
    }

    private BillingDtos.CreditNoteResponse toCreditNoteResponse(CreditNote note, Invoice invoice) {
        return new BillingDtos.CreditNoteResponse(note.getId(), note.getNumber(), invoice.getId(),
                invoice.getNumber(), note.getAmount(), note.getReason(), note.getIssuedBy(),
                note.getIssuedAt(), invoice.getCredited(), invoice.payable(),
                invoice.outstanding(), invoice.refundable());
    }

    private BillingDtos.RefundResponse toRefundResponse(Refund refund, Invoice invoice) {
        return new BillingDtos.RefundResponse(refund.getId(), invoice.getId(), invoice.getNumber(),
                refund.getCreditNoteId(), refund.getAmount(), refund.getMethod(),
                refund.getReference(), refund.getPaidBy(), refund.getPaidAt(),
                invoice.getRefunded(), invoice.refundable());
    }

    // ---- helpers -------------------------------------------------------------

    Invoice require(UUID id) {
        return invoices.findById(id)
                .orElseThrow(() -> new NotFoundException("No invoice with id " + id));
    }

    private String normalisePayer(String payerCode) {
        if (payerCode == null || payerCode.isBlank()) {
            return null;
        }
        return config.requirePayer(payerCode).getCode();
    }

    BillingDtos.InvoiceResponse toResponse(Invoice invoice) {
        return new BillingDtos.InvoiceResponse(invoice.getId(), invoice.getNumber(),
                invoice.getPatientId(), invoice.getPatientMrn(), invoice.getEncounterId(),
                invoice.getPayerCode(), invoice.getStatus(), invoice.getSubtotal(),
                invoice.getDiscount(), invoice.getTaxTotal(), invoice.getTotal(),
                invoice.getAmountPaid(), invoice.getCredited(), invoice.getRefunded(),
                invoice.payable(), invoice.outstanding(), invoice.refundable(),
                invoice.getInvoiceDate(),
                invoice.getIssuedAt(), invoice.getCancelledAt(), invoice.getCancelledReason(),
                invoice.getLines().stream()
                        .map(line -> new BillingDtos.InvoiceLineResponse(line.getId(),
                                line.getChargeItemCode(), line.getDescription(), line.getQty(),
                                line.getUnitPrice(), line.getDiscount(), line.getTaxPercent(),
                                line.getTaxAmount(), line.getLineTotal()))
                        .toList(),
                payments.findByInvoiceIdOrderByReceivedAt(invoice.getId()).stream()
                        .map(payment -> new BillingDtos.PaymentResponse(payment.getId(),
                                payment.getAmount(), payment.getMethod(), payment.getReference(),
                                payment.getReceivedBy(), payment.getReceivedAt()))
                        .toList(),
                // The two registers travel with the invoice, because "why is this bill smaller
                // than the treatment" and "where did that money go" are questions asked while
                // looking at it, and a screen that has to fetch them separately is a screen that
                // shows a total nobody can explain.
                creditNotes.findByInvoiceIdOrderByIssuedAt(invoice.getId()).stream()
                        .map(note -> toCreditNoteResponse(note, invoice))
                        .toList(),
                refunds.findByInvoiceIdOrderByPaidAt(invoice.getId()).stream()
                        .map(refund -> toRefundResponse(refund, invoice))
                        .toList());
    }

    private void publish(String type, Invoice invoice, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("patientId", invoice.getPatientId().toString());
        payload.put("mrn", invoice.getPatientMrn());
        payload.put("invoiceNumber", invoice.getNumber());
        payload.put("status", invoice.getStatus().name());
        events.publish(Topics.BILLING, DomainEvent.of(type, "Invoice", invoice.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }
}
