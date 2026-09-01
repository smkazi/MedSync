package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A booked slot with a clinician.
 *
 * <p>Overlap is prevented by a PostgreSQL exclusion constraint rather than an application check,
 * because a check-then-insert loses to a concurrent booking. The status machine below decides
 * which transitions are legal; the constraint decides whether the time is free.
 */
@Entity
@Table(name = "appointments")
public class Appointment extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24)
    private String patientMrn;

    @Column(name = "clinician_id", nullable = false)
    private UUID clinicianId;

    @Column(name = "clinician_name", length = 160)
    private String clinicianName;

    @Column(name = "department_code", nullable = false, length = 16)
    private String departmentCode;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SchedulingEnums.AppointmentStatus status = SchedulingEnums.AppointmentStatus.BOOKED;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private SchedulingEnums.Priority priority = SchedulingEnums.Priority.ROUTINE;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "booked_by", nullable = false, length = 64)
    private String bookedBy;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "cancelled_reason", length = 255)
    private String cancelledReason;

    /** Cached from ai-service at booking. Null when decision support was unavailable. */
    @Column(name = "no_show_risk", precision = 5, scale = 4)
    private BigDecimal noShowRisk;

    @Column(name = "no_show_band", length = 8)
    private String noShowBand;

    protected Appointment() {
    }

    public Appointment(UUID patientId, String patientMrn, UUID clinicianId, String departmentCode,
                       Instant startsAt, Instant endsAt, String bookedBy) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.clinicianId = clinicianId;
        this.departmentCode = departmentCode;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.bookedBy = bookedBy;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public UUID getClinicianId() {
        return clinicianId;
    }

    public String getClinicianName() {
        return clinicianName;
    }

    public void setClinicianName(String clinicianName) {
        this.clinicianName = clinicianName;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public SchedulingEnums.AppointmentStatus getStatus() {
        return status;
    }

    public SchedulingEnums.Priority getPriority() {
        return priority;
    }

    public void setPriority(SchedulingEnums.Priority priority) {
        this.priority = priority;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBookedBy() {
        return bookedBy;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public String getCancelledReason() {
        return cancelledReason;
    }

    public BigDecimal getNoShowRisk() {
        return noShowRisk;
    }

    public String getNoShowBand() {
        return noShowBand;
    }

    public void applyNoShowRisk(BigDecimal risk, String band) {
        this.noShowRisk = risk;
        this.noShowBand = band;
    }

    /** Whether the appointment may still be moved or cancelled. */
    public boolean isAmendable() {
        return !status.isTerminal();
    }

    public void reschedule(Instant newStart, Instant newEnd) {
        this.startsAt = newStart;
        this.endsAt = newEnd;
        // A moved appointment goes back to BOOKED: the patient has not arrived for the new time.
        this.status = SchedulingEnums.AppointmentStatus.BOOKED;
        this.checkedInAt = null;
    }

    public void checkIn() {
        this.status = SchedulingEnums.AppointmentStatus.CHECKED_IN;
        this.checkedInAt = Instant.now();
    }

    public void begin() {
        this.status = SchedulingEnums.AppointmentStatus.IN_PROGRESS;
    }

    public void complete() {
        this.status = SchedulingEnums.AppointmentStatus.COMPLETED;
    }

    public void cancel(String reason) {
        this.status = SchedulingEnums.AppointmentStatus.CANCELLED;
        this.cancelledReason = reason;
    }

    /**
     * Marks the patient as not attended.
     *
     * <p>Only meaningful once the appointment time has passed and the patient never checked in —
     * the service enforces that, so a no-show cannot be recorded against someone who is present.
     */
    public void markNoShow() {
        this.status = SchedulingEnums.AppointmentStatus.NO_SHOW;
    }

    /** Which transitions are legal from the current status. */
    public boolean canTransitionTo(SchedulingEnums.AppointmentStatus target) {
        return switch (status) {
            case BOOKED -> target == SchedulingEnums.AppointmentStatus.CHECKED_IN
                    || target == SchedulingEnums.AppointmentStatus.CANCELLED
                    || target == SchedulingEnums.AppointmentStatus.NO_SHOW;
            case CHECKED_IN -> target == SchedulingEnums.AppointmentStatus.IN_PROGRESS
                    || target == SchedulingEnums.AppointmentStatus.CANCELLED;
            case IN_PROGRESS -> target == SchedulingEnums.AppointmentStatus.COMPLETED;
            case COMPLETED, CANCELLED, NO_SHOW -> false;
        };
    }
}
