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

    /**
     * What one line of an order set raises.
     *
     * <p>In code rather than a table because each value maps to a different service, a different
     * request shape and a different set of required fields: a third value with nothing behind it
     * would be a row that silently raises nothing when the set is applied.
     */
    public enum OrderSetKind {
        LAB, MEDICATION
    }

    /** A care plan's life. */
    public enum CarePlanStatus {
        ACTIVE, COMPLETED, CANCELLED
    }

    /**
     * Whether a goal was reached.
     *
     * <p>NOT_MET and ABANDONED are separate values, and both require a note. "We tried and it did
     * not happen" and "we stopped trying, and here is why" are different facts about a patient's
     * admission, and a review that cannot tell them apart cannot learn anything from either.
     */
    public enum GoalStatus {
        OPEN, MET, NOT_MET, ABANDONED
    }
}
