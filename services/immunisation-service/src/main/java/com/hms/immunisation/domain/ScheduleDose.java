package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One expected dose of one antigen, in days from date of birth.
 *
 * <p>Every number here is days, and the migration argues why at length: "10 weeks" is exactly 70
 * days and "2 months" is 59, 60, 61 or 62 depending on which two months, so a schedule written in
 * months is a schedule that is up to four days wrong for every child in the district at once, in
 * the same direction, silently.
 *
 * <p>Keyed on the antigen and not the product, which is the register's first rule read from the
 * other end: a schedule says a child needs three doses of protection against Hib, and whether those
 * arrive as Hib vaccine or inside a pentavalent vial is a question about what is in the fridge.
 *
 * <p>{@code minAgeDays} and {@code dueAgeDays} answer different questions and must not be assumed
 * equal by anything: one decides whether a dose that already happened counts, the other decides
 * whether to telephone. {@code minIntervalDays} is null on dose 1 and non-null on every other,
 * which the database enforces as a biconditional rather than trusting this class.
 */
@Entity
@Table(name = "schedule_doses")
public class ScheduleDose extends BaseEntity {

    @Column(name = "schedule_code", nullable = false, updatable = false, length = 32)
    private String scheduleCode;

    @Column(name = "antigen_code", nullable = false, updatable = false, length = 32)
    private String antigenCode;

    @Column(name = "dose_number", nullable = false, updatable = false)
    private int doseNumber;

    @Column(name = "label", nullable = false, length = 64)
    private String label;

    @Column(name = "min_age_days", nullable = false)
    private int minAgeDays;

    @Column(name = "due_age_days", nullable = false)
    private int dueAgeDays;

    @Column(name = "min_interval_days")
    private Integer minIntervalDays;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @Column(name = "max_age_days")
    private Integer maxAgeDays;

    protected ScheduleDose() {
    }

    public ScheduleDose(String scheduleCode, String antigenCode, int doseNumber, String label,
                        int minAgeDays, int dueAgeDays, Integer minIntervalDays, int graceDays,
                        Integer maxAgeDays) {
        this.scheduleCode = scheduleCode;
        this.antigenCode = antigenCode;
        this.doseNumber = doseNumber;
        this.label = label;
        this.minAgeDays = minAgeDays;
        this.dueAgeDays = dueAgeDays;
        this.minIntervalDays = minIntervalDays;
        this.graceDays = graceDays;
        this.maxAgeDays = maxAgeDays;
    }

    public String getScheduleCode() {
        return scheduleCode;
    }

    public String getAntigenCode() {
        return antigenCode;
    }

    public int getDoseNumber() {
        return doseNumber;
    }

    public String getLabel() {
        return label;
    }

    public int getMinAgeDays() {
        return minAgeDays;
    }

    public int getDueAgeDays() {
        return dueAgeDays;
    }

    public Integer getMinIntervalDays() {
        return minIntervalDays;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public Integer getMaxAgeDays() {
        return maxAgeDays;
    }
}
