package com.hms.pharmacy.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.pharmacy.domain.Administration;
import com.hms.pharmacy.domain.PharmacyEnums.AdministrationStatus;
import com.hms.pharmacy.domain.Prescription;
import com.hms.pharmacy.domain.PrescriptionItem;
import com.hms.pharmacy.repo.AdministrationRepository;
import com.hms.pharmacy.repo.PrescriptionItemRepository;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bedside end of the loop: a dose, against two scans.
 *
 * <p><strong>Both scans are checked before the row is written, and a mismatch refuses.</strong>
 * The wristband is checked against the prescription's MRN and the label against the item's drug
 * code, which are the two halves of "right patient, right medicine" — the pair of errors that
 * closed-loop administration exists to catch and that no amount of care at the prescribing end
 * prevents.
 *
 * <p>Typing the numbers in is not blocked, and cannot be: a scanner fails, and a nurse holding a
 * syringe cannot wait for procurement. What the platform can insist on is that <em>something</em>
 * was read from the wristband and it matched, which is why the request has no "scanner unavailable"
 * flag — an override that lets both checks be skipped is an override that becomes the normal path
 * within a week.
 */
@Service
public class AdministrationService {

    private final AdministrationRepository administrations;
    private final PrescriptionItemRepository items;
    private final AuditService audit;
    private final EventPublisher events;

    public AdministrationService(AdministrationRepository administrations,
                                 PrescriptionItemRepository items, AuditService audit,
                                 EventPublisher events) {
        this.administrations = administrations;
        this.items = items;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public PharmacyDtos.AdministrationResponse administer(PharmacyDtos.AdministerRequest request) {
        PrescriptionItem item = require(request.prescriptionItemId());
        Prescription prescription = item.getPrescription();
        if (!prescription.isActive() && prescription.getStatus()
                != com.hms.pharmacy.domain.PharmacyEnums.PrescriptionStatus.COMPLETED) {
            // A cancelled prescription is a medicine somebody stopped. A COMPLETED one is simply
            // fully dispensed, and the patient still has the box, so doses continue against it.
            throw new BadRequestException(
                    "This prescription has been cancelled. Do not give this medicine.");
        }

        String wristband = normalise(request.patientScan());
        String expectedPatient = normalise(prescription.getPatientMrn());
        if (!wristband.equals(expectedPatient)) {
            // The message names what was scanned and what was expected, because the nurse standing
            // at the bed has to work out which of the two is wrong: the wrong patient, or the right
            // patient and the wrong prescription open on the trolley.
            throw new ConflictException(
                    ("Wristband %s does not match this prescription, which is for %s. Do not give "
                            + "this dose. Check that the right chart is open for the patient in "
                            + "front of you.")
                            .formatted(request.patientScan(), prescription.getPatientMrn()));
        }

        String label = normalise(request.drugScan());
        if (!label.equals(normalise(item.getDrugCode()))) {
            throw new ConflictException(
                    ("The medicine scanned (%s) is not the one prescribed (%s — %s). Do not give "
                            + "this dose.")
                            .formatted(request.drugScan(), item.getDrugCode(), item.getDrugName()));
        }

        Administration record = Administration.given(item.getId(), request.scheduledFor(),
                CurrentUser.usernameOrSystem(), request.patientScan().trim(),
                request.drugScan().trim());
        return save(record, item, prescription, "MEDICATION_ADMINISTERED");
    }

    /**
     * Records a dose that was not given.
     *
     * <p>A row rather than a gap, because the absence of a dose is itself a clinical fact: the next
     * shift needs to know the patient declined it, not to find nothing there and guess. No scans,
     * because nothing was scanned — writing a wristband number against a dose that did not happen
     * would put a verification in the record that never took place.
     */
    @Transactional
    public PharmacyDtos.AdministrationResponse notGiven(PharmacyDtos.NotGivenRequest request) {
        if (request.status() == AdministrationStatus.GIVEN) {
            throw new BadRequestException(
                    "A dose that was given is recorded through the scan path, so that the wristband "
                            + "and the label are checked. Use POST /emar/administer.");
        }
        PrescriptionItem item = require(request.prescriptionItemId());
        Administration record = Administration.notGiven(item.getId(), request.scheduledFor(),
                CurrentUser.usernameOrSystem(), request.status(), request.reason().trim());
        return save(record, item, item.getPrescription(), "MEDICATION_NOT_GIVEN");
    }

    @Transactional(readOnly = true)
    public java.util.List<PharmacyDtos.AdministrationResponse> forItem(UUID itemId) {
        return administrations.findByPrescriptionItemIdOrderByScheduledFor(itemId).stream()
                .map(PrescriptionService::toResponse)
                .toList();
    }

    private PharmacyDtos.AdministrationResponse save(Administration record, PrescriptionItem item,
                                                     Prescription prescription, String action) {
        Administration saved;
        try {
            saved = administrations.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // `uq_dose` fired: somebody else has already recorded this dose. Two nurses at one
            // bedside, each believing the other had not given it, is the failure that constraint
            // exists for — and a check-then-insert would let both of them through, because both
            // read "no record" before either wrote one.
            throw new ConflictException(
                    ("This dose has already been recorded for %s at %s. Two records of one dose "
                            + "would read as two doses; refresh the round and check who gave it.")
                            .formatted(item.getDrugName(), record.getScheduledFor()));
        }

        audit.record(action, "Administration", saved.getId(),
                "%s, scheduled %s, %s".formatted(item.getDrugCode(), saved.getScheduledFor(),
                        saved.getStatus().name().toLowerCase(Locale.ROOT)));
        events.publish(Topics.PHARMACY, DomainEvent.of(
                saved.getStatus() == AdministrationStatus.GIVEN
                        ? "pharmacy.administered" : "pharmacy.dose-not-given",
                "Administration", saved.getId(), CurrentUser.idOrSystem().toString(),
                CorrelationId.current(),
                Map.of("patientId", prescription.getPatientId().toString(),
                        "mrn", prescription.getPatientMrn(),
                        "drugCode", item.getDrugCode(),
                        "status", saved.getStatus().name())));
        return PrescriptionService.toResponse(saved);
    }

    private PrescriptionItem require(UUID itemId) {
        return items.findById(itemId)
                .orElseThrow(() -> new NotFoundException("No prescription item with id " + itemId));
    }

    /** Scans arrive with whatever the reader appended; compare on content, not on whitespace. */
    private static String normalise(String scanned) {
        return scanned == null ? "" : scanned.trim().toUpperCase(Locale.ROOT);
    }
}
