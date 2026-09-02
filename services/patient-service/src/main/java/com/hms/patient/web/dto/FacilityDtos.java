package com.hms.patient.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes for the facility directory: floors, rooms and beds.
 *
 * <p>In its own file rather than joining {@code PatientDtos}, which is already long enough that
 * finding anything in it is a scroll.
 */
public final class FacilityDtos {

    private FacilityDtos() {
    }

    // ---- floors ---------------------------------------------------------------

    public record CreateFloorRequest(
            @NotBlank @Size(max = 8) String code,
            @NotBlank @Size(max = 60) String name,
            /** Signed: a basement is -1. Boxed because 0 is a legitimate value (ground floor). */
            @NotNull @Min(-10) @Max(200) Short level) {
    }

    public record UpdateFloorRequest(@Size(max = 60) String name,
                                     @Min(-10) @Max(200) Short level,
                                     Boolean active) {
    }

    public record FloorResponse(UUID id, String code, String name, short level, boolean active) {
    }

    // ---- room types (configuration) -------------------------------------------

    public record CreateRoomTypeRequest(
            @NotBlank @Size(max = 24) String code,
            @NotBlank @Size(max = 60) String name,
            @Size(max = 255) String description,
            /**
             * Boxed, all three: Jackson 3 refuses to map an absent JSON field onto a primitive, so
             * an optional flag must be nullable. Absent means false, which is the safe default for
             * every one of them - a type nobody has declared clinical should not be treated as
             * clinical.
             */
            Boolean clinical,
            Boolean bedAllocated,
            Boolean schedulable,
            @Min(0) @Max(1000) Short displayOrder) {

        public boolean clinicalOrDefault() {
            return Boolean.TRUE.equals(clinical);
        }

        public boolean bedAllocatedOrDefault() {
            return Boolean.TRUE.equals(bedAllocated);
        }

        public boolean schedulableOrDefault() {
            return Boolean.TRUE.equals(schedulable);
        }
    }

    public record UpdateRoomTypeRequest(
            @Size(max = 60) String name,
            @Size(max = 255) String description,
            Boolean clinical,
            Boolean bedAllocated,
            Boolean schedulable,
            @Min(0) @Max(1000) Short displayOrder,
            Boolean active) {
    }

    public record RoomTypeResponse(String code, String name, String description,
                                   boolean clinical, boolean bedAllocated, boolean schedulable,
                                   short displayOrder, boolean active) {
    }

    // ---- rooms ----------------------------------------------------------------

    public record CreateRoomRequest(
            @NotBlank @Size(max = 16) String code,
            @NotBlank @Size(max = 120) String name,
            /** A code from {@code GET /room-types}, not a fixed enum. */
            @NotBlank @Size(max = 24) String roomTypeCode,
            @NotBlank @Size(max = 8) String floorCode,
            /** Null for a non-clinical room. A lobby has no clinic. */
            @Size(max = 16) String departmentCode,
            @Min(0) @Max(200) Short capacity,
            @DecimalMin("0.01") @DecimalMax("999.99") BigDecimal widthFt,
            @DecimalMin("0.01") @DecimalMax("999.99") BigDecimal lengthFt,
            @Size(max = 255) String directions,
            /**
             * Whether appointments may be booked here. Boxed deliberately: Jackson 3 refuses to
             * map an absent JSON field onto a primitive, so an optional flag must be nullable.
             * Absent means false — a room is not bookable until someone says it is.
             */
            Boolean bookable,
            @Size(max = 500) String notes) {

        public boolean bookableOrDefault() {
            return Boolean.TRUE.equals(bookable);
        }

        public short capacityOrDefault() {
            return capacity == null ? 0 : capacity;
        }
    }

    public record UpdateRoomRequest(
            @Size(max = 120) String name,
            @Size(max = 24) String roomTypeCode,
            @Size(max = 8) String floorCode,
            @Size(max = 16) String departmentCode,
            @Min(0) @Max(200) Short capacity,
            @DecimalMin("0.01") @DecimalMax("999.99") BigDecimal widthFt,
            @DecimalMin("0.01") @DecimalMax("999.99") BigDecimal lengthFt,
            @Size(max = 255) String directions,
            Boolean bookable,
            Boolean active,
            @Size(max = 500) String notes) {
    }

    /**
     * A room as every other service and the UI see it.
     *
     * <p>Carries the floor name and the directions text, not just codes, because the one thing a
     * patient-facing view needs is a sentence it can print: "General OPD · Ground Floor · From
     * reception, follow the signs for General".
     */
    public record RoomResponse(UUID id, String code, String name,
                               /**
                                * The type's code plus the flags a caller needs to reason about it,
                                * so nothing downstream has to fetch the type separately or, worse,
                                * hard-code which codes are clinical.
                                */
                               String roomTypeCode, String roomTypeName,
                               boolean clinical, boolean bedAllocated, boolean schedulable,
                               String floorCode, String floorName, short floorLevel,
                               String departmentCode, String departmentName,
                               short capacity, int bedCount,
                               BigDecimal widthFt, BigDecimal lengthFt, String dimensions,
                               String directions, boolean bookable, boolean bookableNow,
                               boolean active, String notes) {
    }

    /** Pick-list row: enough to choose a room, no more. */
    public record RoomSummary(UUID id, String code, String name, String roomTypeCode,
                              String roomTypeName, String floorName, String departmentCode,
                              boolean bookable) {
    }

    /**
     * Where an appointment is. Deliberately small and deliberately separate from
     * {@link RoomResponse}: this is what crosses into scheduling-service and gets cached on an
     * appointment row, so it must not grow a field that could go stale without anyone noticing.
     */
    public record RoomLocation(UUID id, String code, String name,
                               String floorName, String directions, boolean bookable) {
    }

    // ---- beds -----------------------------------------------------------------

    public record CreateBedRequest(@NotBlank @Size(max = 16) String code,
                                   @Size(max = 60) String label) {
    }

    /**
     * Sparse update. The code is absent for the same reason a room's is: it identifies the
     * position, and admissions-service will reference a bed by it.
     */
    public record UpdateBedRequest(@Size(max = 60) String label, Boolean active) {
    }

    public record BedResponse(UUID id, String code, String label, boolean active,
                              String roomCode, String roomName, String floorName) {
    }

    /** A floor with its rooms — the shape a directory or a bed map renders from. */
    public record FloorWithRooms(FloorResponse floor, List<RoomSummary> rooms) {
    }
}
