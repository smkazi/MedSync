package com.hms.laboratory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The flagging rule, tested without a database.
 *
 * <p>This logic is why the platform does not simply echo the analyzer: instruments under-report,
 * so any value outside its range must be highlighted whatever the instrument said.
 */
class ReferenceRangeFlaggingTest {

    private final ReferenceRangeService service = new ReferenceRangeService(null);

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("an explicit high or low from the analyzer is trusted")
    void analyzerFlagIsTrusted() {
        assertThat(service.deriveFlag("8.0", decimal("4.0"), decimal("11.0"), "H")).isEqualTo("H");
        assertThat(service.deriveFlag("8.0", decimal("4.0"), decimal("11.0"), "L")).isEqualTo("L");
        assertThat(service.deriveFlag("8.0", decimal("4.0"), decimal("11.0"), "h")).isEqualTo("H");
    }

    @Test
    @DisplayName("an out-of-range value is flagged even when the analyzer called it normal")
    void derivesFlagDespiteNormalFromAnalyzer() {
        // The case that matters clinically: the instrument said N, the value is not.
        assertThat(service.deriveFlag("20.0", decimal("4.0"), decimal("11.0"), "N")).isEqualTo("H");
        assertThat(service.deriveFlag("2.0", decimal("4.0"), decimal("11.0"), "N")).isEqualTo("L");
    }

    @Test
    @DisplayName("an in-range value gets no flag")
    void inRangeValueIsUnflagged() {
        assertThat(service.deriveFlag("8.0", decimal("4.0"), decimal("11.0"), "")).isEmpty();
    }

    @Test
    @DisplayName("range boundaries are inclusive")
    void boundariesAreInclusive() {
        assertThat(service.deriveFlag("4.0", decimal("4.0"), decimal("11.0"), null)).isEmpty();
        assertThat(service.deriveFlag("11.0", decimal("4.0"), decimal("11.0"), null)).isEmpty();
        assertThat(service.deriveFlag("3.99", decimal("4.0"), decimal("11.0"), null)).isEqualTo("L");
        assertThat(service.deriveFlag("11.01", decimal("4.0"), decimal("11.0"), null)).isEqualTo("H");
    }

    @Test
    @DisplayName("a qualitative result is never flagged")
    void qualitativeResultIsUnflagged() {
        assertThat(service.deriveFlag("Negative", decimal("4.0"), decimal("11.0"), null)).isEmpty();
        assertThat(service.deriveFlag("Trace", decimal("4.0"), decimal("11.0"), null)).isEmpty();
        assertThat(service.deriveFlag("", decimal("4.0"), decimal("11.0"), null)).isEmpty();
        assertThat(service.deriveFlag(null, decimal("4.0"), decimal("11.0"), null)).isEmpty();
    }

    @Test
    @DisplayName("a one-sided range only flags on the side it defines")
    void oneSidedRange() {
        assertThat(service.deriveFlag("250", null, decimal("200"), null)).isEqualTo("H");
        assertThat(service.deriveFlag("10", null, decimal("200"), null)).isEmpty();
        assertThat(service.deriveFlag("30", decimal("40"), null, null)).isEqualTo("L");
        assertThat(service.deriveFlag("50", decimal("40"), null, null)).isEmpty();
    }

    @Test
    @DisplayName("with no range at all nothing is flagged")
    void noRangeMeansNoFlag() {
        assertThat(service.deriveFlag("12345", null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("thousands separators in a transmitted value are tolerated")
    void toleratesThousandsSeparator() {
        assertThat(service.deriveFlag("1,500", decimal("150"), decimal("450"), null)).isEqualTo("H");
    }

    @Test
    @DisplayName("sex normalises to the two scales the ranges are defined against")
    void sexNormalisation() {
        assertThat(ReferenceRangeService.normaliseSex("FEMALE")).isEqualTo("F");
        assertThat(ReferenceRangeService.normaliseSex("f")).isEqualTo("F");
        assertThat(ReferenceRangeService.normaliseSex("MALE")).isEqualTo("M");
        assertThat(ReferenceRangeService.normaliseSex("OTHER")).isEqualTo("M");
        assertThat(ReferenceRangeService.normaliseSex(null)).isEqualTo("M");
        assertThat(ReferenceRangeService.normaliseSex("")).isEqualTo("M");
    }
}
