package com.hms.billing.web.dto;

import com.hms.billing.domain.BillingEnums;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {
    }

    // ---- configuration -------------------------------------------------------

    public record TaxRateResponse(UUID id, String code, String name, BigDecimal percent,
                                  LocalDate effectiveFrom, LocalDate effectiveTo,
                                  boolean inForceToday) {
    }

    /**
     * A new rate for a code.
     *
     * @param effectiveFrom the day it starts. The predecessor is closed the same day, so the two
     *                      cannot both apply — a rate change is a succession rather than an edit,
     *                      because invoices already raised must keep the rate they were raised
     *                      under.
     */
    public record CreateTaxRateRequest(@NotBlank @Size(max = 24) String code,
                                       @NotBlank @Size(max = 120) String name,
                                       @NotNull @DecimalMin("0") @Digits(integer = 3, fraction = 2)
                                       BigDecimal percent,
                                       @NotNull LocalDate effectiveFrom) {
    }

    public record ChargeItemResponse(UUID id, String code, String name, String departmentCode,
                                     BigDecimal unitPrice, boolean taxable, String taxRateCode,
                                     BigDecimal taxPercentToday, boolean active) {
    }

    public record CreateChargeItemRequest(@NotBlank @Size(max = 32) String code,
                                          @NotBlank @Size(max = 160) String name,
                                          @Size(max = 32) String departmentCode,
                                          @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
                                          BigDecimal unitPrice,
                                          Boolean taxable,
                                          @Size(max = 24) String taxRateCode) {
    }

    public record UpdateChargeItemRequest(@Size(max = 160) String name,
                                          @DecimalMin("0") @Digits(integer = 12, fraction = 2)
                                          BigDecimal unitPrice,
                                          Boolean active) {
    }

    public record PayerResponse(UUID id, String code, String name, boolean requiresPreauth,
                                boolean allowsCopay, boolean settlesDirectly, boolean taxExempt,
                                boolean active, List<TariffResponse> tariffs) {

        public PayerResponse {
            tariffs = tariffs == null ? List.of() : List.copyOf(tariffs);
        }
    }

    public record TariffResponse(String chargeItemCode, String chargeItemName, BigDecimal listPrice,
                                 BigDecimal agreedPrice) {
    }

    public record CreatePayerRequest(@NotBlank @Size(max = 32) String code,
                                     @NotBlank @Size(max = 160) String name,
                                     Boolean requiresPreauth, Boolean allowsCopay,
                                     Boolean settlesDirectly, Boolean taxExempt) {
    }

    public record SetTariffRequest(@NotBlank @Size(max = 32) String chargeItemCode,
                                   @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
                                   BigDecimal price) {
    }

    // ---- invoices ------------------------------------------------------------

    public record InvoiceLineResponse(UUID id, String chargeItemCode, String description,
                                     BigDecimal qty, BigDecimal unitPrice, BigDecimal discount,
                                     BigDecimal taxPercent, BigDecimal taxAmount,
                                     BigDecimal lineTotal) {
    }

    /**
     * @param credited   how much of this bill has been said in writing not to be owed
     * @param refunded   how much money has gone back out
     * @param payable    total less credited: what is actually chargeable
     * @param refundable held against a charge since credited, and what a refund draws on. At most
     *                   one of this and {@code outstanding} is ever positive, which is the property
     *                   that makes the pair worth reporting rather than one signed number.
     */
    public record InvoiceResponse(UUID id, String number, UUID patientId, String patientMrn,
                                 UUID encounterId, String payerCode,
                                 BillingEnums.InvoiceStatus status, BigDecimal subtotal,
                                 BigDecimal discount, BigDecimal taxTotal, BigDecimal total,
                                 BigDecimal amountPaid, BigDecimal credited, BigDecimal refunded,
                                 BigDecimal payable, BigDecimal outstanding, BigDecimal refundable,
                                 LocalDate invoiceDate, Instant issuedAt, Instant cancelledAt,
                                 String cancelledReason, List<InvoiceLineResponse> lines,
                                 List<PaymentResponse> payments,
                                 List<CreditNoteResponse> creditNotes,
                                 List<RefundResponse> refunds) {

        public InvoiceResponse {
            lines = lines == null ? List.of() : List.copyOf(lines);
            payments = payments == null ? List.of() : List.copyOf(payments);
            creditNotes = creditNotes == null ? List.of() : List.copyOf(creditNotes);
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
        }
    }

    public record CreateInvoiceRequest(@NotNull UUID patientId,
                                       @NotBlank @Size(max = 24) String patientMrn,
                                       UUID encounterId,
                                       @Size(max = 32) String payerCode,
                                       /*
                                        * The date the tax rates are resolved against. Optional, and
                                        * today when omitted; a back-dated invoice is taxed at the
                                        * rate that applied on its own date rather than today's.
                                        */
                                       LocalDate invoiceDate) {
    }

    /**
     * @param qty      how many. Fractional, because a bed-day can be half a day and a dressing
     *                 pack cannot be 1.5 — the quantity's meaning belongs to the charge item.
     * @param discount an absolute amount off the line, not a percentage. A percentage looks
     *                 friendlier and rounds differently on every line, and the audit question is
     *                 always "how much was taken off", never "what percentage".
     */
    public record AddLineRequest(@NotBlank @Size(max = 32) String chargeItemCode,
                                 @NotNull @DecimalMin(value = "0", inclusive = false)
                                 @Digits(integer = 8, fraction = 2) BigDecimal qty,
                                 @DecimalMin("0") @Digits(integer = 12, fraction = 2)
                                 BigDecimal discount,
                                 @Size(max = 255) String description) {
    }

    /**
     * A charge arriving from somewhere else.
     *
     * @param sourceType where it came from. With {@code sourceId} and the charge code it forms the
     *                   key of {@code posted_charges}, which is what makes posting the same charge
     *                   twice a no-op rather than a second bill.
     */
    public record PostChargeRequest(@NotNull BillingEnums.ChargeSource sourceType,
                                    @NotNull UUID sourceId,
                                    @NotNull UUID patientId,
                                    @NotBlank @Size(max = 24) String patientMrn,
                                    UUID encounterId,
                                    @Size(max = 32) String payerCode,
                                    @NotBlank @Size(max = 32) String chargeItemCode,
                                    @NotNull @DecimalMin(value = "0", inclusive = false)
                                    @Digits(integer = 8, fraction = 2) BigDecimal qty,
                                    @Size(max = 255) String description) {
    }

    /**
     * What posting a charge did.
     *
     * @param alreadyPosted true when this exact charge had already been posted and nothing was
     *                      written. Reported rather than hidden, because a consumer replaying a
     *                      day of events wants to know how many were duplicates.
     */
    public record PostChargeResponse(UUID invoiceId, String invoiceNumber, UUID invoiceLineId,
                                     boolean alreadyPosted, String message) {
    }

    public record RecordPaymentRequest(@NotNull @DecimalMin(value = "0", inclusive = false)
                                       @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                       @NotNull BillingEnums.PaymentMethod method,
                                       @Size(max = 64) String reference) {
    }

    public record PaymentResponse(UUID id, BigDecimal amount, BillingEnums.PaymentMethod method,
                                  String reference, String receivedBy, Instant receivedAt) {
    }

    /**
     * Issues a credit note.
     *
     * <p>The reason is required and has a floor, because a credit note's entire purpose is to say
     * why a charge was withdrawn — and "adjustment" is what a free-text box collects when it does
     * not insist on a sentence. Same judgement, and the same floor, as the break-glass reason.
     */
    public record IssueCreditNoteRequest(@NotNull @DecimalMin(value = "0", inclusive = false)
                                         @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                         @NotBlank @Size(min = 20, max = 255) String reason) {
    }

    public record CreditNoteResponse(UUID id, String number, UUID invoiceId, String invoiceNumber,
                                     BigDecimal amount, String reason, String issuedBy,
                                     Instant issuedAt, BigDecimal invoiceCredited,
                                     BigDecimal invoicePayable, BigDecimal invoiceOutstanding,
                                     BigDecimal invoiceRefundable) {
    }

    /**
     * Pays a refund.
     *
     * <p>{@code creditNoteId} records which authorisation the payout draws on and is optional only
     * because one refund may settle several notes; what actually enforces the authorisation is the
     * conditional UPDATE and the invoice's own CHECK, on the sum rather than the link.
     */
    public record PayRefundRequest(@NotNull @DecimalMin(value = "0", inclusive = false)
                                   @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                   @NotNull BillingEnums.PaymentMethod method,
                                   UUID creditNoteId,
                                   @Size(max = 64) String reference) {
    }

    public record RefundResponse(UUID id, UUID invoiceId, String invoiceNumber, UUID creditNoteId,
                                 BigDecimal amount, BillingEnums.PaymentMethod method,
                                 String reference, String paidBy, Instant paidAt,
                                 BigDecimal invoiceRefunded, BigDecimal invoiceRefundable) {
    }

    /**
     * What the patient owes in total, and across how many bills.
     *
     * <p>One number rather than a list to sum, because the browser summing it would make the figure
     * depend on how many pages had loaded — and the number on a portal's front page is the one thing
     * a patient will quote back to the counter.
     */
    public record PortalBalance(java.math.BigDecimal outstanding, long unpaidInvoices, long invoices) {
    }

    public record CancelInvoiceRequest(@NotBlank @Size(max = 255) String reason) {
    }

    // ---- claims --------------------------------------------------------------

    public record ClaimResponse(UUID id, UUID invoiceId, String invoiceNumber, String payerCode,
                                String preauthNo, Instant submittedAt,
                                BillingEnums.ClaimStatus status, BigDecimal claimedAmount,
                                BigDecimal settledAmount, BigDecimal shortfall,
                                String denialReason) {
    }

    public record RaiseClaimRequest(@NotNull UUID invoiceId, @Size(max = 64) String preauthNo) {
    }

    public record SettleClaimRequest(@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2)
                                     BigDecimal settledAmount) {
    }

    public record DenyClaimRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /** The day's position: what was billed, what was collected, what is still owed. */
    /**
     * @param credited  withdrawn from the day's billing by credit note. Reported beside
     *                  {@code billed} rather than subtracted from it, because what was charged and
     *                  what was then withdrawn are two facts and a netted figure hides the second.
     * @param refunded  money paid back out during the day. {@code collected} is gross, so
     *                  {@code collected - refunded} is what the day actually took — reported as two
     *                  numbers for the same reason.
     * @param net       {@code collected - refunded}, computed here so every screen agrees on it.
     * @param byMethod  collections by method, and {@code refundsByMethod} the payouts, because a
     *                  drawer is counted against cash in *minus* cash out and a card batch nets
     *                  its own refunds.
     */
    public record DayBookResponse(LocalDate on, BigDecimal billed, BigDecimal credited,
                                  BigDecimal collected, BigDecimal refunded, BigDecimal net,
                                  BigDecimal outstanding, int invoices, int payments, int refunds,
                                  List<MethodTotal> byMethod, List<MethodTotal> refundsByMethod) {

        public DayBookResponse {
            byMethod = byMethod == null ? List.of() : List.copyOf(byMethod);
            refundsByMethod = refundsByMethod == null ? List.of() : List.copyOf(refundsByMethod);
        }
    }

    public record MethodTotal(BillingEnums.PaymentMethod method, BigDecimal amount, int count) {
    }

    /**
     * What is owed, by who owes it and how long they have owed it.
     *
     * @param on    the day the report was run against
     * @param rows  one per payer, self-paying included, worst-aged first
     * @param total the same four buckets summed across every row, computed in the service so a
     *              screen never adds two money figures together
     */
    public record ReceivablesResponse(LocalDate on, List<AgeingBucket> rows, AgeingBucket total) {

        public ReceivablesResponse {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * One line of the ageing report.
     *
     * <p>The four buckets are disjoint, so {@code total} is their sum and not a separate query;
     * two figures that could disagree about the same invoice would be worse than one.
     *
     * @param payerCode the payer, or null when the patient is paying for themselves
     * @param payerName the payer's name, or "Self-paying" — resolved here rather than on a screen,
     *                  which would otherwise need the payer list to render a receivables report
     * @param current   raised within the last 30 days
     * @param days90    90 days and older: the money least likely to arrive on its own
     */
    public record AgeingBucket(String payerCode, String payerName, BigDecimal current,
                               BigDecimal days30, BigDecimal days60, BigDecimal days90,
                               BigDecimal total, long invoices) {
    }

    /**
     * Opens a drawer.
     *
     * @param openingFloat what is in it at the start, counted. Zero is legitimate; absent is not,
     *                     because opening a shift is an act of counting and a default would let
     *                     somebody skip it without noticing.
     */
    public record OpenCashSessionRequest(@NotNull @DecimalMin("0")
                                         @Digits(integer = 12, fraction = 2)
                                         BigDecimal openingFloat) {
    }

    /**
     * Counts a drawer and closes the shift.
     *
     * <p>No expected figure is accepted — the platform computes it. A reconciliation whose
     * difference is supplied by the person being reconciled is not one.
     *
     * @param notes required when the count disagrees with the platform, in the service and again
     *             in the database
     */
    public record CloseCashSessionRequest(@NotNull @DecimalMin("0")
                                          @Digits(integer = 12, fraction = 2)
                                          BigDecimal declaredCash,
                                          @Size(max = 1000) String notes) {
    }

    /**
     * One shift.
     *
     * @param expectedCash        what the drawer should hold: float, plus cash in, less cash out.
     *                            Live while the session is open, and the figure frozen at the
     *                            count once it is closed
     * @param variance            declared less expected, null while open
     * @param varianceDescription "over", "short" or "exact" — the word a person uses for the number
     * @param taken               everything that came in through this drawer, by method. The
     *                            non-cash rows are ticked against the terminal's own batch rather
     *                            than counted, which is why they are reported and not declared
     * @param paidBack            and everything that went back out
     */
    public record CashSessionResponse(UUID id, String cashier,
                                      BillingEnums.CashSessionStatus status, Instant openedAt,
                                      BigDecimal openingFloat, Instant closedAt, String closedBy,
                                      BigDecimal declaredCash, BigDecimal expectedCash,
                                      BigDecimal variance, String varianceDescription, String notes,
                                      List<MethodTotal> taken, List<MethodTotal> paidBack) {

        public CashSessionResponse {
            taken = taken == null ? List.of() : List.copyOf(taken);
            paidBack = paidBack == null ? List.of() : List.copyOf(paidBack);
        }
    }

    public record MessageResponse(String message) {
    }
}
