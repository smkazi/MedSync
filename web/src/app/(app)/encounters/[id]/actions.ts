"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { CarePlan, ClinicalNote, Diagnosis, Encounter, Vitals } from "@/lib/types";
import { NOTE_FIELDS, NUMERIC_VITALS, VITALS_FIELDS, type NoteState } from "./state";

/**
 * Writes against one encounter.
 *
 * <p>The interesting one is the note. `PUT /encounters/{id}/note` does three different things
 * depending on state the service owns: it creates revision 1, or edits the current revision in
 * place while it is unsigned, or — once a revision is signed — creates an **amendment**, a new
 * revision carrying `amendsId`. None of that is decided here. The screen's job is to say which of
 * the three is about to happen, because "you are editing a draft" and "you are amending a signed
 * clinical note" are very different acts and the button looks identical.
 */

export async function writeNote(_previous: NoteState, form: FormData): Promise<NoteState> {
  const id = String(form.get("encounterId") ?? "");
  const values = readForm(form, NOTE_FIELDS);

  const result = await submit<ClinicalNote>(`/encounters/${id}/note`, "PUT", values);
  if (!result.ok) {
    // A refusal stays on the form. Four paragraphs of typed note text is the one thing that must
    // not be lost to a redirect, which is why this action carries state at all.
    return refused(values, result);
  }
  // Success redirects like every other write on this screen, so there is exactly one place a
  // success message can appear. Returning one here instead put two of them on the page at once:
  // a stale "Vitals recorded." from the URL sitting above a fresh "Saved. Revision 1, unsigned."
  return finish(
    id,
    null,
    result.data.signed
      ? `Saved as revision ${result.data.revision}.`
      : `Saved. Revision ${result.data.revision}, unsigned.`,
  );
}

export async function signNote(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const result = await submit<ClinicalNote>(`/encounters/${id}/note/sign`, "POST");
  finish(id, result.ok ? null : result.error, result.ok ? "Note signed." : null);
}

export async function recordVitals(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const values = readForm(form, VITALS_FIELDS);

  // The service takes numbers, and a blank field must be absent rather than sent as "" or 0 —
  // an unrecorded observation is not the same as a recorded zero, least of all for a pain score.
  const body: Record<string, unknown> = {};
  for (const [field, value] of Object.entries(withoutBlanks(values))) {
    body[field] = NUMERIC_VITALS.has(field)
      ? Number(value)
      // The oxygen flag is a checkbox, so it arrives as "true"/"false" and the service wants a
      // boolean. It is worth two points on NEWS2, and "false" is emphatically not false.
      : value === "true" || value === "false"
        ? value === "true"
        : value;
  }
  // "On air" alone is not an observation: the checkbox always posts something, so counting it
  // would let an empty form through as a set of vitals with a NEWS2 of 0 beside it.
  const measured = Object.keys(body).filter((field) => field !== "onSupplementalOxygen");
  if (measured.length === 0) {
    finish(id, "Enter at least one observation before recording vitals.", null);
  }

  const result = await submit<Vitals>(`/encounters/${id}/vitals`, "POST", body);
  finish(id, result.ok ? null : result.error, result.ok ? "Vitals recorded." : null);
}

export async function addDiagnosis(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const values = readForm(form, ["icd10Code", "description", "category"] as const);
  const result = await submit<Diagnosis>(`/encounters/${id}/diagnoses`, "POST", {
    icd10Code: values.icd10Code,
    description: values.description,
    ...(values.category ? { category: values.category } : {}),
  });
  finish(id, result.ok ? null : result.error, result.ok ? `Added ${values.icd10Code}.` : null);
}

export async function closeEncounter(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const result = await submit<Encounter>(`/encounters/${id}/close`, "POST");
  // The service refuses to close over an unsigned note, and its message says so precisely
  // ("Revision 2 is unsigned; sign the note before closing"). That is the instruction to show.
  finish(id, result.ok ? null : result.error, result.ok ? "Encounter closed." : null);
}

