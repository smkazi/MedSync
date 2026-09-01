package com.hms.scheduling.domain;

/** The scheduling vocabularies. */
public final class SchedulingEnums {

    private SchedulingEnums() {
    }

    /**
     * An appointment's lifecycle: BOOKED → CHECKED_IN → IN_PROGRESS → COMPLETED, with CANCELLED
     * and NO_SHOW as terminal alternatives.
     */
    public enum AppointmentStatus {
        BOOKED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW;

        /** Whether this status still occupies the clinician's slot. */
        public boolean occupiesSlot() {
            return this != CANCELLED && this != NO_SHOW;
        }

        public boolean isTerminal() {
            return this == COMPLETED || this == CANCELLED || this == NO_SHOW;
        }
    }

    public enum Priority {
        ROUTINE, URGENT, STAT
    }

    public enum EncounterType {
        OUTPATIENT, INPATIENT, EMERGENCY, TELEHEALTH
    }

    public enum EncounterStatus {
        OPEN, CLOSED
    }

    public enum DiagnosisCategory {
        PRIMARY, SECONDARY, PROVISIONAL
    }
}
