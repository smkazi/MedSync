package com.hms.laboratory.device.astm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateful ASTM parser: accumulates records between the H (header) and L (terminator) records and
 * emits one {@link AstmRecord.Sample} per complete transmission.
 *
 * <p>Ported from the {@code ASTMParser} class in {@code parsers/astm_parser.py}
 * (smkazi/HaematologyIS). Two behaviours from the original are load-bearing and kept:
 *
 * <ul>
 *   <li>A frame may contain several {@code \r}-separated records, so frames are split, not assumed
 *       to be one record each.</li>
 *   <li>Identity is merged across the P and O records. A Poch-100i sends an empty {@code P|1} and
 *       puts the name and sex in the O record, so taking either record alone loses the patient.</li>
 * </ul>
 *
 * <p>Unlike the original, this parser does not consult reference ranges: deriving out-of-range
 * flags needs the lab's configured ranges, which live in the database, so it happens in the
 * ingest service. The parser stays pure and therefore testable without a database.
 *
 * <p>Instances are not thread-safe — one parser belongs to one analyzer connection.
 */
public class AstmParser {

    private static final Logger log = LoggerFactory.getLogger(AstmParser.class);

    private final Consumer<AstmRecord.Sample> onSample;

    private AstmRecord.Header header;
    private AstmRecord.Patient patient = AstmRecord.Patient.empty();
    private AstmRecord.Order order = AstmRecord.Order.empty();
    private List<AstmRecord.Result> results = new ArrayList<>();
    private List<AstmRecord.Comment> comments = new ArrayList<>();
    private final List<String> rawLines = new ArrayList<>();

    public AstmParser(Consumer<AstmRecord.Sample> onSample) {
        this.onSample = onSample;
    }

    /**
     * Feeds one decoded ASTM frame. The frame may hold multiple records separated by carriage
     * returns; each is processed in order.
     */
    public void feedFrame(String frame) {
        if (frame == null || frame.isEmpty()) {
            return;
        }
        for (String rawLine : frame.split("\r")) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String line = AstmFrames.stripFrameNumber(trimmed);
            rawLines.add(line);
            processLine(line);
        }
    }

    /** Feeds a whole transmission at once, for a captured or replayed message. */
    public void feedAll(String transmission) {
        feedFrame(transmission);
    }

    /** The record lines seen since the last reset, retained so an ingest can be audited. */
    public List<String> rawLines() {
        return List.copyOf(rawLines);
    }

    private void processLine(String line) {
        if (line.indexOf('|') < 0) {
            return;
        }
        List<String> parts = AstmRecordParser.splitFields(line);
        String first = parts.get(0);
        if (first.isEmpty()) {
            return;
        }
        // The record type is the last character of field 0 ("1H", "P", "2R" and so on).
        char type = Character.toUpperCase(first.charAt(first.length() - 1));

        switch (type) {
            case 'H' -> {
                reset();
                header = AstmRecordParser.parseHeader(parts);
            }
            case 'P' -> patient = AstmRecordParser.parsePatient(parts);
            case 'O' -> order = AstmRecordParser.parseOrder(parts);
            case 'R' -> {
                AstmRecord.Result result = AstmRecordParser.parseResult(parts);
                if (!result.parameter().isBlank()) {
                    results.add(result);
                }
            }
            case 'C' -> comments.add(AstmRecordParser.parseComment(parts));
            case 'L' -> {
                emit();
                reset();
            }
            default -> log.debug("Ignoring unsupported ASTM record type '{}'", type);
        }
    }

    /**
     * Emits the accumulated sample.
     *
     * <p>A transmission with neither results nor an order carries nothing worth filing and is
     * dropped, which is what keeps a bare {@code H}/{@code L} keep-alive from creating an empty
     * specimen.
     */
    private void emit() {
        if (results.isEmpty() && order.isEmpty()) {
            return;
        }
        AstmRecord.Sample sample = new AstmRecord.Sample(patient, order, List.copyOf(results),
                List.copyOf(comments));
        log.info("ASTM sample complete: id={} name={} results={}", sample.resolvedSampleId(),
                sample.resolvedName(), results.size());
        if (onSample != null) {
            onSample.accept(sample);
        }
    }

    private void reset() {
        header = null;
        patient = AstmRecord.Patient.empty();
        order = AstmRecord.Order.empty();
        results = new ArrayList<>();
        comments = new ArrayList<>();
    }

    /**
     * Parses a complete transmission and returns every sample in it, for replaying a captured
     * message rather than driving a live connection.
     */
    public static List<AstmRecord.Sample> parseAll(String transmission) {
        List<AstmRecord.Sample> samples = new ArrayList<>();
        AstmParser parser = new AstmParser(samples::add);
        parser.feedAll(transmission);
        // A transmission whose terminator record is missing still holds a usable sample.
        parser.emit();
        return samples;
    }
}
