package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** One set of observations. Every field is nullable: a nurse records what they measured. */
@Entity
@Table(name = "vitals")
public class VitalsRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "encounter_id", nullable = false)
    private Encounter encounter;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    @Column(name = "recorded_by", nullable = false, length = 64)
    private String recordedBy;

    @Column(name = "heart_rate")
    private Integer heartRate;

    @Column(name = "systolic_bp")
    private Integer systolicBp;

    @Column(name = "diastolic_bp")
    private Integer diastolicBp;

    @Column(name = "respiratory_rate")
    private Integer respiratoryRate;

    @Column(name = "temperature_c", precision = 4, scale = 1)
    private BigDecimal temperatureC;

    @Column(name = "oxygen_saturation")
    private Integer oxygenSaturation;

    @Column(name = "weight_kg", precision = 5, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "height_cm", precision = 5, scale = 1)
    private BigDecimal heightCm;

    @Column(name = "pain_score")
    private Integer painScore;

    @Column(name = "consciousness", length = 16)
    private String consciousness;

    /**
     * Whether the patient is on any supplemental oxygen.
     *
     * <p>Recorded rather than inferred, because NEWS2 scores 2 for it and it cannot be read off a
     * saturation: 96% on four litres is a very different patient from 96% on air. Primitive and
     * defaulted false — "not on oxygen" is the ordinary case, and there is no third state worth
     * distinguishing from it on this chart.
     */
    @Column(name = "on_supplemental_oxygen", nullable = false)
    private boolean onSupplementalOxygen;

    protected VitalsRecord() {
    }

    public VitalsRecord(Encounter encounter, String recordedBy) {
        this.encounter = encounter;
        this.recordedBy = recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public Integer getHeartRate() {
        return heartRate;
    }

    public Integer getSystolicBp() {
        return systolicBp;
    }

    public Integer getDiastolicBp() {
        return diastolicBp;
    }

    public Integer getRespiratoryRate() {
        return respiratoryRate;
    }

    public BigDecimal getTemperatureC() {
        return temperatureC;
    }

    public Integer getOxygenSaturation() {
        return oxygenSaturation;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public BigDecimal getHeightCm() {
        return heightCm;
    }

    public Integer getPainScore() {
        return painScore;
    }

    public String getConsciousness() {
        return consciousness;
    }

    public boolean isOnSupplementalOxygen() {
        return onSupplementalOxygen;
    }

    public void record(Integer heartRate, Integer systolicBp, Integer diastolicBp,
                       Integer respiratoryRate, BigDecimal temperatureC, Integer oxygenSaturation,
                       BigDecimal weightKg, BigDecimal heightCm, Integer painScore,
                       String consciousness, Boolean onSupplementalOxygen) {
        this.heartRate = heartRate;
        this.systolicBp = systolicBp;
        this.diastolicBp = diastolicBp;
        this.respiratoryRate = respiratoryRate;
        this.temperatureC = temperatureC;
        this.oxygenSaturation = oxygenSaturation;
        this.weightKg = weightKg;
        this.heightCm = heightCm;
        this.painScore = painScore;
        this.consciousness = consciousness;
        // Boxed on the way in and primitive on the way through: an absent field in the request
        // means "not on oxygen", which is the ordinary case, while a primitive parameter would
        // have made Jackson refuse the whole body over an omitted flag.
        this.onSupplementalOxygen = Boolean.TRUE.equals(onSupplementalOxygen);
    }

    /**
     * Body mass index from this observation set, when both weight and height were measured.
     *
     * @return BMI to one decimal place, or null when it cannot be computed
     */
    public BigDecimal bodyMassIndex() {
        if (weightKg == null || heightCm == null || heightCm.signum() <= 0) {
            return null;
        }
        BigDecimal metres = heightCm.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
        BigDecimal squared = metres.multiply(metres);
        if (squared.signum() <= 0) {
            return null;
        }
        return weightKg.divide(squared, 1, java.math.RoundingMode.HALF_UP);
    }
}
