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

    /**
     * Where a queue token is in its short life.
     *
     * <p>Three states and no more. {@code WAITING} is issued at check-in, {@code CALLED} when the
     * consultation begins, {@code DONE} when it ends. There is no SKIPPED: a patient who does not
     * answer their number is a no-show on the appointment, which the appointment's own state
     * machine already records — a second place to say the same thing is a second place for the two
     * to disagree.
     */
    public enum TokenStatus {
        WAITING, CALLED, DONE
    }
}
