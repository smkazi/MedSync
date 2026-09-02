"use client";

import { useActionState, useId, useState } from "react";
import { ErrorNote } from "@/components/ui";
import { EMPTY_LAB_STATE } from "../state";
import { enterResults } from "../actions";

/**
 * Hand entry of a panel's results.
 *
 * <p>Deliberately not {@link import("@/components/RecordForm").RecordForm}. That component is a
 * flat list of named fields and has no repeating-group concept, and its own header says it is not
 * extended to forms carrying a rule it cannot express. This one is N parameter rows — every row
 * three inputs sharing one name with every other row's — which is exactly that rule.
 *
 * <p>The values are React state rather than uncontrolled inputs, and that is not a stylistic
 * choice. React resets an uncontrolled form's fields once its action completes, so a refused batch
 * would silently blank a bench worth of typing and leave the technician re-reading the analyzer
 * printout. The same failure was found on the allergy form, where a post-action reset turned a
 * life-threatening allergy into a moderate one.
 *
 * <p>Still works with JavaScript disabled: the server renders each input's `value`, the browser
 * lets it be typed over, and the plain `<form action>` posts.
 */

export type ResultRow = {
  parameter: string;
  displayName: string;
  /** Prefilled from the laboratory's reference interval for this parameter and the patient's sex. */
  unit: string;
  /** The pre-formatted interval, shown beside the input so a number has its context. */
  referenceRange: string;
  /** Set when a result already exists — re-entering it amends rather than duplicates. */
  existing: string | null;
};

export function ResultsForm({ orderId, rows }: { orderId: string; rows: readonly ResultRow[] }) {
  const [state, formAction, pending] = useActionState(enterResults, EMPTY_LAB_STATE);
  const uid = useId();

  // Seeded from what is already recorded, so an amendment starts from the current number rather
  // than from nothing. Holding it here is also what survives a refusal: React resets an
  // uncontrolled field once the action settles, but it does not touch component state.
  const [entered, setEntered] = useState<Record<string, string>>(() =>
    Object.fromEntries(rows.map((row) => [row.parameter, row.existing ?? ""])),
  );

  const amending = rows.filter((row) => row.existing !== null);

  return (
    <form action={formAction} className="space-y-3">
      <input type="hidden" name="orderId" value={orderId} />

      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {state.done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {state.done}
        </p>
      )}

      {amending.length > 0 && (
        <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-xs text-warn">
          {amending.length === 1
            ? `${amending[0]?.displayName} already has a result.`
            : `${amending.length} of these parameters already have results.`}{" "}
          Entering a value again amends the existing result and records the change — it does not add
          a second row.
        </p>
      )}

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-ink-muted">
              <th className="px-2 py-1.5 font-medium">Parameter</th>
              <th className="px-2 py-1.5 font-medium">Value</th>
              <th className="px-2 py-1.5 font-medium">Unit</th>
              <th className="px-2 py-1.5 font-medium">Reference</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const id = `${uid}-${row.parameter}`;
              const error = state.fieldErrors[row.parameter];
              return (
                <tr key={row.parameter} className="border-b border-line/60 last:border-0">
                  <td className="px-2 py-1.5">
                    <label htmlFor={id} className="font-medium">
                      {row.displayName}
                    </label>
                    {/*
                      The parameter travels as a hidden input rather than as a readonly text box.
                      It is not the technician's to change - it comes from the ordered test's
                      catalogue entry - and the row's position is what pairs it with its value.
                    */}
                    <input type="hidden" name="parameter" value={row.parameter} />
                  </td>
                  <td className="px-2 py-1.5">
                    <input
                      id={id}
                      name="value"
                      inputMode="decimal"
                      autoComplete="off"
                      value={entered[row.parameter] ?? ""}
                      onChange={(event) =>
                        setEntered((current) => ({
                          ...current,
                          [row.parameter]: event.target.value,
                        }))
                      }
                      aria-invalid={error ? true : undefined}
                      className={`numeric w-28 rounded border bg-surface-raised px-2 py-1 text-sm ${
                        error ? "border-critical" : "border-line"
                      }`}
                    />
                    {error && <p className="mt-1 text-xs text-critical">{error}</p>}
                  </td>
                  <td className="px-2 py-1.5">
                    <input
                      name="unit"
                      defaultValue={row.unit}
                      autoComplete="off"
                      aria-label={`${row.displayName} unit`}
                      className="w-24 rounded border border-line bg-surface-raised px-2 py-1 text-sm"
                    />
                  </td>
                  <td className="numeric px-2 py-1.5 text-xs text-ink-muted">
                    {row.referenceRange || "—"}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <p className="text-xs text-ink-muted">
        Leave a parameter blank and it is not recorded at all. An unmeasured parameter and one
        measured as blank are different facts, and only the first is honest about what the bench
        actually did.
      </p>

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Recording…" : "Record results"}
      </button>
    </form>
  );
}
