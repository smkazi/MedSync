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
     * A service account, not a person.
     *
     * <p>Held by work that is triggered by an event rather than a request and therefore has no
     * caller's token to forward — today, notification-service deciding where to send "your report
     * is ready". Deliberately the narrowest role on the platform: it reads a patient's phone
     * number and email address and nothing else, so a leaked service password buys an attacker a
     * contact list rather than a chart.
     *
     * <p>Never granted to a human account, and it is not in {@link #CLINICAL_READ}: a service that
     * could read what {@code reception} can read would make the whole point of a separate role
     * disappear.
     */
    public static final String SERVICE = "SERVICE";

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

    /**
     * Retuning the laboratory's own numbers: reference intervals, interpretive rules, morphology
     * cut-offs.
     *
     * <p>The same membership as {@link #LAB_VERIFY} today, and deliberately a separate name. Both
     * configuration endpoints used to write that SpEL string out by hand, duplicating the constant
     * character for character — which is precisely the failure this class exists to prevent, since
     * a typo inside {@code @PreAuthorize} fails silently either open or closed. Naming it
     * separately also means the next change to who may verify a result does not quietly move who
     * may rewrite what a signed report says.
     */
    public static final String LAB_CONFIG = "hasAnyRole('ADMIN','PATHOLOGIST')";

    /**
     * Who may read a patient's contact details on their own, without the rest of the record.
     *
     * <p>The narrow endpoint exists so that a service which needs to address a message does not
     * have to be given {@link #CLINICAL_READ}, which would hand it demographics, allergies and
     * every appointment. The same line {@link #CHART_READ} draws between "can look a patient up"
     * and "can read their chart", drawn once more a level lower.
     *
     * <p>ADMIN is in because an administrator diagnosing why a message was not delivered needs to
     * see what address it would have gone to.
     */
    /**
     * The casualty board and the in-patient census.
     *
     * <p>Clinical, and deliberately narrower than {@link #CLINICAL_READ}: the front desk books and
     * checks in, and has no business reading a list of who is in casualty with what complaint and
     * how sick they are. That list is a chart in table form.
     *
     * <p>The same membership as {@link #CLINICAL_WRITE} today, and a separate name for the same
     * reason {@code LAB_CONFIG} is separate from {@code LAB_VERIFY} — allocating a bed and writing
     * a note are different acts that happen to share a role list, and a change to one should not
     * silently move the other.
     */
    public static final String BED_MANAGE = "hasAnyRole('ADMIN','DOCTOR','NURSE')";

    public static final String CONTACT_READ = "hasAnyRole('ADMIN','SERVICE')";

    /**
     * Who may ask the platform to send a message.
     *
     * <p>Broad on the clinical side and closed to the laboratory: the front desk tells a patient
     * their appointment moved, a clinician tells them to come in, and neither the bench nor a
     * pathologist has a reason to originate one. The event-driven path does not come through here
     * at all — it is a consumer, and consumers are not authorised, they are wired.
     */
    public static final String NOTIFY_SEND = "hasAnyRole('ADMIN','DOCTOR','NURSE','RECEPTIONIST')";

    /** Who may read the delivery log: what was sent, to which address, and whether it arrived. */
    public static final String NOTIFY_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','RECEPTIONIST')";

    public static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private Roles() {
    }
}
