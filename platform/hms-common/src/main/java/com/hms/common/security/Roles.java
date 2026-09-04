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

    /**
     * The pharmacy.
     *
     * <p>A pharmacist reads a prescription and the patient's allergy list, and dispenses against
     * them. They do not read the chart: {@link #CHART_READ} draws that line for the laboratory
     * already, and the same reasoning applies here — the clinical context a dispense needs travels
     * on the prescription, and a pharmacist checking an interaction has no need for the history,
     * assessment and plan.
     */
    public static final String PHARMACIST = "PHARMACIST";

    /** Dispensing, stock and the formulary: the pharmacy's own work. */
    public static final String PHARMACY_WRITE = "hasAnyRole('ADMIN','PHARMACIST')";

    /**
     * Who may write a prescription.
     *
     * <p>Deliberately narrower than {@link #CLINICAL_WRITE}, which includes nurses: prescribing is
     * a prescriber's act, and a platform that let anyone who may record a temperature also order a
     * medication would be wrong in a way no amount of interaction checking repairs. Nurse
     * prescribing exists in some jurisdictions and is a role grant when it does, not a widening of
     * this one.
     */
    public static final String PRESCRIBE = "hasAnyRole('ADMIN','DOCTOR')";

    /**
     * Who may read a medication order: the prescriber, the ward, and the pharmacy.
     *
     * <p>Wider than {@link #PRESCRIBE} because the people who give a medicine are not the people
     * who ordered it, and a nurse who cannot read the prescription cannot safely administer it.
     */
    public static final String MEDICATION_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','PHARMACIST')";

    /**
     * Who may record that a dose was given.
     *
     * <p>Nurses and doctors, not the pharmacy: dispensing hands the medicine over, administering
     * puts it into a patient, and they are different acts by different people at different times.
     * The whole point of a closed loop is that the second one is witnessed at the bedside.
     */
    public static final String MEDICATION_ADMINISTER = "hasAnyRole('ADMIN','DOCTOR','NURSE')";

    /**
     * Who may read a patient's allergy list on its own, without the rest of the record.
     *
     * <p>The same narrowing as {@link #CONTACT_READ}, one level up: a pharmacist about to hand over
     * a medicine must be able to see what the patient reacts to, and must not thereby acquire
     * demographics, appointments and every lab order. An allergy that nobody can see protects
     * nobody; an allergy list that comes bundled with a chart is a chart handed to the pharmacy.
     */
    public static final String ALLERGY_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','PHARMACIST')";

    /**
     * The cashier's desk.
     *
     * <p>Named CASHIER rather than BILLING because a role names a person's job and not a module.
     * The acts are {@link #BILLING_READ} and {@link #BILLING_WRITE}.
     */
    public static final String CASHIER = "CASHIER";

    /**
     * Who may read the money: invoices, payments, claims, the day book and the charge catalogue.
     *
     * <p>Clinicians are in, and deliberately: a doctor asked "what will this cost?" at the bedside
     * needs an answer, and a platform that made them walk to the billing desk to read a number
     * would be routed around within a week. What they may not do is {@link #BILLING_WRITE}.
     *
     * <p>The laboratory and the pharmacy are out. Neither raises an invoice nor takes money — their
     * charges arrive in billing as events, with no token and no screen — and a bench technician who
     * could read what every patient has been billed would be reading a financial record for no
     * reason anybody can state.
     */
    public static final String BILLING_READ =
            "hasAnyRole('ADMIN','CASHIER','DOCTOR','NURSE','RECEPTIONIST')";

    /**
     * Who may raise an invoice, take a payment, or move a claim.
     *
     * <p>Narrow on purpose, and the narrowing is the oldest financial control there is: the person
     * who decides what is owed must not be the person who records that it was paid. A clinician
     * decides what was done; a cashier records what was collected. So DOCTOR and NURSE are in
     * {@link #BILLING_READ} and are refused here — {@code dr.rao} posting a payment is a 403, and
     * the authorization suite asserts it.
     *
     * <p>RECEPTIONIST is out as well. The front desk in a small clinic often does take cash, and
     * when a deployment works that way the answer is to grant those people CASHIER, not to widen
     * this constant until it stops meaning anything.
     */
    public static final String BILLING_WRITE = "hasAnyRole('ADMIN','CASHIER')";

    /**
     * Who may change what things cost: the charge catalogue, tax rates, payers and their tariffs.
     *
     * <p>Separate from {@link #BILLING_WRITE} and narrower, for the reason {@code LAB_CONFIG} is
     * separate from {@code LAB_VERIFY}: taking a payment and rewriting a price list are different
     * acts that happen to be done at the same desk. A cashier who could retune the price of a
     * procedure could discount it to zero and then record it as paid in full, and no reconciliation
     * downstream would notice.
     */
    public static final String BILLING_CONFIG = "hasRole('ADMIN')";

    /**
     * Who may put a name to an MRN, and nothing else.
     *
     * <p>The third narrowing of the same kind as {@link #CONTACT_READ} and {@link #ALLERGY_READ}.
     * A cashier raising an invoice has to identify the person being billed — a bill against the
     * wrong patient is a bill somebody else is asked to pay — and must not thereby acquire the
     * register, which carries date of birth, phone number and a critical-allergy marker.
     *
     * <p>So the endpoint behind this answers an id, an MRN and a name, and the role list is only
     * the people who have no other way to look a patient up: everybody in {@link #CLINICAL_READ}
     * already has the full search and does not need this one.
     */
    public static final String PATIENT_IDENTIFY = "hasAnyRole('ADMIN','CASHIER')";

    /**
     * Who may list a birth cohort: the children born between two dates, with their birthdays.
     *
     * <p>The fourth narrowing of the {@link #CONTACT_READ} / {@link #ALLERGY_READ} /
     * {@link #PATIENT_IDENTIFY} kind, and the only one that <strong>widens</strong> what an earlier
     * one withheld — so it gets its own argument rather than reusing a role list. Read
     * {@link #PATIENT_IDENTIFY} above: it names date of birth as precisely the field that endpoint
     * exists not to hand over. Granting this through that constant would have widened by three
     * words what a paragraph there spends narrowing.
     *
     * <p>A birthday is unavoidable here, because it is the whole question. "Which children are due
     * their measles dose" is arithmetic on a date of birth, and an immunisation clinic calling a
     * cohort in needs the birthday to know what is due and the name to know who to ask for. So the
     * endpoint answers an id, an MRN, a name and a date of birth, and nothing else — no phone
     * number, no address, no allergy marker, no chart.
     *
     * <p>The role list is the jobs that telephone a family: an administrator, a doctor, a nurse.
     * Deliberately <em>not</em> everybody who holds {@link #IMMUNISATION_READ} — a pharmacist keeps
     * the cold chain and reads the register, and does not run the calling list — and deliberately
     * not the cashier who holds {@link #PATIENT_IDENTIFY}, who has no reason to ask which children
     * were born in a fortnight.
     */
    public static final String PATIENT_COHORT_READ = "hasAnyRole('ADMIN','DOCTOR','NURSE')";

    /**
     * Who may write a national health identifier onto a patient record.
     *
     * <p>The front desk and an administrator, and no clinician: linking an ABHA is a registration
     * act done with the patient's card or their phone in front of you, not something decided while
     * reading a chart. Deliberately narrower than the read on the same endpoint, which a doctor
     * needs when a referral quotes a number.
     */
    public static final String ABHA_LINK = "hasAnyRole('ADMIN','RECEPTIONIST')";

    /**
     * Who may read a patient's consent register and the record of what has been disclosed.
     *
     * <p>Clinical, and wide on the read side on purpose: a clinician about to refer a patient needs
     * to know whether there is a consent covering the referral, and "ask an administrator" is how a
     * platform teaches people to send a fax instead. The laboratory and the pharmacy are out —
     * neither refers, and neither has a reason to know who has asked for somebody's record.
     */
    public static final String CONSENT_READ = "hasAnyRole('ADMIN','DOCTOR','NURSE','RECEPTIONIST')";

    /**
     * Who may record a consent decision: request one, mark it granted or refused, revoke it.
     *
     * <p>The front desk and an administrator, not a clinician. A consent decision is the patient's,
     * taken with a consent manager or on paper in front of them, and recorded by whoever is
     * standing with them; a clinician recording a consent for records they are about to send would
     * be authorising their own access.
     */
    public static final String CONSENT_WRITE = "hasAnyRole('ADMIN','RECEPTIONIST')";

    /**
     * Who may cause information to leave the building under a consent.
     *
     * <p>Deliberately narrow, and separate from every read: a share is a disclosure to a third
     * party, and the consent check behind it (which cannot be bypassed) protects the patient rather
     * than the operator. A doctor is in because a referral is a clinical act; the front desk is
     * not, because deciding that a record is the one to send is not a clerical judgement.
     */
    public static final String HEALTH_INFORMATION_SHARE = "hasAnyRole('ADMIN','DOCTOR')";

    /**
     * Who may export a patient's whole record.
     *
     * <p>Administrators alone, until there is a portal where a patient can do it themselves. It is
     * the single most sensitive operation the platform performs — every encounter, every result,
     * every prescription, in one file — and the audit line it writes is loud for that reason.
     */
    public static final String EHI_EXPORT = "hasRole('ADMIN')";

    public static final String ADMIN_ONLY = "hasRole('ADMIN')";

    /**
     * A patient, signed in to the portal, reading their own record.
     *
     * <p>The only role on this platform that answers two questions rather than one. Every staff
     * role answers "what may this person do", and the record they may open follows from it. This
     * one also answers "whose record is this", and the answer is carried by the {@code patient_id}
     * claim on the token rather than by anything the caller sends. Authority without that claim is
     * no authority at all: a PATIENT token that names no patient is refused by every portal
     * endpoint for want of a subject.
     */
    public static final String PATIENT = "PATIENT";

    /**
     * The portal: a patient's own appointments, released reports, invoices and messages.
     *
     * <p>{@code hasRole('PATIENT')} and nothing else, and the exclusion of ADMIN is the deliberate
     * part. Every other constant in this file carries ADMIN, because an administrator is expected
     * to be able to do the job of any desk in the building. Not here: these endpoints answer "the
     * signed-in patient's own record", so an administrator reaching one would be asking for the
     * record of a patient the platform believes them to be. There is no such patient, so the honest
     * answer is 403 rather than an empty list that reads like "you have no appointments".
     *
     * <p>The staff-facing view of the same data already exists and is what an administrator should
     * use: {@code /appointments}, {@code /lab/orders}, {@code /invoices}. A portal is a second door
     * to one person's record, not a second API for the hospital.
     */
    public static final String PORTAL = "hasRole('PATIENT')";

    /**
     * Who may enrol a patient in the portal, reset their access, or take it away.
     *
     * <p>The front desk, because enrolment happens face to face: somebody has to satisfy themselves
     * that the person asking for access to a record is the person the record is about, and no
     * endpoint can do that. It is the same list as {@link #ABHA_LINK} and for the same reason.
     *
     * <p>Deliberately not a clinician. A doctor who could mint a portal account against any patient
     * id could mint one against their own record's neighbour and read it at home, with the audit
     * trail naming a routine enrolment. Handing out credentials is an administrative act and this
     * platform keeps it at the desk that already does identity checks.
     */
    public static final String PORTAL_ENROL = "hasAnyRole('ADMIN','RECEPTIONIST')";

    /**
     * Where a room is: its name, its floor and the directions to it.
     *
     * <p>{@code isAuthenticated()}, and that is not a gap. This is the only constant in this file
     * that names no role, because the thing behind it is not confidential in any sense the rest of
     * this file is about: it is the sign screwed to the wall outside the room, and the corridor
     * display two doors down is reachable with no session at all. What it is <em>not</em> is the
     * room read — capacity, dimensions, whether it is bookable, the census of what is in it — which
     * stays on {@code CLINICAL_READ}.
     *
     * <p>It is this wide because of the portal. A patient looking at their own appointment needs to
     * be told where to go, and that is the single most useful line on the screen; the alternative
     * was a portal that renders "Ground Floor" as blank because the patient's token could not ask.
     * Naming every role instead would have produced a nine-role list that means "everybody" while
     * looking like it means something.
     */
    public static final String WAYFINDING = "isAuthenticated()";

    /**
     * Who may join an encounter's care team by recording a reason — break-glass.
     *
     * <p>No new role, deliberately. Obtaining cover for somebody else's patient is part of a
     * clinician's job, not a job of its own, and a "break-glass" role would be one more thing to
     * grant, forget to revoke, and find in an audit two years later. The same two roles the
     * care-team narrowing applies to, which is the point: administrators and the service lines are
     * not narrowed, so they have no glass to break.
     */
    public static final String CARE_TEAM_JOIN = "hasAnyRole('DOCTOR','NURSE')";

    /**
     * The radiographer: positions the patient, runs the modality, sends the images.
     *
     * <p>A separate role from {@link #RADIOLOGIST} for the reason {@code LAB_TECH} is separate from
     * {@code PATHOLOGIST}: the person who produced the images must not be the person who signs off
     * what they show. Same shape, same argument, one department along.
     */
    public static final String RADIOGRAPHER = "RADIOGRAPHER";

    /**
     * The radiologist: reads the study and signs the report other clinicians treat from.
     *
     * <p>Not folded into {@code PATHOLOGIST} although both sign a diagnostic report. They are
     * different people with different registrations, and one role for both would let either sign
     * the other's work — the blurring {@link #CASHIER} refused for billing.
     */
    public static final String RADIOLOGIST = "RADIOLOGIST";

    /**
     * Acquisition: the modality worklist, and registering the studies that come back.
     *
     * <p>Deliberately not {@code CLINICAL_WRITE}. Scheduling a scan and filing what came off the
     * scanner is the radiography room's work, and a ward nurse has no modality to run — while a
     * radiographer needs the worklist and needs it without a chart write.
     */
    public static final String IMAGING_ACQUIRE = "hasAnyRole('ADMIN','RADIOGRAPHER')";

    /**
     * Reporting: writing the findings, and signing them.
     *
     * <p>Signing <em>is</em> release, as it is in the laboratory: there is no second step, and the
     * moment a report is signed it is what somebody treats from. So this is the narrowest role list
     * in the module, and the abuse-case suite asserts that a radiographer never reaches it.
     */
    public static final String IMAGING_REPORT = "hasAnyRole('ADMIN','RADIOLOGIST')";

    /**
     * Who may read imaging: the ordering clinician, the ward, and the department.
     *
     * <p>Wider than either act above, and it has to be — an image nobody treating the patient can
     * see is an image that was taken for nothing. The care-relationship narrowing then applies on
     * top for the clinical roles, exactly as it does to a laboratory order: this list says which
     * jobs may look at radiology at all, and {@code CareRelationshipClient} says whose patients.
     */
    public static final String IMAGING_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','RADIOLOGIST','RADIOGRAPHER')";

    /**
     * Giving a vaccine, and recording one that was given elsewhere.
     *
     * <p><strong>No new role.</strong> A nurse gives vaccines, and in an immunisation clinic a
     * nurse gives nearly all of them — so this is the same membership as {@link #CLINICAL_WRITE}
     * and it is a separate constant for the reason {@code BED_MANAGE} is separate from it:
     * vaccinating and charting are different acts that happen to share a role list today, and a
     * later decision about either must not silently move the other.
     *
     * <p>It also covers recording a dose given somewhere else, which is deliberate rather than
     * overlooked — reading a card and writing down what it says is the same clinical judgement as
     * giving the dose, and a separate role for it would mean the person holding the card has to
     * find somebody else to type it.
     */
    public static final String IMMUNISE = "hasAnyRole('ADMIN','DOCTOR','NURSE')";

    /**
     * Reading the register: a patient's doses, what they are due, and the department's cohort list.
     *
     * <p>Wider than {@link #IMMUNISE} and narrower than everybody. A pharmacist is here because a
     * vaccine is a drug they keep the cold chain for; the front desk is not, because an
     * immunisation history is a chart. The care-relationship narrowing then applies on top for the
     * clinical roles, exactly as it does to a laboratory order — with one deliberate exception the
     * service documents: the cohort due list is not narrowed per row, because calling a birth
     * cohort in for their vaccinations is inherently cross-patient work.
     */
    public static final String IMMUNISATION_READ =
            "hasAnyRole('ADMIN','DOCTOR','NURSE','PHARMACIST')";

    /**
     * The vaccine catalogue and the schedule: what exists, and when it is due.
     *
     * <p>Administrator only, and it is the tightest gate in the module because it is the one that
     * changes what every other answer means. Editing a schedule row moves the due date for every
     * child in the district at once, and adding an antigen to a product retrospectively changes
     * what past doses are counted as covering.
     */
    public static final String IMMUNISATION_CONFIG = "hasRole('ADMIN')";

    /**
     * Vaccine stock: receiving a lot, and taking one out of use.
     *
     * <p>The pharmacy keeps the cold chain, so the pharmacist is here; a nurse is here too because
     * the person who opens the fridge at eight in the morning is the person who finds the vial
     * monitor has turned, and a withdrawal that needs an administrator would not happen that
     * morning. Not {@link #IMMUNISE}: giving a dose and managing the stock it came from are
     * different jobs in a large clinic and the same person in a small one.
     */
    public static final String VACCINE_STOCK = "hasAnyRole('ADMIN','PHARMACIST','NURSE')";

    private Roles() {
    }
}
