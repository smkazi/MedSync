package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One antigen a measure requires, and how many counted doses of it.
 *
 * <p>Several rows per measure, ANDed: a composite is satisfied by a child who has every one of
 * them, which is what makes a childhood immunisation status a single rate rather than eight. A child
 * protected against seven of eight things is not a covered child.
 */
@Entity
@Table(name = "quality_measure_antigens")
public class QualityMeasureAntigen extends BaseEntity {

    @Column(name = "measure_code", nullable = false, updatable = false, length = 32)
    private String measureCode;

    @Column(name = "antigen_code", nullable = false, updatable = false, length = 32)
    private String antigenCode;

    @Column(name = "doses_required", nullable = false)
    private int dosesRequired;

    protected QualityMeasureAntigen() {
    }

    public String getMeasureCode() {
        return measureCode;
    }

    public String getAntigenCode() {
        return antigenCode;
    }

    public int getDosesRequired() {
        return dosesRequired;
    }
}
