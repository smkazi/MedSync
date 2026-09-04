package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A published immunisation schedule, bounded by the age it is about.
 *
 * <p>The bounds are the whole reason this table exists rather than the dose rows standing alone. A
 * schedule that applied to everybody would produce a due list for a sixty-year-old out of rows
 * written for infants — an answer in the same table and the same colour as the ones that are right,
 * which is worse than no answer.
 *
 * <p>{@code source} names the document the rows were read off. Stored for the reason a quality
 * measure stores its specification version: a due date somebody telephones a family about should be
 * traceable to the thing that says it, and "the schedule" is not a citation.
 */
@Entity
@Table(name = "immunisation_schedules")
public class ImmunisationSchedule extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "applies_from_age_days", nullable = false)
    private int appliesFromAgeDays;

    @Column(name = "applies_to_age_days", nullable = false)
    private int appliesToAgeDays;

    @Column(name = "source", nullable = false, length = 255)
    private String source;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ImmunisationSchedule() {
    }

    public ImmunisationSchedule(String code, String name, int appliesFromAgeDays,
                                int appliesToAgeDays, String source) {
        this.code = code;
        this.name = name;
        this.appliesFromAgeDays = appliesFromAgeDays;
        this.appliesToAgeDays = appliesToAgeDays;
        this.source = source;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getAppliesFromAgeDays() {
        return appliesFromAgeDays;
    }

    public int getAppliesToAgeDays() {
        return appliesToAgeDays;
    }

    public String getSource() {
        return source;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
