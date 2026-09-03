"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Appointment } from "@/lib/types";
import { BOOKING_FIELDS, CANCEL_FIELDS } from "./state";

/**
 * Self-booking, and cancelling.
 *
 * <p>Neither action sends a patient id, and there is nowhere in either request to put one: the
 * platform reads it from the session's token. That is the whole design of `/portal`, and it means
 * these two functions cannot be made to act on somebody else's record by editing the page's HTML.
 *
 * <p>No validation is reimplemented here. `PortalBookingRequest` carries the constraints — the
 * clinician and department are required, the duration is `@Min(5) @Max(240)` — and the booking
 * horizon, the clinician's blackouts and the room exclusion constraint are all the platform's.
 * What comes back on a refusal is the platform's own sentence, which for a taken slot tells the
 * patient to pick another and for a horizon tells them to telephone the department.
 */
export async function bookAppointment(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, BOOKING_FIELDS);
  const body = withoutBlanks(values);
  // The browser's datetime-local gives "2026-09-10T10:30" with no zone; the platform wants an
  // instant. Interpreted in the browser's own zone, which is the clinic's zone for anybody
  // standing in the building and the patient's own otherwise — and either way it is the time they
  // just typed, which is what they meant.
  if (typeof body.startsAt === "string") {
    body.startsAt = new Date(body.startsAt).toISOString();
  }
  if (typeof body.durationMinutes === "string") {
    body.durationMinutes = Number(body.durationMinutes);
  }

  const result = await submit<Appointment>("/portal/appointments", "POST", body);
  if (!result.ok) return refused(values, result);

  revalidatePath("/portal/appointments");
  revalidatePath("/portal");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: "Booked. It is on your appointments list, and the hospital will confirm the room.",
  };
}

/**
 * Cancels one appointment from its row.
 *
 * <p>A redirect rather than form state, following the row actions in the clinical appointment book:
 * the row this button lives in disappears from the list once the appointment is cancelled, so state
 * returned into it would unmount with it and the patient would be told nothing. The outcome comes
 * back as a query parameter the page renders instead.
 */
export async function cancelAppointment(form: FormData): Promise<void> {
  const values = readForm(form, CANCEL_FIELDS);
  const result = await submit<void>(
    `/portal/appointments/${values.appointmentId}/cancel`,
    "POST",
    values.reason ? { reason: values.reason } : {},
  );
  revalidatePath("/portal/appointments");
  revalidatePath("/portal");
  redirect(
    result.ok
      ? "/portal/appointments?done=Your+appointment+has+been+cancelled."
      : `/portal/appointments?problem=${encodeURIComponent(result.error)}`,
  );
}
