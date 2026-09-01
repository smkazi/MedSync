package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.events.DomainEvent;
import com.hms.common.events.EventPublisher;
import com.hms.common.events.Topics;
import com.hms.common.security.CurrentUser;
import com.hms.common.web.CorrelationId;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.web.dto.LabDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hms.laboratory.repo.LabOrderRepository;
import com.hms.laboratory.repo.LabResultRepository;

/**
 * Recording and releasing results.
 *
 * <p>Two rules shape this service: a result is always interpreted against the lab's configured
 * reference range for the patient's sex (never left unflagged just because the instrument said
 * nothing), and entry is separated from verification so a technician's provisional number is not
 * mistaken for a released one.
 */
@Service
public class LabResultService {

    private final LabOrderRepository orders;
    private final LabResultRepository results;
    private final ReferenceRangeService ranges;
    private final EventPublisher events;
    private final AuditService audit;

    public LabResultService(LabOrderRepository orders, LabResultRepository results,
                           ReferenceRangeService ranges, EventPublisher events, AuditService audit) {
        this.orders = orders;
        this.results = results;
        this.ranges = ranges;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Records or updates one result, applying the reference range and flag.
     *
     * <p>Re-recording a parameter amends the existing row rather than adding a second one: an order
     * has one current value per parameter, and the unique constraint enforces it.
     */
    @Transactional
    public LabResult record(LabOrder order, String parameter, String value, String unit,
                            LabEnums.ResultSource source, String analyzerFlag, BigDecimal analyzerLow,
                            BigDecimal analyzerHigh, Specimen specimen, UUID analyzerId) {
        if (!order.acceptsResults()) {
            throw new ConflictException("Order " + order.getId() + " is " + order.getStatus()
                    + " and cannot take further results");
        }
        String actor = CurrentUser.usernameOrSystem();
        ReferenceRangeService.Interpretation interpretation = ranges.interpret(parameter, value,
                order.getPatientSex(), analyzerFlag, analyzerLow, analyzerHigh, unit);

        LabResult result = results.findByOrderIdAndParameter(order.getId(), parameter)
                .map(existing -> {
                    existing.amend(value, actor);
                    return existing;
                })
                .orElseGet(() -> new LabResult(order, parameter, value, interpretation.unit(), source, actor));

        result.setUnit(interpretation.unit());
        result.applyRange(interpretation.normalLow(), interpretation.normalHigh(), interpretation.refText(),
                interpretation.flag());
        if (specimen != null) {
            result.setSpecimen(specimen);
        }
        result.setAnalyzerId(analyzerId);
        return results.save(result);
    }

    /** Manual entry by a technician, for tests no instrument reports. */
    @Transactional
    public List<LabDtos.ResultResponse> recordManual(UUID orderId, LabDtos.ManualResultsRequest request,
                                                    LabMapper mapper) {
        LabOrder order = orders.findDetailById(orderId)
                .orElseThrow(() -> NotFoundException.of("LabOrder", orderId));
        Specimen specimen = order.getSpecimens().isEmpty() ? null
                : order.getSpecimens().get(order.getSpecimens().size() - 1);

        List<LabResult> recorded = request.results().stream()
                .map(entry -> record(order, entry.parameter().trim().toUpperCase(), entry.value(), entry.unit(),
                        LabEnums.ResultSource.MANUAL, null, null, null, specimen, null))
                .toList();

        order.advanceTo(LabEnums.OrderStatus.RESULTED);
        audit.record("LAB_RESULTS_ENTERED", "LabOrder", orderId,
                recorded.size() + " result(s) entered manually");
        publish("lab.results.recorded", order, Map.of("count", recorded.size(), "source", "MANUAL"));

        return recorded.stream()
                .map(result -> mapper.toResponse(result, displayName(result, order)))
                .toList();
    }

    /**
     * Releases every result on an order.
     *
     * <p>Verification is the point at which a number becomes clinically usable, so it is recorded
     * per result with who released it and when — and only a pathologist may call it.
     */
    @Transactional
    public int verifyOrder(UUID orderId) {
        LabOrder order = orders.findDetailById(orderId)
                .orElseThrow(() -> NotFoundException.of("LabOrder", orderId));
        List<LabResult> found = results.findByOrderIdOrderByParameter(orderId);
        if (found.isEmpty()) {
            throw new ConflictException("There are no results on this order to verify");
        }
        String actor = CurrentUser.usernameOrSystem();
        found.forEach(result -> result.verify(actor));
        order.advanceTo(LabEnums.OrderStatus.VERIFIED);

        audit.record("LAB_RESULTS_VERIFIED", "LabOrder", orderId, found.size() + " result(s) released by " + actor);
        publish("lab.results.verified", order, Map.of("count", found.size(),
                "abnormal", found.stream().filter(LabResult::isAbnormal).count()));
        return found.size();
    }

    @Transactional(readOnly = true)
    public List<LabDtos.ResultResponse> list(UUID orderId, LabMapper mapper) {
        LabOrder order = orders.findDetailById(orderId)
                .orElseThrow(() -> NotFoundException.of("LabOrder", orderId));
        return results.findByOrderIdOrderByParameter(orderId).stream()
                .map(result -> mapper.toResponse(result, displayName(result, order)))
                .toList();
    }

    private String displayName(LabResult result, LabOrder order) {
        return ranges.find(result.getParameter(), order.getPatientSex())
                .map(com.hms.laboratory.domain.ReferenceRange::getDisplayName)
                .orElse(result.getParameter());
    }

    private void publish(String type, LabOrder order, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
        payload.put("patientId", order.getPatientId().toString());
        payload.put("mrn", order.getPatientMrn());
        payload.put("status", order.getStatus().name());
        events.publish(Topics.LAB, DomainEvent.of(type, "LabOrder", order.getId(),
                CurrentUser.idOrSystem().toString(), CorrelationId.current(), payload));
    }

    /** Exposed for the ingest path, which resolves the specimen itself. */
    Optional<LabResult> find(UUID orderId, String parameter) {
        return results.findByOrderIdAndParameter(orderId, parameter);
    }
}
