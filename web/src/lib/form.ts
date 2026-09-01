/**
 * Form state and the pure helpers around it.
 *
 * <p>Separate from `mutate.ts` for a build-level reason, not a stylistic one. A `"use client"`
 * component needs `EMPTY_FORM_STATE` and the `FormState` type; `mutate.ts` imports `api()`, which
 * imports the session cookie, which imports `next/headers`. Importing one value from that module
 * drags the whole chain into the browser bundle and the build fails with "You're importing a module
 * that depends on next/headers".
 *
 * <p>Worth noting that neither `tsc` nor ESLint catches it — only `next build` does. It is the
 * mirror image of the other rule this codebase learned the hard way: a constant may not live in a
 * `"use server"` module either. Server-only code, client-safe code, and the pure middle each need
 * their own file.
 */

/** The shape of a refused {@link import("./mutate").submit}. Defined here so `refused` stays pure. */
export type Refusal = {
  ok: false;
  status: number;
  /** The service's own message. Safe to render. */
  error: string;
  /** Bean Validation failures, keyed by the field name the service used. */
  fieldErrors: Record<string, string>;
  /** The parsed error body, for the handful of responses that carry more than a message. */
  body: unknown;
};

/**
 * What a form renders after a submit.
 *
 * <p>Lives here rather than in an action module because a `"use server"` file may only export async
 * functions: a plain object export from one type-checks, builds, and arrives as `undefined` at
 * render time. That cost an outage on `/patients/new` before the link-sweep test caught it.
 */
export type FormState = {
  /** Echoed back so nothing is retyped after a refusal. */
  values: Record<string, string>;
  fieldErrors: Record<string, string>;
  error: string | null;
  /** Set on success, for a form that stays on the page instead of redirecting. */
  done: string | null;
};

export const EMPTY_FORM_STATE: FormState = {
  values: {},
  fieldErrors: {},
  error: null,
  done: null,
};

/**
 * Reads the named fields off a submitted form, trimmed. Absent and blank are the same thing.
 *
 * <p>Generic over the field names so `values.mrn` is a `string`, not `string | undefined`. The
 * project runs with `noUncheckedIndexedAccess`, which is right for real index signatures and pure
 * noise for a record whose keys are a literal union known at the call site.
 */
export function readForm<K extends string>(
  form: FormData,
  fields: readonly K[],
): Record<K, string> {
  const values = {} as Record<K, string>;
  for (const field of fields) {
    values[field] = String(form.get(field) ?? "").trim();
  }
  return values;
}

/**
 * Drops the blanks from a set of form values.
 *
 * <p>An empty string is not "no value" to a service that validates with `@Pattern` or `@Email` — it
 * is a value that fails. A field somebody left alone must be absent from the JSON, not present and
 * empty.
 */
export function withoutBlanks(values: Record<string, string>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  for (const [field, value] of Object.entries(values)) {
    if (value !== "") body[field] = value;
  }
  return body;
}

/** Turns a failed {@link submit} into the state a form re-renders from. */
export function refused(
  values: Record<string, string>,
  failure: Refusal,
): FormState {
  return {
    values,
    fieldErrors: failure.fieldErrors,
    // When the service named specific fields, each input says its own piece; a banner repeating
    // "one or more fields are invalid" above them adds nothing.
    error: Object.keys(failure.fieldErrors).length > 0 ? null : failure.error,
    done: null,
  };
}
