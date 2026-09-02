package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.scheduling.domain.SchedulingEnums.CarePlanStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What this episode is trying to achieve.
 *
 * <p>A chart records what happened. A care plan records what was meant to happen, which is the
 * thing a ward round, a discharge summary and a review all ask about and which no note answers:
 * "improving" is not a goal, and a plan of goals with dates is the difference between treating a
 * patient and watching one.
 *
 * <p>One per encounter, enforced by a unique constraint rather than by a check here. Two plans for
 * one visit are two answers to "what are we trying to achieve", and they can disagree.
 */
@Entity
@Table(name = "care_plans")
public class CarePlan extends BaseEntity {

    @Column(name = "encounter_id", nullable = false, updatable = false)
    private UUID encounterId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CarePlanStatus status = CarePlanStatus.ACTIVE;

    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @OneToMany(mappedBy = "carePlan", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<CarePlanGoal> goals = new ArrayList<>();

    protected CarePlan() {
    }

    public CarePlan(UUID encounterId, UUID patientId, String patientMrn, String title,
                    String createdBy) {
        this.encounterId = encounterId;
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.title = title;
        this.createdBy = createdBy;
    }

    public void addGoal(CarePlanGoal goal) {
        goals.add(goal);
        goal.setCarePlan(this);
    }

    public void close(CarePlanStatus outcome) {
        status = outcome;
        closedAt = Instant.now();
    }

    public boolean isOpen() {
        return status == CarePlanStatus.ACTIVE;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public CarePlanStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public List<CarePlanGoal> getGoals() {
        return Collections.unmodifiableList(goals);
    }
}
