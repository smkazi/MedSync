package com.hms.immunisation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import com.hms.immunisation.service.CoverageMeasureCalculator.Requirement;
import com.hms.immunisation.service.CoverageMeasureCalculator.Result;
import com.hms.immunisation.service.CoverageMeasureCalculator.Subject;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Exemption;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.GivenDose;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Schedule;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.ScheduleRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The coverage rate, with no Spring anywhere near it.
 *
 * <p>Two properties matter more than the arithmetic and each has a test of its own: the numerator is
 * evaluated <strong>as at each child's own birthday</strong> rather than as at today, and a medical
 * contraindication excludes a child while a refusal does not. The first is what stops a published
 * rate improving retroactively; the second is what stops a clinic reporting full coverage by
 * recording refusals.
 */
class CoverageMeasureCalculatorTest {

    /** Two doses of one antigen, at 42 and 70 days, twenty-eight days apart. */
    private static final Schedule SCHEDULE = new Schedule("TEST", "Test schedule", 0, 2192,
            List.of(new ScheduleRow("HIB", 1, "6 weeks", 42, 42, null, 28, null),
                    new ScheduleRow("HIB", 2, "10 weeks", 70, 70, 28, 28, null)));

    /** A measure asking about age one, wanting both doses. */
    private static final int BY_AGE_DAYS = 365;
    private static final List<Requirement> WANTS_BOTH = List.of(new Requirement("HIB", 2));

    private static Subject child(LocalDate bornOn, List<GivenDose> given,
                                 Exemption... exemptions) {
        return new Subject(UUID.randomUUID(), "MRN-" + UUID.randomUUID().toString().substring(0, 6),
                bornOn, given, Arrays.asList(exemptions));
    }

    private static GivenDose dose(LocalDate on) {
        return new GivenDose(UUID.randomUUID(), "HIB", "PENTA", on, false);
    }

    private static GivenDose remembered(LocalDate on) {
        return new GivenDose(UUID.randomUUID(), "HIB", "PENTA", on, true);
    }

    private static Result evaluate(boolean countEstimated, Subject... subjects) {
        return CoverageMeasureCalculator.evaluate(SCHEDULE, BY_AGE_DAYS, WANTS_BOTH,
                Arrays.asList(subjects), countEstimated);
    }

    // ---- the rate ------------------------------------------------------------

