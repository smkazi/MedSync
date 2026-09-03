import Link from "next/link";
import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { Card, Empty, ErrorNote, Stat, formatDateTime } from "@/components/ui";
import type { Appointment, PortalBalance, PortalProfile, PortalReport } from "@/lib/types";

export const metadata = { title: "Your health record — MedSync" };

/**
 * The portal's front page: the four things a patient signs in to find out.
 *
 * <p>Every panel is a separate platform call to the service that owns the data, made in parallel.
 * None of them carries a patient id, because none of them can: the record shown is the one the
 * session's token names.
 *
 * <p>A failed panel is rendered as a failed panel and does not take the page with it. A portal that
 * went blank because the billing service was restarting would have a patient telephoning to ask
 * whether their appointment still exists.
 */
export default async function PortalHome() {
  const [profile, appointments, reports, balance, unread] = await Promise.all([
    load<PortalProfile>("/portal/me"),
    load<Appointment[]>("/portal/appointments"),
    load<PortalReport[]>("/portal/reports"),
    load<PortalBalance>("/portal/invoices/balance"),
    load<{ unread: number }>("/portal/messages/unread"),
  ]);

  // Compared as ISO strings rather than through Date.now(): the platform emits UTC instants, so
  // lexicographic order is chronological order, and the lint rule that forbids an impure call
  // during render is right — a component that reads the clock twice can disagree with itself.
  const nowIso = new Date().toISOString();
  const upcoming = (appointments.data ?? [])
    .filter((appointment) => appointment.startsAt >= nowIso)
    .filter((appointment) => appointment.status === "BOOKED" || appointment.status === "CHECKED_IN")
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  const ready = (reports.data ?? []).filter((report) => report.reportAvailable);
  // Named rather than indexed twice: the project runs with noUncheckedIndexedAccess, and
  // `upcoming[0]` is possibly-undefined however many times the length has been checked.
  const next = upcoming.at(0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">
          {profile.data ? `Hello, ${profile.data.firstName}` : "Your health record"}
        </h1>
        <p className="mt-1 text-sm text-ink-muted">
          {profile.data
            ? `Your hospital number is ${profile.data.mrn}. Please quote it when you telephone.`
            : "Signed in to the patient portal."}
        </p>
      </div>

      {profile.error ? <ErrorNote>{profile.error}</ErrorNote> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Next appointment"
          value={next ? formatDateTime(next.startsAt) : "None booked"}
          hint={next ? next.departmentCode : "You can book one below"}
        />
        <Stat
          label="Reports ready"
          value={ready.length}
          hint={ready.length > 0 ? "Checked and released" : "Nothing waiting"}
        />
        <Stat
          label="Outstanding"
          value={balance.data ? money(balance.data.outstanding) : "—"}
          hint={
            balance.data
              ? `${balance.data.unpaidInvoices} of ${balance.data.invoices} bill(s) unpaid`
              : balance.error ?? undefined
          }
        />
        <Stat
          label="Unread messages"
          value={unread.data ? unread.data.unread : 0}
          hint={unread.error ?? "From the hospital"}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card
          title="Your appointments"
          action={<Link href="/portal/appointments" className="text-sm underline">Open</Link>}
        >
          {appointments.error ? <ErrorNote>{appointments.error}</ErrorNote> : null}
          {upcoming.length === 0 ? (
            <Empty>Nothing booked. You can book into a clinic&apos;s published times.</Empty>
          ) : (
            <ul className="space-y-2 text-sm">
              {upcoming.slice(0, 4).map((appointment) => (
                <li key={appointment.id} className="flex justify-between gap-4">
                  <span>{formatDateTime(appointment.startsAt)}</span>
                  <span className="text-ink-muted">
                    {appointment.clinicianName ?? appointment.departmentCode}
                    {appointment.room ? ` · ${appointment.room.name}` : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card
          title="Test results"
          action={<Link href="/portal/results" className="text-sm underline">Open</Link>}
        >
          {reports.error ? <ErrorNote>{reports.error}</ErrorNote> : null}
          {(reports.data ?? []).length === 0 ? (
            <Empty>No laboratory tests on your record yet.</Empty>
          ) : (
            <ul className="space-y-2 text-sm">
              {(reports.data ?? []).slice(0, 4).map((report) => (
                <li key={report.orderId} className="flex justify-between gap-4">
                  <span>{report.tests.join(", ")}</span>
                  <span className="text-ink-muted">{report.progress}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {/* Said once, on the page everybody lands on, and again on every message thread. A portal
          is not a way to reach anybody quickly and the platform must not let anybody believe it
          is. */}
      <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
        This portal is not monitored continuously. If you are unwell or worried about something
        urgent, telephone the hospital or come to casualty.
      </p>
    </div>
  );
}
