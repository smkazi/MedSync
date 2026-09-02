package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A tax rate, for a period.
 *
 * <p>Rows with effective dates rather than a constant, because rates change by statute and an
 * invoice raised last year must keep the rate that applied then. The rate is resolved against the
 * invoice's own date and then copied onto the line, so a later change cannot rewrite a document
 * somebody has already been given.
 */
@Entity
@Table(name = "tax_rates")
public class TaxRate extends BaseEntity {

    @Column(name = "code", nullable = false, length = 24, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected TaxRate() {
    }

    public TaxRate(String code, String name, BigDecimal percent, LocalDate effectiveFrom,
                   LocalDate effectiveTo) {
        this.code = code;
        this.name = name;
        this.percent = percent;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo));
    }

    /** Closes this period the day a successor starts, so the two cannot both apply. */
    public void supersededFrom(LocalDate date) {
        this.effectiveTo = date;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
