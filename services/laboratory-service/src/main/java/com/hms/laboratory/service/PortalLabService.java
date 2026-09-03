package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.laboratory.client.PatientDirectoryClient;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabOrderItem;
import com.hms.laboratory.repo.LabOrderRepository;
import com.hms.laboratory.web.dto.LabDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A patient's own laboratory results, and the one rule that governs all of them.
 *
 * <p><strong>Released means verified, and nothing else is reachable.</strong> A result entered at
 * the bench is provisional: it has not been looked at by a pathologist, it may be an analyzer
 * artefact, a mislabelled tube or a dilution nobody has repeated, and the platform already refuses
 * to call it a report. Publishing that to the patient would take the one workflow this laboratory
 * is built around — enter, verify, release — and route around its last step, so that the first
 * person to read an unverified number would be the person least equipped to know it might be wrong
 * and most frightened by it.
 *
 * <p>So the list says what stage an order has reached and never what it found, and the report
 * endpoints refuse anything short of {@code VERIFIED} in the platform's own words.
 */
@Service
public class PortalLabService {

    private final LabOrderRepository orders;
    private final LabOrderService labOrders;
    private final LabReportService reports;
    private final PatientDirectoryClient patients;
    private final AuditService audit;

    public PortalLabService(LabOrderRepository orders, LabOrderService labOrders,
                            LabReportService reports,
                            PatientDirectoryClient patients, AuditService audit) {
        this.orders = orders;
        this.labOrders = labOrders;
        this.reports = reports;
        this.patients = patients;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LabDtos.PortalReportSummary> mine(UUID patientId) {
        return orders.findByPatientIdOrderByOrderedAtDesc(patientId).stream()
                .map(PortalLabService::toSummary)
                .toList();
    }

    /**
     * A released order in full: every parameter, its value, its unit and its reference range.
     *
     * <p>The same view a clinician reads, deliberately. A portal that showed "normal" or "abnormal"
     * without the numbers would be asking the patient to take the platform's word for a judgement
     * that depends on which reference interval was applied — and a patient comparing this year's
     * haemoglobin to last year's is doing something useful that a traffic light cannot support.
     */
    @Transactional(readOnly = true)
    public LabDtos.OrderResponse released(UUID patientId, UUID orderId) {
        LabOrder order = orders.findById(orderId)
                .filter(candidate -> patientId.equals(candidate.getPatientId()))
                .orElseThrow(() -> NotFoundException.of("Laboratory order", orderId));
        if (order.getStatus() != LabEnums.OrderStatus.VERIFIED) {
            throw new BadRequestException(
                    "These results have not been released yet. They are checked by a pathologist "
                            + "before they are issued, and they will appear here once that is done.");
        }
        audit.record("PORTAL_RESULTS_READ", "LabOrder", orderId, "by the patient");
        return labOrders.toResponse(labOrders.requireDetail(orderId));
    }

    /**
     * The released report as a PDF, for one of the patient's own orders.
     *
     * <p>Two refusals, and they say different things on purpose. An order that is not this
     * patient's is a 404 — naming it as somebody else's would confirm that the id is a real order,
     * which is the first half of what an enumeration attack wants. An order that is this patient's
     * but not yet released is a 400 that says so plainly, because the patient knows the test was
     * taken and "not found" would be a lie about their own record.
     */
    public LabReportService.Rendered report(UUID patientId, UUID orderId, String bearerToken) {
        LabOrder order = orders.findById(orderId)
                .filter(candidate -> patientId.equals(candidate.getPatientId()))
                .orElseThrow(() -> NotFoundException.of("Laboratory order", orderId));
        if (order.getStatus() != LabEnums.OrderStatus.VERIFIED) {
            throw new BadRequestException(
                    "This report has not been released yet. Results are checked by a pathologist "
                            + "before they are issued, and it will appear here once that is done.");
        }
        PatientDirectoryClient.PatientIdentity me = patients.me(bearerToken);
        LabReportService.Rendered rendered = reports.render(orderId, bearerToken, me);
        // Logged as a disclosure like any other report print. The patient reading their own result
        // is not a disclosure to a stranger, but "who has seen this report" is a question the
        // audit trail should be able to answer completely.
        audit.record("PORTAL_REPORT_READ", "LabOrder", orderId, "by the patient");
        return rendered;
    }

    private static LabDtos.PortalReportSummary toSummary(LabOrder order) {
        return new LabDtos.PortalReportSummary(
                order.getId(),
                order.getOrderedAt(),
                order.getItems().stream().map(LabOrderItem::getTestName).toList(),
                progressOf(order.getStatus()),
                order.getStatus() == LabEnums.OrderStatus.VERIFIED);
    }

    /**
     * The internal status, said in words a patient can act on.
     *
     * <p>Deliberately not the enum. RESULTED means "the bench has entered numbers a pathologist has
     * not yet checked", and a portal that rendered the word "Resulted" would have a patient
     * telephoning to ask for the result it appears to be announcing. Four states are collapsed to
     * three because the difference between IN_PROGRESS and RESULTED is a laboratory's business and
     * changes nothing a patient can do.
     */
    private static String progressOf(LabEnums.OrderStatus status) {
        return switch (status) {
            case ORDERED -> "Waiting for your sample to be taken";
            case COLLECTED, IN_PROGRESS, RESULTED -> "In the laboratory";
            case VERIFIED -> "Report ready";
            case CANCELLED -> "Cancelled";
        };
    }
}
