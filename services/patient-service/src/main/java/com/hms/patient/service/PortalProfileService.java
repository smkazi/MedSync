package com.hms.patient.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.NotFoundException;
import com.hms.patient.domain.Patient;
import com.hms.patient.domain.PatientAllergy;
import com.hms.patient.repo.PatientRepository;
import com.hms.patient.web.dto.PatientDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The patient's own record, read for the patient the session names and for nobody else. */
@Service
public class PortalProfileService {

    private final PatientRepository patients;
    private final AuditService audit;

    public PortalProfileService(PatientRepository patients, AuditService audit) {
        this.patients = patients;
        this.audit = audit;
    }

    /**
     * @param patientId always {@code CurrentUser.requirePatientId()} — never a path variable
     */
    @Transactional(readOnly = true)
    public PatientDtos.PortalProfile read(UUID patientId) {
        Patient patient = patients.findById(patientId).orElseThrow(() -> new NotFoundException(
                "Your record could not be found. Ask the front desk to check your portal access."));
        // Audited like any other read of a record. A patient reading their own chart is not a
        // disclosure to anybody new, but it is the evidence that answers "who looked at this
        // record" completely rather than nearly, and a log with a hole in it is worth less than a
        // log with an extra line in it.
        audit.record("PORTAL_PROFILE_READ", "Patient", patientId, "by the patient");
        return new PatientDtos.PortalProfile(
                patient.getId(), patient.getMrn(), patient.getFirstName(), patient.getLastName(),
                patient.fullName(), patient.getDateOfBirth(),
                patient.age(), patient.getSex(), patient.getBloodGroup(), patient.getPhone(),
                patient.getEmail(), patient.getAddressLine1(), patient.getAddressLine2(),
                patient.getCity(), patient.getState(), patient.getPostalCode(), patient.getCountry(),
                patient.getInsuranceProvider(), patient.getEmergencyContactName(),
                patient.getEmergencyContactPhone(), patient.isActive(), allergies(patient));
    }

    private static List<PatientDtos.PortalAllergy> allergies(Patient patient) {
        return patient.getAllergies().stream()
                .map(PortalProfileService::toPortalAllergy)
                .toList();
    }

    private static PatientDtos.PortalAllergy toPortalAllergy(PatientAllergy allergy) {
        return new PatientDtos.PortalAllergy(allergy.getSubstance(), allergy.getReaction(),
                allergy.getSeverity(), allergy.isCritical(), allergy.getCreatedAt());
    }
}
