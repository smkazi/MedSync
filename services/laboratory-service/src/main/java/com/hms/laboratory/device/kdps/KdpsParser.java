package com.hms.laboratory.device.kdps;

import com.hms.laboratory.device.astm.Histogram;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes a Sysmex K-DPS binary transmission into samples with numeric results and histograms.
 *
 * <p>Ported from {@code parsers/kdps_parser.py} (smkazi/HaematologyIS). K-DPS is a fixed-offset
 * binary protocol, not a delimited one, which drives two rules that are easy to get wrong and are
 * preserved deliberately:
 *
 * <ul>
 *   <li>A payload legitimately contains 0x02/0x03 bytes, so framing may only be stripped at the
 *       known ends of a frame — never by scanning for a control byte.</li>
 *   <li>Frames arrive already delimited by the transport, so a transmission must not be re-split.</li>
 * </ul>
 */
public final class KdpsParser {

    private static final Logger log = LoggerFactory.getLogger(KdpsParser.class);

    private static final int STX = 0x02;
    private static final int ETX = 0x03;
    private static final int ETB = 0x17;

    /** Channel layout of the 215-byte histogram frame: {start, end-exclusive} per cell group. */
    private static final Map<String, int[]> HISTOGRAM_SEGMENTS = new LinkedHashMap<>(Map.of(
            "WBC", new int[] {0, 50},
            "PLT", new int[] {50, 100},
            "RBC", new int[] {100, 140}));

    /**
     * Cell-volume window (fL) each curve spans on the analyzer's printed axis, matching the
     * discriminator positions the report draws: WBC LD/T1/T2/UD at 35/110/160/280 over 0–300;
     * RBC RL/RU 25/200 over 0–250; PLT PL/PU 3/25 over 0–30.
     */
    private static final Map<String, double[]> VOLUME_WINDOW = Map.of(
            "WBC", new double[] {0.0, 300.0},
            "RBC", new double[] {0.0, 250.0},
            "PLT", new double[] {0.0, 30.0});

    /** The two trailing 100s closing the histogram block are a fixed marker, not measured data. */
    private static final int END_MARKER_LENGTH = 2;

    /**
     * Fixed binary signature near the end of a genuine histogram frame.
     *
     * <p>It contains NUL bytes, so it can never appear in the ASCII-hex D2/D3 graphic records —
     * which is exactly what separates a real binary histogram from a text frame of small numbers
     * that would otherwise look like channel values.
     */
    private static final byte[] HISTOGRAM_TRAILER = {0x00, 0x00, 0x55, 0x00, 0x00};

    /**
     * Offset of the numeric CBC row within the header payload, after the leading STX is stripped.
     *
     * <p>In K-DPS-only mode the analyzer sends no ASTM records at all: the CBC is a row of
     * fixed-width ASCII fields inside the header frame, and this offset is the only way to find it.
     */
    private static final int RESULTS_OFFSET = 81;

    private static final int RESULT_FIELD_WIDTH = 5;

    /**
     * Offsets tried when locating the CBC row, primary first. Firmware revisions shift the row by
     * a byte or two, so a small search window around the nominal offset is required.
     */
    private static final int[] RESULT_OFFSET_CANDIDATES = {RESULTS_OFFSET, 82, 80, 83, 79, 84, 78, 85, 77};

    /**
     * The CBC parameters in the fixed order the KX-21 / XP-100 transmits them. Fields beyond P-LCR
     * are analyzer-internal discriminator values and are not reported.
     */
    private static final List<String[]> KDPS_RESULTS = List.of(
            new String[] {"WBC", "10^3/uL"}, new String[] {"RBC", "10^6/uL"},
            new String[] {"HGB", "g/dL"}, new String[] {"HCT", "%"},
            new String[] {"MCV", "fL"}, new String[] {"MCH", "pg"},
            new String[] {"MCHC", "g/dL"}, new String[] {"PLT", "10^3/uL"},
            new String[] {"LYM%", "%"}, new String[] {"MXD%", "%"}, new String[] {"NEUT%", "%"},
            new String[] {"LYM#", "10^3/uL"}, new String[] {"MXD#", "10^3/uL"},
            new String[] {"NEUT#", "10^3/uL"},
            new String[] {"RDW-CV", "%"}, new String[] {"RDW-SD", "fL"},
            new String[] {"PDW", "fL"}, new String[] {"MPV", "fL"}, new String[] {"P-LCR", "%"});

