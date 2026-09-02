package com.hms.admissions.web.dto;

import com.hms.admissions.domain.AdmissionEnums;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AdmissionDtos {

    private AdmissionDtos() {
    }

    /**
     * Someone has arrived in casualty.
     *
     * @param triageAcuity 1 (immediate) to 5 (non-urgent). Required, and there is deliberately no
     *                     default: an untriaged patient sorted as though they were a 3 is the
     *                     failure this whole module is arranged to prevent.
     */
    public record ArrivalRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @NotNull @Min(1) @Max(5) Integer triageAcuity,
            @NotBlank @Size(max = 255) String presentingComplaint) {
    }

    /** Re-triage. A patient waiting in a corridor can get worse, and the board must notice. */
    public record RetriageRequest(@NotNull @Min(1) @Max(5) Integer triageAcuity) {
    }

    public record PlaceInBedRequest(@NotNull UUID bedId) {
    }

    public record AttendanceResponse(UUID id, UUID patientId, String patientMrn, Instant arrivedAt,
                                     int triageAcuity, String presentingComplaint, UUID bedId,
                                     String bedCode, String roomCode,
                                     AdmissionEnums.AttendanceStatus status, UUID admissionId,
                                     Instant closedAt, String triagedBy, long waitingMinutes) {
    }

    /**
     * Admitting a patient.
     *
     * @param attendanceId the casualty attendance this came from, when it came from one. Null for
     *                     a planned admission, which is a real state rather than missing data.
     */
    public record AdmitRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            UUID attendanceId,
            @NotNull UUID bedId,
            @NotNull UUID admittingClinicianId,
            @NotNull AdmissionEnums.AdmissionSource source,
            LocalDate expectedDischarge) {
    }

    public record TransferRequest(@NotNull UUID toBedId,
                                  @NotBlank @Size(max = 255) String reason) {
    }

    public record DischargeRequest(@Size(max = 1000) String summary) {
    }

    public record AdmissionResponse(UUID id, UUID patientId, String patientMrn, UUID attendanceId,
                                    UUID bedId, String bedCode, String roomCode,
                                    UUID admittingClinicianId, AdmissionEnums.AdmissionSource source,
                                    Instant admittedAt, LocalDate expectedDischarge,
                                    Instant dischargedAt, String dischargeSummary,
                                    AdmissionEnums.AdmissionStatus status, long lengthOfStayDays,
                                    List<TransferResponse> transfers) {

        public AdmissionResponse {
            transfers = transfers == null ? List.of() : List.copyOf(transfers);
        }
    }

    public record TransferResponse(UUID id, String fromBedCode, String toBedCode, Instant movedAt,
                                   String movedBy, String reason) {
    }

    /**
     * A bed and whether anybody is in it.
     *
     * <p>Occupancy is this service's answer, not the facility directory's — patient-service
     * deliberately keeps no occupancy flag on a bed, because a flag maintained by one service and
     * written by another is a flag that goes stale.
     */
    public record BedStateResponse(UUID bedId, String bedCode, String label, String roomCode,
                                   String roomName, String floorName, boolean occupied,
                                   AdmissionEnums.OccupantType occupantType, UUID occupantId,
                                   Instant occupiedSince) {
    }
}
