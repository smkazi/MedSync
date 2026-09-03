/**
 * Consent and disclosure field names and vocabulary. Out of `actions.ts` because a `"use server"`
 * module may export only async functions — the rule `patients/new/state.ts` exists to record.
 */

export const CONSENT_FIELDS = [
  "patientId",
  "patientMrn",
  "requester",
  "requesterId",
  "purposeCode",
  "purposeText",
  "hiTypes",
  "coversFrom",
  "coversTo",
  "expiresAt",
  "artefactId",
] as const;

export const SHARE_FIELDS = ["artefactId", "hiType", "recordId"] as const;

/**
 * What a consent may cover.
 *
 * <p>The first three are the ones this platform can actually build a bundle for. The rest are in
 * ABDM's vocabulary and are offered with a warning rather than hidden: a consent may legitimately
 * cover a discharge summary the platform cannot yet produce, and the sharing screen says so at the
 * point somebody tries rather than pretending the consent is wrong.
 */
export const HI_TYPES = [
  { value: "OP_CONSULTATION", label: "Outpatient consultation" },
  { value: "DIAGNOSTIC_REPORT", label: "Laboratory report" },
  { value: "PRESCRIPTION", label: "Prescription" },
  { value: "DISCHARGE_SUMMARY", label: "Discharge summary (not built)" },
  { value: "IMMUNIZATION_RECORD", label: "Immunisation record (not built)" },
  { value: "HEALTH_DOCUMENT_RECORD", label: "Health document (not built)" },
  { value: "WELLNESS_RECORD", label: "Wellness record (not built)" },
] as const;

/** The three types a bundle can actually be built for today. */
export const BUILDABLE_HI_TYPES = HI_TYPES.slice(0, 3);

export const PURPOSES = [
  { value: "CARE_MANAGEMENT", label: "Care management" },
  { value: "BREAK_THE_GLASS", label: "Emergency access (break the glass)" },
  { value: "PUBLIC_HEALTH", label: "Public health" },
  { value: "PAYMENT", label: "Payment or claim" },
  { value: "RESEARCH", label: "Research" },
  { value: "SELF_REQUESTED", label: "The patient asked for it themselves" },
] as const;