/**
 * Opens an encounter for an appointment.
 *
 * <p>Lives here rather than beside the appointment actions because the encounter is what it
 * creates, and the redirect goes to the new chart.
 */
export async function openEncounter(form: FormData): Promise<void> {
  const body = {
    appointmentId: String(form.get("appointmentId") ?? ""),
    patientId: String(form.get("patientId") ?? ""),
    patientMrn: String(form.get("patientMrn") ?? ""),
    clinicianId: String(form.get("clinicianId") ?? ""),
    departmentCode: String(form.get("departmentCode") ?? ""),
  };
  const back = String(form.get("back") ?? "");

  const result = await submit<Encounter>("/encounters", "POST", body);
  if (!result.ok) {
    revalidatePath("/appointments");
    redirect(`/appointments?${new URLSearchParams(back)}&problem=${encodeURIComponent(result.error)}`);
  }
  revalidatePath("/appointments");
  redirect(`/encounters/${result.data.id}`);
}

/** Back to the chart with the outcome, since every one of these is a row action, not a form page. */
/**
 * Applies an order set to this encounter.
 *
 * <p>The refusal is shown verbatim, and here that is not a style preference. Applying a set is a
 * saga across two other services: the message may be the pharmacy's own words about an allergy, or
 * it may be the one sentence that matters — the tests could not be raised, the prescription could
 * not be withdrawn, and prescription X has to be cancelled by hand. A form that flattened those to
 * "could not apply" would leave a live prescription nobody knows about.
 */
export async function applyOrderSet(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const code = String(form.get("code") ?? "");
  const overrideReason = String(form.get("overrideReason") ?? "").trim();
  const result = await submit<{ message: string }>(`/order-sets/${code}/apply`, "POST", {
    encounterId: id,
    ...(overrideReason ? { overrideReason } : {}),
  });
  finish(id, result.ok ? null : result.error, result.ok ? result.data.message : null);
}

export async function startCarePlan(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const title = String(form.get("title") ?? "").trim();
  const result = await submit<CarePlan>("/care-plans", "POST", { encounterId: id, title });
  finish(id, result.ok ? null : result.error, result.ok ? "Care plan started." : null);
}

export async function addCareGoal(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const planId = String(form.get("planId") ?? "");
  const values = readForm(form, ["description", "problemCode", "targetDate"] as const);
  const result = await submit<CarePlan>(`/care-plans/${planId}/goals`, "POST",
    withoutBlanks(values));
  finish(id, result.ok ? null : result.error, result.ok ? "Goal added." : null);
}

/**
 * Records how a goal turned out.
 *
 * <p>The note field is offered for every outcome and required by the service for anything other
 * than met — the refusal explains why rather than naming a constraint, so it is shown as it comes.
 */
export async function recordCareGoal(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const goalId = String(form.get("goalId") ?? "");
  const status = String(form.get("status") ?? "");
  const progressNote = String(form.get("progressNote") ?? "").trim();
  const result = await submit<CarePlan>(`/care-plans/goals/${goalId}`, "PATCH", {
    status,
    ...(progressNote ? { progressNote } : {}),
  });
  finish(id, result.ok ? null : result.error,
    result.ok ? `Goal recorded as ${status.toLowerCase().replace("_", " ")}.` : null);
}

export async function closeCarePlan(form: FormData): Promise<void> {
  const id = String(form.get("encounterId") ?? "");
  const planId = String(form.get("planId") ?? "");
  const outcome = String(form.get("outcome") ?? "COMPLETED");
  const result = await submit<CarePlan>(`/care-plans/${planId}/close?outcome=${outcome}`, "POST");
  // The service refuses to complete a plan with an open goal, and says how many. That is the
  // instruction: record each one, rather than letting an unfinished goal vanish at discharge.
  finish(id, result.ok ? null : result.error,
    result.ok ? `Care plan ${outcome.toLowerCase()}.` : null);
}

function finish(id: string, problem: string | null, done: string | null): never {
  revalidatePath(`/encounters/${id}`);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`/encounters/${id}?${params}`);
}
