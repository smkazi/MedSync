package com.hms.billing.service;

import com.hms.common.error.NotFoundException;
import com.hms.billing.domain.BillingEnums;
import com.hms.billing.domain.Invoice;
import com.hms.billing.repo.InvoiceRepository;
import com.hms.billing.web.dto.BillingDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A patient's own bills.
 *
 * <p>Reuses {@link BillingDtos.InvoiceResponse} rather than defining a portal shape, and that is
 * the deliberate opposite of the decision the laboratory portal made. A pathology result needs a
 * narrower shape because a provisional value is not a result yet; an invoice has no such stage. It
 * is a document the hospital has already handed the patient on paper, line by line with the tax
 * shown, and every field on it is one they are entitled to check — including which cashier took
 * each payment, which is the first thing anybody needs when a receipt is disputed.
 *
 * <p>Cancelled invoices are listed rather than hidden. A patient who was told they owed money and
 * then told they did not should be able to see both, and a bill that quietly vanishes is how a
 * billing department loses an argument it was going to win.
 */
@Service
public class PortalBillingService {

    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;

    public PortalBillingService(InvoiceRepository invoices, InvoiceService invoiceService) {
        this.invoices = invoices;
        this.invoiceService = invoiceService;
    }

    @Transactional(readOnly = true)
    public List<BillingDtos.InvoiceResponse> mine(UUID patientId) {
        return invoiceService.forPatient(patientId);
    }

    /**
     * One of the patient's own invoices.
     *
     * <p>404 rather than 403 when the invoice belongs to somebody else, for the reason every
     * ownership check in the portal answers 404: an invoice number that comes back "not yours"
     * is an invoice number confirmed to exist.
     */
    @Transactional(readOnly = true)
    public BillingDtos.InvoiceResponse read(UUID patientId, UUID invoiceId) {
        Invoice invoice = invoices.findById(invoiceId)
                .filter(candidate -> patientId.equals(candidate.getPatientId()))
                .orElseThrow(() -> NotFoundException.of("Invoice", invoiceId));
        return invoiceService.toResponse(invoice);
    }

    /**
     * What this patient owes, across every invoice that is still live.
     *
     * <p>Its own endpoint because it is the one number a patient opens the portal to see, and
     * summing it in the browser would mean the figure on the screen depended on which page of
     * invoices had loaded. Cancelled invoices are excluded: they are shown in the list as history
     * and owed by nobody.
     */
    @Transactional(readOnly = true)
    public BillingDtos.PortalBalance balance(UUID patientId) {
        List<Invoice> live = invoices.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .filter(invoice -> invoice.getStatus() != BillingEnums.InvoiceStatus.CANCELLED)
                .toList();
        BigDecimal outstanding = live.stream()
                .map(Invoice::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long unpaid = live.stream().filter(invoice -> invoice.outstanding().signum() > 0).count();
        return new BillingDtos.PortalBalance(outstanding, unpaid, live.size());
    }
}
