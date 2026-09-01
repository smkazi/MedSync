package com.hms.patient.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.patient.domain.Bed;
import com.hms.patient.domain.Department;
import com.hms.patient.domain.Floor;
import com.hms.patient.domain.Room;
import com.hms.patient.domain.RoomType;
import com.hms.patient.repo.BedRepository;
import com.hms.patient.repo.DepartmentRepository;
import com.hms.patient.repo.FloorRepository;
import com.hms.patient.repo.RoomRepository;
import com.hms.patient.repo.RoomTypeRepository;
import com.hms.patient.web.dto.FacilityDtos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The facility directory: floors, rooms and bed positions.
 *
 * <p>Master data. Reads are open to anyone who may look a patient up — a receptionist needs to
 * point someone at the right corridor, and the lab needs to know which room a sample came from.
 * Writes are administrative: rooms change when the building changes.
 */
@Service
public class FacilityService {

    private final FloorRepository floors;
    private final RoomRepository rooms;
    private final RoomTypeRepository roomTypes;
    private final BedRepository beds;
    private final DepartmentRepository departments;
    private final AuditService audit;

    public FacilityService(FloorRepository floors, RoomRepository rooms, RoomTypeRepository roomTypes,
                           BedRepository beds, DepartmentRepository departments, AuditService audit) {
        this.floors = floors;
        this.rooms = rooms;
        this.roomTypes = roomTypes;
        this.beds = beds;
        this.departments = departments;
        this.audit = audit;
    }

    // ---- room types (configuration) -------------------------------------------

    @Transactional(readOnly = true)
    public List<FacilityDtos.RoomTypeResponse> allRoomTypes() {
        return roomTypes.findByActiveTrueOrderByDisplayOrderAscCodeAsc().stream()
                .map(FacilityMapper::toResponse).toList();
    }

    /**
     * Adds a room type.
     *
     * <p>The whole point of the reference table: a hospital with a dialysis unit or a physiotherapy
     * room adds one here and every downstream filter, picker and validation rule picks it up with
     * no code change. The flag combinations that would misbehave are refused by CHECK constraints
     * on the table, so this method does not need to re-check them.
     */
    @Transactional
    public FacilityDtos.RoomTypeResponse createRoomType(FacilityDtos.CreateRoomTypeRequest request) {
        String code = normaliseCode(request.code());
        if (roomTypes.findByCodeIgnoreCase(code).isPresent()) {
            throw new ConflictException("Room type '" + code + "' already exists");
        }
        RoomType type = new RoomType(code, request.name().trim(), request.clinicalOrDefault(),
                request.bedAllocatedOrDefault(), request.schedulableOrDefault());
        type.setDescription(trimToNull(request.description()));
        if (request.displayOrder() != null) {
            type.setDisplayOrder(request.displayOrder());
        }
        roomTypes.save(type);
        audit.record("ROOM_TYPE_CREATED", "RoomType", code,
                "clinical=" + type.isClinical() + " bedAllocated=" + type.isBedAllocated()
                        + " schedulable=" + type.isSchedulable());
        return FacilityMapper.toResponse(type);
    }

    @Transactional
    public FacilityDtos.RoomTypeResponse updateRoomType(String code,
                                                        FacilityDtos.UpdateRoomTypeRequest request) {
        RoomType type = requireRoomType(code);
        if (request.name() != null) {
            type.setName(request.name().trim());
        }
        if (request.description() != null) {
            type.setDescription(trimToNull(request.description()));
        }
        if (request.clinical() != null) {
            type.setClinical(request.clinical());
        }
        if (request.bedAllocated() != null) {
            type.setBedAllocated(request.bedAllocated());
        }
        if (request.schedulable() != null) {
            type.setSchedulable(request.schedulable());
        }
        if (request.displayOrder() != null) {
            type.setDisplayOrder(request.displayOrder());
        }
        if (request.active() != null) {
            type.setActive(request.active());
        }
        roomTypes.save(type);
        audit.record("ROOM_TYPE_UPDATED", "RoomType", type.getCode(), type.getName());
        return FacilityMapper.toResponse(type);
    }

