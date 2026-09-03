package com.hms.billing.web;

import com.hms.billing.service.PortalBillingService;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's own bills, and what is still owed.
 *
 * <p>Read-only, and the omission is deliberate rather than unfinished: taking money needs a payment
 * gateway, which needs a merchant account and live credentials this platform does not ship with and
 * must not pretend to. A "Pay now" button that recorded a payment nobody had actually received
 * would be the single most damaging thing in the repository — the invoice would settle, the day
 * book would balance, and the money would not exist. The README's Roadmap names it as a gap.
 */
@RestController
@RequestMapping("/portal/invoices")
@PreAuthorize(Roles.PORTAL)
public class PortalBillingController {

    private final PortalBillingService portal;

    public PortalBillingController(PortalBillingService portal) {
        this.portal = portal;
    }

    @GetMapping
    public List<BillingDtos.InvoiceResponse> mine() {
        return portal.mine(CurrentUser.requirePatientId());
    }

    /** The one number a patient opens the portal for. Ahead of the {id} mapping, so it is not one. */
    @GetMapping("/balance")
    public BillingDtos.PortalBalance balance() {
        return portal.balance(CurrentUser.requirePatientId());
    }

    @GetMapping("/{invoiceId}")
    public BillingDtos.InvoiceResponse read(@PathVariable UUID invoiceId) {
        return portal.read(CurrentUser.requirePatientId(), invoiceId);
    }
}
