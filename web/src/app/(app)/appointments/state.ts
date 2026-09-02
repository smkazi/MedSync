import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";

/**
 * Booking state, kept out of the action module because `"use server"` files may only export async
 * functions — a constant exported from one arrives as `undefined` at render time.
 */
export type BookingState = FormState & {
  /** The appointment that was created, so the form can confirm it rather than only redirecting. */
  bookedId: string | null;
};

export const EMPTY_BOOKING_STATE: BookingState = { ...EMPTY_FORM_STATE, bookedId: null };

/** Every field the booking form posts. */
export const BOOKING_FIELDS = [
  "mrn",
  "clinicianId",
  "clinicianName",
  "departmentCode",
  "roomCode",
  "startsAt",
  "durationMinutes",
  "priority",
  "reason",
] as const;
