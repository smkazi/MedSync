package com.hms.admissions.domain;

/** The vocabulary of casualty and in-patient care. */
public final class AdmissionEnums {

    private AdmissionEnums() {
    }

    /**
     * A casualty attendance's life.
     *
     * <p>{@code LEFT_WITHOUT_BEING_SEEN} is a real status rather than a tidy-up. It is a standard
     * emergency-department quality metric — a department where it rises is a department people are
     * giving up on — and folding it into DISCHARGED would delete the only signal that says so.
     */
    public enum AttendanceStatus {
        WAITING, IN_BED, ADMITTED, DISCHARGED, LEFT_WITHOUT_BEING_SEEN;

        /** Still the department's problem, and still on the board. */
        public boolean isOpen() {
            return this == WAITING || this == IN_BED;
        }
    }

    public enum AdmissionStatus {
        ADMITTED, DISCHARGED
    }

    /**
     * Where the patient came from.
     *
     * <p>In code rather than a table because each value is a different pathway with different
     * expectations — an elective admission has a planned date and a casualty one does not — and
     * because a bed-day is priced from it. A configurable list would let somebody add a source the
     * census does not group by and the billing does not price.
     */
    public enum AdmissionSource {
        CASUALTY, ELECTIVE, TRANSFER, MATERNITY
    }

    /** Which path put a patient in a bed. */
    public enum OccupantType {
        CASUALTY, ADMISSION
    }
}
