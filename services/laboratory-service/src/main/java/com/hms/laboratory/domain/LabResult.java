package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One measured parameter.
 *
 * <p>Values are stored as text, not numbers, because a laboratory result is not always numeric —
 * "Negative", "Trace" and a masked reading are all legitimate — and because the analyzer's exact
 * precision is itself information a clinician may need.
 */
@Entity
@Table(name = "lab_results")
public class LabResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private LabOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "specimen_id")
    private Specimen specimen;

    @Column(name = "parameter", nullable = false, length = 24)
    private String parameter;

    @Column(name = "value", length = 64)
    private String value;

    @Column(name = "unit", nullable = false, length = 24)
    private String unit = "";

    @Column(name = "normal_low", precision = 12, scale = 4)
    private BigDecimal normalLow;

    @Column(name = "normal_high", precision = 12, scale = 4)
    private BigDecimal normalHigh;

    /** {@code H}, {@code L} or blank. Blank means in range, or not comparable. */
    @Column(name = "flag", nullable = false, length = 2)
    private String flag = "";

    @Column(name = "ref_text", nullable = false, length = 64)
    private String refText = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private LabEnums.ResultSource source = LabEnums.ResultSource.ANALYZER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LabEnums.ResultStatus status = LabEnums.ResultStatus.ENTERED;

    @Column(name = "entered_by", length = 64)
    private String enteredBy;

    @Column(name = "verified_by", length = 64)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "analyzer_id")
    private UUID analyzerId;

    protected LabResult() {
    }

    public LabResult(LabOrder order, String parameter, String value, String unit, LabEnums.ResultSource source,
                     String enteredBy) {
        this.order = order;
        this.parameter = parameter;
        this.value = value;
        this.unit = unit == null ? "" : unit;
        this.source = source;
        this.enteredBy = enteredBy;
    }

    public LabOrder getOrder() {
        return order;
    }

    public Specimen getSpecimen() {
        return specimen;
    }

    public void setSpecimen(Specimen specimen) {
        this.specimen = specimen;
    }

    public String getParameter() {
        return parameter;
    }

    public String getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit == null ? "" : unit;
    }

    public BigDecimal getNormalLow() {
        return normalLow;
    }

    public BigDecimal getNormalHigh() {
        return normalHigh;
    }

    public String getFlag() {
        return flag;
    }

    public String getRefText() {
        return refText;
    }

    public LabEnums.ResultSource getSource() {
        return source;
    }

    public LabEnums.ResultStatus getStatus() {
        return status;
    }

    public String getEnteredBy() {
        return enteredBy;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public UUID getAnalyzerId() {
        return analyzerId;
    }

    public void setAnalyzerId(UUID analyzerId) {
        this.analyzerId = analyzerId;
    }

    /** Applies the reference range this value was interpreted against, and the resulting flag. */
    public void applyRange(BigDecimal low, BigDecimal high, String refText, String flag) {
        this.normalLow = low;
        this.normalHigh = high;
        this.refText = refText == null ? "" : refText;
        this.flag = flag == null ? "" : flag;
    }

    /**
     * Replaces the value of a result that has already been recorded.
     *
     * <p>A verified result becomes AMENDED rather than silently overwritten: once a clinician may
     * have acted on a number, the fact that it changed is itself part of the record.
     */
    public void amend(String newValue, String enteredBy) {
        this.value = newValue;
        this.enteredBy = enteredBy;
        if (status == LabEnums.ResultStatus.VERIFIED) {
            this.status = LabEnums.ResultStatus.AMENDED;
            this.verifiedBy = null;
            this.verifiedAt = null;
        }
    }

    /** Releases the result. Only a pathologist may do this; the API enforces that. */
    public void verify(String verifiedBy) {
        this.status = LabEnums.ResultStatus.VERIFIED;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = Instant.now();
    }

    public boolean isAbnormal() {
        return !flag.isBlank();
    }
}
