package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stored cell-distribution curve.
 *
 * <p>Curves are kept as JSON rather than normalised into rows: their shape is the analyzer's, the
 * channel count varies by instrument and firmware, and nothing queries an individual channel.
 */
@Entity
@Table(name = "histograms")
public class HistogramRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private LabOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specimen_id")
    private Specimen specimen;

    /** WBC, RBC or PLT. */
    @Column(name = "group_code", nullable = false, length = 8)
    private String groupCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "curve", nullable = false)
    private String curve;

    /** The indices derived from the curve (MPV, PDW, P-LCR, MCV, RDW-CV, RDW-SD). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "indices")
    private String indices;

    protected HistogramRecord() {
    }

    public HistogramRecord(LabOrder order, String groupCode, String curve, String indices) {
        this.order = order;
        this.groupCode = groupCode;
        this.curve = curve;
        this.indices = indices;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public String getCurve() {
        return curve;
    }

    public String getIndices() {
        return indices;
    }

    public void replace(String curve, String indices) {
        this.curve = curve;
        this.indices = indices;
    }

    public void setSpecimen(Specimen specimen) {
        this.specimen = specimen;
    }
}
