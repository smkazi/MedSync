package com.hms.laboratory.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.laboratory.service.LabReportService;
import com.hms.laboratory.service.PortalLabService;
import com.hms.laboratory.web.dto.LabDtos;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The patient's own laboratory results: what was ordered, and the reports that have been released. */
@RestController
@RequestMapping("/portal/reports")
@PreAuthorize(Roles.PORTAL)
public class PortalLabController {

    private final PortalLabService portal;

    public PortalLabController(PortalLabService portal) {
        this.portal = portal;
    }

    @GetMapping
    public List<LabDtos.PortalReportSummary> mine() {
        return portal.mine(CurrentUser.requirePatientId());
    }

    /** A released order in full: values, units and the reference range each was read against. */
    @GetMapping("/{orderId}")
    public LabDtos.OrderResponse released(@PathVariable UUID orderId) {
        return portal.released(CurrentUser.requirePatientId(), orderId);
    }

    /**
     * The released report, as the same PDF a clinician prints.
     *
     * <p>The same document deliberately: a portal that rendered its own simplified version would be
     * a second report of the same tests, and when the two disagreed nobody would know which one the
     * hospital stands behind. {@code no-store} for the reason the staff endpoint has it — a report
     * is patient data and must not sit in a shared cache.
     */
    @GetMapping(value = "/{orderId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID orderId, HttpServletRequest request) {
        LabReportService.Rendered rendered =
                portal.report(CurrentUser.requirePatientId(), orderId, bearerToken(request));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(rendered.fileName()).build().toString())
                .body(rendered.pdf());
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring("Bearer ".length());
    }
}
