import { load } from "@/lib/load";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";
import type { Appointment } from "@/lib/types";
import { BookingForm } from "./BookingForm";
import { cancelAppointment } from "./actions";

export const metadata = { title: "Your appointments — MedSync" };

/**
 * A patient's own appointments, and self-booking into published availability.
 *
 * <p>Past and future in one list, newest first, which is what the platform answers and what a
 * patient actually wants: "when did I last see somebody" is asked as often as "when am I next in".
 * Cancelling is offered only where the platform would allow it — a completed visit cannot be
 * cancelled and a button that answered 409 would be a button that lies.
 */
export default async function PortalAppointments({
  searchParams,
}: {
  searchParams: Promise<{ done?: string; problem?: string }>;
}) {
  const { done, problem } = await searchParams;
  const appointments = await load<Appointment[]>("/portal/appointments");
  const rows = appointments.data ?? [];

  // The clinicians this patient has already seen, as the booking list. Not a published directory
  // of everybody — the platform has no such screen — but "the doctor I saw last time" is the
  // commonest thing a patient wants to book with, and these ids are already on their own record.
  const clinicians = Array.from(
    new Map(
      rows
        .filter((appointment) => appointment.clinicianName)
        .map((appointment) => [
          appointment.clinicianId,
          { value: appointment.clinicianId, label: appointment.clinicianName as string },
        ]),
    ).values(),
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your appointments</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Everything booked for you, and a form to ask for another time.
        </p>
      </div>

      {problem ? <ErrorNote>{problem}</ErrorNote> : null}
      {done ? (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      ) : null}

      <Card title="Booked for you">
        {appointments.error ? <ErrorNote>{appointments.error}</ErrorNote> : null}
        {rows.length === 0 ? (
          <Empty>Nothing on your record yet.</Empty>
        ) : (
          <Table head={["When", "With", "Where", "Status", ""]}>
            {rows.map((appointment) => (
              <tr key={appointment.id} className="border-t border-line">
                <td className="px-3 py-2">{formatDateTime(appointment.startsAt)}</td>
                <td className="px-3 py-2">
                  {appointment.clinicianName ?? "—"}
                  <span className="block text-xs text-ink-muted">{appointment.departmentCode}</span>
                </td>
                <td className="px-3 py-2 text-sm">
                  {appointment.room ? (
                    <>
                      {appointment.room.name}
                      <span className="block text-xs text-ink-muted">
                        {appointment.room.floorName}
                        {appointment.room.directions ? ` · ${appointment.room.directions}` : ""}
                      </span>
                    </>
                  ) : (
                    <span className="text-xs text-ink-muted">To be confirmed</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(appointment.status)}>{appointment.status}</Badge>
                </td>
                <td className="px-3 py-2">
                  {appointment.status === "BOOKED" ? (
                    <form action={cancelAppointment} className="flex items-center gap-1">
                      <input type="hidden" name="appointmentId" value={appointment.id} />
                      <input
                        name="reason"
                        placeholder="reason"
                        aria-label="Why you are cancelling"
                        className="w-28 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                      />
                      <button
                        type="submit"
                        className="rounded border border-critical/40 px-2 py-1 text-xs text-critical hover:bg-critical-soft"
                      >
                        Cancel
                      </button>
                    </form>
                  ) : (
                    /* Only a booked appointment can be cancelled. The platform refuses the rest,
                       and a button that answered 409 would be a button that lies. */
                    <span className="text-xs text-ink-muted">—</span>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="Ask for an appointment">
        <BookingForm clinicians={clinicians} />
      </Card>
    </div>
  );
}
