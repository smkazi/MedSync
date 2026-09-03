package com.hms.identity.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.identity.service.AuditReportService;
import com.hms.identity.service.UserMapper;
import com.hms.identity.web.dto.AuthDtos;
import java.time.LocalDate;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the platform audit trail. Admin only — it spans every service's activity. */
@RestController
@RequestMapping("/admin")
@PreAuthorize(Roles.ADMIN_ONLY)
public class AuditController {

    private final AuditReportService report;

    public AuditController(AuditReportService report) {
        this.report = report;
    }

    @GetMapping("/audit")
    public PageResponse<AuthDtos.AuditResponse> list(@RequestParam(required = false) String entity,
                                                     @RequestParam(required = false) String action,
                                                     @RequestParam(required = false) String actorId,
                                                     @RequestParam(required = false) String username,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false)
                                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(
                report.search(filters(entity, action, actorId, username, from, to),
                        PageRequest.of(page, Math.min(size, 200))),
                UserMapper::toResponse);
    }

    /**
     * The same report as a file.
     *
     * <p>{@code text/csv} with {@code Content-Disposition: attachment}, following the download
     * shape the portal's record export already uses, and {@code no-store} because this file names
     * who did what to whom.
     */
    @GetMapping(value = "/audit.csv", produces = "text/csv")
    public ResponseEntity<String> csv(@RequestParam(required = false) String entity,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String actorId,
                                      @RequestParam(required = false) String username,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditReportService.Filters filters = filters(entity, action, actorId, username, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.csvFilename(filters) + "\"")
                .body(report.toCsv(filters));
    }

    private static AuditReportService.Filters filters(String entity, String action, String actorId,
                                                      String username, LocalDate from, LocalDate to) {
        return new AuditReportService.Filters(entity, action, actorId, username, from, to);
    }
}
