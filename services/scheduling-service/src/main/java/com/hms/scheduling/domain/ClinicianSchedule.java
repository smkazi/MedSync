package com.hms.scheduling.domain;

import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;

/** One weekday's bookable window for a clinician, and how finely it is divided. */
@Entity
@Table(name = "clinician_schedules")
public class ClinicianSchedule extends BaseEntity {

    @Column(name = "clinician_id", nullable = false)
    private UUID clinicianId;

    @Column(name = "department_code", nullable = false, length = 16)
    private String departmentCode;

    /** ISO-8601: 1 = Monday, 7 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "slot_minutes", nullable = false)
    private int slotMinutes = 15;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ClinicianSchedule() {
    }

    public ClinicianSchedule(UUID clinicianId, String departmentCode, int dayOfWeek,
                             LocalTime startTime, LocalTime endTime, int slotMinutes) {
        this.clinicianId = clinicianId;
        this.departmentCode = departmentCode;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.slotMinutes = slotMinutes;
    }

    public UUID getClinicianId() {
        return clinicianId;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getSlotMinutes() {
        return slotMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
