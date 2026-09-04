package com.hms.immunisation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.immunisation.domain.ImmunisationEnums.DueStatus;
import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Due;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Evaluation;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Exemption;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.GivenDose;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Schedule;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.ScheduleRow;
import com.hms.immunisation.service.ImmunisationScheduleCalculator.Uncounted;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The due calculator, with no Spring anywhere near it.
 *
 * <p><strong>The rows come from the published national schedule, not from the implementation.</strong>
 * That is {@code News2CalculatorTest}'s rule and it is the only thing that makes a test of a
 * standard mean anything: a fixture read off the code under test asserts that the code does what it
 * does. The numbers below — 42, 70 and 98 days for the 6, 10 and 14 week visits, 270 for the measles
 * dose at nine months, 28 days as the minimum interval between doses in a series — are the UIP
 * schedule converted to days once, in {@code V2__immunisation_schedule.sql}, and copied here.
 *
 * <p>Every boundary is tested on both sides, because "due on the tenth" is the kind of statement
 * that is off by one in production and looks right in a screenshot.
 */
class ImmunisationScheduleCalculatorTest {

    /** A fixed birthday, so every date in this file is arithmetic anybody can redo. */
    private static final LocalDate BORN = LocalDate.of(2026, 1, 1);

    // ---- fixtures ------------------------------------------------------------

    private static ScheduleRow row(String antigen, int doseNumber, int minAgeDays, int dueAgeDays,
                                   Integer minIntervalDays, int graceDays, Integer maxAgeDays) {
        return new ScheduleRow(antigen, doseNumber, "dose " + doseNumber, minAgeDays, dueAgeDays,
                minIntervalDays, graceDays, maxAgeDays);
    }

    /** A schedule covering the first six years, which is what UIP-2024's bounds are. */
    private static Schedule schedule(ScheduleRow... rows) {
        return new Schedule("UIP-TEST", "Test schedule", 0, 2192, Arrays.asList(rows));
    }

    private static GivenDose given(String antigen, LocalDate on) {
        return new GivenDose(UUID.randomUUID(), antigen, "PENTA", on, false);
    }

    private static GivenDose estimated(String antigen, LocalDate on) {
        return new GivenDose(UUID.randomUUID(), antigen, "PENTA", on, true);
    }

    private static Evaluation evaluate(Schedule schedule, List<GivenDose> given, LocalDate asAt) {
        return ImmunisationScheduleCalculator.evaluate(BORN, schedule, given, List.of(), asAt);
    }

