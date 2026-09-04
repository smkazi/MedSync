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

    // ---- the line list -------------------------------------------------------
    //
    // Everything above this line is counts, under SURVEILLANCE_READ, which an epidemiologist
    // holds. Everything below it names patients, under PUBLIC_HEALTH_LINE_LIST, which is ADMIN
    // alone -- and that constant's javadoc argues why the epidemiologist is deliberately outside
    // it: the safety property that lets a role hold this whole module without holding a chart is
    // that it reads only aggregates, and a role given the names once no longer has it.

    /**
     * The names behind the counts, as a preview.
     *
     * <p>Audited and <strong>not</strong> registered, which is the distinction the response says
     * out loud in its {@code note}: looking at who is on this fortnight's return has notified
     * nobody. {@link #lineListCsv} is the act that notifies.
     */
    @GetMapping("/surveillance/notifiable/line-list")
    @PreAuthorize(Roles.PUBLIC_HEALTH_LINE_LIST)
    public SurveillanceDtos.NotifiableLineListResponse lineList(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return report.lineList(new SurveillanceReportService.Filters(from, to));
    }

    /**
     * The line list as the file that goes to the authority.
     *
     * <p><strong>The disclosure register is written before this method has a body to return.</strong>
     * If the register cannot be reached the service throws and this endpoint answers <strong>503
     * with no file</strong> — which is the one behaviour worth reading the whole module for: a list
     * of named patients that went out with no record of having gone out is what the register exists
     * to prevent.
     *
     * <p>{@code no-store} for the reason the aggregate export has it, and more so: a cached copy of
     * a file naming patients sitting in a shared browser's disk cache is a disclosure nobody
     * registered. The count of registered patients is echoed in a header rather than in the body,
     * because the body is a CSV somebody opens in a spreadsheet and a nineteenth row saying
     * "18 patients registered" would be read as a nineteenth case.
     */
    @GetMapping(value = "/surveillance/notifiable/line-list.csv", produces = "text/csv")
    @PreAuthorize(Roles.PUBLIC_HEALTH_LINE_LIST)
    public ResponseEntity<String> lineListCsv(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SurveillanceReportService.Notification notification =
                report.lineListCsv(new SurveillanceReportService.Filters(from, to));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + notification.filename() + "\"")
                .header("X-Disclosures-Registered", String.valueOf(notification.patients()))
                .body(notification.csv());
    }
}
