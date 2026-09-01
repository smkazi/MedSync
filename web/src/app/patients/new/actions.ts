"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks } from "@/lib/form";
import { submit } from "@/lib/mutate";
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
  const values = readForm(form, FIELDS);
  // Set only by the "Register anyway" button, which appears once candidates have been shown. It is
  // never a hidden input on the first submit: the confirmation has to be a deliberate act.
  const forceDuplicate = form.get("forceDuplicate") === "true";

  const result = await submit<Patient>("/patients", "POST", {
    ...withoutBlanks(values),
    forceDuplicate,
  });

  if (!result.ok) {
    if (result.status === 409) {
      const warning = result.body as { candidates?: PatientSummary[] } | undefined;
      // Not an error. The service is asking a question, and the form renders it as one.
      return { values, fieldErrors: {}, error: null, done: null, duplicates: warning?.candidates ?? [] };
    }
    return { ...refused(values, result), duplicates: null };
  }

  // Outside any try: redirect() works by throwing, and catching it would render the throw as a
  // registration failure for a patient who was in fact created.
  revalidatePath("/patients");
  redirect(`/patients/${result.data.id}?registered=1`);
}
