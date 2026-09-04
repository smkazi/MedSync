import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Field names and shared constants for the immunisation register.
 *
 * <p>Out of `actions.ts` because a `"use server"` module may export only async functions: a
 * constant exported from one type-checks, builds, and arrives as `undefined` at render time. The
 * rule `patients/new/state.ts` exists to record.
 */

export type ImmunisationFormState = FormState;
export const EMPTY_IMMUNISATION_STATE: ImmunisationFormState = EMPTY_FORM_STATE;

/**
 * A dose given here. `lotNo` is typed rather than picked from a list, deliberately: the person
 * recording this is holding the vial, and the label on it is the evidence. A dropdown of lots the
 * platform believes it has would let a vial that was never received be recorded as given.
 */
export const RECORD_DOSE_FIELDS = [
  "patientId",
  "patientMrn",
  "encounterId",
  "productCode",
  "lotNo",
  "givenOn",
  "site",
] as const;

/**
 * A dose given somewhere else. No lot number anywhere in this list, and that is the point: the
 * failure this endpoint prevents is somebody typing a card dose in as if given here with an
 * invented lot, which puts fabricated evidence in the one column a recall reads.
 */
export const HISTORICAL_DOSE_FIELDS = [
  "patientId",
  "patientMrn",
  "productCode",
  "givenOn",
  "dateEstimated",
  "source",
  "evidence",
] as const;

export const EXEMPTION_FIELDS = [
  "patientId",
  "patientMrn",
  "antigenCode",
  "kind",
  "reason",
  "expiresOn",
] as const;

export const AEFI_FIELDS = ["onsetOn", "description", "seriousness", "outcome"] as const;

export const RECEIVE_LOT_FIELDS = [
  "productCode",
  "lotNo",
  "expiresOn",
  "quantity",
  "vvmStage",
] as const;

export const WITHDRAW_LOT_FIELDS = ["reason"] as const;

/** Where a dose was given. A short controlled list, because a free-text site is unsearchable. */
export const SITES = [
  { value: "LEFT_DELTOID", label: "Left deltoid" },
  { value: "RIGHT_DELTOID", label: "Right deltoid" },
  { value: "LEFT_THIGH", label: "Left thigh (anterolateral)" },
  { value: "RIGHT_THIGH", label: "Right thigh (anterolateral)" },
  { value: "ORAL", label: "Oral" },
  { value: "INTRANASAL", label: "Intranasal" },
] as const;

/**
 * The two grades of historical evidence, and their labels say which is which.
 *
 * <p>`ADMINISTERED_HERE` is deliberately absent: this list feeds the historical form, and the
 * service refuses that value there by name. Offering it would be offering a way to record a dose
 * as given here with no lot number.
 */
export const HISTORICAL_SOURCES = [
  { value: "HISTORICAL_DOCUMENTED", label: "Documented — I am holding the card" },
  { value: "HISTORICAL_PARENT_REPORTED", label: "Reported — the parent says so" },
] as const;

export const EXEMPTION_KINDS = [
  { value: "MEDICAL", label: "Medical contraindication" },
  { value: "REFUSED", label: "Refused" },
] as const;

export const SERIOUSNESS = [
  { value: "NON_SERIOUS", label: "Non-serious" },
  { value: "SERIOUS", label: "Serious" },
] as const;

export const OUTCOMES = [
  { value: "RECOVERED", label: "Recovered" },
  { value: "RECOVERING", label: "Recovering" },
  { value: "NOT_RECOVERED", label: "Not recovered" },
  { value: "RECOVERED_WITH_SEQUELAE", label: "Recovered with sequelae" },
  { value: "DIED", label: "Died" },
  { value: "UNKNOWN", label: "Unknown" },
] as const;
