package com.hms.patient.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.patient.client.IdentityClient;
import com.hms.patient.domain.Patient;
import com.hms.patient.repo.PatientRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrolling a patient in the portal, from the record the portal will show them.
 *
 * <p>This half of enrolment exists so that the identifiers on a portal account come off the record
 * rather than out of somebody's memory. The username is the MRN the patient is already carrying on
 * every slip they have been given, and the address is the one on file — which matters more than it
 * looks, because the address on the account is the address a password reset would go to. An
 * enrolment screen with two free-text boxes is an enrolment screen where a mistyped address hands
 * somebody a way back into a stranger's record.
 */
@Service
public class PortalEnrolmentService {

    private final PatientRepository patients;
    private final IdentityClient identity;
    private final AuditService audit;

    public PortalEnrolmentService(PatientRepository patients, IdentityClient identity, AuditService audit) {
        this.patients = patients;
        this.identity = identity;
        this.audit = audit;
    }

    /**
     * Issues or re-issues portal access, answering the one-time password once.
     *
     * <p>Not transactional over the remote call, and it does not need to be: nothing is written
     * here. The record is read, the account is created in identity-service, and the audit line
     * records that access to a numbered record was issued. If identity-service refuses, nothing has
     * changed anywhere.
     */
    public IdentityClient.PortalAccountIssued enrol(UUID patientId, String bearerToken) {
        Patient patient = require(patientId);
        if (patient.getEmail() == null || patient.getEmail().isBlank()) {
            // Refused rather than enrolled with a placeholder. An account nobody can reach is an
            // account nobody can recover, and the first thing it would need is a password reset.
            throw new BadRequestException(
                    "This record has no email address, so there is nowhere to send a password "
                            + "reset. Add one to the record before issuing portal access.");
        }
        if (!patient.isActive()) {
            throw new BadRequestException(
                    "This record is archived. Portal access is not issued for an archived patient — "
                            + "restore the record first if this person is still a patient here.");
        }
        IdentityClient.PortalAccountIssued issued = identity.enrol(
                patientId, patient.getMrn(), patient.getEmail(), patient.fullName(), bearerToken);
        // The MRN, not the name and not the address: an audit line is read by more people than the
        // record is, and this one only has to say which numbered record had access issued to it.
        audit.record("PORTAL_ACCESS_ISSUED", "Patient", patientId, "MRN " + patient.getMrn());
        return issued;
    }

    @Transactional(readOnly = true)
    public IdentityClient.PortalAccountState find(UUID patientId, String bearerToken) {
        require(patientId);
        return identity.find(patientId, bearerToken);
    }

    public IdentityClient.PortalAccountState withdraw(UUID patientId, String bearerToken) {
        Patient patient = require(patientId);
        IdentityClient.PortalAccountState state = identity.withdraw(patientId, bearerToken);
        audit.record("PORTAL_ACCESS_WITHDRAWN", "Patient", patientId, "MRN " + patient.getMrn());
        return state;
    }

    /**
     * The record, or a 404.
     *
     * <p>Every path here reads the patient first, including the two that only pass an id along.
     * That is the point of enrolment living in this service: an id that names no patient is refused
     * here, where the register is, rather than becoming a portal account pointing at nothing.
     */
    private Patient require(UUID patientId) {
        return patients.findById(patientId).orElseThrow(() -> NotFoundException.of("Patient", patientId));
    }
}
