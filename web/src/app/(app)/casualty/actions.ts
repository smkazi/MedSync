"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { Admission, CasualtyAttendance } from "@/lib/types";
import { ARRIVAL_FIELDS } from "./state";

/**
 * Casualty and in-patient writes.
 *
 * <p>The acuity is coerced to a number and is required, with no default anywhere in this file. An
 * untriaged patient sorted as though they were a 3 is the failure the whole board exists to
 * prevent, and it would be invisible — so the service rejects an absent acuity and nothing here
 * quietly supplies one.
 */

/** Row actions land back on a board with the outcome in the query string. */
function back(path: string, problem: string | null, done: string | null): never {
  revalidatePath(path);
  const params = new URLSearchParams();
  if (problem) params.set("problem", problem);
  if (done) params.set("done", done);
  redirect(`${path}?${params}`);
}

export async function recordArrival(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, ARRIVAL_FIELDS);
  const body = withoutBlanks(values);
  if (body.triageAcuity !== undefined) {
    body.triageAcuity = Number(body.triageAcuity);
  }

  const result = await submit<CasualtyAttendance>("/casualty", "POST", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/casualty");
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Triaged at acuity ${result.data.triageAcuity}.`,
  };
}

/**
 * Re-triage.
 *
 * <p>A row action rather than a form page: a patient waiting in a corridor can get worse, and the
 * nurse noticing needs one click from the board rather than a screen to navigate to.
 */
export async function retriage(form: FormData): Promise<void> {
  const id = String(form.get("attendanceId") ?? "");
  const acuity = Number(form.get("triageAcuity") ?? 0);
  const result = await submit<CasualtyAttendance>(`/casualty/${id}/triage`, "PATCH", {
    triageAcuity: acuity,
  });
  back(
    "/casualty",
    result.ok ? null : result.error,
    result.ok ? `Re-triaged to acuity ${acuity}.` : null,
  );
}

export async function placeInBed(form: FormData): Promise<void> {
  const id = String(form.get("attendanceId") ?? "");
  const bedId = String(form.get("bedId") ?? "");
  const result = await submit<CasualtyAttendance>(`/casualty/${id}/bed`, "POST", { bedId });
  // The 409 says which bed and tells the nurse what to do next — "Bed CAS-1 in GF-CAS has just
  // been taken. Pick another from the free list." — so it is shown verbatim.
  back(
    "/casualty",
    result.ok ? null : result.error,
    result.ok ? `Moved to ${result.data.bedCode}.` : null,
  );
}

export async function dischargeFromCasualty(form: FormData): Promise<void> {
  const id = String(form.get("attendanceId") ?? "");
  const result = await submit<CasualtyAttendance>(`/casualty/${id}/discharge`, "POST");
  back("/casualty", result.ok ? null : result.error, result.ok ? "Discharged." : null);
}

export async function leftWithoutBeingSeen(form: FormData): Promise<void> {
  const id = String(form.get("attendanceId") ?? "");
  const result = await submit<CasualtyAttendance>(`/casualty/${id}/left`, "POST");
  back(
    "/casualty",
    result.ok ? null : result.error,
    result.ok ? "Recorded as having left without being seen." : null,
  );
}

/**
 * Admits a patient, from casualty or directly.
 *
 * <p>Redirects to the census on success rather than returning state, so there is one place a
 * success message appears — the same reason the charting actions redirect.
 */
export async function admit(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, [
    "patientId",
    "patientMrn",
    "attendanceId",
    "bedId",
    "admittingClinicianId",
    "source",
    "expectedDischarge",
  ] as const);

  const result = await submit<Admission>("/admissions", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/casualty");
  // Redirected rather than returned, and this is the one place in the file where that matters for
  // more than tidiness. Admitting takes the patient off the casualty board, so the card holding
  // this form - which exists only while the attendance is open - unmounts on the next render, and
  // a success message returned in the form's own state would unmount with it. The clinician would
  // press Admit and be told nothing at all. Sending the outcome through the query string puts it
  // where the census already shows one.
  back("/admissions", null, `Admitted to ${result.data.bedCode} in ${result.data.roomCode}.`);
}

export async function transfer(form: FormData): Promise<void> {
  const id = String(form.get("admissionId") ?? "");
  const toBedId = String(form.get("toBedId") ?? "");
  const reason = String(form.get("reason") ?? "").trim();
  const result = await submit<Admission>(`/admissions/${id}/transfer`, "POST", {
    toBedId,
    reason,
  });
  back(
    "/admissions",
    result.ok ? null : result.error,
    result.ok ? `Moved to ${result.data.bedCode}.` : null,
  );
}

export async function discharge(form: FormData): Promise<void> {
  const id = String(form.get("admissionId") ?? "");
  const summary = String(form.get("summary") ?? "").trim();
  const result = await submit<Admission>(`/admissions/${id}/discharge`, "POST",
    summary ? { summary } : {});
  back(
    "/admissions",
    result.ok ? null : result.error,
    result.ok ? `Discharged after ${result.data.lengthOfStayDays} day(s).` : null,
  );
}
