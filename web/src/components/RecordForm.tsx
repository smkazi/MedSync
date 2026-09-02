"use client";

import { useActionState, useId } from "react";
import { EMPTY_FORM_STATE, type FormState } from "@/lib/form";
import { ErrorNote } from "@/components/ui";

/**
 * One form for the administrative records.
 *
 * <p>Floors, rooms, room types, beds, departments, staff and users are seven flat sets of fields
 * that post to a service and re-read a list. Written out seven times that is seven places for the
 * same three mistakes — a field error rendered nowhere, a value not echoed after a refusal, a
 * checkbox that submits nothing when unticked — so they share one component and differ only in
 * their {@link Field} list.
 *
 * <p>Deliberately not extended to the clinical forms. Registration, charting and the allergy list
 * each carry a rule this cannot express: a duplicate rendered as candidate charts, a warning that
 * changes what the save button means, a confirmation step. A generic form that grew to cover those
 * would be worse than both.
 *
 * <p>Works with JavaScript disabled, like every other form here: `useActionState` progressively
 * enhances a plain `<form action>`.
 *
 * <p>Every input id is prefixed with a per-instance {@link useId}. A page here renders one of these
 * per table row plus one to add with, so ids taken straight from the field name collided — four
 * `id="name"` on the rooms screen, and a `<label for>` binds to the first match in the document.
 * Clicking the label on the fifth row focused the first row's input, and a screen reader announced
 * the same. The `name` attribute is still the plain field name, because that is what gets posted.
 */

export type Field = {
  name: string;
  label: string;
  /**
   * `select` and `multicheck` need `options`. `checkbox` posts "true" when ticked and a hidden
   * "false" when not; `multicheck` is a set of boxes sharing one name, and posts nothing at all
   * when none is ticked - which for a sparse update reads as "unchanged", not "none".
   */
  type?: "text" | "number" | "date" | "checkbox" | "select" | "textarea" | "multicheck";
  options?: readonly { value: string; label: string }[];
  /** For `multicheck`: the values already on the record. */
  values?: readonly string[];
  required?: boolean;
  hint?: string;
  /** The record's current value, for an edit form. */
  value?: string | number | boolean | null;
  placeholder?: string;
  step?: string;
};

export function RecordForm({
  action,
  fields,
  hidden,
  submitLabel,
  busyLabel,
  columns = 2,
}: {
  action: (previous: FormState, form: FormData) => Promise<FormState>;
  fields: readonly Field[];
  /** Values the service needs that the user does not choose — an id, a parent code. */
  hidden?: Record<string, string>;
  submitLabel: string;
  busyLabel?: string;
  columns?: 1 | 2 | 3;
}) {
  const [state, formAction, pending] = useActionState(action, EMPTY_FORM_STATE);
  const uid = useId();

  const grid =
    columns === 1 ? "sm:grid-cols-1" : columns === 2 ? "sm:grid-cols-2" : "sm:grid-cols-3";

  return (
    <form action={formAction} className="space-y-3">
      {Object.entries(hidden ?? {}).map(([name, value]) => (
        <input key={name} type="hidden" name={name} value={value} />
      ))}

      {state.error && <ErrorNote>{state.error}</ErrorNote>}
      {state.done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {state.done}
        </p>
      )}

      <div className={`grid gap-3 ${grid}`}>
        {fields.map((field) => (
          <Input key={field.name} field={field} state={state} uid={uid} />
        ))}
      </div>

      <button
        type="submit"
        disabled={pending}
        className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? (busyLabel ?? "Saving…") : submitLabel}
      </button>
    </form>
  );
}

