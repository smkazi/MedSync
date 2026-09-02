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

    private Topics() {
    }
}
