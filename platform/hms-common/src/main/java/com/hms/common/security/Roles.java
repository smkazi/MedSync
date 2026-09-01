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

    /**
     * Everyone who may look up a patient: demographics, contact details, allergies, appointments,
     * lab orders. Broad on purpose - the front desk books, the lab needs to know whose sample it
     * is holding, and an allergy that nobody can see protects nobody.
     *
     * <p>This is NOT the same as reading the chart. See {@link #CHART_READ}.
     */
    public static final String CLINICAL_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','RECEPTIONIST','LAB_TECH','PATHOLOGIST')";

    /**
     * Who may read the clinical record itself: encounters, SOAP notes, vitals, diagnoses.
     *
     * <p>Narrower than {@link #CLINICAL_READ}, and deliberately so. Minimum necessary access is
     * the rule for PHI, and a lab technician running a full blood count has no need for the
     * patient's history, assessment, or plan - the clinical context the order needs travels on the
     * order itself. The front desk has less need still. Pathologists are in: reporting a specimen
     * without the clinical picture is how a diagnosis gets missed.
     *
     * <p>Found by the authorization abuse suite in tests/api, which caught a LAB_TECH token
     * reading a signed encounter note.
     */
    public static final String CHART_READ = "hasAnyRole('ADMIN','DOCTOR','NURSE','PATHOLOGIST')";

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
