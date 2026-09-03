package com.hms.common.csv;

import java.util.List;

/**
 * Writes RFC 4180 CSV, and neutralises the leading characters a spreadsheet treats as formulas.
 *
 * <p>The escaping is the obvious half. The other half is the reason this class exists rather than
 * a {@code String.join(",", ...)} at the call site: a field whose first character is {@code =},
 * {@code +}, {@code -}, {@code @}, a tab or a carriage return is <em>executed</em> when the file is
 * opened in Excel, LibreOffice or Google Sheets. So a value somebody typed into this platform can
 * become {@code =HYPERLINK("http://…"&A1)} in the reader's spreadsheet, exfiltrating the row they
 * are looking at, or {@code =cmd|'…'!A1} on Windows. Quoting does not help — the quotes are CSV
 * syntax and the spreadsheet strips them before it looks at the first character.
 *
 * <p>The neutralisation is a single leading apostrophe, which every major spreadsheet reads as
 * "this is text" and strips from the displayed value. Deliberately not stripping the offending
 * character: an audit export whose contents were silently altered to be safe would be an audit
 * export nobody can rely on. A negative number is caught by this too, and the apostrophe is the
 * right answer there as well — a CSV of an audit trail is read, not summed.
 */
public final class CsvWriter {

    /** The characters a spreadsheet reads as the start of a formula rather than as text. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private final StringBuilder out = new StringBuilder();

    public CsvWriter(List<String> header) {
        row(header);
    }

    public CsvWriter row(List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(field(fields.get(i)));
        }
        // CRLF, as RFC 4180 specifies. Excel on Windows is the majority reader of a file like this.
        out.append("\r\n");
        return this;
    }

    public String toCsv() {
        return out.toString();
    }

    static String field(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty() && FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        boolean mustQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!mustQuote) {
            return value;
        }
        // RFC 4180: a quote inside a quoted field is doubled.
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
