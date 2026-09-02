import { ErrorNote } from "@/components/ui";

/**
 * Sign-in.
 *
 * A plain form posting to a server route, so it works before any JavaScript loads and the password
 * never passes through client-side state.
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; done?: string }>;
}) {
  const { error, done } = await searchParams;

  return (
    <div className="mx-auto mt-16 max-w-sm">
      <h1 className="text-2xl font-semibold tracking-tight">
        Med<span className="text-accent">Sync</span>
      </h1>
      <p className="mt-1 text-sm text-ink-muted">Sign in to continue.</p>

      {done && (
        <p
          role="status"
          className="mt-4 rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}

      <form action="/api/auth/login" method="post" className="mt-6 space-y-4">
        {error && <ErrorNote>{error}</ErrorNote>}
        <div>
          <label htmlFor="username" className="block text-sm font-medium">
            Username
          </label>
          <input
            id="username"
            name="username"
            autoComplete="username"
            required
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="password" className="block text-sm font-medium">
            Password
          </label>
          <input
            id="password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="w-full rounded-md bg-accent px-3 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Sign in
        </button>
      </form>
    </div>
  );
}
