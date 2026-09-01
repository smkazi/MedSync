package com.hms.laboratory.report;

import com.hms.laboratory.web.dto.LabDtos;
import java.time.Instant;
import java.util.List;

/**
 * Everything the renderer needs, already resolved.
 *
 * <p>A record rather than the entities, so the renderer touches no repository and no lazy proxy. It
 * can therefore be tested by constructing one — which is what lets the PDF be asserted without a
 * database or a running patient-service.
 */
public record ReportContent(
        String title,
        String patientName,
        String patientMrn,
        String ageSex,
        String accessionNo,
        String orderedBy,
        Instant reportedAt,
        boolean verified,
        String verifiedBy,
        List<String> notes,
        LabDtos.MorphologyView morphology,
        String qrText) {

    public ReportContent {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
