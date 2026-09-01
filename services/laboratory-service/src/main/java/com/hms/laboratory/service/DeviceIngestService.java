package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.laboratory.device.astm.AstmParser;
import com.hms.laboratory.device.astm.AstmRecord;
import com.hms.laboratory.device.astm.Histogram;
import com.hms.laboratory.device.astm.HistogramExtractor;
import com.hms.laboratory.device.kdps.KdpsParser;
import com.hms.laboratory.device.kdps.KdpsSample;
import com.hms.laboratory.domain.Analyzer;
import com.hms.laboratory.domain.DeviceMessage;
import com.hms.laboratory.domain.HistogramRecord;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.web.dto.LabDtos;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hms.laboratory.repo.AnalyzerRepository;
import com.hms.laboratory.repo.DeviceMessageRepository;
import com.hms.laboratory.repo.HistogramRepository;
import com.hms.laboratory.repo.LabOrderRepository;

/**
 * Turns an analyzer transmission into results on a patient's order.
 *
 * <p>This is the seam between the instrument floor and the clinical record. Three decisions matter:
 *
 * <ul>
 *   <li>Every message is stored verbatim before it is parsed, so a transmission that fails to
 *       decode is still available for diagnosis rather than lost.</li>
 *   <li>A sample is matched to an order by accession number first, then by MRN among orders still
 *       awaiting results. An unmatched message is kept and reported, never guessed at — filing a
 *       result on the wrong patient is worse than filing none.</li>
 *   <li>Tagged histogram records are routed to the graph, not the numeric rows, and their derived
 *       indices are stored alongside as computed values.</li>
 * </ul>
 */
@Service
public class DeviceIngestService {

    private static final Logger log = LoggerFactory.getLogger(DeviceIngestService.class);

    private final LabOrderRepository orders;
    private final DeviceMessageRepository messages;
    private final AnalyzerRepository analyzers;
    private final HistogramRepository histograms;
    private final LabResultService resultService;
    private final LabMapper mapper;
    private final AuditService audit;

