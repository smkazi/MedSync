package com.hms.identity.web;

import com.hms.common.security.Roles;
import com.hms.identity.service.PortalAccountService;
import com.hms.identity.web.dto.AuthDtos;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portal accounts, administered one patient at a time.
 *
 * <p>Under {@code /admin} because this is administration of a credential, not a portal endpoint —
 * everything under {@code /portal} is reached <em>by</em> a patient, and nothing here is. It is
 * gated {@link Roles#PORTAL_ENROL} rather than the {@code ADMIN_ONLY} that covers the rest of this
 * prefix, which is the one place the two diverge: enrolment happens at the front desk because
 * somebody has to look at the person asking.
 *
 * <p>Addressed by patient id rather than by account id throughout. The desk is looking at a patient
 * record and knows that id; it has no reason to learn a second one, and an endpoint that took an
 * account id would let a caller aim at a staff account by guessing.
 */
@RestController
@RequestMapping("/admin/portal-accounts")
@PreAuthorize(Roles.PORTAL_ENROL)
public class PortalAccountController {

    private final PortalAccountService service;

    public PortalAccountController(PortalAccountService service) {
        this.service = service;
    }

    /**
     * Issues or re-issues portal access, answering the one-time password.
     *
     * <p>Called by patient-service rather than by a browser: the record has to be found and its
     * MRN and address read off it before anybody can be enrolled, and that is patient-service's
     * job. The screen the receptionist uses posts to {@code /patients/{id}/portal-account}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthDtos.PortalAccountIssued enrol(@Valid @RequestBody AuthDtos.EnrolPortalAccountRequest request) {
        return service.enrol(request);
    }

    @GetMapping("/{patientId}")
    public AuthDtos.PortalAccountResponse find(@PathVariable UUID patientId) {
        return service.find(patientId);
    }

    /** Withdraws access and ends every live session. The account and its history stay. */
    @DeleteMapping("/{patientId}")
    public AuthDtos.PortalAccountResponse withdraw(@PathVariable UUID patientId) {
        return service.deactivate(patientId);
    }
}
