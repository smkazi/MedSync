package com.hms.laboratory.label;

import com.hms.common.error.BadRequestException;

/**
 * Code 128 (subset B) encoder.
 *
 * <p>Written out rather than pulled from a library, for the same reason {@code HistogramChart} in
 * the web app draws its own polyline: this is a lookup table and two loops, and a dependency in the
 * path that prints tube labels would add a supply-chain surface for nothing.
 *
 * <p><strong>Code 128, not QR.</strong> A laboratory handheld scanner reads linear symbologies; the
 * wedge scanners on a bench and the readers built into analyzers expect Code 128 or Code 39, and
 * Code 128 encodes the full printable ASCII set in less space. A QR code would look more modern and
 * be unreadable by the hardware that has to read it.
 *
 * <p>Subset B throughout, with no shifting into A or C. Subset C would halve the width of a purely
 * numeric payload, but an accession number is {@code L2026-000042} — a letter, digits and a hyphen —
 * so C is unusable for most of it and switching sets mid-symbol to save two millimetres is not worth
 * the extra branch in code that labels blood tubes.
 */
public final class Code128 {

    /**
     * The 107 symbol patterns, as element widths in modules.
     *
     * <p>Each string reads bar, space, bar, space, bar, space — six elements summing to eleven
     * modules. The stop pattern is the one exception: seven elements, thirteen modules.
     *
     * <p>Transcribed from the symbology specification, and the transcription is not taken on trust.
     * {@code Code128Test} asserts every entry has the right element count and sums to the right
     * module width, and that all 107 are distinct — a mistyped digit fails at least one of those.
     * The rendered output was also decoded by an independent scanner library during development.
     */
    private static final String[] PATTERNS = {
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112"
    };

    /** Subset B start symbol. */
    private static final int START_B = 104;

    /** Stop symbol, the thirteen-module one. */
    private static final int STOP = 106;

    /** Symbol values are taken modulo this when computing the check character. */
    private static final int CHECK_MODULUS = 103;

    /** Subset B maps printable ASCII to symbol values by subtracting this. */
    private static final int SUBSET_B_OFFSET = 32;

    private static final char FIRST_ENCODABLE = ' ';
    private static final char LAST_ENCODABLE = '~';

    private Code128() {
    }

    /**
     * Encodes {@code data} as the element widths of a complete Code 128 symbol.
     *
     * <p>The returned array alternates bar, space, bar, space … always starting with a bar and
     * ending with one, because the stop pattern has an odd element count. Quiet zones are the
     * caller's business: they are whitespace, not elements.
     *
     * @throws BadRequestException if the payload is empty or contains a character subset B cannot
     *                             encode. Refused rather than silently substituted, because a label
     *                             that scans as the wrong identifier is worse than no label.
     */
    public static int[] encode(String data) {
        if (data == null || data.isEmpty()) {
            throw new BadRequestException("Nothing to encode on the label");
        }
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c < FIRST_ENCODABLE || c > LAST_ENCODABLE) {
                throw new BadRequestException(
                        "'%s' contains a character Code 128 subset B cannot encode at position %d"
                                .formatted(data, i));
            }
        }

        int[] symbols = new int[data.length() + 3];
        symbols[0] = START_B;
        for (int i = 0; i < data.length(); i++) {
            symbols[i + 1] = data.charAt(i) - SUBSET_B_OFFSET;
        }
        symbols[symbols.length - 2] = checkSymbol(symbols, data.length());
        symbols[symbols.length - 1] = STOP;

        return widthsOf(symbols);
    }

    /**
     * The check character: the start value plus each data value weighted by its one-based position,
     * modulo 103.
     *
     * <p>Weighted by position deliberately — an unweighted sum would not notice two characters being
     * transposed, which is exactly the failure a hand-keyed accession number produces.
     */
    private static int checkSymbol(int[] symbols, int dataLength) {
        long sum = symbols[0];
        for (int position = 1; position <= dataLength; position++) {
            sum += (long) position * symbols[position];
        }
        return (int) (sum % CHECK_MODULUS);
    }

    private static int[] widthsOf(int[] symbols) {
        int elementCount = 0;
        for (int symbol : symbols) {
            elementCount += PATTERNS[symbol].length();
        }
        int[] widths = new int[elementCount];
        int at = 0;
        for (int symbol : symbols) {
            String pattern = PATTERNS[symbol];
            for (int i = 0; i < pattern.length(); i++) {
                widths[at++] = pattern.charAt(i) - '0';
            }
        }
        return widths;
    }

    /** Exposed for the test that audits the transcribed table. Not for encoding. */
    static String[] patterns() {
        return PATTERNS.clone();
    }

    /** Exposed so the test can check the arithmetic against an independently computed value. */
    static int checkSymbolFor(String data) {
        int[] symbols = new int[data.length() + 1];
        symbols[0] = START_B;
        for (int i = 0; i < data.length(); i++) {
            symbols[i + 1] = data.charAt(i) - SUBSET_B_OFFSET;
        }
        return checkSymbol(symbols, data.length());
    }
}
