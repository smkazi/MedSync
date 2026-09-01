package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.ClinicalNote;
import com.hms.scheduling.domain.Diagnosis;
import com.hms.scheduling.domain.Encounter;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.domain.VitalsRecord;
import com.hms.scheduling.repo.AppointmentRepository;
import com.hms.scheduling.repo.ClinicalNoteRepository;
import com.hms.scheduling.repo.DiagnosisRepository;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.repo.VitalsRepository;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encounters and everything recorded inside them.
 *
 * <p>The rule that shapes this service: a signed clinical note is a legal record of what a
 * clinician asserted, so it is never edited. A correction becomes a new revision that points back
 * at what it amends, and the original stays readable.
 */
@Service
public class EncounterService {

    private final EncounterRepository encounters;
    private final ClinicalNoteRepository notes;
    private final VitalsRepository vitals;
    private final DiagnosisRepository diagnoses;
    private final AppointmentRepository appointments;
    private final EventPublisher events;
    private final AuditService audit;

    public EncounterService(EncounterRepository encounters, ClinicalNoteRepository notes,
                            VitalsRepository vitals, DiagnosisRepository diagnoses,
                            AppointmentRepository appointments, EventPublisher events,
                            AuditService audit) {
        this.encounters = encounters;
        this.notes = notes;
        this.vitals = vitals;
        this.diagnoses = diagnoses;
        this.appointments = appointments;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Opens an encounter, either from an appointment or standalone (a walk-in).
     *
     * <p>Opening from an appointment copies the patient and clinician off it, so a walk-in and a
     * booked visit produce the same shape of record.
     */
    @Transactional
    public SchedulingDtos.EncounterResponse open(SchedulingDtos.OpenEncounterRequest request) {
        Encounter encounter;
        if (request.appointmentId() != null) {
            Appointment appointment = appointments.findById(request.appointmentId())
                    .orElseThrow(() -> NotFoundException.of("Appointment", request.appointmentId()));
            encounters.findByAppointmentId(appointment.getId()).ifPresent(existing -> {
                throw new ConflictException("This appointment already has an encounter");
            });
            encounter = new Encounter(appointment.getPatientId(), appointment.getPatientMrn(),
                    appointment.getClinicianId(), appointment.getDepartmentCode(),
                    request.typeOrDefault());
            encounter.setAppointmentId(appointment.getId());
            // Opening the encounter is the clinician starting the consultation.
            if (appointment.canTransitionTo(SchedulingEnums.AppointmentStatus.IN_PROGRESS)) {
                appointment.begin();
            }
        } else {
            if (request.patientId() == null || request.patientMrn() == null
                    || request.clinicianId() == null || request.departmentCode() == null) {
                throw new BadRequestException(
                        "A standalone encounter needs patientId, patientMrn, clinicianId and departmentCode");
            }
            encounter = new Encounter(request.patientId(), request.patientMrn().trim(),
                    request.clinicianId(), request.departmentCode().trim().toUpperCase(),
                    request.typeOrDefault());
        }
        encounters.save(encounter);
        audit.record("ENCOUNTER_OPENED", "Encounter", encounter.getId(),
                "%s (%s)".formatted(encounter.getPatientMrn(), encounter.getEncounterType()));
        publish("encounter.opened", encounter);
        return detail(encounter.getId());
    }

    @Transactional(readOnly = true)
    public SchedulingDtos.EncounterResponse detail(UUID id) {
        Encounter encounter = encounters.findDetailById(id)
                .orElseThrow(() -> NotFoundException.of("Encounter", id));
        return SchedulingMapper.toResponse(encounter,
                vitals.findByEncounterIdOrderByRecordedAtDesc(id),
                diagnoses.findByEncounterIdOrderByCategoryAsc(id));
    }

    @Transactional(readOnly = true)
    public List<SchedulingDtos.EncounterSummary> forPatient(UUID patientId) {
        return encounters.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .map(encounter -> SchedulingMapper.toSummary(encounter,
                        diagnoses.findByEncounterIdOrderByCategoryAsc(encounter.getId()).size()))
                .toList();
    }

    /**
     * Writes the encounter's note.
     *
     * <p>An unsigned note is updated in place. Once signed, this creates the next revision as an
     * addendum instead — which is why a clinician can never silently change what they signed.
     */
    @Transactional
    public SchedulingDtos.NoteResponse writeNote(UUID encounterId, SchedulingDtos.NoteRequest request) {
        Encounter encounter = requireEncounter(encounterId);
        String author = CurrentUser.usernameOrSystem();
        ClinicalNote current = notes.findFirstByEncounterIdOrderByRevisionDesc(encounterId).orElse(null);

        if (current == null) {
            ClinicalNote note = new ClinicalNote(encounter, author, 1);
            note.updateContent(request.subjective(), request.objective(), request.assessment(),
                    request.plan());
            encounter.addNote(note);
            encounters.save(encounter);
            audit.record("NOTE_CREATED", "Encounter", encounterId, "revision 1 by " + author);
            return SchedulingMapper.toResponse(note);
        }

        if (!current.isSigned()) {
            current.updateContent(request.subjective(), request.objective(), request.assessment(),
                    request.plan());
            audit.record("NOTE_UPDATED", "Encounter", encounterId,
                    "revision " + current.getRevision() + " amended before signing");
            return SchedulingMapper.toResponse(current);
        }

        ClinicalNote addendum = new ClinicalNote(encounter, author, current.getRevision() + 1);
        addendum.updateContent(request.subjective(), request.objective(), request.assessment(),
                request.plan());
        addendum.setAmendsId(current.getId());
        encounter.addNote(addendum);
        encounters.save(encounter);
        audit.record("NOTE_ADDENDUM", "Encounter", encounterId,
                "revision %d amends revision %d".formatted(addendum.getRevision(),
                        current.getRevision()));
        publish("encounter.note.addendum", encounter);
        return SchedulingMapper.toResponse(addendum);
    }

    /** Signs the current note. An empty note is not signable — a signature must attest to something. */
    @Transactional
    public SchedulingDtos.NoteResponse signNote(UUID encounterId) {
        requireEncounter(encounterId);
        ClinicalNote current = notes.findFirstByEncounterIdOrderByRevisionDesc(encounterId)
                .orElseThrow(() -> new ConflictException("There is no note on this encounter to sign"));
        if (current.isSigned()) {
            throw new ConflictException("Revision " + current.getRevision() + " is already signed");
        }
        if (!current.hasContent()) {
            throw new ConflictException("An empty note cannot be signed");
        }
        current.sign(CurrentUser.usernameOrSystem());
        audit.record("NOTE_SIGNED", "Encounter", encounterId,
                "revision %d signed by %s".formatted(current.getRevision(), current.getSignedBy()));
        publish("encounter.note.signed", requireEncounter(encounterId));
        return SchedulingMapper.toResponse(current);
    }

    @Transactional(readOnly = true)
    public List<SchedulingDtos.NoteResponse> noteHistory(UUID encounterId) {
        requireEncounter(encounterId);
        return notes.findByEncounterIdOrderByRevisionAsc(encounterId).stream()
                .map(SchedulingMapper::toResponse)
                .toList();
    }

    @Transactional
    public SchedulingDtos.VitalsResponse recordVitals(UUID encounterId,
                                                      SchedulingDtos.VitalsRequest request) {
        Encounter encounter = requireEncounter(encounterId);
        VitalsRecord record = new VitalsRecord(encounter, CurrentUser.usernameOrSystem());
        record.record(request.heartRate(), request.systolicBp(), request.diastolicBp(),
                request.respiratoryRate(), request.temperatureC(), request.oxygenSaturation(),
                request.weightKg(), request.heightCm(), request.painScore(), request.consciousness());
        vitals.save(record);
        audit.record("VITALS_RECORDED", "Encounter", encounterId, "by " + record.getRecordedBy());
        return SchedulingMapper.toResponse(record);
    }

    @Transactional
    public SchedulingDtos.DiagnosisResponse addDiagnosis(UUID encounterId,
                                                         SchedulingDtos.DiagnosisRequest request) {
        Encounter encounter = requireEncounter(encounterId);
        String code = request.icd10Code().trim().toUpperCase();
        if (diagnoses.existsByEncounterIdAndIcd10Code(encounterId, code)) {
            throw new ConflictException(code + " is already recorded on this encounter");
        }
        Diagnosis diagnosis = new Diagnosis(encounter, code, request.description().trim(),
                request.categoryOrDefault(), CurrentUser.usernameOrSystem());
        diagnoses.save(diagnosis);
        audit.record("DIAGNOSIS_RECORDED", "Encounter", encounterId,
                "%s (%s)".formatted(code, diagnosis.getCategory()));
        publish("encounter.diagnosis.recorded", encounter);
        return SchedulingMapper.toResponse(diagnosis);
    }

    /**
     * Closes the encounter.
     *
     * <p>An unsigned note blocks closing: an episode of care whose record was never attested to is
     * an incomplete chart, and finding that out weeks later is far more expensive than now.
     */
    @Transactional
    public SchedulingDtos.EncounterResponse close(UUID encounterId) {
        Encounter encounter = requireEncounter(encounterId);
        if (!encounter.isOpen()) {
            throw new ConflictException("This encounter is already closed");
        }
        ClinicalNote current = notes.findFirstByEncounterIdOrderByRevisionDesc(encounterId).orElse(null);
        if (current != null && !current.isSigned()) {
            throw new ConflictException(
                    "Revision " + current.getRevision() + " is unsigned; sign the note before closing");
        }
        encounter.close();
        appointments.findById(encounter.getAppointmentId() == null ? UUID.randomUUID()
                        : encounter.getAppointmentId())
                .filter(appointment ->
                        appointment.canTransitionTo(SchedulingEnums.AppointmentStatus.COMPLETED))
                .ifPresent(Appointment::complete);

        audit.record("ENCOUNTER_CLOSED", "Encounter", encounterId, "closed by "
                + CurrentUser.usernameOrSystem());
        publish("encounter.closed", encounter);
        return detail(encounterId);
    }

    private Encounter requireEncounter(UUID id) {
        return encounters.findById(id).orElseThrow(() -> NotFoundException.of("Encounter", id));
    }

    private void publish(String type, Encounter encounter) {
        events.publish(Topics.APPOINTMENT, DomainEvent.of(type, "Encounter", encounter.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("patientId", encounter.getPatientId().toString(),
                       "mrn", encounter.getPatientMrn(),
                       "status", encounter.getStatus().name())));
    }
}
