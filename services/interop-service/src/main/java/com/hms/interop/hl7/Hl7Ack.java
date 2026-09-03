package com.hms.interop.hl7;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds the acknowledgement, which is the only thing the sender actually waits for.
 *
 * <p>Three codes and they are not interchangeable. {@code AA} means accepted. {@code AE} means the
 * message arrived intact and the platform will not act on it — a patient it cannot match, a type it
 * does not handle — and the sender should not send it again unchanged. {@code AR} means the message
 * was rejected without being understood, and re-sending it might work. Getting these the wrong way
 * round is how an interface either loses messages silently or retries a poisoned one for ever.
 *
 * <p>Sender and receiver are swapped: an acknowledgement is addressed back to whoever sent the
 * message, and one that echoes the original addressing is discarded by anything checking.
 */
public final class Hl7Ack {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public enum Code {
        /** Accepted. */
        AA,
        /** Understood and refused: do not send this again unchanged. */
        AE,
        /** Not understood: re-sending may work. */
        AR
    }

    private Hl7Ack() {
    }

    /**
     * An acknowledgement for a message that parsed.
     *
     * @param text an error description for AE and AR, ignored for AA
     */
    public static String of(Hl7Message message, Code code, String text, String application,
                            String facility) {
        Hl7Encoding encoding = message.encoding();
        String controlId = message.controlId();
        return build(encoding, application, facility,
                message.sendingApplication(), message.sendingFacility(),
                message.processingId(), message.versionId(), code, controlId, text);
    }

    /**
     * An acknowledgement for a message that did not parse.
     *
     * <p>Sent with an empty MSA-2, because the control id lives in the header this message did not
     * have. A sender matching on it will not find the message it sent, which is correct: it never
     * arrived as a message.
     */
    public static String rejected(String text, String application, String facility) {
        return build(Hl7Encoding.DEFAULT, application, facility, "", "", "P", "2.5",
                Code.AR, "", text);
    }

    private static String build(Hl7Encoding encoding, String sendingApp, String sendingFacility,
                                String receivingApp, String receivingFacility, String processingId,
                                String version, Code code, String controlId, String text) {
        char f = encoding.field();
        String stamp = STAMP.format(Instant.now());
        // The acknowledgement carries its own control id, distinct from the one it echoes: they are
        // two different messages and a receiver logging both wants to tell them apart.
        String ackControlId = "ACK" + stamp + Integer.toHexString(System.identityHashCode(text));

        StringBuilder msh = new StringBuilder(encoding.mshPrefix());
        append(msh, f, sendingApp);
        append(msh, f, sendingFacility);
        append(msh, f, receivingApp);
        append(msh, f, receivingFacility);
        append(msh, f, stamp);
        append(msh, f, "");
        append(msh, f, "ACK");
        append(msh, f, ackControlId);
        append(msh, f, processingId.isEmpty() ? "P" : processingId);
        append(msh, f, version.isEmpty() ? "2.5" : version);

        StringBuilder msa = new StringBuilder("MSA");
        append(msa, f, code.name());
        append(msa, f, controlId);
        if (text != null && !text.isBlank()) {
            // One line: a newline inside a field would be read as the end of the segment by the
            // receiver, which turns an explanation into a malformed acknowledgement.
            append(msa, f, Er7Parser.escape(text.replaceAll("[\\r\\n]+", " ").trim(), encoding));
        }

        return msh + "\r" + msa + "\r";
    }

    private static void append(StringBuilder segment, char fieldSeparator, String value) {
        segment.append(fieldSeparator).append(value == null ? "" : value);
    }
}
