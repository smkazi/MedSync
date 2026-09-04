package com.hms.immunisation.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.immunisation.client.PatientCohortClient;
import com.hms.immunisation.domain.Immunisation;
import com.hms.immunisation.domain.ImmunisationExemption;
import com.hms.immunisation.domain.ImmunisationSchedule;
import com.hms.immunisation.domain.ScheduleDose;
import com.hms.immunisation.repo.ExemptionRepository;
import com.hms.immunisation.repo.ImmunisationRepository;
import com.hms.immunisation.repo.ScheduleDoseRepository;
import com.hms.immunisation.repo.ScheduleRepository;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Evaluation;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.GivenDose;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Schedule;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.ScheduleRow;
import com.hms.immunisation.web.dto.ImmunisationDtos;
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
 * The schedule as a read, and the calling list that comes out of it.
 *
 * <p><strong>Nothing here is materialised and nothing is cached.</strong> The whole due list is
 * computed on every read, and the argument is in {@link ImmunisationScheduleCalculator}: the state
 * transition that matters — a dose becoming overdue — has no event behind it, so a stored answer
 * would be a cache whose invalidation key is the wall clock.
 *
 * <p><strong>A due list is asked for a birth cohort, and that is the whole shape of this API.</strong>
 * There is no "every overdue child in the district" query and there deliberately is not one: this
 * service holds no date of birth, so an unbounded due list would mean shipping every patient
 * identifier on the platform across a service boundary in order to compute against them. An
 * immunisation clinic works a fortnight of birthdays at a time, patient-service answers that from an
 * index it already has, and this service makes one {@code patient_id in (:ids)} read against the
 * register.
 *
 * <p><strong>Not narrowed per row</strong>, which is the one deliberate exception
 * {@code Roles.IMMUNISATION_READ} already documents. Calling a birth cohort in for their
 * vaccinations is inherently cross-patient work: narrowing it would answer with the caller's own
 * patients, which is not a cohort and would silently drop every child whose doctor is somebody else.
 * What stands in for the narrowing is the cohort gate itself — {@code PATIENT_COHORT_READ} in
 * patient-service, enforced there against the caller's own forwarded token — so a pharmacist who
 * holds {@code IMMUNISATION_READ} still cannot obtain a list of children and their birthdays.
 */
@Service
public class DueListService {

    private final ScheduleRepository schedules;
    private final ScheduleDoseRepository scheduleDoses;
    private final ImmunisationRepository register;
    private final ExemptionRepository exemptions;
    private final CatalogueService catalogue;
    private final PatientCohortClient cohorts;
    private final ImmunisationClock clock;
    private final AuditService audit;
    private final String defaultScheduleCode;

