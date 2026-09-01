package com.hms.laboratory.device.astm;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the host's answer to an analyzer's query: the orders for one or more samples.
 *
 * <p>This is the reply half of bidirectional operation. The instrument reads a tube's barcode, asks
 * what is ordered for it, and this transmission tells it — so nobody keys a worklist into the
 * instrument's keypad, which is where the second class of sample mix-up comes from.
 *
 * <p><strong>Deliberately round-trippable.</strong> The records emitted here are H, P, O and L, the
 * same four {@link AstmParser} already reads, and the sample id goes in O field 2 where
 * {@code AstmRecordParser.parseOrder} looks for it. That is not a coincidence: it means a test can
 * feed this writer's output straight back through the platform's own parser and assert the sample id
 * and patient survive the trip, which catches a field-position mistake that no amount of reading the
 * specification would.
 *
 * <p><strong>No patient name, and no MRN.</strong> Not an omission - laboratory-service does not
 * hold the name at all (it lives in patient-service, behind a service boundary), and the honest
 * consequence is that the worklist does not carry one. That turns out to be the right answer rather
 * than a limitation to work around: an analyzer files by sample id, and instruments sit in shared
 * rooms spooling to local printers, so reaching across a service boundary to fetch a name purely to
 * put identity on that screen would be a cost with no clinical gain. The P record is still emitted,
 * because instruments expect H/P/O/L, carrying the sample id and sex only.
 */
public final class AstmWorklistWriter {

    private static final DateTimeFormatter ASTM_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private static final String RECORD_SEPARATOR = "\r";

    private AstmWorklistWriter() {
    }

    /**
     * One order per entry, in the order given.
     *
     * <p>An empty list still produces a valid H/L transmission. That matters: an analyzer that asked
     * about a tube with nothing ordered must receive a well-formed "no orders" answer, because a
     * silent connection looks identical to a broken one and the instrument will sit waiting.
     */
    public static String write(List<Entry> entries, String senderName, Instant now) {
        StringBuilder out = new StringBuilder(256);

        // H — header. The delimiter definition is the standard \^& set the parser expects.
        out.append("H|\\^&|||").append(sanitise(senderName)).append("|||||||P|1|")
                .append(ASTM_TIMESTAMP.format(now)).append(RECORD_SEPARATOR);

        int sequence = 1;
        for (Entry entry : entries) {
            // P — patient. Sysmex layout: [2] id, [7] sex. The name field is left empty on purpose
            // (see the class comment); the sample id stands in as the identifier.
            out.append("P|").append(sequence).append('|')
                    .append(sanitise(entry.sampleId()))
                    .append("|||||").append(sanitise(entry.sex()))
                    .append(RECORD_SEPARATOR);

            // O — order. Field 2 is the sample id; field 4 carries the test ids as ^^^CODE
            // components, one O record per requested test, which is how an instrument reads a
            // multi-test order.
            for (String testCode : entry.testCodes()) {
                out.append("O|").append(sequence).append('|')
                        .append(sanitise(entry.sampleId())).append("||^^^")
                        .append(sanitise(testCode)).append('|')
                        .append(priorityCode(entry.priority()))
                        .append("|||||A||||")
                        .append(sanitise(entry.specimenType()))
                        .append(RECORD_SEPARATOR);
            }
            sequence++;
        }

        // L — terminator. N is the normal termination code; anything else tells the instrument the
        // host gave up mid-transmission.
        out.append("L|1|N").append(RECORD_SEPARATOR);
        return out.toString();
    }

    /**
     * ASTM priority codes: S stat, A as-soon-as-possible, R routine.
     *
     * <p>Mapped rather than passed through, because the platform's own vocabulary
     * ({@code ROUTINE}/{@code URGENT}/{@code STAT}) is not the wire's, and sending an unrecognised
     * code makes some instruments reject the whole order.
     */
    private static char priorityCode(String priority) {
        if (priority == null) {
            return 'R';
        }
        return switch (priority.toUpperCase(java.util.Locale.ROOT)) {
            case "STAT" -> 'S';
            case "URGENT" -> 'A';
            default -> 'R';
        };
    }

    /**
     * Strips the ASTM delimiters from a value.
     *
     * <p>The field, repeat, component and escape characters are structural. A value containing one
     * would shift every field after it, and the instrument would read the next order's data in the
     * wrong slot — so they are removed rather than escaped: no accession number, test code or
     * specimen type has any business containing them, and a stripped character is a visible defect
     * where a shifted field is a silent one.
     */
    private static String sanitise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '|' && c != '^' && c != '&' && c != '\\' && c != '\r' && c != '\n') {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    /**
     * One sample's orders.
     *
     * @param sampleId     the accession number the analyzer asked about
     * @param sex          {@code M} or {@code F}; some analyzers apply sex-specific ranges of their own
     * @param priority     platform priority, mapped to an ASTM code
     * @param specimenType e.g. {@code WHOLE_BLOOD}
     * @param testCodes    one O record is written per code
     */
    public record Entry(String sampleId, String sex, String priority,
                        String specimenType, List<String> testCodes) {

        public Entry {
            testCodes = testCodes == null ? List.of() : List.copyOf(testCodes);
        }
    }
}
