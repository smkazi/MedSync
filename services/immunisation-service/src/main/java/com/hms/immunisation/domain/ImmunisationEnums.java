package com.hms.immunisation.domain;

/** The register's vocabularies that carry behaviour, and therefore stay in code. */
public final class ImmunisationEnums {

    private ImmunisationEnums() {
    }

    /**
     * Where the evidence for a dose comes from.
     *
     * <p>Three values and not a boolean, because "his mother says he had it" and "I am holding the
     * card that says he had it" are different grades of evidence, and a measure may legitimately
     * count one and not the other. A boolean would collapse that distinction on the first day
     * somebody needed it.
     *
     * <p>In code rather than a table, by {@code docs/extensibility.md}'s rule: each value decides
     * which columns are required. {@code ADMINISTERED_HERE} demands a lot, a route, a site and a
     * clinician; the historical two forbid a lot and demand a sentence saying what was seen. A
     * fourth value would be a row with no rule about what it must carry — a gap in the record
     * wearing a source's clothes.
     */
    public enum ImmunisationSource {
        /** Given in this hospital, by somebody named, from a lot this hospital holds. */
        ADMINISTERED_HERE,
        /** Copied from a card, a discharge letter or another register that was in front of us. */
        HISTORICAL_DOCUMENTED,
        /** What the family remembers. Kept, because it is a fact, and flagged, because it is not a record. */
        HISTORICAL_PARENT_REPORTED
    }

    /**
     * How a vaccine is given.
     *
     * <p>A property of the product rather than of the dose: BCG is intradermal and OPV is oral, and
     * a dose row that let somebody type otherwise would record a route the vaccine does not have.
     * Each value implies a different site and a different technique, so a sixth with nothing behind
     * it would be a route nothing checks.
     */
    public enum Route {
        INTRAMUSCULAR, SUBCUTANEOUS, INTRADERMAL, ORAL, INTRANASAL
    }

    /**
     * Why a child will not be vaccinated.
     *
     * <p>The two differ in behaviour, not in labelling, and that is why this is an enum: a
     * {@code MEDICAL} contraindication comes out of a coverage measure's denominator and a
     * {@code REFUSED} does not. A clinic able to exclude refusals could report a hundred per cent
     * coverage by recording refusals, and the measure would be measuring the recording of refusals.
     */
    public enum ExemptionKind {
        MEDICAL, REFUSED
    }

    /**
     * How bad an adverse event was.
     *
     * <p>Ordered, and {@code SERIOUS} is a term of art rather than an intensifier: it means death,
     * hospitalisation, disability or a congenital anomaly, which is the threshold at which a report
     * goes to the authority whatever anybody thinks of it. {@code SEVERE} describes how it felt.
     * Naming them separately is the difference between "this was nasty" and "this is reportable".
     */
    public enum Seriousness {
        MINOR, SEVERE, SERIOUS;

        /** True when this is at least as serious as {@code floor}. */
        public boolean atLeast(Seriousness floor) {
            return ordinal() >= floor.ordinal();
        }
    }

    /** How an adverse event ended. {@code UNKNOWN} is a real answer: follow-up is often lost. */
    public enum Outcome {
        RECOVERED, RECOVERING, NOT_RECOVERED, DIED, UNKNOWN
    }

    /**
     * Where one antigen's next dose stands, as at a date.
     *
     * <p>Computed, never stored. A dose becomes overdue because a day passed — nothing happens,
     * nobody writes a row, no event is published — so a column holding this would be a cache whose
     * invalidation key is the wall clock, refreshed by a scheduler this platform deliberately does
     * not have. {@code ImmunisationScheduleCalculator} derives it on every read instead, from a date
     * of birth and the register.
     *
     * <p>Every value is reported rather than filtered out, which is {@code SlotCalculator}'s
     * precedent: a screen that shows the whole picture and greys out what cannot be acted on beats a
     * shorter list that silently omits it. {@code Evaluation.outstanding()} is what a calling list
     * filters on.
     *
     * <p><strong>The two kinds of exemption behave differently here, and only one of them is
     * {@code EXEMPT}.</strong> A {@code MEDICAL} contraindication means the dose must not be given,
     * so it is not due and nobody should be telephoned. A {@code REFUSED} exemption does
     * <em>not</em> suppress the row: a family who declined last year is exactly who a clinic may
     * want to speak to again, and a platform that hid them would make one refusal permanent by
     * accident. The row carries {@code refusalRecorded} instead, so whoever picks up the telephone
     * knows what happened last time. The same behavioural split {@link ExemptionKind} makes for a
     * coverage denominator, one screen along.
     */
    public enum DueStatus {
        /** The date has not arrived yet. */
        NOT_YET_DUE,
        /** Due now, and still inside the grace period the schedule allows. */
        DUE,
        /** Past the due date and past the grace period. This is what a calling list is made of. */
        OVERDUE,
        /** Every dose of this antigen in this schedule has been given and counted. */
        COMPLETE,
        /** A live medical contraindication covers this antigen. Not due, and not a call to make. */
        EXEMPT,
        /**
         * The window closed before it was given: a birth dose at eight months is not a birth dose.
         * Reported rather than dropped, because "this child never had it and never will" is a fact
         * somebody should be able to see rather than infer from an absence.
         */
        NO_LONGER_GIVEN
    }
}