    /** The ASCII date/time stamp that opens a header frame, e.g. {@code 26/ 9/01 14:32}. */
    private static final Pattern HEADER_STAMP =
            Pattern.compile("\\s*(\\d\\d)/\\s?(\\d\\d?)/(\\d\\d)\\s*(\\d\\d):(\\d\\d) ?(.*)");

    private static final Pattern HEADER_STAMP_PREFIX = Pattern.compile("\\s*\\d\\d/\\s?\\d\\d?/\\d\\d.*");

    /** The identity field is a fixed 12-character, space-padded field. */
    private static final int NAME_FIELD_WIDTH = 12;

    private static final Pattern NON_PRINTABLE = Pattern.compile("[^\\x20-\\x7e]");

    private static final Pattern HEX_CAPTURE = Pattern.compile("hex=([0-9a-fA-F]+)");

    private KdpsParser() {
    }

    /**
     * Removes the leading STX and any trailing ETX/ETB plus checksum from one frame.
     *
     * <p>Only the known ends are trimmed. Scanning the payload for a control byte would corrupt
     * binary channel data that legitimately contains 0x02 or 0x03.
     */
    static byte[] stripFraming(byte[] frame) {
        byte[] body = frame;
        if (body.length > 0 && (body[0] & 0xFF) == STX) {
            body = Arrays.copyOfRange(body, 1, body.length);
        }
        for (int terminator : new int[] {ETX, ETB}) {
            int index = lastIndexOf(body, (byte) terminator);
            // A terminator only counts when it sits where one belongs: at the end, before the
            // checksum and line ending (at most 4 trailing bytes).
            if (index != -1 && index >= body.length - 5) {
                return Arrays.copyOfRange(body, 0, index);
            }
        }
        return body;
    }

    /**
     * Extracts {@code name}, timestamp and the numeric CBC row from a header frame.
     *
     * @return the parsed header, or null when the stamp is unreadable
     */
    static Header parseHeader(byte[] frame) {
        byte[] body = frame;
        if (body.length > 0 && (body[0] & 0xFF) == STX) {
            body = Arrays.copyOfRange(body, 1, body.length);
        }
        String text = new String(body, StandardCharsets.ISO_8859_1);
        Matcher stamp = HEADER_STAMP.matcher(text);
        if (!stamp.lookingAt()) {
            return null;
        }
        String yy = stamp.group(1);
        int month = Integer.parseInt(stamp.group(2));
        int day = Integer.parseInt(stamp.group(3));
        String hh = stamp.group(4);
        String mi = stamp.group(5);
        String rest = stamp.group(6);

        // The name is a fixed 12-character field followed by a graphic-mode marker byte and then
        // the binary block, so it is cut by width - not by looking for a separator.
        String printable = NON_PRINTABLE.split(rest, 2)[0];
        String name = printable.length() > NAME_FIELD_WIDTH
                ? printable.substring(0, NAME_FIELD_WIDTH).trim()
                : printable.trim();

        String measuredAt = "20%s-%02d-%02d %s:%s".formatted(yy, month, day, hh, mi);
        String dateKey = "20%s%02d%02d%s%s".formatted(yy, month, day, hh, mi);
        return new Header(name, measuredAt, dateKey, parseResults(body));
    }

