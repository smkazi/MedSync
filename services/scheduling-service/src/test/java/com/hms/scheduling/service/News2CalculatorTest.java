package com.hms.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.scheduling.domain.VitalsRecord;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * NEWS2, against the Royal College of Physicians' published chart.
 *
 * <p>Every expected number here comes from that chart, not from running this implementation. That
 * distinction is the whole value of the file: a test written by reading the code back to itself
 * would agree with a transcription error in the cut-offs, and a transcription error in an early
 * warning score is a patient who scores 4 when they should score 6.
 *
 * <p>Pure, so no Spring and no database — the arithmetic is where boundary bugs live and it is
 * worth being able to check it in a second.
 */
class News2CalculatorTest {

    /** A patient with nothing measured, so each test can set only what it is about. */
    private static VitalsRecord vitals() {
        return new VitalsRecord(null, "test");
    }

    private static VitalsRecord with(Integer respirations, Integer spo2, boolean oxygen,
                                     Integer systolic, Integer pulse, String consciousness,
                                     String temperature) {
        VitalsRecord record = vitals();
        record.record(pulse, systolic, null, respirations,
                temperature == null ? null : new BigDecimal(temperature), spo2,
                null, null, null, consciousness, oxygen);
        return record;
    }

    @Nested
    @DisplayName("each parameter's bands, at the boundaries")
    class Boundaries {

        @ParameterizedTest(name = "respiration rate {0} scores {1}")
        @CsvSource({"8,3", "9,1", "11,1", "12,0", "20,0", "21,2", "24,2", "25,3", "40,3"})
        void respirationRate(int rate, int expected) {
            assertThat(scoreOf(with(rate, null, false, null, null, null, null),
                    "Respiration rate")).isEqualTo(expected);
        }

        @ParameterizedTest(name = "SpO2 {0}% scores {1}")
        @CsvSource({"88,3", "91,3", "92,2", "93,2", "94,1", "95,1", "96,0", "100,0"})
        void oxygenSaturation(int saturation, int expected) {
            assertThat(scoreOf(with(null, saturation, false, null, null, null, null), "SpO2"))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "systolic {0} mmHg scores {1}")
        @CsvSource({"80,3", "90,3", "91,2", "100,2", "101,1", "110,1", "111,0", "219,0", "220,3"})
        void systolic(int systolic, int expected) {
            // Asymmetrical, and deliberately so: the chart scores a systolic of 230 as 3, not as
            // the 1 that symmetry would suggest. A hypertensive emergency is not a mild
            // abnormality.
            assertThat(scoreOf(with(null, null, false, systolic, null, null, null),
                    "Systolic blood pressure")).isEqualTo(expected);
        }

        @ParameterizedTest(name = "pulse {0} bpm scores {1}")
        @CsvSource({"35,3", "40,3", "41,1", "50,1", "51,0", "90,0", "91,1", "110,1",
                    "111,2", "130,2", "131,3", "180,3"})
        void pulse(int pulse, int expected) {
            assertThat(scoreOf(with(null, null, false, null, pulse, null, null), "Pulse"))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "temperature {0} °C scores {1}")
        @CsvSource({"34.9,3", "35.0,3", "35.1,1", "36.0,1", "36.1,0", "38.0,0",
                    "38.1,1", "39.0,1", "39.1,2", "40.5,2"})
        void temperature(String temperature, int expected) {
            // The boundaries sit at one decimal place, which is why the calculator compares
            // BigDecimals: 36.1 as a double is not 36.1, and a patient scoring 1 instead of 0
            // because of binary floating point would be a bug nobody could reproduce by hand.
            assertThat(scoreOf(with(null, null, false, null, null, null, temperature),
                    "Temperature")).isEqualTo(expected);
        }

