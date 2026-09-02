package com.hms.admissions.service;

import com.hms.admissions.client.BedDirectoryClient;
import com.hms.admissions.domain.AdmissionEnums;
import com.hms.admissions.domain.BedOccupancy;
import com.hms.admissions.domain.CasualtyAttendance;
import com.hms.admissions.repo.CasualtyAttendanceRepository;
import com.hms.admissions.web.dto.AdmissionDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casualty: arrival, triage, a bed, and one of four ways out.
 *
 * <p>The one thing in this class that is not bookkeeping is the ordering of {@link #board()}:
 * acuity first, arrival second. A casualty queue served in the order people arrived kills the
 * person who arrived last and is the sickest, and that is not a hypothetical — it is why triage
 * exists as a discipline. The sort lives in the query rather than in a comparator here, so no
 * caller can render the list in arrival order by accident.
 */
@Service
public class CasualtyService {

    private final CasualtyAttendanceRepository attendances;
    private final BedAllocator allocator;
    private final AuditService audit;
    private final EventPublisher events;

    public CasualtyService(CasualtyAttendanceRepository attendances, BedAllocator allocator,
                           AuditService audit, EventPublisher events) {
        this.attendances = attendances;
        this.allocator = allocator;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public AdmissionDtos.AttendanceResponse arrive(AdmissionDtos.ArrivalRequest request) {
        CasualtyAttendance attendance = new CasualtyAttendance(request.patientId(),
                request.patientMrn().trim(), request.triageAcuity().shortValue(),
                request.presentingComplaint().trim(), CurrentUser.usernameOrSystem());
        CasualtyAttendance saved = attendances.save(attendance);

        // The acuity is in the audit detail and the complaint is not. `AuditService`'s contract
        // forbids clinical free text in `detail`, and a presenting complaint is exactly that —
        // while an acuity is a triage decision, which is the thing a later review asks about.
        audit.record("CASUALTY_ARRIVAL", "CasualtyAttendance", saved.getId(),
                "acuity %d".formatted(saved.getTriageAcuity()));
        publish("casualty.arrived", saved, Map.of("acuity", saved.getTriageAcuity()));
        return toResponse(saved);
    }

    @Transactional
    public AdmissionDtos.AttendanceResponse retriage(UUID id, AdmissionDtos.RetriageRequest request) {
        CasualtyAttendance attendance = require(id);
        short was = attendance.getTriageAcuity();
        attendance.retriage(request.triageAcuity().shortValue());
        audit.record("CASUALTY_RETRIAGED", "CasualtyAttendance", id,
                "acuity %d -> %d".formatted(was, attendance.getTriageAcuity()));
        publish("casualty.retriaged", attendance,
                Map.of("from", was, "to", attendance.getTriageAcuity()));
        return toResponse(attendance);
    }

    @Transactional
    public AdmissionDtos.AttendanceResponse placeInBed(UUID id, AdmissionDtos.PlaceInBedRequest request,
                                                       String bearerToken) {
        CasualtyAttendance attendance = require(id);
        BedOccupancy claimed = allocator.claim(request.bedId(), BedDirectoryClient.CASUALTY_TYPES,
                AdmissionEnums.OccupantType.CASUALTY, id, bearerToken);
        attendance.placeIn(claimed.getBedId(), claimed.getBedCode(), claimed.getRoomCode());

        audit.record("CASUALTY_BED_ALLOCATED", "CasualtyAttendance", id,
                "%s in %s".formatted(claimed.getBedCode(), claimed.getRoomCode()));
        publish("casualty.placed", attendance, Map.of("bedCode", claimed.getBedCode()));
        return toResponse(attendance);
    }

    @Transactional
    public AdmissionDtos.AttendanceResponse discharge(UUID id) {
        CasualtyAttendance attendance = require(id);
        attendance.discharge();
        allocator.release(AdmissionEnums.OccupantType.CASUALTY, id);
        audit.record("CASUALTY_DISCHARGED", "CasualtyAttendance", id,
                "after %d minutes".formatted(minutesSince(attendance.getArrivedAt())));
        publish("casualty.discharged", attendance, Map.of());
        return toResponse(attendance);
    }

    /**
     * The patient gave up and left.
     *
     * <p>Its own outcome, because it is a standard emergency-department quality metric: a
     * department where this rises is a department people are giving up on, and recording it as a
     * discharge would delete the only signal that says so.
     */
    @Transactional
    public AdmissionDtos.AttendanceResponse leftWithoutBeingSeen(UUID id) {
        CasualtyAttendance attendance = require(id);
        attendance.leftWithoutBeingSeen();
        allocator.release(AdmissionEnums.OccupantType.CASUALTY, id);
        audit.record("CASUALTY_LEFT_WITHOUT_BEING_SEEN", "CasualtyAttendance", id,
                "after %d minutes at acuity %d".formatted(minutesSince(attendance.getArrivedAt()),
                        attendance.getTriageAcuity()));
        publish("casualty.left", attendance, Map.of("acuity", attendance.getTriageAcuity()));
        return toResponse(attendance);
    }

    /** The board: everybody still in the department, sickest first, ties by longest waiting. */
    @Transactional(readOnly = true)
    public List<AdmissionDtos.AttendanceResponse> board() {
        return attendances.board(List.of(AdmissionEnums.AttendanceStatus.WAITING,
                        AdmissionEnums.AttendanceStatus.IN_BED)).stream()
                .map(CasualtyService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdmissionDtos.AttendanceResponse> forPatient(UUID patientId) {
        return attendances.findByPatientIdOrderByArrivedAtDesc(patientId).stream()
                .map(CasualtyService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CasualtyAttendance require(UUID id) {
        return attendances.findById(id)
                .orElseThrow(() -> NotFoundException.of("CasualtyAttendance", id));
    }

    /** Closes an attendance because it became an admission. Called by {@link AdmissionService}. */
    @Transactional
    public void markAdmitted(CasualtyAttendance attendance, UUID admissionId) {
        attendance.admitted(admissionId);
        allocator.release(AdmissionEnums.OccupantType.CASUALTY, attendance.getId());
        audit.record("CASUALTY_ADMITTED", "CasualtyAttendance", attendance.getId(),
                "admission %s after %d minutes".formatted(admissionId,
                        minutesSince(attendance.getArrivedAt())));
    }

    static AdmissionDtos.AttendanceResponse toResponse(CasualtyAttendance attendance) {
        return new AdmissionDtos.AttendanceResponse(attendance.getId(), attendance.getPatientId(),
                attendance.getPatientMrn(), attendance.getArrivedAt(), attendance.getTriageAcuity(),
                attendance.getPresentingComplaint(), attendance.getBedId(), attendance.getBedCode(),
                attendance.getRoomCode(), attendance.getStatus(), attendance.getAdmissionId(),
                attendance.getClosedAt(), attendance.getTriagedBy(),
                // Computed rather than stored, so it is right whenever the board is read. A
                // stored figure would be right once and wrong for the rest of the shift, which on
                // a board that exists to show who has been waiting is worse than absent.
                minutesSince(attendance.getArrivedAt(), attendance.getClosedAt()));
    }

    private static long minutesSince(Instant from) {
        return minutesSince(from, null);
    }

    private static long minutesSince(Instant from, Instant until) {
        return Duration.between(from, until == null ? Instant.now() : until).toMinutes();
    }

    private void publish(String type, CasualtyAttendance attendance, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("patientId", attendance.getPatientId().toString());
        payload.put("mrn", attendance.getPatientMrn());
        payload.put("status", attendance.getStatus().name());
        events.publish(Topics.ADMISSION, DomainEvent.of(type, "CasualtyAttendance",
                attendance.getId(), CurrentUser.idOrSystem().toString(), CorrelationId.current(),
                payload));
    }
}
