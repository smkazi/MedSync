"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import type { Patient, PatientSummary } from "@/lib/types";
import type { RegisterState } from "./state";

/**
 * The platform's first write from a browser, and the shape every later form copies.
 *
 * <p><strong>Why a server action rather than a route handler.</strong> The access token lives in an
 * httpOnly cookie and {@link api} already runs server-side, so an action can call the gateway
 * directly and then revalidate. A route handler would mean re-plumbing auth for every form and
 * hand-rolling the redirect. The two existing `/api/ai/*` handlers stay as they are — they are
 * called from interactive client components, which is the case route handlers suit.
 *
 * <p><strong>Where validation lives.</strong> The service owns the rules — `CreatePatientRequest`
 * carries the `@Size`, `@Past`, `@Pattern` and `@Email` constraints — and none of them is
 * reimplemented here. A second copy in TypeScript would drift and then disagree, at which point the
 * browser is refusing something the platform would accept, or accepting something it will not. The
 * form does use `required`, `type="date"` and `type="email"`, which the browser enforces on its own;
 * that is markup helping somebody fill the form in, and it is a strict subset of what the service
 * checks. The answer to "is this record valid" always comes back from the API.
 */

/** Every field the form posts. Absent and blank are the same thing to the service. */
const FIELDS = [
  "firstName",
  "lastName",
  "dateOfBirth",
  "sex",
  "bloodGroup",
  "phone",
  "email",
  "addressLine1",
  "addressLine2",
  "city",
  "state",
  "postalCode",
  "country",
  "nationalId",
  "insuranceProvider",
  "insurancePolicyNo",
  "emergencyContactName",
  "emergencyContactPhone",
  "notes",
] as const;

export async function registerPatient(
  _previous: RegisterState,
  form: FormData,
): Promise<RegisterState> {
  const values: Record<string, string> = {};
  for (const field of FIELDS) {
    values[field] = String(form.get(field) ?? "").trim();
  }
  // Set only by the "Register anyway" button, which appears once candidates have been shown. It is
  // never a hidden input on the first submit: the confirmation has to be a deliberate act.
  const forceDuplicate = form.get("forceDuplicate") === "true";

  const body: Record<string, unknown> = { forceDuplicate };
  for (const [field, value] of Object.entries(values)) {
    // Blank means "not recorded", and an empty string would fail the service's own @Pattern and
    // @Email rules for a field the user simply left alone.
    if (value !== "") body[field] = value;
  }

  let created: Patient;
  try {
    created = await api<Patient>("/patients", { method: "POST", body });
  } catch (caught) {
    if (caught instanceof ApiError) {
      if (caught.status === 409) {
        const warning = caught.body as { candidates?: PatientSummary[] } | undefined;
        return {
          values,
          fieldErrors: {},
          // Not an error. The service is asking a question, and the form renders it as one.
          error: null,
          duplicates: warning?.candidates ?? [],
        };
      }
      return {
        values,
        fieldErrors: caught.fieldErrors ?? {},
        error: caught.fieldErrors ? null : caught.detail,
        duplicates: null,
      };
    }
    return {
      values,
      fieldErrors: {},
      error: caught instanceof Error ? caught.message : "Registration failed",
      duplicates: null,
    };
  }

  // Outside the try: redirect() works by throwing, and catching it here would render the throw as a
  // registration failure for a patient who was in fact created.
  revalidatePath("/patients");
  redirect(`/patients/${created.id}?registered=1`);
}
