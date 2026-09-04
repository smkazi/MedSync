package com.hms.immunisation.service;

import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Due;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Evaluation;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Exemption;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.GivenDose;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Schedule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A coverage rate: how many of these children were protected by the age the measure asks about.
 *
 * <p>The seventh pure calculator on this platform, after {@code SlotCalculator},
 * {@code News2Calculator}, {@code AllergyChecker}, {@code InteractionChecker}, {@code Pricer} and
 * {@code ImmunisationScheduleCalculator} — {@code public final}, private constructor, all static,
 * no Spring and no clock.
 *
 * <p><strong>It does not define what counts as a dose. It asks the schedule calculator.</strong>
 * That is the single most important line in this class. Two definitions of "a dose counts" is how a
 * measure and the screen above it come to disagree in front of the clinician being measured: the
 * register would tell a nurse a child is up to date and the district return would score them as
 * uncovered, and nobody looking at either could tell which was wrong. So the numerator is read off
 * {@link ImmunisationScheduleCalculator}'s own {@code dosesCounted}, and a change to the counting
 * rules moves both at once or neither.
 *
 * <p><strong>Evaluated as at each child's Nth birthday, never as at today.</strong> That is what
 * "by age two" means, and it is the difference between a rate and a rate that improves
 * retroactively: evaluated today, a dose given at three years old would start counting toward a
 * two-year-old figure, and last quarter's published number would rise every time somebody caught
 * up. The schedule calculator takes {@code asAt} as a parameter rather than reading a clock, which
 * is what makes this possible at all.
 *
 * <p><strong>A denominator of zero is a null rate, not zero per cent.</strong> "No children reached
 * their second birthday in this district last month" is not "zero per cent of them were
 * vaccinated", and rendering it as 0% would put a false failure into a return somebody signs.
 *
 * <p><strong>A medical contraindication excludes; a refusal does not.</strong> A clinic able to
 * exclude refusals could report full coverage by recording refusals, and the measure would then be
 * measuring the recording of refusals. The same behavioural split {@code ExemptionKind} makes for
 * the due list, and it is transcribed into the measure's own {@code denominator_exclusion} sentence
 * so a reader can check the code against the specification.
 */
public final class CoverageMeasureCalculator {

    /** Two decimal places on a percentage, which is what a district return is published to. */
    private static final int RATE_SCALE = 2;

    private CoverageMeasureCalculator() {
    }

    /** One antigen the measure requires, and how many counted doses of it. */
    public record Requirement(String antigenCode, int dosesRequired) {
    }

    /**
     * One child's inputs, as at their own birthday.
     *
     * @param dateOfBirth from patient-service. This service holds none, which is why a measure is
     *                    computed for a cohort somebody else enumerated
     */
    public record Subject(UUID patientId, String mrn, LocalDate dateOfBirth,
                          List<GivenDose> given, List<Exemption> exemptions) {

        public Subject {
            given = given == null ? List.of() : List.copyOf(given);
            exemptions = exemptions == null ? List.of() : List.copyOf(exemptions);
        }
    }

    /**
     * Why one child fell where they did.
     *
     * <p>Per-child reasons are computed and returned, and what the API does with them is a
     * disclosure decision rather than an arithmetic one: the aggregate rate carries none of this,
     * and the line list is a separate, administrator-only act. Keeping the reasons here is what
     * lets a rate be checked at all — a percentage nobody can decompose is a number that has to be
     * believed.
     *
     * @param shortfall the antigens this child was short of, and by how many doses. Empty when they
     *                  met the numerator
     */
    public record SubjectOutcome(UUID patientId, String mrn, LocalDate evaluatedOn,
                                 boolean inDenominator, boolean inNumerator, String because,
                                 Map<String, Integer> shortfall) {

        public SubjectOutcome {
            shortfall = shortfall == null ? Map.of() : Map.copyOf(shortfall);
        }
    }

    /**
     * The answer.
     *
     * @param rate percent to two places, or null when the denominator is zero. Null rather than
     *             zero, and rather than absent: the row exists, it has an initial population, and
     *             it has no rate — which is a different fact from a rate of nought
     */
    public record Result(int initialPopulation, int denominator, int numerator, BigDecimal rate,
                         List<SubjectOutcome> subjects) {

        public Result {
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
        }
    }

