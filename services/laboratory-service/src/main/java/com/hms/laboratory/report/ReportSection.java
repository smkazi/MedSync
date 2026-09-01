package com.hms.laboratory.report;

import java.util.List;

/** One headed block of rows on the report. */
public record ReportSection(String title, List<ReportRow> rows) {

    public ReportSection {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
