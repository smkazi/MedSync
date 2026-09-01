import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";
import { currentUser, hasRole } from "@/lib/session";
import { Badge } from "@/components/ui";

export const metadata: Metadata = {
  title: "MedSync",
  description: "Hospital management platform",
};

/** Navigation is role-aware: a receptionist is not shown a lab worklist they cannot act on. */
async function Nav() {
  const user = await currentUser();
  if (!user) return null;

  const links: { href: string; label: string; visible: boolean }[] = [
    { href: "/", label: "Dashboard", visible: true },
    { href: "/patients", label: "Patients", visible: true },
    { href: "/appointments", label: "Appointments", visible: true },
    {
      href: "/triage",
      label: "Triage",
      visible: hasRole(user, "ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST"),
    },
    {
      href: "/laboratory",
      label: "Laboratory",
      visible: hasRole(user, "ADMIN", "DOCTOR", "NURSE", "LAB_TECH", "PATHOLOGIST"),
    },
  ];

  return (
    <header className="border-b border-line bg-surface-raised">
      <div className="mx-auto flex max-w-7xl items-center gap-6 px-6 py-3">
        <Link href="/" className="text-base font-semibold tracking-tight">
          Med<span className="text-accent">Sync</span>
        </Link>
        <nav className="flex flex-1 gap-1">
          {links
            .filter((link) => link.visible)
            .map((link) => (
              <Link
                key={link.href}
                href={link.href}
                className="rounded-md px-3 py-1.5 text-sm text-ink-muted hover:bg-surface hover:text-ink"
              >
                {link.label}
              </Link>
            ))}
        </nav>
        <div className="flex items-center gap-3 text-sm">
          <span className="text-ink-muted">{user.fullName}</span>
          {user.roles.map((role) => (
            <Badge key={role} tone="accent">
              {role}
            </Badge>
          ))}
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
  const user = await currentUser();
  return (
    <html lang="en">
      <body className="min-h-dvh antialiased">
        <Nav />
        {user?.mustChangePassword && (
          <div className="border-b border-warn/30 bg-warn-soft px-6 py-2 text-center text-sm text-warn">
            This account is still using its initial password. Change it before recording clinical
            data.
          </div>
        )}
        <main className="mx-auto max-w-7xl px-6 py-6">{children}</main>
      </body>
    </html>
  );
}