    /**
     * Computes the rate.
     *
     * <p>{@code countEstimatedDates} filters the input rather than being consulted during the
     * arithmetic, which keeps the counting rules in one place: a dose whose date is somebody's
     * recollection is either part of the register this measure reads or it is not.
     */
    public static Result evaluate(Schedule schedule, int byAgeDays, List<Requirement> requirements,
                                  List<Subject> subjects, boolean countEstimatedDates) {
        List<SubjectOutcome> outcomes = new ArrayList<>();
        int denominator = 0;
        int numerator = 0;

        for (Subject subject : subjects) {
            // Each child on their own birthday, from the MEASURE's age rather than the schedule's
            // bounds -- so a cohort read on one afternoon contains as many evaluation dates as
            // there are children in it, which is the point rather than an inconvenience.
            LocalDate evaluatedOn = subject.dateOfBirth().plusDays(byAgeDays);
            SubjectOutcome outcome = evaluateSubject(schedule, requirements, subject,
                    countEstimatedDates, evaluatedOn);
            outcomes.add(outcome);
            if (outcome.inDenominator()) {
                denominator++;
                if (outcome.inNumerator()) {
                    numerator++;
                }
            }
        }

        BigDecimal rate = denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
        return new Result(subjects.size(), denominator, numerator, rate, outcomes);
    }

    private static SubjectOutcome evaluateSubject(Schedule schedule, List<Requirement> requirements,
                                                  Subject subject, boolean countEstimatedDates,
                                                  LocalDate evaluatedOn) {
        // A live medical contraindication covering anything this measure requires takes the child
        // out of the denominator. A refusal does not, and that asymmetry is the measure's integrity.
        for (Requirement requirement : requirements) {
            boolean contraindicated = subject.exemptions().stream()
                    .anyMatch(exemption -> exemption.kind() == ExemptionKind.MEDICAL
                            && exemption.coversOn(requirement.antigenCode(), evaluatedOn));
            if (contraindicated) {
                return new SubjectOutcome(subject.patientId(), subject.mrn(), evaluatedOn, false,
                        false, ("Excluded: a medical contraindication covering %s was live on %s.")
                        .formatted(requirement.antigenCode(), evaluatedOn), Map.of());
            }
        }

        List<GivenDose> counted = countEstimatedDates
                ? subject.given()
                : subject.given().stream().filter(dose -> !dose.dateEstimated()).toList();

        Evaluation evaluation = ImmunisationScheduleCalculator.evaluate(subject.dateOfBirth(),
                schedule, counted, subject.exemptions(), evaluatedOn);

        Map<String, Integer> shortfall = new LinkedHashMap<>();
        for (Requirement requirement : requirements) {
            int had = dosesCounted(evaluation, requirement.antigenCode());
            if (had < requirement.dosesRequired()) {
                shortfall.put(requirement.antigenCode(), requirement.dosesRequired() - had);
            }
        }
        boolean met = shortfall.isEmpty();
        return new SubjectOutcome(subject.patientId(), subject.mrn(), evaluatedOn, true, met,
                met ? "Met: every antigen this measure requires was counted by " + evaluatedOn
                        : "Short by " + shortfall, shortfall);
    }

    /**
     * How many doses of one antigen the schedule calculator counted.
     *
     * <p>Read off its rows rather than recounted here, which is the whole point: the register's
     * screen and this rate cannot disagree about what a dose is, because there is one answer and
     * both read it. An antigen the schedule has no rows for counts zero — a measure requiring an
     * antigen the schedule does not deliver is a measure nobody can satisfy, and that is a
     * configuration error worth seeing as a zero rather than hiding as a pass.
     */
    private static int dosesCounted(Evaluation evaluation, String antigenCode) {
        return evaluation.due().stream()
                .filter(row -> row.antigenCode().equalsIgnoreCase(antigenCode))
                .mapToInt(Due::dosesCounted)
                .max()
                .orElse(0);
    }
}
