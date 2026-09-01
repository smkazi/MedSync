package com.hms.laboratory.web.dto;

import com.hms.laboratory.domain.LabEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LabDtos {

    private LabDtos() {
    }

    public record CreateOrderRequest(
            @NotNull UUID patientId,
            @NotBlank @Size(max = 24) String patientMrn,
            @Size(max = 1) String patientSex,
            @NotEmpty List<@NotBlank String> testCodes,
            LabEnums.Priority priority,
            @Size(max = 32) String department,
            @Size(max = 1000) String clinicalNotes) {

        public LabEnums.Priority priorityOrDefault() {
            return priority == null ? LabEnums.Priority.ROUTINE : priority;
        }
    }

    public record CollectSpecimenRequest(@Size(max = 32) String specimenType) {
    }

    public record ManualResultRequest(@NotBlank @Size(max = 24) String parameter,
                                      @Size(max = 64) String value,
                                      @Size(max = 24) String unit) {
    }

    public record ManualResultsRequest(@NotEmpty List<ManualResultRequest> results) {
    }

    /** Raw analyzer transmission. Text for ASTM, base64 for the binary K-DPS protocol. */
    public record DeviceMessageRequest(
            @NotNull LabEnums.Protocol protocol,
            @Size(max = 80) String analyzerName,
            @Size(max = 1_000_000) String payload,
            /** Set when {@code payload} is base64-encoded binary rather than text. */
            Boolean base64) {

        public boolean isBase64() {
            return Boolean.TRUE.equals(base64);
        }
    }

    public record OrderItemResponse(UUID id, String testCode, String testName) {
    }

    public record SpecimenResponse(UUID id, String accessionNo, String specimenType,
                                   LabEnums.SpecimenStatus status, Instant collectedAt, Instant receivedAt,
                                   String collectedBy) {
    }

    public record ResultResponse(UUID id, String parameter, String displayName, String value, String unit,
                                 BigDecimal normalLow, BigDecimal normalHigh, String referenceRange, String flag,
                                 boolean abnormal, LabEnums.ResultSource source, LabEnums.ResultStatus status,
                                 String enteredBy, String verifiedBy, Instant verifiedAt) {
    }

    public record HistogramResponse(String group, List<Double> x, List<Double> y, String xLabel,
                                    Map<String, Double> indices) {
    }

    public record OrderResponse(UUID id, UUID patientId, String patientMrn, String patientSex, String orderedBy,
                                String department, LabEnums.Priority priority, LabEnums.OrderStatus status,
                                String clinicalNotes, Instant orderedAt, List<OrderItemResponse> items,
                                List<SpecimenResponse> specimens, List<ResultResponse> results,
                                List<HistogramResponse> histograms, boolean hasAbnormalResults,
                                InterpretationView interpretation) {
    }

    public record OrderSummary(UUID id, UUID patientId, String patientMrn, LabEnums.Priority priority,
                               LabEnums.OrderStatus status, Instant orderedAt, int testCount, int resultCount,
                               boolean hasAbnormalResults, String accessionNo) {
    }

    public record CatalogEntryResponse(UUID id, String code, String name, String department, String specimenType,
                                       List<String> parameters) {
    }

    public record ReferenceRangeResponse(UUID id, String parameter, String sex, BigDecimal normalLow,
                                         BigDecimal normalHigh, String unit, String displayName,
                                         String referenceRange) {
    }

    public record UpdateReferenceRangeRequest(BigDecimal normalLow, BigDecimal normalHigh) {
    }

    public record AnalyzerResponse(UUID id, String name, String model, LabEnums.Protocol protocol,
                                   String transport, boolean active, Instant lastSeen) {
    }

    /** What an ingest did, so a technician can see why a message did or did not land. */
    public record IngestResponse(UUID messageId, boolean parsedOk, String sampleId, UUID matchedOrderId,
                                 int samplesParsed, int resultsStored, int histogramsStored, String error,
                                 List<String> warnings) {
    }

    public record DeviceMessageResponse(UUID id, LabEnums.Protocol protocol, String sampleId, UUID matchedOrderId,
                                        boolean parsedOk, int resultCount, String error, Instant receivedAt,
                                        int payloadBytes) {
    }

    /**
     * What is ordered for one scanned sample.
     *
     * @param runnable whether an analyzer may be told to run it. False for a cancelled order (the
     *                 tests were called off) and for a verified one (signed off and closed - a
     *                 genuine repeat is a new order, which is also how it gets its own audit trail).
     *                 Exposed as a field rather than implied by an empty test list, so a UI can say
     *                 <em>why</em> a tube will not run instead of showing nothing.
     */
    public record WorklistResponse(String accessionNo, UUID orderId, String patientMrn,
                                   String patientSex, String priority, String orderStatus,
                                   String specimenType, List<String> testCodes, boolean runnable) {

        public WorklistResponse {
            testCodes = testCodes == null ? List.of() : List.copyOf(testCodes);
        }
    }

    /** One interpretive rule as configured, with its ANDed conditions. */
    public record InterpretiveRuleResponse(UUID id, String code, String label, String message,
                                           short displayOrder, boolean active,
                                           List<RuleConditionResponse> conditions) {

        public InterpretiveRuleResponse {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    public record RuleConditionResponse(UUID id, List<String> parameters, String operator,
                                        BigDecimal threshold) {

        public RuleConditionResponse {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /** Retunes a rule. Only the wording and whether it fires - the conditions are their own rows. */
    public record UpdateInterpretiveRuleRequest(@Size(max = 500) String message, Boolean active) {
    }

    public record MorphologyThresholdResponse(String code, BigDecimal threshold, String note) {
    }

    public record UpdateThresholdRequest(@NotNull BigDecimal threshold) {
    }

    /**
     * The narrative attached to a report: comments plus the smear morphology.
     *
     * <p>Decision support, deterministic and auditable - no model and no inference. It annotates a
     * report a pathologist signs; nothing here writes to a patient record on its own.
     */
    public record InterpretationView(List<String> notes, MorphologyView morphology) {

        public InterpretationView {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public boolean isEmpty() {
            return notes.isEmpty() && morphology == null;
        }
    }

    /**
     * Peripheral-smear morphology.
     *
     * @param comment  a pathologist's own comment, when one was entered. Present only for a manual
     *                 entry, and then the derived fields are null - somebody who looked down a
     *                 microscope outranks an inference from indices.
     * @param derived  true when the platform worked this out from the numeric indices rather than a
     *                 human entering it. Surfaced rather than hidden: a reader is entitled to know
     *                 which sentences on a report came from a person.
     */
    public record MorphologyView(String comment, String redCells, String whiteCells,
                                 boolean derived, String platelets) {

        /** Manual comment, or a derived pair without a platelet line. */
        public MorphologyView(String comment, String redCells, String whiteCells, boolean derived) {
            this(comment, redCells, whiteCells, derived, null);
        }
    }

    public record MessageResponse(String message) {
    }
}
