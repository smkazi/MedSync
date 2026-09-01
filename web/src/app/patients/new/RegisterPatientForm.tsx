"use client";

import Link from "next/link";
import { useActionState } from "react";
import { registerPatient } from "./actions";
import { EMPTY_REGISTER_STATE, type RegisterState } from "./state";
import { ErrorNote } from "@/components/ui";

/**
 * The registration form.
 *
 * <p>Client only because {@link useActionState} needs to be, and only for the returned state — the
 * work happens in the server action. That matters for one specific reason: the national id is
 * encrypted at rest and served only by the audited identifiers endpoint, so it must not pass
 * through client-side state on the way in either. It does not: the field is uncontrolled, the
 * browser posts it straight to the action, and the only value React holds is whatever the server
 * echoes back — which for a failed submit is the same string the user is still looking at.
 *
 * <p>The form works with JavaScript disabled. `useActionState` progressively enhances a plain
 * `<form action>`, so a submit without a hydrated bundle posts, runs the action, and re-renders
 * server-side with the same messages.
 */

const SEXES = ["FEMALE", "MALE", "OTHER", "UNKNOWN"] as const;

function Field({
  name,
  label,
  state,
  type = "text",
  required = false,
  hint,
  autoComplete,
}: {
  name: string;
  label: string;
  state: RegisterState;
  type?: string;
  required?: boolean;
  hint?: string;
  autoComplete?: string;
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
        autoComplete={autoComplete}
        defaultValue={state.values[name] ?? ""}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${name}-error` : hint ? `${name}-hint` : undefined}
        className={`mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${
          error ? "border-critical" : "border-line"
        }`}
      />
      {error ? (
        <p id={`${name}-error`} className="mt-1 text-xs text-critical">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${name}-hint`} className="mt-1 text-xs text-ink-muted">
            {hint}
          </p>
        )
      )}
    </div>
  );
}

export function RegisterPatientForm() {
  const [state, formAction, pending] = useActionState(registerPatient, EMPTY_REGISTER_STATE);
  const duplicates = state.duplicates;

  return (
    <form action={formAction} className="space-y-6">
      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      {Object.keys(state.fieldErrors).length > 0 && (
        <ErrorNote>
          The platform rejected {Object.keys(state.fieldErrors).length} field
          {Object.keys(state.fieldErrors).length === 1 ? "" : "s"}. Each one is marked below.
        </ErrorNote>
      )}

      {duplicates && (
        <div className="rounded-md border border-warn/40 bg-warn-soft p-4">
          <p className="text-sm font-medium">
            {duplicates.length === 1 ? "This chart looks" : "These charts look"} like the same
            person
          </p>
          <p className="mt-1 text-xs text-ink-muted">
            Same surname and date of birth. Open the existing chart if it is them — a second chart
            for one patient splits their history in two, and the halves are hard to put back
            together.
          </p>
          <ul className="mt-3 space-y-2">
            {duplicates.map((candidate) => (
              <li key={candidate.id}>
                <Link
                  href={`/patients/${candidate.id}`}
                  className="block rounded border border-line bg-surface px-3 py-2 hover:bg-surface-raised"
                >
                  <span className="text-sm font-medium">{candidate.fullName}</span>
                  <span className="ml-2 numeric text-xs text-ink-muted">{candidate.mrn}</span>
                  <span className="block text-xs text-ink-muted">
                    {candidate.dateOfBirth} · {candidate.age} · {candidate.sex}
                    {candidate.active ? "" : " · archived"}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
          <button
            type="submit"
            name="forceDuplicate"
            value="true"
            disabled={pending}
            className="mt-3 rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-surface disabled:opacity-60"
          >
            Not the same person — register anyway
          </button>
        </div>
      )}

      <fieldset className="space-y-4">
        <legend className="text-sm font-medium">Identity</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field name="firstName" label="First name" state={state} required autoComplete="off" />
          <Field name="lastName" label="Surname" state={state} required autoComplete="off" />
          <Field name="dateOfBirth" label="Date of birth" type="date" state={state} required />
          <div>
            <label htmlFor="sex" className="block text-sm font-medium">
              Sex<span className="ml-0.5 text-accent">*</span>
            </label>
            <select
              id="sex"
              name="sex"
              required
              defaultValue={state.values.sex ?? ""}
              aria-describedby="sex-hint"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            >
              <option value="" disabled>
                Select…
              </option>
              {SEXES.map((sex) => (
                <option key={sex} value={sex}>
                  {sex.charAt(0) + sex.slice(1).toLowerCase()}
                </option>
              ))}
            </select>
            <p id="sex-hint" className="mt-1 text-xs text-ink-muted">
              Administrative sex. Selects the sex-specific laboratory reference intervals, so it is
              a clinical field, not a demographic one.
            </p>
          </div>
          <Field
            name="bloodGroup"
            label="Blood group"
            state={state}
            hint="Optional. Recorded, never inferred."
          />
        </div>
      </fieldset>

      <fieldset className="space-y-4">
        <legend className="text-sm font-medium">Contact</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field name="phone" label="Phone" type="tel" state={state} autoComplete="off" />
          <Field name="email" label="Email" type="email" state={state} autoComplete="off" />
          <Field name="addressLine1" label="Address line 1" state={state} />
          <Field name="addressLine2" label="Address line 2" state={state} />
          <Field name="city" label="City" state={state} />
          <Field name="state" label="State" state={state} />
          <Field name="postalCode" label="Postal code" state={state} />
          <Field name="country" label="Country" state={state} />
        </div>
      </fieldset>

      <fieldset className="space-y-4">
        <legend className="text-sm font-medium">Identifiers and cover</legend>
        <p className="text-xs text-ink-muted">
          The national id and the policy number are encrypted at rest and are not returned by any
          list or chart response. Reading them back is a separate, audited request.
        </p>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field name="nationalId" label="National id" state={state} autoComplete="off" />
          <Field name="insuranceProvider" label="Insurer" state={state} />
          <Field name="insurancePolicyNo" label="Policy number" state={state} autoComplete="off" />
        </div>
      </fieldset>

      <fieldset className="space-y-4">
        <legend className="text-sm font-medium">Emergency contact</legend>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field name="emergencyContactName" label="Name" state={state} />
          <Field name="emergencyContactPhone" label="Phone" type="tel" state={state} />
        </div>
      </fieldset>

      <div>
        <label htmlFor="notes" className="block text-sm font-medium">
          Registration notes
        </label>
        <textarea
          id="notes"
          name="notes"
          rows={3}
          defaultValue={state.values.notes ?? ""}
          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
        />
        <p className="mt-1 text-xs text-ink-muted">
          Administrative only. Clinical findings belong in an encounter note, where they are part of
          the record a clinician reads.
        </p>
        {state.fieldErrors.notes && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.notes}</p>
        )}
      </div>

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
        >
          {pending ? "Registering…" : "Register patient"}
        </button>
        <Link href="/patients" className="text-sm text-ink-muted hover:text-ink">
          Cancel
        </Link>
      </div>
    </form>
  );
}
