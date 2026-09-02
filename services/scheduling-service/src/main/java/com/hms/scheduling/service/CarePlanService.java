package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.scheduling.domain.CarePlan;
import com.hms.scheduling.domain.CarePlanGoal;
import com.hms.scheduling.domain.Diagnosis;
import com.hms.scheduling.domain.Encounter;
import com.hms.scheduling.domain.SchedulingEnums.CarePlanStatus;
import com.hms.scheduling.domain.SchedulingEnums.GoalStatus;
import com.hms.scheduling.repo.CarePlanGoalRepository;
import com.hms.scheduling.repo.CarePlanRepository;
import com.hms.scheduling.repo.DiagnosisRepository;
import com.hms.scheduling.repo.EncounterRepository;
import com.hms.scheduling.web.dto.CareDtos;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Care plans: what an episode is trying to achieve, and whether it did.
 *
 * <p>Two rules live here rather than in the database, because both need to look at the encounter:
 * a goal may only name a problem the patient has actually been diagnosed with, and a plan closes
 * only when nothing is still open. Everything else — one plan per encounter, a note for any
 * outcome other than met — is a constraint, because a constraint cannot be forgotten.
 */
@Service
public class CarePlanService {

    private final CarePlanRepository plans;
    private final CarePlanGoalRepository goals;
    private final EncounterRepository encounters;
    private final DiagnosisRepository diagnoses;
    private final AuditService audit;

    public CarePlanService(CarePlanRepository plans, CarePlanGoalRepository goals,
                           EncounterRepository encounters, DiagnosisRepository diagnoses,
                           AuditService audit) {
        this.plans = plans;
        this.goals = goals;
        this.encounters = encounters;
        this.diagnoses = diagnoses;
        this.audit = audit;
    }

