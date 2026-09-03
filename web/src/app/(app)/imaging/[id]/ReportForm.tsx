"use client";

import { useActionState, useId, useState } from "react";
import { ErrorNote } from "@/components/ui";
import { AMEND_REASON_MIN, EMPTY_IMAGING_STATE } from "../state";
import { amendReport, writeReport } from "../actions";

/**
 * The report editor: findings, impression, and — once a report is signed — the reason it changed.
 *
 * <p>One component for drafting and for amending, because it is the same two boxes and the
 * difference is a third. Splitting it in two would have meant maintaining the same textareas twice
 * and letting them drift apart, and a radiologist amending a report is doing what they did when
 * they wrote it, plus explaining themselves.
 *
 * <p>The text is React state rather than uncontrolled inputs, and that is the lesson the results
 * form and the allergy form both paid for: React resets an uncontrolled form once its action
 * settles, so a refusal would blank a report a radiologist had just dictated. What is in these
 * boxes may be twenty minutes of somebody's reading, and it survives a refusal.
 *
 * <p>Findings and impression are two fields and not one on purpose. The findings are what is on the
 * images; the impression is what they mean and is the part the requester acts on. A single box
 * collapses the distinction, and the platform's own DTO keeps them apart.
 *
 * <p>Works with JavaScript disabled: the server renders each `value`, the browser lets it be typed
 * over, and the plain `<form action>` posts.
 */
export function ReportForm({
  studyId,
  findings,
  impression,
  signed,
}: {
  studyId: string;
  findings: string;
  impression: string;
  /** True once the report has been released — the form then amends rather than drafts. */
  signed: boolean;
}) {
  const [state, formAction, pending] = useActionState(
    signed ? amendReport : writeReport,
    EMPTY_IMAGING_STATE,
  );
  const uid = useId();
  const [text, setText] = useState({ findings, impression, reason: "" });
  const field = (name: keyof typeof text) => ({
    id: `${uid}-${name}`,
    name,
    value: text[name],
    onChange: (event: { target: { value: string } }) =>
      setText((current) => ({ ...current, [name]: event.target.value })),
    "aria-invalid": state.fieldErrors[name] ? (true as const) : undefined,
    className: `mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${
      state.fieldErrors[name] ? "border-critical" : "border-line"
    }`,
  });

  return (
    <form action={formAction} className="space-y-4">
      <input type="hidden" name="studyId" value={studyId} />

      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {state.done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {state.done}
        </p>
      )}

      {signed && (
        <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-xs text-warn">
          This report has been released and somebody may have treated from it. Amending it keeps the
          text that was signed beside the new text, and both stay on the record.
        </p>
      )}

      <div>
        <label htmlFor={`${uid}-findings`} className="block text-sm font-medium">
          Findings
        </label>
        <p className="text-xs text-ink-muted">What is on the images.</p>
        <textarea {...field("findings")} rows={10} />
        {state.fieldErrors.findings && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.findings}</p>
        )}
      </div>

      <div>
        <label htmlFor={`${uid}-impression`} className="block text-sm font-medium">
          Impression
        </label>
        <p className="text-xs text-ink-muted">
          What they mean. This is the part the clinician who asked will act on.
        </p>
        <textarea {...field("impression")} rows={4} />
        {state.fieldErrors.impression && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.impression}</p>
        )}
      </div>

      {signed && (
        <div>
          <label htmlFor={`${uid}-reason`} className="block text-sm font-medium">
            Why it is being amended
          </label>
          <p className="text-xs text-ink-muted">
            At least {AMEND_REASON_MIN} characters — a sentence, because this is on the record next
            to the text it replaced.
          </p>
          <textarea {...field("reason")} rows={3} minLength={AMEND_REASON_MIN} required />
          {state.fieldErrors.reason && (
            <p className="mt-1 text-xs text-critical">{state.fieldErrors.reason}</p>
          )}
        </div>
      )}

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending
          ? signed
            ? "Amending…"
            : "Saving…"
          : signed
            ? "Amend this report"
            : "Save as a draft"}
      </button>
      {!signed && (
        <p className="text-xs text-ink-muted">
          Saving does not release anything. Nobody treats from a draft — signing is what releases it
          to whoever asked for the examination.
        </p>
      )}
    </form>
  );
}
