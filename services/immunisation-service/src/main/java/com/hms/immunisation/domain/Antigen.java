package com.hms.immunisation.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * What a vaccine protects against — the vocabulary schedules, coverage and this whole module are
 * written in.
 *
 * <p>Separate from the product for the reason the pharmacy separates an ingredient from a brand: a
 * child vaccinated against measles is vaccinated against it under every trade name and inside every
 * combination product it ever arrived in. A pentavalent injection is one product and five of these.
 */
@Entity
@Table(name = "antigens")
public class Antigen extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "protects_against", nullable = false, length = 160)
    private String protectsAgainst;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected Antigen() {
    }

    public Antigen(String code, String name, String protectsAgainst) {
        this.code = code;
        this.name = name;
        this.protectsAgainst = protectsAgainst;
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

    public String getProtectsAgainst() {
        return protectsAgainst;
    }

    public void setProtectsAgainst(String protectsAgainst) {
        this.protectsAgainst = protectsAgainst;
    }

    public boolean isActive() {
        return active;
    }

    /** Retired, never deleted: doses recorded against it are still real, and schedules still name it. */
    public void setActive(boolean active) {
        this.active = active;
    }
}
