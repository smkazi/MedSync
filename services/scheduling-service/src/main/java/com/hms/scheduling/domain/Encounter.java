package com.hms.scheduling.domain;

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

/** One episode of care: the container a note, vitals and diagnoses hang off. */
@Entity
@Table(name = "encounters")
public class Encounter extends BaseEntity {

    @Column(name = "appointment_id", unique = true)
    private UUID appointmentId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "patient_mrn", nullable = false, length = 24)
    private String patientMrn;

    @Column(name = "clinician_id", nullable = false)
    private UUID clinicianId;

    @Column(name = "department_code", nullable = false, length = 16)
    private String departmentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_type", nullable = false, length = 16)
    private SchedulingEnums.EncounterType encounterType = SchedulingEnums.EncounterType.OUTPATIENT;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SchedulingEnums.EncounterStatus status = SchedulingEnums.EncounterStatus.OPEN;

    @OneToMany(mappedBy = "encounter", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("revision asc")
    private List<ClinicalNote> notes = new ArrayList<>();

    protected Encounter() {
    }

    public Encounter(UUID patientId, String patientMrn, UUID clinicianId, String departmentCode,
                     SchedulingEnums.EncounterType encounterType) {
        this.patientId = patientId;
        this.patientMrn = patientMrn;
        this.clinicianId = clinicianId;
        this.departmentCode = departmentCode;
        this.encounterType = encounterType;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(UUID appointmentId) {
        this.appointmentId = appointmentId;
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

    public String getDepartmentCode() {
        return departmentCode;
    }

    public SchedulingEnums.EncounterType getEncounterType() {
        return encounterType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public SchedulingEnums.EncounterStatus getStatus() {
        return status;
    }

    public List<ClinicalNote> getNotes() {
        return notes;
    }

    public void addNote(ClinicalNote note) {
        notes.add(note);
    }

    public boolean isOpen() {
        return status == SchedulingEnums.EncounterStatus.OPEN;
    }

    /**
     * Closes the encounter.
     *
     * <p>Closing does not lock the chart: an addendum to a signed note is still allowed
     * afterwards, because corrections legitimately arrive after a patient has left.
     */
    public void close() {
        this.status = SchedulingEnums.EncounterStatus.CLOSED;
        this.endedAt = Instant.now();
    }

    /** The current note: the highest revision recorded. */
    public ClinicalNote currentNote() {
        return notes.isEmpty() ? null : notes.get(notes.size() - 1);
    }
}
