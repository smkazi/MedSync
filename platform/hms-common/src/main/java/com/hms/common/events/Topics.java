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

    private Topics() {
    }
}
