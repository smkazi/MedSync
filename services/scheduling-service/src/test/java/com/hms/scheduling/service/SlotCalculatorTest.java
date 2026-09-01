package com.hms.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.ClinicianSchedule;
import com.hms.scheduling.domain.ScheduleBlackout;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slot arithmetic, tested without a database — this is where boundary and off-by-one bugs live.
 */
class SlotCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final UUID CLINICIAN = UUID.randomUUID();
    /** A Wednesday, so the ISO day-of-week is 3. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 2);
    /** Well before the test date, so nothing is filtered out as being in the past. */
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private ClinicianSchedule schedule(LocalTime from, LocalTime to, int slotMinutes) {
        return new ClinicianSchedule(CLINICIAN, "GEN", WEDNESDAY.getDayOfWeek().getValue(),
                from, to, slotMinutes);
    }

    private Appointment appointmentAt(String startIso, int minutes) {
        Instant start = Instant.parse(startIso);
        return new Appointment(UUID.randomUUID(), "MRN-1", CLINICIAN, "GEN", start,
                start.plusSeconds(minutes * 60L), "reception");
    }

    private List<SchedulingDtos.SlotResponse> slots(List<ClinicianSchedule> schedules,
                                                    List<Appointment> occupied,
                                                    List<ScheduleBlackout> blackouts) {
        return SlotCalculator.slotsFor(WEDNESDAY, ZONE, schedules, occupied, blackouts, NOW);
    }

    @Test
    @DisplayName("a four-hour window at 15 minutes yields sixteen slots")
    void dividesTheWindowIntoSlots() {
        List<SchedulingDtos.SlotResponse> result =
                slots(List.of(schedule(LocalTime.of(9, 0), LocalTime.of(13, 0), 15)), List.of(), List.of());

        assertThat(result).hasSize(16);
        assertThat(result.get(0).startsAt()).isEqualTo(Instant.parse("2026-09-02T09:00:00Z"));
        assertThat(result.get(15).endsAt()).isEqualTo(Instant.parse("2026-09-02T13:00:00Z"));
    }

    @Test
    @DisplayName("a partial trailing slot is not offered")
    void doesNotOfferAPartialSlot() {
        // 09:00-09:50 at 20 minutes fits two whole slots; the last 10 minutes are not bookable.
        List<SchedulingDtos.SlotResponse> result =
                slots(List.of(schedule(LocalTime.of(9, 0), LocalTime.of(9, 50), 20)), List.of(), List.of());

        assertThat(result).hasSize(2);
        assertThat(result.get(1).endsAt()).isEqualTo(Instant.parse("2026-09-02T09:40:00Z"));
    }

    @Test
    @DisplayName("a schedule for another weekday produces nothing")
    void ignoresOtherWeekdays() {
        ClinicianSchedule monday = new ClinicianSchedule(CLINICIAN, "GEN", 1,
                LocalTime.of(9, 0), LocalTime.of(13, 0), 15);
        assertThat(slots(List.of(monday), List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("an inactive schedule produces nothing")
    void ignoresInactiveSchedules() {
        ClinicianSchedule inactive = schedule(LocalTime.of(9, 0), LocalTime.of(13, 0), 15);
        inactive.setActive(false);
        assertThat(slots(List.of(inactive), List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a taken slot is reported as unavailable rather than hidden")
    void marksTakenSlots() {
        List<SchedulingDtos.SlotResponse> result = slots(
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)),
                List.of(appointmentAt("2026-09-02T09:15:00Z", 15)), List.of());

        assertThat(result).hasSize(4);
        assertThat(result.get(1).available()).isFalse();
        assertThat(result.get(1).unavailableReason()).isEqualTo("already booked");
        assertThat(result.get(0).available())
                .as("the UI shows a full day and greys out what cannot be booked")
                .isTrue();
    }

    @Test
    @DisplayName("a slot ending exactly when a booking starts is not a clash")
    void adjacentSlotsDoNotClash() {
        // Half-open ranges, matching the database constraint's '[)'.
        List<SchedulingDtos.SlotResponse> result = slots(
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)),
                List.of(appointmentAt("2026-09-02T09:15:00Z", 15)), List.of());

        assertThat(result.get(0).available()).isTrue();   // 09:00-09:15
        assertThat(result.get(2).available()).isTrue();   // 09:30-09:45
    }

    @Test
    @DisplayName("a longer booking blocks every slot it spans")
    void aLongBookingBlocksEverySlotItSpans() {
        List<SchedulingDtos.SlotResponse> result = slots(
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)),
                List.of(appointmentAt("2026-09-02T09:15:00Z", 30)), List.of());

        assertThat(result.get(1).available()).isFalse();
        assertThat(result.get(2).available()).isFalse();
        assertThat(result.get(3).available()).isTrue();
    }

    @Test
    @DisplayName("a blackout blocks its slots and gives its reason")
    void blackoutsBlockSlotsWithAReason() {
        ScheduleBlackout blackout = new ScheduleBlackout(CLINICIAN,
                Instant.parse("2026-09-02T09:00:00Z"), Instant.parse("2026-09-02T09:30:00Z"),
                "theatre list");

        List<SchedulingDtos.SlotResponse> result = slots(
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)), List.of(),
                List.of(blackout));

        assertThat(result.get(0).unavailableReason()).isEqualTo("theatre list");
        assertThat(result.get(1).unavailableReason()).isEqualTo("theatre list");
        assertThat(result.get(2).available()).isTrue();
    }

    @Test
    @DisplayName("a blackout with no reason still explains itself")
    void blackoutWithoutAReasonStillExplains() {
        ScheduleBlackout blackout = new ScheduleBlackout(CLINICIAN,
                Instant.parse("2026-09-02T09:00:00Z"), Instant.parse("2026-09-02T09:15:00Z"), null);

        List<SchedulingDtos.SlotResponse> result = slots(
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)), List.of(),
                List.of(blackout));

        assertThat(result.get(0).unavailableReason()).isEqualTo("clinician unavailable");
    }

    @Test
    @DisplayName("slots already past are not bookable")
    void pastSlotsAreNotBookable() {
        Instant afterTheMorning = Instant.parse("2026-09-02T09:31:00Z");

        List<SchedulingDtos.SlotResponse> result = SlotCalculator.slotsFor(WEDNESDAY, ZONE,
                List.of(schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 15)), List.of(), List.of(),
                afterTheMorning);

        assertThat(result.get(0).unavailableReason()).isEqualTo("in the past");
        assertThat(result.get(1).unavailableReason()).isEqualTo("in the past");
        assertThat(result.get(3).available()).isTrue();
    }

    @Test
    @DisplayName("two windows in one day are merged in time order")
    void mergesMorningAndAfternoonWindows() {
        List<SchedulingDtos.SlotResponse> result = slots(List.of(
                schedule(LocalTime.of(14, 0), LocalTime.of(15, 0), 30),
                schedule(LocalTime.of(9, 0), LocalTime.of(10, 0), 30)), List.of(), List.of());

        assertThat(result).hasSize(4);
        assertThat(result.get(0).startsAt()).isEqualTo(Instant.parse("2026-09-02T09:00:00Z"));
        assertThat(result.get(3).startsAt()).isEqualTo(Instant.parse("2026-09-02T14:30:00Z"));
    }
}
