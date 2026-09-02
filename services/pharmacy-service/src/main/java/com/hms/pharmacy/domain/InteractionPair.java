package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.pharmacy.domain.PharmacyEnums.InteractionSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Two ingredients that should not be given together, and what to do about it.
 *
 * <p><strong>One row per unordered pair.</strong> The database holds the pair sorted
 * ({@code CHECK (ingredient_a < ingredient_b)}) and this class does the sorting, because the
 * alternative — two rows, one for each direction — is a deployment where (warfarin, aspirin) is
 * MAJOR and (aspirin, warfarin) is MINOR, and which one fires depends on the order the caller
 * happened to pass its ingredients in. That is not a hypothetical: it is the shape every
 * two-column pair table drifts into once somebody adds rows by hand.
 *
 * <p>{@code management} is the field that earns the module its keep. "These interact" makes a
 * prescriber close the dialog; "monitor INR weekly for the first month" tells them what to do
 * instead, and a warning with no action attached is a warning people learn to dismiss.
 */
@Entity
@Table(name = "interaction_pairs")
public class InteractionPair extends BaseEntity {

    @Column(name = "ingredient_a", nullable = false, length = 64, updatable = false)
    private String ingredientA;

    @Column(name = "ingredient_b", nullable = false, length = 64, updatable = false)
    private String ingredientB;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private InteractionSeverity severity;

    @Column(name = "effect", nullable = false, length = 255)
    private String effect;

    @Column(name = "management", nullable = false, length = 255)
    private String management;

    @Column(name = "source", length = 120)
    private String source;

    protected InteractionPair() {
    }

    public InteractionPair(String first, String second, InteractionSeverity severity, String effect,
                           String management, String source) {
        // Sorted here rather than trusted from the caller: the CHECK constraint would refuse the
        // wrong order, and refusing is not the behaviour that is wanted — the pair is genuinely
        // unordered, so the right answer is to normalise it, not to make the caller guess.
        String low = first.compareTo(second) <= 0 ? first : second;
        String high = first.compareTo(second) <= 0 ? second : first;
        this.ingredientA = low;
        this.ingredientB = high;
        this.severity = severity;
        this.effect = effect;
        this.management = management;
        this.source = source;
    }

    public String getIngredientA() {
        return ingredientA;
    }

    public String getIngredientB() {
        return ingredientB;
    }

    public InteractionSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(InteractionSeverity severity) {
        this.severity = severity;
    }

    public String getEffect() {
        return effect;
    }

    public String getManagement() {
        return management;
    }

    public void setManagement(String management) {
        this.management = management;
    }

    public String getSource() {
        return source;
    }
}
