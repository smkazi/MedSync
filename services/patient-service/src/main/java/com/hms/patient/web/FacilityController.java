package com.hms.patient.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.patient.service.FacilityService;
import com.hms.patient.web.dto.FacilityDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The facility directory.
 *
 * <p>Reads are {@link Roles#CLINICAL_READ}: knowing where the pharmacy is, or which corridor the
 * paediatric clinic is down, is not privileged information and every role needs it to do its job.
 * Writes are {@link Roles#ADMIN_ONLY} — a room changes when the building changes, and a clinician
 * quietly marking a consulting room unbookable would silently empty a clinic's calendar.
 */
@RestController
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    // ---- room types (configuration) -------------------------------------------

    /**
     * The room-type vocabulary.
     *
     * <p>Readable by everyone, because the UI needs it to render a pick-list and a filter. Writable
     * by an administrator, because adding a type is how a hospital with a dialysis unit or a
     * physiotherapy room extends the platform without anyone touching the code.
     */
    @GetMapping("/room-types")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.RoomTypeResponse> roomTypes(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.allRoomTypes(includeInactive);
    }

    @PostMapping("/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.RoomTypeResponse createRoomType(
            @Valid @RequestBody FacilityDtos.CreateRoomTypeRequest request) {
        return service.createRoomType(request);
    }

    @PatchMapping("/room-types/{code}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.RoomTypeResponse updateRoomType(
            @PathVariable String code,
            @Valid @RequestBody FacilityDtos.UpdateRoomTypeRequest request) {
        return service.updateRoomType(code, request);
    }

    // ---- floors ---------------------------------------------------------------

    @GetMapping("/floors")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.FloorResponse> floors(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.allFloors(includeInactive);
    }

    @PostMapping("/floors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.FloorResponse createFloor(@Valid @RequestBody FacilityDtos.CreateFloorRequest request) {
        return service.createFloor(request);
    }

    @PatchMapping("/floors/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.FloorResponse updateFloor(@PathVariable UUID id,
                                                  @Valid @RequestBody FacilityDtos.UpdateFloorRequest request) {
        return service.updateFloor(id, request);
    }

    // ---- rooms ----------------------------------------------------------------

    @GetMapping("/rooms")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PageResponse<FacilityDtos.RoomResponse> rooms(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String floor,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(service.search(q, department, type, floor, includeInactive,
                PageRequest.of(page, Math.min(size, 200), Sort.by("code"))));
    }

    /** The whole building, grouped by floor. What the directory page and the bed map render from. */
    @GetMapping("/rooms/directory")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.FloorWithRooms> directory() {
        return service.directory();
    }

    /**
     * Rooms an appointment may be booked into, optionally narrowed to one clinic.
     *
     * <p>Ahead of {@code /rooms/{code}} in this file and in the mapping order it produces, because
     * "bookable" would otherwise be read as a room code.
     */
    @GetMapping("/rooms/bookable")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.RoomSummary> bookable(@RequestParam(required = false) String department) {
        return service.bookableRooms(department);
    }

    @GetMapping("/rooms/{code}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public FacilityDtos.RoomResponse room(@PathVariable String code) {
        return service.byCode(code);
    }

    /**
     * The narrow location shape scheduling-service validates a booking against.
     *
     * <p>Its own endpoint rather than the full room read, so the cross-service contract stays
     * explicit: scheduling gets an id, a name, a floor and a directions string, and does not
     * acquire a dependency on capacity or dimensions.
     *
     * <p>{@link Roles#WAYFINDING} rather than {@code CLINICAL_READ}, which is a deliberate
     * widening and the only one in this controller: a patient reading their own appointment in the
     * portal has to be told where to go, and this narrow shape is a sign on a wall rather than a
     * fact about the building's use. Every other room endpoint here is unchanged.
     */
    @GetMapping("/rooms/{code}/location")
    @PreAuthorize(Roles.WAYFINDING)
    public FacilityDtos.RoomLocation location(@PathVariable String code) {
        return service.locationOf(code);
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.RoomResponse createRoom(@Valid @RequestBody FacilityDtos.CreateRoomRequest request) {
        return service.createRoom(request);
    }

    @PatchMapping("/rooms/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.RoomResponse updateRoom(@PathVariable UUID id,
                                                @Valid @RequestBody FacilityDtos.UpdateRoomRequest request) {
        return service.updateRoom(id, request);
    }

    // ---- beds -----------------------------------------------------------------

    /**
     * Every active bed, optionally only those in rooms of the given types.
     *
     * <p>The type filter is how admissions-service asks for "the casualty beds" without needing to
     * know which room codes make up casualty.
     */
    @GetMapping("/beds")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.BedResponse> beds(@RequestParam(required = false) List<String> type,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.allBeds(type, includeInactive);
    }

    @GetMapping("/rooms/{code}/beds")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<FacilityDtos.BedResponse> bedsInRoom(@PathVariable String code) {
        return service.bedsIn(code);
    }

    @PatchMapping("/beds/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.BedResponse updateBed(@PathVariable UUID id,
            @Valid @RequestBody FacilityDtos.UpdateBedRequest request) {
        return service.updateBed(id, request);
    }

    @PostMapping("/rooms/{code}/beds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN_ONLY)
    public FacilityDtos.BedResponse addBed(@PathVariable String code,
                                           @Valid @RequestBody FacilityDtos.CreateBedRequest request) {
        return service.addBed(code, request);
    }
}
