package com.hms.pharmacy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.pharmacy.client.AllergyClient;
import com.hms.pharmacy.domain.InteractionPair;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import com.hms.pharmacy.domain.PharmacyEnums.InteractionSeverity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two safety rules, tested without a database or a network.
 *
 * <p>They are pure functions precisely so that this file can exist: the rules that refuse a
 * prescription should be readable and checkable on their own, rather than only through a stack of
 * HTTP, JPA and a stubbed client. Same reasoning as {@code News2CalculatorTest}.
 */
@DisplayName("the medication safety checks")
class SafetyCheckerTest {

    private static AllergyClient.Allergy allergy(String substance, String severity) {
        return new AllergyClient.Allergy(substance, "rash", severity,
                "LIFE_THREATENING".equals(severity));
    }

    @Nested
    @DisplayName("drug-allergy")
    class Allergies {

        @Test
        @DisplayName("a penicillin allergy blocks amoxicillin, which is the whole point of ingredients")
        void classAllergyBlocksMember() {
            // Nobody records an allergy as "amoxicillin, benzylpenicillin, amoxicillin-clavulanate".
            // They record "penicillin", and the class marker on the product is what connects the
            // two. A checker that compared brand names would hand over the amoxicillin.
            var result = AllergyChecker.check("AMOX500", "Amoxicillin",
                    Set.of("AMOXICILLIN", "PENICILLIN"), List.of(allergy("Penicillin", "SEVERE")));

            assertThat(result.outcome()).isEqualTo(CheckOutcome.REFUSED);
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.matchedOn()).isEqualTo("PENICILLIN");
                assertThat(finding.overridable()).isFalse();
            });
        }

        @Test
        @DisplayName("a severe allergy cannot be overridden and a mild one can")
        void severityDecidesWhetherItIsOverridable() {
            assertThat(AllergyChecker.check("IBU400", "Ibuprofen", Set.of("IBUPROFEN", "NSAID"),
                    List.of(allergy("Ibuprofen", "MILD"))).outcome())
                    .isEqualTo(CheckOutcome.OVERRIDABLE);
            assertThat(AllergyChecker.check("IBU400", "Ibuprofen", Set.of("IBUPROFEN", "NSAID"),
                    List.of(allergy("Ibuprofen", "LIFE_THREATENING"))).outcome())
                    .isEqualTo(CheckOutcome.REFUSED);
        }

        @Test
        @DisplayName("a substring is not a match: 'ace' does not block paracetamol")
        void matchesWholeWordsOnly() {
            // The reason the matcher uses word boundaries. Plain containment makes an ACE-inhibitor
            // allergy block paracetamol, and a checker that cries wolf is one people click through
            // — which costs the cases it exists to catch.
            var result = AllergyChecker.check("PARA500", "Paracetamol", Set.of("PARACETAMOL"),
                    List.of(allergy("ACE", "SEVERE")));

            assertThat(result.outcome()).isEqualTo(CheckOutcome.CLEAR);
            assertThat(result.findings()).isEmpty();
        }

        @Test
        @DisplayName("an underscore in an ingredient code does not hide the word inside it")
        void separatorsDoNotDefeatTheMatch() {
            var result = AllergyChecker.check("BENPEN1M", "Benzylpenicillin",
                    Set.of("BENZYL_PENICILLIN", "PENICILLIN"),
                    List.of(allergy("penicillin", "SEVERE")));

            assertThat(result.findings()).isNotEmpty();
        }

        @Test
        @DisplayName("an allergy recorded against the brand still matches")
        void matchesTheProductNameToo() {
            // Patients are told brand names, so that is what gets typed into the chart.
            var result = AllergyChecker.check("METFORMIN500", "Metformin", Set.of("METFORMIN"),
                    List.of(allergy("Metformin", "MODERATE")));

            assertThat(result.outcome()).isEqualTo(CheckOutcome.OVERRIDABLE);
        }

        @Test
        @DisplayName("nothing recorded is clear, and a blank substance is ignored rather than matching everything")
        void emptyInputIsSafe() {
            assertThat(AllergyChecker.check("PARA500", "Paracetamol", Set.of("PARACETAMOL"),
                    List.of()).clear()).isTrue();
            assertThat(AllergyChecker.check("PARA500", "Paracetamol", Set.of("PARACETAMOL"),
                    List.of(allergy("   ", "SEVERE"))).clear()).isTrue();
        }
    }

    @Nested
    @DisplayName("drug-drug")
    class Interactions {

        private static InteractionPair pair(String a, String b, InteractionSeverity severity) {
            return new InteractionPair(a, b, severity, "effect", "management", "test");
        }

        @Test
        @DisplayName("a contraindicated pairing refuses, and no reason unlocks it")
        void contraindicatedRefuses() {
            var result = InteractionChecker.check(
                    Map.of("CLARITH500", Set.of("CLARITHROMYCIN", "MACROLIDE"),
                            "SIMVA20", Set.of("SIMVASTATIN", "STATIN")),
                    List.of(pair("CLARITHROMYCIN", "SIMVASTATIN", InteractionSeverity.CONTRAINDICATED)),
                    InteractionSeverity.MAJOR);

            assertThat(result.outcome()).isEqualTo(CheckOutcome.REFUSED);
            assertThat(result.findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.overridable()).isFalse());
        }

        @Test
        @DisplayName("a class pairing fires for a member: warfarin against NSAID catches ibuprofen")
        void classPairingCatchesMembers() {
            var result = InteractionChecker.check(
                    Map.of("WARF5", Set.of("WARFARIN"), "IBU400", Set.of("IBUPROFEN", "NSAID")),
                    List.of(pair("NSAID", "WARFARIN", InteractionSeverity.MAJOR)),
                    InteractionSeverity.MAJOR);

            assertThat(result.outcome()).isEqualTo(CheckOutcome.OVERRIDABLE);
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.drugA()).isEqualTo("IBU400");
                assertThat(finding.drugB()).isEqualTo("WARF5");
                assertThat(finding.overridable()).isTrue();
            });
        }

        @Test
        @DisplayName("the pair is unordered: it fires whichever way round the ingredients arrive")
        void theOrderOfTheIngredientsDoesNotMatter() {
            // The reason the table holds one sorted row per pair rather than two. Two rows is a
            // deployment where the same pairing has two severities and which one fires depends on
            // the order the caller happened to pass its ingredients in.
            var pairs = List.of(pair("WARFARIN", "NSAID", InteractionSeverity.MAJOR));
            assertThat(pairs.getFirst().getIngredientA()).isEqualTo("NSAID");

            var oneWay = InteractionChecker.check(
                    Map.of("WARF5", Set.of("WARFARIN"), "IBU400", Set.of("NSAID")),
                    pairs, InteractionSeverity.MAJOR);
            var otherWay = InteractionChecker.check(
                    Map.of("IBU400", Set.of("NSAID"), "WARF5", Set.of("WARFARIN")),
                    pairs, InteractionSeverity.MAJOR);

            assertThat(oneWay.outcome()).isEqualTo(otherWay.outcome())
                    .isEqualTo(CheckOutcome.OVERRIDABLE);
        }

        @Test
        @DisplayName("a combination product does not interact with itself")
        void aCoFormulatedProductDoesNotSelfFlag() {
            // Both ingredients of one tablet being a known pairing is not an interaction anybody
            // introduced. Reporting it would fire on every co-formulated product in the formulary,
            // every time, which is how a warning becomes background noise.
            var result = InteractionChecker.check(
                    Map.of("COMBO", Set.of("NSAID", "WARFARIN")),
                    List.of(pair("NSAID", "WARFARIN", InteractionSeverity.CONTRAINDICATED)),
                    InteractionSeverity.MAJOR);

            assertThat(result.outcome()).isEqualTo(CheckOutcome.CLEAR);
            assertThat(result.findings()).isEmpty();
        }

        @Test
        @DisplayName("below the floor it is reported and does not block")
        void belowTheFloorIsInformational() {
            var result = InteractionChecker.check(
                    Map.of("ASPIRIN75", Set.of("ASPIRIN"), "IBU400", Set.of("IBUPROFEN")),
                    List.of(pair("ASPIRIN", "IBUPROFEN", InteractionSeverity.MODERATE)),
                    InteractionSeverity.MAJOR);

            // Clear, and still reported: a prescriber who wants to see minor interactions should be
            // able to, and an interruption for every one of them teaches people to dismiss the
            // dialog without reading it.
            assertThat(result.outcome()).isEqualTo(CheckOutcome.CLEAR);
            assertThat(result.findings()).hasSize(1);
        }

        @Test
        @DisplayName("lowering the floor makes the same pairing blocking")
        void theFloorIsWhatDecides() {
            var pairs = List.of(pair("ASPIRIN", "IBUPROFEN", InteractionSeverity.MODERATE));
            var drugs = Map.of("ASPIRIN75", Set.of("ASPIRIN"), "IBU400", Set.of("IBUPROFEN"));

            assertThat(InteractionChecker.check(drugs, pairs, InteractionSeverity.MODERATE).outcome())
                    .isEqualTo(CheckOutcome.OVERRIDABLE);
            assertThat(InteractionChecker.check(drugs, pairs, InteractionSeverity.MINOR).outcome())
                    .isEqualTo(CheckOutcome.OVERRIDABLE);
            assertThat(InteractionChecker.check(drugs, pairs, InteractionSeverity.CONTRAINDICATED)
                    .outcome()).isEqualTo(CheckOutcome.CLEAR);
        }

        @Test
        @DisplayName("one prescription of three medicines reports each pairing once")
        void reportsEachPairingOnce() {
            var result = InteractionChecker.check(
                    Map.of("WARF5", Set.of("WARFARIN"),
                            "IBU400", Set.of("IBUPROFEN", "NSAID"),
                            "ASPIRIN75", Set.of("ASPIRIN", "NSAID")),
                    List.of(pair("NSAID", "WARFARIN", InteractionSeverity.MAJOR),
                            pair("ASPIRIN", "IBUPROFEN", InteractionSeverity.MODERATE)),
                    InteractionSeverity.MAJOR);

            // Warfarin against each of the two NSAIDs, plus the aspirin-ibuprofen pairing: three
            // findings, not five. This is the assertion that catches a matcher counting the same
            // pairing twice because two products share a class marker.
            assertThat(result.findings()).hasSize(3);
            assertThat(result.outcome()).isEqualTo(CheckOutcome.OVERRIDABLE);
        }
    }
}
