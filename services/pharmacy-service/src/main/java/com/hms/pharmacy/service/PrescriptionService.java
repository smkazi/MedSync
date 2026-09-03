package com.hms.pharmacy.service;

import com.hms.common.audit.AuditService;
import com.hms.common.careteam.CareRelationshipClient;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.pharmacy.domain.Formulary;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import com.hms.pharmacy.domain.PharmacyEnums.PrescriptionStatus;
import com.hms.pharmacy.domain.Prescription;
import com.hms.pharmacy.domain.PrescriptionItem;
import com.hms.pharmacy.repo.AdministrationRepository;
import com.hms.pharmacy.repo.PrescriptionRepository;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing a prescription, and refusing to.
 *
 * <p>The order of operations here is the safety rule: the checks run <em>before</em> anything is
 * written, in the same transaction, and a refusal leaves no rows behind. A service that saved the
 * prescription and then checked it would have a moment in which an unsafe order existed and was
 * dispensable, and "we deleted it straight away" is not a defence a pharmacy accepts.
 */
@Service
public class PrescriptionService {

    private final PrescriptionRepository prescriptions;
    private final AdministrationRepository administrations;
    private final SafetyService safety;
    private final AuditService audit;
    private final EventPublisher events;
    private final CareRelationshipClient careRelationships;

    public PrescriptionService(PrescriptionRepository prescriptions,
                               AdministrationRepository administrations, SafetyService safety,
                               AuditService audit, EventPublisher events,
                               CareRelationshipClient careRelationships) {
        this.prescriptions = prescriptions;
        this.administrations = administrations;
        this.safety = safety;
        this.audit = audit;
        this.events = events;
        this.careRelationships = careRelationships;
    }

