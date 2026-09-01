package com.hms.laboratory.web;

import com.hms.common.api.PageResponse;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.Roles;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.label.SpecimenLabelRenderer;
import com.hms.laboratory.service.DeviceIngestService;
import com.hms.laboratory.service.LabMapper;
import com.hms.laboratory.service.LabOrderService;
import com.hms.laboratory.service.LabResultService;
import com.hms.laboratory.service.ReferenceRangeService;
import com.hms.laboratory.service.WorklistService;
import com.hms.laboratory.web.dto.LabDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.hms.laboratory.repo.AnalyzerRepository;
import com.hms.laboratory.repo.DeviceMessageRepository;
import com.hms.laboratory.repo.LabTestCatalogRepository;
import com.hms.laboratory.repo.ReferenceRangeRepository;

/** The laboratory API: ordering, collection, results, verification, catalog and device ingest. */
@RestController
@RequestMapping("/lab")
public class LabController {

    private final LabOrderService orderService;
    private final LabResultService resultService;
    private final DeviceIngestService ingestService;
    private final ReferenceRangeService rangeService;
    private final LabTestCatalogRepository catalog;
    private final AnalyzerRepository analyzers;
    private final DeviceMessageRepository messages;
    private final ReferenceRangeRepository ranges;
    private final LabMapper mapper;
    private final SpecimenLabelRenderer labels;
    private final WorklistService worklists;

    public LabController(LabOrderService orderService, LabResultService resultService,
                         DeviceIngestService ingestService, ReferenceRangeService rangeService,
                         LabTestCatalogRepository catalog,
                         AnalyzerRepository analyzers,
                         DeviceMessageRepository messages,
                         ReferenceRangeRepository ranges, LabMapper mapper,
                         SpecimenLabelRenderer labels, WorklistService worklists) {
        this.orderService = orderService;
        this.resultService = resultService;
        this.ingestService = ingestService;
        this.rangeService = rangeService;
        this.catalog = catalog;
        this.analyzers = analyzers;
        this.messages = messages;
        this.ranges = ranges;
        this.mapper = mapper;
        this.labels = labels;
        this.worklists = worklists;
    }

    // ---- orders ----------------------------------------------------------------

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public LabDtos.OrderResponse createOrder(@Valid @RequestBody LabDtos.CreateOrderRequest request) {
        return orderService.create(request);
    }

