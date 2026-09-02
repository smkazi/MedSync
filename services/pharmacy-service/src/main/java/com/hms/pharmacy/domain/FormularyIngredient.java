package com.hms.pharmacy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * What a product contains.
 *
 * <p>A join table with no surrogate key and no auditing columns, which is why it does not extend
 * {@code BaseEntity}: the pair *is* the row, and there is nothing to say about it beyond that it
 * exists. Every check in this service — allergy and interaction alike — starts here.
 */
@Entity
@Table(name = "formulary_ingredients")
public class FormularyIngredient {

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "drug_code", nullable = false, length = 32)
        private String drugCode;

        @Column(name = "ingredient_code", nullable = false, length = 64)
        private String ingredientCode;

        protected Key() {
        }

        public Key(String drugCode, String ingredientCode) {
            this.drugCode = drugCode;
            this.ingredientCode = ingredientCode;
        }

        public String getDrugCode() {
            return drugCode;
        }

        public String getIngredientCode() {
            return ingredientCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(drugCode, that.drugCode)
                    && Objects.equals(ingredientCode, that.ingredientCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(drugCode, ingredientCode);
        }
    }

    @EmbeddedId
    private Key id;

    protected FormularyIngredient() {
    }

    public FormularyIngredient(String drugCode, String ingredientCode) {
        this.id = new Key(drugCode, ingredientCode);
    }

    public String getDrugCode() {
        return id.getDrugCode();
    }

    public String getIngredientCode() {
        return id.getIngredientCode();
    }
}
