package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabOrderItem;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.LabTestCatalogEntry;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.web.dto.LabDtos;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hms.laboratory.repo.HistogramRepository;
import com.hms.laboratory.repo.LabOrderRepository;
import com.hms.laboratory.repo.LabResultRepository;
import com.hms.laboratory.repo.LabTestCatalogRepository;
import com.hms.laboratory.repo.SpecimenRepository;

/** Ordering laboratory work, collecting specimens, and reading a completed order. */
@Service
public class LabOrderService {

    private final LabOrderRepository orders;
    private final LabResultRepository results;
    private final SpecimenRepository specimens;
    private final LabTestCatalogRepository catalog;
    private final HistogramRepository histograms;
    private final AccessionGenerator accessions;
    private final ReferenceRangeService ranges;
    private final LabMapper mapper;
    private final EventPublisher events;
    private final AuditService audit;
    private final InterpretationService interpretations;

    public LabOrderService(LabOrderRepository orders,
                           LabResultRepository results,
                           SpecimenRepository specimens,
                           LabTestCatalogRepository catalog,
                           HistogramRepository histograms,
                           AccessionGenerator accessions, ReferenceRangeService ranges, LabMapper mapper,
                           EventPublisher events, AuditService audit,
                           InterpretationService interpretations) {
        this.orders = orders;
        this.results = results;
        this.specimens = specimens;
        this.catalog = catalog;
        this.histograms = histograms;
        this.accessions = accessions;
        this.ranges = ranges;
        this.mapper = mapper;
        this.events = events;
        this.audit = audit;
        this.interpretations = interpretations;
    }

    @Transactional
    public LabDtos.OrderResponse create(LabDtos.CreateOrderRequest request) {
        Set<String> codes = new LinkedHashSet<>(request.testCodes().stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .toList());
        if (codes.isEmpty()) {
            throw new BadRequestException("At least one test code is required");
        }

        LabOrder order = new LabOrder(request.patientId(), request.patientMrn().trim(),
                ReferenceRangeService.normaliseSex(request.patientSex()),
                CurrentUser.usernameOrSystem(), request.priorityOrDefault());
        if (request.department() != null && !request.department().isBlank()) {
            order.setDepartment(request.department().trim());
        }
        order.setClinicalNotes(request.clinicalNotes());

        for (String code : codes) {
            LabTestCatalogEntry entry = catalog.findByCodeIgnoreCase(code)
                    .orElseThrow(() -> new BadRequestException("Unknown test code '" + code + "'"));
            if (!entry.isActive()) {
                throw new BadRequestException("Test '" + code + "' is no longer orderable");
            }
            order.addItem(new LabOrderItem(order, entry.getCode(), entry.getName()));
        }
        orders.save(order);

        audit.record("LAB_ORDER_CREATED", "LabOrder", order.getId(),
                "%s for %s (%s)".formatted(codes, order.getPatientMrn(), order.getPriority()));
        publish("lab.order.created", order, Map.of("tests", List.copyOf(codes),
                "priority", order.getPriority().name()));
        return toResponse(order);
    }

    /**
     * Registers the tube and issues its accession number — the identifier the analyzer will send
     * back, and therefore the link between a physical sample and this order.
     */
    @Transactional
    public LabDtos.SpecimenResponse collect(UUID orderId, LabDtos.CollectSpecimenRequest request) {
        LabOrder order = requireDetail(orderId);
        if (order.getStatus() == LabEnums.OrderStatus.CANCELLED) {
            throw new ConflictException("This order was cancelled");
        }
        String specimenType = request != null && request.specimenType() != null && !request.specimenType().isBlank()
                ? request.specimenType().trim()
                : defaultSpecimenType(order);

        Specimen specimen = new Specimen(order, accessions.next(), specimenType);
        specimen.markCollected(CurrentUser.usernameOrSystem());
        specimen.markReceived();
        order.addSpecimen(specimen);
        order.advanceTo(LabEnums.OrderStatus.COLLECTED);
        orders.save(order);

        audit.record("SPECIMEN_COLLECTED", "LabOrder", orderId, "accession " + specimen.getAccessionNo());
        publish("lab.specimen.collected", order, Map.of("accessionNo", specimen.getAccessionNo()));
        return mapper.toResponse(specimen);
    }

    @Transactional
    public void cancel(UUID orderId) {
        LabOrder order = requireDetail(orderId);
        if (results.countByOrderId(orderId) > 0) {
            throw new ConflictException("Results have already been recorded; this order cannot be cancelled");
        }
        order.cancel();
        audit.record("LAB_ORDER_CANCELLED", "LabOrder", orderId, "no results had been recorded");
        publish("lab.order.cancelled", order, Map.of());
    }

    @Transactional(readOnly = true)
    public LabDtos.OrderResponse get(UUID orderId) {
        return toResponse(requireDetail(orderId));
    }

    @Transactional(readOnly = true)
    public LabOrder requireDetail(UUID orderId) {
        return orders.findDetailById(orderId).orElseThrow(() -> NotFoundException.of("LabOrder", orderId));
    }

