import Link from "next/link";
import { currentUser, hasRole } from "@/lib/session";
import { Badge } from "@/components/ui";

/**
 * The patient portal's chrome: its own navigation, its own header, no clinical menu.
 *
 * <p>A route group of its own rather than a section of the application, and the separation is the
 * point. `(app)`'s navigation is built from `menusFor`, which enumerates every module a hospital
 * runs; a patient must not be shown that list even filtered to nothing, because the shape of a
 * menu is itself a description of the building. So the portal has a different layout with six
 * links in it, and there is no code path that renders one inside the other.
 *
 * <p>The header carries no role badges either. A patient has exactly one role and it is not news
 * to them; what they need instead is a plain reminder of which record they are looking at, because
 * the commonest portal mistake is a parent signing in on a shared machine and reading the wrong
 * child's results.
 */
const LINKS: { href: string; label: string }[] = [
  { href: "/portal", label: "Overview" },
  { href: "/portal/appointments", label: "Appointments" },
  { href: "/portal/results", label: "Test results" },
  { href: "/portal/visits", label: "Visits" },
  { href: "/portal/medicines", label: "Medicines" },
  { href: "/portal/bills", label: "Bills" },
  { href: "/portal/messages", label: "Messages" },
  { href: "/portal/record", label: "My record" },
];

async function PortalNav() {
  const user = await currentUser();
  if (!user) return null;

  return (
    <header className="border-b border-line bg-surface-raised">
      <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-2 px-6 py-3">
        <Link href="/portal" className="text-base font-semibold tracking-tight">
          Med<span className="text-accent">Sync</span>
          <span className="ml-2 text-sm font-normal text-ink-muted">Patient portal</span>
        </Link>
        <nav className="flex flex-wrap items-center gap-4 text-sm">
          {LINKS.map((link) => (
            <Link key={link.href} href={link.href} className="text-ink-muted hover:text-ink hover:underline">
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="ml-auto flex items-center gap-3 text-sm">
          {/* Which record this is, in plain words. The commonest portal mistake is somebody
              signed in as the wrong family member. */}
          <span className="text-ink-muted">{user.fullName}</span>
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

export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const user = await currentUser();
  // Belt and braces: the middleware already keeps staff out of /portal, and every portal endpoint
  // is gated `hasRole('PATIENT')` in five services besides. This is the third layer and the
  // cheapest, and it exists because a routing rule is a redirect and not an authorisation.
  if (user && !hasRole(user, "PATIENT")) {
    return (
      <main className="mx-auto max-w-5xl px-6 py-10">
        <h1 className="text-lg font-semibold">This is the patient portal</h1>
        <p className="mt-2 text-sm text-ink-muted">
          Your account is a staff account, so there is no patient record for it to show. The
          clinical application is at <Link href="/" className="underline">the platform home page</Link>.
        </p>
        <div className="mt-4">
          <Badge tone="neutral">Signed in as staff</Badge>
        </div>
      </main>
    );
  }

  return (
    <>
      <PortalNav />
      <main className="mx-auto max-w-5xl px-6 py-6">{children}</main>
    </>
  );
}
