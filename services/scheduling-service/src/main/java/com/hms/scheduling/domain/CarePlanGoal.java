package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.scheduling.domain.SchedulingEnums.GoalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * One thing this admission is trying to achieve, and whether it was.
 *
 * <p>{@code problemCode} links the goal to one of the encounter's own diagnoses. Checked against
 * them when it is set rather than merely stored, so a plan cannot name a problem the patient has
 * not been given — a goal for a diagnosis nobody made is a goal nobody will review.
 *
 * <p>An outcome other than OPEN or MET needs a note, enforced by a CHECK. "Not met" on its own is
 * the shape of a record that cannot be learned from.
 */
@Entity
@Table(name = "care_plan_goals")
public class CarePlanGoal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "care_plan_id", nullable = false)
    private CarePlan carePlan;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "problem_code", length = 16)
    private String problemCode;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GoalStatus status = GoalStatus.OPEN;

    @Column(name = "progress_note", length = 1000)
    private String progressNote;

    @Column(name = "updated_by", nullable = false, length = 64)
    private String updatedBy;

    protected CarePlanGoal() {
    }

    public CarePlanGoal(String description, String problemCode, LocalDate targetDate,
                        String updatedBy) {
        this.description = description;
        this.problemCode = problemCode;
        this.targetDate = targetDate;
        this.updatedBy = updatedBy;
    }

    public void record(GoalStatus outcome, String note, String by) {
        this.status = outcome;
        this.progressNote = note;
        this.updatedBy = by;
    }

    void setCarePlan(CarePlan carePlan) {
        this.carePlan = carePlan;
    }

    public CarePlan getCarePlan() {
        return carePlan;
    }

    public String getDescription() {
        return description;
    }

    public String getProblemCode() {
        return problemCode;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    /** Past its date and still open. What a ward round wants highlighted. */
    public boolean overdue() {
        return status == GoalStatus.OPEN && targetDate != null
                && targetDate.isBefore(LocalDate.now());
    }

    public GoalStatus getStatus() {
        return status;
    }

    public String getProgressNote() {
        return progressNote;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
