import Link from "next/link";
import { api } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { Appointment, Page } from "@/lib/types";
import { advanceAppointment, cancelAppointment } from "./actions";
import {
  Badge,
  ButtonLink,
  Card,
  Empty,
  ErrorNote,
  Table,
  formatDateTime,
  formatTime,
  statusTone,
} from "@/components/ui";

/**
 * The appointment book: a day's clinic, filterable by date and MRN, and now writable.
 *
 * <p>Each row's lifecycle buttons are plain forms posting to a server action, so they work with no
 * JavaScript and need no client component. Which buttons appear follows the status, but that is
 * presentation only — `Appointment.canTransitionTo` is the control, and an illegal move comes back
 * as a 409 whose reason is shown. A UI that decided what was legal would be a second, drifting copy
 * of a rule the service already owns.
 *
 * <p>The room column is the wayfinding the facility work was for: "General OPD · Ground Floor" with
 * the directions on hover. The code is stored on the appointment; the name and directions are
 * resolved live, so renaming a room does not strand old bookings with stale text.
 */
export default async function AppointmentsPage({
  searchParams,
}: {
  searchParams: Promise<{
    from?: string;
    to?: string;
    mrn?: string;
    status?: string;
    booked?: string;
    changed?: string;
    problem?: string;
  }>;
}) {
  const { from, to, mrn, status, booked, changed, problem } = await searchParams;
  const user = await currentUser();
  const mayBook = hasRole(user, "ADMIN", "RECEPTIONIST", "DOCTOR", "NURSE");
  const mayChart = hasRole(user, "ADMIN", "DOCTOR", "NURSE");
  const now = new Date();
  const today = now.toISOString().slice(0, 10);
  const fromDate = from ?? today;
  const toDate = to ?? fromDate;

  const params = new URLSearchParams({ size: "200", from: fromDate, to: toDate });
  if (mrn) params.set("mrn", mrn);
  // No status filter means the service's default: everything still needing attention.
  if (status) params.set("status", status);

  let results: Page<Appointment> | null = null;
  let error: string | null = null;
  try {
    results = await api<Page<Appointment>>(`/appointments?${params}`);
  } catch (caught) {
    error = caught instanceof Error ? caught.message : "Could not load appointments";
  }

  const appointments = results?.content ?? [];
  // The filters this list was rendered under, so a row action returns here rather than to today.
  const back = new URLSearchParams({ from: fromDate, to: toDate });
  if (mrn) back.set("mrn", mrn);
  if (status) back.set("status", status);

  return (
    <div className="space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Appointments</h1>
          <p className="text-sm text-ink-muted">
            {fromDate === toDate ? fromDate : `${fromDate} to ${toDate}`}
            {results ? ` · ${results.totalElements} appointments` : ""}
          </p>
        </div>
        {mayBook && <ButtonLink href="/appointments/new">Book an appointment</ButtonLink>}
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <Field label="From" name="from" type="date" defaultValue={fromDate} />
        <Field label="To" name="to" type="date" defaultValue={toDate} />
        <Field label="MRN" name="mrn" defaultValue={mrn ?? ""} placeholder="MRN-2026-…" />
        <div>
          <label htmlFor="status" className="block text-sm font-medium">
            Status
          </label>
          <select
            id="status"
            name="status"
            defaultValue={status ?? ""}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Needing attention</option>
            <option value="BOOKED">Booked</option>
            <option value="CHECKED_IN">Checked in</option>
            <option value="IN_PROGRESS">In progress</option>
            <option value="COMPLETED">Completed</option>
            <option value="CANCELLED">Cancelled</option>
            <option value="NO_SHOW">Did not attend</option>
          </select>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Apply
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}
      {problem && <ErrorNote>{problem}</ErrorNote>}
      {booked && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          Booked. The slot is now held, and the room with it.
        </p>
      )}
      {changed && !problem && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          Updated.
        </p>
      )}

      <Card title="Clinic">
        {appointments.length === 0 ? (
          <Empty>No appointments match these filters.</Empty>
        ) : (
          <Table
            head={["Time", "MRN", "Clinician", "Room", "Priority", "Status", "No-show", "", ""]}
          >
            {appointments.map((appointment) => (
              <tr key={appointment.id}>
                <td className="numeric px-3 py-2" title={formatDateTime(appointment.startsAt)}>
                  {formatTime(appointment.startsAt)}–{formatTime(appointment.endsAt)}
                </td>
                <td className="numeric px-3 py-2">
                  <Link
                    href={`/patients/${appointment.patientId}`}
                    className="text-accent hover:underline"
                  >
                    {appointment.patientMrn}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  {appointment.clinicianName ?? "—"}
                  <span className="block text-xs text-ink-muted">
                    {appointment.departmentCode}
                  </span>
                </td>
                <td className="px-3 py-2">
                  {appointment.room ? (
                    appointment.room.resolved ? (
                      <span title={appointment.room.directions ?? undefined}>
                        <span className="block">{appointment.room.name}</span>
                        <span className="block text-xs text-ink-muted">
                          {appointment.room.floorName}
                        </span>
                      </span>
                    ) : (
                      // The directory could not answer for this code. Show the code rather than
                      // implying there is no room.
                      <span className="numeric text-xs">{appointment.room.code}</span>
                    )
                  ) : (
                    <span className="text-xs text-ink-muted">—</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  {appointment.priority === "ROUTINE" ? (
                    <span className="text-xs text-ink-muted">routine</span>
                  ) : (
                    <Badge tone={statusTone(appointment.priority)}>{appointment.priority}</Badge>
                  )}
                </td>
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
                <td className="px-3 py-2">
                  <Lifecycle
                    id={appointment.id}
                    status={appointment.status}
                    endsAt={appointment.endsAt}
                    now={now.getTime()}
                    back={back}
                    mayBook={mayBook}
                    mayChart={mayChart}
                  />
                </td>
                <td className="px-3 py-2 text-right">
                  {appointment.encounterId ? (
                    <Link
                      href={`/encounters/${appointment.encounterId}`}
                      className="text-sm text-accent hover:underline"
                    >
                      Encounter
                    </Link>
                  ) : (
                    <Link
                      href={`/patients/${appointment.patientId}`}
                      className="text-sm text-accent hover:underline"
                    >
                      Chart
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}

function Field({
  label,
  name,
  type = "text",
  defaultValue,
  placeholder,
}: {
  label: string;
  name: string;
  type?: string;
  defaultValue?: string;
  placeholder?: string;
}) {
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        defaultValue={defaultValue}
        placeholder={placeholder}
        className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
      />
    </div>
  );
}

/**
 * The lifecycle buttons for one row.
 *
 * <p>Plain `<form>` elements posting to a server action — no client component, no JavaScript
 * required, and each button is a real submit rather than a link that mutates (a GET that changes
 * state is the thing a crawler or a prefetch will trigger for you).
 *
 * <p>Which buttons show follows the status, but only as presentation. `Appointment.canTransitionTo`
 * is the authority; pressing something the service refuses returns its reason, and that is what the
 * banner shows. Cancel carries a reason field because `cancelled_reason` is a column somebody will
 * later want to report on, and an empty one is a lost fact.
 */
function Lifecycle({
  id,
  status,
  endsAt,
  now,
  back,
  mayBook,
  mayChart,
}: {
  id: string;
  status: Appointment["status"];
  endsAt: string;
  /**
   * The instant the page was rendered, passed in rather than read here.
   *
   * <p>Two reasons. `react-hooks/purity` rightly refuses a clock read inside a component body; and
   * every row in one list should be judged against the same moment, not against a clock that moves
   * as the table renders.
   */
  now: number;
  /** The list's current filters, so the action can send the user back to the day they were on. */
  back: URLSearchParams;
  mayBook: boolean;
  mayChart: boolean;
}) {
  // The service refuses a no-show before the slot has ended - marking a patient absent while they
  // could still walk in would be a false record. Offering the button anyway would be a button that
  // can only fail, so it appears when it can succeed.
  const slotHasPassed = new Date(endsAt).getTime() < now;

  const steps: { step: string; label: string; allowed: boolean }[] = [
    { step: "check-in", label: "Check in", allowed: status === "BOOKED" && mayBook },
    { step: "start", label: "Start", allowed: status === "CHECKED_IN" && mayChart },
    { step: "complete", label: "Complete", allowed: status === "IN_PROGRESS" && mayChart },
    { step: "no-show", label: "No-show", allowed: status === "BOOKED" && mayBook && slotHasPassed },
  ].filter((candidate) => candidate.allowed);

  const cancellable = (status === "BOOKED" || status === "CHECKED_IN") && mayBook;

  if (steps.length === 0 && !cancellable) {
    return <span className="text-xs text-ink-muted">—</span>;
  }

  return (
    <div className="flex flex-wrap items-center gap-1">
      {steps.map((candidate) => (
        <form key={candidate.step} action={advanceAppointment}>
          <input type="hidden" name="id" value={id} />
          <input type="hidden" name="step" value={candidate.step} />
          <input type="hidden" name="back" value={back.toString()} />
          <button
            type="submit"
            className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
          >
            {candidate.label}
          </button>
        </form>
      ))}
      {cancellable && (
        <form action={cancelAppointment} className="flex items-center gap-1">
          <input type="hidden" name="id" value={id} />
          <input type="hidden" name="back" value={back.toString()} />
          <input
            name="reason"
            placeholder="reason"
            aria-label="Cancellation reason"
            className="w-24 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
          />
          <button
            type="submit"
            className="rounded border border-critical/40 px-2 py-1 text-xs text-critical hover:bg-critical-soft"
          >
            Cancel
          </button>
        </form>
      )}
    </div>
  );
}
