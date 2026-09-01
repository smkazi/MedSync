package com.hms.patient.service;

import com.hms.patient.domain.Bed;
import com.hms.patient.domain.Floor;
import com.hms.patient.domain.Room;
import com.hms.patient.domain.RoomType;
import com.hms.patient.web.dto.FacilityDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Entity to transport mapping for the facility directory.
 *
 * <p>Static, like {@code PatientMapper}: mapping is a pure function of the entity and has nothing
 * to inject.
 */
public final class FacilityMapper {

    private FacilityMapper() {
    }

    public static FacilityDtos.RoomTypeResponse toResponse(RoomType type) {
        return new FacilityDtos.RoomTypeResponse(type.getCode(), type.getName(), type.getDescription(),
                type.isClinical(), type.isBedAllocated(), type.isSchedulable(),
                type.getDisplayOrder(), type.isActive());
    }

    public static FacilityDtos.FloorResponse toResponse(Floor floor) {
        return new FacilityDtos.FloorResponse(floor.getId(), floor.getCode(), floor.getName(),
                floor.getLevel(), floor.isActive());
    }

    /**
     * @param bedCount actual bed rows, passed in rather than walked off the entity so this stays a
     *                 single query per page instead of one per room
     */
    public static FacilityDtos.RoomResponse toResponse(Room room, int bedCount) {
        RoomType type = room.getRoomType();
        return new FacilityDtos.RoomResponse(
                room.getId(), room.getCode(), room.getName(),
                type.getCode(), type.getName(),
                type.isClinical(), type.isBedAllocated(), type.isSchedulable(),
                room.getFloor().getCode(), room.getFloor().getName(), room.getFloor().getLevel(),
                room.getDepartment() == null ? null : room.getDepartment().getCode(),
                room.getDepartment() == null ? null : room.getDepartment().getName(),
                room.getCapacity(), bedCount,
                room.getWidthFt(), room.getLengthFt(), dimensions(room),
                room.getDirections(), room.isBookable(), room.isBookableNow(),
                room.isActive(), room.getNotes());
    }

    public static FacilityDtos.RoomSummary toSummary(Room room) {
        return new FacilityDtos.RoomSummary(room.getId(), room.getCode(), room.getName(),
                room.getRoomType().getCode(), room.getRoomType().getName(),
                room.getFloor().getName(),
                room.getDepartment() == null ? null : room.getDepartment().getCode(),
                room.isBookableNow());
    }

    public static FacilityDtos.RoomLocation toLocation(Room room) {
        return new FacilityDtos.RoomLocation(room.getId(), room.getCode(), room.getName(),
                room.getFloor().getName(), room.getDirections(), room.isBookableNow());
    }

    public static FacilityDtos.BedResponse toResponse(Bed bed) {
        Room room = bed.getRoom();
        return new FacilityDtos.BedResponse(bed.getId(), bed.getCode(), bed.getLabel(), bed.isActive(),
                room.getCode(), room.getName(), room.getFloor().getName());
    }

    /**
     * The as-drawn size, formatted the way the drawings write it.
     *
     * <p>Feet and inches rather than decimal feet, because "15'6"" is what is on the plan and what
     * whoever is holding the plan will look for. Null when either dimension is unrecorded — half a
     * size is worse than none, since it reads as a complete measurement.
     */
    static String dimensions(Room room) {
        BigDecimal width = room.getWidthFt();
        BigDecimal length = room.getLengthFt();
        if (width == null || length == null) {
            return null;
        }
        return feetAndInches(width) + " x " + feetAndInches(length);
    }

    private static String feetAndInches(BigDecimal decimalFeet) {
        int feet = decimalFeet.intValue();
        int inches = decimalFeet.subtract(BigDecimal.valueOf(feet))
                .multiply(BigDecimal.valueOf(12))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        // 11.98 ft rounds to 12 inches, which is a foot. Carry it rather than printing 11'12".
        if (inches == 12) {
            feet += 1;
            inches = 0;
        }
        return inches == 0 ? feet + "'" : feet + "'" + inches + "\"";
    }
}
