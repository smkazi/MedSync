package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.scheduling.client.OrderingClient;
import com.hms.scheduling.domain.Encounter;
import com.hms.scheduling.domain.OrderSet;
import com.hms.scheduling.domain.OrderSetItem;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.repo.OrderSetRepository;
import com.hms.scheduling.web.dto.CareDtos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order sets: the list, and applying one.
 *
 * <p>Applying is a <strong>saga with compensation</strong>, and the honesty about that is the most
 * important thing in this class. The plan for this work said "in a single transaction, atomically",
 * and that is not available: the prescription lands in pharmacy-service's schema and the laboratory
 * order in laboratory-service's, so no database transaction spans them. Writing the code as though
 * one did would produce exactly the failure such a comment is meant to prevent.
 *
 * <p>What is available, and what this does:
 *
 * <ol>
 *   <li>The set's codes are validated first, so an obviously wrong set raises nothing.</li>
 *   <li>The prescription is raised first, because it is the step that can be refused on clinical
 *       grounds — an allergy, an interaction, a role that may not prescribe. A refusal at this
 *       point has left nothing behind.</li>
 *   <li>The laboratory order is raised second. If it fails, the prescription is withdrawn.</li>
 *   <li>If the withdrawal also fails, the response says so plainly and names the prescription,
 *       because a clinician can cancel it by hand and cannot act on "something went wrong".</li>
 * </ol>
 */
@Service
public class OrderSetService {

    private final OrderSetRepository orderSets;
    private final EncounterRepository encounters;
    private final OrderingClient ordering;
    private final AuditService audit;

