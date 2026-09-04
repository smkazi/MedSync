package com.hms.immunisation.service;

import com.hms.immunisation.domain.ImmunisationEnums.DueStatus;
import com.hms.immunisation.domain.ImmunisationEnums.ExemptionKind;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What this child is due, as at a date.
 *
 * <p>The sixth pure calculator on this platform, and it names its predecessor for the same reason
 * each of the others does — {@code SlotCalculator}, then {@code News2Calculator}, then
 * {@code AllergyChecker}, then {@code InteractionChecker}, then {@code Pricer}. Each is a
 * {@code public final class} with a private constructor and nothing but static methods, sitting
 * beside the {@code @Service} that calls it and holding none of its dependencies. The chain is the
 * only mechanism by which a reader who finds one finds the other five.
 *
 * <p><strong>No {@code ZoneId} appears in this file, and none appears in its test.</strong> That is
 * not incidental tidiness: four comments written a commit before this one promise it —
 * {@code ImmunisationClock}, {@code Immunisation.givenOn}, {@code V1__immunisation_schema.sql} and
 * {@code application.yml} each say that the schedule arithmetic reads no clock. "28 days after dose
 * 1" is a difference between two dates and has no zone in it, so {@code asAt} is a parameter and
 * never a clock read. Two things fall out of that and both are the point: a schedule means exactly
 * the same thing in a UTC container and in an IST one, and this class can answer "what was due on
 * the first" — which is what makes a coverage measure evaluable at a child's second birthday rather
 * than only today.
 *
 * <p><strong>Computed on read, never stored.</strong> A dose becomes overdue because a day passed.
 * Nothing happens, nobody writes a row, no event is published — so a materialised due table would
 * be a cache whose invalidation key is the wall clock, and the only thing that could refresh it is
 * a scheduler this platform deliberately does not have. The specific failure that argument is
 * about: a stale row saying DUE for a child vaccinated yesterday is worse than no row, because
 * somebody telephones a mother about an appointment she kept, and the register — which is right —
 * never gets consulted.
 *
 * <p><strong>It knows nothing about products.</strong> Its input is antigens, because that is what
 * a schedule is written in and what coverage is a question about: a child who had a pentavalent
 * vial is covered for its five antigens under every trade name it has ever been sold under. The
 * caller expands each recorded dose into the antigens its product contains before calling in.
 *
 * <p><strong>An invalid dose does not advance the series and is not dropped.</strong> A dose given
 * before {@code minAgeDays}, or sooner after the previous one than {@code minIntervalDays} allows,
 * comes back in {@link Evaluation#uncounted()} with a sentence naming the rule and the date it was
 * measured against. Silently ignoring it would be the worst of the three options available: the
 * clinician would see the dose in the register, see the child still due, and have no way to tell
 * whether the platform had counted it.
 *
 * <p>Every row it produces says <em>why</em>, which is {@code AllergyChecker.Finding.matchedOn}'s
 * rule — "matched on AMOXICILLIN" is checkable and "allergy detected" is not. Here that means a due
 * row names the date it is due from and what set it, and an uncounted row names the rule that
 * rejected it and the boundary date.
 *
 * <p>Its worked examples come from the published national schedule rather than from this
 * implementation, which is {@code News2CalculatorTest}'s rule and the only way the test means
 * anything.
 */
public final class ImmunisationScheduleCalculator {

    private ImmunisationScheduleCalculator() {
    }

    // ---- what goes in --------------------------------------------------------

    /**
     * One expected dose, in days from date of birth.
     *
     * @param minAgeDays      the earliest age at which this dose is valid. A dose before it does not
     *                        count: an immune system does not respond to a vaccine given too early,
     *                        so counting one would record protection the child does not have
     * @param dueAgeDays      the age at which it is expected. A different question from the minimum
     *                        — one decides whether a dose that happened counts, the other decides
     *                        whether to telephone — and deliberately not assumed equal to it
     * @param minIntervalDays days since the previous counted dose of this antigen. Null on dose 1,
     *                        where there is nothing to measure from
     * @param graceDays       how long after the due date this stays {@code DUE} before it reads
     *                        {@code OVERDUE}
     * @param maxAgeDays      the age after which this dose is no longer given at all, or null when
     *                        the window never closes
     */
    public record ScheduleRow(String antigenCode, int doseNumber, String label, int minAgeDays,
                              int dueAgeDays, Integer minIntervalDays, int graceDays,
                              Integer maxAgeDays) {
    }

    /**
     * A schedule, with the age bounds it applies to.
     *
     * <p>The bounds travel with the rows rather than being a separate parameter, because a set of
     * dose rows without them is answerable for anybody: it would produce a due list for a
     * sixty-year-old out of rows written for infants, in the same table and the same colour as the
     * answers that are right.
     */
    public record Schedule(String code, String name, int appliesFromAgeDays, int appliesToAgeDays,
                           List<ScheduleRow> doses) {

        public Schedule {
            doses = doses == null ? List.of() : List.copyOf(doses);
        }
    }

    /**
     * One dose already in the register, for one antigen.
     *
     * <p>A recorded dose of a combination product becomes one of these per antigen it contains, so
     * a pentavalent vial arrives here five times with the same {@code doseId} and date. That is the
     * model rather than a shortcut: the five antigens have five independent series, and the same
     * vial can be dose 2 of one and dose 4 of another.
     *
     * @param dateEstimated true when the date is somebody's recollection rather than a record. It
     *                      still counts — it is the best information there is — and the flag travels
     *                      onto the due row so a screen can say so
     */
    public record GivenDose(UUID doseId, String antigenCode, String productCode, LocalDate givenOn,
                            boolean dateEstimated) {
    }

    /**
     * A recorded reason a dose will not be given.
     *
     * @param antigenCode null means every antigen — a blanket exemption, which is a real clinical
     *                    situation
     * @param expiresOn   null means it does not lapse. Liveness is decided against {@code asAt}
     *                    inside this class rather than by the caller, so an evaluation of a past
     *                    date uses the exemptions that were live then
     */
    public record Exemption(String antigenCode, ExemptionKind kind, LocalDate expiresOn) {

        boolean coversOn(String antigen, LocalDate asAt) {
            boolean applies = antigenCode == null || antigenCode.equalsIgnoreCase(antigen);
            boolean live = expiresOn == null || !expiresOn.isBefore(asAt);
            return applies && live;
        }
    }

    // ---- what comes out -----------------------------------------------------

    /**
     * Where one antigen stands, and why.
     *
     * @param earliestOn   the first date this dose could validly be given
     * @param dueOn        the date it is expected. Later than the age alone when a minimum interval
     *                     since the previous dose pushes it out, which is the case that catches
     *                     people: a child who started late is not due their second dose on the
     *                     schedule's date, they are due it an interval after their first
     * @param overdueFrom  the first date this reads {@code OVERDUE}
     * @param because      the sentence a clinician can check the row against
     */
    public record Due(String antigenCode, int doseNumber, String label, DueStatus status,
                      LocalDate earliestOn, LocalDate dueOn, LocalDate overdueFrom,
                      LocalDate windowClosesOn, int dosesCounted, boolean basedOnEstimatedDate,
                      boolean refusalRecorded, String because) {
    }

    /** A dose in the register that does not advance a series, and the rule that says so. */
    public record Uncounted(UUID doseId, String antigenCode, String productCode, LocalDate givenOn,
                            int doseNumberAttempted, String because) {
    }

    /**
     * The answer for one child.
     *
     * @param inSchedule false when the child is outside the schedule's age bounds, in which case
     *                   {@code note} says so and both lists are empty. Not an exception: "this
     *                   schedule has nothing to say about this person" is an answer
     */
    public record Evaluation(int ageDays, boolean inSchedule, String note, List<Due> due,
                             List<Uncounted> uncounted) {

        public Evaluation {
            due = due == null ? List.of() : List.copyOf(due);
            uncounted = uncounted == null ? List.of() : List.copyOf(uncounted);
        }

        /** What a calling list is made of: due now, or overdue. */
        public List<Due> outstanding() {
            return due.stream()
                    .filter(row -> row.status() == DueStatus.DUE || row.status() == DueStatus.OVERDUE)
                    .toList();
        }
    }

    // ---- the arithmetic -----------------------------------------------------

    /**
     * Evaluates one child against one schedule.
     *
     * <p>{@code asAt} is last, following {@code SlotCalculator}'s {@code Instant now}, and it is a
     * date rather than a clock for the reason this class exists to demonstrate.
     *
     * @throws IllegalArgumentException if {@code asAt} precedes the date of birth. Not a silently
     *                                  empty answer: a negative age is a caller bug or a mistyped
     *                                  birthday, and both need looking at rather than rendering
     */
    public static Evaluation evaluate(LocalDate dateOfBirth, Schedule schedule,
                                      List<GivenDose> given, List<Exemption> exemptions,
                                      LocalDate asAt) {
        long age = ChronoUnit.DAYS.between(dateOfBirth, asAt);
        if (age < 0) {
            throw new IllegalArgumentException(("Cannot evaluate a schedule as at %s for a patient "
                    + "born on %s: that is before they were born.").formatted(asAt, dateOfBirth));
        }
        int ageDays = (int) Math.min(age, Integer.MAX_VALUE);
        List<Exemption> live = exemptions == null ? List.of() : exemptions;

        if (ageDays < schedule.appliesFromAgeDays() || ageDays > schedule.appliesToAgeDays()) {
            return new Evaluation(ageDays, false, ("%s covers ages %d to %d days; this patient is "
                    + "%d days old, so it has nothing to say about them.").formatted(schedule.code(),
                    schedule.appliesFromAgeDays(), schedule.appliesToAgeDays(), ageDays),
                    List.of(), List.of());
        }

        Map<String, List<ScheduleRow>> rowsByAntigen = groupRows(schedule.doses());
        Map<String, List<GivenDose>> givenByAntigen = groupGiven(given, asAt);

        List<Due> due = new ArrayList<>();
        List<Uncounted> uncounted = new ArrayList<>();
        for (Map.Entry<String, List<ScheduleRow>> entry : rowsByAntigen.entrySet()) {
            evaluateAntigen(dateOfBirth, entry.getKey(), entry.getValue(),
                    givenByAntigen.getOrDefault(entry.getKey(), List.of()), live, asAt, due,
                    uncounted);
        }
        return new Evaluation(ageDays, true, null, due, uncounted);
    }

    /**
     * One antigen's series: which recorded doses count, and where that leaves the child.
     *
     * <p>Walked in date order, matching each recorded dose against the next dose the schedule
     * expects. A dose that fails a rule does not consume the row it was measured against — the next
     * valid dose is still dose 2 — which is what makes a too-early dose a dose that has to be
     * repeated rather than one that shifts the whole series.
     */
    private static void evaluateAntigen(LocalDate dateOfBirth, String antigen,
                                        List<ScheduleRow> rows, List<GivenDose> doses,
                                        List<Exemption> exemptions, LocalDate asAt,
                                        List<Due> due, List<Uncounted> uncounted) {
        // Two counters, and they are not the same number once a window closes. `cursor` is the row
        // the schedule is up to; `countedDoses` is how many doses the child actually has. Skipping
        // a closed birth-dose row moves the first and must not move the second, or the register
        // would report a dose that was never given.
        int cursor = 0;
        int countedDoses = 0;
        LocalDate previousCounted = null;
        boolean estimatedInSeries = false;

        for (GivenDose dose : doses) {
            // A row whose window had already closed when this dose was given is not available to
            // it. Without this the birth-dose row would swallow the first pentavalent dose at six
            // weeks and record it as a dose given at birth -- the register would then say a child
            // had their birth dose, on a date that proves they did not.
            cursor = skipClosedRows(dateOfBirth, rows, cursor, dose.givenOn(), antigen, exemptions,
                    asAt, countedDoses, estimatedInSeries, due);
            if (cursor >= rows.size()) {
                uncounted.add(new Uncounted(dose.doseId(), antigen, dose.productCode(),
                        dose.givenOn(), rows.size(),
                        ("The %s series is complete at %d dose(s) in this schedule, so this one is "
                                + "extra. It is recorded and it does not extend the series.")
                                .formatted(antigen, rows.size())));
                continue;
            }
            ScheduleRow row = rows.get(cursor);
            LocalDate earliestByAge = dateOfBirth.plusDays(row.minAgeDays());
            if (dose.givenOn().isBefore(earliestByAge)) {
                uncounted.add(new Uncounted(dose.doseId(), antigen, dose.productCode(),
                        dose.givenOn(), row.doseNumber(),
                        ("Dose %d of %s may not be given before %s (%d days old); this was given on "
                                + "%s. It does not count and does not advance the series, so dose %d "
                                + "is still outstanding.").formatted(row.doseNumber(), antigen,
                                earliestByAge, row.minAgeDays(), dose.givenOn(), row.doseNumber())));
                continue;
            }
            if (row.minIntervalDays() != null && previousCounted != null) {
                LocalDate notBefore = previousCounted.plusDays(row.minIntervalDays());
                if (dose.givenOn().isBefore(notBefore)) {
                    uncounted.add(new Uncounted(dose.doseId(), antigen, dose.productCode(),
                            dose.givenOn(), row.doseNumber(),
                            ("Dose %d of %s must be at least %d days after the previous dose on %s, "
                                    + "so not before %s; this was given on %s. It does not count "
                                    + "and does not advance the series.").formatted(row.doseNumber(),
                                    antigen, row.minIntervalDays(), previousCounted, notBefore,
                                    dose.givenOn())));
                    continue;
                }
            }
            cursor++;
            countedDoses++;
            previousCounted = dose.givenOn();
            estimatedInSeries = estimatedInSeries || dose.dateEstimated();
        }

        // And the rows whose window has closed by the date being evaluated. The same pass as the
        // one inside the loop above, run once more against `asAt` rather than against a dose, which
        // is what turns a never-given birth dose into a stated NO_LONGER_GIVEN rather than a row
        // that reads overdue for the rest of the child's life.
        cursor = skipClosedRows(dateOfBirth, rows, cursor, asAt, antigen, exemptions, asAt,
                countedDoses, estimatedInSeries, due);

        if (cursor >= rows.size()) {
            // Complete means every dose was given, not merely that the schedule has run out of rows
            // to offer. The two came apart the first time this ran against a real cohort: a child
            // past every rotavirus window got three NO_LONGER_GIVEN rows and then a COMPLETE one
            // reading "0 of the 3 doses are recorded and counted", which is a contradiction on the
            // same screen. When the rows ran out because their windows shut, those rows have
            // already said so and there is nothing to add.
            if (countedDoses < rows.size()) {
                return;
            }
            ScheduleRow last = rows.get(rows.size() - 1);
            due.add(new Due(antigen, last.doseNumber(), last.label(), DueStatus.COMPLETE,
                    null, null, null, null, countedDoses, estimatedInSeries,
                    refusalRecorded(exemptions, antigen, asAt),
                    ("All %d dose(s) of %s in this schedule are recorded and counted; nothing "
                            + "further is due.").formatted(rows.size(), antigen)));
            return;
        }

        due.add(nextDue(dateOfBirth, rows.get(cursor), countedDoses, previousCounted,
                estimatedInSeries, antigen, exemptions, asAt));
    }

    /**
     * Advances past every row whose window had closed by {@code by}, reporting each one.
     *
     * <p>Called twice per antigen with two different dates, and the pair is the whole rule. Against
     * a <em>dose's</em> date it stops a row accepting a dose given after the window shut — without
     * it the birth-dose row swallows the first pentavalent dose at six weeks, and the register then
     * says a child had their birth dose on a date that proves they did not. Against
     * <em>{@code asAt}</em> it turns a never-given birth dose into a stated
     * {@code NO_LONGER_GIVEN} rather than a row reading overdue for the rest of the child's life.
     *
     * <p>Reported rather than dropped, and the count deliberately does not move: a dose that was
     * not given is not a dose and is not a baseline for the next one's interval.
     */
    private static int skipClosedRows(LocalDate dateOfBirth, List<ScheduleRow> rows, int cursor,
                                      LocalDate by, String antigen, List<Exemption> exemptions,
                                      LocalDate asAt, int countedDoses, boolean estimatedInSeries,
                                      List<Due> due) {
        int at = cursor;
        while (at < rows.size()) {
            ScheduleRow row = rows.get(at);
            if (row.maxAgeDays() == null) {
                break;
            }
            LocalDate closesOn = dateOfBirth.plusDays(row.maxAgeDays());
            if (!by.isAfter(closesOn)) {
                break;
            }
            due.add(new Due(antigen, row.doseNumber(), row.label(), DueStatus.NO_LONGER_GIVEN,
                    dateOfBirth.plusDays(row.minAgeDays()), dateOfBirth.plusDays(row.dueAgeDays()),
                    null, closesOn, countedDoses, estimatedInSeries,
                    refusalRecorded(exemptions, antigen, asAt),
                    ("Dose %d of %s (%s) is no longer given after %s and was not recorded before "
                            + "then. Nothing further is due for it.").formatted(row.doseNumber(),
                            antigen, row.label(), closesOn)));
            at++;
        }
        return at;
    }

    /** The one actionable row for an antigen: the next dose the schedule expects, and its dates. */
    private static Due nextDue(LocalDate dateOfBirth, ScheduleRow row, int counted,
                               LocalDate previousCounted, boolean estimatedInSeries, String antigen,
                               List<Exemption> exemptions, LocalDate asAt) {
        LocalDate earliestOn = dateOfBirth.plusDays(row.minAgeDays());
        LocalDate dueOn = dateOfBirth.plusDays(row.dueAgeDays());
        LocalDate byInterval = null;
        if (row.minIntervalDays() != null && previousCounted != null) {
            byInterval = previousCounted.plusDays(row.minIntervalDays());
            // The interval can push a dose past its scheduled age, and this is the case that
            // catches people: a child who started late is not due their second dose on the
            // schedule's date, they are due it an interval after their first.
            if (byInterval.isAfter(earliestOn)) {
                earliestOn = byInterval;
            }
            if (byInterval.isAfter(dueOn)) {
                dueOn = byInterval;
            }
        }
        LocalDate overdueFrom = dueOn.plusDays(row.graceDays() + 1L);
        LocalDate windowClosesOn = row.maxAgeDays() == null
                ? null : dateOfBirth.plusDays(row.maxAgeDays());

        boolean exempt = exemptions.stream()
                .anyMatch(e -> e.kind() == ExemptionKind.MEDICAL && e.coversOn(antigen, asAt));
        DueStatus status;
        if (exempt) {
            status = DueStatus.EXEMPT;
        } else if (asAt.isBefore(dueOn)) {
            status = DueStatus.NOT_YET_DUE;
        } else if (asAt.isBefore(overdueFrom)) {
            status = DueStatus.DUE;
        } else {
            status = DueStatus.OVERDUE;
        }

        String because = switch (status) {
            case EXEMPT -> ("Dose %d of %s (%s) would be due on %s, and a medical exemption covers "
                    + "this antigen. It is not to be given.").formatted(row.doseNumber(), antigen,
                    row.label(), dueOn);
            case NOT_YET_DUE -> dueSentence(row, antigen, dueOn, byInterval, previousCounted)
                    + " That has not arrived yet.";
            case DUE -> dueSentence(row, antigen, dueOn, byInterval, previousCounted)
                    + " It reads overdue from %s.".formatted(overdueFrom);
            default -> dueSentence(row, antigen, dueOn, byInterval, previousCounted)
                    + " It has been overdue since %s.".formatted(overdueFrom);
        };

        return new Due(antigen, row.doseNumber(), row.label(), status, earliestOn, dueOn,
                overdueFrom, windowClosesOn, counted, estimatedInSeries,
                refusalRecorded(exemptions, antigen, asAt), because);
    }

    /** Why this date and not another one — the half of the sentence a clinician checks. */
    private static String dueSentence(ScheduleRow row, String antigen, LocalDate dueOn,
                                      LocalDate byInterval, LocalDate previousCounted) {
        if (byInterval != null && byInterval.equals(dueOn)) {
            return ("Dose %d of %s (%s) is due on %s, which is %d days after the previous dose on "
                    + "%s rather than the scheduled age.").formatted(row.doseNumber(), antigen,
                    row.label(), dueOn, row.minIntervalDays(), previousCounted);
        }
        return "Dose %d of %s (%s) is due on %s, at %d days old."
                .formatted(row.doseNumber(), antigen, row.label(), dueOn, row.dueAgeDays());
    }

    /**
     * Whether a refusal has been recorded for this antigen.
     *
     * <p>Carried on the row rather than changing the status, which is the decision
     * {@code DueStatus} argues: a family who declined last year is exactly who a clinic may want to
     * speak to again, and hiding them would make one refusal permanent by accident.
     */
    private static boolean refusalRecorded(List<Exemption> exemptions, String antigen,
                                           LocalDate asAt) {
        return exemptions.stream()
                .anyMatch(e -> e.kind() == ExemptionKind.REFUSED && e.coversOn(antigen, asAt));
    }

    /**
     * The schedule, grouped by antigen and ordered by dose number within each.
     *
     * <p>Sorted here rather than trusted from the caller. The repository reads it in this order
     * already, and a calculator that assumed an order it did not impose could be handed an
     * unsorted schedule and would count dose 3 as dose 1 — silently, and in the direction that
     * says a child is protected.
     */
    private static Map<String, List<ScheduleRow>> groupRows(List<ScheduleRow> rows) {
        Map<String, List<ScheduleRow>> byAntigen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ScheduleRow row : rows) {
            byAntigen.computeIfAbsent(row.antigenCode(), key -> new ArrayList<>()).add(row);
        }
        byAntigen.values().forEach(list -> list.sort(Comparator.comparingInt(ScheduleRow::doseNumber)));
        return byAntigen;
    }

    /**
     * The register as it stood on {@code asAt}, grouped by antigen and ordered by date.
     *
     * <p><strong>Doses given after {@code asAt} are not in it.</strong> Found by asking a live
     * stack what a child was due 270 days ago and being told their next dose was measured from one
     * they had not yet received. An evaluation of a past date has to use the register as it stood on
     * that date, or "what was due on the first" is answered with the benefit of hindsight — and the
     * coverage measure that reads this at a child's second birthday would count doses given after
     * it, producing a rate that improves retroactively.
     *
     * <p>Filtered silently rather than reported: a dose in the future of the question is not an
     * error, it simply had not happened yet. Ties are broken on the dose id so the answer is stable
     * when two doses share a date.
     */
    private static Map<String, List<GivenDose>> groupGiven(List<GivenDose> given, LocalDate asAt) {
        Map<String, List<GivenDose>> byAntigen = new LinkedHashMap<>();
        if (given == null) {
            return byAntigen;
        }
        for (GivenDose dose : given) {
            if (dose.givenOn().isAfter(asAt)) {
                continue;
            }
            byAntigen.computeIfAbsent(dose.antigenCode(), key -> new ArrayList<>()).add(dose);
        }
        byAntigen.values().forEach(list -> list.sort(Comparator.comparing(GivenDose::givenOn)
                .thenComparing(dose -> String.valueOf(dose.doseId()))));
        return byAntigen;
    }
}
