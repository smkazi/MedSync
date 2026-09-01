package com.hms.laboratory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Which section of the report a parameter prints in, and where within it.
 *
 * <p>A parameter with no row still prints, in the trailing "Other" section. Dropping a measured
 * value off a clinical report because nobody configured its section would be the worst available
 * failure for a lookup table.
 */
@Entity
@Table(name = "report_parameter_groups")
public class ReportParameterGroup {

    @Id
    @Column(name = "parameter", nullable = false, length = 24, updatable = false)
    private String parameter;

    @Column(name = "group_code", nullable = false, length = 16)
    private String groupCode;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 100;

    @Column(name = "version", nullable = false)
    private long version;

    protected ReportParameterGroup() {
    }

    public String getParameter() {
        return parameter;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }
}
