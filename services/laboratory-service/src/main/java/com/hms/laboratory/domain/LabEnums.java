package com.hms.laboratory.domain;

/** The laboratory's state vocabularies, kept together so the workflow reads in one place. */
public final class LabEnums {

    private LabEnums() {
    }

    /** How quickly a result is needed; drives worklist ordering. */
    public enum Priority {
        ROUTINE, URGENT, STAT
    }

    /**
     * An order's progress. ORDERED → COLLECTED → IN_PROGRESS → RESULTED → VERIFIED, with
     * CANCELLED reachable until results exist.
     */
    public enum OrderStatus {
        ORDERED, COLLECTED, IN_PROGRESS, RESULTED, VERIFIED, CANCELLED
    }

    public enum SpecimenStatus {
        PENDING, COLLECTED, RECEIVED, REJECTED
    }

    /** Where a result came from. DERIVED means the platform computed it from a histogram. */
    public enum ResultSource {
        ANALYZER, MANUAL, DERIVED
    }

    /** ENTERED results are provisional; only a pathologist's VERIFIED result is releasable. */
    public enum ResultStatus {
        ENTERED, VERIFIED, AMENDED
    }

    /** The wire protocol an analyzer speaks. */
    public enum Protocol {
        ASTM, KDPS
    }
}
