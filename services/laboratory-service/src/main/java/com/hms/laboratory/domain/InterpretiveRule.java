package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * One interpretive comment and the conditions that trigger it.
 *
 * <p>Rows rather than code, because adding a rule needs no new behaviour — the same test that
 * governs the seeded fifteen governs the sixteenth the moment it is inserted. Ported from the
 * {@code ANALYZER_FLAG_RULES} literal in smkazi/HaematologyIS, where retuning a threshold meant
 * editing Python.
 *
 * <p>Conditions are ANDed. Anisocytosis needs both RDW-CV and RDW-SD raised because either alone is
 * unreliable, which is why a rule owns a set of conditions rather than one comparison.
 */
@Entity
@Table(name = "interpretive_rules")
public class InterpretiveRule extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 100;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("threshold")
    private List<InterpretiveRuleCondition> conditions = new ArrayList<>();

    protected InterpretiveRule() {
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public String getMessage() {
        return message;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public List<InterpretiveRuleCondition> getConditions() {
        return conditions;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
