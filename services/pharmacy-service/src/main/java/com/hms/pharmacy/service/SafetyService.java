package com.hms.pharmacy.service;

import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.pharmacy.client.AllergyClient;
import com.hms.pharmacy.domain.Formulary;
import com.hms.pharmacy.domain.FormularyIngredient;
import com.hms.pharmacy.domain.PharmacyEnums.CheckOutcome;
import com.hms.pharmacy.domain.PharmacyEnums.InteractionSeverity;
import com.hms.pharmacy.repo.FormularyIngredientRepository;
import com.hms.pharmacy.repo.FormularyRepository;
import com.hms.pharmacy.repo.InteractionPairRepository;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two checks, run together, over a whole order.
 *
 * <p>Together and over the whole order, both deliberately. An interaction is a property of a set of
 * medicines, so checking one item at a time checks it against nothing; and a prescriber needs one
 * answer rather than two dialogs, because the second one gets dismissed.
 *
 * <p>This class is where the two pure checkers meet the world: it resolves drug codes to
 * ingredients, fetches the allergy list (failing closed), and combines the two outcomes. The rules
 * themselves live in {@link AllergyChecker} and {@link InteractionChecker} where they can be read
 * and tested without a database.
 */
@Service
public class SafetyService {

    private final FormularyRepository formulary;
    private final FormularyIngredientRepository ingredients;
    private final InteractionPairRepository interactions;
    private final AllergyClient allergies;
    private final InteractionSeverity floor;

    /**
     * @param floor the severity at or above which an interaction must be justified in writing.
     *              Bound as the enum rather than as a string on purpose: Spring converts it, so an
     *              unreadable value fails the context at startup with the property named, instead
     *              of falling back to a default. A deployment that meant to refuse everything from
     *              MODERATE up and mistyped it would otherwise quietly refuse less, which is the
     *              kind of misconfiguration nobody notices until it matters.
     */
    public SafetyService(FormularyRepository formulary, FormularyIngredientRepository ingredients,
                         InteractionPairRepository interactions, AllergyClient allergies,
                         @Value("${hms.pharmacy.interaction-floor:MAJOR}") InteractionSeverity floor) {
        this.formulary = formulary;
        this.ingredients = ingredients;
        this.interactions = interactions;
        this.allergies = allergies;
        this.floor = floor;
    }

    public InteractionSeverity interactionFloor() {
        return floor;
    }

    /** What each of these products contains, keyed by code, in the order asked for. */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> ingredientsOf(List<String> drugCodes) {
        Map<String, Set<String>> byDrug = new LinkedHashMap<>();
        for (String code : drugCodes) {
            byDrug.put(code, new LinkedHashSet<>());
        }
        for (FormularyIngredient row : ingredients.findByDrugCodes(drugCodes)) {
            byDrug.computeIfAbsent(row.getDrugCode(), key -> new LinkedHashSet<>())
                    .add(row.getIngredientCode());
        }
        return byDrug;
    }

    /**
     * Resolves the codes, refusing anything unknown or retired.
     *
     * <p>Two different refusals with two different messages, because they mean different things to
     * the prescriber: "there is no such medicine" is a typo, and "this one is no longer stocked" is
     * a decision somebody made, which needs a substitute rather than a correction.
     */
    @Transactional(readOnly = true)
    public List<Formulary> requireOrderable(List<String> drugCodes) {
        Map<String, Formulary> found = formulary.findByCodeIn(drugCodes).stream()
                .collect(Collectors.toMap(Formulary::getCode, entry -> entry));
        List<Formulary> resolved = new ArrayList<>();
        for (String code : drugCodes) {
            Formulary entry = found.get(code);
            if (entry == null) {
                throw new NotFoundException("Unknown drug code '" + code + "'");
            }
            if (!entry.isActive()) {
                throw new BadRequestException(
                        "'%s' (%s) is no longer stocked and cannot be prescribed. Choose a substitute."
                                .formatted(entry.getName(), code));
            }
            resolved.add(entry);
        }
        return resolved;
    }

    /**
     * Runs both checks.
     *
     * @param bearerToken the caller's own token, forwarded to patient-service for the allergy list
     */
    @Transactional(readOnly = true)
    public PharmacyDtos.SafetyCheckResponse check(UUID patientId, List<String> drugCodes,
                                                  String bearerToken) {
        List<Formulary> products = requireOrderable(drugCodes);
        Map<String, Set<String>> byDrug = ingredientsOf(drugCodes);

        List<AllergyClient.Allergy> recorded = allergies.forPatient(patientId, bearerToken);
        List<AllergyChecker.Finding> allergyFindings = new ArrayList<>();
        CheckOutcome allergyOutcome = CheckOutcome.CLEAR;
        for (Formulary product : products) {
            AllergyChecker.Result result = AllergyChecker.check(product.getCode(),
                    product.getName(), byDrug.getOrDefault(product.getCode(), Set.of()), recorded);
            allergyFindings.addAll(result.findings());
            allergyOutcome = worse(allergyOutcome, result.outcome());
        }

        Set<String> everyIngredient = byDrug.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        InteractionChecker.Result interaction = everyIngredient.isEmpty()
                ? new InteractionChecker.Result(CheckOutcome.CLEAR, List.of())
                : InteractionChecker.check(byDrug, interactions.findAmong(everyIngredient), floor);

        CheckOutcome outcome = worse(allergyOutcome, interaction.outcome());
        return new PharmacyDtos.SafetyCheckResponse(outcome, allergyFindings,
                interaction.findings(), describe(outcome, allergyFindings, interaction.findings()));
    }

    /** The worse of two outcomes. REFUSED beats OVERRIDABLE beats CLEAR. */
    private static CheckOutcome worse(CheckOutcome left, CheckOutcome right) {
        return left.compareTo(right) >= 0 ? left : right;
    }

    /**
     * One sentence a prescriber can act on.
     *
     * <p>Built here rather than in the web layer because it is also the text of the refusal, and
     * the two must not be able to drift apart: a screen that says "check the interactions" while
     * the service refuses for an allergy is a screen that sends somebody looking in the wrong place.
     */
    private static String describe(CheckOutcome outcome, List<AllergyChecker.Finding> allergies,
                                   List<InteractionChecker.Finding> interactions) {
        return switch (outcome) {
            case CLEAR -> interactions.isEmpty()
                    ? "No allergy or interaction found."
                    : "No blocking finding. %d interaction(s) below the refusal threshold, listed for information."
                            .formatted(interactions.size());
            case OVERRIDABLE -> "This order can be written, but somebody has to say why: "
                    + summarise(allergies, interactions);
            case REFUSED -> "This order cannot be written: " + summarise(allergies, interactions);
        };
    }

    private static String summarise(List<AllergyChecker.Finding> allergies,
                                    List<InteractionChecker.Finding> interactions) {
        List<String> parts = new ArrayList<>();
        for (AllergyChecker.Finding finding : allergies) {
            parts.add("recorded %s allergy to %s (matched on %s)"
                    .formatted(String.valueOf(finding.severity()).toLowerCase(java.util.Locale.ROOT)
                            .replace('_', ' '), finding.substance(), finding.matchedOn()));
        }
        for (InteractionChecker.Finding finding : interactions) {
            parts.add("%s interaction between %s and %s — %s; %s"
                    .formatted(finding.severity().name().toLowerCase(java.util.Locale.ROOT),
                            finding.drugA(), finding.drugB(), finding.effect(),
                            finding.management()));
        }
        return String.join("; ", parts) + ".";
    }
}
