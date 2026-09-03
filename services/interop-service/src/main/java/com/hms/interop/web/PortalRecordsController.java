package com.hms.interop.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.interop.service.ExchangeService;
import com.hms.interop.web.dto.InteropDtos;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Download and transmit: a patient's whole record, in FHIR, on request.
 *
 * <p>The same {@code searchset} bundle the administrator-run export produces, assembled from the
 * same builder — because a patient who takes their record to another hospital should be handing
 * over the thing this platform stands behind, not a portal-flavoured version of it.
 *
 * <p>{@code Content-Disposition: attachment} rather than inline. The response is several hundred
 * kilobytes of JSON and its purpose is to be saved and given to somebody, so the browser is told
 * to download it rather than render it. {@code no-store} for the reason every clinical response
 * on this platform carries it.
 */
@RestController
@RequestMapping("/portal/records")
@PreAuthorize(Roles.PORTAL)
public class PortalRecordsController {

    private final ExchangeService exchange;

    public PortalRecordsController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(HttpServletRequest request) {
        Map<String, Object> bundle =
                exchange.exportForSelf(CurrentUser.requirePatientId(), bearer(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"health-record.fhir.json\"")
                .body(bundle);
    }

    /**
     * The patient's own accounting of disclosures: what has left the building about them, to whom,
     * when, and how much of it.
     *
     * <p>Hung here rather than at {@code /portal/disclosures} for a concrete reason:
     * {@code /portal/records/**} is already routed to this service, so this endpoint needs no
     * gateway change at all — and a route nobody had to add is a route nobody can forget to add.
     *
     * <p>The patient comes from {@code requirePatientId()}, the signed claim, exactly as the export
     * beside it does. There is no path or query parameter naming a patient anywhere under
     * {@code /portal}, which is why an IDOR test against these endpoints has nothing to tamper
     * with.
     */
    @GetMapping(value = "/disclosures", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<InteropDtos.MyDisclosureResponse>> myDisclosures(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(exchange.myDisclosures(CurrentUser.requirePatientId(), from, to));
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }
}
