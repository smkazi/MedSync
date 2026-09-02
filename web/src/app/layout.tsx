import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import { currentUser } from "@/lib/session";
import { menusFor } from "@/lib/menu";
import { MenuBar } from "@/components/MenuBar";
import { Badge } from "@/components/ui";

export const metadata: Metadata = {
  title: "MedSync",
  description: "Hospital management platform",
};

/**
 * Navigation is role-aware, and it filters rather than disables.
 *
 * <p>The menu is trimmed here, on the server, by {@link menusFor} — so an item the signed-in user
 * may not reach is never serialised into the page. A greyed-out item would disclose both what exists
 * and who else can get at it, which is not the front desk's business.
 *
 * <p>The structure itself lives in `src/lib/menu.ts`, one place that says what the application
 * contains. The five links this replaced had drifted a long way behind the platform.
 */
async function Nav() {
  const user = await currentUser();
  if (!user) return null;

  const menus = menusFor(user);

  return (
    <header className="border-b border-line bg-surface-raised">
      <div className="mx-auto flex max-w-7xl items-center gap-6 px-6 py-3">
        <Link href="/" className="text-base font-semibold tracking-tight">
          Med<span className="text-accent">Sync</span>
        </Link>
        <MenuBar menus={menus} />
        <div className="flex items-center gap-3 text-sm">
          <span className="text-ink-muted">{user.fullName}</span>
          {user.roles.map((role) => (
            <Badge key={role} tone="accent">
              {role}
            </Badge>
          ))}
          {/* Beside Sign out rather than in a menu: it belongs to the account, not to a module,
              and it is the one screen a locked-out account is sent to. */}
          <Link href="/change-password" className="text-ink-muted hover:text-ink hover:underline">
            Change password
          </Link>
          <form action="/api/auth/logout" method="post">
            <button
              type="submit"
              className="rounded-md border border-line px-3 py-1.5 text-sm hover:bg-surface"
            >
              Sign out
            </button>
          </form>
        </div>
      </div>
    </header>
  );
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">
        <Nav />
        {/* The initial-password banner used to live here. It is gone because it was the whole
            of the enforcement and it enforced nothing: the account carried on working, and an API
            client never saw the banner at all. Such an account is now issued a role-less token and
            the middleware sends it to /change-password, so a banner on every other screen would
            be advice about a screen the account cannot reach. */}
        <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
      </body>
    </html>
  );
}
