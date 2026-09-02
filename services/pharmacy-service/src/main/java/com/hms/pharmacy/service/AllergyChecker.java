package com.hms.pharmacy.service;

import com.hms.pharmacy.client.AllergyClient;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Does this patient react to anything in this medicine?
 *
 * <p>A pure function over two lists, deliberately: no repository, no HTTP, no clock. The rule it
 * encodes is the one that refuses a dispense, so it has to be readable and testable on its own,
 * the same reasoning that put {@code News2Calculator} beside the vitals service rather than inside
 * it.
 *
 * <p><strong>Matching is on ingredients, not on brand names</strong>, because a patient allergic to
 * penicillin is allergic to it under every trade name it has been sold under, and a check that
 * compared the words somebody typed against the words on the box would pass the second brand.
 * Drug <em>classes</em> are carried in the same table as a class marker per product — see the
 * migration — so an allergy recorded as "penicillin" blocks every beta-lactam that names it,
 * without this class needing a second matching path.
 *
 * <p><strong>When in doubt it matches.</strong> Whole-word containment rather than equality, so
 * "penicillin" finds "penicillin v" and "benzyl penicillin". The cost of a false match is a
 * prescriber reading a warning about a medicine that was in fact safe; the cost of a miss is
 * anaphylaxis. Substring matching without the word boundary was tried and rejected: it makes "ace"
 * match paracetamol, and a checker that cries wolf is a checker people learn to click through,
 * which costs the cases it exists to catch.
 */
public final class AllergyChecker {

    /**
     * Severities that cannot be overridden.
     *
     * <p>A prescriber may go ahead against a recorded mild rash, with a reason; nobody goes ahead
     * against a recorded anaphylaxis through this platform. That is a deliberate limit on what the
     * software will do, not a claim that no clinician ever should — desensitisation happens, under
     * supervision, and it is not something a web form should be able to authorise.
     */
    private static final Set<String> NEVER_OVERRIDABLE = Set.of("SEVERE", "LIFE_THREATENING");

    private AllergyChecker() {
    }

    /**
     * One matched allergy, and what it was matched against.
     *
     * @param matchedOn the ingredient or product name that triggered it, so the prescriber can see
     *                  *why* — "matched on AMOXICILLIN" is checkable and "allergy detected" is not
     */
    public record Finding(String substance, String reaction, String severity, String drugCode,
                          String matchedOn, boolean overridable) {
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
     * @param drugCode    the product being checked, echoed onto any finding
     * @param drugName    its name, matched as well as its ingredients — a patient may have an
     *                    allergy recorded against a brand, because that is what they were told
     * @param ingredients its ingredient codes, including any class markers
     */
    public static Result check(String drugCode, String drugName, Set<String> ingredients,
                               List<AllergyClient.Allergy> allergies) {
        List<Finding> findings = new ArrayList<>();
        for (AllergyClient.Allergy allergy : allergies) {
            String substance = normalise(allergy.substance());
            if (substance.isEmpty()) {
                continue;
            }
            String matched = firstMatch(substance, drugName, ingredients);
            if (matched != null) {
                boolean overridable = !NEVER_OVERRIDABLE.contains(
                        allergy.severity() == null ? "" : allergy.severity());
                findings.add(new Finding(allergy.substance(), allergy.reaction(),
                        allergy.severity(), drugCode, matched, overridable));
            }
        }
        if (findings.isEmpty()) {
            return new Result(CheckOutcome.CLEAR, List.of());
        }
        boolean anyBlocking = findings.stream().anyMatch(finding -> !finding.overridable());
        return new Result(anyBlocking ? CheckOutcome.REFUSED : CheckOutcome.OVERRIDABLE, findings);
    }

    private static String firstMatch(String substance, String drugName, Set<String> ingredients) {
        for (String ingredient : ingredients) {
            if (containsWord(normalise(ingredient), substance)) {
                return ingredient;
            }
        }
        return containsWord(normalise(drugName), substance) ? drugName : null;
    }

    /**
     * Whole-word containment, with underscores read as spaces.
     *
     * <p>Ingredient codes are written {@code BENZYL_PENICILLIN} and allergies are typed "penicillin",
     * so the separator has to stop mattering before the comparison rather than after it.
     */
    private static boolean containsWord(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(needle) + "\\b").matcher(haystack).find();
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
