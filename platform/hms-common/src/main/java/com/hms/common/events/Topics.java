package com.hms.common.events;

/** Kafka topic names. One topic per aggregate family, keyed by aggregate id for ordering. */
public final class Topics {

    public static final String PATIENT = "hms.patient.events";
    public static final String APPOINTMENT = "hms.appointment.events";
    public static final String LAB = "hms.lab.events";
    public static final String AUDIT = "hms.audit.events";

    private Topics() {
    }
}
