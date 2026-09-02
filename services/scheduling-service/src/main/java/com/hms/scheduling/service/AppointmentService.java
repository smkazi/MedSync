package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.scheduling.client.NoShowRiskClient;
import com.hms.scheduling.client.RoomDirectoryClient;
import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.repo.AppointmentRepository;
import com.hms.scheduling.repo.ClinicianScheduleRepository;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.repo.ScheduleBlackoutRepository;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Booking, moving, checking in and closing appointments. */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    /** The exclusion constraint's name, so a violation can be told apart from any other. */
    private static final String CLINICIAN_OVERLAP_CONSTRAINT = "no_overlapping_appointments";
    private static final String ROOM_OVERLAP_CONSTRAINT = "no_overlapping_room_bookings";

    private final AppointmentRepository appointments;
    private final ClinicianScheduleRepository schedules;
    private final ScheduleBlackoutRepository blackouts;
    private final EncounterRepository encounters;
    private final NoShowRiskClient riskClient;
    private final RoomDirectoryClient roomDirectory;
    private final EventPublisher events;
    private final AuditService audit;
    private final QueueService queue;
    private final ZoneId zone;

    public AppointmentService(AppointmentRepository appointments, ClinicianScheduleRepository schedules,
                             ScheduleBlackoutRepository blackouts, EncounterRepository encounters,
                             NoShowRiskClient riskClient, RoomDirectoryClient roomDirectory,
                             EventPublisher events, AuditService audit, QueueService queue,
                             @Value("${hms.scheduling.zone:UTC}") String zone) {
        this.appointments = appointments;
        this.schedules = schedules;
        this.blackouts = blackouts;
        this.encounters = encounters;
        this.riskClient = riskClient;
        this.roomDirectory = roomDirectory;
        this.events = events;
        this.audit = audit;
        this.queue = queue;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Books an appointment.
     *
     * <p>The overlap check is the database's exclusion constraint, not a query here: two
     * receptionists booking the same slot at the same moment would both pass a read-then-write.
     * A constraint violation is translated into a 409 the front desk can act on.
     */
    @Transactional
    public SchedulingDtos.AppointmentResponse book(SchedulingDtos.BookAppointmentRequest request,
                                                   String bearerToken) {
        Instant startsAt = request.startsAt();
        Instant endsAt = startsAt.plus(Duration.ofMinutes(request.durationOrDefault()));

        if (startsAt.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            throw new BadRequestException("An appointment cannot be booked in the past");
        }
        if (!blackouts.findOverlapping(request.clinicianId(), startsAt, endsAt).isEmpty()) {
            throw new ConflictException("The clinician is unavailable at that time");
        }

        Appointment appointment = new Appointment(request.patientId(), request.patientMrn().trim(),
                request.clinicianId(), request.departmentCode().trim().toUpperCase(Locale.ROOT), startsAt, endsAt,
                CurrentUser.usernameOrSystem());
        appointment.setClinicianName(request.clinicianName());
        appointment.setPriority(request.priorityOrDefault());
        appointment.setReason(request.reason());

        // Validated before the insert, and fatal if it fails: writing an unverified room code onto
        // an appointment would mean sending a patient to a room that may not exist or may be a
        // resuscitation bay. Unlike the no-show score below, this does not degrade.
        RoomDirectoryClient.RoomLocation room = null;
        if (request.roomCode() != null && !request.roomCode().isBlank()) {
            room = roomDirectory.require(request.roomCode(), bearerToken);
            appointment.assignRoom(room.id(), room.code());
        }

        applyNoShowRisk(appointment, request, bearerToken);

        try {
            appointments.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException ex) {
            throw overlapConflict(ex, appointment);
        }

        audit.record("APPOINTMENT_BOOKED", "Appointment", appointment.getId(),
                "%s with clinician %s at %s%s".formatted(appointment.getPatientMrn(),
                        appointment.getClinicianId(), startsAt,
                        appointment.getRoomCode() == null ? "" : " in " + appointment.getRoomCode()));
        publish("appointment.booked", appointment);
        return SchedulingMapper.toResponse(appointment, null, room);
    }

    /**
     * Fetches and caches the no-show score.
     *
     * <p>Best effort by design: a failure here leaves the score null and the booking proceeds.
     * The patient's own attendance history supplies the strongest feature.
     */
    private void applyNoShowRisk(Appointment appointment,
                                 SchedulingDtos.BookAppointmentRequest request, String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }
        long history = appointments.countAttendanceHistory(request.patientId());
        long noShows = appointments.countByPatientIdAndStatus(request.patientId(),
                SchedulingEnums.AppointmentStatus.NO_SHOW);
        java.time.ZonedDateTime local = appointment.getStartsAt().atZone(zone);

        NoShowRiskClient.Request payload = new NoShowRiskClient.Request(
                Math.max(0, (int) Duration.between(Instant.now(), appointment.getStartsAt()).toDays()),
                // Age is not held in this service; the model treats the platform default as unknown.
                40,
                (int) history,
                (int) noShows,
                local.getHour(),
                local.getDayOfWeek().getValue() - 1,
                history == 0,
                request.reminderContactOrDefault(),
                request.travelDistanceKm() == null ? 5.0 : request.travelDistanceKm(),
                appointment.getPriority().name());

        try {
            Optional<NoShowRiskClient.Risk> risk =
                    riskClient.score(payload, bearerToken).get(3, TimeUnit.SECONDS);
            risk.ifPresent(value -> appointment.applyNoShowRisk(value.score(), value.band()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while fetching no-show risk; booking without a score");
        } catch (Exception ex) {
            log.warn("Could not attach a no-show score ({}); booking anyway", ex.getMessage());
        }
    }

    /**
     * Turns an exclusion-constraint violation into the 409 the front desk can act on.
     *
     * <p>The two constraints get different messages, and the difference is not cosmetic: "that
     * clinician is already booked" and "that room is already in use" call for different fixes —
     * move the appointment, or move the room. A single message would send whoever is standing at
     * the desk looking in the wrong place.
     *
     * <p>Anything that is not one of the two overlap constraints is rethrown: it is a real
     * integrity failure, and disguising it as a booking clash would hide it.
     */
    private static RuntimeException overlapConflict(DataIntegrityViolationException ex,
                                                    Appointment appointment) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message == null) {
            return ex;
        }
        if (message.contains(ROOM_OVERLAP_CONSTRAINT)) {
            return new ConflictException(("Room %s is already in use at that time. "
                    + "Pick another room or another slot.")
                    .formatted(appointment.getRoomCode() == null ? "" : appointment.getRoomCode()));
        }
        if (message.contains(CLINICIAN_OVERLAP_CONSTRAINT)) {
            return new ConflictException("That slot has just been taken; please pick another");
        }
        return ex;
    }

    @Transactional(readOnly = true)
    public SchedulingDtos.AvailabilityResponse availability(UUID clinicianId, LocalDate date) {
        List<com.hms.scheduling.domain.ClinicianSchedule> pattern =
                schedules.findByClinicianIdAndDayOfWeekAndActiveTrue(clinicianId,
                        date.getDayOfWeek().getValue());
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<SchedulingDtos.SlotResponse> slots = SlotCalculator.slotsFor(date, zone, pattern,
                appointments.findOccupying(clinicianId, dayStart, dayEnd),
                blackouts.findOverlapping(clinicianId, dayStart, dayEnd), Instant.now());

        int slotMinutes = pattern.isEmpty() ? 15 : pattern.get(0).getSlotMinutes();
        return new SchedulingDtos.AvailabilityResponse(clinicianId, date, slotMinutes, slots);
    }

    @Transactional
    public SchedulingDtos.AppointmentResponse reschedule(UUID id,
                                                         SchedulingDtos.RescheduleRequest request,
                                                         String bearerToken) {
        Appointment appointment = require(id);
        if (!appointment.isAmendable()) {
            throw new ConflictException("A " + appointment.getStatus() + " appointment cannot be moved");
        }
        Instant newEnd = request.startsAt().plus(Duration.ofMinutes(request.durationOrDefault()));
        if (!blackouts.findOverlapping(appointment.getClinicianId(), request.startsAt(), newEnd).isEmpty()) {
            throw new ConflictException("The clinician is unavailable at that time");
        }
        appointment.reschedule(request.startsAt(), newEnd);
        try {
            appointments.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException ex) {
            throw overlapConflict(ex, appointment);
        }
        audit.record("APPOINTMENT_RESCHEDULED", "Appointment", id, "moved to " + request.startsAt());
        publish("appointment.rescheduled", appointment);
        return SchedulingMapper.toResponse(appointment, encounterIdFor(id),
                roomResolver(bearerToken).apply(appointment.getRoomCode()));
    }

    @Transactional
    public SchedulingDtos.AppointmentResponse transition(UUID id,
                                                         SchedulingEnums.AppointmentStatus target,
                                                         String bearerToken) {
        Appointment appointment = require(id);
        if (!appointment.canTransitionTo(target)) {
            throw new ConflictException("An appointment cannot go from " + appointment.getStatus()
                    + " to " + target);
        }
        switch (target) {
            case CHECKED_IN -> {
                appointment.checkIn();
                // The token is issued here rather than by anything the desk has to remember,
                // because a queue somebody maintains alongside the appointment book is a queue
                // that has drifted out of step with it by mid-morning. Inside this transaction on
                // purpose: a rolled-back check-in must not leave a number that nobody holds.
                queue.issueFor(appointment);
            }
            case IN_PROGRESS -> {
                appointment.begin();
                queue.markCalled(id);
            }
            case COMPLETED -> {
                appointment.complete();
                queue.markFinished(id);
            }
            case NO_SHOW -> {
                if (appointment.getEndsAt().isAfter(Instant.now())) {
                    // Marking a patient absent before their slot has ended would be a false record.
                    throw new ConflictException(
                            "This appointment has not finished yet; it cannot be marked as a no-show");
                }
                appointment.markNoShow();
                // Off the board. A number that stays lit for somebody who is not coming is how a
                // corridor stops believing the display.
                queue.markFinished(id);
            }
            default -> throw new BadRequestException("Unsupported transition to " + target);
        }
        audit.record("APPOINTMENT_" + target, "Appointment", id, "status now " + target);
        publish("appointment." + target.name().toLowerCase(Locale.ROOT), appointment);
        return SchedulingMapper.toResponse(appointment, encounterIdFor(id),
                roomResolver(bearerToken).apply(appointment.getRoomCode()));
    }

    @Transactional
    public void cancel(UUID id, String reason) {
        Appointment appointment = require(id);
        if (!appointment.isAmendable()) {
            throw new ConflictException("A " + appointment.getStatus() + " appointment cannot be cancelled");
        }
        appointment.cancel(reason);
        audit.record("APPOINTMENT_CANCELLED", "Appointment", id, reason == null ? "no reason given" : reason);
        publish("appointment.cancelled", appointment);
    }

    @Transactional(readOnly = true)
    public Appointment require(UUID id) {
        return appointments.findById(id).orElseThrow(() -> NotFoundException.of("Appointment", id));
    }

    @Transactional(readOnly = true)
    public SchedulingDtos.AppointmentResponse get(UUID id, String bearerToken) {
        Appointment appointment = require(id);
        return SchedulingMapper.toResponse(appointment, encounterIdFor(id),
                roomResolver(bearerToken).apply(appointment.getRoomCode()));
    }

    @Transactional(readOnly = true)
    public Page<SchedulingDtos.AppointmentResponse> search(String mrn,
                                                            List<SchedulingEnums.AppointmentStatus> statuses,
                                                            LocalDate from, LocalDate to,
                                                            Pageable pageable, String bearerToken) {
        List<SchedulingEnums.AppointmentStatus> effective = statuses == null || statuses.isEmpty()
                ? List.of(SchedulingEnums.AppointmentStatus.BOOKED,
                          SchedulingEnums.AppointmentStatus.CHECKED_IN,
                          SchedulingEnums.AppointmentStatus.IN_PROGRESS)
                : statuses;
        LocalDate start = from == null ? LocalDate.now(zone) : from;
        LocalDate end = to == null ? start.plusDays(1) : to.plusDays(1);
        return appointments.search(QueryPatterns.exactOrAny(mrn), effective,
                        start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant(), pageable)
                .map(withRooms(roomResolver(bearerToken)));
    }

    @Transactional(readOnly = true)
    public List<SchedulingDtos.AppointmentResponse> forPatient(UUID patientId, String bearerToken) {
        Function<Appointment, SchedulingDtos.AppointmentResponse> mapper =
                withRooms(roomResolver(bearerToken));
        return appointments.findByPatientIdOrderByStartsAtDesc(patientId).stream()
                .map(mapper)
                .toList();
    }

    /** Booked appointments whose time has passed with no check-in — the no-show candidates. */
    @Transactional(readOnly = true)
    public List<SchedulingDtos.AppointmentResponse> lapsed(String bearerToken) {
        Function<String, RoomDirectoryClient.RoomLocation> rooms = roomResolver(bearerToken);
        return appointments.findLapsed(Instant.now()).stream()
                .map(appointment -> SchedulingMapper.toResponse(appointment, null,
                        rooms.apply(appointment.getRoomCode())))
                .toList();
    }

    /** One mapper for a whole page, so the room cache is shared across its rows. */
    private Function<Appointment, SchedulingDtos.AppointmentResponse> withRooms(
            Function<String, RoomDirectoryClient.RoomLocation> rooms) {
        return appointment -> SchedulingMapper.toResponse(appointment,
                encounterIdFor(appointment.getId()), rooms.apply(appointment.getRoomCode()));
    }

    /**
     * Resolves room codes to locations, once per distinct code per request.
     *
     * <p>Every read path used to pass {@code null} here, which meant {@code RoomView.resolved} was
     * <em>always</em> false and the wayfinding this was all for — "General OPD · Ground Floor, from
     * reception turn right" — never appeared anywhere. The DTO's own comment claimed the name and
     * directions were "resolved live". They were not, on any endpoint except the booking response
     * itself, and nothing noticed because no screen displayed a room until the appointment book
     * became writable.
     *
     * <p>The cache is what makes this affordable. A page holds up to 200 appointments and the
     * building has 21 rooms, so without it a single list would make 200 HTTP calls to
     * patient-service for a handful of distinct answers. With it, one call per distinct code.
     *
     * <p>{@link RoomDirectoryClient#find} is the fail-soft variant on purpose: rendering is not
     * booking. A directory that cannot answer leaves {@code resolved} false and the bare code on
     * screen, which is the honest thing to show — the booking path still uses {@code require} and
     * still refuses outright.
     */
    private Function<String, RoomDirectoryClient.RoomLocation> roomResolver(String bearerToken) {
        Map<String, Optional<RoomDirectoryClient.RoomLocation>> resolved = new HashMap<>();
        return code -> code == null
                ? null
                : resolved.computeIfAbsent(code, key -> roomDirectory.find(key, bearerToken))
                        .orElse(null);
    }

    private UUID encounterIdFor(UUID appointmentId) {
        return encounters.findByAppointmentId(appointmentId)
                .map(com.hms.scheduling.domain.Encounter::getId)
                .orElse(null);
    }

    private void publish(String type, Appointment appointment) {
        events.publish(Topics.APPOINTMENT, DomainEvent.of(type, "Appointment", appointment.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                Map.of("patientId", appointment.getPatientId().toString(),
                       "mrn", appointment.getPatientMrn(),
                       "clinicianId", appointment.getClinicianId().toString(),
                       "startsAt", appointment.getStartsAt().toString(),
                       "status", appointment.getStatus().name())));
    }
}