    public DueListService(ScheduleRepository schedules, ScheduleDoseRepository scheduleDoses,
                          ImmunisationRepository register, ExemptionRepository exemptions,
                          CatalogueService catalogue, PatientCohortClient cohorts,
                          ImmunisationClock clock, AuditService audit,
                          @Value("${hms.immunisation.default-schedule:UIP-2024}")
                          String defaultScheduleCode) {
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

    // ---- the schedule as configuration ---------------------------------------

    @Transactional(readOnly = true)
    public List<ImmunisationDtos.ScheduleResponse> published() {
        return schedules.findByActiveTrueOrderByCodeAsc().stream()
                .map(schedule -> toResponse(schedule,
                        scheduleDoses.findByScheduleCodeOrderByAntigenCodeAscDoseNumberAsc(
                                schedule.getCode())))
                .toList();
    }

    // ---- the due list --------------------------------------------------------

    /**
     * What the children born between two dates are due, as at today.
     *
     * <p>{@code asAt} is the hospital's day rather than the container's — {@link ImmunisationClock},
     * on the {@code HMS_ZONE} chain — and it is resolved once here and passed down, so every child
     * in one list is evaluated against the same date. Resolving it per child would let a list
     * spanning local midnight answer two different questions.
     *
     * @param asAt an explicit date to evaluate against, or null for today. Present because the
     *             calculator takes a date rather than reading a clock, so "what was due on the
     *             first" is answerable — which is what a coverage measure needs and what makes the
     *             arithmetic checkable against a chart
     */
    @Transactional(readOnly = true)
    public ImmunisationDtos.DueListResponse due(LocalDate bornFrom, LocalDate bornTo,
                                                String scheduleCode, Integer limit, LocalDate asAt) {
        if (bornFrom == null || bornTo == null) {
            throw new BadRequestException(
                    "A due list is asked for a birth cohort: bornFrom and bornTo are both required.");
        }
        if (bornTo.isBefore(bornFrom)) {
            throw new BadRequestException(("A birth range from %s to %s runs backwards.")
                    .formatted(bornFrom, bornTo));
        }
        LocalDate on = asAt == null ? clock.today() : asAt;
        String code = scheduleCode == null || scheduleCode.isBlank()
                ? defaultScheduleCode : scheduleCode;
        ImmunisationSchedule published = schedules.findByCode(code).orElseThrow(
                () -> new NotFoundException(("No immunisation schedule '%s'. A due list has to be "
                        + "computed against a schedule somebody published.").formatted(code)));
        List<ScheduleDose> rows =
                scheduleDoses.findByScheduleCodeOrderByAntigenCodeAscDoseNumberAsc(code);
        if (rows.isEmpty()) {
            // An empty schedule would answer "nothing is due" for every child in the cohort, which
            // reads exactly like a fully vaccinated population -- a wrong answer that looks like
            // good news, and therefore one nobody checks.
            throw new BadRequestException(("Schedule '%s' has no doses in it, so it cannot say what "
                    + "anybody is due.").formatted(code));
        }
        Schedule schedule = new Schedule(published.getCode(), published.getName(),
                published.getAppliesFromAgeDays(), published.getAppliesToAgeDays(),
                rows.stream().map(DueListService::toRow).toList());

        PatientCohortClient.Cohort cohort = cohorts.bornBetween(bornFrom, bornTo, limit);

        // The range, the schedule and the counts. No patient identifier and no clinical word: this
        // read is about a period, so entityId is null, exactly as the patient cohort lookup and the
        // identity lookup it sits behind record theirs.
        audit.record("IMMUNISATION_DUE_LIST", "Immunisation", null,
                "%d child(ren) born %s..%s against %s as at %s, for %s".formatted(
                        cohort.members().size(), bornFrom, bornTo, code, on,
                        CurrentUser.usernameOrSystem()));

        if (cohort.members().isEmpty()) {
            return new ImmunisationDtos.DueListResponse(published.getCode(), published.getName(),
                    on, bornFrom, bornTo, 0, cohort.total(), cohort.truncated(), cohort.note(),
                    List.of());
        }

        List<UUID> ids = cohort.members().stream().map(PatientCohortClient.CohortMember::id).toList();
        Map<UUID, List<GivenDose>> givenByPatient = givenDoses(ids);
        Map<UUID, List<ImmunisationScheduleCalculator.Exemption>> exemptByPatient = exemptions(ids);

        List<ImmunisationDtos.PatientDueResponse> children = new ArrayList<>();
        for (PatientCohortClient.CohortMember member : cohort.members()) {
            Evaluation evaluation = ImmunisationScheduleCalculator.evaluate(member.dateOfBirth(),
                    schedule, givenByPatient.getOrDefault(member.id(), List.of()),
                    exemptByPatient.getOrDefault(member.id(), List.of()), on);
            children.add(new ImmunisationDtos.PatientDueResponse(member.id(), member.mrn(),
                    member.fullName(), member.dateOfBirth(), evaluation.ageDays(),
                    evaluation.inSchedule(), evaluation.note(),
                    evaluation.due().stream().map(DueListService::toResponse).toList(),
                    evaluation.uncounted().stream().map(DueListService::toResponse).toList()));
        }
        return new ImmunisationDtos.DueListResponse(published.getCode(), published.getName(), on,
                bornFrom, bornTo, children.size(), cohort.total(), cohort.truncated(),
                cohort.note(), children);
    }

    /**
     * The register for a whole cohort, expanded from products into antigens.
     *
     * <p>This is the one place the two vocabularies meet. A schedule is written in antigens and the
     * register records products, so a pentavalent dose becomes five entries — which is the model
     * rather than a shortcut: those five antigens have five independent series, and the same vial
     * can be dose 2 of one and dose 4 of another.
     *
     * <p>Two queries for the whole cohort rather than two per child. A product whose contents
     * cannot be resolved is skipped with nothing invented, which cannot happen through the API — a
     * dose carries a foreign key to its product — and would mean a row inserted by hand.
     */
    private Map<UUID, List<GivenDose>> givenDoses(List<UUID> patientIds) {
        Map<String, Set<String>> antigensByProduct = catalogue.antigensByProduct();
        Map<UUID, List<GivenDose>> byPatient = new HashMap<>();
        for (Immunisation dose : register.forCohort(patientIds)) {
            for (String antigen : antigensByProduct.getOrDefault(dose.getProductCode(), Set.of())) {
                byPatient.computeIfAbsent(dose.getPatientId(), key -> new ArrayList<>())
                        .add(new GivenDose(dose.getId(), antigen, dose.getProductCode(),
                                dose.getGivenOn(), dose.isGivenOnEstimated()));
            }
        }
        return byPatient;
    }

    private Map<UUID, List<ImmunisationScheduleCalculator.Exemption>> exemptions(List<UUID> ids) {
        Map<UUID, List<ImmunisationScheduleCalculator.Exemption>> byPatient = new HashMap<>();
        for (ImmunisationExemption exemption : exemptions.findByPatientIdIn(ids)) {
            byPatient.computeIfAbsent(exemption.getPatientId(), key -> new ArrayList<>())
                    .add(new ImmunisationScheduleCalculator.Exemption(exemption.getAntigenCode(),
                            exemption.getKind(), exemption.getExpiresOn()));
        }
        return byPatient;
    }

    // ---- mapping -------------------------------------------------------------

    private static ScheduleRow toRow(ScheduleDose dose) {
        return new ScheduleRow(dose.getAntigenCode(), dose.getDoseNumber(), dose.getLabel(),
                dose.getMinAgeDays(), dose.getDueAgeDays(), dose.getMinIntervalDays(),
                dose.getGraceDays(), dose.getMaxAgeDays());
    }

    private static ImmunisationDtos.ScheduleResponse toResponse(ImmunisationSchedule schedule,
                                                                List<ScheduleDose> doses) {
        return new ImmunisationDtos.ScheduleResponse(schedule.getCode(), schedule.getName(),
                schedule.getAppliesFromAgeDays(), schedule.getAppliesToAgeDays(),
                schedule.getSource(), schedule.isActive(),
                doses.stream()
                        .map(dose -> new ImmunisationDtos.ScheduleDoseResponse(dose.getAntigenCode(),
                                dose.getDoseNumber(), dose.getLabel(), dose.getMinAgeDays(),
                                dose.getDueAgeDays(), dose.getMinIntervalDays(), dose.getGraceDays(),
                                dose.getMaxAgeDays()))
                        .toList());
    }

    private static ImmunisationDtos.DueResponse toResponse(ImmunisationScheduleCalculator.Due due) {
        return new ImmunisationDtos.DueResponse(due.antigenCode(), due.doseNumber(), due.label(),
                due.status(), due.earliestOn(), due.dueOn(), due.overdueFrom(),
                due.windowClosesOn(), due.dosesCounted(), due.basedOnEstimatedDate(),
                due.refusalRecorded(), due.because());
    }

    private static ImmunisationDtos.UncountedDoseResponse toResponse(
            ImmunisationScheduleCalculator.Uncounted uncounted) {
        return new ImmunisationDtos.UncountedDoseResponse(uncounted.doseId(),
                uncounted.antigenCode(), uncounted.productCode(), uncounted.givenOn(),
                uncounted.doseNumberAttempted(), uncounted.because());
    }
}
