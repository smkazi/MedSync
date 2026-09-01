import Link from "next/link";
import { load } from "@/lib/load";
import type { Appointment } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";

/**
 * Appointments whose slot passed with nobody checking in.
 *
 * <p>A follow-up list, not a report. Each of these is a decision the front desk owes somebody: mark
 * it a no-show, or find out what happened. Leaving them BOOKED forever is how a clinic's no-show
 * rate becomes fiction — and the no-show model is trained on exactly this field.
 */
export default async function LapsedPage() {
  const { data: lapsed, error } = await load<Appointment[]>("/appointments/lapsed");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Lapsed appointments</h1>
        <p className="text-sm text-ink-muted">
          Booked, the slot has passed, and no one checked in. Each needs marking as a no-show or
          chasing.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {lapsed && (
        <Card title={`Awaiting a decision (${lapsed.length})`}>
          {lapsed.length === 0 ? (
            <Empty>Nothing lapsed. Every past slot has been resolved.</Empty>
          ) : (
            <Table head={["Slot", "MRN", "Clinician", "Dept", "Priority", "Reason"]}>
              {lapsed.map((appointment) => (
                <tr key={appointment.id}>
                  <td className="numeric whitespace-nowrap px-3 py-2">
                    {formatDateTime(appointment.startsAt)}
                  </td>
                  <td className="px-3 py-2">
                    <Link
                      href={`/patients?q=${encodeURIComponent(appointment.patientMrn)}`}
                      className="numeric text-accent hover:underline"
                    >
                      {appointment.patientMrn}
                    </Link>
                  </td>
                  <td className="px-3 py-2">{appointment.clinicianName ?? "—"}</td>
                  <td className="px-3 py-2 text-ink-muted">{appointment.departmentCode}</td>
                  <td className="px-3 py-2">
                    {appointment.priority === "ROUTINE" ? (
                      <span className="text-xs text-ink-muted">routine</span>
                    ) : (
                      <Badge tone="critical">{appointment.priority}</Badge>
                    )}
                  </td>
                  <td className="px-3 py-2 text-ink-muted">{appointment.reason ?? "—"}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        Marking a no-show is <span className="numeric">POST /appointments/{"{id}"}/no-show</span>,
        which the API only accepts once the slot has passed. The button for it arrives with the write
        screens.
      </p>
    </div>
  );
}
