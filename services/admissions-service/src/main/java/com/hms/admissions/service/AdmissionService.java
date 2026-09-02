package com.hms.admissions.service;

import com.hms.admissions.client.BedDirectoryClient;
import com.hms.admissions.domain.Admission;
import com.hms.admissions.domain.AdmissionEnums;
import com.hms.admissions.domain.BedOccupancy;
import com.hms.admissions.domain.BedTransfer;
import com.hms.admissions.domain.CasualtyAttendance;
import com.hms.admissions.repo.AdmissionRepository;
import com.hms.admissions.repo.BedTransferRepository;
import com.hms.admissions.web.dto.AdmissionDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
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

/** In-patient stays: admission, transfer, discharge, and the census. */
@Service
public class AdmissionService {

    private final AdmissionRepository admissions;
    private final BedTransferRepository transfers;
    private final BedAllocator allocator;
    private final CasualtyService casualty;
    private final AuditService audit;
    private final EventPublisher events;

    public AdmissionService(AdmissionRepository admissions, BedTransferRepository transfers,
                            BedAllocator allocator, CasualtyService casualty, AuditService audit,
                            EventPublisher events) {
        this.admissions = admissions;
        this.transfers = transfers;
        this.allocator = allocator;
        this.casualty = casualty;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Admits a patient to a ward bed.
     *
     * <p>When it comes from casualty, the attendance is closed and its bay released in the same
     * transaction. That matters: the two together are one movement, and a failure between them
     * would leave a patient occupying a resus bay they are no longer in — which is a bay the
     * department believes it does not have.
     */
    @Transactional
    public AdmissionDtos.AdmissionResponse admit(AdmissionDtos.AdmitRequest request,
                                                 String bearerToken) {
        CasualtyAttendance attendance = request.attendanceId() == null
                ? null
                : casualty.require(request.attendanceId());
        if (attendance != null && !attendance.isOpen()) {
            throw new BadRequestException("That casualty attendance is already "
                    + attendance.getStatus() + " and cannot be admitted again");
        }
        if (attendance != null && !attendance.getPatientId().equals(request.patientId())) {
            // Cheap, and it catches the copy-paste that admits one patient against another's
            // attendance — which would join two people's records together.
            throw new BadRequestException(
                    "That casualty attendance belongs to a different patient");
        }

        // Claimed before the row exists, so a taken bed refuses the admission rather than leaving
        // an admitted patient with nowhere to be.
        Admission admission = new Admission(request.patientId(), request.patientMrn().trim(),
                request.attendanceId(), request.bedId(), "pending", "pending",
                request.admittingClinicianId(), request.source());
        Admission saved = admissions.save(admission);

        BedOccupancy claimed = allocator.claim(request.bedId(), BedDirectoryClient.INPATIENT_TYPES,
                AdmissionEnums.OccupantType.ADMISSION, saved.getId(), bearerToken);
        saved.moveTo(claimed.getBedId(), claimed.getBedCode(), claimed.getRoomCode());
        if (request.expectedDischarge() != null) {
            saved.expectDischargeOn(request.expectedDischarge());
        }

        if (attendance != null) {
            casualty.markAdmitted(attendance, saved.getId());
        }

        audit.record("PATIENT_ADMITTED", "Admission", saved.getId(),
                "%s in %s from %s".formatted(claimed.getBedCode(), claimed.getRoomCode(),
                        saved.getSource()));
        publish("admission.admitted", saved, Map.of("bedCode", claimed.getBedCode(),
                "source", saved.getSource().name()));
        return toResponse(saved, List.of());
    }

    /**
     * Moves a patient to another bed.
     *
     * <p>Two occupancy writes in one transaction, so there is no window in which the patient
     * appears in two beds — see {@link BedAllocator#move}, which explains why the release comes
     * first and what that costs.
     */
    @Transactional
    public AdmissionDtos.AdmissionResponse transfer(UUID id, AdmissionDtos.TransferRequest request,
                                                    String bearerToken) {
        Admission admission = require(id);
        if (admission.getBedId().equals(request.toBedId())) {
            throw new BadRequestException("This patient is already in that bed");
        }
        UUID fromBedId = admission.getBedId();
        String fromBedCode = admission.getBedCode();

        BedOccupancy claimed = allocator.move(AdmissionEnums.OccupantType.ADMISSION, id,
                request.toBedId(), BedDirectoryClient.INPATIENT_TYPES, bearerToken);
        admission.moveTo(claimed.getBedId(), claimed.getBedCode(), claimed.getRoomCode());

        transfers.save(new BedTransfer(id, fromBedId, fromBedCode, claimed.getBedId(),
                claimed.getBedCode(), CurrentUser.usernameOrSystem(), request.reason().trim()));

        audit.record("PATIENT_TRANSFERRED", "Admission", id,
                "%s -> %s".formatted(fromBedCode, claimed.getBedCode()));
        publish("admission.transferred", admission,
                Map.of("fromBedCode", fromBedCode, "toBedCode", claimed.getBedCode()));
        return toResponse(admission, transfersFor(id));
    }

    @Transactional
    public AdmissionDtos.AdmissionResponse discharge(UUID id, AdmissionDtos.DischargeRequest request) {
        Admission admission = require(id);
        admission.discharge(request.summary() == null ? null : request.summary().trim());
        allocator.release(AdmissionEnums.OccupantType.ADMISSION, id);

        long days = lengthOfStayDays(admission);
        audit.record("PATIENT_DISCHARGED", "Admission", id,
                "%s free after %d day(s)".formatted(admission.getBedCode(), days));
        // The bed-day count travels on the event, which is what billing prices a stay from. It is
        // published here rather than computed there because this service owns the admission's
        // clock and nothing else should be re-deriving it.
        publish("admission.discharged", admission,
                Map.of("bedCode", admission.getBedCode(), "bedDays", days));
        return toResponse(admission, transfersFor(id));
    }

    @Transactional(readOnly = true)
    public List<AdmissionDtos.AdmissionResponse> census(String roomCode) {
        String room = roomCode == null || roomCode.isBlank()
                ? null
                : roomCode.trim().toUpperCase(java.util.Locale.ROOT);
        return admissions.census(AdmissionEnums.AdmissionStatus.ADMITTED, room).stream()
                .map(admission -> toResponse(admission, List.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdmissionDtos.AdmissionResponse get(UUID id) {
        return toResponse(require(id), transfersFor(id));
    }

    @Transactional(readOnly = true)
    public List<AdmissionDtos.AdmissionResponse> forPatient(UUID patientId) {
        return admissions.findByPatientIdOrderByAdmittedAtDesc(patientId).stream()
                .map(admission -> toResponse(admission, List.of()))
                .toList();
    }

    private Admission require(UUID id) {
        return admissions.findById(id).orElseThrow(() -> NotFoundException.of("Admission", id));
    }

    private List<AdmissionDtos.TransferResponse> transfersFor(UUID id) {
        return transfers.findByAdmissionIdOrderByMovedAtDesc(id).stream()
                .map(transfer -> new AdmissionDtos.TransferResponse(transfer.getId(),
                        transfer.getFromBedCode(), transfer.getToBedCode(), transfer.getMovedAt(),
                        transfer.getMovedBy(), transfer.getReason()))
                .toList();
    }

    private static long lengthOfStayDays(Admission admission) {
        Instant until = admission.getDischargedAt() == null ? Instant.now()
                : admission.getDischargedAt();
        // At least one: a patient admitted and discharged the same day has occupied a bed for a
        // day as far as a ward and a bill are concerned, and zero bed-days for a real stay is a
        // number nobody would believe.
        return Math.max(1, Duration.between(admission.getAdmittedAt(), until).toDays());
    }

    static AdmissionDtos.AdmissionResponse toResponse(Admission admission,
                                                      List<AdmissionDtos.TransferResponse> transfers) {
        return new AdmissionDtos.AdmissionResponse(admission.getId(), admission.getPatientId(),
                admission.getPatientMrn(), admission.getAttendanceId(), admission.getBedId(),
                admission.getBedCode(), admission.getRoomCode(),
                admission.getAdmittingClinicianId(), admission.getSource(),
                admission.getAdmittedAt(), admission.getExpectedDischarge(),
                admission.getDischargedAt(), admission.getDischargeSummary(),
                admission.getStatus(), lengthOfStayDays(admission), transfers);
    }

    private void publish(String type, Admission admission, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("patientId", admission.getPatientId().toString());
        payload.put("mrn", admission.getPatientMrn());
        payload.put("status", admission.getStatus().name());
        events.publish(Topics.ADMISSION, DomainEvent.of(type, "Admission", admission.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }
}
