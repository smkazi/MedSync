package com.hms.laboratory.service;

import com.hms.laboratory.device.astm.Histogram;
import com.hms.laboratory.domain.Analyzer;
import com.hms.laboratory.domain.DeviceMessage;
import com.hms.laboratory.domain.HistogramRecord;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabOrderItem;
import com.hms.laboratory.domain.LabResult;
import com.hms.laboratory.domain.LabTestCatalogEntry;
import com.hms.laboratory.domain.ReferenceRange;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.web.dto.LabDtos;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Entity to DTO translation for the laboratory API. */
public class LabMapper {

    private final ObjectMapper objectMapper;

    public LabMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LabDtos.OrderItemResponse toResponse(LabOrderItem item) {
        return new LabDtos.OrderItemResponse(item.getId(), item.getTestCode(), item.getTestName());
    }

    public LabDtos.SpecimenResponse toResponse(Specimen specimen) {
        return new LabDtos.SpecimenResponse(specimen.getId(), specimen.getAccessionNo(),
                specimen.getSpecimenType(), specimen.getStatus(), specimen.getCollectedAt(),
                specimen.getReceivedAt(), specimen.getCollectedBy());
    }

    public LabDtos.ResultResponse toResponse(LabResult result, String displayName) {
        return new LabDtos.ResultResponse(result.getId(), result.getParameter(),
                displayName == null || displayName.isBlank() ? result.getParameter() : displayName,
                result.getValue(), result.getUnit(), result.getNormalLow(), result.getNormalHigh(),
                result.getRefText(), result.getFlag(), result.isAbnormal(), result.getSource(),
                result.getStatus(), result.getEnteredBy(), result.getVerifiedBy(), result.getVerifiedAt());
    }

    public LabDtos.HistogramResponse toResponse(HistogramRecord record) {
        Histogram curve = readCurve(record.getCurve());
        Map<String, Double> indices = readIndices(record.getIndices());
        return new LabDtos.HistogramResponse(record.getGroupCode(), curve.x(), curve.y(), curve.xLabel(), indices);
    }

    public LabDtos.CatalogEntryResponse toResponse(LabTestCatalogEntry entry) {
        return new LabDtos.CatalogEntryResponse(entry.getId(), entry.getCode(), entry.getName(),
                entry.getDepartment(), entry.getSpecimenType(), entry.parameterList());
    }

    public LabDtos.ReferenceRangeResponse toResponse(ReferenceRange range) {
        return new LabDtos.ReferenceRangeResponse(range.getId(), range.getParameter(), range.getSex(),
                range.getNormalLow(), range.getNormalHigh(), range.getUnit(), range.getDisplayName(),
                range.asText());
    }

    public LabDtos.AnalyzerResponse toResponse(Analyzer analyzer) {
        return new LabDtos.AnalyzerResponse(analyzer.getId(), analyzer.getName(), analyzer.getModel(),
                analyzer.getProtocol(), analyzer.getTransport(), analyzer.isActive(), analyzer.getLastSeen());
    }

    public LabDtos.DeviceMessageResponse toResponse(DeviceMessage message) {
        return new LabDtos.DeviceMessageResponse(message.getId(), message.getProtocol(), message.getSampleId(),
                message.getMatchedOrderId(), message.isParsedOk(), message.getResultCount(), message.getError(),
                message.getReceivedAt(), message.getPayloadBytes());
    }

    public LabDtos.OrderSummary toSummary(LabOrder order, long resultCount, boolean hasAbnormal) {
        String accessionNo = order.getSpecimens().isEmpty() ? null
                : order.getSpecimens().get(order.getSpecimens().size() - 1).getAccessionNo();
        return new LabDtos.OrderSummary(order.getId(), order.getPatientId(), order.getPatientMrn(),
                order.getPriority(), order.getStatus(), order.getOrderedAt(), order.getItems().size(),
                (int) resultCount, hasAbnormal, accessionNo);
    }

    public String writeCurve(Histogram histogram) {
        return objectMapper.writeValueAsString(histogram);
    }

    public String writeIndices(Map<String, Double> indices) {
        return objectMapper.writeValueAsString(indices);
    }

    private Histogram readCurve(String json) {
        if (json == null || json.isBlank()) {
            return new Histogram(List.of(), List.of(), Histogram.CHANNEL_AXIS);
        }
        return objectMapper.readValue(json, Histogram.class);
    }

    private Map<String, Double> readIndices(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {
        });
    }
}
