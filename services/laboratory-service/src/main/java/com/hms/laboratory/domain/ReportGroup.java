package com.hms.laboratory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A section heading on the printed report. */
@Entity
@Table(name = "report_groups")
public class ReportGroup {

    @Id
    @Column(name = "code", nullable = false, length = 16, updatable = false)
    private String code;

    @Column(name = "title", nullable = false, length = 80)
    private String title;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 100;

    @Column(name = "version", nullable = false)
    private long version;

    protected ReportGroup() {
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }
}
