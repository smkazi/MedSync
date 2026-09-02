package com.hms.pharmacy.service;

import com.hms.pharmacy.domain.InteractionPair;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import com.hms.pharmacy.domain.PharmacyEnums.InteractionSeverity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Do any two of these medicines interact?
 *
 * <p>Pure, like {@link AllergyChecker}, and for the same reason. What it adds is the three-way
 * answer the whole module is arranged around:
 *
 * <ul>
 *   <li><strong>CONTRAINDICATED refuses</strong>, and no reason unlocks it. "Never give these
 *       together" is not a warning with a checkbox.</li>
 *   <li><strong>At or above the deployment's floor, overridable</strong> — the prescriber may go
 *       ahead having written down why. Most real prescribing of interacting pairs is exactly this:
 *       a considered decision with monitoring attached.</li>
 *   <li><strong>Below the floor, reported and not blocking.</strong> They still appear on the
 *       response, because a prescriber who wants to see minor interactions should be able to, and
 *       an interruption for every one of them is how a hospital teaches its clinicians to dismiss
 *       the dialog without reading it.</li>
 * </ul>
 *
 * <p>The floor is configuration (`hms.pharmacy.interaction-floor`) because formularies and
 * tolerance for interruption differ between a district hospital and a tertiary centre, and a floor
 * nobody can move is a floor somebody works around. The <em>scale</em> is not configurable: it is
 * an ordered enum, and the ordering is what "at or above" means.
 */
public final class InteractionChecker {

    private InteractionChecker() {
    }

    /**
     * One interacting pair, resolved back to the products that caused it.
     *
     * @param management what to do instead — the field that makes the warning actionable. "These
     *                   interact" gets dismissed; "monitor INR weekly for the first month" does not.
     */
    public record Finding(String ingredientA, String ingredientB, String drugA, String drugB,
                          InteractionSeverity severity, String effect, String management,
                          boolean overridable) {
    }

    public record Result(CheckOutcome outcome, List<Finding> findings) {

        public Result {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public boolean clear() {
            return outcome == CheckOutcome.CLEAR;
        }
    }

    /**
     * @param ingredientsByDrug what each product on the order contains, keyed by drug code
     * @param pairs             every known pairing among the union of those ingredients
     * @param floor             the severity at or above which a pairing must be justified
     */
    public static Result check(Map<String, Set<String>> ingredientsByDrug,
                               List<InteractionPair> pairs, InteractionSeverity floor) {
        // Indexed by the sorted pair, which is how the rows are stored — see InteractionPair, which
        // normalises the order precisely so a lookup like this one needs only one key.
        Map<String, InteractionPair> bySortedPair = new HashMap<>();
        for (InteractionPair pair : pairs) {
            bySortedPair.put(key(pair.getIngredientA(), pair.getIngredientB()), pair);
        }

        // Walked as pairs of *products* rather than pairs of ingredients, so that a co-formulated
        // tablet cannot flag against itself. Two ingredients of one combination product being a
        // known pairing is not an interaction the prescriber introduced, and reporting it would
        // fire on every such product in the formulary.
        List<String> drugs = new ArrayList<>(ingredientsByDrug.keySet());
        java.util.Collections.sort(drugs);
        List<Finding> findings = new ArrayList<>();
        Set<String> reported = new java.util.HashSet<>();
        for (int i = 0; i < drugs.size(); i++) {
            for (int j = i + 1; j < drugs.size(); j++) {
                String drugA = drugs.get(i);
                String drugB = drugs.get(j);
                for (String left : ingredientsByDrug.getOrDefault(drugA, Set.of())) {
                    for (String right : ingredientsByDrug.getOrDefault(drugB, Set.of())) {
                        InteractionPair pair = bySortedPair.get(key(left, right));
                        // The same pairing can be reachable twice when a patient is on two products
                        // that share an ingredient; report it once.
                        if (pair == null || !reported.add(drugA + "|" + drugB + "|" + key(left, right))) {
                            continue;
                        }
                        boolean blocking = pair.getSeverity() == InteractionSeverity.CONTRAINDICATED;
                        findings.add(new Finding(pair.getIngredientA(), pair.getIngredientB(),
                                drugA, drugB, pair.getSeverity(), pair.getEffect(),
                                pair.getManagement(),
                                !blocking && pair.getSeverity().atLeast(floor)));
                    }
                }
            }
        }

        if (findings.isEmpty()) {
            return new Result(CheckOutcome.CLEAR, List.of());
        }
        if (findings.stream().anyMatch(f -> f.severity() == InteractionSeverity.CONTRAINDICATED)) {
            return new Result(CheckOutcome.REFUSED, findings);
        }
        boolean anyNeedsReason = findings.stream().anyMatch(f -> f.severity().atLeast(floor));
        return new Result(anyNeedsReason ? CheckOutcome.OVERRIDABLE : CheckOutcome.CLEAR, findings);
    }

    /** The pair as it is stored: sorted, so one key finds it whichever way round it is asked. */
    private static String key(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "\u0000" + second : second + "\u0000" + first;
    }

}
