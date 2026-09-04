package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A diagnosis that has to be reported to a public health authority, and how fast.
 *
 * <p>One row per ICD-10 code and never a prefix. A prefix widens invisibly — {@code A0} would sweep
 * cholera through typhoid into amoebiasis and one line of a statutory return — and it could not be
 * an equality join, so the index this feature added would go unused.
 *
 * <p>{@code notifyWithinHours} is a number rather than an urgency enum, because how many hours is
 * the whole of the behaviour. It is <strong>recorded and not enforced</strong>: this platform has no
 * outbound channel to an authority, so a countdown it could not act on would be a promise nothing
 * keeps.
 */
@Entity
@Table(name = "notifiable_conditions")
public class NotifiableCondition extends BaseEntity {

    @Column(name = "icd10_code", nullable = false, updatable = false, length = 16)
    private String icd10Code;

    @Column(name = "condition_name", nullable = false, length = 160)
    private String conditionName;

    @Column(name = "notify_within_hours", nullable = false)
    private int notifyWithinHours;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected NotifiableCondition() {
    }

    public NotifiableCondition(String icd10Code, String conditionName, int notifyWithinHours) {
        this.icd10Code = icd10Code;
        this.conditionName = conditionName;
        this.notifyWithinHours = notifyWithinHours;
    }

    public String getIcd10Code() {
        return icd10Code;
    }

    public String getConditionName() {
        return conditionName;
    }

    public int getNotifyWithinHours() {
        return notifyWithinHours;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
