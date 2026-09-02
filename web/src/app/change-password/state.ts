import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Change-password form state. Kept out of the action module: a `"use server"` file may only export
 * async functions, and a constant exported from one arrives as `undefined` at render time.
 */
export type PasswordState = FormState;
export const EMPTY_PASSWORD_STATE: PasswordState = EMPTY_FORM_STATE;

export const PASSWORD_FIELDS = ["currentPassword", "newPassword", "confirmPassword"] as const;
