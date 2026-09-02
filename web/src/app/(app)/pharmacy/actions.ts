"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { DispenseRecord, DoseRecord, FormularyEntry, InteractionPairing, Prescription, StockBatch } from "@/lib/types";
import {
  FORMULARY_FIELDS,
  INTERACTION_FIELDS,
  NUMBER_FIELDS,
  PRESCRIBE_FIELDS,
  STOCK_FIELDS,
} from "./state";

/**
 * The medication loop's writes.
 *
 * <p>Every refusal here is shown verbatim, and that matters more in this module than anywhere else
 * on the platform. "This order cannot be written: recorded life threatening allergy to Penicillin
 * (matched on PENICILLIN)" tells a prescriber what the platform found and why; a generic "conflict"
 * tells them the computer is being difficult, which is how a clinician learns to work around a
 * safety check. The service composes those sentences deliberately — including the
 * <em>management</em> text for an interaction, which is the part that says what to do instead — and
 * this layer's job is to get them onto the screen unedited.
 */

/** Row actions land back on a screen with the outcome in the query string. */
function back(path: string, problem: string | null, done: string | null): never {
  revalidatePath(path);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`${path}?${params}`);
}

function coerceNumbers(values: Record<string, string>): Record<string, unknown> {
  const body = withoutBlanks(values);
  for (const field of NUMBER_FIELDS) {
    if (body[field] !== undefined) {
      body[field] = Number(body[field]);
    }
  }
  return body;
}

/**
 * Writes a prescription from the chart.
 *
 * <p>One medicine per submission, which is a deliberate limitation and named as such on the screen:
 * an interaction is a property of a set, so the platform checks this line against the patient's
 * <em>other active medicines</em> rather than against nothing — but it cannot check two lines
 * submitted together as one order, because this form posts one. A prescriber adding a second
 * medicine gets a second check that includes the first.
 */
export async function prescribe(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, PRESCRIBE_FIELDS);
  const body = coerceNumbers(values);
  const items = [{
    drugCode: body.drugCode,
    dose: body.dose,
    frequency: body.frequency,
    durationDays: body.durationDays,
    quantity: body.quantity,
    instructions: body.instructions,
  }];

  const result = await submit<Prescription>("/prescriptions", "POST", {
    patientId: body.patientId,
    patientMrn: body.patientMrn,
    encounterId: body.encounterId,
    items,
    overrideReason: body.overrideReason,
  });
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/pharmacy");
  if (values.encounterId) {
    revalidatePath(`/encounters/${values.encounterId}`);
  }
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Prescribed ${result.data.items[0]?.drugName ?? "the medicine"}.`,
  };
}

export async function cancelPrescription(form: FormData): Promise<void> {
  const id = String(form.get("prescriptionId") ?? "");
  const result = await submit<Prescription>(`/prescriptions/${id}/cancel`, "POST");
  back("/pharmacy", result.ok ? null : result.error,
    result.ok ? "Prescription cancelled." : null);
}

export async function dispense(form: FormData): Promise<void> {
  const itemId = String(form.get("prescriptionItemId") ?? "");
  const quantity = Number(form.get("quantity") ?? 0);
  const batchId = String(form.get("batchId") ?? "").trim();
  const result = await submit<DispenseRecord>("/pharmacy/dispenses", "POST", {
    prescriptionItemId: itemId,
    quantity,
    ...(batchId ? { batchId } : {}),
  });
  // The batch is named in the confirmation because that is what the label on the box has to say,
  // and because a recall asks which batch went to whom.
  back("/pharmacy", result.ok ? null : result.error,
    result.ok
      ? `Dispensed ${result.data.quantity} from batch ${result.data.batchNo}${
          result.data.outstanding > 0 ? `; ${result.data.outstanding} still outstanding` : ""}.`
      : null);
}

export async function receiveStock(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, STOCK_FIELDS);
  const result = await submit<StockBatch>("/pharmacy/stock", "POST", coerceNumbers(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/pharmacy/stock");
  revalidatePath("/pharmacy/formulary");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Batch ${result.data.batchNo} received: ${result.data.quantityOnHand} unit(s), expiring ${result.data.expiresOn}.`,
  };
}

export async function addToFormulary(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, FORMULARY_FIELDS);
  const body = withoutBlanks(values);
  // A comma-separated list on the screen, an array on the wire. Every check in the service runs on
  // these, so an entry with none would pass every one of them by having nothing to match.
  body.ingredients = String(values.ingredients ?? "")
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0);
  body.controlled = values.controlled === "true";

  const result = await submit<FormularyEntry>("/pharmacy/formulary", "POST", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/pharmacy/formulary");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `${result.data.label} added, with ${result.data.ingredients.length} ingredient(s).`,
  };
}

export async function retireFromFormulary(form: FormData): Promise<void> {
  const code = String(form.get("code") ?? "");
  const active = String(form.get("active") ?? "") === "true";
  const result = await submit<FormularyEntry>(`/pharmacy/formulary/${code}`, "PATCH", { active });
  back("/pharmacy/formulary", result.ok ? null : result.error,
    result.ok ? `${code} is now ${active ? "stocked" : "retired"}.` : null);
}

export async function recordInteraction(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, INTERACTION_FIELDS);
  const result = await submit<InteractionPairing>("/pharmacy/interactions", "POST",
    withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/pharmacy/interactions");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `${result.data.ingredientA} + ${result.data.ingredientB} recorded as ${result.data.severity.toLowerCase()}.`,
  };
}

/**
 * Records a dose against two scans.
 *
 * <p>Both scans are required fields with no "scanner unavailable" escape, and that is the
 * deliberate part: an override that lets both checks be skipped becomes the normal path within a
 * week. Typing the numbers in is not blocked — a scanner fails and a nurse holding a syringe
 * cannot wait for procurement — but something has to be entered and it has to match.
 */
export async function administer(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form,
    ["prescriptionItemId", "scheduledFor", "patientScan", "drugScan"] as const);
  const result = await submit<DoseRecord>("/emar/administer", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/emar");
  return { values: {}, fieldErrors: {}, error: null, done: "Dose recorded as given." };
}

export async function recordNotGiven(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form,
    ["prescriptionItemId", "scheduledFor", "status", "reason"] as const);
  const result = await submit<DoseRecord>("/emar/not-given", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/emar");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Recorded as ${result.data.status.toLowerCase()}, with the reason.`,
  };
}
