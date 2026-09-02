package com.hms.billing.web;

import com.hms.billing.service.BillingConfigService;
import com.hms.billing.service.ClaimService;
import com.hms.billing.service.DayBookService;
import com.hms.billing.service.InvoiceService;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.security.Roles;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The revenue cycle over HTTP.
 *
 * <p>Three authorities, and the gaps between them are the whole design:
 * {@link Roles#BILLING_READ} lets a clinician see what a patient has been billed,
 * {@link Roles#BILLING_WRITE} lets a cashier raise an invoice and take money, and
 * {@link Roles#BILLING_CONFIG} — administrators alone — lets somebody change what things cost. A
 * cashier who could retune a price could discount a procedure to zero and then record it as paid in
 * full; a clinician who could post a payment would break the oldest financial control there is.
 *
 * <p>{@code POST /charges} is the one endpoint here written for a machine rather than a person. It
 * is what the event listener calls, and it is idempotent by database key rather than by care —
 * which is why it is safe to expose at all.
 */
@RestController
public class BillingController {

    private final BillingConfigService config;
    private final InvoiceService invoices;
    private final ClaimService claims;
    private final DayBookService dayBook;

    public BillingController(BillingConfigService config, InvoiceService invoices,
                             ClaimService claims, DayBookService dayBook) {
        this.config = config;
        this.invoices = invoices;
        this.claims = claims;
        this.dayBook = dayBook;
    }

    // ---- the price list ------------------------------------------------------

    @GetMapping("/charge-items")
    @PreAuthorize(Roles.BILLING_READ)
    public List<BillingDtos.ChargeItemResponse> chargeItems(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return config.catalogue(q, includeInactive);
    }

    @PostMapping("/charge-items")
    @PreAuthorize(Roles.BILLING_CONFIG)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingDtos.ChargeItemResponse addChargeItem(
            @Valid @RequestBody BillingDtos.CreateChargeItemRequest request) {
        return config.addItem(request);
    }

    @PatchMapping("/charge-items/{code}")
    @PreAuthorize(Roles.BILLING_CONFIG)
    public BillingDtos.ChargeItemResponse updateChargeItem(@PathVariable String code,
            @Valid @RequestBody BillingDtos.UpdateChargeItemRequest request) {
        return config.updateItem(code, request);
    }

    @GetMapping("/tax-rates")
    @PreAuthorize(Roles.BILLING_READ)
    public List<BillingDtos.TaxRateResponse> taxRates() {
        return config.rates();
    }

    /**
     * A rate change is a new row, never an edit.
     *
     * <p>An invoice raised last year must keep the rate it was raised under: GST changes by
     * statute, and a system that edited the rate in place would silently restate every historical
     * invoice the next time somebody recalculated one.
     */
    @PostMapping("/tax-rates")
    @PreAuthorize(Roles.BILLING_CONFIG)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingDtos.TaxRateResponse addTaxRate(
            @Valid @RequestBody BillingDtos.CreateTaxRateRequest request) {
        return config.addRate(request);
    }

    @GetMapping("/payers")
    @PreAuthorize(Roles.BILLING_READ)
    public List<BillingDtos.PayerResponse> payers() {
        return config.allPayers();
    }

    @PostMapping("/payers")
    @PreAuthorize(Roles.BILLING_CONFIG)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingDtos.PayerResponse addPayer(
            @Valid @RequestBody BillingDtos.CreatePayerRequest request) {
        return config.addPayer(request);
    }

    @PostMapping("/payers/{code}/tariffs")
    @PreAuthorize(Roles.BILLING_CONFIG)
    public BillingDtos.PayerResponse setTariff(@PathVariable String code,
            @Valid @RequestBody BillingDtos.SetTariffRequest request) {
        return config.setTariff(code, request);
    }

    // ---- invoices ------------------------------------------------------------

    @GetMapping("/invoices")
    @PreAuthorize(Roles.BILLING_READ)
    public List<BillingDtos.InvoiceResponse> invoices(
            @RequestParam(required = false) UUID patientId) {
        return patientId == null ? invoices.open() : invoices.forPatient(patientId);
    }

    @GetMapping("/invoices/{id}")
    @PreAuthorize(Roles.BILLING_READ)
    public BillingDtos.InvoiceResponse invoice(@PathVariable UUID id) {
        return invoices.read(id);
    }

    @PostMapping("/invoices")
    @PreAuthorize(Roles.BILLING_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingDtos.InvoiceResponse createInvoice(
            @Valid @RequestBody BillingDtos.CreateInvoiceRequest request) {
        return invoices.create(request);
    }

    @PostMapping("/invoices/{id}/lines")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.InvoiceResponse addLine(@PathVariable UUID id,
            @Valid @RequestBody BillingDtos.AddLineRequest request) {
        return invoices.addLine(id, request);
    }

    @PostMapping("/invoices/{id}/issue")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.InvoiceResponse issue(@PathVariable UUID id) {
        return invoices.issue(id);
    }

    @PostMapping("/invoices/{id}/cancel")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.InvoiceResponse cancel(@PathVariable UUID id,
            @Valid @RequestBody BillingDtos.CancelInvoiceRequest request) {
        return invoices.cancel(id, request.reason());
    }

    /**
     * Takes money.
     *
     * <p>A 409 here is a real answer and not a failure: it means the balance changed under the
     * cashier's feet, and the message says what is actually outstanding.
     */
    @PostMapping("/invoices/{id}/payments")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.InvoiceResponse pay(@PathVariable UUID id,
            @Valid @RequestBody BillingDtos.RecordPaymentRequest request) {
        return invoices.pay(id, request);
    }

    /**
     * Posts a charge from somewhere else in the platform.
     *
     * <p>Called by this service's own event listener, and reachable by an operator replaying a
     * charge that a broker lost. Either way the {@code posted_charges} key decides whether it
     * writes anything, so a replay answers {@code alreadyPosted} rather than billing twice.
     */
    @PostMapping("/charges")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.PostChargeResponse postCharge(
            @Valid @RequestBody BillingDtos.PostChargeRequest request) {
        return invoices.postCharge(request);
    }

    // ---- claims --------------------------------------------------------------

    @GetMapping("/claims")
    @PreAuthorize(Roles.BILLING_READ)
    public List<BillingDtos.ClaimResponse> claims(@RequestParam(required = false) String payerCode,
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return claims.list(payerCode, includeClosed);
    }

    @GetMapping("/claims/{id}")
    @PreAuthorize(Roles.BILLING_READ)
    public BillingDtos.ClaimResponse claim(@PathVariable UUID id) {
        return claims.read(id);
    }

    @PostMapping("/claims")
    @PreAuthorize(Roles.BILLING_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public BillingDtos.ClaimResponse raiseClaim(
            @Valid @RequestBody BillingDtos.RaiseClaimRequest request) {
        return claims.raise(request);
    }

    @PostMapping("/claims/{id}/submit")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.ClaimResponse submitClaim(@PathVariable UUID id) {
        return claims.submit(id);
    }

    @PostMapping("/claims/{id}/settle")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.ClaimResponse settleClaim(@PathVariable UUID id,
            @Valid @RequestBody BillingDtos.SettleClaimRequest request) {
        return claims.settle(id, request);
    }

    @PostMapping("/claims/{id}/deny")
    @PreAuthorize(Roles.BILLING_WRITE)
    public BillingDtos.ClaimResponse denyClaim(@PathVariable UUID id,
            @Valid @RequestBody BillingDtos.DenyClaimRequest request) {
        return claims.deny(id, request);
    }

    // ---- the day's position --------------------------------------------------

    @GetMapping("/day-book")
    @PreAuthorize(Roles.BILLING_READ)
    public BillingDtos.DayBookResponse dayBook(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return dayBook.on(on);
    }
}
