"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Consent, ShareOutcome } from "@/lib/types";
import { CONSENT_FIELDS, SHARE_FIELDS } from "./state";

/**
 * Consent decisions and disclosures.
 *
 * <p>Every refusal is shown verbatim, and here that matters for a reason particular to this
 * module: "Consent LOCAL-79E7 does not cover prescription. It covers op consultation, and consent
 * for one kind of record is not consent for another." tells a clinician what to ask the patient
 * for. A generic "forbidden" tells them the platform is in the way, and somebody who believes the
 * platform is in the way looks for a way round it — which for health information means an email
 * attachment.
 */

function back(path: string, problem: string | null, done: string | null): never {
  revalidatePath(path);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`${path}?${params}`);
}

export async function requestConsent(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, CONSENT_FIELDS);
  const body = withoutBlanks(values);
  // A set of checkboxes, so the field is read as a list rather than the single value readForm
  // returns. A consent covering nothing would be a consent that authorises nothing and looks
  // like permission.
  body.hiTypes = form.getAll("hiTypes").map(String);
  if ((body.hiTypes as string[]).length === 0) {
    return {
      values,
      fieldErrors: { hiTypes: "Say what the consent covers — at least one kind of record." },
      error: null,
      done: null,
    };
  }
  // A local datetime from the form, sent as an instant.
  if (values.expiresAt) {
    body.expiresAt = new Date(values.expiresAt).toISOString();
  }

  const result = await submit<Consent>("/consents", "POST", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/sharing");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Consent ${result.data.artefactId} recorded as requested. It authorises nothing until the patient grants it.`,
  };
}

export async function grantConsent(form: FormData): Promise<void> {
  const artefactId = String(form.get("artefactId") ?? "");
  const signature = String(form.get("signature") ?? "").trim();
  const result = await submit<Consent>(`/consents/${artefactId}/grant`, "POST",
    signature ? { signature } : {});
  back("/sharing", result.ok ? null : result.error,
    result.ok
      ? `${result.data.artefactId} granted until ${result.data.expiresAt}.`
      : null);
}

export async function denyConsent(form: FormData): Promise<void> {
  const artefactId = String(form.get("artefactId") ?? "");
  const result = await submit<Consent>(`/consents/${artefactId}/deny`, "POST");
  back("/sharing", result.ok ? null : result.error,
    result.ok ? `${result.data.artefactId} recorded as refused by the patient.` : null);
}

export async function revokeConsent(form: FormData): Promise<void> {
  const artefactId = String(form.get("artefactId") ?? "");
  const reason = String(form.get("reason") ?? "").trim();
  const result = await submit<Consent>(`/consents/${artefactId}/revoke`, "POST", { reason });
  back("/sharing", result.ok ? null : result.error,
    result.ok
      ? `${result.data.artefactId} revoked. Nothing further can be shared under it, and the record of what already was stays.`
      : null);
}

export async function shareRecord(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, SHARE_FIELDS);
  const result = await submit<ShareOutcome>("/interop/share", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/sharing");
  revalidatePath("/sharing/disclosures");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    // The platform's own sentence about whether anything actually left, verbatim. A screen that
    // said "shared" when the log adapter recorded and sent nothing would be the single most
    // misleading thing this module could do.
    done: `${result.data.resourceCount} resource(s), ${result.data.byteCount} bytes. ${result.data.message}`,
  };
}

export async function linkAbha(_previous: FormState, form: FormData): Promise<FormState> {
  const patientId = String(form.get("patientId") ?? "");
  const values = readForm(form, ["abhaNumber", "abhaAddress"] as const);
  const result = await submit<{ mrn: string }>(`/patients/${patientId}/abha`, "PUT", values);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath(`/patients/${patientId}`);
  return {
    values: {},
    fieldErrors: {},
    error: null,
    // Deliberately does not echo the number. It is a national identifier, and a confirmation
    // banner is rendered into a page, a screenshot and a support ticket.
    done: `ABHA linked to ${result.data.mrn}. It is stored encrypted and is not shown on the chart.`,
  };
}
