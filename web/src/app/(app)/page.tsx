import Link from "next/link";
import { api, isAuthError } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { Appointment, LabOrderSummary, Page, Prescription } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Stat,
  Table,
  formatTime,
  statusTone,
} from "@/components/ui";

/**
 * The dashboard answers one question per role: what needs attention now.
 *
 * Every panel loads independently and degrades on its own. One service being down greys out its
 * card rather than blanking the screen — a clinician should still see today's clinic when the lab
 * is unreachable.
 */
export default async function Dashboard() {
  const user = await currentUser();
  // Each panel is fetched only for a role that may read it. The alternative — asking anyway and
  // rendering the refusal — is what this page used to do, and it gave the pharmacist, whose role
  // deliberately cannot see a clinic list, a dashboard of error notes on every sign-in.
  const maySeeClinic = hasRole(user, "ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH",
    "PATHOLOGIST");
  const maySeeMedicines = hasRole(user, "ADMIN", "DOCTOR", "NURSE", "PHARMACIST");
  const [appointments, labOrders, prescriptions] = await Promise.all([
    maySeeClinic
      ? load<Page<Appointment>>("/appointments?size=100")
      : Promise.resolve({ ok: true as const, data: null }),
    hasRole(user, "ADMIN", "DOCTOR", "NURSE", "LAB_TECH", "PATHOLOGIST")
      ? load<Page<LabOrderSummary>>("/lab/orders?size=100")
      : Promise.resolve({ ok: true as const, data: null }),
    maySeeMedicines
      ? load<Prescription[]>("/prescriptions")
      : Promise.resolve({ ok: true as const, data: null }),
  ]);

  const todays = appointments.ok && appointments.data ? appointments.data.content : [];
  const waiting = todays.filter((a) => a.status === "CHECKED_IN");
  const inProgress = todays.filter((a) => a.status === "IN_PROGRESS");
  const highRisk = todays.filter((a) => a.noShowRisk?.band === "HIGH");
  const orders = labOrders.ok && labOrders.data ? labOrders.data.content : [];
  const awaitingRelease = orders.filter((o) => o.status === "RESULTED");
  const abnormal = orders.filter((o) => o.hasAbnormalResults && o.status !== "VERIFIED");
  const queue = prescriptions.ok && prescriptions.data ? prescriptions.data : [];
  const linesToDispense = queue.reduce(
    (total, rx) => total + rx.items.filter((item) => item.outstanding > 0).length, 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Today</h1>
        <p className="text-sm text-ink-muted">
          {user?.fullName} · {new Date().toISOString().slice(0, 10)}
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Clinic today" value={todays.length} hint="booked, checked in or in progress" />
        <Stat label="Waiting" value={waiting.length} hint="checked in, not yet seen" />
        <Stat label="In consultation" value={inProgress.length} />
        <Stat
          label="Awaiting release"
          value={awaitingRelease.length}
          hint="results needing a pathologist"
        />
        {maySeeMedicines && (
          <Stat
            label="To dispense"
            value={linesToDispense}
            hint="medicines waiting at the pharmacy"
          />
        )}
      </div>

      {!appointments.ok && <ErrorNote>Appointments unavailable: {appointments.error}</ErrorNote>}

      <Card
        title="Clinic list"
        action={
          <Link href="/appointments" className="text-sm text-accent hover:underline">
            All appointments
          </Link>
        }
      >
        {todays.length === 0 ? (
          <Empty>Nothing booked for today.</Empty>
        ) : (
          <Table head={["Time", "MRN", "Clinician", "Status", "No-show risk", ""]}>
            {todays.slice(0, 10).map((appointment) => (
              <tr key={appointment.id}>
                <td className="numeric px-3 py-2">{formatTime(appointment.startsAt)}</td>
                <td className="numeric px-3 py-2">{appointment.patientMrn}</td>
                <td className="px-3 py-2">{appointment.clinicianName ?? "—"}</td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(appointment.status)}>{appointment.status}</Badge>
                </td>
                <td className="px-3 py-2">
                  {appointment.noShowRisk ? (
                    <Badge tone={appointment.noShowRisk.band === "HIGH" ? "warn" : "neutral"}>
                      {(appointment.noShowRisk.score * 100).toFixed(0)}%
                    </Badge>
                  ) : (
                    <span className="text-xs text-ink-muted">—</span>
                  )}
                </td>
                <td className="px-3 py-2 text-right">
                  <Link
                    href={`/patients/${appointment.patientId}`}
                    className="text-sm text-accent hover:underline"
                  >
                    Chart
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {highRisk.length > 0 && (
        <Card title="High no-show risk" tone="critical">
          <p className="mb-3 text-sm text-ink-muted">
            These appointments would benefit from a reminder or a confirmation call. The score is a
            scheduling aid — it must never affect the care offered.
          </p>
          <Table head={["Time", "MRN", "Risk"]}>
            {highRisk.map((appointment) => (
              <tr key={appointment.id}>
                <td className="numeric px-3 py-2">{formatTime(appointment.startsAt)}</td>
                <td className="numeric px-3 py-2">{appointment.patientMrn}</td>
                <td className="px-3 py-2">
                  <Badge tone="warn">
                    {((appointment.noShowRisk?.score ?? 0) * 100).toFixed(0)}%
                  </Badge>
                </td>
              </tr>
            ))}
          </Table>
        </Card>
      )}

      {abnormal.length > 0 && (
        <Card
          title="Abnormal results"
          action={
            <Link href="/laboratory" className="text-sm text-accent hover:underline">
              Laboratory
            </Link>
          }
        >
          <Table head={["Accession", "MRN", "Status", ""]}>
            {abnormal.slice(0, 8).map((order) => (
              <tr key={order.id}>
                <td className="numeric px-3 py-2">{order.accessionNo ?? "—"}</td>
                <td className="numeric px-3 py-2">{order.patientMrn}</td>
                <td className="px-3 py-2">
                  <Badge tone="critical">abnormal</Badge>
                </td>
                <td className="px-3 py-2 text-right">
                  <Link href={`/laboratory/${order.id}`} className="text-sm text-accent hover:underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        </Card>
      )}
    </div>
  );
}

/** Loads a panel's data, turning a failure into a value the page can render around. */
async function load<T>(
  path: string,
): Promise<{ ok: true; data: T } | { ok: false; error: string }> {
  try {
    return { ok: true, data: await api<T>(path) };
  } catch (error) {
    if (isAuthError(error)) {
      return { ok: false, error: "your session or role does not allow this" };
    }
    return { ok: false, error: error instanceof Error ? error.message : "unknown error" };
  }
}
