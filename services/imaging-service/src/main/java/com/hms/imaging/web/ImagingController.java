package com.hms.imaging.web;

import com.hms.common.error.BadRequestException;
import com.hms.common.security.Roles;
import com.hms.imaging.service.ImagingOrderService;
import com.hms.imaging.service.ReportService;
import com.hms.imaging.service.StudyIngestService;
import com.hms.imaging.service.StudyReadService;
import com.hms.imaging.web.dto.ImagingDtos;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Radiology's API.
 *
 * <p>Three roles' work behind one prefix, and the {@code @PreAuthorize} on each method is where the
 * separation lives: a clinician orders, a radiographer acquires, a radiologist reports. No account
 * holds two of those, which the abuse-case suite asserts row by row.
 */
@RestController
@RequestMapping("/imaging")
public class ImagingController {

    private static final int MAX_UNMATCHED_PAGE = 200;

    private final ImagingOrderService orders;
    private final StudyIngestService ingest;
    private final StudyReadService studies;
    private final ReportService reports;

    public ImagingController(ImagingOrderService orders, StudyIngestService ingest,
                             StudyReadService studies, ReportService reports) {
        this.orders = orders;
        this.ingest = ingest;
        this.studies = studies;
        this.reports = reports;
    }

    // ---- the catalogue -------------------------------------------------------

    /**
     * What can be ordered.
     *
     * <p>Readable by anybody signed in, like the laboratory catalogue: it is a price-list-shaped
     * list of examination names with no patient anywhere in it, and an ordering screen that could
     * not read it would be an ordering screen with an empty select.
     */
    @GetMapping("/procedures")
    @PreAuthorize("isAuthenticated()")
    public List<ImagingDtos.ProcedureResponse> catalogue() {
        return orders.catalogue();
    }

    // ---- ordering ------------------------------------------------------------

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public ImagingDtos.OrderResponse order(@Valid @RequestBody ImagingDtos.CreateOrderRequest request) {
        return orders.create(request);
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize(Roles.IMAGING_READ)
    public ImagingDtos.OrderResponse readOrder(@PathVariable UUID id) {
        return orders.read(id);
    }

    @GetMapping("/patients/{patientId}/orders")
    @PreAuthorize(Roles.IMAGING_READ)
    public List<ImagingDtos.OrderResponse> forPatient(@PathVariable UUID patientId) {
        return orders.forPatient(patientId);
    }

    @GetMapping("/encounters/{encounterId}/orders")
    @PreAuthorize(Roles.IMAGING_READ)
    public List<ImagingDtos.OrderResponse> forEncounter(@PathVariable UUID encounterId) {
        return orders.forEncounter(encounterId);
    }

    /** Cancelling is the requester's act, not the department's: they asked, so they can unask. */
    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize(Roles.CLINICAL_WRITE)
    public ImagingDtos.OrderResponse cancel(@PathVariable UUID id,
                                            @Valid @RequestBody ImagingDtos.CancelRequest request) {
        return orders.cancel(id, request.reason());
    }

    // ---- acquisition ---------------------------------------------------------

    /**
     * The modality worklist.
     *
     * <p>{@code IMAGING_ACQUIRE}, so the department reads it and a ward does not. Scheduling a slot
     * is on the same gate for the same reason: which patient the machine sees at eleven is the
     * radiography room's business.
     */
    @GetMapping("/worklist")
    @PreAuthorize(Roles.IMAGING_ACQUIRE)
    public List<ImagingDtos.WorklistEntry> worklist(
            @RequestParam(required = false) String modality) {
        return orders.worklist(modality);
    }

    @PostMapping("/orders/{id}/schedule")
    @PreAuthorize(Roles.IMAGING_ACQUIRE)
    public ImagingDtos.OrderResponse schedule(@PathVariable UUID id,
                                              @Valid @RequestBody ImagingDtos.ScheduleRequest request) {
        return orders.schedule(id, request.scheduledFor());
    }

    /**
     * Registers one DICOM instance.
     *
     * <p>Multipart because the thing being sent is a file, and one instance per call because that is
     * how a modality sends: a study is assembled from however many arrive. Idempotent per SOP
     * instance UID, so a scanner that resends because it was unsure the first attempt landed does
     * not produce a second copy.
     *
     * <p>{@code consumes} is stated so a JSON body gets a 415 naming the problem rather than a
     * confusing failure inside the parser.
     */
    @PostMapping(value = "/studies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.IMAGING_ACQUIRE)
    public ImagingDtos.IngestResponse receive(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("No file was uploaded");
        }
        try {
            return ingest.ingest(file.getBytes());
        } catch (IOException ex) {
            // The upload did not arrive intact. Not a 500 about our own state: the caller's transfer
            // failed and resending is the fix, so it is reported as theirs.
            throw new UncheckedIOException("The uploaded file could not be read", ex);
        }
    }

    /**
     * Studies that matched no order.
     *
     * <p>{@code IMAGING_ACQUIRE}: resolving one means going back to the modality and the day's
     * paperwork, which is the department's job. Nothing here is narrowed, and nothing here can be —
     * an unmatched study has no patient to have a care relationship with, which is precisely the
     * problem it represents.
     */
    @GetMapping("/studies/unmatched")
    @PreAuthorize(Roles.IMAGING_ACQUIRE)
    public List<ImagingDtos.StudyResponse> unmatched(
            @RequestParam(defaultValue = "50") int size) {
        return studies.unmatched(PageRequest.of(0, Math.min(Math.max(size, 1), MAX_UNMATCHED_PAGE)));
    }

    // ---- reporting -----------------------------------------------------------

    /** Acquired and unread. The queue a radiologist works from. */
    @GetMapping("/reporting-queue")
    @PreAuthorize(Roles.IMAGING_REPORT)
    public List<ImagingDtos.WorklistEntry> reportingQueue() {
        return orders.reportingQueue();
    }

    @PutMapping("/studies/{studyId}/report")
    @PreAuthorize(Roles.IMAGING_REPORT)
    public ImagingDtos.ReportResponse draft(@PathVariable UUID studyId,
                                            @Valid @RequestBody ImagingDtos.ReportRequest request) {
        return reports.draft(studyId, request);
    }

    /** Signing is release. The screen says so; so does this method's own name in the audit trail. */
    @PostMapping("/studies/{studyId}/report/sign")
    @PreAuthorize(Roles.IMAGING_REPORT)
    public ImagingDtos.ReportResponse sign(@PathVariable UUID studyId) {
        return reports.sign(studyId);
    }

    @PostMapping("/studies/{studyId}/report/amend")
    @PreAuthorize(Roles.IMAGING_REPORT)
    public ImagingDtos.ReportResponse amend(@PathVariable UUID studyId,
                                            @Valid @RequestBody ImagingDtos.AmendRequest request) {
        return reports.amend(studyId, request);
    }

    @GetMapping("/studies/{studyId}/report")
    @PreAuthorize(Roles.IMAGING_READ)
    public ImagingDtos.ReportResponse readReport(@PathVariable UUID studyId) {
        return reports.read(studyId);
    }
}
