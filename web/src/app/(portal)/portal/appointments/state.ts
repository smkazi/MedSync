/**
 * Field names for the portal's forms.
 *
 * <p>A plain module rather than a constant in `actions.ts`, because a `"use server"` file may only
 * export async functions — a plain object export from one type-checks, builds, and arrives as
 * `undefined` at runtime. The rule `patients/new/state.ts` exists to record, applied again.
 */
export const BOOKING_FIELDS = ["clinicianId", "departmentCode", "startsAt", "durationMinutes", "reason"] as const;

export const CANCEL_FIELDS = ["appointmentId", "reason"] as const;
