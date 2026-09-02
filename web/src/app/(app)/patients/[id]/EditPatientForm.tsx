"use client";

import Link from "next/link";
import { useActionState } from "react";
import type { Patient } from "@/lib/types";
import { ErrorNote } from "@/components/ui";
import { updatePatient } from "./actions";
import { EMPTY_EDIT_STATE, type EditState } from "./state";

/**
 * Editing a patient's demographics.
 *
 * <p>Only the fields `PATCH /patients/{id}` accepts, and deliberately not the national id or the
 * insurance policy number: those are encrypted at rest and released only through a separately
 * audited request, so a screen that could rewrite them without one would be a hole in that
 * arrangement. Correcting them is an administrative act with its own path, not a demographics
 * edit.
 *
 * <p>Field errors come back from the service's own Bean Validation messages. The date of birth is
 * the one worth watching: `@Past` refuses a future date, and that message is more useful than
 * anything this form could invent.
 */

const SEXES = ["FEMALE", "MALE", "OTHER", "UNKNOWN"] as const;

export function EditPatientForm({ patient }: { patient: Patient }) {
  const [state, formAction, pending] = useActionState(updatePatient, EMPTY_EDIT_STATE);

  // The record's own value unless the user has already typed something the service refused.
  const field = (name: string, current: string | null) => state.values[name] ?? current ?? "";

  return (
    <form action={formAction} className="space-y-6">
      <input type="hidden" name="patientId" value={patient.id} />
      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field name="firstName" label="First name" value={field("firstName", patient.firstName)} state={state} required />
        <Field name="lastName" label="Last name" value={field("lastName", patient.lastName)} state={state} required />
        <Field
          name="dateOfBirth"
          label="Date of birth"
          type="date"
          value={field("dateOfBirth", patient.dateOfBirth)}
          state={state}
          required
        />
        <div>
          <label htmlFor="sex" className="block text-sm font-medium">
            Sex
          </label>
          <select
            id="sex"
            name="sex"
            defaultValue={field("sex", patient.sex)}
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            {SEXES.map((sex) => (
              <option key={sex} value={sex}>
                {sex.charAt(0) + sex.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </div>
        <Field name="bloodGroup" label="Blood group" value={field("bloodGroup", patient.bloodGroup)} state={state} />
        <Field name="phone" label="Phone" type="tel" value={field("phone", patient.phone)} state={state} />
        <Field name="email" label="Email" type="email" value={field("email", patient.email)} state={state} />
      </section>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field name="addressLine1" label="Address line 1" value={field("addressLine1", patient.addressLine1)} state={state} />
        <Field name="addressLine2" label="Address line 2" value={field("addressLine2", patient.addressLine2)} state={state} />
        <Field name="city" label="City" value={field("city", patient.city)} state={state} />
        <Field name="state" label="State" value={field("state", patient.state)} state={state} />
        <Field name="postalCode" label="Postal code" value={field("postalCode", patient.postalCode)} state={state} />
        <Field name="country" label="Country" value={field("country", patient.country)} state={state} />
      </section>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Field
          name="insuranceProvider"
          label="Insurance provider"
          value={field("insuranceProvider", patient.insuranceProvider)}
          state={state}
        />
        <Field
          name="emergencyContactName"
          label="Next of kin"
          value={field("emergencyContactName", patient.emergencyContactName)}
          state={state}
        />
        <Field
          name="emergencyContactPhone"
          label="Next of kin phone"
          type="tel"
          value={field("emergencyContactPhone", patient.emergencyContactPhone)}
          state={state}
        />
      </section>

      <div>
        <label htmlFor="notes" className="block text-sm font-medium">
          Administrative notes
        </label>
        <p className="mt-0.5 text-xs text-ink-muted">
          Not a clinical note. Nothing written here appears on a chart or in a report — the
          encounter note is where clinical content belongs.
        </p>
        <textarea
          id="notes"
          name="notes"
          rows={3}
          defaultValue={field("notes", patient.notes)}
          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
        />
      </div>

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
        >
          {pending ? "Saving…" : "Save changes"}
        </button>
        <Link href={`/patients/${patient.id}`} className="text-sm text-accent hover:underline">
          Cancel
        </Link>
      </div>
    </form>
  );
}

function Field({
  name,
  label,
  value,
  state,
  type = "text",
  required = false,
}: {
  name: string;
  label: string;
  value: string;
  state: EditState;
  type?: string;
  required?: boolean;
}) {
  const error = state.fieldErrors[name];
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
        {required && <span className="ml-0.5 text-accent">*</span>}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        required={required}
        defaultValue={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${name}-error` : undefined}
        className={`mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${
          error ? "border-critical" : "border-line"
        }`}
      />
      {error && (
        <p id={`${name}-error`} className="mt-1 text-xs text-critical">
          {error}
        </p>
      )}
    </div>
  );
}
