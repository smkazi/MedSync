package com.hms.interop.hl7;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ER7, which is the half of the codec that decides whether the far end can read us.
 *
 * <p>Every value goes through {@link Er7Parser#escape}, and that is the entire discipline of
 * building HL7. A surname of "O^Brien" written unescaped does not corrupt the name — it moves the
 * rest of the segment one component to the left, and the receiving system files a valid-looking
 * message with the wrong data in every field after it. Nobody notices for a month.
 *
 * <p>So a field cannot be a bare string here. {@link #segment} takes {@link Field}, and the only
 * ways to make one are {@link #field} and {@link #components}, both of which escape. Passing a
 * value straight through is not a discipline anybody has to remember; it does not compile.
 */
public final class Er7Builder {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * One field, already escaped and composed.
     *
     * <p>A type rather than a string so that the escaping cannot be skipped by accident. It is the
     * whole reason this class is not three calls to {@code String.join}.
     */
    public record Field(String composed) {
    }

    private final Hl7Encoding encoding;
    private final List<String> segments = new ArrayList<>();

    public Er7Builder(Hl7Encoding encoding) {
        this.encoding = encoding;
    }

    public static String timestamp(Instant instant) {
        return STAMP.format(instant == null ? Instant.now() : instant);
    }

    /** A single value, escaped. */
    public Field field(String value) {
        return new Field(Er7Parser.escape(value, encoding));
    }

    /** Several components in one field, each escaped, joined by the component separator. */
    public Field components(String... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(encoding.component());
            }
            out.append(Er7Parser.escape(values[i], encoding));
        }
        return new Field(out.toString());
    }

    /**
     * The header.
     *
     * <p>MSH-1 and MSH-2 are written by {@link Hl7Encoding#mshPrefix()} rather than as fields,
     * because they are the delimiters themselves: passing them through the escaper would escape the
     * punctuation the message is written in.
     */
    public Er7Builder header(String sendingApplication, String sendingFacility,
                             String receivingApplication, String receivingFacility,
                             String messageType, String controlId, String processingId,
                             String version) {
        StringBuilder msh = new StringBuilder(encoding.mshPrefix());
        for (String value : List.of(sendingApplication, sendingFacility, receivingApplication,
                receivingFacility, timestamp(Instant.now()), "")) {
            msh.append(encoding.field()).append(Er7Parser.escape(value, encoding));
        }
        // MSH-9 is the one field here whose separator is structure rather than content.
        // "ADT^A04" is a type and a trigger event in two components, so escaping that caret --
        // which is what happens to every other value on this line -- produces "ADT\S\A04": a
        // single component with a literal caret in it, which a receiver routes to nothing. Split
        // and escape the parts, then join with a live separator.
        msh.append(encoding.field())
                .append(components(Hl7Segment.split(messageType, encoding.component())).composed());
        for (String value : List.of(controlId, processingId, version)) {
            msh.append(encoding.field()).append(Er7Parser.escape(value, encoding));
        }
        segments.add(msh.toString());
        return this;
    }

    /**
     * Any other segment: the name, then its fields in order.
     *
     * <p>An empty field is written as an empty field rather than skipped. Skipping would shift
     * everything after it by one, which is the same defect as an unescaped delimiter and is found
     * the same way — by somebody at the far end reading a date of birth out of the sex field.
     */
    public Er7Builder segment(String name, Field... fields) {
        StringBuilder segment = new StringBuilder(name);
        for (Field value : fields) {
            segment.append(encoding.field())
                    .append(value == null ? "" : value.composed());
        }
        segments.add(segment.toString());
        return this;
    }

    /**
     * The finished message.
     *
     * <p>Segments are separated by a carriage return and the message ends with one. The trailing
     * separator is not decoration: a receiver reading line by line drops the last segment without
     * it, which on a result message is the last result.
     */
    public String build() {
        return String.join("\r", segments) + "\r";
    }
}
