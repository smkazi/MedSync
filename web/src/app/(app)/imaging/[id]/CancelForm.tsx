"use client";

import { useActionState, useId } from "react";
import { ErrorNote } from "@/components/ui";
import { EMPTY_IMAGING_STATE } from "../state";
import { cancelExamination } from "../actions";

/**
 * Withdraws a request.
 *
 * <p>The requester's act rather than the department's, which is why this renders for a clinician
 * and not for a radiographer: they asked, so they can unask. A radiography room that could cancel
 * requests would be deciding what a patient does not need.
 *
 * <p>Offered only while nothing has been acquired — the platform refuses afterwards, because an
 * examination somebody has already had is a fact and cancelling it would be editing history rather
 * than a plan.
 */
export function CancelForm({ orderId }: { orderId: string }) {
  const [state, formAction, pending] = useActionState(cancelExamination, EMPTY_IMAGING_STATE);
  const id = useId();

  return (
    <form action={formAction} className="space-y-2">
      <input type="hidden" name="orderId" value={orderId} />
      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {state.done && (
        <p role="status" className="text-sm text-good">
          {state.done}
        </p>
      )}
      <div>
        <label htmlFor={id} className="block text-sm font-medium">
          Why it is no longer needed
        </label>
        <input
          id={id}
          name="reason"
          defaultValue={state.values.reason ?? ""}
          required
          aria-invalid={state.fieldErrors.reason ? true : undefined}
          className={`mt-1 w-full max-w-md rounded-md border bg-surface-raised px-3 py-2 text-sm ${
            state.fieldErrors.reason ? "border-critical" : "border-line"
          }`}
        />
        {state.fieldErrors.reason && (
          <p className="mt-1 text-xs text-critical">{state.fieldErrors.reason}</p>
        )}
      </div>
      <button
        type="submit"
        disabled={pending}
        className="rounded-md border border-critical/50 px-4 py-2 text-sm font-medium text-critical hover:bg-critical-soft disabled:opacity-60"
      >
        {pending ? "Cancelling…" : "Cancel this request"}
      </button>
    </form>
  );
}
