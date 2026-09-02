package com.hms.pharmacy.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One thing this hospital stocks.
 *
 * <p>A formulary entry is a product — a brand, a form and a strength — and the safety checks in
 * this service do not run on it. They run on its ingredients, which live in their own table,
 * because two brands of the same molecule are two rows here and one ingredient there, and a
 * patient allergic to penicillin is allergic to it under every trade name it has been sold under.
 *
 * <p>Retired rather than deleted, like every other vocabulary on the platform: prescriptions from
 * last year name this code, and `active` false takes it out of the pick-list without rewriting
 * them.
 */
@Entity
@Table(name = "formulary")
public class Formulary extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "form", nullable = false, length = 32)
    private String form;

    @Column(name = "strength", nullable = false, length = 48)
    private String strength;

    @Column(name = "unit", nullable = false, length = 24)
    private String unit;

    @Column(name = "controlled", nullable = false)
    private boolean controlled;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Formulary() {
    }

    public Formulary(String code, String name, String form, String strength, String unit,
                     boolean controlled) {
        this.code = code;
        this.name = name;
        this.form = form;
        this.strength = strength;
        this.unit = unit;
        this.controlled = controlled;
    }

    /** What appears on a prescription and on the label: name, strength and form together. */
    public String label() {
        return "%s %s %s".formatted(name, strength, form.toLowerCase());
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getForm() {
        return form;
    }

    public String getStrength() {
        return strength;
    }

    public String getUnit() {
        return unit;
    }

    public boolean isControlled() {
        return controlled;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
