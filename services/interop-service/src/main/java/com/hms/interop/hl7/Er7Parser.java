package com.hms.interop.hl7;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses ER7 — the pipe-delimited encoding almost every HL7 v2 interface actually speaks.
 *
 * <p>Deliberately permissive about everything except the one thing that makes a message a message.
 * Real interfaces send segments separated by {@code \r}, by {@code \n}, or by {@code \r\n}
 * depending on what wrote them; they send trailing blank lines; they pad. None of that is worth
 * refusing traffic over, and an engine that did would spend its life being right while the hospital
 * telephoned the ward for results.
 *
 * <p>What it will not do is guess. A message with no MSH is rejected rather than parsed into
 * something plausible: MSH carries the delimiters, the type, and the control id the acknowledgement
 * has to echo, so without it there is nothing to be permissive with — and filing a message under a
 * type nobody sent is the kind of helpfulness that ends up in the wrong patient's record.
 */
public final class Er7Parser {

    private Er7Parser() {
    }

    /** Thrown for a message that cannot be parsed at all, which the caller turns into an AR. */
    public static class Hl7ParseException extends RuntimeException {

        public Hl7ParseException(String message) {
            super(message);
        }
    }

    public static Hl7Message parse(String text) {
        if (text == null || text.isBlank()) {
            throw new Hl7ParseException("The message is empty");
        }

        List<String> lines = splitSegments(text);
        if (lines.isEmpty()) {
            throw new Hl7ParseException("The message contains no segments");
        }

        String first = lines.get(0);
        if (!first.startsWith("MSH")) {
            // Named rather than described: an interface engineer reading this at three in the
            // morning wants to know what arrived, and "FHS" or "BHS" tells them somebody sent a
            // batch to an endpoint expecting single messages.
            throw new Hl7ParseException("The first segment is '%s', not MSH — a message must begin "
                    .formatted(first.length() >= 3 ? first.substring(0, 3) : first)
                    + "with its header, which carries the delimiters and the control id");
        }
        if (first.length() < 8) {
            throw new Hl7ParseException("The MSH segment is truncated");
        }

        Hl7Encoding encoding = Hl7Encoding.from(first);
        List<Hl7Segment> segments = new ArrayList<>(lines.size());
        for (String line : lines) {
            List<String> fields = List.of(Hl7Segment.split(line, encoding.field()));
            String name = fields.get(0);
            // A stray delimiter or a wrapped line can produce something that is not a segment name.
            // Kept rather than dropped, with its name as it arrived: the raw message is stored
            // whole anyway, and a segment silently discarded here is a result silently discarded.
            segments.add(new Hl7Segment(name.length() > 3 ? name.substring(0, 3) : name,
                    fields, encoding));
        }
        return new Hl7Message(segments, encoding);
    }

    /**
     * Splits into segments on any of the line endings a real sender uses, dropping blanks.
     *
     * <p>The standard says {@code \r}. Windows-hosted senders send {@code \r\n}, and more than one
     * system that writes messages to a file and posts the file sends {@code \n} alone.
     */
    private static List<String> splitSegments(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Decodes the escape sequences HL7 uses to carry its own delimiters inside a value.
     *
     * <p>Needed wherever a value reaches a person or another format: a surname of "O\S\Brien" is
     * "O^Brien", and writing the escape into a chart is a defect somebody reports as a corrupted
     * name. {@code \X..\} (hexadecimal) and the formatting escapes {@code \H\} and {@code \N\} are
     * handled by dropping them — they are display instructions, not content, and a highlight-on
     * marker rendered literally is worse than one ignored.
     */
    public static String unescape(String value, Hl7Encoding encoding) {
        if (value == null || value.indexOf(encoding.escape()) < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != encoding.escape()) {
                out.append(c);
                i++;
                continue;
            }
            int end = value.indexOf(encoding.escape(), i + 1);
            if (end < 0) {
                // An unterminated escape is data, not a sequence. Kept verbatim.
                out.append(value.substring(i));
                break;
            }
            String code = value.substring(i + 1, end);
            switch (code.isEmpty() ? ' ' : code.charAt(0)) {
                case 'F' -> out.append(encoding.field());
                case 'S' -> out.append(encoding.component());
                case 'T' -> out.append(encoding.subcomponent());
                case 'R' -> out.append(encoding.repetition());
                case 'E' -> out.append(encoding.escape());
                case 'X' -> appendHex(out, code.substring(1));
                case 'H', 'N' -> { /* display instructions: dropped, not rendered */ }
                default -> { /* an escape nobody here knows: dropped rather than shown raw */ }
            }
            i = end + 1;
        }
        return out.toString();
    }

    private static void appendHex(StringBuilder out, String hex) {
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            try {
                out.append((char) Integer.parseInt(hex.substring(i, i + 2), 16));
            } catch (NumberFormatException ex) {
                return;
            }
        }
    }

    /** The inverse, for writing a value that may contain a delimiter. */
    public static String escape(String value, Hl7Encoding encoding) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            // The escape character first: escaping it after the others would double-escape what
            // they just wrote.
            if (c == encoding.escape()) {
                out.append(encoding.escape()).append('E').append(encoding.escape());
            } else if (c == encoding.field()) {
                out.append(encoding.escape()).append('F').append(encoding.escape());
            } else if (c == encoding.component()) {
                out.append(encoding.escape()).append('S').append(encoding.escape());
            } else if (c == encoding.subcomponent()) {
                out.append(encoding.escape()).append('T').append(encoding.escape());
            } else if (c == encoding.repetition()) {
                out.append(encoding.escape()).append('R').append(encoding.escape());
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
