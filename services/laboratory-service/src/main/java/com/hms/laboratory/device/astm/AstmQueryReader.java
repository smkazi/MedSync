package com.hms.laboratory.device.astm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the sample ids out of an ASTM query transmission.
 *
 * <p>Separate from {@link AstmParser} deliberately. That class accumulates a result transmission
 * and emits a filed sample; this one reads a request and answers nothing. Folding a second
 * conversation into a stateful accumulator would mean one class holding two protocols' worth of
 * state, and the query direction has no patient, no results and no terminator semantics worth
 * sharing.
 *
 * <p>Stateless and therefore safe to share.
 */
public final class AstmQueryReader {

    private AstmQueryReader() {
    }

    /**
     * Extracts every distinct sample id a query transmission asks about.
     *
     * <p>A transmission may carry several Q records — an analyzer that has read a whole rack asks
     * once, in one conversation. Order is preserved and duplicates collapse, because answering the
     * same sample twice in one worklist would be read by the instrument as two orders.
     */
    public static List<String> sampleIdsIn(String transmission) {
        Set<String> ids = new LinkedHashSet<>();
        for (AstmRecord.Query query : queriesIn(transmission)) {
            if (!query.isEmpty()) {
                ids.add(query.sampleId());
            }
        }
        return List.copyOf(ids);
    }

    /** Every Q record in the transmission, parsed. */
    public static List<AstmRecord.Query> queriesIn(String transmission) {
        List<AstmRecord.Query> queries = new ArrayList<>();
        if (transmission == null || transmission.isEmpty()) {
            return queries;
        }
        for (String rawLine : transmission.split("\r")) {
            String line = AstmFrames.stripFrameNumber(rawLine.trim());
            if (line.isEmpty() || line.indexOf('|') < 0) {
                continue;
            }
            List<String> parts = AstmRecordParser.splitFields(line);
            String first = parts.get(0);
            if (first.isEmpty()) {
                continue;
            }
            // Record type is the last character of field 0, matching AstmParser: "Q", "1Q", "2Q".
            if (Character.toUpperCase(first.charAt(first.length() - 1)) == 'Q') {
                queries.add(AstmRecordParser.parseQuery(parts));
            }
        }
        return queries;
    }

    /** True when the transmission is a query rather than a result upload. */
    public static boolean isQuery(String transmission) {
        return !queriesIn(transmission).isEmpty();
    }
}
