package com.hms.pharmacy.domain;

/** The vocabulary of the medication loop. */
public final class PharmacyEnums {

    private PharmacyEnums() {
    }

    /**
     * How badly two ingredients interact.
     *
     * <p>In code rather than a table, and this is the load-bearing decision in the module: the
     * values are ordered, the ordering is what a deployment's refusal floor is compared against,
     * and a configurable list would let somebody insert a severity between MODERATE and MAJOR that
     * no comparison knows where to put. The pairings themselves are rows — thousands of them, and
     * they change as evidence changes. The scale they are graded on does not.
     */
    public enum InteractionSeverity {
        MINOR, MODERATE, MAJOR, CONTRAINDICATED;

        public boolean atLeast(InteractionSeverity floor) {
            return compareTo(floor) >= 0;
        }
    }

    /**
     * A prescription's life.
     *
     * <p>Three states and no PARTIALLY_DISPENSED, deliberately: how much of an item has been handed
     * over is a number on the item (`quantity_dispensed`), and a status derived from a sum of
     * numbers is a second copy of that sum which can disagree with it. COMPLETED means every item
     * has been fully dispensed, and the service asks the numbers.
     */
    public enum PrescriptionStatus {
        ACTIVE, COMPLETED, CANCELLED
    }

    /**
     * What happened at the bedside.
     *
     * <p>REFUSED is the patient declining; OMITTED is the dose not being given for any other
     * reason — nil by mouth, medicine unavailable, patient off the ward. Both require a reason, and
     * they are separate values because "the patient did not want it" and "we did not have it" are
     * different problems with different fixes, and folding them together loses the distinction the
     * next shift needs.
     */
    public enum AdministrationStatus {
        GIVEN, REFUSED, OMITTED
    }

    /**
     * What a safety check decided.
     *
     * <p>Not a boolean, because there are three answers and only one of them is "fine": REFUSED
     * cannot be overridden at all, OVERRIDABLE can be with a recorded reason, and CLEAR means
     * nothing was found. A two-valued check would force the middle case into one of the outer ones,
     * and both choices are wrong — refusing everything makes the check something clinicians route
     * around, and warning about everything makes it something they stop reading.
     */
    public enum CheckOutcome {
        CLEAR, OVERRIDABLE, REFUSED
    }
}
