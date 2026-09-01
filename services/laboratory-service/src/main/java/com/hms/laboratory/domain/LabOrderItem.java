package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One test or panel on an order. */
@Entity
@Table(name = "lab_order_items")
public class LabOrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private LabOrder order;

    @Column(name = "test_code", nullable = false, length = 24)
    private String testCode;

    @Column(name = "test_name", nullable = false, length = 160)
    private String testName;

    protected LabOrderItem() {
    }

    public LabOrderItem(LabOrder order, String testCode, String testName) {
        this.order = order;
        this.testCode = testCode;
        this.testName = testName;
    }

    public String getTestCode() {
        return testCode;
    }

    public String getTestName() {
        return testName;
    }
}
