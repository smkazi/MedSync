package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line of an order set: a test to raise, or a medicine to prescribe.
 *
 * <p>The code is not a foreign key. A test code belongs to laboratory-service and a drug code to
 * pharmacy-service, and this service must not fail because one of them is mid-migration — the same
 * reasoning that leaves {@code patient_id} unconstrained everywhere on this platform. What replaces
 * the constraint is that both are checked at the moment the set is applied, which is the moment it
 * matters: a retired test refuses by name, and the clinician is told which line failed.
 */
@Entity
@Table(name = "order_set_items")
public class OrderSetItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_set_id", nullable = false)
    private OrderSet orderSet;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private SchedulingEnums.OrderSetKind kind;

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "dose", length = 48)
    private String dose;

    @Column(name = "frequency", length = 48)
    private String frequency;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "instructions", length = 500)
    private String instructions;

    @Column(name = "priority", length = 16)
    private String priority;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected OrderSetItem() {
    }

    /** A laboratory line: a test code and a priority, and deliberately no dose fields. */
    public static OrderSetItem lab(String code, String priority, int displayOrder) {
        OrderSetItem item = new OrderSetItem();
        item.kind = SchedulingEnums.OrderSetKind.LAB;
        item.code = code;
        item.priority = priority;
        item.displayOrder = displayOrder;
        return item;
    }

    /** A medication line. Every dose field is required, by the database as well as here. */
    public static OrderSetItem medication(String code, String dose, String frequency,
                                          int durationDays, int quantity, String instructions,
                                          int displayOrder) {
        OrderSetItem item = new OrderSetItem();
        item.kind = SchedulingEnums.OrderSetKind.MEDICATION;
        item.code = code;
        item.dose = dose;
        item.frequency = frequency;
        item.durationDays = durationDays;
        item.quantity = quantity;
        item.instructions = instructions;
        item.displayOrder = displayOrder;
        return item;
    }

    void setOrderSet(OrderSet orderSet) {
        this.orderSet = orderSet;
    }

    public SchedulingEnums.OrderSetKind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public String getDose() {
        return dose;
    }

    public String getFrequency() {
        return frequency;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getPriority() {
        return priority;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
