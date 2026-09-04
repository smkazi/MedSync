package com.hms.common.events;

/** Kafka topic names. One topic per aggregate family, keyed by aggregate id for ordering. */
public final class Topics {

    public static final String PATIENT = "hms.patient.events";
    public static final String APPOINTMENT = "hms.appointment.events";
    public static final String LAB = "hms.lab.events";
    public static final String AUDIT = "hms.audit.events";

    /**
     * Casualty attendances, admissions, transfers and discharges.
     *
     * <p>Its own topic rather than a share of {@code hms.appointment.events}: an admission is a
     * different aggregate family with a different key, and messages are keyed by aggregate id for
     * ordering — mixing them would put two unrelated orderings in one partition.
     */
    public static final String ADMISSION = "hms.admission.events";

    /**
     * Prescriptions, dispenses and doses given.
     *
     * <p>Its own topic for the reason {@link #ADMISSION} is: a different aggregate family with a
     * different key. It also carries what billing will need — a dispense is a chargeable event —
     * which is why the events are published now rather than added when a billing service exists.
     * A module that has to be changed to become observable is a module nobody makes observable.
     */
    public static final String PHARMACY = "hms.pharmacy.events";

    /**
     * Invoices, payments and claims.
     *
     * <p>Its own topic, like the others. Nothing consumes it yet — billing is the end of the chain
     * rather than the middle — and it is published anyway, because a module that has to be changed
     * to become observable is a module nobody makes observable.
     */
    public static final String BILLING = "hms.billing.events";

    /**
     * Radiology: what was ordered, what was scanned, and what the radiologist signed.
     *
     * <p>Its own topic, like the others. The event that carries weight is the signed report —
     * billing prices it, and notification tells the patient a report is ready without saying a word
     * about what it says. It carries the procedure code rather than a count, which is the correction
     * the laboratory's release event needed once billing existed: a count cannot be priced.
     */
    public static final String IMAGING = "hms.imaging.events";

    /**
     * Immunisations: what was given, and to whom.
     *
     * <p>Its own topic, on the rule the others follow — a distinct aggregate family with a distinct
     * key. An immunisation is not an appointment and not a prescription: it is a fact about a person
     * that outlives both, which is why it is a separate FHIR resource too.
     *
     * <p>The event carries the product code <em>and</em> the antigen codes rather than a count, for
     * the reason the imaging and laboratory events both had to learn: a count cannot be priced, and
     * a vaccine administration is chargeable. The antigens are there because they are what a
     * downstream register or a coverage report keys on, and expanding a product into them needs
     * this service's own join table.
     *
     * <p>Published from the first commit though nothing consumes it yet, which is the rule stated
     * twice above: a module that has to be changed to become observable is a module nobody makes
     * observable.
     */
    public static final String IMMUNISATION = "hms.immunisation.events";

    private Topics() {
    }
}
