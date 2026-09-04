package com.hms.scheduling.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The public-health return's shapes.
 *
 * <p>Every record here is an aggregate. There is no patient id, MRN, name or date of birth anywhere
 * in this file, and the query behind it does not select one — which is the point rather than an
 * omission, and is why the whole surveillance surface can be held by a role that reads no chart.
 */
public final class SurveillanceDtos {

    private SurveillanceDtos() {
    }

    /** One configured condition: which code is reportable, and how fast. */
    public record NotifiableConditionResponse(String icd10Code, String conditionName,
                                              int notifyWithinHours, boolean active) {
    }

    /**
     * One line of the return.
     *
     * @param cases     distinct patients diagnosed in the period, or <strong>null</strong> when the
     *                  count is below the small-cell threshold. Null rather than zero, because a
     *                  suppressed count and no cases are different facts and rendering them
     *                  identically would make the report lie in the safer-looking direction
     * @param suppressed true when {@code cases} was withheld, so a reader can tell the two apart
     */
    public record NotifiableCountResponse(String icd10Code, String conditionName, Long cases,
                                          int notifyWithinHours, boolean suppressed) {
    }

    /**
     * The return.
     *
     * <p>Every configured condition appears, including those with no cases: a report that omitted
     * the zeroes would render "no cholera this fortnight" and "cholera is not on our list"
     * identically, and those are very different facts about a district.
     *
     * @param zone                the zone the period was resolved in, echoed because a notifiable
     *                            week is a statutory boundary and a return whose days were cut
     *                            somewhere else is a different return
     * @param smallCellThreshold  what was configured, echoed so a reader knows whether anything
     *                            could have been withheld. Zero means nothing was
     */
    public record NotifiableReportResponse(LocalDate from, LocalDate to, String zone,
                                           List<NotifiableCountResponse> conditions, long totalCases,
                                           int smallCellThreshold, boolean suppressed,
                                           Instant computedAt) {

        public NotifiableReportResponse {
            conditions = List.copyOf(conditions);
        }
    }
}
