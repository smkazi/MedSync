package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.service.SurveillanceReportService;
import com.hms.scheduling.web.dto.SurveillanceDtos;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notifiable-disease return.
 *
 * <p>Lives in scheduling-service because scheduling owns {@code diagnoses}, and that is forced
 * rather than chosen: to compute an aggregate defined by carrying no patient identifiers, a service
 * anywhere else would have to ship every patient identifier over the wire to count them. The
 * aggregate endpoint would internally <em>be</em> the line list, and the group-by would happen in a
 * JVM instead of on an index.
 *
 * <p>Everything here is counts. The names behind them are a separate act with a separate gate, in a
 * separate service, and they leave a disclosure record behind — which is the whole reason this
 * surface can be held by a role that reads no chart.
 */
@RestController
public class SurveillanceController {

    private final SurveillanceReportService report;

    public SurveillanceController(SurveillanceReportService report) {
        this.report = report;
    }

    /**
     * Which diagnoses are reportable, and how fast.
     *
     * <p>Gated like the report itself rather than left open to anybody signed in. A vaccine
     * catalogue is a list of names; this is a list of what the hospital is watching for, which in a
     * small district is a statement about what it has been seeing.
     */
    @GetMapping("/surveillance/notifiable-conditions")
    @PreAuthorize(Roles.SURVEILLANCE_READ)
    public List<SurveillanceDtos.NotifiableConditionResponse> conditions() {
        return report.configured();
    }

    @GetMapping("/surveillance/notifiable")
    @PreAuthorize(Roles.SURVEILLANCE_READ)
    public SurveillanceDtos.NotifiableReportResponse notifiable(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return report.report(new SurveillanceReportService.Filters(from, to));
    }

    /**
     * The same report as CSV, because a return gets filed rather than read.
     *
     * <p>{@code no-store} and a filename carrying the period, following the audit export: two
     * downloads in one folder have to be tellable apart, and a cached statutory return is a return
     * somebody files twice.
     */
    @GetMapping(value = "/surveillance/notifiable.csv", produces = "text/csv")
    @PreAuthorize(Roles.SURVEILLANCE_READ)
    public ResponseEntity<String> csv(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SurveillanceReportService.Filters filters = new SurveillanceReportService.Filters(from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.csvFilename(filters) + "\"")
                .body(report.toCsv(filters));
    }
}
