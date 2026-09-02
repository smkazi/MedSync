package com.hms.billing.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Who settles the bill: the patient, an insurer, a scheme.
 *
 * <p>Behaviour as columns rather than as subclasses, which is the pattern this platform uses for
 * every configurable vocabulary that carries rules: a deployment adds a payer without a
 * deployment, and each flag is a decision the invoice logic asks about by name.
 */
@Entity
@Table(name = "payers")
public class Payer extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "requires_preauth", nullable = false)
    private boolean requiresPreauth;

    @Column(name = "allows_copay", nullable = false)
    private boolean allowsCopay = true;

    @Column(name = "settles_directly", nullable = false)
    private boolean settlesDirectly;

    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Payer() {
    }

    public Payer(String code, String name, boolean requiresPreauth, boolean allowsCopay,
                 boolean settlesDirectly, boolean taxExempt) {
        this.code = code;
        this.name = name;
        this.requiresPreauth = requiresPreauth;
        this.allowsCopay = allowsCopay;
        this.settlesDirectly = settlesDirectly;
        this.taxExempt = taxExempt;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isRequiresPreauth() {
        return requiresPreauth;
    }

    public boolean isAllowsCopay() {
        return allowsCopay;
    }

    public boolean isSettlesDirectly() {
        return settlesDirectly;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
