import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Field names and shared constants for the laboratory write layer.
 *
 * <p>Kept out of `actions.ts` because a `"use server"` module may export only async functions: a
 * constant exported from one type-checks, builds, and arrives as `undefined` at render time. The
 * mirror of the other rule in `form.ts` — a `"use client"` module may not reach `next/headers`.
 */

export type LabFormState = FormState;
export const EMPTY_LAB_STATE: LabFormState = EMPTY_FORM_STATE;

/** Ordering, from an encounter chart. `testCodes` is a repeated checkbox name, read with getAll. */
export const ORDER_FIELDS = [
  "patientId",
  "patientMrn",
  "patientSex",
  "encounterId",
  "priority",
  "department",
  "clinicalNotes",
] as const;

/**
 * The three rows of a hand-entered result, posted as repeated names.
 *
 * <p>Repeated rather than indexed (`value.0`, `value.1`) so a panel of any width posts without the
 * form knowing how many rows it has, and so the whole thing still submits with JavaScript off.
 * `FormData.getAll` keeps them in document order, which is what pairs a value with its parameter.
 */
export const RESULT_ROW_FIELDS = ["parameter", "value", "unit"] as const;

export const PRIORITIES = [
  { value: "ROUTINE", label: "Routine" },
  { value: "URGENT", label: "Urgent" },
  { value: "STAT", label: "STAT" },
] as const;

/**
 * Specimen types the collection form offers.
 *
 * <p>A free-text field, capped at 32 characters by the service, so the list is a convenience and
 * not a vocabulary — which is why it lives here rather than in a table. Where a laboratory needs
 * its own list, `lab_test_catalog.specimen_type` is the configured one and the service accepts
 * whatever it is given.
 */
export const SPECIMEN_TYPES = [
  { value: "WHOLE_BLOOD", label: "Whole blood (EDTA)" },
  { value: "SERUM", label: "Serum" },
  { value: "PLASMA", label: "Plasma" },
  { value: "URINE", label: "Urine" },
  { value: "SWAB", label: "Swab" },
  { value: "CSF", label: "CSF" },
] as const;
