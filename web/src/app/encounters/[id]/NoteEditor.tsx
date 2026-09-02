"use client";

import { useActionState } from "react";
import type { ClinicalNote } from "@/lib/types";
import { ErrorNote } from "@/components/ui";
import { writeNote } from "./actions";
import { EMPTY_NOTE_STATE } from "./state";

/**
 * The SOAP note.
 *
 * <p>One save button, three different consequences, and the screen says which before it happens:
 *
 * <ul>
 *   <li>no note yet — this creates revision 1</li>
 *   <li>the current revision is a draft — this edits it in place, no new revision</li>
 *   <li>the current revision is signed — this creates an <strong>amendment</strong>, a new
 *       revision that records what it amends</li>
 * </ul>
 *
 * <p>All three are the service's rules, not this component's; it only reports them. The third is
 * the one worth a warning: amending a signed clinical note is a different act from correcting a
 * draft, and the button looks identical either way.
 */
export function NoteEditor({
  encounterId,
  current,
  editable,
}: {
  encounterId: string;
  current: ClinicalNote | null;
  /** False once the encounter is closed - the API refuses, and offering the form would mislead. */
  editable: boolean;
}) {
  const [state, formAction, pending] = useActionState(writeNote, EMPTY_NOTE_STATE);

  const amending = Boolean(current?.signed);
  const field = (name: "subjective" | "objective" | "assessment" | "plan") =>
    state.values[name] ?? current?.[name] ?? "";

  if (!editable) {
    return (
      <p className="text-sm text-ink-muted">
        This encounter is closed. Its notes are part of the record and cannot be edited here.
      </p>
    );
  }

  return (
    <form action={formAction} className="space-y-4">
      <input type="hidden" name="encounterId" value={encounterId} />

      {/* Only refusals surface here. A successful save redirects, so the outcome is reported by
          the one status line on the page rather than a second one inside this form. */}
      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      {amending && (
        <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
          Revision {current?.revision} is signed. Saving creates an <strong>amendment</strong> — a
          new revision recorded against this one. The signed text stays in the record and remains
          readable; nothing is overwritten.
        </p>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <Section name="subjective" label="Subjective" value={field("subjective")} state={state} />
        <Section name="objective" label="Objective" value={field("objective")} state={state} />
        <Section name="assessment" label="Assessment" value={field("assessment")} state={state} />
        <Section name="plan" label="Plan" value={field("plan")} state={state} />
      </div>

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Saving…" : amending ? "Save as an amendment" : "Save note"}
      </button>
    </form>
  );
}

function Section({
  name,
  label,
  value,
  state,
}: {
  name: string;
  label: string;
  value: string;
  state: { fieldErrors: Record<string, string> };
}) {
  const error = state.fieldErrors[name];
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
      </label>
      <textarea
        id={name}
        name={name}
        rows={5}
        defaultValue={value}
        aria-invalid={error ? true : undefined}
        className={`mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${
          error ? "border-critical" : "border-line"
        }`}
      />
      {error && <p className="mt-1 text-xs text-critical">{error}</p>}
    </div>
  );
}
