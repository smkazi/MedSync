package com.hms.pharmacy.service;

import com.hms.common.audit.AuditService;
import com.hms.common.data.QueryPatterns;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.pharmacy.domain.Formulary;
import com.hms.pharmacy.domain.FormularyIngredient;
import com.hms.pharmacy.domain.InteractionPair;
import com.hms.pharmacy.repo.FormularyIngredientRepository;
import com.hms.pharmacy.repo.FormularyRepository;
import com.hms.pharmacy.repo.InteractionPairRepository;
import com.hms.pharmacy.repo.StockBatchRepository;
import com.hms.pharmacy.web.dto.PharmacyDtos;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The formulary and the interaction table: the pharmacy's own configuration.
 *
 * <p>Both are rows rather than code, because they are exactly the kind of vocabulary
 * {@code docs/extensibility.md} says should be: adding a medicine or a pairing needs no new
 * behaviour. The <em>severity scale</em> those pairings are graded on is code, because its ordering
 * is what a refusal threshold is compared against.
 */
@Service
public class FormularyService {

    private final FormularyRepository formulary;
    private final FormularyIngredientRepository ingredients;
    private final InteractionPairRepository interactions;
    private final StockBatchRepository batches;
    private final AuditService audit;

    public FormularyService(FormularyRepository formulary, FormularyIngredientRepository ingredients,
                            InteractionPairRepository interactions, StockBatchRepository batches,
                            AuditService audit) {
        this.formulary = formulary;
        this.ingredients = ingredients;
        this.interactions = interactions;
        this.batches = batches;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PharmacyDtos.FormularyResponse> catalogue(String query, boolean includeInactive) {
        List<Formulary> rows = formulary.search(QueryPatterns.contains(query), includeInactive);
        List<String> codes = rows.stream().map(Formulary::getCode).toList();
        Map<String, List<String>> byDrug = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            for (FormularyIngredient row : ingredients.findByDrugCodes(codes)) {
                byDrug.computeIfAbsent(row.getDrugCode(), key -> new ArrayList<>())
                        .add(row.getIngredientCode());
            }
        }

        // Stock is summarised onto the catalogue row rather than left to a second call: "what do we
        // have and when does the first of it expire" is one question, and a screen that has to ask
        // twice is a screen that shows a medicine as available while the shelf is empty.
        LocalDate today = LocalDate.now();
        Map<String, Integer> unitsByDrug = new LinkedHashMap<>();
        Map<String, LocalDate> earliestByDrug = new LinkedHashMap<>();
        batches.onHand().forEach(batch -> {
            if (batch.expiredOn(today)) {
                return;
            }
            unitsByDrug.merge(batch.getDrugCode(), batch.getQuantityOnHand(), Integer::sum);
            earliestByDrug.merge(batch.getDrugCode(), batch.getExpiresOn(),
                    (left, right) -> left.isBefore(right) ? left : right);
        });

        return rows.stream()
                .map(entry -> new PharmacyDtos.FormularyResponse(entry.getId(), entry.getCode(),
                        entry.getName(), entry.getForm(), entry.getStrength(), entry.getUnit(),
                        entry.label(), entry.isControlled(), entry.isActive(),
                        byDrug.getOrDefault(entry.getCode(), List.of()),
                        unitsByDrug.getOrDefault(entry.getCode(), 0),
                        earliestByDrug.get(entry.getCode())))
                .toList();
    }

    @Transactional
    public PharmacyDtos.FormularyResponse add(PharmacyDtos.CreateFormularyRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (formulary.findByCode(code).isPresent()) {
            throw new ConflictException("A formulary entry with code '" + code + "' already exists");
        }
        Formulary saved = formulary.save(new Formulary(code, request.name().trim(),
                request.form().trim().toUpperCase(Locale.ROOT), request.strength().trim(),
                request.unit().trim(), Boolean.TRUE.equals(request.controlled())));
        for (String ingredient : request.ingredients()) {
            ingredients.save(new FormularyIngredient(code,
                    ingredient.trim().toUpperCase(Locale.ROOT).replace(' ', '_')));
        }
        audit.record("FORMULARY_ADDED", "Formulary", saved.getId(),
                "%s: %d ingredient(s)".formatted(code, request.ingredients().size()));
        return catalogue(null, true).stream()
                .filter(row -> row.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Renames or retires an entry.
     *
     * <p>The code is not editable and neither is the ingredient list. A code is referenced by every
     * prescription and every batch; an ingredient list is what the safety checks run on, and
     * editing it in place would silently change what past prescriptions were checked against.
     * Correcting one means retiring the entry and adding it again, which is visible.
     */
    @Transactional
    public PharmacyDtos.FormularyResponse update(String code,
                                                 PharmacyDtos.UpdateFormularyRequest request) {
        Formulary entry = formulary.findByCode(code)
                .orElseThrow(() -> new NotFoundException("No formulary entry with code " + code));
        if (request.name() != null && !request.name().isBlank()) {
            entry.setName(request.name().trim());
        }
        if (request.active() != null) {
            entry.setActive(request.active());
        }
        formulary.save(entry);
        audit.record("FORMULARY_UPDATED", "Formulary", entry.getId(),
                "%s, active=%s".formatted(code, entry.isActive()));
        return catalogue(null, true).stream()
                .filter(row -> row.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<PharmacyDtos.InteractionResponse> pairings() {
        return interactions.findAllByOrderByIngredientAAscIngredientBAsc().stream()
                .map(FormularyService::toResponse)
                .toList();
    }

    /**
     * Records or corrects a pairing.
     *
     * <p>Upsert rather than create-or-409, because the pair is the identity: a deployment adding
     * (aspirin, warfarin) when (warfarin, aspirin) is already recorded means to change that row,
     * not to add a second one — and the ordered-pair CHECK would refuse the second anyway. The
     * normalisation happens in {@link InteractionPair}'s constructor, so both orders find the row.
     */
    @Transactional
    public PharmacyDtos.InteractionResponse upsert(PharmacyDtos.UpsertInteractionRequest request) {
        String first = request.ingredientA().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        String second = request.ingredientB().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (first.equals(second)) {
            throw new ConflictException(
                    "An ingredient cannot interact with itself; those two are the same ('"
                            + first + "').");
        }
        String low = first.compareTo(second) <= 0 ? first : second;
        String high = first.compareTo(second) <= 0 ? second : first;

        InteractionPair pair = interactions.findByIngredientAAndIngredientB(low, high)
                .orElseGet(() -> new InteractionPair(low, high, request.severity(),
                        request.effect().trim(), request.management().trim(), request.source()));
        pair.setSeverity(request.severity());
        pair.setManagement(request.management().trim());
        InteractionPair saved = interactions.save(pair);
        audit.record("INTERACTION_RECORDED", "InteractionPair", saved.getId(),
                "%s + %s = %s".formatted(low, high, request.severity()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PharmacyDtos.InteractionResponse readPairing(UUID id) {
        return interactions.findById(id).map(FormularyService::toResponse)
                .orElseThrow(() -> new NotFoundException("No interaction pairing with id " + id));
    }

    private static PharmacyDtos.InteractionResponse toResponse(InteractionPair pair) {
        return new PharmacyDtos.InteractionResponse(pair.getId(), pair.getIngredientA(),
                pair.getIngredientB(), pair.getSeverity(), pair.getEffect(), pair.getManagement(),
                pair.getSource());
    }
}
