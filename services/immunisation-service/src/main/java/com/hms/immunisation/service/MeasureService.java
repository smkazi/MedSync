package com.hms.immunisation.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.immunisation.client.PatientCohortClient;
import com.hms.immunisation.domain.Immunisation;
import com.hms.immunisation.domain.ImmunisationExemption;
import com.hms.immunisation.domain.ImmunisationSchedule;
import com.hms.immunisation.domain.QualityMeasure;
import com.hms.immunisation.domain.QualityMeasureAntigen;
import com.hms.immunisation.domain.ScheduleDose;
import com.hms.immunisation.repo.ExemptionRepository;
import com.hms.immunisation.repo.ImmunisationRepository;
import com.hms.immunisation.repo.QualityMeasureAntigenRepository;
import com.hms.immunisation.repo.QualityMeasureRepository;
import com.hms.immunisation.repo.ScheduleDoseRepository;
import com.hms.immunisation.repo.ScheduleRepository;
import com.hms.immunisation.service.CoverageMeasureCalculator.Requirement;
import com.hms.immunisation.service.CoverageMeasureCalculator.Subject;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.GivenDose;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Schedule;
import com.hms.immunisation.web.dto.ImmunisationDtos;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quality measures: what is measured, and what this period's answer is.
 *
 * <p><strong>The kind is code and everything else is rows</strong>, which is what makes a second
 * coverage measure an INSERT. This class dispatches on {@code kind} to exactly one calculator, and
 * a row naming a kind nothing implements cannot exist — the database refuses it. If one somehow
 * did, this refuses to answer rather than rendering a percentage of nothing.
 *
 * <p><strong>Computed on read and never cached.</strong> The same argument as the due list, plus a
 * sharper one: there is no invalidation key. A dose entered from a card this morning correctly
 * changes last quarter's rate, so a cached number is a number that can be published stale. Each
 * answer is stamped with the specification version that produced it and the moment it was computed.
 *
 * <p>The denominator is a <em>birth cohort</em>, which is what lets this be computed at all: a
 * measure asking about children who reached age N during a period is asking about children born
 * during a period exactly N days earlier, and patient-service answers a birth range from an index
 * it already has.
 */
@Service
public class MeasureService {

    /** The one kind implemented, matching the database CHECK that admits it. */
    static final String ANTIGEN_COVERAGE_BY_AGE = "ANTIGEN_COVERAGE_BY_AGE";

    private final QualityMeasureRepository measures;
    private final QualityMeasureAntigenRepository requirements;
    private final ScheduleRepository schedules;
    private final ScheduleDoseRepository scheduleDoses;
    private final ImmunisationRepository register;
    private final ExemptionRepository exemptions;
    private final CatalogueService catalogue;
    private final PatientCohortClient cohorts;
    private final ImmunisationClock clock;
    private final AuditService audit;
    private final String defaultScheduleCode;

    public MeasureService(QualityMeasureRepository measures,
                          QualityMeasureAntigenRepository requirements,
                          ScheduleRepository schedules, ScheduleDoseRepository scheduleDoses,
                          ImmunisationRepository register, ExemptionRepository exemptions,
                          CatalogueService catalogue, PatientCohortClient cohorts,
                          ImmunisationClock clock, AuditService audit,
                          @Value("${hms.immunisation.default-schedule:UIP-2024}")
                          String defaultScheduleCode) {
        this.measures = measures;
        this.requirements = requirements;
        this.schedules = schedules;
        this.scheduleDoses = scheduleDoses;
        this.register = register;
        this.exemptions = exemptions;
        this.catalogue = catalogue;
        this.cohorts = cohorts;
        this.clock = clock;
        this.audit = audit;
        this.defaultScheduleCode = defaultScheduleCode;
    }

    /** What is measured, with the specification each rate will be stamped with. */
    @Transactional(readOnly = true)
    public List<ImmunisationDtos.MeasureResponse> published() {
        return measures.findByActiveTrueOrderByCodeAsc().stream()
                .map(measure -> toResponse(measure,
                        requirements.findByMeasureCodeOrderByAntigenCodeAsc(measure.getCode())))
                .toList();
    }