    /**
     * Finds the order a scanned tube belongs to.
     *
     * <p>This is the point of putting a barcode on the label. Without it a technician standing at a
     * rack of six visually identical tubes types an accession number into a search box, and the
     * failure mode of that is not a missing result - it is a result filed against the wrong patient.
     *
     * <p>404 for an unknown accession rather than an empty list: a tube whose label does not resolve
     * is an incident to investigate, not a search that found nothing.
     */
    @Transactional(readOnly = true)
    public LabDtos.OrderResponse byAccession(String accessionNo) {
        Specimen specimen = requireSpecimen(accessionNo);
        return toResponse(requireDetail(specimen.getOrder().getId()));
    }

    @Transactional(readOnly = true)
    public Specimen requireSpecimen(String accessionNo) {
        String trimmed = accessionNo == null ? "" : accessionNo.trim();
        return findSpecimen(trimmed)
                .orElseThrow(() -> new NotFoundException("No specimen with accession '" + trimmed + "'"));
    }

    /**
     * Looks a specimen up without throwing when it is absent.
     *
     * <p>Needed because a caller that must not fail - the analyzer query path, which owes a waiting
     * instrument a reply - cannot simply catch {@link NotFoundException} from
     * {@link #requireSpecimen}. Throwing a runtime exception out of a transactional method marks
     * that transaction rollback-only, so swallowing it upstream still fails at commit with an
     * {@code UnexpectedRollbackException}. An absent row is an expected outcome for that caller, so
     * it is modelled as an empty Optional rather than as an exception to be recovered from.
     */
    @Transactional(readOnly = true)
    public Optional<Specimen> findSpecimen(String accessionNo) {
        return specimens.findByAccessionNo(accessionNo == null ? "" : accessionNo.trim());
    }

    /**
     * The worklist. Defaults to orders still needing attention rather than the full history, which
     * is what a bench technician actually wants to see.
     */
    @Transactional(readOnly = true)
    public Page<LabDtos.OrderSummary> search(String mrn, List<LabEnums.OrderStatus> statuses, Pageable pageable) {
        List<LabEnums.OrderStatus> effective = statuses == null || statuses.isEmpty()
                ? List.of(LabEnums.OrderStatus.ORDERED, LabEnums.OrderStatus.COLLECTED,
                          LabEnums.OrderStatus.IN_PROGRESS, LabEnums.OrderStatus.RESULTED)
                : statuses;
        return orders.search(QueryPatterns.exactOrAny(mrn), effective, pageable)
                .map(order -> {
                    List<LabResult> found = results.findByOrderIdOrderByParameter(order.getId());
                    boolean abnormal = found.stream().anyMatch(LabResult::isAbnormal);
                    return mapper.toSummary(order, found.size(), abnormal);
                });
    }

    @Transactional(readOnly = true)
    public List<LabDtos.OrderSummary> forPatient(UUID patientId) {
        return orders.findByPatientIdOrderByOrderedAtDesc(patientId).stream()
                .map(order -> {
                    List<LabResult> found = results.findByOrderIdOrderByParameter(order.getId());
                    return mapper.toSummary(order, found.size(), found.stream().anyMatch(LabResult::isAbnormal));
                })
                .toList();
    }

    /** Builds the full order view: tests, tubes, results with their ranges, and any curves. */
    @Transactional(readOnly = true)
    public LabDtos.OrderResponse toResponse(LabOrder order) {
        List<LabResult> found = results.findByOrderIdOrderByParameter(order.getId());
        List<LabDtos.ResultResponse> resultResponses = found.stream()
                .map(result -> mapper.toResponse(result,
                        ranges.find(result.getParameter(), order.getPatientSex())
                                .map(com.hms.laboratory.domain.ReferenceRange::getDisplayName)
                                .orElse(result.getParameter())))
                .toList();

        return new LabDtos.OrderResponse(order.getId(), order.getPatientId(), order.getPatientMrn(),
                order.getPatientSex(), order.getOrderedBy(), order.getDepartment(), order.getPriority(),
                order.getStatus(), order.getClinicalNotes(), order.getOrderedAt(),
                order.getItems().stream().map(mapper::toResponse).toList(),
                order.getSpecimens().stream().map(mapper::toResponse).toList(),
                resultResponses,
                histograms.findByOrderId(order.getId()).stream().map(mapper::toResponse).toList(),
                found.stream().anyMatch(LabResult::isAbnormal),
                // The narrative the report prints. Computed on read rather than stored, so retuning
                // a threshold changes what today's reports say without a migration over history -
                // the same reasoning that keeps a room's directions off the appointment row.
                interpretations.interpret(found, null));
    }

    private String defaultSpecimenType(LabOrder order) {
        return order.getItems().stream()
                .findFirst()
                .flatMap(item -> catalog.findByCodeIgnoreCase(item.getTestCode()))
                .map(LabTestCatalogEntry::getSpecimenType)
                .orElse("WHOLE_BLOOD");
    }

    private void publish(String type, LabOrder order, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("patientId", order.getPatientId().toString());
        payload.put("mrn", order.getPatientMrn());
        payload.put("status", order.getStatus().name());
        events.publish(Topics.LAB, DomainEvent.of(type, "LabOrder", order.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }
}
