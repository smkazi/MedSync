package com.hms.imaging.domain;

/** The vocabularies radiology works in. Each one matches a CHECK constraint in {@code V1}. */
public final class ImagingEnums {

    private ImagingEnums() {
    }

    /**
     * Where an order is up to.
     *
     * <p>{@code ACQUIRED} and {@code REPORTED} are separate states because they are separate
     * people's work finishing: the images exist and nobody has read them yet is the single most
     * important state in a radiology department, and it is the queue a radiologist works from.
     */
    public enum OrderStatus {
        ORDERED,
        SCHEDULED,
        IN_PROGRESS,
        ACQUIRED,
        REPORTED,
        CANCELLED
    }

    /**
     * How soon.
     *
     * <p>Three values rather than the five an emergency department triages with: a request is
     * routine, needed today, or needed now. The worklist orders by this before it orders by time,
     * which is the whole reason it is not a queue.
     */
    public enum Priority {
        ROUTINE,
        URGENT,
        STAT
    }

    /**
     * A report's state.
     *
     * <p>{@code SIGNED} is release — there is no second step — and {@code AMENDED} is a signed
     * report that was superseded, with the previous text kept beside it. A draft is nobody's
     * answer, and the read endpoints say so rather than showing provisional findings as findings.
     */
    public enum ReportStatus {
        DRAFT,
        SIGNED,
        AMENDED
    }
}
