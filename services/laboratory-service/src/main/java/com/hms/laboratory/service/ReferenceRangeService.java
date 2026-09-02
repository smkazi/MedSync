package com.hms.laboratory.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.laboratory.domain.ReferenceRange;
import com.hms.laboratory.web.dto.LabDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
    private final AuditService audit;

    public ReferenceRangeService(ReferenceRangeRepository ranges, AuditService audit) {
        this.ranges = ranges;
        this.audit = audit;
    }

    /** The configured range for a parameter and sex, if the lab defines one. */
    @Transactional(readOnly = true)
    public Optional<ReferenceRange> find(String parameter, String sex) {
        if (parameter == null || parameter.isBlank()) {
            return Optional.empty();
        }
        String resolved = normaliseSex(sex);
        if (resolved == null) {
            // No sex on the order, so no sex-specific interval applies. `interpret` then falls
            // back to the instrument's own range, which is honest; guessing here is not.
            return Optional.empty();
        }
        return ranges.findByParameterIgnoreCaseAndSex(parameter.trim(), resolved);
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
        String instrument = analyzerFlag == null ? "" : analyzerFlag.trim().toUpperCase(Locale.ROOT);
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

    /**
     * The reference-interval scale to look up, or {@code null} when there is none to look up.
     *
     * <p>This used to answer {@code "M"} for absent, blank and unrecognised alike, so an order
     * carrying no sex — or, before the request pattern was tightened, a sex of {@code "X"} — was
     * flagged against male intervals with nothing on the report saying so. A haemoglobin of 12.5
     * g/dL is normal for a woman and low for a man; picking a side by default is picking wrong half
     * the time, silently, on the number a clinician treats from.
     *
     * <p>So the unknown answers {@code null} and the caller declines to apply a range. Returning
     * {@code "M"} was never a clinical judgement, only a lookup that could not fail.
     */
    static String normaliseSex(String sex) {
        if (sex == null || sex.isBlank()) {
            return null;
        }
        String first = sex.trim().substring(0, 1).toUpperCase(Locale.ROOT);
        if ("F".equals(first)) {
            return "F";
        }
        return "M".equals(first) ? "M" : null;
    }

    /**
     * Retunes one interval.
     *
     * <p>Sparse, and the comparison is why this cannot live in the request record: patching only
     * the low bound has to be checked against the high bound already stored, or an inverted pair
     * is reachable in two requests that each look fine on their own. An inverted interval is not a
     * cosmetic error — {@code deriveFlag} then marks every subsequent value for that parameter as
     * high, on every report, until somebody notices.
     *
     * <p>Equal bounds are allowed: an interval of exactly one value is unusual but legitimate, and
     * refusing it would be inventing a rule the laboratory did not ask for.
     */
    @Transactional
    public ReferenceRange update(UUID id, LabDtos.UpdateReferenceRangeRequest request) {
        ReferenceRange range = ranges.findById(id)
                .orElseThrow(() -> NotFoundException.of("ReferenceRange", id));

        BigDecimal low = request.normalLow() == null ? range.getNormalLow() : request.normalLow();
        BigDecimal high = request.normalHigh() == null ? range.getNormalHigh() : request.normalHigh();
        if (low != null && high != null && low.compareTo(high) > 0) {
            throw new BadRequestException(("A reference interval cannot start above where it ends: "
                    + "low %s is greater than high %s for %s (%s). Every value would read as high.")
                    .formatted(low.stripTrailingZeros().toPlainString(),
                            high.stripTrailingZeros().toPlainString(),
                            range.getParameter(), range.getSex()));
        }

        range.setNormalLow(low);
        range.setNormalHigh(high);
        ReferenceRange saved = ranges.save(range);
        // Audited like every other configuration write. This one was the exception: it mutated the
        // repository from inside the controller and left no trace of who moved a reporting
        // threshold.
        audit.record("REFERENCE_RANGE_UPDATED", "ReferenceRange", saved.getId(),
                "%s (%s) now %s".formatted(saved.getParameter(), saved.getSex(), saved.asText()));
        return saved;
    }

    /** How a value was interpreted: the range applied, the unit, and the resulting flag. */
    public record Interpretation(BigDecimal normalLow, BigDecimal normalHigh, String unit, String refText,
                                 String displayName, String flag) {
    }
}
