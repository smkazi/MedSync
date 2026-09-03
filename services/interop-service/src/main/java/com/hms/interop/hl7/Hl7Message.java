package com.hms.interop.hl7;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * A parsed HL7 v2 message: its segments, and the handful of header values everything asks for.
 *
 * <p>Immutable, and holds no opinion about what the message means. Deciding that an {@code ADT^A01}
 * admits somebody is the job of whatever routes it; this type's job is to answer "what type is it,
 * who sent it, and what is in PID-3" without the caller counting pipes.
 */
public record Hl7Message(List<Hl7Segment> segments, Hl7Encoding encoding) {

    public Hl7Message {
        segments = List.copyOf(segments);
    }

    /** The first segment with this name, which is what a caller wants for MSH, PID or PV1. */
    public Optional<Hl7Segment> segment(String name) {
        return segments.stream().filter(segment -> name.equals(segment.name())).findFirst();
    }

    /** Every segment with this name — OBX and NTE repeat, and reading only the first loses results. */
    public List<Hl7Segment> allSegments(String name) {
        return segments.stream().filter(segment -> name.equals(segment.name())).toList();
    }

    public Optional<Hl7Segment> header() {
        return segment("MSH");
    }

    private String headerField(int n) {
        return header().map(segment -> segment.field(n)).orElse("");
    }

    public String sendingApplication() {
        return headerField(3);
    }

    public String sendingFacility() {
        return headerField(4);
    }

    public String receivingApplication() {
        return headerField(5);
    }

    public String receivingFacility() {
        return headerField(6);
    }

    /**
     * The message type as {@code ADT^A01}.
     *
     * <p>MSH-9 has three components — type, trigger event, and a structure name that many senders
     * omit. Type and trigger together are what routing needs, and joining them here means a caller
     * never has to decide whether to compare against "ADT" or "ADT^A01".
     */
    public String messageType() {
        Hl7Segment msh = header().orElse(null);
        if (msh == null) {
            return "";
        }
        String type = msh.component(9, 1);
        String trigger = msh.component(9, 2);
        return trigger.isEmpty() ? type : type + "^" + trigger;
    }

    /** The sender's own id for this message, echoed in the acknowledgement so they can match it. */
    public String controlId() {
        return headerField(10);
    }

    /** {@code P} for production, {@code T} for test, {@code D} for debug. */
    public String processingId() {
        return header().map(segment -> segment.component(11, 1)).orElse("");
    }

    public String versionId() {
        return header().map(segment -> segment.component(12, 1)).orElse("");
    }

    /** MSH-7, the sender's clock. Empty when absent or unparseable rather than throwing. */
    public Optional<Instant> messageDateTime() {
        return parseTimestamp(headerField(7));
    }

    /**
     * Reads an HL7 timestamp: {@code YYYYMMDDHHMMSS} to any precision, with an optional fraction
     * and offset.
     *
     * <p>Interpreted as UTC when the sender gives no offset. That is a documented assumption rather
     * than a correct one — a message stamped 0930 with no zone is 0930 wherever the sender is — but
     * inventing a local zone for a stranger's clock would be worse, and the raw value is kept
     * beside the parsed one so nothing is lost.
     */
    public static Optional<Instant> parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.trim();
        ZoneOffset offset = ZoneOffset.UTC;
        int sign = Math.max(text.indexOf('+', 8), text.indexOf('-', 8));
        if (sign > 0 && text.length() >= sign + 5) {
            try {
                offset = ZoneOffset.of(text.substring(sign));
            } catch (RuntimeException ex) {
                offset = ZoneOffset.UTC;
            }
            text = text.substring(0, sign);
        }
        int dot = text.indexOf('.');
        if (dot > 0) {
            text = text.substring(0, dot);
        }
        // Pad to seconds: a date alone is midnight, an hour alone is on the hour.
        String padded = (text + "00000000000000").substring(0, 14);
        try {
            return Optional.of(LocalDateTime
                    .parse(padded, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .toInstant(offset));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }
}
