package com.hms.interop.hl7;

import java.util.List;

/**
 * One segment — {@code MSH}, {@code PID}, {@code OBX} — and the values in it.
 *
 * <p>Fields are held as raw strings and split on demand rather than parsed into a tree up front.
 * Almost every field read from a real message is a plain value or its first component, and building
 * a three-level structure for every field of every segment to serve that is work done for nothing;
 * the structure is there, in {@link #component} and {@link #repetitions}, for the fields that use
 * it.
 *
 * <p><strong>MSH counts differently and this is the classic off-by-one in every HL7 codebase.</strong>
 * MSH-1 <em>is</em> the field separator, so splitting {@code MSH|^~\&|APP|...} on {@code |} yields
 * "MSH", "^~\&", "APP" — and MSH-2 is that second token while MSH-3 is the third. For every other
 * segment the first token is the name and field 1 is the second token. Both are handled here, once,
 * so that {@code segment.field(3)} means MSH-3 on an MSH and PID-3 on a PID without the caller
 * knowing which it holds.
 */
public record Hl7Segment(String name, List<String> rawFields, Hl7Encoding encoding) {

    public Hl7Segment {
        rawFields = List.copyOf(rawFields);
    }

    /** True for the header segment, which is the one that counts its fields differently. */
    public boolean isHeader() {
        return "MSH".equals(name);
    }

    /**
     * Field {@code n}, 1-based, or "" when the segment is too short.
     *
     * <p>Absent and empty are deliberately the same thing here. HL7 distinguishes them — {@code ""}
     * in a field means "delete this value" in an update message, where absent means "leave it
     * alone" — and that distinction matters to a system applying updates field by field, which this
     * is not. Collapsing them keeps every caller from writing a null check that would be wrong in
     * the same way each time.
     */
    public String field(int n) {
        if (n < 1) {
            return "";
        }
        if (isHeader() && n == 1) {
            return String.valueOf(encoding.field());
        }
        int index = isHeader() ? n - 1 : n;
        return index < rawFields.size() ? rawFields.get(index) : "";
    }

    /** Component {@code c} of field {@code n}, both 1-based; "" when either is absent. */
    public String component(int n, int c) {
        return componentOf(firstRepetition(field(n)), c);
    }

    /** Component {@code c} of subcomponent {@code s}, for the handful of fields that nest that far. */
    public String subcomponent(int n, int c, int s) {
        return part(component(n, c), encoding.subcomponent(), s);
    }

    /** Every repetition of field {@code n}, which is one entry for a field that does not repeat. */
    public List<String> repetitions(int n) {
        String value = field(n);
        return value.isEmpty() ? List.of() : List.of(split(value, encoding.repetition()));
    }

    /** How many fields the segment actually carries, for a caller reporting a truncated message. */
    public int fieldCount() {
        return isHeader() ? rawFields.size() : Math.max(0, rawFields.size() - 1);
    }

    private String firstRepetition(String value) {
        int at = value.indexOf(encoding.repetition());
        return at < 0 ? value : value.substring(0, at);
    }

    private String componentOf(String value, int c) {
        return part(value, encoding.component(), c);
    }

    private String part(String value, char separator, int index) {
        if (value.isEmpty() || index < 1) {
            return "";
        }
        String[] parts = split(value, separator);
        return index <= parts.length ? parts[index - 1] : "";
    }

    /**
     * Splits on a delimiter without regard for regular expressions.
     *
     * <p>{@code String.split} takes a regex, and three of the five HL7 delimiters — {@code ^},
     * {@code |} and {@code \} — are regex metacharacters. Quoting them at every call site is a
     * thing somebody eventually forgets, so the split happens here and takes a plain character.
     */
    static String[] split(String value, char delimiter) {
        List<String> parts = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == delimiter) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts.toArray(new String[0]);
    }
}
