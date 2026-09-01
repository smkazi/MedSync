package com.hms.laboratory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * How to normalise one parameter that arrives on two possible scales.
 *
 * <p>An analyzer may transmit WBC as {@code 7.36} (×10³/µL) or {@code 7360} (absolute /µL) depending
 * on model and configuration. A rule written against one scale never fires against the other, and
 * fails silently — no error, just a comment that stops appearing. A value above {@link #getAbove()}
 * is taken to be on the absolute scale and divided.
 *
 * <p>Ported from {@code _FLAG_SCALE_PARAMS} in smkazi/HaematologyIS. The values came from real
 * instrument output and are kept as they were; only their home changed, from a literal to a row.
 */
@Entity
@Table(name = "parameter_scales")
public class ParameterScale {

    @Id
    @Column(name = "parameter", nullable = false, length = 24, updatable = false)
    private String parameter;

    @Column(name = "above", nullable = false, precision = 14, scale = 4)
    private BigDecimal above;

    @Column(name = "divide_by", nullable = false, precision = 14, scale = 4)
    private BigDecimal divideBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ParameterScale() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public String getParameter() {
        return parameter;
    }

    public BigDecimal getAbove() {
        return above;
    }

    public BigDecimal getDivideBy() {
        return divideBy;
    }
}
