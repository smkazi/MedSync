package com.hms.interop.hl7;

/**
 * The delimiters a message declares about itself.
 *
 * <p>HL7 v2 does not fix its own punctuation. Every message carries it: {@code MSH-1} is the field
 * separator — whatever character happens to sit in position 4 of the message — and {@code MSH-2} is
 * the remaining four, in a fixed order. Almost every sender in the world uses {@code |} and
 * {@code ^~\&}, and a parser that hard-codes them works until the one interface that does not,
 * which is why this is read rather than assumed.
 *
 * @param field        separates fields, from MSH-1
 * @param component    separates components within a field
 * @param repetition   separates repetitions of a whole field
 * @param escape       introduces an escape sequence
 * @param subcomponent separates subcomponents within a component
 */
public record Hl7Encoding(char field, char component, char repetition, char escape,
                          char subcomponent) {

    /** What all but a handful of senders use. */
    public static final Hl7Encoding DEFAULT = new Hl7Encoding('|', '^', '~', '\\', '&');

    /**
     * Reads the delimiters out of an MSH segment.
     *
     * <p>Tolerates a short MSH-2: some senders send only {@code ^~\} and a few send only
     * {@code ^~}. Missing characters fall back to the usual ones rather than failing the message,
     * because a sender that omits the subcomponent separator is a sender that never uses
     * subcomponents, and refusing their traffic over a character they do not need would be the
     * parser being right about nothing.
     */
    public static Hl7Encoding from(String mshSegment) {
        if (mshSegment == null || mshSegment.length() < 4) {
            return DEFAULT;
        }
        char field = mshSegment.charAt(3);
        String encodingCharacters = mshSegment.substring(4);
        int end = encodingCharacters.indexOf(field);
        if (end >= 0) {
            encodingCharacters = encodingCharacters.substring(0, end);
        }
        return new Hl7Encoding(field,
                charAt(encodingCharacters, 0, DEFAULT.component()),
                charAt(encodingCharacters, 1, DEFAULT.repetition()),
                charAt(encodingCharacters, 2, DEFAULT.escape()),
                charAt(encodingCharacters, 3, DEFAULT.subcomponent()));
    }

    /** The five characters as they appear in MSH-1 and MSH-2, for writing a message out. */
    public String mshPrefix() {
        return "MSH" + field + component + repetition + escape + subcomponent;
    }

    private static char charAt(String value, int index, char fallback) {
        return index < value.length() ? value.charAt(index) : fallback;
    }
}