    @Transactional
    public CareDtos.CarePlanResponse create(CareDtos.CreateCarePlanRequest request) {
        Encounter encounter = encounters.findById(request.encounterId())
                .orElseThrow(() -> new NotFoundException(
                        "No encounter with id " + request.encounterId()));
        if (plans.findByEncounterId(encounter.getId()).isPresent()) {
            throw new ConflictException(
                    "This encounter already has a care plan. Add goals to it rather than starting a "
                            + "second: two plans for one visit are two answers to the same question.");
        }

        CarePlan plan = new CarePlan(encounter.getId(), encounter.getPatientId(),
                encounter.getPatientMrn(), request.title().trim(), CurrentUser.usernameOrSystem());
        Set<String> problems = problemCodes(encounter.getId());
        for (CareDtos.AddGoalRequest goal : request.goals() == null ? List.<CareDtos.AddGoalRequest>of()
                : request.goals()) {
            plan.addGoal(newGoal(goal, problems));
        }
        CarePlan saved = plans.save(plan);
        audit.record("CARE_PLAN_CREATED", "CarePlan", saved.getId(),
                "%d goal(s) on encounter %s".formatted(saved.getGoals().size(),
                        encounter.getId()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CareDtos.CarePlanResponse forEncounter(UUID encounterId) {
        return plans.findByEncounterId(encounterId).map(CarePlanService::toResponse)
                .orElseThrow(() -> new NotFoundException(
                        "No care plan for encounter " + encounterId));
    }

    @Transactional(readOnly = true)
    public List<CareDtos.CarePlanResponse> forPatient(UUID patientId) {
        return plans.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .map(CarePlanService::toResponse)
                .toList();
    }

    @Transactional
    public CareDtos.CarePlanResponse addGoal(UUID planId, CareDtos.AddGoalRequest request) {
        CarePlan plan = require(planId);
        if (!plan.isOpen()) {
            throw new BadRequestException("This care plan is closed. Goals cannot be added to it.");
        }
        plan.addGoal(newGoal(request, problemCodes(plan.getEncounterId())));
        plans.save(plan);
        audit.record("CARE_GOAL_ADDED", "CarePlan", planId, "goal added");
        return toResponse(plan);
    }

    /**
     * Records how a goal turned out.
     *
     * <p>A note is required for anything other than OPEN or MET, and the message says why rather
     * than naming the constraint: "not met" with nothing beside it is a record a review cannot
     * learn anything from, which is the only reason to keep goals at all.
     */
    @Transactional
    public CareDtos.CarePlanResponse recordGoal(UUID goalId, CareDtos.RecordGoalRequest request) {
        CarePlanGoal goal = goals.findById(goalId)
                .orElseThrow(() -> new NotFoundException("No goal with id " + goalId));
        boolean needsNote = request.status() != GoalStatus.OPEN && request.status() != GoalStatus.MET;
        if (needsNote && (request.progressNote() == null || request.progressNote().isBlank())) {
            throw new BadRequestException(
                    ("A goal recorded as %s needs a note saying what happened. Without one the "
                            + "record cannot be reviewed, which is the only reason to keep it.")
                            .formatted(request.status().name().toLowerCase(Locale.ROOT)
                                    .replace('_', ' ')));
        }
        goal.record(request.status(),
                request.progressNote() == null || request.progressNote().isBlank()
                        ? null : request.progressNote().trim(),
                CurrentUser.usernameOrSystem());
        goals.save(goal);
        audit.record("CARE_GOAL_RECORDED", "CarePlanGoal", goalId,
                "recorded as " + request.status());
        return toResponse(goal.getCarePlan());
    }

    /**
     * Closes the plan.
     *
     * <p>Refused while a goal is still open, and that is the useful part: closing a plan with an
     * open goal is how "we were going to do that" disappears from a discharge. Giving up on a goal
     * is a decision with its own status and its own note, and this makes somebody make it.
     */
    @Transactional
    public CareDtos.CarePlanResponse close(UUID planId, CarePlanStatus outcome) {
        CarePlan plan = require(planId);
        if (!plan.isOpen()) {
            throw new BadRequestException("This care plan is already "
                    + plan.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (outcome == CarePlanStatus.ACTIVE) {
            throw new BadRequestException("A plan is closed as completed or cancelled.");
        }
        List<CarePlanGoal> stillOpen = plan.getGoals().stream()
                .filter(goal -> goal.getStatus() == GoalStatus.OPEN)
                .toList();
        if (outcome == CarePlanStatus.COMPLETED && !stillOpen.isEmpty()) {
            throw new ConflictException(
                    ("%d goal(s) are still open, so this plan cannot be completed. Record each one "
                            + "as met, not met or abandoned — an open goal at discharge is a plan "
                            + "somebody was still working on.").formatted(stillOpen.size()));
        }
        plan.close(outcome);
        plans.save(plan);
        audit.record("CARE_PLAN_CLOSED", "CarePlan", planId, outcome.name().toLowerCase(Locale.ROOT));
        return toResponse(plan);
    }

    /**
     * The problems this encounter has actually recorded.
     *
     * <p>Read live rather than cached on the plan: a diagnosis added after the plan was written is
     * a problem a goal may legitimately name, and a snapshot would refuse it.
     */
    private Set<String> problemCodes(UUID encounterId) {
        return diagnoses.findByEncounterIdOrderByCategoryAsc(encounterId).stream()
                .map(Diagnosis::getIcd10Code)
                .map(code -> code.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private CarePlanGoal newGoal(CareDtos.AddGoalRequest request, Set<String> problems) {
        String code = request.problemCode() == null || request.problemCode().isBlank()
                ? null : request.problemCode().trim().toUpperCase(Locale.ROOT);
        if (code != null && !problems.contains(code)) {
            throw new BadRequestException(
                    ("This encounter has no diagnosis of %s, so a goal cannot be filed under it. "
                            + "Record the diagnosis first, or leave the problem blank for a goal "
                            + "that belongs to the admission rather than to one problem.")
                            .formatted(code));
        }
        return new CarePlanGoal(request.description().trim(), code, request.targetDate(),
                CurrentUser.usernameOrSystem());
    }

    private CarePlan require(UUID id) {
        return plans.findById(id)
                .orElseThrow(() -> new NotFoundException("No care plan with id " + id));
    }

    private static CareDtos.CarePlanResponse toResponse(CarePlan plan) {
        return new CareDtos.CarePlanResponse(plan.getId(), plan.getEncounterId(),
                plan.getPatientId(), plan.getPatientMrn(), plan.getTitle(), plan.getStatus(),
                plan.getCreatedBy(), plan.getCreatedAt(), plan.getClosedAt(),
                plan.getGoals().stream()
                        .map(goal -> new CareDtos.GoalResponse(goal.getId(), goal.getDescription(),
                                goal.getProblemCode(), goal.getTargetDate(), goal.getStatus(),
                                goal.getProgressNote(), goal.getUpdatedBy(), goal.overdue()))
                        .toList());
    }
}
