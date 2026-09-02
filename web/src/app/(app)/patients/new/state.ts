import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";
import type { PatientSummary } from "@/lib/types";

/**
 * The registration form's state, kept out of the action module on purpose.
 *
 * <p>A `"use server"` file may only export async functions: everything in it becomes a callable
 * server reference, and a plain object export does not survive the transform. It does not fail the
 * build either — `EMPTY_REGISTER_STATE` imported from the action module type-checked, compiled, and
 * then arrived as `undefined` at render time, so the first thing the page did was
 * `Object.keys(undefined)`. The browser showed "This page couldn't load" and the server logged a
 * `TypeError` with no file in the stack. Types and constants live here instead, where they are
 * ordinary module exports.
 */

/**
 * Registration adds one field to the shared form state.
 *
 * <p>`duplicates` is why this screen is more than a POST: the service's 409 carries candidate
 * charts, and they are the entire point of that response.
 */
export type RegisterState = FormState & {
  /**
   * Charts that look like the same person, from the service's 409. Present means the registration
   * was refused pending a decision — not that it failed.
   */
  duplicates: PatientSummary[] | null;
};

export const EMPTY_REGISTER_STATE: RegisterState = { ...EMPTY_FORM_STATE, duplicates: null };