function Input({ field, state, uid }: { field: Field; state: FormState; uid: string }) {
  const error = state.fieldErrors[field.name];
  const submitted = state.values[field.name];
  const border = error ? "border-critical" : "border-line";
  const base = `mt-1 w-full rounded-md border bg-surface-raised px-3 py-2 text-sm ${border}`;
  const id = `${uid}-${field.name}`;
  const describedBy = error ? `${id}-error` : field.hint ? `${id}-hint` : undefined;

  if (field.type === "checkbox") {
    // A ticked checkbox posts its value and an unticked one posts nothing at all, which for a
    // sparse PATCH would read as "leave it alone" rather than "set it false". So the field is
    // always present: the checkbox posts "true" when ticked, and the hidden twin posts "false".
    //
    // The order is load-bearing and was wrong first time round. `FormData.get()` returns the
    // *first* value for a repeated name, not the last, so with the hidden input written above the
    // checkbox every box read as false however it was set - a room type ticked clinical and
    // schedulable was created as neither, and the screen showed the truth while looking like it
    // had ignored the form. Checkbox first, twin second: ticked gives ["true", "false"] and reads
    // true, unticked gives ["false"] and reads false. `readForm` has a test that pins this.
    const checked = submitted !== undefined ? submitted === "true" : Boolean(field.value);
    return (
      <label className="flex items-center gap-2 self-end text-sm">
        <input
          type="checkbox"
          name={field.name}
          value="true"
          defaultChecked={checked}
          className="size-4 rounded border-line"
        />
        <input type="hidden" name={field.name} value="false" />
        <span>{field.label}</span>
      </label>
    );
  }

  if (field.type === "multicheck") {
    const already = new Set(field.values ?? []);
    return (
      <fieldset>
        <legend className="block text-sm font-medium">
          {field.label}
          {field.required && <span className="ml-0.5 text-accent">*</span>}
        </legend>
        <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
          {(field.options ?? []).map((option) => (
            <label key={option.value} className="flex items-center gap-1.5 text-sm">
              <input
                type="checkbox"
                name={field.name}
                value={option.value}
                defaultChecked={already.has(option.value)}
                className="size-4 rounded border-line"
              />
              <span>{option.label}</span>
            </label>
          ))}
        </div>
        {error ? (
          <p className="mt-1 text-xs text-critical">{error}</p>
        ) : field.hint ? (
          <p className="mt-1 text-xs text-ink-muted">{field.hint}</p>
        ) : null}
      </fieldset>
    );
  }

  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {field.label}
        {field.required && <span className="ml-0.5 text-accent">*</span>}
      </label>

      {field.type === "select" ? (
        <select
          id={id}
          name={field.name}
          required={field.required}
          defaultValue={submitted ?? String(field.value ?? "")}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={base}
        >
          <option value="">—</option>
          {(field.options ?? []).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      ) : field.type === "textarea" ? (
        <textarea
          id={id}
          name={field.name}
          rows={2}
          required={field.required}
          defaultValue={submitted ?? String(field.value ?? "")}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={base}
        />
      ) : (
        <input
          id={id}
          name={field.name}
          type={field.type ?? "text"}
          step={field.step}
          inputMode={field.type === "number" ? "decimal" : undefined}
          required={field.required}
          placeholder={field.placeholder}
          defaultValue={submitted ?? String(field.value ?? "")}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={field.type === "number" ? `numeric ${base}` : base}
        />
      )}

      {error ? (
        <p id={`${id}-error`} className="mt-1 text-xs text-critical">
          {error}
        </p>
      ) : field.hint ? (
        <p id={`${id}-hint`} className="mt-1 text-xs text-ink-muted">
          {field.hint}
        </p>
      ) : null}
    </div>
  );
}

/**
 * An edit form folded away behind a summary.
 *
 * <p>A native `<details>`, so a table of twenty rooms does not mount twenty open forms and the
 * disclosure needs no client state and no JavaScript.
 */
export function EditRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <details className="group">
      <summary className="cursor-pointer list-none text-xs text-accent hover:underline">
        {label}
      </summary>
      <div className="mt-3 rounded-md border border-line bg-surface p-3">{children}</div>
    </details>
  );
}
