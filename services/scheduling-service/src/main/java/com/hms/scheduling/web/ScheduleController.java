package com.hms.scheduling.web;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.security.Roles;
import com.hms.scheduling.domain.ClinicianSchedule;
import com.hms.scheduling.domain.ScheduleBlackout;
import com.hms.scheduling.repo.ClinicianScheduleRepository;
import com.hms.scheduling.repo.ScheduleBlackoutRepository;
import com.hms.scheduling.service.SchedulingMapper;
import com.hms.scheduling.web.dto.SchedulingDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Clinician working patterns and unavailability. */
@RestController
@RequestMapping("/schedules")
public class ScheduleController {

    private final ClinicianScheduleRepository schedules;
    private final ScheduleBlackoutRepository blackouts;
    private final AuditService audit;

    public ScheduleController(ClinicianScheduleRepository schedules,
                              ScheduleBlackoutRepository blackouts, AuditService audit) {
        this.schedules = schedules;
        this.blackouts = blackouts;
        this.audit = audit;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public SchedulingDtos.ScheduleResponse create(@Valid @RequestBody SchedulingDtos.CreateScheduleRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BadRequestException("The end of a working window must be after its start");
        }
        ClinicianSchedule schedule = new ClinicianSchedule(request.clinicianId(),
                request.departmentCode().trim().toUpperCase(Locale.ROOT), request.dayOfWeek(),
                request.startTime(), request.endTime(), request.slotMinutesOrDefault());
        schedules.save(schedule);
        audit.record("SCHEDULE_CREATED", "ClinicianSchedule", schedule.getId(),
                "day %d %s-%s".formatted(request.dayOfWeek(), request.startTime(), request.endTime()));
        return SchedulingMapper.toResponse(schedule);
    }

    @GetMapping("/clinicians/{clinicianId}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<SchedulingDtos.ScheduleResponse> forClinician(@PathVariable UUID clinicianId) {
        return schedules.findByClinicianIdAndActiveTrue(clinicianId).stream()
                .map(SchedulingMapper::toResponse)
                .toList();
    }

    /** Blocks out time a clinician is unavailable, which removes those slots from availability. */
    @PostMapping("/blackouts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','DOCTOR')")
    public SchedulingDtos.MessageResponse createBlackout(@Valid @RequestBody SchedulingDtos.CreateBlackoutRequest request) {
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BadRequestException("A blackout must end after it starts");
        }
        ScheduleBlackout blackout = new ScheduleBlackout(request.clinicianId(), request.startsAt(),
                request.endsAt(), request.reason());
        blackouts.save(blackout);
        audit.record("BLACKOUT_CREATED", "ScheduleBlackout", blackout.getId(),
                "%s to %s".formatted(request.startsAt(), request.endsAt()));
        return new SchedulingDtos.MessageResponse("Blackout recorded");
    }
}
