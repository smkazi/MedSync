package com.hms.scheduling.service;

import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.scheduling.client.PortalIdentityClient;
import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.repo.AppointmentRepository;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The appointment book as one patient sees it, and the one act they may perform on it.
 *
 * <p>A thin layer over {@link AppointmentService} rather than a second implementation of booking:
 * the overlap constraint, the blackout check, the room validation and the no-show scoring are all
 * one code path, and a portal booking that skipped any of them would be a way to put an appointment
 * into a clinic's day that the clinic's own rules would have refused.
 *
 * <p>What this layer adds is everything a patient may not choose:
 * <ul>
 *   <li><strong>The patient is the session's.</strong> Never a request field. There is no patient
 *       id in any portal request body, so there is nothing to tamper with.</li>
 *   <li><strong>The priority is always routine.</strong> {@code Priority} is on the staff booking
 *       request, and a portal that passed it through would let anybody mark their own appointment
 *       urgent — which is not a booking rule, it is a triage decision, and it belongs to a
 *       clinician. Self-declared urgency also degrades to noise within a week, at which point the
 *       flag stops meaning anything for the patients who really are urgent.</li>
 *   <li><strong>No room.</strong> Rooms are allocated by the desk against the day's whole list. A
 *       patient choosing one would be choosing on behalf of everybody booked after them.</li>
 *   <li><strong>A horizon.</strong> Clinics publish a rota some weeks ahead; booking beyond it is
 *       booking against a pattern nobody has committed to yet.</li>
 * </ul>
 */
@Service
public class PortalSchedulingService {

    private final AppointmentService appointmentService;
    private final AppointmentRepository appointments;
    private final EncounterService encounters;
    private final EncounterRepository encounterRepository;
    private final PortalIdentityClient identity;
    private final int horizonDays;

    public PortalSchedulingService(AppointmentService appointmentService,
                                   AppointmentRepository appointments,
                                   EncounterService encounters,
                                   EncounterRepository encounterRepository,
                                   PortalIdentityClient identity,
                                   @Value("${hms.portal.booking-horizon-days:60}") int horizonDays) {
        this.appointmentService = appointmentService;
        this.appointments = appointments;
        this.encounters = encounters;
        this.encounterRepository = encounterRepository;
        this.identity = identity;
        this.horizonDays = horizonDays;
    }

    /**
     * Every visit this patient has had, in full — notes, vitals and diagnoses.
     *
     * <p>The full detail rather than a summary, and that is the certification criterion rather than
     * a generosity: "view, download and transmit" means the record, and a portal that showed a list
     * of dates would satisfy the word "view" and none of its purpose. What a clinician wrote about
     * a consultation is what the patient came to read.
     *
     * <p>Only signed notes reach it, which is the same rule the FHIR export applies and for the
     * same reason: an unsigned note is a draft whose author has not finished forming their opinion,
     * and it is not the hospital's position until they have.
     */
    @Transactional(readOnly = true)
    public List<SchedulingDtos.EncounterResponse> myEncounters(UUID patientId) {
        return encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .map(encounter -> encounters.detail(encounter.getId()))
                .map(PortalSchedulingService::signedNotesOnly)
                .toList();
    }

    /**
     * One of the patient's own visits.
     *
     * <p>404 rather than 403 for somebody else's, as everywhere else in the portal: an encounter id
     * that comes back "not yours" is an encounter id confirmed to exist.
     */
    @Transactional(readOnly = true)
    public SchedulingDtos.EncounterResponse myEncounter(UUID patientId, UUID encounterId) {
        return encounterRepository.findById(encounterId)
                .filter(candidate -> patientId.equals(candidate.getPatientId()))
                .map(candidate -> signedNotesOnly(encounters.detail(candidate.getId())))
                .orElseThrow(() -> NotFoundException.of("Encounter", encounterId));
    }

    @Transactional(readOnly = true)
    public List<SchedulingDtos.AppointmentResponse> mine(UUID patientId, String bearerToken) {
        return appointmentService.forPatient(patientId, bearerToken);
    }

    /**
     * Books an appointment for the signed-in patient.
     *
     * <p>The MRN is read from the register rather than taken from the request, for the same reason
     * the patient id is read from the token: a booking whose MRN a caller supplied is a booking
     * that can be filed against somebody else at the desk, where the MRN is what people go by.
     */
    public SchedulingDtos.AppointmentResponse book(UUID patientId,
                                                   SchedulingDtos.PortalBookingRequest request,
                                                   String bearerToken) {
        Instant startsAt = request.startsAt();
        if (startsAt.isAfter(Instant.now().plus(Duration.ofDays(horizonDays)))) {
            throw new BadRequestException(
                    "Appointments can be booked up to %d days ahead. For anything further out, "
                            .formatted(horizonDays)
                            + "please call the department.");
        }
        PortalIdentityClient.PortalIdentity me = identity.require(bearerToken);
        if (!me.id().equals(patientId)) {
            // Cannot happen: both come from the same token, one as a claim and one as the register's
            // answer to that claim. Stated anyway, because the alternative to failing here is
            // booking an appointment for somebody else under this patient's session.
            throw new IllegalStateException(
                    "The signed-in patient and the record read for them do not match");
        }

        SchedulingDtos.BookAppointmentRequest booking = new SchedulingDtos.BookAppointmentRequest(
                patientId, me.mrn(), request.clinicianId(), null,
                request.departmentCode(), null, startsAt, request.durationMinutes(),
                SchedulingEnums.Priority.ROUTINE, request.reason(), null, null);
        return appointmentService.book(booking, bearerToken);
    }

    /**
     * The same encounter with unsigned notes removed.
     *
     * <p>Filtered here rather than in a repository query, because {@code detail} is the staff view
     * and must keep showing a clinician their own draft. A draft is a sentence somebody is still
     * deciding whether they believe; showing it to its subject makes it a statement they never
     * made, and the way clinicians respond to that is by not drafting in the system.
     */
    private static SchedulingDtos.EncounterResponse signedNotesOnly(
            SchedulingDtos.EncounterResponse encounter) {
        return new SchedulingDtos.EncounterResponse(
                encounter.id(), encounter.appointmentId(), encounter.patientId(),
                encounter.patientMrn(), encounter.clinicianId(), encounter.departmentCode(),
                encounter.encounterType(), encounter.startedAt(), encounter.endedAt(),
                encounter.status(),
                encounter.notes().stream().filter(SchedulingDtos.NoteResponse::signed).toList(),
                encounter.vitals(), encounter.diagnoses());
    }

    /**
     * Cancels one of the signed-in patient's own appointments.
     *
     * <p>The only place in the portal where the caller names a record, because an appointment id is
     * the only way to say which one. So the ownership check is here and it answers 404 rather than
     * 403: telling somebody that an appointment exists but is not theirs is telling them that a
     * particular id is a real appointment, which is the first half of what an enumeration attack
     * wants.
     */
    public void cancel(UUID patientId, UUID appointmentId, String reason) {
        Appointment appointment = appointments.findById(appointmentId)
                .filter(candidate -> patientId.equals(candidate.getPatientId()))
                .orElseThrow(() -> NotFoundException.of("Appointment", appointmentId));
        appointmentService.cancel(appointment.getId(),
                reason == null || reason.isBlank() ? "Cancelled by the patient" : reason);
    }
}
