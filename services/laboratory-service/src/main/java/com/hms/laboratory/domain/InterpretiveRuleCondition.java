package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** One comparison inside an {@link InterpretiveRule}. All of a rule's conditions must hold. */
@Entity
@Table(name = "interpretive_rule_conditions")
public class InterpretiveRuleCondition extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private InterpretiveRule rule;

    /**
     * Comma-separated parameter aliases, tried in order.
     *
     * <p>One analyzer transmits {@code LYM#}, another {@code LYMPH#}, another {@code LY#}. Storing
     * the alternatives on the condition means a lab that swaps instruments edits a row instead of
     * discovering months later that a comment quietly stopped appearing.
     */
    @Column(name = "parameters", nullable = false, length = 120)
    private String parameters;

    @Column(name = "operator", nullable = false, length = 2)
    private String operator;

    @Column(name = "threshold", nullable = false, precision = 14, scale = 4)
    private BigDecimal threshold;

    protected InterpretiveRuleCondition() {
    }

    public InterpretiveRule getRule() {
        return rule;
    }

    public String getParameters() {
        return parameters;
    }

    /** The aliases, split and upper-cased, in the order they should be tried. */
    public List<String> parameterAliases() {
        return Arrays.stream(parameters.split(","))
                .map(String::trim)
                .filter(alias -> !alias.isEmpty())
                .map(alias -> alias.toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    public String getOperator() {
        return operator;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }
}
