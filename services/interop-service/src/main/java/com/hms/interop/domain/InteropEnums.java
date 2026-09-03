package com.hms.interop.domain;

/** The vocabulary of health-information exchange. */
public final class InteropEnums {

    private InteropEnums() {
    }

    /**
     * A consent artefact's life.
     *
     * <p>REQUESTED is a consent asked for and not yet answered — a real state, because the request
     * goes to the patient through a consent manager and the answer comes back later. EXPIRED is
     * derived from {@code expires_at} and also stored, which looks like duplication and is not: a
     * consent that lapsed six months ago must read as expired without anybody running a job, and
     * the stored status is what makes a list query cheap. The service treats a GRANTED row past its
     * expiry as expired regardless, so the two cannot disagree in the direction that matters.
     */
    public enum ConsentStatus {
        REQUESTED, GRANTED, DENIED, REVOKED, EXPIRED
    }

    /**
     * What a consent may cover, in ABDM's own vocabulary.
     *
     * <p>In code, and each value maps to a bundle this service knows how to build. A configurable
     * list would let a deployment grant consent for something no builder can produce — a consent
     * that reads as covering a discharge summary and silently shares nothing, which is worse than
     * a refusal because the patient believes their record moved.
     */
    public enum HiType {
        /** An outpatient visit: the encounter, its vitals, its note and its diagnoses. */
        OP_CONSULTATION,
        /** Laboratory reports, released ones only. */
        DIAGNOSTIC_REPORT,
        /** Medication orders. */
        PRESCRIPTION,
        /** An in-patient stay's summary. Not built yet, and named in the README's gaps. */
        DISCHARGE_SUMMARY,
        /** Immunisations. Not built yet — the platform records none. */
        IMMUNIZATION_RECORD,
        /** A scanned or attached document. Not built yet — there is no document store. */
        HEALTH_DOCUMENT_RECORD,
        /** Patient-recorded vitals and observations. Not built yet — there is no portal. */
        WELLNESS_RECORD
    }

    /**
     * Why somebody is asking.
     *
     * <p>The codes are ABDM's purpose-of-use list, kept because a disclosure log that cannot say
     * *why* the data moved answers half the question an investigation asks.
     */
    public enum PurposeCode {
        CARE_MANAGEMENT, BREAK_THE_GLASS, PUBLIC_HEALTH, PAYMENT, RESEARCH, SELF_REQUESTED
    }

    /**
     * What kind of release a disclosure was.
     *
     * <p>The distinction is load-bearing rather than descriptive. A CONSENTED_SHARE goes to a third
     * party and cannot exist without a consent artefact — the database refuses it. A PATIENT_EXPORT
     * hands a person their own record and has no consent behind it, because asking somebody to
     * consent to receiving their own data is a formality that would teach everybody to click
     * through consent screens. A CARE_SUMMARY travels with a referral the patient is present for.
     */
    public enum DisclosureKind {
        CONSENTED_SHARE, PATIENT_EXPORT, CARE_SUMMARY
    }
}