    // ---- floors ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FacilityDtos.FloorResponse> allFloors() {
        return floors.findByActiveTrueOrderByLevelAsc().stream().map(FacilityMapper::toResponse).toList();
    }

    @Transactional
    public FacilityDtos.FloorResponse createFloor(FacilityDtos.CreateFloorRequest request) {
        String code = normaliseCode(request.code());
        if (floors.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("Floor '" + code + "' already exists");
        }
        Floor floor = new Floor(code, request.name().trim(), request.level());
        floors.save(floor);
        audit.record("FLOOR_CREATED", "Floor", floor.getId(), code + " level " + request.level());
        return FacilityMapper.toResponse(floor);
    }

    @Transactional
    public FacilityDtos.FloorResponse updateFloor(UUID id, FacilityDtos.UpdateFloorRequest request) {
        Floor floor = floors.findById(id).orElseThrow(() -> NotFoundException.of("Floor", id));
        if (request.name() != null) {
            floor.setName(request.name().trim());
        }
        if (request.level() != null) {
            floor.setLevel(request.level());
        }
        if (request.active() != null) {
            floor.setActive(request.active());
        }
        audit.record("FLOOR_UPDATED", "Floor", id, floor.getCode());
        return FacilityMapper.toResponse(floors.save(floor));
    }

    // ---- rooms ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<FacilityDtos.RoomResponse> search(String query, String departmentCode, String roomTypeCode,
                                                  String floorCode, boolean includeInactive,
                                                  Pageable pageable) {
        Page<Room> page = rooms.search(QueryPatterns.contains(query),
                QueryPatterns.exactOrAny(departmentCode), QueryPatterns.exactOrAny(roomTypeCode),
                QueryPatterns.exactOrAny(floorCode), includeInactive, pageable);
        Map<UUID, Integer> bedCounts = bedCountsFor(page.getContent());
        return page.map(room -> FacilityMapper.toResponse(room, bedCounts.getOrDefault(room.getId(), 0)));
    }

    @Transactional(readOnly = true)
    public FacilityDtos.RoomResponse byCode(String code) {
        Room room = requireRoom(code);
        return FacilityMapper.toResponse(room, beds.findByRoomIdAndActiveTrueOrderByCodeAsc(room.getId()).size());
    }

    /**
     * The minimal location shape, for scheduling-service to validate a room and cache its name.
     *
     * <p>Separate endpoint from the full room read so the cross-service contract is explicit and
     * narrow: scheduling gets a name, a floor and a directions string, and does not become
     * dependent on capacity or dimensions changing shape.
     */
    @Transactional(readOnly = true)
    public FacilityDtos.RoomLocation locationOf(String code) {
        return FacilityMapper.toLocation(requireRoom(code));
    }

    /** Rooms an appointment may be booked into, for the booking dialog's picker. */
    @Transactional(readOnly = true)
    public List<FacilityDtos.RoomSummary> bookableRooms(String departmentCode) {
        String wanted = departmentCode == null || departmentCode.isBlank()
                ? null : normaliseCode(departmentCode);
        return rooms.findByActiveTrueOrderByCodeAsc().stream()
                .filter(Room::isBookableNow)
                .filter(room -> wanted == null
                        || (room.getDepartment() != null && wanted.equals(room.getDepartment().getCode())))
                .map(FacilityMapper::toSummary)
                .toList();
    }

    /** The whole building, grouped — what a directory page and a bed map both render from. */
    @Transactional(readOnly = true)
    public List<FacilityDtos.FloorWithRooms> directory() {
        List<Room> active = rooms.findByActiveTrueOrderByCodeAsc();
        Map<String, List<FacilityDtos.RoomSummary>> byFloor = new HashMap<>();
        for (Room room : active) {
            byFloor.computeIfAbsent(room.getFloor().getCode(), key -> new ArrayList<>())
                    .add(FacilityMapper.toSummary(room));
        }
        return floors.findByActiveTrueOrderByLevelAsc().stream()
                .map(floor -> new FacilityDtos.FloorWithRooms(FacilityMapper.toResponse(floor),
                        byFloor.getOrDefault(floor.getCode(), List.of())))
                .toList();
    }

    @Transactional
    public FacilityDtos.RoomResponse createRoom(FacilityDtos.CreateRoomRequest request) {
        String code = normaliseCode(request.code());
        if (rooms.existsByCodeIgnoreCase(code)) {
            throw new ConflictException("Room '" + code + "' already exists");
        }
        Floor floor = floors.findByCodeIgnoreCase(normaliseCode(request.floorCode()))
                .orElseThrow(() -> new BadRequestException(
                        "No such floor: '" + request.floorCode() + "'"));

        Room room = new Room(code, request.name().trim(), requireRoomType(request.roomTypeCode()), floor);
        room.setDepartment(resolveDepartment(request.departmentCode()));
        room.setCapacity(request.capacityOrDefault());
        room.setWidthFt(request.widthFt());
        room.setLengthFt(request.lengthFt());
        room.setDirections(trimToNull(request.directions()));
        room.setBookable(request.bookableOrDefault());
        room.setNotes(trimToNull(request.notes()));
        validate(room);
        rooms.save(room);

        audit.record("ROOM_CREATED", "Room", room.getId(),
                code + " " + room.getRoomType().getCode() + " on " + floor.getCode());
        return FacilityMapper.toResponse(room, 0);
    }

    @Transactional
    public FacilityDtos.RoomResponse updateRoom(UUID id, FacilityDtos.UpdateRoomRequest request) {
        Room room = rooms.findDetailById(id).orElseThrow(() -> NotFoundException.of("Room", id));
        if (request.name() != null) {
            room.setName(request.name().trim());
        }
        if (request.roomTypeCode() != null) {
            room.setRoomType(requireRoomType(request.roomTypeCode()));
        }
        if (request.floorCode() != null) {
            room.setFloor(floors.findByCodeIgnoreCase(normaliseCode(request.floorCode()))
                    .orElseThrow(() -> new BadRequestException("No such floor: '" + request.floorCode() + "'")));
        }
        if (request.departmentCode() != null) {
            room.setDepartment(resolveDepartment(request.departmentCode()));
        }
        if (request.capacity() != null) {
            room.setCapacity(request.capacity());
        }
        if (request.widthFt() != null) {
            room.setWidthFt(request.widthFt());
        }
        if (request.lengthFt() != null) {
            room.setLengthFt(request.lengthFt());
        }
        if (request.directions() != null) {
            room.setDirections(trimToNull(request.directions()));
        }
        if (request.bookable() != null) {
            room.setBookable(request.bookable());
        }
        if (request.active() != null) {
            room.setActive(request.active());
        }
        if (request.notes() != null) {
            room.setNotes(trimToNull(request.notes()));
        }
        validate(room);
        rooms.save(room);

        audit.record("ROOM_UPDATED", "Room", id, room.getCode());
        return FacilityMapper.toResponse(room,
                beds.findByRoomIdAndActiveTrueOrderByCodeAsc(id).size());
    }

    // ---- beds -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FacilityDtos.BedResponse> bedsIn(String roomCode) {
        Room room = requireRoom(roomCode);
        return beds.findByRoomIdAndActiveTrueOrderByCodeAsc(room.getId()).stream()
                .map(FacilityMapper::toResponse).toList();
    }

    /**
     * Every bed in the building, or only those in rooms of the given types.
     *
     * <p>The type filter is how admissions-service asks for "the casualty beds" without having to
     * know which room codes make up casualty — which would make the bay's layout its problem.
     */
    @Transactional(readOnly = true)
    public List<FacilityDtos.BedResponse> allBeds(List<String> roomTypeCodes) {
        List<Bed> found = roomTypeCodes == null || roomTypeCodes.isEmpty()
                ? beds.findAllActive()
                : beds.findActiveByRoomTypes(roomTypeCodes.stream()
                        .map(FacilityService::normaliseCode).toList());
        return found.stream().map(FacilityMapper::toResponse).toList();
    }

    @Transactional
    public FacilityDtos.BedResponse addBed(String roomCode, FacilityDtos.CreateBedRequest request) {
        Room room = requireRoom(roomCode);
        if (!room.isClinical()) {
            throw new BadRequestException("Beds belong in clinical rooms; " + room.getCode()
                    + " is " + room.getRoomType().getCode());
        }
        String code = normaliseCode(request.code());
        if (beds.existsByRoomIdAndCodeIgnoreCase(room.getId(), code)) {
            throw new ConflictException("Bed '" + code + "' already exists in " + room.getCode());
        }
        // Over the designed capacity is a warning worth refusing rather than logging: a bay with
        // more beds recorded than it has positions means one of them is somewhere else.
        int existing = beds.findByRoomIdAndActiveTrueOrderByCodeAsc(room.getId()).size();
        if (room.getCapacity() > 0 && existing >= room.getCapacity()) {
            throw new ConflictException(room.getCode() + " is designed for " + room.getCapacity()
                    + " bed(s) and already has " + existing
                    + ". Raise the room's capacity first if the building changed.");
        }
        Bed bed = new Bed(room, code, trimToNull(request.label()));
        beds.save(bed);
        audit.record("BED_CREATED", "Bed", bed.getId(), code + " in " + room.getCode());
        return FacilityMapper.toResponse(bed);
    }

    // ---- helpers --------------------------------------------------------------

    private Room requireRoom(String code) {
        return rooms.findDetailByCodeIgnoreCase(normaliseCode(code))
                .orElseThrow(() -> new NotFoundException("No such room: '" + code + "'"));
    }

    private RoomType requireRoomType(String code) {
        return roomTypes.findByCodeIgnoreCase(normaliseCode(code))
                .orElseThrow(() -> new BadRequestException("No such room type: '" + code
                        + "'. Add it via POST /room-types - the taxonomy is configuration."));
    }

    private Department resolveDepartment(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return departments.findByCode(normaliseCode(code))
                .orElseThrow(() -> new BadRequestException("No such department: '" + code + "'"));
    }

    /**
     * Rules the database cannot express, checked against the type's own flags.
     *
     * <p>No {@code switch} over room types here, and that is the point. This method used to name
     * EMERGENCY_BAY, EMERGENCY_ROOM, WARD and SUITE explicitly, which meant a hospital adding a
     * high-dependency unit had to come and edit it. Now it asks the type what it is, so a new type
     * configured with {@code bed_allocated = true} is governed by the same rule the day it is
     * inserted.
     *
     * <p>What is still checked here rather than by a constraint: these are relationships between a
     * room and its type, not properties of either alone, and refusing them at the point someone
     * typed the room is better than discovering it when a booking lands in a corridor.
     */
    private void validate(Room room) {
        RoomType type = room.getRoomType();
        if (!type.isClinical() && room.getCapacity() > 0) {
            throw new BadRequestException(type.getCode()
                    + " is not a clinical room type, so it cannot have a bed capacity");
        }
        if (room.isBookable() && !type.isSchedulable()) {
            String because = type.isBedAllocated()
                    ? " space is allocated by bed rather than booked on a calendar"
                    : (type.isClinical() ? " rooms do not carry appointments"
                                         : " is not clinical space, and appointments only go into"
                                           + " clinical space");
            throw new BadRequestException(type.getCode() + because
                    + ", so this room cannot be marked bookable");
        }
    }

    /** Bed counts for a page of rooms in one query rather than one per row. */
    private Map<UUID, Integer> bedCountsFor(List<Room> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        for (Bed bed : beds.findAllActive()) {
            counts.merge(bed.getRoom().getId(), 1, Integer::sum);
        }
        return counts;
    }

    /** Codes are upper-case identifiers. Locale.ROOT: the default locale mangles "I" in Turkish. */
    private static String normaliseCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
