"use client";

import { useActionState, useState } from "react";
import { ErrorNote } from "@/components/ui";
import { addAllergy } from "./actions";
import { EMPTY_ALLERGY_STATE } from "./state";

/**
 * Recording an allergy.
 *
 * <p>The severity dropdown is the important control on this form, and it is the reason the form
 * exists rather than a plain POST. Severity is not a label — the platform reads it, and a
 * LIFE_THREATENING entry will later refuse a dispense outright. So choosing it asks a question
 * first, and the question says what the entry will *do* rather than "are you sure".
 *
 * <p>The confirmation is a server round trip, not a `confirm()`: it survives with JavaScript
 * disabled, and the wording lives beside the rule instead of in a browser dialog.
 *
 * <p>Two details make that round trip trustworthy, and the first was a bug before it was a design
 * decision. <strong>The severity is controlled state.</strong> React resets a form's uncontrolled
 * fields once an action completes, and a reset `<select>` returns to the option that was marked
 * selected when it first mounted — not to whatever the current `defaultValue` prop says. So
 * choosing "Life-threatening", being asked the question, and confirming it recorded the allergy as
 * <em>moderate</em>: the answer to the question was submitted with the field silently back at its
 * default. Text inputs survive the same reset because React updates their `value` attribute, which
 * is exactly why this was invisible — the substance came back correctly and only the severity, the
 * one field the question was about, did not. Second, the fields are read-only while the question is
 * on screen, so what gets recorded is what was asked about.
 */

const SEVERITIES = [
  { value: "MILD", label: "Mild" },
  { value: "MODERATE", label: "Moderate" },
  { value: "SEVERE", label: "Severe" },
  { value: "LIFE_THREATENING", label: "Life-threatening" },
] as const;

export function AllergyForm({ patientId }: { patientId: string }) {
  const [state, formAction, pending] = useActionState(addAllergy, EMPTY_ALLERGY_STATE);
  const [severity, setSeverity] = useState<string>("MODERATE");
  const confirming = state.confirming;

  return (
    <form action={formAction} className="space-y-3">
      <input type="hidden" name="patientId" value={patientId} />
      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      <div>
        <label htmlFor="substance" className="block text-sm font-medium">
          Substance<span className="ml-0.5 text-accent">*</span>
        </label>
        <input
          id="substance"
          name="substance"
          required
          readOnly={Boolean(confirming)}
          defaultValue={state.values.substance ?? ""}
          placeholder="Penicillin"
          aria-invalid={state.fieldErrors.substance ? true : undefined}
          className={`mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm read-only:opacity-70 ${
            state.fieldErrors.substance ? "border-critical" : "border-line"
          }`}
        />
        {state.fieldErrors.substance && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.substance}</p>
        )}
      </div>

      <div>
        <label htmlFor="reaction" className="block text-sm font-medium">
          Reaction
        </label>
        <input
          id="reaction"
          name="reaction"
          readOnly={Boolean(confirming)}
          defaultValue={state.values.reaction ?? ""}
          placeholder="Urticaria and facial swelling"
          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm read-only:opacity-70"
        />
      </div>

      <div>
        <label htmlFor="severity" className="block text-sm font-medium">
          Severity<span className="ml-0.5 text-accent">*</span>
        </label>
        <select
          id="severity"
          // Named only when it is the live control. While the question is on screen the select is
          // disabled - and a disabled field is not submitted - so the hidden input below carries
          // the value instead, and the answer cannot be to a different question.
          name={confirming ? undefined : "severity"}
          required
          disabled={Boolean(confirming)}
          value={severity}
          onChange={(event) => setSeverity(event.target.value)}
          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm disabled:opacity-70"
        >
          {SEVERITIES.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {confirming ? (
        <div
          role="alert"
          className="space-y-2 rounded-md border-2 border-critical bg-critical-soft px-3 py-2 text-sm text-critical"
        >
          <input type="hidden" name="severity" value={confirming} />
          <p>
            <strong>{state.values.substance}</strong> will be recorded as life-threatening. That
            puts a red banner at the top of this chart and makes the platform refuse to dispense
            anything containing it — not warn, refuse. Record it only if that is what the clinical
            record says.
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="submit"
              name="confirmed"
              value="yes"
              disabled={pending}
              className="rounded-md bg-critical px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
            >
              Yes, record it as life-threatening
            </button>
            <button
              type="submit"
              name="cancelled"
              value="yes"
              disabled={pending}
              className="rounded-md border border-line bg-surface-raised px-3 py-1.5 text-sm font-medium hover:bg-surface"
            >
              No, go back and change it
            </button>
          </div>
        </div>
      ) : (
        <button
          type="submit"
          disabled={pending}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
        >
          {pending ? "Recording…" : "Record allergy"}
        </button>
      )}
    </form>
  );
}
