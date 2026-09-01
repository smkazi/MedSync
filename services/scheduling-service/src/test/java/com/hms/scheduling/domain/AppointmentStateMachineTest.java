package com.hms.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AppointmentStateMachineTest {

    private Appointment appointment() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return new Appointment(UUID.randomUUID(), "MRN-1", UUID.randomUUID(), "GEN",
                start, start.plus(15, ChronoUnit.MINUTES), "reception");
    }

    @Test
    @DisplayName("a new appointment is booked and amendable")
    void startsBooked() {
        Appointment subject = appointment();
        assertThat(subject.getStatus()).isEqualTo(SchedulingEnums.AppointmentStatus.BOOKED);
        assertThat(subject.isAmendable()).isTrue();
    }

    @Test
    @DisplayName("the normal path is booked, checked in, in progress, completed")
    void happyPath() {
        Appointment subject = appointment();
        assertThat(subject.canTransitionTo(SchedulingEnums.AppointmentStatus.CHECKED_IN)).isTrue();
        subject.checkIn();
        assertThat(subject.getCheckedInAt()).isNotNull();

        assertThat(subject.canTransitionTo(SchedulingEnums.AppointmentStatus.IN_PROGRESS)).isTrue();
        subject.begin();
        assertThat(subject.canTransitionTo(SchedulingEnums.AppointmentStatus.COMPLETED)).isTrue();
        subject.complete();
        assertThat(subject.isAmendable()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SchedulingEnums.AppointmentStatus.class,
            names = {"BOOKED", "CHECKED_IN", "IN_PROGRESS", "COMPLETED", "CANCELLED", "NO_SHOW"})
    @DisplayName("no transition out of a terminal status is legal")
    void terminalStatusesAreFinal(SchedulingEnums.AppointmentStatus target) {
        Appointment completed = appointment();
        completed.complete();
        assertThat(completed.canTransitionTo(target)).isFalse();

        Appointment cancelled = appointment();
        cancelled.cancel("patient rang");
        assertThat(cancelled.canTransitionTo(target)).isFalse();
    }

    @Test
    @DisplayName("a checked-in appointment cannot skip straight to completed")
    void cannotSkipInProgress() {
        Appointment subject = appointment();
        subject.checkIn();
        assertThat(subject.canTransitionTo(SchedulingEnums.AppointmentStatus.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("a checked-in patient cannot be recorded as a no-show")
    void checkedInCannotBecomeNoShow() {
        // They are standing at the desk; recording absence would be a false record.
        Appointment subject = appointment();
        subject.checkIn();
        assertThat(subject.canTransitionTo(SchedulingEnums.AppointmentStatus.NO_SHOW)).isFalse();
    }

    @Test
    @DisplayName("cancelled and no-show release the clinician's slot; other statuses hold it")
    void slotOccupancy() {
        assertThat(SchedulingEnums.AppointmentStatus.BOOKED.occupiesSlot()).isTrue();
        assertThat(SchedulingEnums.AppointmentStatus.CHECKED_IN.occupiesSlot()).isTrue();
        assertThat(SchedulingEnums.AppointmentStatus.COMPLETED.occupiesSlot()).isTrue();
        assertThat(SchedulingEnums.AppointmentStatus.CANCELLED.occupiesSlot()).isFalse();
        assertThat(SchedulingEnums.AppointmentStatus.NO_SHOW.occupiesSlot()).isFalse();
    }

    @Test
    @DisplayName("rescheduling returns the appointment to booked and clears the check-in")
    void reschedulingResetsArrival() {
        Appointment subject = appointment();
        subject.checkIn();
        Instant newStart = Instant.now().plus(2, ChronoUnit.DAYS);

        subject.reschedule(newStart, newStart.plus(15, ChronoUnit.MINUTES));

        assertThat(subject.getStatus()).isEqualTo(SchedulingEnums.AppointmentStatus.BOOKED);
        assertThat(subject.getCheckedInAt())
                .as("the patient has not arrived for the new time")
                .isNull();
    }
}
