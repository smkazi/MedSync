package com.hms.pharmacy.web;

import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.pharmacy.service.PrescriptionService;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's own prescriptions: what they have been prescribed, and what has been dispensed.
 *
 * <p>Read-only and there is nothing to add. Every write in this service is a clinical or a
 * pharmacy act — prescribing, dispensing, administering — and each already refuses everybody
 * outside one role. A patient asking for a repeat is a request to a prescriber, not a prescription,
 * and building it as one here would be building the wrong thing in the most dangerous module on
 * the platform.
 *
 * <p>The full prescription rather than a summary, for the reason the portal shows a whole encounter:
 * the dose, the frequency and the instructions are the part a patient needs, and they are the part
 * most often misremembered on the way home.
 */
@RestController
@RequestMapping("/portal/prescriptions")
@PreAuthorize(Roles.PORTAL)
public class PortalPharmacyController {

    private final PrescriptionService prescriptions;

    public PortalPharmacyController(PrescriptionService prescriptions) {
        this.prescriptions = prescriptions;
    }

    @GetMapping
    public List<PharmacyDtos.PrescriptionResponse> mine() {
        return prescriptions.forPatient(CurrentUser.requirePatientId());
    }

    /**
     * One of the patient's own prescriptions.
     *
     * <p>Filtered from the patient's own list rather than fetched by id and checked, so there is
     * one query that can return somebody else's row and it is the one already scoped to this
     * patient. 404 for anything not in it — an id that comes back "not yours" is an id confirmed
     * to be real.
     */
    @GetMapping("/{id}")
    public PharmacyDtos.PrescriptionResponse mine(@PathVariable UUID id) {
        UUID patientId = CurrentUser.requirePatientId();
        return prescriptions.forPatient(patientId).stream()
                .filter(prescription -> prescription.id().equals(id))
                .findFirst()
                .orElseThrow(() -> NotFoundException.of("Prescription", id));
    }
}