    /**
     * This period's rate.
     *
     * <p>The period bounds <em>birthdays</em>, not doses: a child is in the initial population when
     * their Nth birthday falls inside it. So the birth range handed to patient-service is the period
     * shifted back by the measure's age, and each child is then evaluated as at their own birthday
     * rather than as at today — which is what "by age two" means, and the difference between a rate
     * and a rate that improves retroactively.
     */
    @Transactional(readOnly = true)
    public ImmunisationDtos.MeasureRateResponse rate(String code, LocalDate periodFrom,
                                                     LocalDate periodTo, String scheduleCode) {
        if (periodFrom == null || periodTo == null) {
            throw new BadRequestException("A measure is computed for a period: periodFrom and "
                    + "periodTo are both required.");
        }
        if (periodTo.isBefore(periodFrom)) {
            throw new BadRequestException(("A period from %s to %s runs backwards.")
                    .formatted(periodFrom, periodTo));
        }
        QualityMeasure measure = measures.findByCode(code).orElseThrow(
                () -> new NotFoundException("No quality measure '%s'".formatted(code)));
        if (!ANTIGEN_COVERAGE_BY_AGE.equals(measure.getKind())) {
            // Unreachable through the database, which admits exactly the kinds implemented here.
            // Kept because the alternative to refusing is rendering a rate for a question nothing
            // answered -- a percentage of nothing, which is the failure the CHECK exists to stop
            // and this is the second lock on the same door.
            throw new BadRequestException(("Measure '%s' is of kind '%s', which no calculator on "
                    + "this platform implements. No rate is produced.")
                    .formatted(code, measure.getKind()));
        }
        List<QualityMeasureAntigen> required =
                requirements.findByMeasureCodeOrderByAntigenCodeAsc(code);
        if (required.isEmpty()) {
            throw new BadRequestException(("Measure '%s' names no antigens, so every child would "
                    + "meet it. No rate is produced.").formatted(code));
        }

        Schedule schedule = schedule(scheduleCode == null || scheduleCode.isBlank()
                ? defaultScheduleCode : scheduleCode);

        // The period bounds birthdays; the cohort is the births that produce them.
        LocalDate bornFrom = periodFrom.minusDays(measure.getByAgeDays());
        LocalDate bornTo = periodTo.minusDays(measure.getByAgeDays());
        // The names-free cohort, deliberately. A rate needs a birthday to compute an age against
        // and a key to join the register on; it does not need to know who anybody is, which is
        // what lets an epidemiologist read this while holding nothing that names a patient.
        PatientCohortClient.Cohort cohort = cohorts.bornBetweenWithoutNames(bornFrom, bornTo, null);

        audit.record("QUALITY_MEASURE_COMPUTED", "QualityMeasure", null,
                "%s over %s..%s, %d in the initial population, for %s".formatted(code, periodFrom,
                        periodTo, cohort.members().size(), CurrentUser.usernameOrSystem()));

        List<Requirement> wanted = required.stream()
                .map(row -> new Requirement(row.getAntigenCode(), row.getDosesRequired()))
                .toList();
        CoverageMeasureCalculator.Result result = CoverageMeasureCalculator.evaluate(schedule,
                measure.getByAgeDays(), wanted, subjects(cohort), measure.countsEstimatedDates());

        return new ImmunisationDtos.MeasureRateResponse(measure.getCode(), measure.getName(),
                measure.getKind(), measure.getSteward(), measure.getSpecificationVersion(),
                schedule.code(), periodFrom, periodTo, bornFrom, bornTo,
                result.initialPopulation(), result.denominator(), result.numerator(),
                result.rate(), cohort.truncated(), cohort.note(), Instant.now());
    }

    /** The cohort, with each child's doses and exemptions attached. */
    private List<Subject> subjects(PatientCohortClient.Cohort cohort) {
        if (cohort.members().isEmpty()) {
            return List.of();
        }
        List<UUID> ids = cohort.members().stream().map(PatientCohortClient.CohortMember::id).toList();
        Map<String, Set<String>> antigensByProduct = catalogue.antigensByProduct();

        Map<UUID, List<GivenDose>> dosesByPatient = new HashMap<>();
        for (Immunisation dose : register.forCohort(ids)) {
            for (String antigen : antigensByProduct.getOrDefault(dose.getProductCode(), Set.of())) {
                dosesByPatient.computeIfAbsent(dose.getPatientId(), key -> new ArrayList<>())
                        .add(new GivenDose(dose.getId(), antigen, dose.getProductCode(),
                                dose.getGivenOn(), dose.isGivenOnEstimated()));
            }
        }
        Map<UUID, List<ImmunisationScheduleCalculator.Exemption>> exemptByPatient = new HashMap<>();
        for (ImmunisationExemption exemption : exemptions.findByPatientIdIn(ids)) {
            exemptByPatient.computeIfAbsent(exemption.getPatientId(), key -> new ArrayList<>())
                    .add(new ImmunisationScheduleCalculator.Exemption(exemption.getAntigenCode(),
                            exemption.getKind(), exemption.getExpiresOn()));
        }

        return cohort.members().stream()
                .map(member -> new Subject(member.id(), member.mrn(), member.dateOfBirth(),
                        dosesByPatient.getOrDefault(member.id(), List.of()),
                        exemptByPatient.getOrDefault(member.id(), List.of())))
                .toList();
    }

    private Schedule schedule(String code) {
        ImmunisationSchedule published = schedules.findByCode(code).orElseThrow(
                () -> new NotFoundException(("No immunisation schedule '%s'. A coverage rate is "
                        + "counted against the schedule a dose was expected under.").formatted(code)));
        List<ScheduleDose> rows =
                scheduleDoses.findByScheduleCodeOrderByAntigenCodeAscDoseNumberAsc(code);
        if (rows.isEmpty()) {
            throw new BadRequestException(("Schedule '%s' has no doses in it, so no dose could "
                    + "count toward anything.").formatted(code));
        }
        return new Schedule(published.getCode(), published.getName(),
                published.getAppliesFromAgeDays(), published.getAppliesToAgeDays(),
                rows.stream().map(row -> new ImmunisationScheduleCalculator.ScheduleRow(
                        row.getAntigenCode(), row.getDoseNumber(), row.getLabel(),
                        row.getMinAgeDays(), row.getDueAgeDays(), row.getMinIntervalDays(),
                        row.getGraceDays(), row.getMaxAgeDays())).toList());
    }

    /** Today, for a screen that wants to default the period. On the {@code HMS_ZONE} chain. */
    public LocalDate today() {
        return clock.today();
    }

    private static ImmunisationDtos.MeasureResponse toResponse(QualityMeasure measure,
                                                               List<QualityMeasureAntigen> required) {
        return new ImmunisationDtos.MeasureResponse(measure.getCode(), measure.getName(),
                measure.getKind(), measure.getByAgeDays(), measure.getSteward(),
                measure.getSpecificationVersion(), measure.getInitialPopulation(),
                measure.getDenominator(), measure.getDenominatorExclusion(), measure.getNumerator(),
                measure.countsEstimatedDates(), measure.isActive(),
                required.stream()
                        .map(row -> new ImmunisationDtos.MeasureAntigenResponse(row.getAntigenCode(),
                                row.getDosesRequired()))
                        .toList());
    }
}