    /**
     * Reads the fixed-width CBC row, aligning to the nominal offset and falling back to nearby ones.
     *
     * <p>Alignment is confirmed by requiring WBC, RBC and HGB to parse as plain numbers — without
     * that check a misaligned window yields plausible-looking nonsense.
     */
    static List<KdpsSample.KdpsResult> parseResults(byte[] frame) {
        int span = RESULT_FIELD_WIDTH * KDPS_RESULTS.size();
        for (int offset : RESULT_OFFSET_CANDIDATES) {
            if (offset >= frame.length) {
                continue;
            }
            int end = Math.min(offset + span, frame.length);
            String segment = new String(frame, offset, end - offset, StandardCharsets.ISO_8859_1);
            List<String> fields = new ArrayList<>();
            for (int i = 0; i < segment.length(); i += RESULT_FIELD_WIDTH) {
                fields.add(segment.substring(i, Math.min(i + RESULT_FIELD_WIDTH, segment.length())));
            }
            if (fields.size() < 8) {
                continue;
            }
            if (numericField(fields.get(0)) == null || numericField(fields.get(1)) == null
                    || numericField(fields.get(2)) == null) {
                continue;
            }
            List<KdpsSample.KdpsResult> results = new ArrayList<>();
            for (int i = 0; i < Math.min(fields.size(), KDPS_RESULTS.size()); i++) {
                String value = numericField(fields.get(i));
                if (value != null) {
                    results.add(new KdpsSample.KdpsResult(KDPS_RESULTS.get(i)[0], value,
                            KDPS_RESULTS.get(i)[1]));
                }
            }
            return results;
        }
        return List.of();
    }

    /**
     * One fixed-width result field.
     *
     * @return the numeric text, or null when the analyzer marked the value unmeasurable
     *         ({@code *0000}, {@code ----}, {@code ******}, {@code +++})
     */
    static String numericField(String field) {
        String value = field == null ? "" : field.trim();
        if (value.isEmpty() || value.contains("*") || value.contains("+")) {
            return null;
        }
        boolean allDashes = true;
        for (char c : value.toCharArray()) {
            if (c != '-') {
                allDashes = false;
                break;
            }
        }
        if (allDashes) {
            return null;
        }
        try {
            Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
        return value;
    }

    /**
     * A header frame opens with the ASCII {@code YY/ M/DD HH:MM} stamp.
     *
     * <p>Tolerates a leading STX, matching {@link #parseHeader}: a caller may hand over a frame
     * whose transport framing has already been stripped, or one that still carries it.
     */
    static boolean looksLikeHeader(byte[] frame) {
        int start = (frame.length > 0 && (frame[0] & 0xFF) == STX) ? 1 : 0;
        int length = Math.min(16, frame.length - start);
        if (length <= 0) {
            return false;
        }
        String head = new String(frame, start, length, StandardCharsets.ISO_8859_1);
        return HEADER_STAMP_PREFIX.matcher(head).lookingAt();
    }

    /**
     * Whether this is a real binary histogram frame — and not one of the ASCII-hex D1/D2/D3 graphic
     * records, which are all-printable text whose bytes would otherwise pass for small channel
     * values.
     *
     * <p>A genuine frame is roughly 215 bytes and carries the trailer signature. The fallback path
     * (a firmware variant without the exact trailer) requires a NUL byte, which rules out
     * ASCII-hex text, plus a body that is overwhelmingly small channel values.
     */
    static boolean looksLikeHistogram(byte[] frame) {
        if (frame.length < 150 || frame.length > 260) {
            return false;
        }
        byte[] tail = Arrays.copyOfRange(frame, Math.max(0, frame.length - 16), frame.length);
        if (indexOf(tail, HISTOGRAM_TRAILER) != -1) {
            return true;
        }
        if (lastIndexOf(frame, (byte) 0x00) == -1) {
            return false;
        }
        int small = 0;
        int bodyLength = Math.min(140, frame.length);
        for (int i = 0; i < bodyLength; i++) {
            if ((frame[i] & 0xFF) <= 100) {
                small++;
            }
        }
        return small >= 130;
    }

    /**
     * Splits a histogram frame into its per-group curves. A curve whose channels are all zero means
     * the parameter was not measured, and is omitted rather than plotted as a flat line.
     */
    static Map<String, Histogram> parseHistogramFrame(byte[] frame) {
        Map<String, Histogram> curves = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : HISTOGRAM_SEGMENTS.entrySet()) {
            String group = entry.getKey();
            int start = entry.getValue()[0];
            int end = Math.min(entry.getValue()[1], frame.length);
            if (start >= end) {
                continue;
            }
            List<Double> channels = new ArrayList<>();
            for (int i = start; i < end; i++) {
                channels.add((double) (frame[i] & 0xFF));
            }
            if ("RBC".equals(group) && channels.size() > END_MARKER_LENGTH
                    && channels.get(channels.size() - 1) == 100.0
                    && channels.get(channels.size() - 2) == 100.0) {
                channels = channels.subList(0, channels.size() - END_MARKER_LENGTH);
            }
            if (channels.isEmpty() || channels.stream().allMatch(value -> value == 0.0)) {
                continue;
            }
            curves.put(group, normaliseCurve(channels, group));
        }
        return curves;
    }

