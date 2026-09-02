package com.hms.scheduling.service;

import com.hms.scheduling.domain.VitalsRecord;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NEWS2 — the National Early Warning Score, version 2.
 *
 * <p>The platform captured vitals and scored nothing, which meant a patient deteriorating on a ward
 * was visible only to whoever happened to read the numbers and compare them to the last set. NEWS2
 * is the standard answer to that: seven parameters, each scored 0–3, summed, with an escalation
 * band. It is deterministic, it needs no model, and everything it needs is already recorded.
 *
 * <p><strong>Pure, and the cut-offs are in code.</strong> {@code docs/extensibility.md}'s rule is
 * that a vocabulary becomes configuration when adding a member needs no new behaviour — and by that
 * rule these numbers look like configuration, since they are just bands. They are not, and the
 * reason is worth stating: NEWS2 is a <em>national standard</em>. Its whole value is that a score
 * of 6 means the same thing in every hospital that uses it, and a deployment that could edit the
 * cut-offs could produce a number it calls NEWS2 which is not NEWS2 — a wrong answer that travels
 * with the authority of a standard. What a hospital genuinely does decide locally is the
 * *escalation policy*: who is called, how fast, at which score. That is configuration, and it lives
 * in {@code escalation_policies}.
 *
 * <p><strong>Advisory, always.</strong> Nothing here changes a status, moves a patient or raises an
 * order. It is a number beside the observations, and the screens say so — an early warning score
 * that acted on its own would be a clinical decision made by a table of ranges.
 *
 * <p>Scored against the Royal College of Physicians' published chart. The worked examples in
 * {@code News2CalculatorTest} come from that chart rather than from this implementation, which is
 * the only way the test means anything.
 */
public final class News2Calculator {

    private News2Calculator() {
    }

    /**
     * One parameter's contribution, and why.
     *
     * @param parameter what was measured, in the words a clinician uses
     * @param value     as recorded, formatted for display — null-safe, so an absent observation
     *                  says so rather than reading as zero
     * @param score     0 to 3
     */
    public record Component(String parameter, String value, int score) {
    }

