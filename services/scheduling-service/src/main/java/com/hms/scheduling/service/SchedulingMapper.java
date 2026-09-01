package com.hms.scheduling.service;

import com.hms.scheduling.client.RoomDirectoryClient;
import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.ClinicalNote;
import com.hms.scheduling.domain.ClinicianSchedule;
import com.hms.scheduling.domain.Diagnosis;
import com.hms.scheduling.domain.Encounter;
import com.hms.scheduling.domain.VitalsRecord;
import com.hms.scheduling.web.dto.SchedulingDtos;
import java.util.List;
import java.util.UUID;

/** Entity to DTO translation for the scheduling API. */
public final class SchedulingMapper {

    private SchedulingMapper() {
    }

    public static SchedulingDtos.AppointmentResponse toResponse(Appointment appointment,
                                                               UUID encounterId) {
        return toResponse(appointment, encounterId, null);
    }

    /**
     * @param room the resolved location, or null when the appointment has no room or the directory
     *             could not answer for it. A code with no resolution still renders — the patient is
     *             better served by "GF-GEN" than by no room at all.
     */
    public static SchedulingDtos.AppointmentResponse toResponse(Appointment appointment,
                                                               UUID encounterId,
                                                               RoomDirectoryClient.RoomLocation room) {
        SchedulingDtos.NoShowRiskView risk = appointment.getNoShowRisk() == null
                ? null
                : new SchedulingDtos.NoShowRiskView(appointment.getNoShowRisk(),
                        appointment.getNoShowBand());
        return new SchedulingDtos.AppointmentResponse(
                appointment.getId(), appointment.getPatientId(), appointment.getPatientMrn(),
                appointment.getClinicianId(), appointment.getClinicianName(),
                appointment.getDepartmentCode(), appointment.getStartsAt(), appointment.getEndsAt(),
                appointment.getStatus(), appointment.getPriority(), appointment.getReason(),
                appointment.getBookedBy(), appointment.getCheckedInAt(),
                appointment.getCancelledReason(), risk, encounterId,
                roomView(appointment, room));
    }

    private static SchedulingDtos.RoomView roomView(Appointment appointment,
                                                    RoomDirectoryClient.RoomLocation room) {
        if (appointment.getRoomCode() == null) {
            return null;
        }
        if (room == null) {
            // A code the directory could not answer for: a decommissioned room, or the directory
            // briefly unreachable. Say so rather than dropping the room silently, which would tell
            // a patient there is nowhere to go.
            return new SchedulingDtos.RoomView(appointment.getRoomCode(), null, null, null, false);
        }
        return new SchedulingDtos.RoomView(room.code(), room.name(), room.floorName(),
                room.directions(), true);
    }

    public static SchedulingDtos.NoteResponse toResponse(ClinicalNote note) {
        return new SchedulingDtos.NoteResponse(note.getId(), note.getRevision(), note.getSubjective(),
                note.getObjective(), note.getAssessment(), note.getPlan(), note.getAuthor(),
                note.isSigned(), note.getSignedAt(), note.getSignedBy(), note.getAmendsId());
    }

    public static SchedulingDtos.VitalsResponse toResponse(VitalsRecord vitals) {
        return new SchedulingDtos.VitalsResponse(vitals.getId(), vitals.getRecordedAt(),
                vitals.getRecordedBy(), vitals.getHeartRate(), vitals.getSystolicBp(),
                vitals.getDiastolicBp(), vitals.getRespiratoryRate(), vitals.getTemperatureC(),
                vitals.getOxygenSaturation(), vitals.getWeightKg(), vitals.getHeightCm(),
                vitals.getPainScore(), vitals.getConsciousness(), vitals.bodyMassIndex());
    }

    public static SchedulingDtos.DiagnosisResponse toResponse(Diagnosis diagnosis) {
        return new SchedulingDtos.DiagnosisResponse(diagnosis.getId(), diagnosis.getIcd10Code(),
                diagnosis.getDescription(), diagnosis.getCategory(), diagnosis.getRecordedBy());
    }

    public static SchedulingDtos.ScheduleResponse toResponse(ClinicianSchedule schedule) {
        return new SchedulingDtos.ScheduleResponse(schedule.getId(), schedule.getClinicianId(),
                schedule.getDepartmentCode(), schedule.getDayOfWeek(), schedule.getStartTime(),
                schedule.getEndTime(), schedule.getSlotMinutes(), schedule.isActive());
    }

    public static SchedulingDtos.EncounterResponse toResponse(Encounter encounter,
                                                             List<VitalsRecord> vitals,
                                                             List<Diagnosis> diagnoses) {
        return new SchedulingDtos.EncounterResponse(
                encounter.getId(), encounter.getAppointmentId(), encounter.getPatientId(),
                encounter.getPatientMrn(), encounter.getClinicianId(), encounter.getDepartmentCode(),
                encounter.getEncounterType(), encounter.getStartedAt(), encounter.getEndedAt(),
                encounter.getStatus(),
                encounter.getNotes().stream().map(SchedulingMapper::toResponse).toList(),
                vitals.stream().map(SchedulingMapper::toResponse).toList(),
                diagnoses.stream().map(SchedulingMapper::toResponse).toList());
    }

    public static SchedulingDtos.EncounterSummary toSummary(Encounter encounter, int diagnosisCount) {
        return new SchedulingDtos.EncounterSummary(encounter.getId(), encounter.getPatientId(),
                encounter.getPatientMrn(), encounter.getEncounterType(), encounter.getStartedAt(),
                encounter.getStatus(), encounter.getNotes().size(), diagnosisCount);
    }
}
