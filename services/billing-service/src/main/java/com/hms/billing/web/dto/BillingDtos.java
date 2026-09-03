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

    public record InvoiceResponse(UUID id, String number, UUID patientId, String patientMrn,
                                 UUID encounterId, String payerCode,
                                 BillingEnums.InvoiceStatus status, BigDecimal subtotal,
                                 BigDecimal discount, BigDecimal taxTotal, BigDecimal total,
                                 BigDecimal amountPaid, BigDecimal outstanding,
                                 LocalDate invoiceDate, Instant issuedAt, Instant cancelledAt,
                                 String cancelledReason, List<InvoiceLineResponse> lines,
                                 List<PaymentResponse> payments) {

        public InvoiceResponse {
            lines = lines == null ? List.of() : List.copyOf(lines);
            payments = payments == null ? List.of() : List.copyOf(payments);
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
    public record DayBookResponse(LocalDate on, BigDecimal billed, BigDecimal collected,
                                  BigDecimal outstanding, int invoices, int payments,
                                  List<MethodTotal> byMethod) {

        public DayBookResponse {
            byMethod = byMethod == null ? List.of() : List.copyOf(byMethod);
        }
    }

    public record MethodTotal(BillingEnums.PaymentMethod method, BigDecimal amount, int count) {
    }

    public record MessageResponse(String message) {
    }
}