    @GetMapping("/orders")
    @PreAuthorize(Roles.CLINICAL_READ)
    public PageResponse<LabDtos.OrderSummary> worklist(
            @RequestParam(required = false) String mrn,
            @RequestParam(required = false) List<LabEnums.OrderStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(orderService.search(mrn, status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "orderedAt"))));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public LabDtos.OrderResponse getOrder(@PathVariable UUID id) {
        return orderService.get(id);
    }

    @GetMapping("/patients/{patientId}/orders")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<LabDtos.OrderSummary> ordersForPatient(@PathVariable UUID patientId) {
        return orderService.forPatient(patientId);
    }

    /** Registers the tube and issues its accession number. */
    @PostMapping("/orders/{id}/specimens")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.LAB_WRITE)
    public LabDtos.SpecimenResponse collect(@PathVariable UUID id,
                                            @RequestBody(required = false) LabDtos.CollectSpecimenRequest request) {
        return orderService.collect(id, request);
    }

    @DeleteMapping("/orders/{id}")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public LabDtos.MessageResponse cancelOrder(@PathVariable UUID id) {
        orderService.cancel(id);
        return new LabDtos.MessageResponse("Order cancelled");
    }

    // ---- analyzer worklist: the host-query direction ---------------------------

    /**
     * What is ordered for a sample, as JSON.
     *
     * <p>The protocol-neutral form of the question, for the bench UI and for any device gateway that
     * would rather speak JSON than ASTM. 404 for an unknown accession, because a person asking about
     * a tube that does not exist needs telling.
     */
    @GetMapping("/worklist/query")
    @PreAuthorize(Roles.CLINICAL_READ)
    public LabDtos.WorklistResponse worklistFor(@RequestParam String sampleId) {
        return worklists.forSample(sampleId);
    }

    /**
     * The same question from an instrument, answered on the wire.
     *
     * <p>Raw ASTM in, raw ASTM out — this is the seam a serial or TCP device gateway relays through,
     * the reply half of the {@code POST /lab/device-messages} path that already existed. Text rather
     * than JSON because the payload is a framed transmission, not a document.
     *
     * <p>Always 200 with a well-formed transmission, even when nothing is ordered. An analyzer is a
     * state machine waiting on a reply: an error status would leave it blocked mid-conversation, and
     * the operator would see a hung instrument rather than a tube with no orders.
     */
    @PostMapping(value = "/device-messages/query", consumes = "text/plain", produces = "text/plain")
    @PreAuthorize(Roles.LAB_WRITE)
    public ResponseEntity<String> answerAnalyzerQuery(@RequestBody String queryTransmission) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(worklists.astmReply(queryTransmission));
    }

    // ---- specimens: barcode labels and scan-to-find ----------------------------

    /**
     * The order a scanned tube belongs to.
     *
     * <p>{@code CLINICAL_READ} rather than {@code LAB_WRITE}: scanning a tube to see what was
     * ordered is a read, and a nurse chasing a sample should not need write access to the bench.
     */
    @GetMapping("/specimens/by-accession/{accessionNo}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public LabDtos.OrderResponse orderForSpecimen(@PathVariable String accessionNo) {
        return orderService.byAccession(accessionNo);
    }

    /**
     * The tube label, as SVG.
     *
     * <p>Served as {@code image/svg+xml} so a browser prints it rather than displaying markup, and
     * with {@code no-store}: a label is generated for a specific tube at a specific moment, and a
     * cached label is how the wrong barcode ends up on the right tube.
     */
    @GetMapping(value = "/specimens/{accessionNo}/label", produces = "image/svg+xml")
    @PreAuthorize(Roles.LAB_WRITE)
    public ResponseEntity<String> specimenLabel(@PathVariable String accessionNo) {
        Specimen specimen = orderService.requireSpecimen(accessionNo);
        String svg = labels.render(specimen.getAccessionNo(), specimen.getSpecimenType());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(svg);
    }

    // ---- results ---------------------------------------------------------------

    @GetMapping("/orders/{id}/results")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<LabDtos.ResultResponse> results(@PathVariable UUID id) {
        return resultService.list(id, mapper);
    }

    @PostMapping("/orders/{id}/results")
    @PreAuthorize(Roles.LAB_WRITE)
    public List<LabDtos.ResultResponse> enterResults(@PathVariable UUID id,
                                                     @Valid @RequestBody LabDtos.ManualResultsRequest request) {
        return resultService.recordManual(id, request, mapper);
    }

    /** Releases the order's results. Restricted to a pathologist: this is the clinical sign-off. */
    @PostMapping("/orders/{id}/verify")
    @PreAuthorize(Roles.LAB_VERIFY)
    public LabDtos.MessageResponse verify(@PathVariable UUID id) {
        return new LabDtos.MessageResponse(resultService.verifyOrder(id) + " result(s) verified and released");
    }

    // ---- device ingest ---------------------------------------------------------

    /**
     * Accepts a raw analyzer transmission.
     *
     * <p>This is the seam a device gateway plugs into: the parsers are transport-agnostic, so a
     * serial or TCP listener only has to forward the bytes it received.
     */
    @PostMapping("/device-messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(Roles.LAB_WRITE)
    public LabDtos.IngestResponse ingest(@Valid @RequestBody LabDtos.DeviceMessageRequest request) {
        return ingestService.ingest(request);
    }

    @GetMapping("/device-messages")
    @PreAuthorize(Roles.LAB_WRITE)
    public PageResponse<LabDtos.DeviceMessageResponse> deviceMessages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(messages.findAllByOrderByReceivedAtDesc(PageRequest.of(page, Math.min(size, 100)))
                .map(mapper::toResponse));
    }

    // ---- reference data --------------------------------------------------------

    @GetMapping("/catalog")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<LabDtos.CatalogEntryResponse> catalog() {
        return catalog.findByActiveTrueOrderByName().stream().map(mapper::toResponse).toList();
    }

    @GetMapping("/reference-ranges")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<LabDtos.ReferenceRangeResponse> referenceRanges() {
        return rangeService.findAll().stream().map(mapper::toResponse).toList();
    }

    /** Ranges are lab-configurable; changing one is an administrative act. */
    @PatchMapping("/reference-ranges/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PATHOLOGIST')")
    public LabDtos.ReferenceRangeResponse updateRange(@PathVariable UUID id,
                                                      @RequestBody LabDtos.UpdateReferenceRangeRequest request) {
        var range = ranges.findById(id).orElseThrow(() -> NotFoundException.of("ReferenceRange", id));
        if (request.normalLow() != null) {
            range.setNormalLow(request.normalLow());
        }
        if (request.normalHigh() != null) {
            range.setNormalHigh(request.normalHigh());
        }
        return mapper.toResponse(ranges.save(range));
    }

    @GetMapping("/analyzers")
    @PreAuthorize(Roles.CLINICAL_READ)
    public List<LabDtos.AnalyzerResponse> analyzers() {
        return analyzers.findByActiveTrueOrderByName().stream().map(mapper::toResponse).toList();
    }
}
