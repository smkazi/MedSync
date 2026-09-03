package com.hms.imaging.service;

import com.hms.common.audit.AuditService;
import com.hms.common.careteam.CareRelationshipClient;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.imaging.domain.ImagingEnums;
import com.hms.imaging.domain.ImagingOrder;
import com.hms.imaging.domain.ImagingProcedure;
import com.hms.imaging.repo.OrderRepository;
import com.hms.imaging.repo.ProcedureRepository;
import com.hms.imaging.web.dto.ImagingDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ordering an examination, scheduling it, and the worklists the department works from. */
@Service
public class ImagingOrderService {

    private final OrderRepository orders;
    private final ProcedureRepository procedures;
    private final StudyReadService studies;
    private final CareRelationshipClient careRelationships;
    private final EventPublisher events;
    private final AuditService audit;
    private final String accessionPrefix;

    public ImagingOrderService(OrderRepository orders,
                               ProcedureRepository procedures,
                               StudyReadService studies,
                               CareRelationshipClient careRelationships,
                               EventPublisher events,
                               AuditService audit,
                               @Value("${hms.imaging.accession-prefix:IMG}") String accessionPrefix) {
        this.orders = orders;
        this.procedures = procedures;
        this.studies = studies;
        this.careRelationships = careRelationships;
        this.events = events;
        this.audit = audit;
        this.accessionPrefix = accessionPrefix;
    }

    /**
     * Raises an order and issues its accession number.
     *
     * <p>The number is issued now rather than when the scan happens, because the worklist a modality
     * reads needs it beforehand: it is what the scanner writes into every image, and it is the only
     * link back to this order that survives the trip out and back.
     *
     * <p>The modality, body part and contrast come from the catalogue rather than from the request.
     * A requester chooses an examination, not a machine — and letting the two disagree is how a
     * chest X-ray ends up on the CT worklist.
     */
    @Transactional
    public ImagingDtos.OrderResponse create(ImagingDtos.CreateOrderRequest request) {
        ImagingProcedure procedure = procedures.findById(request.procedureCode().trim())
                .orElseThrow(() -> new BadRequestException(
                        "Unknown examination '%s'".formatted(request.procedureCode())));
        if (!procedure.isActive()) {
            throw new BadRequestException(
                    "'%s' is no longer orderable".formatted(procedure.getName()));
        }

        ImagingOrder order = new ImagingOrder(request.patientId(), request.patientMrn().trim(),
                procedure.getModality(), procedure.getCode(), procedure.getName(),
                request.clinicalQuestion().trim(), CurrentUser.usernameOrSystem(), nextAccession());
        order.setBodyPart(procedure.getBodyPart());
        order.setContrast(procedure.isContrast());
        order.setPriority(request.priorityOrDefault());
        order.setEncounterId(request.encounterId());
        if (request.scheduledFor() != null) {
            order.schedule(request.scheduledFor());
        }
        orders.save(order);

        audit.record("IMAGING_ORDERED", "ImagingOrder", order.getId(),
                "%s %s for %s".formatted(order.getAccessionNo(), order.getProcedureCode(),
                        order.getPatientMrn()));
        publish("imaging.order.created", order, Map.of("procedureCode", order.getProcedureCode()));
        return toResponse(order);
    }

    @Transactional
    public ImagingDtos.OrderResponse schedule(UUID id, Instant when) {
        ImagingOrder order = require(id);
        order.schedule(when);
        audit.record("IMAGING_SCHEDULED", "ImagingOrder", id,
                "%s for %s".formatted(order.getAccessionNo(), when));
        publish("imaging.order.scheduled", order, Map.of());
        return toResponse(order);
    }

    @Transactional
    public ImagingDtos.OrderResponse cancel(UUID id, String reason) {
        ImagingOrder order = require(id);
        order.cancel(reason.trim());
        audit.record("IMAGING_CANCELLED", "ImagingOrder", id,
                "%s: %s".formatted(order.getAccessionNo(), reason.trim()));
        publish("imaging.order.cancelled", order, Map.of());
        return toResponse(order);
    }

