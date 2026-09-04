package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import com.hms.immunisation.domain.ImmunisationEnums.Route;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the store buys and a nurse picks off a list.
 *
 * <p>The antigens it contains are an {@code @ElementCollection} of codes rather than a mapped
 * association, which is the shape {@code Formulary} uses for its ingredients: the join table holds
 * codes and nothing else, and reading a product should not drag every antigen entity in behind it.
 *
 * <p><strong>The contents list is written once and never edited.</strong> There is no setter and no
 * mutator, deliberately. Editing it in place would change what already-recorded doses are counted
 * as covering — a child recorded as having had PENTA in 2024 had whatever PENTA contained in 2024,
 * and a row edited in 2026 must not retrospectively give them a Hib dose they did not receive. A
 * product whose formulation genuinely changes is a new code.
 */
@Entity
@Table(name = "vaccine_products")
public class VaccineProduct extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "manufacturer", nullable = false, length = 160)
    private String manufacturer;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, updatable = false, length = 24)
    private Route route;

    @Column(name = "doses_per_vial", nullable = false, updatable = false)
    private int dosesPerVial = 1;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "vaccine_product_antigens",
            joinColumns = @JoinColumn(name = "product_code", referencedColumnName = "code"))
    @Column(name = "antigen_code", nullable = false, length = 32)
    private Set<String> antigenCodes = new LinkedHashSet<>();

    protected VaccineProduct() {
    }

    public VaccineProduct(String code, String name, String manufacturer, Route route,
                          int dosesPerVial, Set<String> antigenCodes) {
        this.code = code;
        this.name = name;
        this.manufacturer = manufacturer;
        this.route = route;
        this.dosesPerVial = dosesPerVial;
        this.antigenCodes = new LinkedHashSet<>(antigenCodes);
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

    public String getManufacturer() {
        return manufacturer;
    }

    public Route getRoute() {
        return route;
    }

    public int getDosesPerVial() {
        return dosesPerVial;
    }

    public boolean isActive() {
        return active;
    }

    /** Retired rather than deleted: a retired product still has doses recorded against it. */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** Unmodifiable on purpose — see the class comment on why this list never changes. */
    public Set<String> getAntigenCodes() {
        return Set.copyOf(antigenCodes);
    }
}
