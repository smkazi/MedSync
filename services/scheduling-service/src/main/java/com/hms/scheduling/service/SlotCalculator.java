package com.hms.scheduling.service;

import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.ClinicianSchedule;
import com.hms.scheduling.domain.ScheduleBlackout;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a clinician's weekly pattern into the concrete slots a receptionist can click.
 *
 * <p>Pure: it takes the schedule, the blackouts and the existing appointments and returns slots.
 * That keeps the arithmetic — which is where off-by-one and boundary bugs live — testable without
 * a database.
 */
public final class SlotCalculator {

    private SlotCalculator() {
    }

    /**
     * Builds the day's slots, marking each available or not with the reason.
     *
     * <p>A slot is unavailable if it is already taken, falls inside a blackout, or has passed.
     * Slots are reported rather than filtered out, so the UI can show a full day and grey out
     * what cannot be booked instead of silently presenting a shorter list.
     */
    public static List<SchedulingDtos.SlotResponse> slotsFor(LocalDate date, ZoneId zone,
                                                            List<ClinicianSchedule> schedules,
                                                            List<Appointment> occupied,
                                                            List<ScheduleBlackout> blackouts,
                                                            Instant now) {
        List<SchedulingDtos.SlotResponse> slots = new ArrayList<>();
        int isoDay = date.getDayOfWeek().getValue();

        for (ClinicianSchedule schedule : schedules) {
            if (!schedule.isActive() || schedule.getDayOfWeek() != isoDay) {
                continue;
            }
            Instant windowStart = date.atTime(schedule.getStartTime()).atZone(zone).toInstant();
            Instant windowEnd = date.atTime(schedule.getEndTime()).atZone(zone).toInstant();
            Duration slotLength = Duration.ofMinutes(schedule.getSlotMinutes());

            for (Instant start = windowStart; !start.plus(slotLength).isAfter(windowEnd);
                 start = start.plus(slotLength)) {
                Instant end = start.plus(slotLength);
                slots.add(describe(start, end, occupied, blackouts, now));
            }
        }
        slots.sort(java.util.Comparator.comparing(SchedulingDtos.SlotResponse::startsAt));
        return slots;
    }

    private static SchedulingDtos.SlotResponse describe(Instant start, Instant end,
                                                        List<Appointment> occupied,
                                                        List<ScheduleBlackout> blackouts,
                                                        Instant now) {
        if (end.isBefore(now)) {
            return new SchedulingDtos.SlotResponse(start, end, false, "in the past");
        }
        for (ScheduleBlackout blackout : blackouts) {
            if (blackout.covers(start, end)) {
                String reason = blackout.getReason() == null || blackout.getReason().isBlank()
                        ? "clinician unavailable"
                        : blackout.getReason();
                return new SchedulingDtos.SlotResponse(start, end, false, reason);
            }
        }
        for (Appointment appointment : occupied) {
            // Half-open comparison, matching the database's '[)' range: a slot ending exactly when
            // the next begins is not a clash.
            if (appointment.getStartsAt().isBefore(end) && appointment.getEndsAt().isAfter(start)) {
                return new SchedulingDtos.SlotResponse(start, end, false, "already booked");
            }
        }
        return new SchedulingDtos.SlotResponse(start, end, true, null);
    }
}
