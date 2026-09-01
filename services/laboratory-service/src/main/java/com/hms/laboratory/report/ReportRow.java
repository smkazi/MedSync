package com.hms.laboratory.report;

/**
 * One printed result line.
 *
 * @param parameter      the instrument's own code (HGB, RDW-CV). Carried alongside the display name
 *                       because the section layout is keyed on it — matching back from the display
 *                       name would break the moment a laboratory renames "Haemoglobin" to "Hb".
 * @param flag           {@code H}, {@code L} or blank. Rendered as-is: a pathologist reads these
 *                       letters, and expanding them to "high" would cost the column its scannability.
 * @param flagged        whether to print the row in bold, so an abnormal value is findable without
 *                       reading across to the flag column
 */
public record ReportRow(String parameter, String displayName, String value, String unit,
                        String referenceRange, String flag, boolean flagged) {
}
