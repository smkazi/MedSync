package com.hms.laboratory.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A request for laboratory work on one patient.
 *
 * <p>The patient's MRN and sex are copied onto the order rather than looked up each time. That is
 * deliberate: reference ranges are sex-specific, and a result must be interpretable years later
 * from the order alone, without a call to another service that may have changed or be unavailable.
 */
@Entity
@Table(name = "lab_orders")
public class LabOrder extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24)
    private String patientMrn;

    @Column(name = "patient_sex", nullable = false, length = 1)
    private String patientSex = "M";

    @Column(name = "ordered_by", nullable = false, length = 64)
    private String orderedBy;

    @Column(name = "department", nullable = false, length = 32)
    private String department = "PATH";

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private LabEnums.Priority priority = LabEnums.Priority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LabEnums.OrderStatus status = LabEnums.OrderStatus.ORDERED;

    @Column(name = "clinical_notes", length = 1000)
    private String clinicalNotes;

    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt = Instant.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("testCode asc")
    private List<LabOrderItem> items = new ArrayList<>();

    /** Ordered oldest first, so "the current tube" is unambiguously the last element. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt asc")
    private List<Specimen> specimens = new ArrayList<>();

    protected LabOrder() {
    }

    public LabOrder(UUID patientId, String patientMrn, String patientSex, String orderedBy,
                    LabEnums.Priority priority) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.patientSex = patientSex;
        this.orderedBy = orderedBy;
        this.priority = priority;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public String getPatientSex() {
        return patientSex;
    }

    public void setPatientSex(String patientSex) {
        this.patientSex = patientSex;
    }

    public String getOrderedBy() {
        return orderedBy;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public LabEnums.Priority getPriority() {
        return priority;
    }

    public LabEnums.OrderStatus getStatus() {
        return status;
    }

    public String getClinicalNotes() {
        return clinicalNotes;
    }

    public void setClinicalNotes(String clinicalNotes) {
        this.clinicalNotes = clinicalNotes;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }

    public List<LabOrderItem> getItems() {
        return items;
    }

    public List<Specimen> getSpecimens() {
        return specimens;
    }

    public void addItem(LabOrderItem item) {
        items.add(item);
    }

    public void addSpecimen(Specimen specimen) {
        specimens.add(specimen);
    }

    /** Whether results may still be recorded against this order. */
    public boolean acceptsResults() {
        return status != LabEnums.OrderStatus.CANCELLED && status != LabEnums.OrderStatus.VERIFIED;
    }

    /**
     * Advances the order's status, refusing to move backwards.
     *
     * <p>A late analyzer message must not drag a verified order back to IN_PROGRESS, and a
     * cancelled order must not be revived by one.
     */
    public void advanceTo(LabEnums.OrderStatus target) {
        if (status == LabEnums.OrderStatus.CANCELLED) {
            return;
        }
        if (target.ordinal() > status.ordinal() || target == LabEnums.OrderStatus.CANCELLED) {
            this.status = target;
        }
    }

    public void cancel() {
        this.status = LabEnums.OrderStatus.CANCELLED;
    }
}
