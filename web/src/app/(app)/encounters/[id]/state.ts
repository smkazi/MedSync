import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Charting form state. Kept out of the action module: a `"use server"` file may only export async
 * functions, and a constant exported from one arrives as `undefined` at render time.
 */
export type NoteState = FormState;
export const EMPTY_NOTE_STATE: NoteState = EMPTY_FORM_STATE;

export const NOTE_FIELDS = ["subjective", "objective", "assessment", "plan"] as const;

export const VITALS_FIELDS = [
  "heartRate",
  "systolicBp",
  "diastolicBp",
  "respiratoryRate",
  "temperatureC",
  "oxygenSaturation",
  "weightKg",
  "heightCm",
  "painScore",
  "consciousness",
  "onSupplementalOxygen",
] as const;

/** Vitals the service takes as numbers; everything else on the form is a string. */
export const NUMERIC_VITALS = new Set<string>([
  "heartRate",
  "systolicBp",
  "diastolicBp",
  "respiratoryRate",
  "temperatureC",
  "oxygenSaturation",
  "weightKg",
  "heightCm",
  "painScore",
]);

/**
 * How a goal can end.
 *
 * <p>OPEN is not offered: this list is what somebody picks when they are recording an outcome, and
 * "still open" is the absence of one. NOT_MET and ABANDONED are separate because "we tried and it
 * did not happen" and "we stopped trying, and here is why" are different facts about an admission,
 * and both need a note the service refuses to do without.
 */
export const GOAL_OUTCOMES = [
  { value: "MET", label: "Met" },
  { value: "NOT_MET", label: "Not met" },
  { value: "ABANDONED", label: "Abandoned" },
] as const;
