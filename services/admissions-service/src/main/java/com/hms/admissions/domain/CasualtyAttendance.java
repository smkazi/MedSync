package com.hms.admissions.domain;

import com.hms.common.error.ConflictException;
import com.hms.common.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Somebody who has walked into casualty.
 *
 * <p>The field that matters is {@code triageAcuity}. The board orders by it before arrival time,
 * and that ordering is the entire clinical point of the module — a casualty queue served in the
 * order people arrived kills the person who arrived last and is the sickest. Everything else here
 * is bookkeeping around that one sort.
 */
@Entity
@Table(name = "casualty_attendances")
public class CasualtyAttendance extends BaseEntity {

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24, updatable = false)
    private String patientMrn;

    @Column(name = "arrived_at", nullable = false, updatable = false)
    private Instant arrivedAt = Instant.now();

    @Column(name = "triage_acuity", nullable = false)
    private short triageAcuity;

    @Column(name = "presenting_complaint", nullable = false, length = 255)
    private String presentingComplaint;

    @Column(name = "bed_id")
    private UUID bedId;

    @Column(name = "bed_code", length = 24)
    private String bedCode;

    @Column(name = "room_code", length = 24)
    private String roomCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AdmissionEnums.AttendanceStatus status;

    @Column(name = "admission_id")
    private UUID admissionId;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "triaged_by", nullable = false, length = 64, updatable = false)
    private String triagedBy;

    protected CasualtyAttendance() {
    }

    public CasualtyAttendance(UUID patientId, String patientMrn, short triageAcuity,
                              String presentingComplaint, String triagedBy) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.triageAcuity = triageAcuity;
        this.presentingComplaint = presentingComplaint;
        this.triagedBy = triagedBy;
        this.status = AdmissionEnums.AttendanceStatus.WAITING;
    }

    /** A bed has been found. */
    public void placeIn(UUID bed, String code, String room) {
        requireOpen("moved to a bed");
        this.bedId = bed;
        this.bedCode = code;
        this.roomCode = room;
        this.status = AdmissionEnums.AttendanceStatus.IN_BED;
    }

    /** Re-triaged. A patient waiting in a corridor can get worse, and the board must notice. */
    public void retriage(short acuity) {
        requireOpen("re-triaged");
        this.triageAcuity = acuity;
    }

    /** Sent home. */
    public void discharge() {
        close(AdmissionEnums.AttendanceStatus.DISCHARGED);
    }

    /**
     * Gave up and left.
     *
     * <p>Its own outcome rather than a discharge, because it is a standard quality metric: a
     * department where this rises is a department people are giving up on, and recording it as a
     * discharge would delete the only signal that says so.
     */
    public void leftWithoutBeingSeen() {
        close(AdmissionEnums.AttendanceStatus.LEFT_WITHOUT_BEING_SEEN);
    }

    /** Became an in-patient. The two halves of one visit join here. */
    public void admitted(UUID admission) {
        close(AdmissionEnums.AttendanceStatus.ADMITTED);
        this.admissionId = admission;
    }

    private void close(AdmissionEnums.AttendanceStatus outcome) {
        requireOpen("closed");
        this.status = outcome;
        this.closedAt = Instant.now();
    }

    private void requireOpen(String verb) {
        if (!status.isOpen()) {
            throw new ConflictException(
                    "This attendance is already " + status + " and cannot be " + verb);
        }
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    public UUID getPatientId() {
        return patientId;
    }

    public String getPatientMrn() {
        return patientMrn;
    }

    public Instant getArrivedAt() {
        return arrivedAt;
    }

    public short getTriageAcuity() {
        return triageAcuity;
    }

    public String getPresentingComplaint() {
        return presentingComplaint;
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

    public AdmissionEnums.AttendanceStatus getStatus() {
        return status;
    }

    public UUID getAdmissionId() {
        return admissionId;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getTriagedBy() {
        return triagedBy;
    }
}
