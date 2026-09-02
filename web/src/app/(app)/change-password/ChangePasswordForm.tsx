"use client";

import { useActionState } from "react";
import { ErrorNote } from "@/components/ui";
import { changePassword } from "./actions";
import { EMPTY_PASSWORD_STATE, type PasswordState } from "./state";

/**
 * The change-password form.
 *
 * <p>Every field is uncontrolled and nothing is echoed back on a refusal, including by the action.
 * A password that has been typed once should not survive a round trip through React state or a
 * re-rendered `defaultValue`, and the small convenience of not retyping it is not worth the
 * chance of it landing in a serialised payload.
 *
 * <p>Works with JavaScript disabled: `useActionState` progressively enhances a plain
 * `<form action>`, and this is the screen an account is locked to, so it had better not need a
 * hydrated bundle to escape.
 */
export function ChangePasswordForm() {
  const [state, formAction, pending] = useActionState(changePassword, EMPTY_PASSWORD_STATE);

  return (
    <form action={formAction} className="mt-6 space-y-4">
      {state.error && <ErrorNote>{state.error}</ErrorNote>}

      <Secret
        name="currentPassword"
        label="Current password"
        autoComplete="current-password"
        state={state}
      />
      <Secret
        name="newPassword"
        label="New password"
        autoComplete="new-password"
        state={state}
        hint="At least 12 characters. It must differ from the current one."
      />
      <Secret
        name="confirmPassword"
        label="Confirm new password"
        autoComplete="new-password"
        state={state}
      />

      <button
        type="submit"
        disabled={pending}
        className="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-60"
      >
        {pending ? "Changing…" : "Change password"}
      </button>
      <p className="text-xs text-ink-muted">
        Changing it signs out every session on every device, including this one, so you will sign in
        again straight afterwards.
      </p>
    </form>
  );
}

function Secret({
  name,
  label,
  autoComplete,
  state,
  hint,
}: {
  name: string;
  label: string;
  autoComplete: string;
  state: PasswordState;
  hint?: string;
}) {
  const error = state.fieldErrors[name];
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={name}
        name={name}
        type="password"
        required
        autoComplete={autoComplete}
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
      ) : hint ? (
        <p id={`${name}-hint`} className="mt-1 text-xs text-ink-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