    /**
     * The score, its parts, and what it means.
     *
     * @param total       the sum
     * @param components  every parameter that could be scored, in chart order
     * @param missing     the parameters that were not recorded — reported, because a NEWS2 of 3
     *                    from four observations is not the same fact as a NEWS2 of 3 from seven,
     *                    and a screen that hid the difference would be inviting a wrong reading
     * @param anyThree    whether a single parameter scored 3, which escalates on its own
     * @param band        the clinical risk band
     */
    public record Score(int total, List<Component> components, List<String> missing,
                        boolean anyThree, Band band) {

        public Score {
            components = components == null ? List.of() : List.copyOf(components);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /**
     * The published risk bands.
     *
     * <p>{@code LOW_MEDIUM} exists because of the single-parameter rule: a total of 3 that is all
     * from one parameter is a different clinical picture from 3 spread across three, and the chart
     * escalates the first one further. Collapsing the two would lose the distinction the score was
     * designed to make.
     */
    public enum Band {
        /** 0. Routine monitoring. */
        NONE,
        /** 1–4, nothing scoring 3. */
        LOW,
        /** 1–4 with a single parameter at 3. Urgent review by a clinician competent to act. */
        LOW_MEDIUM,
        /** 5–6. Urgent review, and a decision about higher-level care. */
        MEDIUM,
        /** 7 or more. Emergency assessment, and usually critical care involvement. */
        HIGH
    }

    /**
     * Scores one set of observations.
     *
     * <p>An absent observation contributes nothing and is named in {@link Score#missing()}. That is
     * the honest handling: NEWS2 has no rule for "assume normal", and silently treating a missing
     * respiratory rate as 12–20 would turn an incomplete set of observations into a reassuring
     * number, which is the single most dangerous thing this class could do.
     */
    public static Score of(VitalsRecord vitals) {
        List<Component> components = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        score(components, missing, "Respiration rate", vitals.getRespiratoryRate(),
                News2Calculator::respirationScore, value -> value + " /min");
        score(components, missing, "SpO2", vitals.getOxygenSaturation(),
                News2Calculator::oxygenSaturationScore, value -> value + "%");
        // Air or oxygen. Two points for any supplemental oxygen at all, which is a large share of
        // the score for a lot of ward patients - and the reason `on_supplemental_oxygen` had to be
        // recorded rather than inferred. Before it existed this score under-read by 2 for
        // everybody on oxygen, which is the direction that gets missed.
        components.add(new Component("Air or oxygen",
                vitals.isOnSupplementalOxygen() ? "supplemental oxygen" : "air",
                vitals.isOnSupplementalOxygen() ? 2 : 0));
        score(components, missing, "Systolic blood pressure", vitals.getSystolicBp(),
                News2Calculator::systolicScore, value -> value + " mmHg");
        score(components, missing, "Pulse", vitals.getHeartRate(),
                News2Calculator::pulseScore, value -> value + " bpm");
        scoreConsciousness(components, missing, vitals.getConsciousness());
        scoreTemperature(components, missing, vitals.getTemperatureC());

        int total = components.stream().mapToInt(Component::score).sum();
        boolean anyThree = components.stream().anyMatch(component -> component.score() == 3);
        return new Score(total, components, missing, anyThree, band(total, anyThree));
    }

    /** The published bands, in the order the chart applies them. */
    private static Band band(int total, boolean anyThree) {
        if (total >= 7) {
            return Band.HIGH;
        }
        if (total >= 5) {
            return Band.MEDIUM;
        }
        // Checked after 5 and 7, not before: a single 3 escalates a *low* total, it does not
        // de-escalate a high one.
        if (anyThree) {
            return Band.LOW_MEDIUM;
        }
        return total == 0 ? Band.NONE : Band.LOW;
    }

    private static void score(List<Component> components, List<String> missing, String parameter,
                              Integer value, java.util.function.IntUnaryOperator scorer,
                              java.util.function.IntFunction<String> format) {
        if (value == null) {
            missing.add(parameter);
            return;
        }
        components.add(new Component(parameter, format.apply(value), scorer.applyAsInt(value)));
    }

    private static int respirationScore(int rate) {
        if (rate <= 8) {
            return 3;
        }
        if (rate <= 11) {
            return 1;
        }
        if (rate <= 20) {
            return 0;
        }
        return rate <= 24 ? 2 : 3;
    }

    /**
     * SpO2, on Scale 1.
     *
     * <p>Scale 2 — for patients with a prescribed target range of 88–92%, typically chronic
     * hypoxaemic respiratory failure — is deliberately not implemented, because using it requires a
     * documented prescription for that target and the platform does not record one. Scoring a
     * COPD patient on Scale 1 over-reads rather than under-reads, which is the safer error of the
     * two, and the gap is named in the README rather than papered over with a guess.
     */
    private static int oxygenSaturationScore(int saturation) {
        if (saturation <= 91) {
            return 3;
        }
        if (saturation <= 93) {
            return 2;
        }
        return saturation <= 95 ? 1 : 0;
    }

    private static int systolicScore(int systolic) {
        if (systolic <= 90) {
            return 3;
        }
        if (systolic <= 100) {
            return 2;
        }
        if (systolic <= 110) {
            return 1;
        }
        // The high end scores 3, not 1: the chart is not symmetrical, and a systolic of 230 is an
        // emergency rather than a mild abnormality.
        return systolic <= 219 ? 0 : 3;
    }

    private static int pulseScore(int pulse) {
        if (pulse <= 40) {
            return 3;
        }
        if (pulse <= 50) {
            return 1;
        }
        if (pulse <= 90) {
            return 0;
        }
        if (pulse <= 110) {
            return 1;
        }
        return pulse <= 130 ? 2 : 3;
    }

    /**
     * Consciousness, as ACVPU.
     *
     * <p>Alert scores 0 and everything else scores 3 — there is no middle. "New confusion" is
     * worth as much as unresponsive on this chart, which surprises people and is the point: a
     * patient who has become confused is deteriorating whatever their numbers say.
     */
    private static void scoreConsciousness(List<Component> components, List<String> missing,
                                           String consciousness) {
        if (consciousness == null || consciousness.isBlank()) {
            missing.add("Consciousness");
            return;
        }
        String value = consciousness.trim().toUpperCase(Locale.ROOT);
        components.add(new Component("Consciousness", value.toLowerCase(Locale.ROOT),
                "ALERT".equals(value) ? 0 : 3));
    }

    private static void scoreTemperature(List<Component> components, List<String> missing,
                                         BigDecimal temperature) {
        if (temperature == null) {
            missing.add("Temperature");
            return;
        }
        // BigDecimal comparisons, not doubles: the boundaries are at one decimal place and 36.1
        // as a double is not 36.1. A patient scoring 1 instead of 0 because of binary floating
        // point would be a bug nobody could reproduce by hand.
        int score;
        if (temperature.compareTo(new BigDecimal("35.0")) <= 0) {
            score = 3;
        } else if (temperature.compareTo(new BigDecimal("36.0")) <= 0) {
            score = 1;
        } else if (temperature.compareTo(new BigDecimal("38.0")) <= 0) {
            score = 0;
        } else if (temperature.compareTo(new BigDecimal("39.0")) <= 0) {
            score = 1;
        } else {
            score = 2;
        }
        components.add(new Component("Temperature",
                temperature.stripTrailingZeros().toPlainString() + " °C", score));
    }
}
