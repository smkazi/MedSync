package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;

/** An orderable test or panel, and the parameters it is expected to report. */
@Entity
@Table(name = "lab_test_catalog")
public class LabTestCatalogEntry extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "department", nullable = false, length = 32)
    private String department = "HAEMATOLOGY";

    @Column(name = "specimen_type", nullable = false, length = 32)
    private String specimenType = "WHOLE_BLOOD";

    /** Comma-separated parameter codes; a panel's expected result rows. */
    @Column(name = "parameters", nullable = false, length = 1000)
    private String parameters = "";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected LabTestCatalogEntry() {
    }

    public LabTestCatalogEntry(String code, String name, String department, String specimenType, String parameters) {
        this.code = code;
        this.name = name;
        this.department = department;
        this.specimenType = specimenType;
        this.parameters = parameters == null ? "" : parameters;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDepartment() {
        return department;
    }

    public String getSpecimenType() {
        return specimenType;
    }

    public boolean isActive() {
        return active;
    }

    /** The parameters this panel reports, as a list. */
    public List<String> parameterList() {
        if (parameters.isBlank()) {
            return List.of();
        }
        return Arrays.stream(parameters.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
