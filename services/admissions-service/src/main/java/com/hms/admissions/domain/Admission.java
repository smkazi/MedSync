package com.hms.admissions.domain;

import com.hms.common.error.ConflictException;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An in-patient stay. */
@Entity
@Table(name = "admissions")
public class Admission extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "attendance_id", updatable = false)
    private UUID attendanceId;

    @Column(name = "bed_id", nullable = false)
    private UUID bedId;

    @Column(name = "bed_code", nullable = false, length = 24)
    private String bedCode;

    @Column(name = "room_code", nullable = false, length = 24)
    private String roomCode;

    @Column(name = "admitting_clinician_id", nullable = false, updatable = false)
    private UUID admittingClinicianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24, updatable = false)
    private AdmissionEnums.AdmissionSource source;

    @Column(name = "admitted_at", nullable = false, updatable = false)
    private Instant admittedAt = Instant.now();

    @Column(name = "expected_discharge")
    private LocalDate expectedDischarge;

    @Column(name = "discharged_at")
    private Instant dischargedAt;

    @Column(name = "discharge_summary", length = 1000)
    private String dischargeSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AdmissionEnums.AdmissionStatus status;

    protected Admission() {
    }

    public Admission(UUID patientId, String patientMrn, UUID attendanceId, UUID bedId, String bedCode,
                     String roomCode, UUID admittingClinicianId, AdmissionEnums.AdmissionSource source) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.attendanceId = attendanceId;
        this.bedId = bedId;
        this.bedCode = bedCode;
        this.roomCode = roomCode;
        this.admittingClinicianId = admittingClinicianId;
        this.source = source;
        this.status = AdmissionEnums.AdmissionStatus.ADMITTED;
    }

    /**
     * Moves the patient to another bed.
     *
     * <p>The occupancy rows are the record of where they have been; this is the current answer, so
     * the census and the bed map do not have to reconstruct it from a transfer log.
     */
    public void moveTo(UUID bed, String code, String room) {
        requireAdmitted("transferred");
        this.bedId = bed;
        this.bedCode = code;
        this.roomCode = room;
    }

    public void discharge(String summary) {
        requireAdmitted("discharged");
        this.status = AdmissionEnums.AdmissionStatus.DISCHARGED;
        this.dischargedAt = Instant.now();
        this.dischargeSummary = summary;
    }

    public void expectDischargeOn(LocalDate date) {
        requireAdmitted("re-dated");
        this.expectedDischarge = date;
    }

    private void requireAdmitted(String verb) {
        if (status != AdmissionEnums.AdmissionStatus.ADMITTED) {
            throw new ConflictException(
                    "This patient was discharged and cannot be " + verb);
        }
    }

    public boolean isAdmitted() {
        return status == AdmissionEnums.AdmissionStatus.ADMITTED;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public UUID getAttendanceId() {
        return attendanceId;
    }

    public UUID getBedId() {
        return bedId;
    }

    public String getBedCode() {
        return bedCode;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public UUID getAdmittingClinicianId() {
        return admittingClinicianId;
    }

    public AdmissionEnums.AdmissionSource getSource() {
        return source;
    }

    public Instant getAdmittedAt() {
        return admittedAt;
    }

    public LocalDate getExpectedDischarge() {
        return expectedDischarge;
    }

    public Instant getDischargedAt() {
        return dischargedAt;
    }

    public String getDischargeSummary() {
        return dischargeSummary;
    }

    public AdmissionEnums.AdmissionStatus getStatus() {
        return status;
    }
}
