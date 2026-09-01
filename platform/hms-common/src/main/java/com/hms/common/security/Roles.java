package com.hms.common.security;

/**
 * The platform's role vocabulary. Kept as constants because they appear inside
 * {@code @PreAuthorize} SpEL strings, where a typo would silently fail open or closed.
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String DOCTOR = "DOCTOR";
    public static final String NURSE = "NURSE";
    public static final String RECEPTIONIST = "RECEPTIONIST";
    public static final String LAB_TECH = "LAB_TECH";
    public static final String PATHOLOGIST = "PATHOLOGIST";

    /** Everyone who may read a patient chart. */
    public static final String CLINICAL_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH','PATHOLOGIST')";

    /** Clinicians who may write clinical content (notes, diagnoses, vitals). */
    public static final String CLINICAL_WRITE = "hasAnyRole('ADMIN','DOCTOR','NURSE')";

    /** Front-desk operations: registration, booking, check-in. */
    public static final String FRONT_DESK = "hasAnyRole('ADMIN','RECEPTIONIST','DOCTOR','NURSE')";

    /** Laboratory operations: order handling and result entry. */
    public static final String LAB_WRITE = "hasAnyRole('ADMIN','LAB_TECH','PATHOLOGIST')";

    /** Only a pathologist (or admin) may verify and release a result. */
    public static final String LAB_VERIFY = "hasAnyRole('ADMIN','PATHOLOGIST')";

    public static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private Roles() {
    }
}
