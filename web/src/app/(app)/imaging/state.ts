import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Field names and shared constants for radiology.
 *
 * <p>Out of `actions.ts` because a `"use server"` module may export only async functions: a
 * constant exported from one type-checks, builds, and arrives as `undefined` at render time.
 */

export type ImagingFormState = FormState;
export const EMPTY_IMAGING_STATE: ImagingFormState = EMPTY_FORM_STATE;

/** Ordering, from an encounter chart. */
export const ORDER_FIELDS = [
  "patientId",
  "patientMrn",
  "encounterId",
  "procedureCode",
  "clinicalQuestion",
  "priority",
] as const;

export const SCHEDULE_FIELDS = ["scheduledFor"] as const;

export const REPORT_FIELDS = ["findings", "impression"] as const;

export const AMEND_FIELDS = ["findings", "impression", "reason"] as const;

export const CANCEL_FIELDS = ["reason"] as const;

/**
 * The floor on a clinical question, matching `@Size(min = 20)` on the request.
 *
 * <p>Repeated here so the form can say it before the platform has to refuse it — and the number
 * lives in one place on this side, because a hint that disagrees with the rule is worse than no
 * hint.
 */
export const QUESTION_MIN = 20;

/** The same floor on an amendment's reason, and for the same reason: it has to be a sentence. */
export const AMEND_REASON_MIN = 20;

/** The three priorities radiology recognises, matching `ImagingEnums.Priority`. */
export const IMAGING_PRIORITIES = [
  { value: "ROUTINE", label: "Routine" },
  { value: "URGENT", label: "Urgent" },
  { value: "STAT", label: "STAT" },
] as const;
