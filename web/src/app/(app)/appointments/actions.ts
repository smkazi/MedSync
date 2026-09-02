"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Appointment, Patient } from "@/lib/types";
import { BOOKING_FIELDS, type BookingState } from "./state";

/**
 * Writes against the appointment book.
 *
 * <p>Two things here are deliberate and worth stating.
 *
 * <p><strong>The slot carries its own instant.</strong> The form never asks for a wall-clock time.
 * A `datetime-local` input yields "2026-09-02T09:00" with no zone, and the browser's zone is not
 * necessarily the platform's — so booking through one would be a timezone bug waiting for the first
 * clinician who travels. Instead the availability endpoint returns each slot as an exact instant and
 * the form submits the one that was chosen, unmodified. There is no arithmetic on a date anywhere in
 * this file.
 *
 * <p><strong>A refusal is shown verbatim.</strong> `AppointmentService.overlapConflict` distinguishes
 * a taken room from a taken clinician, and those are different instructions to the front desk — "pick
 * another room or another slot" versus "pick another slot". Flattening both to "conflict" would throw
 * away the only part of the response that says what to do next.
 */

/** Books an appointment, resolving the MRN to a patient id first. */
export async function bookAppointment(
  _previous: BookingState,
  form: FormData,
): Promise<BookingState> {
  const values = readForm(form, BOOKING_FIELDS);

  if (!values.startsAt) {
    return {
      values,
      fieldErrors: { startsAt: "Choose a slot." },
      error: null,
      done: null,
      bookedId: null,
    };
  }

  // The service needs both the id and the MRN: the id is the reference, the MRN is denormalised onto
  // the appointment so it survives a patient-service outage. Resolving here rather than asking the
  // form for both means a typo is caught as a field error instead of writing a booking against a
  // patient id nobody checked.
  const found = await lookupByMrn(values.mrn);
  if (!found.ok) {
    return {
      values,
      fieldErrors: { mrn: found.error },
      error: null,
      done: null,
      bookedId: null,
    };
  }

  const body = {
    ...withoutBlanks({
      departmentCode: values.departmentCode,
      roomCode: values.roomCode,
      reason: values.reason,
      priority: values.priority,
      // Denormalised onto the appointment on purpose: the book has to name the clinician even if
      // the staff directory is unreachable, and a dash where a person should be is not a clinic list.
      clinicianName: values.clinicianName,
    }),
    patientId: found.data.id,
    patientMrn: found.data.mrn,
    clinicianId: values.clinicianId,
    startsAt: values.startsAt,
    durationMinutes: values.durationMinutes ? Number(values.durationMinutes) : undefined,
  };

  const result = await submit<Appointment>("/appointments", "POST", body);
  if (!result.ok) {
    return { ...refused(values, result), bookedId: null };
  }

  revalidatePath("/appointments");
  revalidatePath(`/patients/${found.data.id}`);
  redirect(`/appointments?booked=${result.data.id}`);
}

/** GET is not a mutation, so it does not go through `submit`; this keeps the error shape uniform. */
async function lookupByMrn(mrn: string) {
  const { api, ApiError } = await import("@/lib/api");
  try {
    return { ok: true as const, data: await api<Patient>(`/patients/by-mrn/${encodeURIComponent(mrn)}`) };
  } catch (caught) {
    const message =
      caught instanceof ApiError && caught.status === 404
        ? `No patient with MRN ${mrn}.`
        : caught instanceof Error
          ? caught.message
          : "Could not look up that MRN.";
    return { ok: false as const, error: message };
  }
}

/**
 * Moves an appointment along its lifecycle.
 *
 * <p>One action for every transition, because they are one endpoint shape and the legal moves are
 * the service's to enforce — `Appointment.canTransitionTo` is the control, and a UI that hid a
 * button would only be a second, drifting copy of it. An illegal move comes back as a 409 with the
 * reason, and that is what gets shown.
 */
export async function advanceAppointment(form: FormData): Promise<void> {
  const id = String(form.get("id") ?? "");
  const step = String(form.get("step") ?? "");
  const back = String(form.get("back") ?? "");
  const allowed = ["check-in", "start", "complete", "no-show"];
  if (!id || !allowed.includes(step)) {
    redirect("/appointments?problem=Unknown+action");
  }

  const result = await submit<Appointment>(`/appointments/${id}/${step}`, "POST");
  finish(result.ok ? null : result.error, id, back);
}

export async function cancelAppointment(form: FormData): Promise<void> {
  const id = String(form.get("id") ?? "");
  const back = String(form.get("back") ?? "");
  const reason = String(form.get("reason") ?? "").trim();
  const result = await submit<void>(`/appointments/${id}`, "DELETE", reason ? { reason } : {});
  finish(result.ok ? null : result.error, id, back);
}

export async function rescheduleAppointment(form: FormData): Promise<void> {
  const id = String(form.get("id") ?? "");
  const back = String(form.get("back") ?? "");
  const startsAt = String(form.get("startsAt") ?? "");
  const durationMinutes = String(form.get("durationMinutes") ?? "");
  if (!startsAt) {
    redirect(`/appointments?problem=Choose+a+slot+to+move+to`);
  }
  const result = await submit<Appointment>(`/appointments/${id}/schedule`, "PUT", {
    startsAt,
    durationMinutes: durationMinutes ? Number(durationMinutes) : undefined,
  });
  finish(result.ok ? null : result.error, id, back);
}

/**
 * Sends the outcome back to the list the user was actually looking at.
 *
 * <p>`back` is the filter query the row was rendered under. Without it, checking somebody in on
 * Thursday's clinic bounced the front desk to today's and they had to navigate back for every single
 * patient — the list is filtered by date, so "return to /appointments" is not returning to where
 * they were.
 *
 * <p>The message travels in the query string, which is safe here for a specific reason rather than
 * by luck: these refusals name rooms, slots and statuses — "Room GF-GEN is already in use at that
 * time", "cannot go from COMPLETED to CHECKED_IN" — and never a patient. The same rule the audit
 * trail follows: no clinical free text in a field that gets logged.
 */
function finish(problem: string | null, id: string, back: string): never {
  revalidatePath("/appointments");
  const params = new URLSearchParams(back);
  // Never let a stale notice from the previous action survive into the next render.
  params.delete("problem");
  params.delete("changed");
  params.delete("booked");
  params.set(problem ? "problem" : "changed", problem ?? id);
  redirect(`/appointments?${params}`);
}