    /** The one row for an antigen, which is what the calculator produces per antigen. */
    private static Due dueFor(Evaluation evaluation, String antigen) {
        List<Due> rows = evaluation.due().stream()
                .filter(row -> row.antigenCode().equals(antigen))
                .toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ---- the boundaries ------------------------------------------------------

    @Nested
    @DisplayName("not yet due, due, overdue")
    class Boundaries {

        /** Measles at nine months with a three-month grace period: UIP's own numbers. */
        private final Schedule measles = schedule(row("MEAS", 1, 270, 270, null, 90, null));

        @ParameterizedTest
        @CsvSource({
                // The day before the due date, the due date itself, the last day of grace, and the
                // day after it. Both sides of both boundaries.
                "269, NOT_YET_DUE",
                "270, DUE",
                "360, DUE",
                "361, OVERDUE"
        })
        @DisplayName("the status turns over on the day, not around it")
        void statusTurnsOverOnTheDay(int ageDays, DueStatus expected) {
            Evaluation evaluation = evaluate(measles, List.of(), BORN.plusDays(ageDays));

            assertThat(dueFor(evaluation, "MEAS").status()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the dates on the row say where the boundaries are")
        void theRowCarriesItsOwnDates() {
            Due due = dueFor(evaluate(measles, List.of(), BORN.plusDays(300)), "MEAS");

            assertThat(due.dueOn()).isEqualTo(BORN.plusDays(270));
            assertThat(due.overdueFrom()).isEqualTo(BORN.plusDays(361));
            // Day 300 is inside the grace period, so the sentence says where the next boundary is
            // rather than that it has passed. Both wordings are asserted; see theSentenceTurnsOver.
            assertThat(due.because()).contains("270 days old").contains("overdue from");
        }

        @Test
        @DisplayName("the sentence changes tense when the grace period runs out")
        void theSentenceTurnsOver() {
            assertThat(dueFor(evaluate(measles, List.of(), BORN.plusDays(400)), "MEAS").because())
                    .contains("overdue since " + BORN.plusDays(361));
        }

        @Test
        @DisplayName("a due row for a child with nothing recorded counts no doses")
        void nothingRecordedCountsNothing() {
            assertThat(dueFor(evaluate(measles, List.of(), BORN.plusDays(300)), "MEAS")
                    .dosesCounted()).isZero();
        }
    }

    // ---- what counts and what does not ---------------------------------------

    @Nested
    @DisplayName("a dose that does not advance the series")
    class Uncountable {

        /** The pentavalent series as UIP writes it: 6, 10 and 14 weeks, 28 days apart. */
        private final Schedule series = schedule(
                row("HIB", 1, 42, 42, null, 28, null),
                row("HIB", 2, 70, 70, 28, 28, null),
                row("HIB", 3, 98, 98, 28, 28, null));

        @ParameterizedTest
        @CsvSource({
                // The day before the minimum age and the day of it. A dose on day 41 does not
                // count and one on day 42 does, which is the whole of the rule.
                "41, 0",
                "42, 1"
        })
        @DisplayName("a dose before the minimum age counts for nothing; one on it counts")
        void tooEarlyDoesNotCount(int givenOnAge, int expectedCounted) {
            Evaluation evaluation = evaluate(series,
                    List.of(given("HIB", BORN.plusDays(givenOnAge))), BORN.plusDays(120));

            assertThat(dueFor(evaluation, "HIB").dosesCounted()).isEqualTo(expectedCounted);
        }

        @Test
        @DisplayName("an uncounted dose is returned with the rule that rejected it, not dropped")
        void anUncountedDoseSaysWhy() {
            Evaluation evaluation = evaluate(series, List.of(given("HIB", BORN.plusDays(28))),
                    BORN.plusDays(120));

            assertThat(evaluation.uncounted()).hasSize(1);
            Uncounted rejected = evaluation.uncounted().get(0);
            assertThat(rejected.doseNumberAttempted()).isEqualTo(1);
            assertThat(rejected.givenOn()).isEqualTo(BORN.plusDays(28));
            // The date it was measured against, in the sentence. A clinician can check "not before
            // 12 February" against a card; "does not count" is not checkable.
            assertThat(rejected.because()).contains(BORN.plusDays(42).toString());
            // And the series did not move: dose 1 is still what this child needs.
            assertThat(dueFor(evaluation, "HIB").doseNumber()).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({
                // Dose 1 on day 42; dose 2 needs 28 days after it. Day 69 is 27 days later and
                // does not count; day 70 is 28 and does.
                "69, 1",
                "70, 2"
        })
        @DisplayName("the minimum interval is measured from the previous counted dose")
        void theIntervalIsMeasuredFromThePreviousDose(int secondDoseAge, int expectedCounted) {
            Evaluation evaluation = evaluate(series,
                    List.of(given("HIB", BORN.plusDays(42)), given("HIB", BORN.plusDays(secondDoseAge))),
                    BORN.plusDays(200));

            assertThat(dueFor(evaluation, "HIB").dosesCounted()).isEqualTo(expectedCounted);
        }

        @Test
        @DisplayName("a fourth dose of a three-dose series is extra, and is recorded as extra")
        void anExtraDoseIsNamedAsOne() {
            Evaluation evaluation = evaluate(series, List.of(
                    given("HIB", BORN.plusDays(42)), given("HIB", BORN.plusDays(70)),
                    given("HIB", BORN.plusDays(98)), given("HIB", BORN.plusDays(200))),
                    BORN.plusDays(300));

            assertThat(dueFor(evaluation, "HIB").status()).isEqualTo(DueStatus.COMPLETE);
            assertThat(evaluation.uncounted()).hasSize(1);
            assertThat(evaluation.uncounted().get(0).because()).contains("complete at 3 dose(s)");
        }

        @Test
        @DisplayName("a remembered date still counts, and the row says it was remembered")
        void anEstimatedDateCountsAndIsFlagged() {
            Evaluation evaluation = evaluate(series, List.of(estimated("HIB", BORN.plusDays(42))),
                    BORN.plusDays(120));

            Due due = dueFor(evaluation, "HIB");
            assertThat(due.dosesCounted()).isEqualTo(1);
            // Counted because it is the best information there is, and flagged because it is not a
            // record. A coverage measure decides for itself whether to count one.
            assertThat(due.basedOnEstimatedDate()).isTrue();
        }
    }

    // ---- the case that catches people ----------------------------------------

    @Nested
    @DisplayName("a child who started late")
    class StartedLate {

        private final Schedule series = schedule(
                row("HIB", 1, 42, 42, null, 28, null),
                row("HIB", 2, 70, 70, 28, 28, null));

        @Test
        @DisplayName("the interval pushes dose 2 past its scheduled age, and the row says so")
        void theIntervalWins() {
            // Dose 1 given at 200 days, which is valid -- late, but past the minimum age. Dose 2's
            // scheduled age is 70 days, long gone; what it is actually due is 28 days after the
            // dose the child had.
            Evaluation evaluation = evaluate(series, List.of(given("HIB", BORN.plusDays(200))),
                    BORN.plusDays(210));

            Due due = dueFor(evaluation, "HIB");
            assertThat(due.doseNumber()).isEqualTo(2);
            assertThat(due.dueOn()).isEqualTo(BORN.plusDays(228));
            assertThat(due.status()).isEqualTo(DueStatus.NOT_YET_DUE);
            assertThat(due.because()).contains("28 days after the previous dose");
        }

        @Test
        @DisplayName("the earliest valid date moves with it, not only the due date")
        void theEarliestDateMovesToo() {
            Due due = dueFor(evaluate(series, List.of(given("HIB", BORN.plusDays(200))),
                    BORN.plusDays(210)), "HIB");

            assertThat(due.earliestOn()).isEqualTo(BORN.plusDays(228));
        }
    }

    // ---- windows that close --------------------------------------------------

    @Nested
    @DisplayName("a dose whose window has closed")
    class ClosedWindows {

        /** Hepatitis B as UIP has it: a birth dose, then the pentavalent series continues it. */
        private final Schedule hepB = schedule(
                row("HEPB", 1, 0, 0, null, 7, 14),
                row("HEPB", 2, 42, 42, 28, 28, null),
                row("HEPB", 3, 70, 70, 28, 28, null));

        @ParameterizedTest
        @CsvSource({
                // The last day of the window and the day after it. Day 14 is long past the seven
                // days of grace a birth dose gets, so it reads overdue — but it can still be
                // given, which is the distinction this pair exists to hold.
                "14, OVERDUE",
                "15, NO_LONGER_GIVEN"
        })
        @DisplayName("the window closes on the day the schedule says")
        void theWindowClosesOnTheDay(int ageDays, DueStatus expected) {
            Evaluation evaluation = evaluate(hepB, List.of(), BORN.plusDays(ageDays));

            assertThat(evaluation.due().stream()
                    .filter(row -> row.doseNumber() == 1)
                    .map(Due::status)
                    .findFirst()).contains(expected);
        }

        @Test
        @DisplayName("a closed dose is reported and the series moves on to the next one")
        void aClosedDoseIsReportedAndSkipped() {
            // Nothing recorded, and the child is sixty days old: the birth dose can never be given
            // now, and dose 2 is what the clinic should be calling about.
            Evaluation evaluation = evaluate(hepB, List.of(), BORN.plusDays(60));

            List<Due> rows = evaluation.due().stream()
                    .filter(row -> row.antigenCode().equals("HEPB")).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).status()).isEqualTo(DueStatus.NO_LONGER_GIVEN);
            assertThat(rows.get(0).because()).contains("no longer given after");
            assertThat(rows.get(1).doseNumber()).isEqualTo(2);
            // Due rather than overdue: dose 2 is expected at 42 days with 28 days of grace, so at
            // 60 days old this child is inside the window the schedule allows.
            assertThat(rows.get(1).status()).isEqualTo(DueStatus.DUE);
        }

        @Test
        @DisplayName("skipping a closed dose does not invent one: the count stays at zero")
        void skippingDoesNotCount() {
            // The bug this exists to stop. Advancing the row cursor past a closed window must not
            // advance the dose count, or the register would report a dose that never happened.
            Evaluation evaluation = evaluate(hepB, List.of(), BORN.plusDays(60));

            assertThat(evaluation.due()).allSatisfy(row -> assertThat(row.dosesCounted()).isZero());
        }

        @Test
        @DisplayName("a dose given after the window shut does not fill the row it closed on")
        void aLateDoseDoesNotBecomeABirthDose() {
            // The bug this test exists for, found by writing the API test above it: a pentavalent
            // dose at six weeks was being counted as the hepatitis B BIRTH dose, because the row's
            // minimum age is zero and only un-given rows were being checked against the window. The
            // register then said a child had their birth dose, on a date that proves they did not.
            // The dose belongs to dose 2, whose minimum age is 42 days, and that is where it goes.
            Evaluation evaluation = evaluate(hepB, List.of(given("HEPB", BORN.plusDays(42))),
                    BORN.plusDays(60));

            List<Due> rows = evaluation.due().stream()
                    .filter(row -> row.antigenCode().equals("HEPB")).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).doseNumber()).isEqualTo(1);
            assertThat(rows.get(0).status()).isEqualTo(DueStatus.NO_LONGER_GIVEN);
            // One dose counted, and it is the second one in the series.
            assertThat(rows.get(1).doseNumber()).isEqualTo(3);
            assertThat(rows.get(1).dosesCounted()).isEqualTo(1);
        }

        @Test
        @DisplayName("a series whose every window has shut is not reported as complete")
        void closedIsNotComplete() {
            // Found by reading the first due list this produced against a real cohort: a child past
            // every rotavirus window got three NO_LONGER_GIVEN rows and then a COMPLETE one saying
            // "0 of the 3 doses are recorded and counted", which is a contradiction on one screen.
            // Complete means every dose was given, not that the schedule ran out of rows to offer.
            Schedule rota = schedule(
                    row("ROTA", 1, 42, 42, null, 28, 105),
                    row("ROTA", 2, 70, 70, 28, 28, 240));

            Evaluation evaluation = evaluate(rota, List.of(), BORN.plusDays(300));

            assertThat(evaluation.due()).hasSize(2);
            assertThat(evaluation.due()).allSatisfy(rowSeen ->
                    assertThat(rowSeen.status()).isEqualTo(DueStatus.NO_LONGER_GIVEN));
        }

        @Test
        @DisplayName("a dose given inside the window counts, and nothing is skipped")
        void insideTheWindowItCounts() {
            Evaluation evaluation = evaluate(hepB, List.of(given("HEPB", BORN)), BORN.plusDays(60));

            Due due = dueFor(evaluation, "HEPB");
            assertThat(due.doseNumber()).isEqualTo(2);
            assertThat(due.dosesCounted()).isEqualTo(1);
        }
    }

