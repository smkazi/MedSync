package com.hms.scheduling.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.service.AppointmentService;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    /**
     * Books an appointment.
     *
     * <p>The caller's own token is forwarded to ai-service for the no-show score, so decision
     * support runs with the user's authority rather than a service-wide credential.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.FRONT_DESK)
    public SchedulingDtos.AppointmentResponse book(@Valid @RequestBody SchedulingDtos.BookAppointmentRequest request,
                                                   HttpServletRequest httpRequest) {
        return service.book(request, bearerToken(httpRequest));
    }

    @GetMapping
    @PreAuthorize(Roles.CLINICAL_READ)
    public PageResponse<SchedulingDtos.AppointmentResponse> search(
            @RequestParam(required = false) String mrn,
            @RequestParam(required = false) List<SchedulingEnums.AppointmentStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest httpRequest) {
        return PageResponse.of(service.search(mrn, status, from, to,
                PageRequest.of(page, Math.min(size, 200), Sort.by("startsAt")),
                bearerToken(httpRequest)));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public SchedulingDtos.AppointmentResponse get(@PathVariable UUID id,
                                                  HttpServletRequest httpRequest) {
        return service.get(id, bearerToken(httpRequest));
    }

    @GetMapping("/patients/{patientId}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<SchedulingDtos.AppointmentResponse> forPatient(@PathVariable UUID patientId,
                                                               HttpServletRequest httpRequest) {
        return service.forPatient(patientId, bearerToken(httpRequest));
    }

    /** Slots for one clinician on one day, each marked bookable or not with the reason. */
    @GetMapping("/availability")
    @PreAuthorize(Roles.CLINICAL_READ)
    public SchedulingDtos.AvailabilityResponse availability(
            @RequestParam UUID clinicianId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.availability(clinicianId, date);
    }

    @PutMapping("/{id}/schedule")
    @PreAuthorize(Roles.FRONT_DESK)
    public SchedulingDtos.AppointmentResponse reschedule(@PathVariable UUID id,
                                                         @Valid @RequestBody SchedulingDtos.RescheduleRequest request,
                                                         HttpServletRequest httpRequest) {
        return service.reschedule(id, request, bearerToken(httpRequest));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize(Roles.FRONT_DESK)
    public SchedulingDtos.AppointmentResponse checkIn(@PathVariable UUID id,
                                                  HttpServletRequest httpRequest) {
        return service.transition(id, SchedulingEnums.AppointmentStatus.CHECKED_IN, bearerToken(httpRequest));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.AppointmentResponse start(@PathVariable UUID id,
                                                  HttpServletRequest httpRequest) {
        return service.transition(id, SchedulingEnums.AppointmentStatus.IN_PROGRESS, bearerToken(httpRequest));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public SchedulingDtos.AppointmentResponse complete(@PathVariable UUID id,
                                                  HttpServletRequest httpRequest) {
        return service.transition(id, SchedulingEnums.AppointmentStatus.COMPLETED, bearerToken(httpRequest));
    }

    /** Records that the patient did not attend. Only valid once the slot has passed. */
    @PostMapping("/{id}/no-show")
    @PreAuthorize(Roles.FRONT_DESK)
    public SchedulingDtos.AppointmentResponse noShow(@PathVariable UUID id,
                                                  HttpServletRequest httpRequest) {
        return service.transition(id, SchedulingEnums.AppointmentStatus.NO_SHOW, bearerToken(httpRequest));
    }

    /** Booked appointments whose slot has passed with no check-in. */
    @GetMapping("/lapsed")
    @PreAuthorize(Roles.FRONT_DESK)
    public List<SchedulingDtos.AppointmentResponse> lapsed(HttpServletRequest httpRequest) {
        return service.lapsed(bearerToken(httpRequest));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.FRONT_DESK)
    public SchedulingDtos.MessageResponse cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) SchedulingDtos.CancelRequest request) {
        service.cancel(id, request == null ? null : request.reason());
        return new SchedulingDtos.MessageResponse("Appointment cancelled");
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }
}
