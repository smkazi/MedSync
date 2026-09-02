package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named list of things to raise at once.
 *
 * <p>The reason clinicians tolerate computerised ordering: a fever needs the same six things every
 * time, and typing them one at a time is where the sixth gets forgotten at four in the morning.
 *
 * <p>Rows rather than code, because adding one needs no new behaviour. What it does need is to be
 * complete — a medication line with no dose is a prompt to guess in the one place where a guess is
 * applied to a patient without anybody typing it — and that is a CHECK constraint rather than a
 * convention.
 */
@Entity
@Table(name = "order_sets")
public class OrderSet extends BaseEntity {

    @Column(name = "code", nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "department_code", length = 32)
    private String departmentCode;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "orderSet", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("displayOrder asc")
    private List<OrderSetItem> items = new ArrayList<>();

    protected OrderSet() {
    }

    public OrderSet(String code, String name, String description, String departmentCode) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.departmentCode = departmentCode;
    }

    public void addItem(OrderSetItem item) {
        items.add(item);
        item.setOrderSet(this);
    }

    public List<OrderSetItem> labItems() {
        return items.stream().filter(item -> item.getKind() == SchedulingEnums.OrderSetKind.LAB)
                .toList();
    }

    public List<OrderSetItem> medicationItems() {
        return items.stream()
                .filter(item -> item.getKind() == SchedulingEnums.OrderSetKind.MEDICATION)
                .toList();
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

    public String getDescription() {
        return description;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public List<OrderSetItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
