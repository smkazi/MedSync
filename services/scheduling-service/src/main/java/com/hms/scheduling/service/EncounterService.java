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
import com.hms.scheduling.client.StaffDirectoryClient;
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
import java.util.Locale;
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
    private final EscalationPolicyService escalations;
    private final CareTeamGuard careTeam;
    private final StaffDirectoryClient staffDirectory;

    public EncounterService(EncounterRepository encounters, ClinicalNoteRepository notes,
                            VitalsRepository vitals, DiagnosisRepository diagnoses,
                            AppointmentRepository appointments, EventPublisher events,
                            AuditService audit, EscalationPolicyService escalations,
                            CareTeamGuard careTeam, StaffDirectoryClient staffDirectory) {
        this.encounters = encounters;
        this.notes = notes;
        this.vitals = vitals;
        this.diagnoses = diagnoses;
        this.appointments = appointments;
        this.events = events;
        this.audit = audit;
        this.escalations = escalations;
        this.careTeam = careTeam;
        this.staffDirectory = staffDirectory;
    }

    /**
     * Opens an encounter, either from an appointment or standalone (a walk-in).
     *
     * <p>Opening from an appointment copies the patient and clinician off it, so a walk-in and a
     * booked visit produce the same shape of record.
     */
    @Transactional
    public SchedulingDtos.EncounterResponse open(SchedulingDtos.OpenEncounterRequest request,
                                                 String bearerToken) {
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
                    request.clinicianId(), request.departmentCode().trim().toUpperCase(Locale.ROOT),
                    request.typeOrDefault());
        }
        // The clinician id is checked against the staff directory before it is written, and this
        // is where it stops being a label. Since the narrowing, that column decides who may read
        // the chart, and an id nobody validated would be a care relationship the platform invented.
        // Fails closed: if the directory is unreachable the encounter is refused, because an
        // unverified clinician must not reach a record that access turns on.
        staffDirectory.require(encounter.getClinicianId(), bearerToken);

        encounters.save(encounter);
        // The care team, before anything else can read the record. The encounter's own clinician
        // and whoever opened it: this is the half that makes the narrowing shippable rather than an
        // outage, because it means the treating clinician's day does not change at all.
        careTeam.enrolOnOpening(encounter.getId(), encounter.getClinicianId());
        audit.record("ENCOUNTER_OPENED", "Encounter", encounter.getId(),
                "%s (%s)".formatted(encounter.getPatientMrn(), encounter.getEncounterType()));
        publish("encounter.opened", encounter);
        return detail(encounter.getId());
    }

    @Transactional(readOnly = true)
    public SchedulingDtos.EncounterResponse detail(UUID id) {
        careTeam.requireChartAccess(id);
        Encounter encounter = encounters.findDetailById(id)
                .orElseThrow(() -> NotFoundException.of("Encounter", id));
        return SchedulingMapper.toResponse(encounter,
                vitals.findByEncounterIdOrderByRecordedAtDesc(id),
                diagnoses.findByEncounterIdOrderByCategoryAsc(id),
                escalations.byBand());
    }

    /**
     * The patient's encounters, as an index: date, type, status and how many notes and diagnoses
     * each has. Not narrowed by the care team, and that is a decision rather than an oversight.
     *
     * <p>Minimum necessary applies to the record; this is the catalogue. Hiding from a treating
     * clinician that four earlier visits exist is worse medicine than showing that they do — and it
     * would break break-glass itself, which depends on somebody being able to see that there is
     * something to ask for. Nothing clinical is here: no assessment, no note, no vital sign. Opening
     * any one of these still goes through {@link #detail}, which is narrowed.
     */
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
        requireEncounterToRead(encounterId);
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
                request.weightKg(), request.heightCm(), request.painScore(), request.consciousness(),
                request.onSupplementalOxygen());
        vitals.save(record);
        News2Calculator.Score score = News2Calculator.of(record);
        // The score goes in the audit detail and the observations do not. A NEWS2 is a derived
        // number rather than a clinical narrative, and "who was scored 7 and when" is exactly the
        // question a deterioration review asks — while `detail` must never carry clinical free
        // text, which is AuditService's own contract.
        audit.record("VITALS_RECORDED", "Encounter", encounterId,
                "by %s, NEWS2 %d (%s)".formatted(record.getRecordedBy(), score.total(),
                        score.band()));
        return SchedulingMapper.toResponse(record, escalations.byBand());
    }

    @Transactional
    public SchedulingDtos.DiagnosisResponse addDiagnosis(UUID encounterId,
                                                         SchedulingDtos.DiagnosisRequest request) {
        Encounter encounter = requireEncounter(encounterId);
        String code = request.icd10Code().trim().toUpperCase(Locale.ROOT);
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

    /** Who is on this encounter's team, and how each of them came to be. */
    @Transactional(readOnly = true)
    public List<SchedulingDtos.CareTeamMemberResponse> careTeam(UUID encounterId) {
        careTeam.requireChartAccess(encounterId);
        return careTeam.team(encounterId).stream().map(SchedulingMapper::toResponse).toList();
    }

    /** Break-glass. The reason is validated and recorded by the guard, which owns that decision. */
    @Transactional
    public SchedulingDtos.CareTeamMemberResponse breakGlass(UUID encounterId, String reason) {
        return SchedulingMapper.toResponse(careTeam.breakGlass(encounterId, reason));
    }

    /**
     * The choke point for every <em>write</em> on one encounter. Loads it, and records that the
     * caller took part — see {@code CareTeamGuard} for why providing care enrols you rather than
     * being refused. The role gate on the endpoint is unchanged and is still what decides whether
     * the call may happen at all.
     *
     * <p>Here rather than repeated at seven call sites, for the reason every guard belongs at a
     * choke point: a method added later gets it without anybody remembering to.
     */
    private Encounter requireEncounter(UUID id) {
        Encounter encounter = encounters.findById(id)
                .orElseThrow(() -> NotFoundException.of("Encounter", id));
        careTeam.enrolOnContact(id);
        return encounter;
    }

    /** The same lookup for a read, where membership is required rather than recorded. */
    private Encounter requireEncounterToRead(UUID id) {
        careTeam.requireChartAccess(id);
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
