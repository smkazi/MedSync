"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Allergy, Patient, PortalAccountIssued } from "@/lib/types";
import {
  ALLERGY_FIELDS,
  EDIT_FIELDS,
  SEVERITIES_NEEDING_CONFIRMATION,
  type AllergyState,
  type EditState,
} from "./state";

/**
 * Writes against one patient: demographics, archiving, and the allergy list.
 *
 * <p>The allergy list is the part that carries weight. It is not a free-text note — a severity of
 * SEVERE or LIFE_THREATENING is what a later dispense check reads to refuse a drug, so recording
 * one is closer to writing a rule than to writing a remark. That is why adding a life-threatening
 * allergy asks a question first, and why removing any allergy does too.
 */

export async function updatePatient(_previous: EditState, form: FormData): Promise<EditState> {
  const id = String(form.get("patientId") ?? "");
  const values = readForm(form, EDIT_FIELDS);

  // Blank means "not provided" rather than "set to empty": PATCH is sparse, and sending "" for an
  // untouched optional field would clear it. The one thing that must survive is a field the user
  // deliberately emptied, and the form marks those by submitting them - a blank required field is
  // refused by the service's own validation, not silently dropped.
  const result = await submit<Patient>(`/patients/${id}`, "PATCH", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/patients/${id}`);
  redirect(`/patients/${id}?done=${encodeURIComponent("Patient record updated.")}`);
}

/**
 * Archives a patient.
 *
 * <p>A `DELETE` that is not a delete: the platform sets `active` to false and keeps every row. A
 * patient record is a legal document with retention obligations, and the appointments, encounters
 * and lab orders that reference it stay valid. The screen says "archive" because that is what
 * happens, not because it is the gentler word.
 */
export async function archivePatient(form: FormData): Promise<void> {
  const id = String(form.get("patientId") ?? "");
  const result = await submit<{ message: string }>(`/patients/${id}`, "DELETE");
  finish(id, result.ok ? null : result.error, result.ok ? "Patient archived." : null);
}

/** Puts an archived patient back in use. Sparse PATCH, so nothing else on the record moves. */
export async function restorePatient(form: FormData): Promise<void> {
  const id = String(form.get("patientId") ?? "");
  const result = await submit<Patient>(`/patients/${id}`, "PATCH", { active: true });
  finish(id, result.ok ? null : result.error, result.ok ? "Patient restored." : null);
}

/**
 * Records an allergy, asking first when the severity is one the platform will act on.
 *
 * <p>The confirmation is a server round trip rather than a `confirm()` dialog, so it survives with
 * JavaScript disabled and so the text of the question comes from the same place as the rule. What
 * is being confirmed is not "are you sure" but what it will do: a life-threatening allergy is
 * checked before a drug is dispensed and refuses it outright.
 */
export async function addAllergy(_previous: AllergyState, form: FormData): Promise<AllergyState> {
  const id = String(form.get("patientId") ?? "");
  const values = readForm(form, ALLERGY_FIELDS);
  const confirmed = form.get("confirmed") === "yes";

  // Answering "no" puts the form back the way it was, with what was typed still in it.
  if (form.get("cancelled") === "yes") {
    return { values, fieldErrors: {}, error: null, done: null, confirming: null };
  }
  if (!values.substance.trim()) {
    return {
      values,
      fieldErrors: { substance: "Name the substance." },
      error: null,
      done: null,
      confirming: null,
    };
  }
  if (SEVERITIES_NEEDING_CONFIRMATION.has(values.severity) && !confirmed) {
    return { values, fieldErrors: {}, error: null, done: null, confirming: values.severity };
  }

  const result = await submit<Allergy>(`/patients/${id}/allergies`, "POST", {
    substance: values.substance,
    ...(values.reaction ? { reaction: values.reaction } : {}),
    severity: values.severity,
  });
  if (!result.ok) {
    return { ...refused(values, result), confirming: null };
  }
  revalidatePath(`/patients/${id}`);
  redirect(`/patients/${id}?done=${encodeURIComponent(`Recorded: ${values.substance}.`)}`);
}

/**
 * Removes an allergy.
 *
 * <p>Also confirmed, and for the sharper reason: taking a critical allergy off the record removes
 * a refusal the platform was making on the patient's behalf. The form carries the substance so the
 * question can name it — "remove this row" is not a question anybody can answer safely.
 */
export async function removeAllergy(form: FormData): Promise<void> {
  const id = String(form.get("patientId") ?? "");
  const allergyId = String(form.get("allergyId") ?? "");
  const substance = String(form.get("substance") ?? "this allergy");

  const result = await submit<{ message: string }>(`/patients/${id}/allergies/${allergyId}`, "DELETE");
  finish(id, result.ok ? null : result.error, result.ok ? `Removed ${substance}.` : null);
}

/** Back to the chart with the outcome, since each of these is a row action, not a form page. */
function finish(id: string, problem: string | null, done: string | null): never {
  revalidatePath(`/patients/${id}`);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`/patients/${id}?${params}`);
}

/**
 * Issues or re-issues this patient's portal access.
 *
 * <p>The one-time password comes back in the response and exists in readable form nowhere else —
 * not stored, not logged, not emailed. It is passed to the chart as a query parameter so the desk
 * can read it out, which means it is briefly in a URL and therefore in the browser's history: the
 * screen says so, and the password is worthless the moment the patient changes it, which the
 * platform requires before the account can do anything at all.
 */
export async function issuePortalAccess(form: FormData): Promise<void> {
  const id = String(form.get("id") ?? "");
  const result = await submit<PortalAccountIssued>(`/patients/${id}/portal-account`, "POST");
  revalidatePath(`/patients/${id}`);
  redirect(
    result.ok
      ? `/patients/${id}?portalUser=${encodeURIComponent(result.data.username)}`
        + `&portalPassword=${encodeURIComponent(result.data.temporaryPassword)}`
      : `/patients/${id}?problem=${encodeURIComponent(result.error)}`,
  );
}

/** Withdraws portal access and ends every live session the patient holds. */
export async function withdrawPortalAccess(form: FormData): Promise<void> {
  const id = String(form.get("id") ?? "");
  const result = await submit<unknown>(`/patients/${id}/portal-account`, "DELETE");
  revalidatePath(`/patients/${id}`);
  redirect(
    result.ok
      ? `/patients/${id}?done=${encodeURIComponent("Portal access withdrawn and every session ended.")}`
      : `/patients/${id}?problem=${encodeURIComponent(result.error)}`,
  );
}
