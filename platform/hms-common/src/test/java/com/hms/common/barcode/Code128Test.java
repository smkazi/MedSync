package com.hms.common.barcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.common.error.BadRequestException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audits the transcribed Code 128 table and the encoder's arithmetic.
 *
 * <p>A hand-written symbology table is only as good as the transcription, and the failure mode is
 * nasty: a single mistyped digit produces a symbol that still looks like a barcode, still renders,
 * and either fails to scan or — far worse — scans as a different string. So the table is checked
 * against the structural properties the specification guarantees, each of which a typo breaks.
 *
 * <p>These are hermetic and run in CI. Separately, during development the rendered output was
 * decoded by an independent scanner library (zbar 0.23.93) for four payloads including the live
 * accession format, which is the check these invariants cannot make: that a real scanner agrees.
 */
class Code128Test {

    private static final int DATA_SYMBOL_MODULES = 11;
    private static final int STOP_SYMBOL_MODULES = 13;
    private static final int STOP_INDEX = 106;

    @Test
    @DisplayName("every pattern has the element count and module width the specification requires")
    void patternsAreWellFormed() {
        String[] patterns = Code128.patterns();
        assertThat(patterns).hasSize(107);

        for (int value = 0; value < patterns.length; value++) {
            String pattern = patterns[value];
            boolean isStop = value == STOP_INDEX;
            int expectedElements = isStop ? 7 : 6;
            int expectedModules = isStop ? STOP_SYMBOL_MODULES : DATA_SYMBOL_MODULES;

            assertThat(pattern.length())
                    .as("symbol %d (%s) element count", value, pattern)
                    .isEqualTo(expectedElements);

            int modules = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char digit = pattern.charAt(i);
                assertThat(digit)
                        .as("symbol %d (%s) element width must be 1-4 modules", value, pattern)
                        .isBetween('1', '4');
                modules += digit - '0';
            }
            assertThat(modules)
                    .as("symbol %d (%s) module width", value, pattern)
                    .isEqualTo(expectedModules);
        }
    }

    @Test
    @DisplayName("no two symbols share a pattern")
    void patternsAreDistinct() {
        String[] patterns = Code128.patterns();
        Set<String> distinct = new HashSet<>(Arrays.asList(patterns));
        // Two symbols with the same widths would decode ambiguously - the scanner would be free to
        // read either value, which is the one thing a check digit cannot save you from.
        assertThat(distinct).hasSameSizeAs(patterns);
    }

    @Test
    @DisplayName("the check character is the position-weighted sum, modulo 103")
    void checkCharacterArithmetic() {
        // Computed here independently of the encoder rather than copied from its output, which would
        // only prove the encoder agrees with itself.
        //   start B = 104, 'A' = 65 - 32 = 33, weight 1
        //   (104 + 1*33) mod 103 = 137 mod 103 = 34
        assertThat(Code128.checkSymbolFor("A")).isEqualTo(34);

        //   'A'=33 w1, 'B'=34 w2, 'C'=35 w3 -> 104 + 33 + 68 + 105 = 310; 310 mod 103 = 1
        assertThat(Code128.checkSymbolFor("ABC")).isEqualTo(1);

        // The live accession shape, cross-checked against the independent scanner run.
        assertThat(Code128.checkSymbolFor("L2026-000042")).isEqualTo(30);
    }

    @Test
    @DisplayName("weighting by position catches a transposition")
    void transpositionChangesTheCheckCharacter() {
        // The reason the sum is weighted at all. An unweighted sum is identical for "12" and "21",
        // so a hand-keyed accession number with two digits swapped would pass its own check digit.
        assertThat(Code128.checkSymbolFor("L2026-000012"))
                .isNotEqualTo(Code128.checkSymbolFor("L2026-000021"));
    }

    @Test
    @DisplayName("a symbol is start + data + check + stop, and starts and ends with a bar")
    void symbolStructure() {
        String data = "L2026-000042";
        int[] widths = Code128.encode(data);

        // start + data + check = 11 modules each; stop adds 13.
        int expectedModules = DATA_SYMBOL_MODULES * (1 + data.length() + 1) + STOP_SYMBOL_MODULES;
        assertThat(Arrays.stream(widths).sum()).isEqualTo(expectedModules);

        // Six elements per symbol, seven for the stop. An odd total means the last element is a bar,
        // which is what the specification requires - the symbol must not end on a space, or the
        // quiet zone and the final element become indistinguishable.
        int expectedElements = 6 * (1 + data.length() + 1) + 7;
        assertThat(widths).hasSize(expectedElements);
        assertThat(expectedElements % 2).as("must end on a bar").isEqualTo(1);
    }

    @Test
    @DisplayName("a character subset B cannot encode is refused, not substituted")
    void unencodableCharacterIsRefused() {
        // Refused rather than stripped or replaced. A label that silently drops a character scans
        // as a valid-looking identifier for a different tube.
        assertThatThrownBy(() -> Code128.encode("L2026-é042"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot encode");

        assertThatThrownBy(() -> Code128.encode(""))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("distinct payloads produce distinct symbols")
    void differentPayloadsDifferentSymbols() {
        assertThat(Code128.encode("L2026-000001"))
                .isNotEqualTo(Code128.encode("L2026-000002"));
    }
}