    public DeviceIngestService(LabOrderRepository orders,
                               DeviceMessageRepository messages,
                               AnalyzerRepository analyzers,
                               HistogramRepository histograms,
                               LabResultService resultService, LabMapper mapper, AuditService audit) {
        this.orders = orders;
        this.messages = messages;
        this.analyzers = analyzers;
        this.histograms = histograms;
        this.resultService = resultService;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional
    public LabDtos.IngestResponse ingest(LabDtos.DeviceMessageRequest request) {
        if (request.payload() == null || request.payload().isBlank()) {
            throw new BadRequestException("Device message payload is empty");
        }
        Analyzer analyzer = request.analyzerName() == null || request.analyzerName().isBlank()
                ? null
                : analyzers.findByNameIgnoreCase(request.analyzerName().trim()).orElse(null);

        DeviceMessage message = messages.save(new DeviceMessage(
                analyzer == null ? null : analyzer.getId(), request.protocol(), request.payload()));
        if (analyzer != null) {
            analyzer.touch();
        }

        List<String> warnings = new ArrayList<>();
        try {
            LabDtos.IngestResponse response = request.protocol() == LabEnums.Protocol.ASTM
                    ? ingestAstm(request, message, analyzer, warnings)
                    : ingestKdps(request, message, analyzer, warnings);
            audit.record("DEVICE_MESSAGE_INGESTED", "DeviceMessage", message.getId(),
                    "%s: %d result(s), order %s".formatted(request.protocol(), response.resultsStored(),
                            response.matchedOrderId()));
            return response;
        } catch (RuntimeException ex) {
            // The message is already persisted, so a decode failure is diagnosable rather than lost.
            log.error("Failed to ingest {} message {}", request.protocol(), message.getId(), ex);
            message.recordFailure(ex.getMessage());
            audit.record("DEVICE_MESSAGE_FAILED", "DeviceMessage", message.getId(), ex.getMessage());
            return new LabDtos.IngestResponse(message.getId(), false, null, null, 0, 0, 0,
                    ex.getMessage(), warnings);
        }
    }

    private LabDtos.IngestResponse ingestAstm(LabDtos.DeviceMessageRequest request, DeviceMessage message,
                                              Analyzer analyzer, List<String> warnings) {
        String transmission = request.isBase64()
                ? new String(Base64.getDecoder().decode(request.payload()), StandardCharsets.ISO_8859_1)
                : request.payload();

        List<AstmRecord.Sample> samples = AstmParser.parseAll(transmission);
        if (samples.isEmpty()) {
            message.recordFailure("No complete ASTM sample in the transmission");
            return new LabDtos.IngestResponse(message.getId(), false, null, null, 0, 0, 0,
                    "No complete ASTM sample in the transmission", warnings);
        }

        int storedResults = 0;
        int storedHistograms = 0;
        UUID matchedOrderId = null;
        String sampleId = null;

        for (AstmRecord.Sample sample : samples) {
            sampleId = sample.resolvedSampleId();
            Optional<LabOrder> match = matchOrder(sampleId, sample.resolvedName());
            if (match.isEmpty()) {
                warnings.add("No open order matched sample '" + sampleId + "'; results were not filed");
                continue;
            }
            LabOrder order = match.get();
            matchedOrderId = order.getId();
            Specimen specimen = latestSpecimen(order);

            // Histograms arrive as tagged result records; they belong on the graph, not in the
            // numeric rows, so they are separated before anything is stored.
            Map<String, Histogram> curves = new LinkedHashMap<>(
                    HistogramExtractor.extractFromResults(sample.results()));
            HistogramExtractor.extractFromComments(sample.comments())
                    .forEach(curves::putIfAbsent);

            for (AstmRecord.Result result : sample.results()) {
                if (HistogramExtractor.isHistogramParameter(result.parameter())) {
                    continue;
                }
                if (!result.hasValue()) {
                    // A masked or unmeasurable reading carries no number; recording a blank would
                    // look like a measured empty value.
                    warnings.add(result.parameter() + " was transmitted without a measurable value");
                    continue;
                }
                resultService.record(order, result.parameter(), result.value(), result.unit(),
                        LabEnums.ResultSource.ANALYZER, result.flag(),
                        toDecimal(result.normalLow()), toDecimal(result.normalHigh()), specimen,
                        analyzer == null ? null : analyzer.getId());
                storedResults++;
            }

            storedHistograms += storeHistograms(order, specimen, curves);
            order.advanceTo(LabEnums.OrderStatus.RESULTED);
        }

        message.recordSuccess(sampleId, matchedOrderId, storedResults);
        return new LabDtos.IngestResponse(message.getId(), true, sampleId, matchedOrderId, samples.size(),
                storedResults, storedHistograms, null, warnings);
    }

    private LabDtos.IngestResponse ingestKdps(LabDtos.DeviceMessageRequest request, DeviceMessage message,
                                              Analyzer analyzer, List<String> warnings) {
        List<KdpsSample> samples;
        if (request.isBase64()) {
            samples = KdpsParser.parse(Base64.getDecoder().decode(request.payload()));
        } else {
            // A non-base64 K-DPS payload is a raw-capture log of hex frames.
            samples = KdpsParser.parseCaptureText(request.payload());
        }
        if (samples.isEmpty()) {
            message.recordFailure("No K-DPS sample could be decoded from the payload");
            return new LabDtos.IngestResponse(message.getId(), false, null, null, 0, 0, 0,
                    "No K-DPS sample could be decoded from the payload", warnings);
        }

        int storedResults = 0;
        int storedHistograms = 0;
        UUID matchedOrderId = null;
        String sampleId = null;

        for (KdpsSample sample : samples) {
            sampleId = sample.identifier();
            Optional<LabOrder> match = matchOrder(sampleId, sampleId);
            if (match.isEmpty()) {
                warnings.add("No open order matched K-DPS sample '" + sampleId + "'; graphs were not filed");
                continue;
            }
            LabOrder order = match.get();
            matchedOrderId = order.getId();
            Specimen specimen = latestSpecimen(order);

            for (KdpsSample.KdpsResult result : sample.results()) {
                resultService.record(order, result.parameter(), result.value(), result.unit(),
                        LabEnums.ResultSource.ANALYZER, null, null, null, specimen,
                        analyzer == null ? null : analyzer.getId());
                storedResults++;
            }
            storedHistograms += storeHistograms(order, specimen, sample.histograms());
            order.advanceTo(LabEnums.OrderStatus.RESULTED);
        }

        message.recordSuccess(sampleId, matchedOrderId, storedResults);
        return new LabDtos.IngestResponse(message.getId(), true, sampleId, matchedOrderId, samples.size(),
                storedResults, storedHistograms, null, warnings);
    }

    /**
     * Stores each curve and the indices derived from it.
     *
     * <p>Derived values are recorded as results with source DERIVED, so a clinician can tell an
     * instrument's own MPV from one this platform computed off the distribution.
     */
    private int storeHistograms(LabOrder order, Specimen specimen, Map<String, Histogram> curves) {
        int stored = 0;
        for (Map.Entry<String, Histogram> entry : curves.entrySet()) {
            String group = entry.getKey();
            Histogram curve = entry.getValue();
            Map<String, Double> indices = HistogramExtractor.deriveIndices(curve, group);

            HistogramRecord record = histograms.findByOrderIdAndGroupCode(order.getId(), group)
                    .map(existing -> {
                        existing.replace(mapper.writeCurve(curve), mapper.writeIndices(indices));
                        return existing;
                    })
                    .orElseGet(() -> new HistogramRecord(order, group, mapper.writeCurve(curve),
                            mapper.writeIndices(indices)));
            record.setSpecimen(specimen);
            histograms.save(record);
            stored++;

            for (Map.Entry<String, Double> index : indices.entrySet()) {
                if ("rel_area".equals(index.getKey())) {
                    // A relative cell mass, not a reportable measurement: it has no calibration.
                    continue;
                }
                if (resultService.find(order.getId(), index.getKey()).isPresent()) {
                    // The instrument already reported this parameter; its own value wins.
                    continue;
                }
                resultService.record(order, index.getKey(), trimNumber(index.getValue()), "",
                        LabEnums.ResultSource.DERIVED, null, null, null, specimen, null);
            }
        }
        return stored;
    }

    /**
     * Finds the order a transmission belongs to: by accession number first, then by MRN among
     * orders still awaiting results.
     */
    private Optional<LabOrder> matchOrder(String sampleId, String fallbackId) {
        for (String candidate : new String[] {sampleId, fallbackId}) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String trimmed = candidate.trim();
            List<LabOrder> byAccession = orders.findOpenOrdersByAccession(trimmed);
            if (!byAccession.isEmpty()) {
                return Optional.of(byAccession.get(0));
            }
            List<LabOrder> byMrn = orders.findAwaitingResultsByMrn(trimmed);
            if (!byMrn.isEmpty()) {
                return Optional.of(byMrn.get(0));
            }
        }
        return Optional.empty();
    }

    private static Specimen latestSpecimen(LabOrder order) {
        List<Specimen> specimens = order.getSpecimens();
        return specimens.isEmpty() ? null : specimens.get(specimens.size() - 1);
    }

    private static BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /** Renders a derived index without a trailing {@code .0} on a whole number. */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
