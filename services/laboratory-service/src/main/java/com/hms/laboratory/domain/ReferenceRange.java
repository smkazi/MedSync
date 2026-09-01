package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * The normal range for one parameter and sex.
 *
 * <p>Ranges are data, not code: a lab adjusts them for its population and its instruments, so they
 * live in a table the lab can edit rather than in a constant.
 */
@Entity
@Table(name = "reference_ranges")
public class ReferenceRange extends BaseEntity {

    @Column(name = "parameter", nullable = false, length = 24)
    private String parameter;

    /** {@code M} or {@code F} — the scale the analyzer's ranges are defined against. */
    @Column(name = "sex", nullable = false, length = 1)
    private String sex;

    @Column(name = "normal_low", precision = 12, scale = 4)
    private BigDecimal normalLow;

    @Column(name = "normal_high", precision = 12, scale = 4)
    private BigDecimal normalHigh;

    @Column(name = "unit", nullable = false, length = 24)
    private String unit = "";

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName = "";

    protected ReferenceRange() {
    }

    public ReferenceRange(String parameter, String sex, BigDecimal normalLow, BigDecimal normalHigh, String unit,
                          String displayName) {
        this.parameter = parameter;
        this.sex = sex;
        this.normalLow = normalLow;
        this.normalHigh = normalHigh;
        this.unit = unit == null ? "" : unit;
        this.displayName = displayName == null ? "" : displayName;
    }

    public String getParameter() {
        return parameter;
    }

    public String getSex() {
        return sex;
    }

    public BigDecimal getNormalLow() {
        return normalLow;
    }

    public void setNormalLow(BigDecimal normalLow) {
        this.normalLow = normalLow;
    }

    public BigDecimal getNormalHigh() {
        return normalHigh;
    }

    public void setNormalHigh(BigDecimal normalHigh) {
        this.normalHigh = normalHigh;
    }

    public String getUnit() {
        return unit;
    }

    public String getDisplayName() {
        return displayName.isBlank() ? parameter : displayName;
    }

    /** The range as printed on a report, e.g. {@code 4.0 - 11.0}. */
    public String asText() {
        if (normalLow != null && normalHigh != null) {
            return "%s - %s".formatted(strip(normalLow), strip(normalHigh));
        }
        if (normalHigh != null) {
            return "< %s".formatted(strip(normalHigh));
        }
        if (normalLow != null) {
            return "> %s".formatted(strip(normalLow));
        }
        return "";
    }

    private static String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
