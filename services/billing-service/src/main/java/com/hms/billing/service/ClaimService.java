package com.hms.billing.service;

import com.hms.billing.domain.BillingEnums.ClaimStatus;
import com.hms.billing.domain.BillingEnums.PaymentMethod;
import com.hms.billing.domain.Claim;
import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.Money;
import com.hms.billing.domain.Payer;
import com.hms.billing.repo.ClaimRepository;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the hospital asks a payer for, and what comes back.
 *
 * <p>Three decisions here are worth naming, because each is a way a revenue cycle goes wrong
 * quietly:
 *
 * <ul>
 *   <li><strong>A claim is raised against an issued invoice, never a draft.</strong> A draft is
 *       still collecting charges — an in-patient's bed-days land on it nightly — and claiming for
 *       a bill that has not stopped growing means claiming the wrong amount and re-arguing it
 *       later.</li>
 *   <li><strong>Settling a claim takes money.</strong> The settlement is recorded as an
 *       {@code INSURANCE} payment against the invoice through the same path a cashier's cash goes
 *       through, so {@code amount_paid} is the single answer to "what has been collected". A claim
 *       that settled without touching the invoice would leave the patient owing money the payer
 *       had already paid.</li>
 *   <li><strong>The shortfall is reported, never absorbed.</strong> A payer settling short leaves
 *       a balance that somebody has to bill to the patient or write off. This service will not
 *       decide which; it makes the number impossible to miss.</li>
 * </ul>
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final InvoiceService invoices;
    private final BillingConfigService config;
    private final AuditService audit;

    public ClaimService(ClaimRepository claims, InvoiceService invoices,
                        BillingConfigService config, AuditService audit) {
        this.claims = claims;
        this.invoices = invoices;
        this.config = config;
        this.audit = audit;
    }

    /**
     * Raises a claim for what an issued invoice still has outstanding.
     *
     * <p>The claimed amount is the outstanding balance rather than the total: a patient who has
     * already paid a co-pay at the desk must not have that same money claimed from their insurer
     * as well, and the two figures differ by exactly the co-pay.
     */
    @Transactional
    public BillingDtos.ClaimResponse raise(BillingDtos.RaiseClaimRequest request) {
        Invoice invoice = invoices.require(request.invoiceId());
        if (invoice.getPayerCode() == null) {
            throw new BadRequestException(("Invoice %s names no payer, so there is nobody to claim "
                    + "from. A self-paying patient's invoice is collected, not claimed.")
                    .formatted(invoice.getNumber()));
        }
        Payer payer = config.requirePayer(invoice.getPayerCode());
        if (!payer.isSettlesDirectly()) {
            throw new BadRequestException(("%s does not settle directly: the patient pays and "
                    + "claims reimbursement themselves, so the hospital has no claim to raise.")
                    .formatted(payer.getName()));
        }
        if (invoice.getStatus() != com.hms.billing.domain.BillingEnums.InvoiceStatus.ISSUED) {
            throw new ConflictException(("Invoice %s is %s. A claim is raised against an issued "
                    + "invoice: a draft is still collecting charges, and a paid or cancelled one "
                    + "has nothing left to claim.")
                    .formatted(invoice.getNumber(),
                            invoice.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        BigDecimal claimed = invoice.outstanding();
        if (claimed.signum() <= 0) {
            throw new ConflictException("Invoice %s has nothing outstanding to claim."
                    .formatted(invoice.getNumber()));
        }
        if (payer.isRequiresPreauth()
                && (request.preauthNo() == null || request.preauthNo().isBlank())) {
            throw new BadRequestException(("%s requires a pre-authorisation number before a claim "
                    + "can be raised. Obtain one from the payer and record it here.")
                    .formatted(payer.getName()));
        }

        // Asked before inserting, and the answer is used in the refusal. `uq_claim_per_invoice` is
        // still the control — two claims for one treatment is fraud however accidental — but a
        // constraint violation aborts the transaction, and a caller cannot then be told *which*
        // claim already exists, because reading one is another statement the database will not
        // accept. So the query goes first and the constraint stays behind it.
        Optional<Claim> existing = claims.findByInvoiceId(invoice.getId());
        if (existing.isPresent()) {
            throw new ConflictException(("Invoice %s already carries a claim (%s, %s). A rejected "
                    + "claim is re-argued on that row rather than by raising a second one.")
                    .formatted(invoice.getNumber(),
                            existing.get().getStatus().name().toLowerCase(Locale.ROOT),
                            existing.get().getClaimedAmount()));
        }

        Claim claim = new Claim(invoice.getId(), payer.getCode(), trimmed(request.preauthNo()),
                claimed);
        Claim saved;
        try {
            saved = claims.saveAndFlush(claim);
        } catch (DataIntegrityViolationException ex) {
            // Two callers raised the same claim at once and the other one won. Nothing to read
            // back here — this transaction is finished — so the refusal says what happened
            // without naming the winner's numbers.
            throw new ConflictException(("Invoice %s has just been claimed for by somebody else. "
                    + "Open the claim that exists rather than raising a second one.")
                    .formatted(invoice.getNumber()));
        }
        audit.record("CLAIM_RAISED", "Claim", saved.getId(),
                "%s to %s for %s".formatted(invoice.getNumber(), payer.getCode(), claimed));
        return toResponse(saved, invoice.getNumber());
    }

    @Transactional
    public BillingDtos.ClaimResponse submit(UUID id) {
        Claim claim = require(id);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ConflictException("This claim is already %s."
                    .formatted(claim.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        claim.submit();
        audit.record("CLAIM_SUBMITTED", "Claim", id,
                "%s for %s".formatted(claim.getPayerCode(), claim.getClaimedAmount()));
        return toResponse(claim, numberOf(claim));
    }

    /**
     * Records what the payer paid, and takes the money.
     *
     * <p>The settlement goes onto the invoice as an {@code INSURANCE} payment, through
     * {@link InvoiceService#pay}. That is deliberate rather than convenient: the overpayment
     * refusal, the atomic {@code amount_paid} update and the audit record all live on that path,
     * and a settlement written straight onto the claim row would have none of them.
     */
    @Transactional
    public BillingDtos.ClaimResponse settle(UUID id, BillingDtos.SettleClaimRequest request) {
        Claim claim = require(id);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw new ConflictException(("A claim is settled after it is submitted; this one is %s."
                    + " Submit it to the payer first.")
                    .formatted(claim.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        BigDecimal settled = Money.scale(request.settledAmount());
        if (settled.compareTo(claim.getClaimedAmount()) > 0) {
            throw new BadRequestException(("A payer cannot settle %s against a claim for %s. If "
                    + "more is owed, the claim was raised for the wrong amount.")
                    .formatted(settled, claim.getClaimedAmount()));
        }

        claim.settle(settled);
        if (settled.signum() > 0) {
            invoices.pay(claim.getInvoiceId(), new BillingDtos.RecordPaymentRequest(settled,
                    PaymentMethod.INSURANCE, "CLAIM " + claim.getId()));
        }
        audit.record("CLAIM_SETTLED", "Claim", id, "%s settled %s of %s"
                .formatted(claim.getPayerCode(), settled, claim.getClaimedAmount()));
        return toResponse(claim, numberOf(claim));
    }

    @Transactional
    public BillingDtos.ClaimResponse deny(UUID id, BillingDtos.DenyClaimRequest request) {
        Claim claim = require(id);
        if (claim.getStatus() == ClaimStatus.SETTLED
                || claim.getStatus() == ClaimStatus.PARTIALLY_SETTLED) {
            throw new ConflictException(
                    "This claim has already been settled and cannot then be denied.");
        }
        claim.deny(request.reason().trim());
        audit.record("CLAIM_DENIED", "Claim", id, claim.getPayerCode());
        return toResponse(claim, numberOf(claim));
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.ClaimResponse> list(String payerCode, boolean includeClosed) {
        List<ClaimStatus> statuses = includeClosed
                ? List.of(ClaimStatus.values())
                : List.of(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.PARTIALLY_SETTLED,
                        ClaimStatus.DENIED);
        List<Claim> found = payerCode == null || payerCode.isBlank()
                ? claims.findByStatusInOrderByCreatedAtDesc(statuses)
                : claims.findByPayerCodeAndStatusInOrderByCreatedAtDesc(
                        config.requirePayer(payerCode).getCode(), statuses);
        return found.stream().map(claim -> toResponse(claim, numberOf(claim))).toList();
    }

    @Transactional(readOnly = true)
    public BillingDtos.ClaimResponse read(UUID id) {
        Claim claim = require(id);
        return toResponse(claim, numberOf(claim));
    }

    private Claim require(UUID id) {
        return claims.findById(id)
                .orElseThrow(() -> new NotFoundException("No claim with id " + id));
    }

    private String numberOf(Claim claim) {
        return invoices.require(claim.getInvoiceId()).getNumber();
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BillingDtos.ClaimResponse toResponse(Claim claim, String invoiceNumber) {
        return new BillingDtos.ClaimResponse(claim.getId(), claim.getInvoiceId(), invoiceNumber,
                claim.getPayerCode(), claim.getPreauthNo(), claim.getSubmittedAt(),
                claim.getStatus(), claim.getClaimedAmount(), claim.getSettledAmount(),
                claim.shortfall(), claim.getDenialReason());
    }
}
