import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Patient write-form state. Kept out of the action modules: a `"use server"` file may only export
 * async functions, and a constant exported from one arrives as `undefined` at render time.
 */

export type EditState = FormState;
export const EMPTY_EDIT_STATE: EditState = EMPTY_FORM_STATE;

/**
 * The allergy form's state adds one field.
 *
 * <p>`confirming` holds a severity the user has chosen but not yet confirmed. It is the whole
 * reason this form is more than a POST: a LIFE_THREATENING allergy is not a note, it is an
 * instruction to the platform to refuse things later, and recording one by mis-clicking a dropdown
 * should not be possible.
 */
export type AllergyState = FormState & { confirming: string | null };
export const EMPTY_ALLERGY_STATE: AllergyState = { ...EMPTY_FORM_STATE, confirming: null };

export const EDIT_FIELDS = [
  "firstName",
  "lastName",
  "dateOfBirth",
  "sex",
  "bloodGroup",
  "phone",
  "email",
  "addressLine1",
  "addressLine2",
  "city",
  "state",
  "postalCode",
  "country",
  "insuranceProvider",
  "emergencyContactName",
  "emergencyContactPhone",
  "notes",
] as const;

export const ALLERGY_FIELDS = ["substance", "reaction", "severity"] as const;

/** Severities the platform will act on rather than merely display. */
export const SEVERITIES_NEEDING_CONFIRMATION = new Set(["LIFE_THREATENING"]);