    @Test
    @DisplayName("a fully vaccinated cohort is a hundred per cent")
    void aCoveredCohort() {
        LocalDate born = LocalDate.of(2025, 1, 1);
        Result result = evaluate(false,
                child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(70)))),
                child(born, List.of(dose(born.plusDays(50)), dose(born.plusDays(90)))));

        assertThat(result.initialPopulation()).isEqualTo(2);
        assertThat(result.denominator()).isEqualTo(2);
        assertThat(result.numerator()).isEqualTo(2);
        assertThat(result.rate()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("one child short of a dose is half of two")
    void aPartlyCoveredCohort() {
        LocalDate born = LocalDate.of(2025, 1, 1);
        Result result = evaluate(false,
                child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(70)))),
                child(born, List.of(dose(born.plusDays(42)))));

        assertThat(result.numerator()).isEqualTo(1);
        assertThat(result.rate()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("the rate says which antigen a child was short of, and by how many")
    void theShortfallIsNamed() {
        LocalDate born = LocalDate.of(2025, 1, 1);
        Result result = evaluate(false, child(born, List.of(dose(born.plusDays(42)))));

        // A percentage nobody can decompose is a number that has to be believed. AllergyChecker's
        // rule, one module along: "matched on AMOXICILLIN" is checkable and "allergy detected" is
        // not.
        assertThat(result.subjects()).hasSize(1);
        assertThat(result.subjects().get(0).shortfall()).containsEntry("HIB", 1);
        assertThat(result.subjects().get(0).because()).contains("Short by");
    }

    @Test
    @DisplayName("a denominator of nobody is a null rate, not zero per cent")
    void anEmptyDenominatorIsNull() {
        Result result = evaluate(false);

        // "No children reached their first birthday in this district last month" is not "zero per
        // cent of them were vaccinated", and rendering it as 0% would put a false failure into a
        // return somebody signs.
        assertThat(result.initialPopulation()).isZero();
        assertThat(result.denominator()).isZero();
        assertThat(result.rate()).isNull();
    }

    @Test
    @DisplayName("a rate is rounded to two places, half up, like every other published number")
    void theRateIsRoundedNotTruncated() {
        LocalDate born = LocalDate.of(2025, 1, 1);
        Result result = evaluate(false,
                child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(70)))),
                child(born, List.of()),
                child(born, List.of()));

        // One of three. Truncation would publish 33.33 and so does rounding here, which is the
        // point of pinning it: the next third-of-a-cohort would differ.
        assertThat(result.rate()).isEqualByComparingTo(new BigDecimal("33.33"));
    }

    // ---- the two properties that matter --------------------------------------

    @Nested
    @DisplayName("as at the birthday, not as at today")
    class AsAtTheBirthday {

        @Test
        @DisplayName("a dose given after the birthday does not count toward that birthday's rate")
        void aLaterDoseDoesNotCount() {
            LocalDate born = LocalDate.of(2025, 1, 1);

            // Both doses given, but the second one at eighteen months -- after the first birthday
            // this measure asks about. Counting it would make last year's published rate rise the
            // day somebody caught up, which is a number nobody can audit.
            Result result = evaluate(false,
                    child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(540)))));

            assertThat(result.numerator()).isZero();
            assertThat(result.rate()).isEqualByComparingTo("0.00");
            assertThat(result.subjects().get(0).evaluatedOn()).isEqualTo(born.plusDays(365));
        }

        @Test
        @DisplayName("the same doses inside the year do count")
        void anEarlierDoseCounts() {
            LocalDate born = LocalDate.of(2025, 1, 1);

            Result result = evaluate(false,
                    child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(364)))));

            assertThat(result.numerator()).isEqualTo(1);
        }

        @Test
        @DisplayName("each child is evaluated on their own birthday, not the cohort's")
        void everyChildHasTheirOwnDate() {
            // A cohort read on one afternoon contains as many evaluation dates as there are
            // children in it, which is the point rather than an inconvenience.
            Result result = evaluate(false,
                    child(LocalDate.of(2025, 1, 1), List.of()),
                    child(LocalDate.of(2025, 6, 30), List.of()));

            assertThat(result.subjects().get(0).evaluatedOn()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(result.subjects().get(1).evaluatedOn()).isEqualTo(LocalDate.of(2026, 6, 30));
        }
    }

    @Nested
    @DisplayName("a contraindication excludes; a refusal does not")
    class Exemptions {

        private final LocalDate born = LocalDate.of(2025, 1, 1);

        @Test
        @DisplayName("a medical contraindication takes the child out of the denominator")
        void medicalExcludes() {
            Result result = evaluate(false, child(born, List.of(),
                    new Exemption("HIB", ExemptionKind.MEDICAL, null)));

            assertThat(result.initialPopulation()).isEqualTo(1);
            assertThat(result.denominator()).isZero();
            // Out of the denominator, so the rate has nobody in it -- not a failing child.
            assertThat(result.rate()).isNull();
            assertThat(result.subjects().get(0).because()).contains("Excluded");
        }

        @Test
        @DisplayName("a refusal stays in the denominator and counts against the rate")
        void refusalDoesNotExclude() {
            // The measure's integrity. A clinic able to exclude refusals could report full coverage
            // by recording refusals, and the measure would then be measuring the recording of
            // refusals rather than the vaccination of children.
            Result result = evaluate(false, child(born, List.of(),
                    new Exemption("HIB", ExemptionKind.REFUSED, null)));

            assertThat(result.denominator()).isEqualTo(1);
            assertThat(result.numerator()).isZero();
            assertThat(result.rate()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("an exemption that had lapsed by the birthday does not exclude")
        void alapsedExemptionDoesNotExclude() {
            // Liveness is decided as at the evaluation date rather than as at today, so a deferral
            // that ended before the birthday is not an exclusion from that birthday's rate.
            Result result = evaluate(false, child(born, List.of(),
                    new Exemption("HIB", ExemptionKind.MEDICAL, born.plusDays(100))));

            assertThat(result.denominator()).isEqualTo(1);
        }

        @Test
        @DisplayName("an exemption for an antigen the measure does not name does not exclude")
        void anUnrelatedExemptionDoesNotExclude() {
            Result result = evaluate(false, child(born,
                    List.of(dose(born.plusDays(42)), dose(born.plusDays(70))),
                    new Exemption("MEAS", ExemptionKind.MEDICAL, null)));

            assertThat(result.denominator()).isEqualTo(1);
            assertThat(result.numerator()).isEqualTo(1);
        }
    }

    // ---- what counts ---------------------------------------------------------

    @Test
    @DisplayName("a dose that does not count for the schedule does not count for the measure")
    void oneDefinitionOfADose() {
        LocalDate born = LocalDate.of(2025, 1, 1);

        // Dose 2 given fourteen days after dose 1, where the schedule wants twenty-eight. The
        // schedule calculator does not count it, and neither does this -- because this asks that
        // one rather than counting doses itself. Two definitions of "a dose counts" is how a
        // measure and the screen above it come to disagree in front of the clinician being
        // measured.
        Result result = evaluate(false,
                child(born, List.of(dose(born.plusDays(42)), dose(born.plusDays(56)))));

        assertThat(result.numerator()).isZero();
        assertThat(result.subjects().get(0).shortfall()).containsEntry("HIB", 1);
    }

    @Test
    @DisplayName("a remembered date counts only when the measure says it does")
    void estimatedDatesAreAParameter() {
        LocalDate born = LocalDate.of(2025, 1, 1);
        Subject remembers = child(born,
                List.of(remembered(born.plusDays(42)), remembered(born.plusDays(70))));

        // A rate that silently counted recollected dates would be higher than one that did not,
        // with nobody reading it able to tell which they had. So it is a column on the measure, and
        // the same child scores differently under the two settings -- which is the honest outcome.
        assertThat(CoverageMeasureCalculator.evaluate(SCHEDULE, BY_AGE_DAYS, WANTS_BOTH,
                List.of(remembers), false).numerator()).isZero();
        assertThat(CoverageMeasureCalculator.evaluate(SCHEDULE, BY_AGE_DAYS, WANTS_BOTH,
                List.of(remembers), true).numerator()).isEqualTo(1);
    }

    @Test
    @DisplayName("an antigen the schedule has no rows for counts zero rather than passing")
    void anUnschedulableRequirementScoresZero() {
        LocalDate born = LocalDate.of(2025, 1, 1);

        // A measure requiring something the schedule does not deliver is a configuration error, and
        // it shows up as a rate of nought rather than hiding as a pass. Silently treating "no rows"
        // as "satisfied" would report full coverage for an antigen nobody gives.
        Result result = CoverageMeasureCalculator.evaluate(SCHEDULE, BY_AGE_DAYS,
                List.of(new Requirement("JE", 1)),
                List.of(child(born, List.of(dose(born.plusDays(42))))), false);

        assertThat(result.denominator()).isEqualTo(1);
        assertThat(result.numerator()).isZero();
        assertThat(result.subjects().get(0).shortfall()).containsEntry("JE", 1);
    }
}
