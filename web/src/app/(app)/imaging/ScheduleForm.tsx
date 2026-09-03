"use client";

import { useActionState, useId } from "react";
import { EMPTY_IMAGING_STATE } from "./state";
import { scheduleExamination } from "./actions";

/**
 * Books one worklist row onto a modality.
 *
 * <p>One of these per row, so it has to be small: a label the eye can skip, a time, a button. The
 * heading above the column carries the word "Booked for", which is what the input means, so the
 * input's own label is for screen readers and for the test that has to find this row's field among
 * a dozen identical ones — hence the accession number in it.
 *
 * <p>Deliberately not {@link import("@/components/RecordForm").RecordForm}: that component renders
 * a card with a heading and a stacked field list, which is right for a page and absurd inside a
 * table cell.
 *
 * <p>Nothing is converted here. The typed time goes to the server action as typed and is read
 * there against the deployment's zone — see {@link scheduleExamination}. Doing the arithmetic in
 * the browser would read the console's clock instead, and a radiographer's console is not
 * necessarily set to the hospital's zone.
 *
 * <p>Works with JavaScript disabled: a plain `<form action>` with a native date-time input.
 */
export function ScheduleForm({
  orderId,
  accessionNo,
  scheduledFor,
}: {
  orderId: string;
  accessionNo: string;
  /** Set when the row is already booked — the button then says so. */
  scheduledFor?: string | null;
}) {
  const [state, formAction, pending] = useActionState(scheduleExamination, EMPTY_IMAGING_STATE);
  const id = useId();
  const error = state.fieldErrors.scheduledFor ?? state.error;

  return (
    <form action={formAction} className="flex items-start gap-2">
      <input type="hidden" name="orderId" value={orderId} />
      <div>
        <label htmlFor={id} className="sr-only">
          Slot for {accessionNo}
        </label>
        <input
          id={id}
          name="scheduledFor"
          type="datetime-local"
          defaultValue={state.values.scheduledFor ?? ""}
          aria-invalid={error ? true : undefined}
          className={`rounded border bg-surface-raised px-2 py-1 text-xs ${
            error ? "border-critical" : "border-line"
          }`}
        />
        {error && <p className="mt-1 max-w-[14rem] text-xs text-critical">{error}</p>}
        {state.done && (
          <p role="status" className="mt-1 text-xs text-good">
            {state.done}
          </p>
        )}
      </div>
      <button
        type="submit"
        disabled={pending}
        className="rounded border border-line px-2 py-1 text-xs font-medium hover:bg-surface disabled:opacity-60"
      >
        {pending ? "Booking…" : scheduledFor ? "Rebook" : "Book"}
      </button>
    </form>
  );
}
