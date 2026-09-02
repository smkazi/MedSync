/**
 * Pharmacy field names and vocabulary. Out of `actions.ts` because a `"use server"` module may
 * export only async functions — the rule `patients/new/state.ts` exists to record.
 */

export const PRESCRIBE_FIELDS = [
  "patientId",
  "patientMrn",
  "encounterId",
  "drugCode",
  "dose",
  "frequency",
  "durationDays",
  "quantity",
  "instructions",
  "overrideReason",
] as const;

export const STOCK_FIELDS = ["drugCode", "batchNo", "expiresOn", "quantity"] as const;

export const FORMULARY_FIELDS = [
  "code",
  "name",
  "form",
  "strength",
  "unit",
  "controlled",
  "ingredients",
] as const;

export const INTERACTION_FIELDS = [
  "ingredientA",
  "ingredientB",
  "severity",
  "effect",
  "management",
  "source",
] as const;

/** Fields the platform expects as numbers rather than strings. */
export const NUMBER_FIELDS = ["durationDays", "quantity"] as const;

/**
 * The severity scale, in order.
 *
 * <p>In code because the ordering is what a deployment's refusal threshold is compared against —
 * a configurable list would let somebody add a level between MODERATE and MAJOR that no comparison
 * knows where to put. The pairings graded on it are rows.
 */
export const SEVERITIES = [
  { value: "MINOR", label: "Minor — worth knowing" },
  { value: "MODERATE", label: "Moderate — monitor" },
  { value: "MAJOR", label: "Major — justify in writing" },
  { value: "CONTRAINDICATED", label: "Contraindicated — never together" },
] as const;

export const DOSE_FORMS = [
  { value: "TABLET", label: "Tablet" },
  { value: "CAPSULE", label: "Capsule" },
  { value: "SYRUP", label: "Syrup" },
  { value: "INJECTION", label: "Injection" },
  { value: "DROPS", label: "Drops" },
  { value: "CREAM", label: "Cream" },
  { value: "INHALER", label: "Inhaler" },
] as const;

/** Why a dose was not given. Two values, because they are two different problems. */
export const NOT_GIVEN_REASONS = [
  { value: "REFUSED", label: "Patient declined" },
  { value: "OMITTED", label: "Omitted (nil by mouth, unavailable, off the ward)" },
] as const;

/** How a severity is coloured, so the same word never appears in two different colours. */
export function severityTone(severity: string): "critical" | "warn" | "neutral" {
  if (severity === "CONTRAINDICATED" || severity === "LIFE_THREATENING") {
    return "critical";
  }
  if (severity === "MAJOR" || severity === "SEVERE") {
    return "warn";
  }
  return "neutral";
}
