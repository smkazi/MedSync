package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A clinical quality measure over the register.
 *
 * <p>The parameters are rows and the kind of question is code, which the migration argues at
 * length. {@code kind} carries a database CHECK naming exactly the calculators that exist, so a row
 * asking a question nothing can answer is refused rather than published as a percentage of nothing.
 *
 * <p>The three population descriptions are transcribed from the specification rather than rendered
 * from the parameters, deliberately. A sentence generated from the columns would always agree with
 * the code and would therefore never reveal a disagreement between the code and the specification —
 * which is the only disagreement worth finding.
 */
@Entity
@Table(name = "quality_measures")
public class QualityMeasure extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "kind", nullable = false, updatable = false, length = 48)
    private String kind;

    @Column(name = "by_age_days", nullable = false)
    private int byAgeDays;

    @Column(name = "steward", nullable = false, length = 160)
    private String steward;

    @Column(name = "specification_version", nullable = false, length = 48)
    private String specificationVersion;

    @Column(name = "initial_population", nullable = false)
    private String initialPopulation;

    @Column(name = "denominator", nullable = false)
    private String denominator;

    @Column(name = "denominator_exclusion", nullable = false)
    private String denominatorExclusion;

    @Column(name = "numerator", nullable = false)
    private String numerator;

    @Column(name = "counts_estimated_dates", nullable = false)
    private boolean countsEstimatedDates;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected QualityMeasure() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public int getByAgeDays() {
        return byAgeDays;
    }

    public String getSteward() {
        return steward;
    }

    public String getSpecificationVersion() {
        return specificationVersion;
    }

    public String getInitialPopulation() {
        return initialPopulation;
    }

    public String getDenominator() {
        return denominator;
    }

    public String getDenominatorExclusion() {
        return denominatorExclusion;
    }

    public String getNumerator() {
        return numerator;
    }

    public boolean countsEstimatedDates() {
        return countsEstimatedDates;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
