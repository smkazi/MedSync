package com.hms.laboratory.device.astm;

/**
 * Frame-level helpers for ASTM E1394 / LIS2-A2 transmissions.
 *
 * <p>Ported from {@code parsers/astm_parser.py} in smkazi/HaematologyIS, which was hardened
 * against real Sysmex analyzer output (Poch-100i, XP-100, XP-300, XQ-320, XN-330).
 */
public final class AstmFrames {

    private AstmFrames() {
    }

    /**
     * Verifies the ASTM checksum: the sum of the bytes between STX and ETX, modulo 256, expressed
     * as two hex characters at the end of the frame.
     *
     * <p>Deliberately permissive — a frame too short to carry a checksum, or one whose trailing
     * characters are not hex, is accepted rather than discarded. Analyzers in the field send both,
     * and dropping a frame loses a patient result; the original implementation made the same
     * choice and the behaviour is preserved here on purpose.
     *
     * @return true when the checksum matches, or when it cannot be evaluated
     */
    public static boolean verifyChecksum(byte[] frame) {
        if (frame == null || frame.length < 3) {
            return true;
        }
        try {
            String trailer = new String(frame, frame.length - 2, 2, java.nio.charset.StandardCharsets.US_ASCII);
            int expected = Integer.parseInt(trailer, 16);
            int calculated = 0;
            for (int i = 0; i < frame.length - 2; i++) {
                calculated += frame[i] & 0xFF;
            }
            return (calculated % 256) == expected;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Removes the leading frame sequence number (1–7) that ASTM puts before the record type. */
    public static String stripFrameNumber(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        return Character.isDigit(line.charAt(0)) ? line.substring(1) : line;
    }
}
