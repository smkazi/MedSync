import { currentUser } from "@/lib/session";
import { ChangePasswordForm } from "./ChangePasswordForm";

/**
 * Change your own password.
 *
 * <p>Reachable at any time, and the only screen an account still on its initial password can
 * reach. That restriction is enforced by the platform, not by this page: such an account is issued
 * a token with no roles, so every other endpoint refuses it. The middleware redirect is a
 * courtesy on top — without it the account would see a fully drawn UI in which nothing worked.
 */
export default async function ChangePasswordPage() {
  const user = await currentUser();
  const forced = Boolean(user?.mustChangePassword);

  return (
    <div className="mx-auto mt-10 max-w-sm">
      <h1 className="text-xl font-semibold tracking-tight">Change your password</h1>
      {forced ? (
        <div
          role="alert"
          className="mt-3 rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn"
        >
          This account is still using the password it was issued with, so it can do nothing else
          yet. Whoever set it up knows that password; until it is changed, anything done with this
          account could have been done by them.
        </div>
      ) : (
        <p className="mt-1 text-sm text-ink-muted">
          Signed in as {user?.fullName ?? "this account"}.
        </p>
      )}

      <ChangePasswordForm />
    </div>
  );
}
