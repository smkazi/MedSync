package com.hms.patient.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.patient.domain.AllergySeverity;
import com.hms.patient.domain.Patient;
import com.hms.patient.domain.PatientAllergy;
import com.hms.patient.web.dto.PatientDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Patient registration, chart maintenance, search and allergy recording. */
@Service
public class PatientService {

    private final com.hms.patient.repo.PatientRepository patients;
    private final MrnGenerator mrnGenerator;
    private final EventPublisher events;
    private final AuditService audit;

    public PatientService(com.hms.patient.repo.PatientRepository patients, MrnGenerator mrnGenerator,
                          EventPublisher events, AuditService audit) {
        this.patients = patients;
        this.mrnGenerator = mrnGenerator;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Registers a patient.
     *
     * <p>Unless {@code forceDuplicate} is set, a matching surname and date of birth is reported as
     * a conflict with the candidate records, so a receptionist resolves it instead of silently
     * creating a second chart for the same person.
     */
    @Transactional
    public PatientDtos.PatientResponse register(PatientDtos.CreatePatientRequest request) {
        if (!request.isForced()) {
            List<Patient> duplicates = patients.findPotentialDuplicates(request.lastName(), request.dateOfBirth());
            if (!duplicates.isEmpty()) {
                throw new DuplicatePatientException(duplicates.stream()
                        .map(candidate -> PatientMapper.toSummary(candidate, candidate.hasCriticalAllergy()))
                        .toList());
            }
        }

        Patient patient = new Patient(mrnGenerator.next(), request.firstName().trim(), request.lastName().trim(),
                request.dateOfBirth(), request.sex());
        applyCreateFields(patient, request);
        patients.save(patient);

        audit.record("PATIENT_REGISTERED", "Patient", patient.getId(), "mrn " + patient.getMrn());
        publish("patient.created", patient);
        return PatientMapper.toResponse(patient);
    }

    /**
     * Search results, already mapped. Mapping happens inside the transaction: with
     * {@code open-in-view} disabled (as it should be), handing an entity to a controller and
     * touching a lazy association there fails at runtime.
     */
    @Transactional(readOnly = true)
    public Page<PatientDtos.PatientSummary> search(String query, boolean includeInactive, Pageable pageable) {
        Page<Patient> page = patients.search(QueryPatterns.contains(query), includeInactive, pageable);
        Set<UUID> critical = criticalAllergyIds(page.getContent());
        return page.map(patient -> PatientMapper.toSummary(patient, critical.contains(patient.getId())));
    }

    /** Resolves the critical-allergy marker for a whole page in one query. */
    private Set<UUID> criticalAllergyIds(List<Patient> page) {
        if (page.isEmpty()) {
            return Set.of();
        }
        return patients.findIdsWithAllergySeverity(page.stream().map(Patient::getId).toList(),
                List.of(AllergySeverity.SEVERE, AllergySeverity.LIFE_THREATENING));
    }

    @Transactional(readOnly = true)
    public PatientDtos.PatientResponse getById(UUID id) {
        return PatientMapper.toResponse(patients.findDetailById(id)
                .orElseThrow(() -> NotFoundException.of("Patient", id)));
    }

    @Transactional(readOnly = true)
    public PatientDtos.PatientResponse getByMrn(String mrn) {
        return PatientMapper.toResponse(patients.findDetailByMrn(mrn)
                .orElseThrow(() -> new NotFoundException("Patient with MRN " + mrn + " not found")));
    }

    @Transactional(readOnly = true)
    public Patient require(UUID id) {
        return patients.findDetailById(id).orElseThrow(() -> NotFoundException.of("Patient", id));
    }

    @Transactional
    public PatientDtos.PatientResponse update(UUID id, PatientDtos.UpdatePatientRequest request) {
        Patient patient = require(id);
        applyUpdateFields(patient, request);
        audit.record("PATIENT_UPDATED", "Patient", id, "mrn " + patient.getMrn());
        publish("patient.updated", patient);
        return PatientMapper.toResponse(patient);
    }

    /**
     * Archives a chart. Patient records are never deleted: downstream appointments, encounters and
     * lab results reference them, and a medical record is a legal document.
     */
    @Transactional
    public void archive(UUID id) {
        Patient patient = require(id);
        if (!patient.isActive()) {
            return;
        }
        patient.setActive(false);
        audit.record("PATIENT_ARCHIVED", "Patient", id, "mrn " + patient.getMrn());
        publish("patient.archived", patient);
    }

    /**
     * Reads the encrypted identifiers. Separated from the main patient view so that access is a
     * distinct, individually audited action rather than a side effect of opening a chart.
     */
    @Transactional(readOnly = true)
    public PatientDtos.PatientIdentifiers readIdentifiers(UUID id) {
        Patient patient = require(id);
        audit.record("PATIENT_IDENTIFIERS_READ", "Patient", id,
                "national id / insurance policy released to " + CurrentUser.usernameOrSystem());
        return new PatientDtos.PatientIdentifiers(patient.getId(), patient.getMrn(), patient.getNationalId(),
                patient.getInsurancePolicyNo());
    }

    @Transactional
    public PatientDtos.AllergyResponse addAllergy(UUID patientId, PatientDtos.AddAllergyRequest request) {
        Patient patient = require(patientId);
        boolean alreadyRecorded = patient.getAllergies().stream()
                .anyMatch(existing -> existing.getSubstance().equalsIgnoreCase(request.substance().trim()));
        if (alreadyRecorded) {
            throw new ConflictException("An allergy to '" + request.substance() + "' is already recorded");
        }
        PatientAllergy allergy = new PatientAllergy(patient, request.substance().trim(), request.reaction(),
                request.severity(), CurrentUser.usernameOrSystem());
        patient.addAllergy(allergy);
        patients.save(patient);

        audit.record("ALLERGY_RECORDED", "Patient", patientId,
                request.severity() + " allergy to " + request.substance());
        publish("patient.allergy.recorded", patient);
        return PatientMapper.toResponse(allergy);
    }

    @Transactional
    public void removeAllergy(UUID patientId, UUID allergyId) {
        Patient patient = require(patientId);
        PatientAllergy allergy = patient.getAllergies().stream()
                // allergyId on the left: an entity's id is nullable until it is persisted, and
                // this way a transient row in the collection cannot NPE the lookup.
                .filter(candidate -> allergyId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow(() -> NotFoundException.of("Allergy", allergyId));
        patient.removeAllergy(allergy);
        audit.record("ALLERGY_REMOVED", "Patient", patientId, "removed " + allergy.getSubstance());
    }

    private void applyCreateFields(Patient patient, PatientDtos.CreatePatientRequest request) {
        patient.setBloodGroup(request.bloodGroup());
        patient.setPhone(blankToNull(request.phone()));
        patient.setEmail(blankToNull(request.email()));
        patient.setAddressLine1(request.addressLine1());
        patient.setAddressLine2(request.addressLine2());
        patient.setCity(request.city());
        patient.setState(request.state());
        patient.setPostalCode(request.postalCode());
        patient.setCountry(request.country());
        patient.setNationalId(blankToNull(request.nationalId()));
        patient.setInsuranceProvider(request.insuranceProvider());
        patient.setInsurancePolicyNo(blankToNull(request.insurancePolicyNo()));
        patient.setEmergencyContactName(request.emergencyContactName());
        patient.setEmergencyContactPhone(blankToNull(request.emergencyContactPhone()));
        patient.setNotes(request.notes());
    }

    private void applyUpdateFields(Patient patient, PatientDtos.UpdatePatientRequest request) {
        if (request.firstName() != null) {
            patient.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            patient.setLastName(request.lastName().trim());
        }
        if (request.dateOfBirth() != null) {
            patient.setDateOfBirth(request.dateOfBirth());
        }
        if (request.sex() != null) {
            patient.setSex(request.sex());
        }
        if (request.bloodGroup() != null) {
            patient.setBloodGroup(request.bloodGroup());
        }
        if (request.phone() != null) {
            patient.setPhone(blankToNull(request.phone()));
        }
        if (request.email() != null) {
            patient.setEmail(blankToNull(request.email()));
        }
        if (request.addressLine1() != null) {
            patient.setAddressLine1(request.addressLine1());
        }
        if (request.addressLine2() != null) {
            patient.setAddressLine2(request.addressLine2());
        }
        if (request.city() != null) {
            patient.setCity(request.city());
        }
        if (request.state() != null) {
            patient.setState(request.state());
        }
        if (request.postalCode() != null) {
            patient.setPostalCode(request.postalCode());
        }
        if (request.country() != null) {
            patient.setCountry(request.country());
        }
        if (request.insuranceProvider() != null) {
            patient.setInsuranceProvider(request.insuranceProvider());
        }
        if (request.emergencyContactName() != null) {
            patient.setEmergencyContactName(request.emergencyContactName());
        }
        if (request.emergencyContactPhone() != null) {
            patient.setEmergencyContactPhone(blankToNull(request.emergencyContactPhone()));
        }
        if (request.notes() != null) {
            patient.setNotes(request.notes());
        }
        if (request.active() != null) {
            patient.setActive(request.active());
        }
        if (request.deceased() != null) {
            patient.setDeceased(request.deceased());
        }
    }

    /**
     * Publishes a patient event. The payload carries identifiers and nothing clinical: events land
     * in a broker's retained log, so they must not become a second copy of the medical record.
     */
    private void publish(String type, Patient patient) {
        events.publish(Topics.PATIENT, DomainEvent.of(type, "Patient", patient.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("mrn", patient.getMrn(), "active", patient.isActive())));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Raised when a registration looks like a repeat of an existing chart. */
    public static class DuplicatePatientException extends RuntimeException {

        private final transient List<PatientDtos.PatientSummary> candidates;

        public DuplicatePatientException(List<PatientDtos.PatientSummary> candidates) {
            super("A patient with the same surname and date of birth is already registered");
            // Copied: an exception travels up through code that has no idea it is holding a
            // reference into someone else's list, and this one is rendered into a 409 body.
            this.candidates = List.copyOf(candidates);
        }

        public List<PatientDtos.PatientSummary> candidates() {
            return candidates;
        }
    }
}