        @ParameterizedTest(name = "consciousness {0} scores {1}")
        @CsvSource({"ALERT,0", "VOICE,3", "PAIN,3", "UNRESPONSIVE,3", "CONFUSION,3"})
        void consciousness(String state, int expected) {
            // No middle ground: new confusion is worth as much as unresponsive on this chart,
            // which surprises people and is the point — a patient who has become confused is
            // deteriorating whatever their numbers say.
            assertThat(scoreOf(with(null, null, false, null, null, state, null), "Consciousness"))
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("supplemental oxygen scores 2 whatever the saturation is")
    void supplementalOxygenScoresTwo() {
        VitalsRecord onAir = with(16, 98, false, 120, 70, "ALERT", "36.5");
        VitalsRecord onOxygen = with(16, 98, true, 120, 70, "ALERT", "36.5");

        assertThat(News2Calculator.of(onAir).total()).isZero();
        // The same numbers, two points apart. 98% on oxygen is a different patient from 98% on
        // air, and this is the whole reason the flag had to be recorded rather than inferred.
        assertThat(News2Calculator.of(onOxygen).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("a healthy set of observations scores zero")
    void aWellPatientScoresZero() {
        News2Calculator.Score score = News2Calculator.of(
                with(16, 98, false, 120, 70, "ALERT", "36.8"));

        assertThat(score.total()).isZero();
        assertThat(score.band()).isEqualTo(News2Calculator.Band.NONE);
        assertThat(score.missing()).isEmpty();
        assertThat(score.anyThree()).isFalse();
    }

    @Test
    @DisplayName("a worked example: the deteriorating patient scores 7 and bands HIGH")
    void aDeterioratingPatientScoresSeven() {
        // Respirations 22 (2), SpO2 93 (2), air (0), systolic 105 (1), pulse 115 (2),
        // alert (0), 37.2 (0) = 7.
        News2Calculator.Score score = News2Calculator.of(
                with(22, 93, false, 105, 115, "ALERT", "37.2"));

        assertThat(score.total()).isEqualTo(7);
        assertThat(score.band()).isEqualTo(News2Calculator.Band.HIGH);
    }

    @Test
    @DisplayName("a worked example: septic shock scores 15")
    void septicShockScoresFifteen() {
        // Respirations 30 (3), SpO2 88 on oxygen (3 + 2), systolic 85 (3), pulse 135 (3),
        // voice (3)... which is 17 with a temperature of 39.5 (2) = 19. Counted explicitly below
        // so the arithmetic is visible rather than trusted.
        News2Calculator.Score score = News2Calculator.of(
                with(30, 88, true, 85, 135, "VOICE", "39.5"));

        assertThat(score.components()).extracting(News2Calculator.Component::score)
                .containsExactly(3, 3, 2, 3, 3, 3, 2);
        assertThat(score.total()).isEqualTo(19);
        assertThat(score.band()).isEqualTo(News2Calculator.Band.HIGH);
    }

    @Nested
    @DisplayName("the bands, including the single-parameter rule")
    class Bands {

        @Test
        @DisplayName("a total of 3 spread across parameters is LOW")
        void spreadThreeIsLow() {
            // Respirations 10 (1), SpO2 94 (1), systolic 105 (1) = 3, nothing scoring 3.
            News2Calculator.Score score = News2Calculator.of(
                    with(10, 94, false, 105, 70, "ALERT", "36.8"));

            assertThat(score.total()).isEqualTo(3);
            assertThat(score.anyThree()).isFalse();
            assertThat(score.band()).isEqualTo(News2Calculator.Band.LOW);
        }

        @Test
        @DisplayName("a total of 3 from one parameter is LOW_MEDIUM, and that is the point")
        void aSingleThreeEscalatesOnItsOwn() {
            // Respirations 26 (3) and everything else normal. Same total as above, different
            // clinical picture, and the chart escalates this one further. Collapsing the two
            // bands would lose the distinction the score was designed to make.
            News2Calculator.Score score = News2Calculator.of(
                    with(26, 98, false, 120, 70, "ALERT", "36.8"));

            assertThat(score.total()).isEqualTo(3);
            assertThat(score.anyThree()).isTrue();
            assertThat(score.band()).isEqualTo(News2Calculator.Band.LOW_MEDIUM);
        }

        @Test
        @DisplayName("a single 3 does not de-escalate a high total")
        void aSingleThreeDoesNotLowerAHighTotal() {
            // Checked because the ordering in `band` is load-bearing: the single-parameter rule
            // raises a low total, it must not lower a high one.
            News2Calculator.Score score = News2Calculator.of(
                    with(30, 90, true, 85, 135, "VOICE", "34.5"));

            assertThat(score.anyThree()).isTrue();
            assertThat(score.band()).isEqualTo(News2Calculator.Band.HIGH);
        }

        @ParameterizedTest(name = "a spread total of {0} bands {1}")
        @CsvSource({"5,MEDIUM", "6,MEDIUM", "7,HIGH"})
        void mediumAndHigh(int expectedTotal, String band) {
            // Built from 1s and 2s so no single parameter reaches 3 and the band is decided by
            // the total alone.
            VitalsRecord record = switch (expectedTotal) {
                // 10 (1) + 94 (1) + 105 (1) + 115 (2) = 5
                case 5 -> with(10, 94, false, 105, 115, "ALERT", "36.8");
                // 22 (2) + 92 (2) + 105 (1) + 95 (1) = 6
                case 6 -> with(22, 92, false, 105, 95, "ALERT", "36.8");
                // 22 (2) + 92 (2) + oxygen (2) + 105 (1) + pulse 70 (0) = 7.
                //
                // Pulse 70 rather than 95, and the first version of this line had 95 and expected
                // 7 — which is 8, because 95 bpm scores 1. The test caught it, which is the
                // argument for spelling the arithmetic out in the comment and taking the
                // expectations from the chart rather than from a run of the code.
                default -> with(22, 92, true, 105, 70, "ALERT", "36.8");
            };
            News2Calculator.Score score = News2Calculator.of(record);

            assertThat(score.anyThree()).as("no single parameter reaches 3").isFalse();
            assertThat(score.total()).isEqualTo(expectedTotal);
            assertThat(score.band().name()).isEqualTo(band);
        }
    }

    @Test
    @DisplayName("an unmeasured parameter contributes nothing and is named")
    void missingObservationsAreReportedRatherThanAssumedNormal() {
        // The single most dangerous thing this class could do is treat an absent respiratory rate
        // as 12-20, because that turns an incomplete set of observations into a reassuring number.
        // NEWS2 has no rule for "assume normal", so neither does this.
        News2Calculator.Score score = News2Calculator.of(
                with(null, null, false, 120, 70, "ALERT", "36.8"));

        assertThat(score.total()).isZero();
        assertThat(score.missing()).containsExactlyInAnyOrder("Respiration rate", "SpO2");
        // Air or oxygen is never missing: not being on oxygen is a fact, not an absence.
        assertThat(score.components()).extracting(News2Calculator.Component::parameter)
                .contains("Air or oxygen");
    }

    @Test
    @DisplayName("no observations at all scores zero and says every parameter is missing")
    void nothingRecordedIsHonestAboutIt() {
        News2Calculator.Score score = News2Calculator.of(vitals());

        assertThat(score.total()).isZero();
        assertThat(score.missing()).hasSize(6);
        assertThat(score.band()).isEqualTo(News2Calculator.Band.NONE);
    }

    private static int scoreOf(VitalsRecord record, String parameter) {
        return News2Calculator.of(record).components().stream()
                .filter(component -> component.parameter().equals(parameter))
                .mapToInt(News2Calculator.Component::score)
                .findFirst()
                .orElseThrow(() -> new AssertionError(parameter + " was not scored"));
    }
}
