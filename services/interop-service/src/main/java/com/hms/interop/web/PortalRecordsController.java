package com.hms.interop.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.interop.service.ExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }
}