    // ---- exemptions ----------------------------------------------------------

    @Nested
    @DisplayName("a recorded reason not to vaccinate")
    class Exemptions {

        private final Schedule measles = schedule(row("MEAS", 1, 270, 270, null, 90, null));

        private Evaluation withExemption(Exemption exemption, LocalDate asAt) {
            return ImmunisationScheduleCalculator.evaluate(BORN, measles, List.of(),
                    List.of(exemption), asAt);
        }

        @Test
        @DisplayName("a medical contraindication makes the dose exempt rather than due")
        void medicalExemptionSuppressesTheCall() {
            Due due = dueFor(withExemption(new Exemption("MEAS", ExemptionKind.MEDICAL, null),
                    BORN.plusDays(400)), "MEAS");

            assertThat(due.status()).isEqualTo(DueStatus.EXEMPT);
            assertThat(due.because()).contains("not to be given");
        }

        @Test
        @DisplayName("a blanket exemption with no antigen covers this one too")
        void aBlanketExemptionCovers() {
            assertThat(dueFor(withExemption(new Exemption(null, ExemptionKind.MEDICAL, null),
                    BORN.plusDays(400)), "MEAS").status()).isEqualTo(DueStatus.EXEMPT);
        }

        @Test
        @DisplayName("a refusal does not suppress the row: it is still overdue, and flagged")
        void aRefusalIsFlaggedAndStillDue() {
            // The judgement DueStatus argues. A family who declined last year is exactly who a
            // clinic may want to speak to again, and hiding them would make one refusal permanent
            // by accident -- so the row stays and carries what happened last time.
            Due due = dueFor(withExemption(new Exemption("MEAS", ExemptionKind.REFUSED, null),
                    BORN.plusDays(400)), "MEAS");

            assertThat(due.status()).isEqualTo(DueStatus.OVERDUE);
            assertThat(due.refusalRecorded()).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
                // An exemption expiring on day 400 is live on day 400 and gone on day 401. A
                // deferral until a course of steroids finishes must stop deferring.
                "400, EXEMPT",
                "401, OVERDUE"
        })
        @DisplayName("an exemption lapses on its date, and the dose becomes due again")
        void anExemptionLapses(int ageDays, DueStatus expected) {
            Exemption expiring =
                    new Exemption("MEAS", ExemptionKind.MEDICAL, BORN.plusDays(400));

            assertThat(dueFor(withExemption(expiring, BORN.plusDays(ageDays)), "MEAS").status())
                    .isEqualTo(expected);
        }
    }

    // ---- the schedule's own bounds -------------------------------------------

    @Nested
    @DisplayName("somebody the schedule is not about")
    class OutOfScope {

        @Test
        @DisplayName("an adult gets an answer saying the schedule has nothing to say, not a due list")
        void anAdultIsOutOfScope() {
            Evaluation evaluation = evaluate(schedule(row("MEAS", 1, 270, 270, null, 90, null)),
                    List.of(), BORN.plusDays(20_000));

            assertThat(evaluation.inSchedule()).isFalse();
            assertThat(evaluation.due()).isEmpty();
            assertThat(evaluation.uncounted()).isEmpty();
            // The bounds are in the sentence, so the answer is checkable rather than only empty.
            assertThat(evaluation.note()).contains("0 to 2192 days").contains("20000 days old");
        }

        @Test
        @DisplayName("evaluating before the patient was born is refused rather than answered")
        void aNegativeAgeIsRefused() {
            assertThatThrownBy(() -> evaluate(schedule(row("MEAS", 1, 270, 270, null, 90, null)),
                    List.of(), BORN.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("before they were born");
        }
    }

    // ---- the whole thing -----------------------------------------------------

    @Test
    @DisplayName("a child vaccinated on the published schedule reads complete on every antigen")
    void aFullyVaccinatedChildIsComplete() {
        // The UIP pentavalent visits: 6, 10 and 14 weeks. Read off the schedule, not off the code.
        Schedule series = schedule(
                row("HIB", 1, 42, 42, null, 28, null),
                row("HIB", 2, 70, 70, 28, 28, null),
                row("HIB", 3, 98, 98, 28, 28, null));

        Evaluation evaluation = evaluate(series, List.of(
                given("HIB", BORN.plusDays(42)), given("HIB", BORN.plusDays(70)),
                given("HIB", BORN.plusDays(98))), BORN.plusDays(365));

        Due due = dueFor(evaluation, "HIB");
        assertThat(due.status()).isEqualTo(DueStatus.COMPLETE);
        assertThat(due.dosesCounted()).isEqualTo(3);
        assertThat(evaluation.uncounted()).isEmpty();
        assertThat(evaluation.outstanding()).isEmpty();
    }

    @Test
    @DisplayName("outstanding is what a calling list is made of, and nothing else")
    void outstandingIsDueAndOverdueOnly() {
        Schedule mixed = schedule(
                row("MEAS", 1, 270, 270, null, 90, null),
                row("JE", 1, 480, 480, null, 180, null));

        Evaluation evaluation = evaluate(mixed, List.of(), BORN.plusDays(400));

        assertThat(evaluation.due()).hasSize(2);
        assertThat(evaluation.outstanding()).hasSize(1);
        assertThat(evaluation.outstanding().get(0).antigenCode()).isEqualTo("MEAS");
    }

    @Test
    @DisplayName("an evaluation of a past date does not know about doses given since")
    void asAtDoesNotUseHindsight() {
        // Found by asking a live stack what a child was due 270 days ago: it answered that their
        // next dose was measured from one they had not yet received. An evaluation of a past date
        // has to use the register as it stood then, or the coverage measure that reads this at a
        // child's second birthday counts doses given after it -- a rate that improves
        // retroactively.
        Schedule series = schedule(
                row("HIB", 1, 42, 42, null, 28, null),
                row("HIB", 2, 70, 70, 28, 28, null));
        List<GivenDose> doses = List.of(given("HIB", BORN.plusDays(42)));

        Due asAtDayThirty = dueFor(evaluate(series, doses, BORN.plusDays(30)), "HIB");
        Due asAtDaySixty = dueFor(evaluate(series, doses, BORN.plusDays(60)), "HIB");

        assertThat(asAtDayThirty.doseNumber()).isEqualTo(1);
        assertThat(asAtDayThirty.dosesCounted()).isZero();
        assertThat(asAtDaySixty.doseNumber()).isEqualTo(2);
        assertThat(asAtDaySixty.dosesCounted()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same dates answer the same way whatever zone the machine is in")
    void theArithmeticHasNoZoneInIt() {
        // The promise four comments in this module make, asserted rather than asserted-in-prose.
        // "28 days after dose 1" is a difference between two dates: if a zone had crept into this
        // class, the same inputs would answer differently on a machine set to Honolulu.
        Schedule series = schedule(
                row("HIB", 1, 42, 42, null, 28, null),
                row("HIB", 2, 70, 70, 28, 28, null));
        List<GivenDose> doses = List.of(given("HIB", BORN.plusDays(42)));
        LocalDate asAt = BORN.plusDays(69);

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            Due honolulu = dueFor(evaluate(series, doses, asAt), "HIB");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            Due kiritimati = dueFor(evaluate(series, doses, asAt), "HIB");

            assertThat(honolulu).isEqualTo(kiritimati);
            assertThat(honolulu.dueOn()).isEqualTo(BORN.plusDays(70));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