    @Transactional
    public PharmacyDtos.PrescriptionResponse prescribe(PharmacyDtos.PrescribeRequest request,
                                                       String bearerToken) {
        List<String> codes = request.items().stream()
                .map(PharmacyDtos.PrescribeItemRequest::drugCode)
                .toList();
        if (codes.size() != codes.stream().distinct().count()) {
            // Two lines for the same product is either a duplicate somebody did not mean or a dose
            // change somebody expressed by adding a line. Both are dangerous to guess at, and the
            // interaction check would compare the drug with itself.
            throw new BadRequestException(
                    "The same medicine appears twice on this prescription. Combine the lines, or "
                            + "cancel and re-prescribe if the dose is changing.");
        }

        Map<String, Formulary> products = safety.requireOrderable(codes).stream()
                .collect(Collectors.toMap(Formulary::getCode, entry -> entry,
                        (first, second) -> first, LinkedHashMap::new));

        PharmacyDtos.SafetyCheckResponse check = safety.check(request.patientId(), codes, bearerToken);
        String reason = request.overrideReason() == null ? "" : request.overrideReason().trim();
        if (check.outcome() == CheckOutcome.REFUSED) {
            // A 409 rather than a 400: the request is well-formed and the platform is refusing it
            // on clinical grounds, which is a different thing from a malformed body and should read
            // differently to whoever is looking at the response.
            throw new ConflictException(check.message());
        }
        if (check.outcome() == CheckOutcome.OVERRIDABLE && reason.isEmpty()) {
            throw new ConflictException(check.message()
                    + " Re-send with an override reason to go ahead.");
        }

        Prescription prescription = new Prescription(request.encounterId(), request.patientId(),
                request.patientMrn().trim(), CurrentUser.idOrSystem(),
                CurrentUser.usernameOrSystem(), reason.isEmpty() ? null : reason);
        for (PharmacyDtos.PrescribeItemRequest item : request.items()) {
            Formulary product = products.get(item.drugCode());
            prescription.addItem(new PrescriptionItem(product.getCode(), product.label(),
                    item.dose().trim(), item.frequency().trim(), item.durationDays(),
                    item.quantity(), item.instructions()));
        }
        Prescription saved = prescriptions.save(prescription);

        // The audit detail names the codes and whether anything was overridden, and carries no
        // clinical free text: `AuditService`'s contract forbids it, and the override *reason* is
        // clinical, which is why it lives on the prescription where a pharmacist can read it.
        audit.record("PRESCRIPTION_WRITTEN", "Prescription", saved.getId(),
                "%d item(s): %s%s".formatted(saved.getItems().size(), String.join(", ", codes),
                        reason.isEmpty() ? "" : "; override recorded"));
        publish("pharmacy.prescribed", saved,
                Map.of("items", codes, "overridden", !reason.isEmpty()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    /**
     * One prescription, if it is a patient this clinician is looking after.
     *
     * <p>Read before the check because whose prescription it is is not knowable until it is: the
     * caller addresses a prescription id. Nothing is returned when the answer is no.
     *
     * <p>The pharmacist is not narrowed by this and could not do the job if they were — filling a
     * queue is inherently cross-patient work, and a care-relationship model does not describe it.
     * What is narrowed is a doctor or a nurse reading the medicines of somebody they are not
     * looking after, which is browsing.
     */
    public PharmacyDtos.PrescriptionResponse read(UUID id) {
        Prescription prescription = require(id);
        careRelationships.requirePatientAccess(prescription.getPatientId());
        return toResponse(prescription);
    }

    @Transactional(readOnly = true)
    public List<PharmacyDtos.PrescriptionResponse> forPatient(UUID patientId) {
        careRelationships.requirePatientAccess(patientId);
        return prescriptions.findByPatientIdOrderByIssuedAtDesc(patientId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PharmacyDtos.PrescriptionResponse> forEncounter(UUID encounterId) {
        return prescriptions.findByEncounterIdOrderByIssuedAtDesc(encounterId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** The pharmacy's work list: prescriptions with something still to hand over, oldest first. */
    @Transactional(readOnly = true)
    public List<PharmacyDtos.PrescriptionResponse> queue() {
        return prescriptions.queue(List.of(PrescriptionStatus.ACTIVE)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Cancels a prescription.
     *
     * <p>Refused once anything has been dispensed, and that is not tidiness: the medicine has left
     * the pharmacy and may be in the patient's hand, so "cancelled" would be a false statement
     * about the physical world. Stopping a medicine that has already been handed over is a new
     * clinical instruction, not the deletion of an old one.
     */
    @Transactional
    public PharmacyDtos.PrescriptionResponse cancel(UUID id) {
        Prescription prescription = require(id);
        if (!prescription.isActive()) {
            throw new BadRequestException("This prescription is already "
                    + prescription.getStatus().name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        boolean anythingDispensed = prescription.getItems().stream()
                .anyMatch(item -> item.getQuantityDispensed() > 0);
        if (anythingDispensed) {
            throw new ConflictException(
                    "Part of this prescription has already been dispensed, so it cannot be "
                            + "cancelled. Write the instruction to stop instead.");
        }
        prescription.cancel();
        audit.record("PRESCRIPTION_CANCELLED", "Prescription", id, "before any dispense");
        publish("pharmacy.prescription-cancelled", prescription, Map.of());
        return toResponse(prescription);
    }

    Prescription require(UUID id) {
        return prescriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("No prescription with id " + id));
    }

    PharmacyDtos.PrescriptionResponse toResponse(Prescription prescription) {
        List<UUID> itemIds = prescription.getItems().stream().map(PrescriptionItem::getId).toList();
        Map<UUID, List<PharmacyDtos.AdministrationResponse>> doses = itemIds.isEmpty()
                ? Map.of()
                : administrations.findByPrescriptionItemIdInOrderByScheduledFor(itemIds).stream()
                        .collect(Collectors.groupingBy(
                                com.hms.pharmacy.domain.Administration::getPrescriptionItemId,
                                Collectors.mapping(PrescriptionService::toResponse,
                                        Collectors.toList())));

        return new PharmacyDtos.PrescriptionResponse(prescription.getId(),
                prescription.getEncounterId(), prescription.getPatientId(),
                prescription.getPatientMrn(), prescription.getPrescriberId(),
                prescription.getPrescriberName(), prescription.getStatus(),
                prescription.getOverrideReason(), prescription.getIssuedAt(),
                prescription.getCancelledAt(),
                prescription.getItems().stream()
                        .map(item -> new PharmacyDtos.PrescriptionItemResponse(item.getId(),
                                item.getDrugCode(), item.getDrugName(), item.getDose(),
                                item.getFrequency(), item.getDurationDays(), item.getQuantity(),
                                item.getInstructions(), item.getQuantityDispensed(),
                                item.outstanding(), doses.getOrDefault(item.getId(), List.of())))
                        .toList());
    }

    static PharmacyDtos.AdministrationResponse toResponse(
            com.hms.pharmacy.domain.Administration record) {
        return new PharmacyDtos.AdministrationResponse(record.getId(),
                record.getPrescriptionItemId(), record.getScheduledFor(),
                record.getAdministeredAt(), record.getAdministeredBy(), record.getStatus(),
                record.getRefusalReason());
    }

    private void publish(String type, Prescription prescription, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("patientId", prescription.getPatientId().toString());
        payload.put("mrn", prescription.getPatientMrn());
        payload.put("status", prescription.getStatus().name());
        events.publish(Topics.PHARMACY, DomainEvent.of(type, "Prescription", prescription.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }
}
