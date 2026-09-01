package com.hms.laboratory.service;

import com.hms.laboratory.domain.ReferenceRange;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hms.laboratory.repo.ReferenceRangeRepository;

/**
 * Interprets a value against the lab's reference ranges.
 *
 * <p>The flagging rule is ported from {@code models/reference_ranges.py} (smkazi/HaematologyIS) and
 * exists because analyzers under-report: a report must highlight <em>any</em> value outside its
 * range, not only the ones the instrument happened to flag. So an explicit H or L from the analyzer
 * is trusted, an explicit N is treated as "no flag", and anything else is derived by comparison.
 */
@Service
public class ReferenceRangeService {

    public static final String FLAG_HIGH = "H";
    public static final String FLAG_LOW = "L";
    public static final String NO_FLAG = "";

    private final ReferenceRangeRepository ranges;

    public ReferenceRangeService(ReferenceRangeRepository ranges) {
        this.ranges = ranges;
    }

    /** The configured range for a parameter and sex, if the lab defines one. */
    @Transactional(readOnly = true)
    public Optional<ReferenceRange> find(String parameter, String sex) {
        if (parameter == null || parameter.isBlank()) {
            return Optional.empty();
        }
        return ranges.findByParameterIgnoreCaseAndSex(parameter.trim(), normaliseSex(sex));
    }

    @Transactional(readOnly = true)
    public List<ReferenceRange> findAll() {
        return ranges.findAllByOrderByParameterAscSexAsc();
    }

    /**
     * Decides the out-of-range status of a value.
     *
     * @param value         the transmitted value; a qualitative result yields no flag
     * @param low           lower bound, or null when unbounded
     * @param high          upper bound, or null when unbounded
     * @param analyzerFlag  what the instrument said, if anything
     * @return {@code H}, {@code L}, or blank for in-range, unknown or non-numeric
     */
    public String deriveFlag(String value, BigDecimal low, BigDecimal high, String analyzerFlag) {
        String instrument = analyzerFlag == null ? "" : analyzerFlag.trim().toUpperCase();
        if (FLAG_HIGH.equals(instrument) || FLAG_LOW.equals(instrument)) {
            return instrument;
        }
        // An explicit "normal" from the analyzer is not proof: it is checked against the range too,
        // which is the whole point of this method.
        Optional<BigDecimal> numeric = parseNumeric(value);
        if (numeric.isEmpty()) {
            return NO_FLAG;
        }
        BigDecimal parsed = numeric.get();
        if (low != null && parsed.compareTo(low) < 0) {
            return FLAG_LOW;
        }
        if (high != null && parsed.compareTo(high) > 0) {
            return FLAG_HIGH;
        }
        return NO_FLAG;
    }

    /** Applies the configured range for the parameter, then flags the value against it. */
    @Transactional(readOnly = true)
    public Interpretation interpret(String parameter, String value, String sex, String analyzerFlag,
                                    BigDecimal analyzerLow, BigDecimal analyzerHigh, String analyzerUnit) {
        Optional<ReferenceRange> configured = find(parameter, sex);

        // The lab's own range wins over the instrument's: the lab calibrated it for this population.
        BigDecimal low = configured.map(ReferenceRange::getNormalLow).orElse(analyzerLow);
        BigDecimal high = configured.map(ReferenceRange::getNormalHigh).orElse(analyzerHigh);
        String unit = configured.map(ReferenceRange::getUnit)
                .filter(value2 -> !value2.isBlank())
                .orElse(analyzerUnit == null ? "" : analyzerUnit);
        String refText = configured.map(ReferenceRange::asText).orElse(rangeText(low, high));
        String displayName = configured.map(ReferenceRange::getDisplayName).orElse(parameter);

        return new Interpretation(low, high, unit, refText, displayName,
                deriveFlag(value, low, high, analyzerFlag));
    }

    private static String rangeText(BigDecimal low, BigDecimal high) {
        if (low != null && high != null) {
            return "%s - %s".formatted(low.stripTrailingZeros().toPlainString(),
                    high.stripTrailingZeros().toPlainString());
        }
        return "";
    }

    private static Optional<BigDecimal> parseNumeric(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String cleaned = value.trim().replace(",", "");
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(cleaned));
        } catch (NumberFormatException ex) {
            // A qualitative result ("Negative", "Trace") is not comparable to a range.
            return Optional.empty();
        }
    }

    static String normaliseSex(String sex) {
        if (sex == null || sex.isBlank()) {
            return "M";
        }
        String first = sex.trim().substring(0, 1).toUpperCase();
        return "F".equals(first) ? "F" : "M";
    }

    /** How a value was interpreted: the range applied, the unit, and the resulting flag. */
    public record Interpretation(BigDecimal normalLow, BigDecimal normalHigh, String unit, String refText,
                                 String displayName, String flag) {
    }
}
