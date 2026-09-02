package com.hms.admissions.web;

import com.hms.admissions.service.AdmissionService;
import com.hms.admissions.service.BedBoardService;
import com.hms.admissions.service.CasualtyService;
import com.hms.admissions.web.dto.AdmissionDtos;
import com.hms.common.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
 * Casualty and in-patient care.
 *
 * <p>Everything here is {@code BED_MANAGE} — admin, doctor, nurse — and deliberately not
 * {@code CLINICAL_READ}. The front desk books and checks in; a list of who is in casualty, with
 * what complaint and how sick they are, is a chart in table form and is not their business.
 *
 * <p>The bearer token is forwarded to the facility directory, so a bed lookup applies the
 * clinician's own authority rather than a broader one. That is why several methods take the
 * Authorization header: admitting a patient is something a person does, unlike the event-driven
 * work in notification-service which needs an identity of its own.
 */
@RestController
public class AdmissionsController {

    private final CasualtyService casualty;
    private final AdmissionService admissions;
    private final BedBoardService board;

    public AdmissionsController(CasualtyService casualty, AdmissionService admissions,
                                BedBoardService board) {
        this.casualty = casualty;
        this.admissions = admissions;
        this.board = board;
    }

    // ---- casualty -------------------------------------------------------------

    /**
     * The board: everybody still in the department, <strong>sickest first</strong>.
     *
     * <p>The ordering is the module's whole clinical point and it comes from the query, not from
     * anything a caller chooses — there is no sort parameter, because a casualty queue served in
     * arrival order kills the person who arrived last and is the sickest.
     */
    @GetMapping("/casualty")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.AttendanceResponse> board() {
        return casualty.board();
    }

    @PostMapping("/casualty")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AttendanceResponse arrive(
            @Valid @RequestBody AdmissionDtos.ArrivalRequest request) {
        return casualty.arrive(request);
    }

    @PatchMapping("/casualty/{id}/triage")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AttendanceResponse retriage(
            @PathVariable UUID id, @Valid @RequestBody AdmissionDtos.RetriageRequest request) {
        return casualty.retriage(id, request);
    }

    @PostMapping("/casualty/{id}/bed")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AttendanceResponse placeInBed(
            @PathVariable UUID id, @Valid @RequestBody AdmissionDtos.PlaceInBedRequest request,
            HttpServletRequest httpRequest) {
        return casualty.placeInBed(id, request, bearerToken(httpRequest));
    }

    @PostMapping("/casualty/{id}/discharge")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AttendanceResponse dischargeFromCasualty(@PathVariable UUID id) {
        return casualty.discharge(id);
    }

    @PostMapping("/casualty/{id}/left")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AttendanceResponse leftWithoutBeingSeen(@PathVariable UUID id) {
        return casualty.leftWithoutBeingSeen(id);
    }

    @GetMapping("/casualty/patients/{patientId}")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.AttendanceResponse> attendancesFor(@PathVariable UUID patientId) {
        return casualty.forPatient(patientId);
    }

    // ---- in-patient -----------------------------------------------------------

    /** The census: who is currently admitted, by room and bed. */
    @GetMapping("/admissions")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.AdmissionResponse> census(
            @RequestParam(required = false) String room) {
        return admissions.census(room);
    }

    @PostMapping("/admissions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AdmissionResponse admit(
            @Valid @RequestBody AdmissionDtos.AdmitRequest request,
            HttpServletRequest httpRequest) {
        return admissions.admit(request, bearerToken(httpRequest));
    }

    @GetMapping("/admissions/{id}")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AdmissionResponse get(@PathVariable UUID id) {
        return admissions.get(id);
    }

    @PostMapping("/admissions/{id}/transfer")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AdmissionResponse transfer(
            @PathVariable UUID id, @Valid @RequestBody AdmissionDtos.TransferRequest request,
            HttpServletRequest httpRequest) {
        return admissions.transfer(id, request, bearerToken(httpRequest));
    }

    @PostMapping("/admissions/{id}/discharge")
    @PreAuthorize(Roles.BED_MANAGE)
    public AdmissionDtos.AdmissionResponse discharge(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) AdmissionDtos.DischargeRequest request) {
        return admissions.discharge(id,
                request == null ? new AdmissionDtos.DischargeRequest(null) : request);
    }

    @GetMapping("/admissions/patients/{patientId}")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.AdmissionResponse> admissionsFor(@PathVariable UUID patientId) {
        return admissions.forPatient(patientId);
    }

    // ---- the bed map ----------------------------------------------------------

    /**
     * Every bed and whether anybody is in it.
     *
     * <p>Composed from the facility directory and this service's occupancy. patient-service
     * deliberately keeps no occupancy flag on a bed — a flag written by one service and maintained
     * by another goes stale — so the join happens at read time and neither has to trust the
     * other's copy.
     *
     * <p>Under {@code /casualty} and {@code /admissions} rather than {@code /beds}, which is the
     * second design of this endpoint. {@code /beds/casualty} reads better and was wrong: the
     * gateway already routes {@code /beds/**} to patient-service, which owns beds as master data,
     * and the first matching predicate wins — so the occupancy endpoints answered 405 from the
     * wrong service. Ordering the routes would have fixed the symptom and left an invisible
     * dependency, where anybody adding a bed sub-resource to patient-service breaks this one.
     * Living inside the owning module's own prefix means there is no shared namespace to collide
     * in at all.
     */
    @GetMapping("/casualty/beds")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.BedStateResponse> casualtyBeds(HttpServletRequest httpRequest) {
        return board.casualtyBeds(bearerToken(httpRequest));
    }

    @GetMapping("/admissions/beds")
    @PreAuthorize(Roles.BED_MANAGE)
    public List<AdmissionDtos.BedStateResponse> inpatientBeds(HttpServletRequest httpRequest) {
        return board.inpatientBeds(bearerToken(httpRequest));
    }

    /**
     * The caller's own token, for forwarding to the facility directory.
     *
     * <p>Read off the request rather than bound with {@code @RequestHeader}, which is the pattern
     * {@code AppointmentController} already uses, and it matters for a reason worth writing down:
     * a required header parameter makes the method untestable with Spring Security's {@code jwt()}
     * post-processor, because supplying a real {@code Authorization} header sends the resource
     * server off to validate a token that is not a token and the request comes back 401. Returning
     * null when it is absent is safe here — the filter chain has already established there is a
     * valid token, and the fail-closed directory client refuses rather than guessing if one
     * somehow is not.
     */
    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length()).trim();
    }
}
