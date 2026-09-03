"use client";

import { useActionState } from "react";
import { ErrorNote } from "@/components/ui";
import { EMPTY_IMAGING_STATE } from "../state";
import { signReport } from "../actions";

/**
 * Signs a draft, which releases it.
 *
 * <p>Its own form rather than a second button inside the editor, because it is a different act on
 * different text: the editor posts what is in its boxes, and this posts nothing at all. Two submit
 * buttons in one form would have made "sign" mean "save whatever is on screen and release that",
 * so a radiologist who had started typing a correction and then pressed sign would have released
 * the half-finished sentence.
 *
 * <p>The button says what it does. There is no second step and no separate release: a report that
 * were finished and unreleased would be a finding nobody could act on, which is the same reasoning
 * the laboratory settled on when verifying a result became releasing it.
 */
export function SignForm({ studyId }: { studyId: string }) {
  const [state, formAction, pending] = useActionState(signReport, EMPTY_IMAGING_STATE);

  return (
    <form action={formAction} className="space-y-2">
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
      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Signing…" : "Sign and release this report"}
      </button>
      <p className="text-xs text-ink-muted">
        Signing releases the report to the clinician who asked. After that it can be amended but not
        withdrawn, because somebody may already have acted on it.
      </p>
    </form>
  );
}
