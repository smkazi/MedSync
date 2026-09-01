package com.hms.scheduling.web.dto;

import com.hms.scheduling.domain.SchedulingEnums;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class SchedulingDtos {

    private SchedulingDtos() {
    }

    // ---- appointments ------------------------------------------------------

    public record BookAppointmentRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @NotNull UUID clinicianId,
            @Size(max = 160) String clinicianName,
            @NotBlank @Size(max = 16) String departmentCode,
            /**
             * Where the appointment happens. Optional: a booking may be taken before a room is
             * assigned, and a teleconsultation has none. When supplied it is validated against the
             * facility directory and the booking fails if the room does not exist or cannot be
             * booked — unlike the no-show score, a room is not something to degrade gracefully on.
             */
            @Size(max = 16) String roomCode,
            @NotNull Instant startsAt,
            @Min(5) @Max(240) Integer durationMinutes,
            SchedulingEnums.Priority priority,
            @Size(max = 500) String reason,
            /** Distance is a no-show feature the front desk may know; boxed because it is optional. */
            @Min(0) @Max(500) Integer travelDistanceKm,
            Boolean hasReminderContact) {

        public int durationOrDefault() {
            return durationMinutes == null ? 15 : durationMinutes;
        }

        public SchedulingEnums.Priority priorityOrDefault() {
            return priority == null ? SchedulingEnums.Priority.ROUTINE : priority;
        }

        public boolean reminderContactOrDefault() {
            return !Boolean.FALSE.equals(hasReminderContact);
        }
    }

    /** Moving an appointment may also move it to a different room. */
    public record RescheduleRequest(@NotNull Instant startsAt,
                                    @Min(5) @Max(240) Integer durationMinutes) {

        public int durationOrDefault() {
            return durationMinutes == null ? 15 : durationMinutes;
        }
    }

    public record CancelRequest(@Size(max = 255) String reason) {
    }

    public record NoShowRiskView(BigDecimal score, String band) {
    }

    public record AppointmentResponse(UUID id, UUID patientId, String patientMrn, UUID clinicianId,
                                      String clinicianName, String departmentCode, Instant startsAt,
                                      Instant endsAt, SchedulingEnums.AppointmentStatus status,
                                      SchedulingEnums.Priority priority, String reason, String bookedBy,
                                      Instant checkedInAt, String cancelledReason,
                                      NoShowRiskView noShowRisk, UUID encounterId,
                                      /**
                                       * Where to go. The code is cached on the appointment; the
                                       * name, floor and directions are resolved live, so a renamed
                                       * room does not leave stale text on an existing appointment.
                                       */
                                      RoomView room) {
    }

    /**
     * Wayfinding, as a patient reads it: "General OPD · Ground Floor · From reception, follow the
     * signs for General".
     *
     * <p>{@code resolved} is false when the appointment has a room code the directory could not
     * answer for — a decommissioned room, or the directory being briefly unreachable. The UI shows
     * the code alone in that case rather than pretending there is no room.
     */
    public record RoomView(String code, String name, String floorName, String directions,
                           boolean resolved) {
    }

    /** One bookable slot in a clinician's day. */
    public record SlotResponse(Instant startsAt, Instant endsAt, boolean available, String unavailableReason) {
    }

    public record AvailabilityResponse(UUID clinicianId, LocalDate date, int slotMinutes,
                                       List<SlotResponse> slots) {
    }

    // ---- clinician schedules ----------------------------------------------

    public record CreateScheduleRequest(
            @NotNull UUID clinicianId,
            @NotBlank @Size(max = 16) String departmentCode,
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @Min(5) @Max(240) Integer slotMinutes) {

        public int slotMinutesOrDefault() {
            return slotMinutes == null ? 15 : slotMinutes;
        }
    }

    public record ScheduleResponse(UUID id, UUID clinicianId, String departmentCode, int dayOfWeek,
                                   LocalTime startTime, LocalTime endTime, int slotMinutes,
                                   boolean active) {
    }

    public record CreateBlackoutRequest(@NotNull UUID clinicianId, @NotNull Instant startsAt,
                                        @NotNull Instant endsAt, @Size(max = 255) String reason) {
    }

    // ---- encounters --------------------------------------------------------

    public record OpenEncounterRequest(
            UUID appointmentId,
            UUID patientId,
            @Size(max = 24) String patientMrn,
            UUID clinicianId,
            @Size(max = 16) String departmentCode,
            SchedulingEnums.EncounterType encounterType) {

        public SchedulingEnums.EncounterType typeOrDefault() {
            return encounterType == null ? SchedulingEnums.EncounterType.OUTPATIENT : encounterType;
        }
    }

    public record NoteRequest(@Size(max = 20000) String subjective, @Size(max = 20000) String objective,
                              @Size(max = 20000) String assessment, @Size(max = 20000) String plan) {
    }

    public record NoteResponse(UUID id, int revision, String subjective, String objective,
                               String assessment, String plan, String author, boolean signed,
                               Instant signedAt, String signedBy, UUID amendsId) {
    }

    public record VitalsRequest(
            @Min(0) @Max(300) Integer heartRate,
            @Min(0) @Max(300) Integer systolicBp,
            @Min(0) @Max(200) Integer diastolicBp,
            @Min(0) @Max(90) Integer respiratoryRate,
            BigDecimal temperatureC,
            @Min(0) @Max(100) Integer oxygenSaturation,
            BigDecimal weightKg,
            BigDecimal heightCm,
            @Min(0) @Max(10) Integer painScore,
            @Size(max = 16) String consciousness) {
    }

    public record VitalsResponse(UUID id, Instant recordedAt, String recordedBy, Integer heartRate,
                                 Integer systolicBp, Integer diastolicBp, Integer respiratoryRate,
                                 BigDecimal temperatureC, Integer oxygenSaturation, BigDecimal weightKg,
                                 BigDecimal heightCm, Integer painScore, String consciousness,
                                 BigDecimal bodyMassIndex) {
    }

    public record DiagnosisRequest(@NotBlank @Size(max = 16) String icd10Code,
                                   @NotBlank @Size(max = 255) String description,
                                   SchedulingEnums.DiagnosisCategory category) {

        public SchedulingEnums.DiagnosisCategory categoryOrDefault() {
            return category == null ? SchedulingEnums.DiagnosisCategory.SECONDARY : category;
        }
    }

    public record DiagnosisResponse(UUID id, String icd10Code, String description,
                                    SchedulingEnums.DiagnosisCategory category, String recordedBy) {
    }

    public record EncounterResponse(UUID id, UUID appointmentId, UUID patientId, String patientMrn,
                                    UUID clinicianId, String departmentCode,
                                    SchedulingEnums.EncounterType encounterType, Instant startedAt,
                                    Instant endedAt, SchedulingEnums.EncounterStatus status,
                                    List<NoteResponse> notes, List<VitalsResponse> vitals,
                                    List<DiagnosisResponse> diagnoses) {
    }

    public record EncounterSummary(UUID id, UUID patientId, String patientMrn,
                                   SchedulingEnums.EncounterType encounterType, Instant startedAt,
                                   SchedulingEnums.EncounterStatus status, int noteCount,
                                   int diagnosisCount) {
    }

    public record MessageResponse(String message) {
    }
}
