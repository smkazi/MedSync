"use server";

import { revalidatePath } from "next/cache";
import { ApiError, apiUpload } from "@/lib/api";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { instantFromLocal } from "@/lib/zone";
import { submit } from "@/lib/mutate";
import type { ImagingOrder, ImagingReport } from "@/lib/types";
import {
  AMEND_FIELDS,
  CANCEL_FIELDS,
  ORDER_FIELDS,
  REPORT_FIELDS,
  SCHEDULE_FIELDS,
  type ImagingFormState,
} from "./state";

/** Every radiology screen a write can change. Cheap, and a stale worklist is a repeated scan. */
function revalidateRadiology(): void {
  revalidatePath("/imaging");
  revalidatePath("/imaging/reporting");
  revalidatePath("/imaging/unmatched");
}

export async function orderExamination(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const values = readForm(form, ORDER_FIELDS);
  const result = await submit<ImagingOrder>("/imaging/orders", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  if (values.encounterId) {
    revalidatePath(`/encounters/${values.encounterId}`);
  }
  revalidateRadiology();
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      `Requested. ${result.data.accessionNo} — ${result.data.procedureName}. ` +
      "The accession number is on the worklist now; the modality writes it into every image.",
  };
}

/**
 * Books a slot on a modality.
 *
 * <p>The one place on this screen that converts anything. A `datetime-local` input submits a
 * wall-clock time with no zone and the platform wants an instant, so the reading has to be pinned
 * to a zone somewhere — and the two zones nearest to hand are both wrong. Reading it here with
 * `new Date(local)` reads the *container's* `TZ`, usually UTC; reading it in the browser reads
 * whatever the console's clock is set to. Either would book a slot at a time nobody typed, and
 * `formatDateTime` would then render it back differently from what the radiographer entered, which
 * looks exactly like the platform having moved the appointment.
 */
export async function scheduleExamination(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const orderId = String(form.get("orderId") ?? "");
  const values = readForm(form, SCHEDULE_FIELDS);
  const scheduledFor = instantFromLocal(values.scheduledFor);
  if (scheduledFor === null) {
    return {
      values,
      fieldErrors: { scheduledFor: "Choose a date and a time." },
      error: null,
      done: null,
    };
  }
  const result = await submit<ImagingOrder>(`/imaging/orders/${orderId}/schedule`, "POST",
    { scheduledFor });
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRadiology();
  return { values: {}, fieldErrors: {}, error: null, done: "Booked." };
}

export async function cancelExamination(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const orderId = String(form.get("orderId") ?? "");
  const values = readForm(form, CANCEL_FIELDS);
  const result = await submit<ImagingOrder>(`/imaging/orders/${orderId}/cancel`, "POST",
    withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRadiology();
  return { values: {}, fieldErrors: {}, error: null, done: "Cancelled." };
}

/**
 * Files one DICOM instance.
 *
 * <p>The only write on the platform that carries a file, so it goes through {@link apiUpload}
 * rather than {@link submit} — which sends JSON and would post the string "[object File]".
 *
 * <p>The confirmation is the platform's own sentence, not one composed here. Whether the study
 * matched an order and whether the image was archived are two facts a radiographer acts on, and the
 * service already says both in words; rewording them here would be this screen's opinion of what
 * happened.
 */
export async function fileStudy(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const file = form.get("file");
  if (!(file instanceof File) || file.size === 0) {
    return {
      values: {},
      fieldErrors: { file: "Choose a DICOM file to file." },
      error: null,
      done: null,
    };
  }
  try {
    const result = await apiUpload<{ matched: boolean; message: string; accessionNo: string | null }>(
      "/imaging/studies",
      file,
    );
    revalidateRadiology();
    return { values: {}, fieldErrors: {}, error: null, done: result.message };
  } catch (caught) {
    return {
      values: {},
      fieldErrors: {},
      error:
        caught instanceof ApiError
          ? caught.status === 403
            ? "Your role does not have permission to do that."
            : caught.detail
          : "The file could not be filed.",
      done: null,
    };
  }
}

export async function writeReport(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const studyId = String(form.get("studyId") ?? "");
  const values = readForm(form, REPORT_FIELDS);
  const result = await submit<ImagingReport>(`/imaging/studies/${studyId}/report`, "PUT",
    withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRadiology();
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: "Saved as a draft. Nobody treats from a draft — sign it to release it.",
  };
}

/**
 * Signs the report, which releases it.
 *
 * <p>The confirmation says so in as many words. A radiologist pressing this has released the
 * finding to whoever ordered it, and a screen that answered "saved" would be describing a
 * different act.
 */
export async function signReport(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const studyId = String(form.get("studyId") ?? "");
  const result = await submit<ImagingReport>(`/imaging/studies/${studyId}/report/sign`, "POST");
  if (!result.ok) {
    return refused({}, result);
  }
  revalidateRadiology();
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: `Signed and released by ${result.data.signedBy}. The requester can read it now.`,
  };
}

export async function amendReport(
  _previous: ImagingFormState,
  form: FormData,
): Promise<ImagingFormState> {
  const studyId = String(form.get("studyId") ?? "");
  const values = readForm(form, AMEND_FIELDS);
  const result = await submit<ImagingReport>(`/imaging/studies/${studyId}/report/amend`, "POST",
    withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidateRadiology();
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done: "Amended. The text that was signed is kept beside it, because somebody may have acted on it.",
  };
}
