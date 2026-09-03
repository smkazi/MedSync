package com.hms.imaging.domain;

import com.hms.common.error.ConflictException;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * A request for an examination: what was asked for, of whom, and why.
 *
 * <p>The patient's sex and date of birth are copied onto the order rather than looked up, for the
 * reason the laboratory copies them: a study must be interpretable years later from the order
 * alone, and a radiologist reporting a pelvis needs to know whose it is.
 */
@Entity
@Table(name = "imaging_orders")
public class ImagingOrder extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "patient_sex", nullable = false, length = 1)
    private String patientSex = "O";

    @Column(name = "patient_birth_date")
    private LocalDate patientBirthDate;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "modality", nullable = false, length = 16)
    private String modality;

    @Column(name = "body_part", length = 64)
    private String bodyPart;

    @Column(name = "procedure_code", nullable = false, length = 32)
    private String procedureCode;

    @Column(name = "procedure_name", nullable = false, length = 160)
    private String procedureName;

    @Column(name = "clinical_question", nullable = false, length = 1000)
    private String clinicalQuestion;

    @Column(name = "contrast", nullable = false)
    private boolean contrast;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private ImagingEnums.Priority priority = ImagingEnums.Priority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImagingEnums.OrderStatus status = ImagingEnums.OrderStatus.ORDERED;

    @Column(name = "ordered_by", nullable = false, length = 64, updatable = false)
    private String orderedBy;

    @Column(name = "ordered_at", nullable = false, updatable = false)
    private Instant orderedAt = Instant.now();

    @Column(name = "accession_no", nullable = false, length = 24, updatable = false)
    private String accessionNo;

    @Column(name = "scheduled_for")
    private Instant scheduledFor;

    @Column(name = "cancelled_reason", length = 255)
    private String cancelledReason;

    protected ImagingOrder() {
    }

    public ImagingOrder(UUID patientId, String patientMrn, String modality, String procedureCode,
                        String procedureName, String clinicalQuestion, String orderedBy,
                        String accessionNo) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.modality = modality.toUpperCase(Locale.ROOT);
        this.procedureCode = procedureCode;
        this.procedureName = procedureName;
        this.clinicalQuestion = clinicalQuestion;
        this.orderedBy = orderedBy;
        this.accessionNo = accessionNo;
    }

    /**
     * Whether this order can still move to {@code target}.
     *
     * <p>Modelled as a table of what follows what rather than as a set of {@code if} statements
     * scattered through the service, so the whole lifecycle can be read in one place. The rule that
     * matters most: nothing leaves {@code CANCELLED} or {@code REPORTED}. A cancelled request that
     * could be resumed would let a scan happen after somebody stopped it, and a reported study that
     * could go back to {@code ORDERED} would lose a signed report.
     */
    public boolean canTransitionTo(ImagingEnums.OrderStatus target) {
        return switch (status) {
            case ORDERED -> target == ImagingEnums.OrderStatus.SCHEDULED
                    || target == ImagingEnums.OrderStatus.IN_PROGRESS
                    || target == ImagingEnums.OrderStatus.ACQUIRED
                    || target == ImagingEnums.OrderStatus.CANCELLED;
            case SCHEDULED -> target == ImagingEnums.OrderStatus.IN_PROGRESS
                    || target == ImagingEnums.OrderStatus.ACQUIRED
                    || target == ImagingEnums.OrderStatus.CANCELLED;
            case IN_PROGRESS -> target == ImagingEnums.OrderStatus.ACQUIRED
                    || target == ImagingEnums.OrderStatus.CANCELLED;
            // An acquired study can still be cancelled -- the wrong examination does get performed,
            // and the honest record of that is a cancelled order with images attached to it.
            case ACQUIRED -> target == ImagingEnums.OrderStatus.REPORTED
                    || target == ImagingEnums.OrderStatus.CANCELLED;
            case REPORTED, CANCELLED -> false;
        };
    }

    /** Moves the order on, refusing in the platform's own words rather than silently no-oping. */
    public void transitionTo(ImagingEnums.OrderStatus target) {
        if (status == target) {
            return;
        }
        if (!canTransitionTo(target)) {
            throw new ConflictException("An order that is %s cannot become %s"
                    .formatted(status, target));
        }
        this.status = target;
    }

    public void schedule(Instant when) {
        this.scheduledFor = when;
        transitionTo(ImagingEnums.OrderStatus.SCHEDULED);
    }

    public void cancel(String reason) {
        transitionTo(ImagingEnums.OrderStatus.CANCELLED);
        this.cancelledReason = reason;
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

    public LocalDate getPatientBirthDate() {
        return patientBirthDate;
    }

    public void setPatientBirthDate(LocalDate patientBirthDate) {
        this.patientBirthDate = patientBirthDate;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(UUID encounterId) {
        this.encounterId = encounterId;
    }

    public String getModality() {
        return modality;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public void setBodyPart(String bodyPart) {
        this.bodyPart = bodyPart;
    }

    public String getProcedureCode() {
        return procedureCode;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public String getClinicalQuestion() {
        return clinicalQuestion;
    }

    public boolean isContrast() {
        return contrast;
    }

    public void setContrast(boolean contrast) {
        this.contrast = contrast;
    }

    public ImagingEnums.Priority getPriority() {
        return priority;
    }

    public void setPriority(ImagingEnums.Priority priority) {
        this.priority = priority;
    }

    public ImagingEnums.OrderStatus getStatus() {
        return status;
    }

    public String getOrderedBy() {
        return orderedBy;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }

    public String getAccessionNo() {
        return accessionNo;
    }

    public Instant getScheduledFor() {
        return scheduledFor;
    }

    public String getCancelledReason() {
        return cancelledReason;
    }
}
