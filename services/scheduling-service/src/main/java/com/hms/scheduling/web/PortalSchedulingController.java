package com.hms.scheduling.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.scheduling.service.AppointmentService;
import com.hms.scheduling.service.PortalSchedulingService;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A patient's own appointments, and self-booking into published availability.
 *
 * <p>No patient id appears anywhere in this file. The list is the session's patient's, the booking
 * is made for the session's patient, and the cancellation is refused unless the appointment already
 * belongs to them — the one place an id is named, because an appointment id is the only way to say
 * which appointment.
 */
@RestController
@RequestMapping("/portal")
@PreAuthorize(Roles.PORTAL)
public class PortalSchedulingController {

    private final PortalSchedulingService portal;
    private final AppointmentService appointments;

    public PortalSchedulingController(PortalSchedulingService portal, AppointmentService appointments) {
        this.portal = portal;
        this.appointments = appointments;
    }

    /** Everything booked for this patient, newest first, past and future. */
    @GetMapping("/appointments")
    public List<SchedulingDtos.AppointmentResponse> mine(HttpServletRequest request) {
        return portal.mine(CurrentUser.requirePatientId(), bearer(request));
    }

    /**
     * A clinician's free slots on a day, exactly as the staff availability grid computes them.
     *
     * <p>The same calculator and the same answer, deliberately: a portal that computed availability
     * separately would eventually disagree with the desk about whether 10:30 is free, and the
     * patient standing at the counter would be the one told they are wrong. What it does not carry
     * is who is in the taken slots — the response is a list of times and a free/taken flag, which
     * is what a booking screen needs and all it needs.
     */
    @GetMapping("/availability")
    public SchedulingDtos.AvailabilityResponse availability(
            @RequestParam UUID clinicianId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return appointments.availability(clinicianId, date);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public SchedulingDtos.AppointmentResponse book(
            @Valid @RequestBody SchedulingDtos.PortalBookingRequest request,
            HttpServletRequest httpRequest) {
        return portal.book(CurrentUser.requirePatientId(), request, bearer(httpRequest));
    }

    /** Every visit, in full: the signed notes, the observations and the diagnoses recorded. */
    @GetMapping("/encounters")
    public List<SchedulingDtos.EncounterResponse> myEncounters() {
        return portal.myEncounters(CurrentUser.requirePatientId());
    }

    @GetMapping("/encounters/{id}")
    public SchedulingDtos.EncounterResponse myEncounter(@PathVariable UUID id) {
        return portal.myEncounter(CurrentUser.requirePatientId(), id);
    }

    @PostMapping("/appointments/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id,
                       @Valid @RequestBody(required = false) SchedulingDtos.PortalCancelRequest request) {
        portal.cancel(CurrentUser.requirePatientId(), id, request == null ? null : request.reason());
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }
}