    /**
     * One order with its studies and their reports.
     *
     * <p>Narrowed: the care-relationship check runs before anything is returned, exactly as it does
     * for a laboratory order. The refusal names what it is rather than pretending the order is
     * missing — the caller is a clinician who can already list patients, so there is nothing here to
     * enumerate, and telling them how to proceed is worth more than a 404.
     */
    @Transactional(readOnly = true)
    public ImagingDtos.OrderResponse read(UUID id) {
        ImagingOrder order = require(id);
        careRelationships.requirePatientAccess(order.getPatientId());
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<ImagingDtos.OrderResponse> forPatient(UUID patientId) {
        careRelationships.requirePatientAccess(patientId);
        return orders.findByPatientIdOrderByOrderedAtDesc(patientId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * This encounter's imaging, for the chart card.
     *
     * <p>Not narrowed again here, because reaching an encounter is already narrowed by the service
     * that owns it: a caller holding an encounter id has been through {@code CareTeamGuard}.
     * Checking a second time would be another network call per chart open to answer a question that
     * has been answered.
     */
    @Transactional(readOnly = true)
    public List<ImagingDtos.OrderResponse> forEncounter(UUID encounterId) {
        return orders.findByEncounterIdOrderByOrderedAtDesc(encounterId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * The modality worklist.
     *
     * <p>Not narrowed, deliberately: a radiographer is a service line, like the laboratory and the
     * pharmacy, and the narrowing applies to clinicians reading charts rather than to the department
     * doing the work. A worklist a radiographer could only half see is a department that cannot run.
     *
     * <p>It is also the narrowest shape in this file — accession, MRN, sex, date of birth, what to
     * do — because a worklist is read on a screen beside a scanner in a room patients walk through.
     * No clinical question, no findings, no history.
     */
    @Transactional(readOnly = true)
    public List<ImagingDtos.WorklistEntry> worklist(String modality) {
        String filter = modality == null || modality.isBlank() ? "%" : modality.trim();
        return orders.worklist(filter).stream().map(ImagingOrderService::toWorklistEntry).toList();
    }

    /** Acquired and unread: the radiologist's queue, oldest first — nobody's images should wait. */
    @Transactional(readOnly = true)
    public List<ImagingDtos.WorklistEntry> reportingQueue() {
        return orders.findByStatusOrderByOrderedAtAsc(ImagingEnums.OrderStatus.ACQUIRED).stream()
                .map(ImagingOrderService::toWorklistEntry)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ImagingDtos.ProcedureResponse> catalogue() {
        return procedures.findByActiveTrueOrderByModalityAscNameAsc().stream()
                .map(p -> new ImagingDtos.ProcedureResponse(p.getCode(), p.getName(),
                        p.getModality(), p.getBodyPart(), p.getMinutes(), p.isContrast()))
                .toList();
    }

    public ImagingOrder require(UUID id) {
        return orders.findById(id).orElseThrow(() -> NotFoundException.of("ImagingOrder", id));
    }

    /** Assembles an order with the studies filed against it. */
    public ImagingDtos.OrderResponse toResponse(ImagingOrder o) {
        return new ImagingDtos.OrderResponse(o.getId(), o.getPatientId(), o.getPatientMrn(),
                o.getEncounterId(), o.getModality(), o.getBodyPart(), o.getProcedureCode(),
                o.getProcedureName(), o.getClinicalQuestion(), o.isContrast(), o.getPriority(),
                o.getStatus(), o.getOrderedBy(), o.getOrderedAt(), o.getAccessionNo(),
                o.getScheduledFor(), o.getCancelledReason(), studies.forOrder(o.getId()));
    }

    private String nextAccession() {
        long sequence = orders.nextAccessionSequence();
        return "%s%d-%06d".formatted(accessionPrefix, LocalDate.now().getYear(), sequence);
    }

    private static ImagingDtos.WorklistEntry toWorklistEntry(ImagingOrder o) {
        return new ImagingDtos.WorklistEntry(o.getId(), o.getAccessionNo(), o.getPatientMrn(),
                o.getPatientSex(), o.getPatientBirthDate(), o.getModality(), o.getProcedureCode(),
                o.getProcedureName(), o.isContrast(), o.getPriority(), o.getStatus(),
                o.getScheduledFor());
    }

    private void publish(String type, ImagingOrder order, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("patientId", order.getPatientId().toString());
        payload.put("mrn", order.getPatientMrn());
        payload.put("accessionNo", order.getAccessionNo());
        payload.put("status", order.getStatus().name());
        events.publish(Topics.IMAGING, DomainEvent.of(type, "ImagingOrder", order.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }
}