    /** Places 0–100 channel frequencies on the group's real volume axis. */
    private static Histogram normaliseCurve(List<Double> channels, String group) {
        int count = channels.size();
        double[] window = VOLUME_WINDOW.getOrDefault(group, new double[] {0.0, Math.max(1, count - 1)});
        if (count > 1) {
            double step = (window[1] - window[0]) / (count - 1);
            List<Double> x = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                x.add(Math.round((window[0] + i * step) * 100.0) / 100.0);
            }
            return new Histogram(List.copyOf(x), List.copyOf(channels), Histogram.VOLUME_AXIS);
        }
        return new Histogram(List.of(0.0), List.copyOf(channels), Histogram.CHANNEL_AXIS);
    }

    /**
     * Decodes a sequence of transport-delimited frames into samples.
     *
     * <p>Header and histogram frames are paired in order of appearance. A histogram frame with no
     * readable header still yields its curves with empty identity fields, so it can be matched to
     * a result by position or time rather than being discarded.
     */
    public static List<KdpsSample> parseFrames(List<byte[]> frames) {
        List<KdpsSample> samples = new ArrayList<>();
        Header pendingHeader = null;
        for (byte[] rawFrame : frames) {
            byte[] frame = stripFraming(rawFrame);
            if (looksLikeHeader(frame)) {
                pendingHeader = parseHeader(frame);
                continue;
            }
            if (looksLikeHistogram(frame)) {
                Map<String, Histogram> curves = parseHistogramFrame(frame);
                if (curves.isEmpty()) {
                    continue;
                }
                Header header = pendingHeader;
                samples.add(new KdpsSample(
                        header == null ? "" : header.identifier(),
                        header == null ? "" : header.measuredAt(),
                        header == null ? "" : header.dateKey(),
                        header == null ? List.of() : header.results(),
                        curves));
                pendingHeader = null;
            }
        }
        log.info("Decoded {} K-DPS sample(s) from {} frame(s)", samples.size(), frames.size());
        return samples;
    }

    /** Decodes a single frame's bytes. */
    public static List<KdpsSample> parse(byte[] data) {
        return parseFrames(List.of(data));
    }

    /**
     * Decodes from a raw-capture log, where each received frame is stored on its own line as
     * {@code ... hex=<hexdigits> | ascii=...}. Each hex payload is one whole frame and must not be
     * re-split.
     */
    public static List<KdpsSample> parseCaptureText(String text) {
        List<byte[]> frames = new ArrayList<>();
        Matcher matcher = HEX_CAPTURE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            try {
                frames.add(hexToBytes(matcher.group(1)));
            } catch (IllegalArgumentException ex) {
                log.debug("Skipping unparseable hex frame in capture");
            }
        }
        return parseFrames(frames);
    }

    static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex payload has an odd number of digits");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static int lastIndexOf(byte[] haystack, byte needle) {
        for (int i = haystack.length - 1; i >= 0; i--) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The decoded header frame. */
    record Header(String identifier, String measuredAt, String dateKey, List<KdpsSample.KdpsResult> results) {
    }
}
