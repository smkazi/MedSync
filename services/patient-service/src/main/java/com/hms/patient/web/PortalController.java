package com.hms.patient.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.patient.service.PortalProfileService;
import com.hms.patient.web.dto.PatientDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's own record, through the portal.
 *
 * <p>One endpoint and no path variable, which is the whole design of {@code /portal}. There is no
 * {@code /portal/patients/{id}} anywhere on this platform and there is not going to be: the patient
 * is read from the {@code patient_id} claim on the token, so there is no identifier in the request
 * for anybody to change. An IDOR test against this endpoint has nothing to tamper with, which is a
 * stronger property than an IDOR test that passes.
 *
 * <p>The staff-facing {@code /patients/{id}} is unchanged and is still where a clinician reads a
 * record. This is a second door onto one record, not a second API for the register.
 */
@RestController
@RequestMapping("/portal")
@PreAuthorize(Roles.PORTAL)
public class PortalController {

    private final PortalProfileService profiles;

    public PortalController(PortalProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    public PatientDtos.PortalProfile me() {
        return profiles.read(CurrentUser.requirePatientId());
    }
}