    public OrderSetService(OrderSetRepository orderSets, EncounterRepository encounters,
                           OrderingClient ordering, AuditService audit) {
        this.orderSets = orderSets;
        this.encounters = encounters;
        this.ordering = ordering;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CareDtos.OrderSetResponse> available(String department, boolean includeInactive) {
        List<OrderSet> rows = includeInactive
                ? orderSets.findAllByOrderByNameAsc()
                : orderSets.available(department == null ? "" : department.trim());
        return rows.stream().map(OrderSetService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CareDtos.OrderSetResponse read(String code) {
        return toResponse(require(code));
    }

    @Transactional
    public CareDtos.OrderSetResponse create(CareDtos.CreateOrderSetRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (orderSets.findByCode(code).isPresent()) {
            throw new ConflictException("An order set with code '" + code + "' already exists");
        }
        OrderSet set = new OrderSet(code, request.name().trim(), request.description(),
                blankToNull(request.departmentCode()));
        int order = 1;
        for (CareDtos.OrderSetItemRequest item : request.items()) {
            set.addItem(toItem(item, order++));
        }
        OrderSet saved = orderSets.save(set);
        audit.record("ORDER_SET_CREATED", "OrderSet", saved.getId(),
                "%s: %d item(s)".formatted(code, saved.getItems().size()));
        return toResponse(saved);
    }

    @Transactional
    public CareDtos.OrderSetResponse update(String code, CareDtos.UpdateOrderSetRequest request) {
        OrderSet set = require(code);
        if (request.name() != null && !request.name().isBlank()) {
            set.setName(request.name().trim());
        }
        if (request.active() != null) {
            set.setActive(request.active());
        }
        orderSets.save(set);
        audit.record("ORDER_SET_UPDATED", "OrderSet", set.getId(),
                "%s, active=%s".formatted(code, set.isActive()));
        return toResponse(set);
    }

    /**
     * Raises everything the set names, against one encounter.
     *
     * @param bearerToken the caller's own token. Both callees apply the clinician's authority, so a
     *                    nurse applying a set that contains medicines is refused by
     *                    pharmacy-service — which is where that rule belongs, rather than
     *                    duplicated here as a second role list that can drift.
     */
    @Transactional
    public CareDtos.ApplyOrderSetResponse apply(String code,
                                                CareDtos.ApplyOrderSetRequest request,
                                                String bearerToken) {
        OrderSet set = require(code);
        if (!set.isActive()) {
            throw new BadRequestException(
                    "Order set '%s' has been retired and cannot be applied.".formatted(code));
        }
        Encounter encounter = encounters.findById(request.encounterId())
                .orElseThrow(() -> new NotFoundException(
                        "No encounter with id " + request.encounterId()));
        if (!encounter.isOpen()) {
            // The same rule the note and the vitals follow: a closed encounter is a finished
            // episode, and orders raised against one belong to a visit that is over.
            throw new BadRequestException(
                    "This encounter is closed. Open a new one before raising orders.");
        }

        // Pulled out once, with the invariant stated: an encounter loaded from the database always
        // has both, and if one is somehow absent the right behaviour is to fail loudly rather than
        // to post the string "null" to another service as a patient id.
        String encounterKey = Objects.requireNonNull(encounter.getId(),
                "an encounter always has an id").toString();
        String patientKey = Objects.requireNonNull(encounter.getPatientId(),
                "an encounter always names a patient").toString();

        List<OrderSetItem> medicines = set.medicationItems();
        List<OrderSetItem> tests = set.labItems();
        List<String> raised = new ArrayList<>();

        // Step one: the prescription, because it is the refusable step.
        UUID prescriptionId = null;
        if (!medicines.isEmpty()) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("encounterId", encounterKey);
            body.put("patientId", patientKey);
            body.put("patientMrn", encounter.getPatientMrn());
            body.put("items", medicines.stream().map(item -> {
                Map<String, Object> line = new java.util.LinkedHashMap<>();
                line.put("drugCode", item.getCode());
                line.put("dose", item.getDose());
                line.put("frequency", item.getFrequency());
                line.put("durationDays", item.getDurationDays());
                line.put("quantity", item.getQuantity());
                if (item.getInstructions() != null) {
                    line.put("instructions", item.getInstructions());
                }
                return line;
            }).toList());
            if (request.overrideReason() != null && !request.overrideReason().isBlank()) {
                body.put("overrideReason", request.overrideReason().trim());
            }
            OrderingClient.Raised prescription = ordering.prescribe(body, bearerToken);
            prescriptionId = prescription.id();
            raised.add(prescription.describe());
        }

        // Step two: the tests, as one order. One order rather than one per test, because a panel of
        // bloods is one needle: separate orders would mean separate specimens, separate accession
        // numbers and a patient stuck twice for what the phlebotomist does once.
        UUID labOrderId = null;
        boolean compensated = false;
        boolean compensationFailed = false;
        if (!tests.isEmpty()) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("patientId", patientKey);
            body.put("patientMrn", encounter.getPatientMrn());
            body.put("encounterId", encounterKey);
            body.put("testCodes", tests.stream().map(OrderSetItem::getCode).toList());
            body.put("priority", highestPriority(tests));
            if (encounter.getDepartmentCode() != null) {
                body.put("department", encounter.getDepartmentCode());
            }
            body.put("clinicalNotes", "Raised from order set " + set.getCode()
                    + " (" + set.getName() + ")");
            try {
                OrderingClient.Raised order = ordering.orderTests(body, bearerToken);
                labOrderId = order.id();
                raised.add(order.describe());
            } catch (RuntimeException ex) {
                if (prescriptionId == null) {
                    throw ex;
                }
                compensated = true;
                compensationFailed = !ordering.cancelPrescription(prescriptionId, bearerToken);
                audit.record("ORDER_SET_APPLY_FAILED", "OrderSet", set.getId(),
                        "%s: tests refused, prescription %s %s".formatted(code, prescriptionId,
                                compensationFailed ? "COULD NOT BE WITHDRAWN" : "withdrawn"));
                throw new ConflictException(compensationFailed
                        ? ("The tests in '%s' could not be raised (%s), and the prescription raised "
                                + "a moment earlier could not be withdrawn either. Prescription %s "
                                + "exists and has to be cancelled by hand.")
                                .formatted(code, ex.getMessage(), prescriptionId)
                        : ("The tests in '%s' could not be raised (%s). The prescription that had "
                                + "already been raised has been withdrawn, so nothing from this "
                                + "set is outstanding.").formatted(code, ex.getMessage()));
            }
        }

        audit.record("ORDER_SET_APPLIED", "Encounter", UUID.fromString(encounterKey),
                "%s: %s".formatted(code, String.join(", ", raised)));
        return new CareDtos.ApplyOrderSetResponse(set.getCode(), labOrderId, prescriptionId,
                raised, "%s applied: %s.".formatted(set.getName(), String.join(" and ", raised)),
                compensated, compensationFailed);
    }

    /**
     * The most urgent priority any test in the set carries.
     *
     * <p>One order carries one priority, and taking the highest is the only safe way to collapse
     * several: a set with one urgent test and two routine ones is an urgent draw, and averaging or
     * taking the first would quietly downgrade it.
     */
    private static String highestPriority(List<OrderSetItem> tests) {
        return tests.stream()
                .map(item -> item.getPriority() == null ? "ROUTINE" : item.getPriority())
                .max(Comparator.comparingInt(OrderSetService::rank))
                .orElse("ROUTINE");
    }

    private static int rank(String priority) {
        try {
            return SchedulingEnums.Priority.valueOf(priority.toUpperCase(Locale.ROOT)).ordinal();
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    private static OrderSetItem toItem(CareDtos.OrderSetItemRequest request, int order) {
        if (request.kind() == SchedulingEnums.OrderSetKind.MEDICATION) {
            if (request.dose() == null || request.dose().isBlank()
                    || request.frequency() == null || request.frequency().isBlank()
                    || request.durationDays() == null || request.quantity() == null) {
                // Refused here as well as by the CHECK constraint, so the message names the line
                // rather than a constraint. An order set is applied in one click: a line with no
                // dose is a prompt to guess in the one place nobody types the answer.
                throw new BadRequestException(
                        ("Medication line '%s' needs a dose, a frequency, a duration and a quantity. "
                                + "An order set is applied in one click, so a half-filled line "
                                + "would be a dose nobody chose.").formatted(request.code()));
            }
            return OrderSetItem.medication(request.code().trim().toUpperCase(Locale.ROOT),
                    request.dose().trim(), request.frequency().trim(), request.durationDays(),
                    request.quantity(), request.instructions(), order);
        }
        if (request.dose() != null || request.frequency() != null || request.durationDays() != null
                || request.quantity() != null) {
            throw new BadRequestException(
                    ("Laboratory line '%s' carries dose fields. That is a medicine typed as a test, "
                            + "and it would be raised as neither.").formatted(request.code()));
        }
        return OrderSetItem.lab(request.code().trim().toUpperCase(Locale.ROOT),
                request.priority() == null ? "ROUTINE"
                        : request.priority().trim().toUpperCase(Locale.ROOT), order);
    }

    private OrderSet require(String code) {
        return orderSets.findByCode(code.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No order set with code " + code));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CareDtos.OrderSetResponse toResponse(OrderSet set) {
        return new CareDtos.OrderSetResponse(set.getId(), set.getCode(), set.getName(),
                set.getDescription(), set.getDepartmentCode(), set.isActive(),
                set.getItems().stream()
                        .map(item -> new CareDtos.OrderSetItemResponse(item.getId(), item.getKind(),
                                item.getCode(), item.getDose(), item.getFrequency(),
                                item.getDurationDays(), item.getQuantity(), item.getInstructions(),
                                item.getPriority(), item.getDisplayOrder()))
                        .toList());
    }
}
