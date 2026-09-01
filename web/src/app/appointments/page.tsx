import Link from "next/link";
import { api } from "@/lib/api";
import type { Appointment, Page } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Table,
  formatDateTime,
  formatTime,
  statusTone,
} from "@/components/ui";

/** The appointment book: a day's clinic, filterable by date and MRN. */
export default async function AppointmentsPage({
  searchParams,
}: {
  searchParams: Promise<{ from?: string; to?: string; mrn?: string; status?: string }>;
}) {
  const { from, to, mrn, status } = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
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

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Appointments</h1>
        <p className="text-sm text-ink-muted">
          {fromDate === toDate ? fromDate : `${fromDate} to ${toDate}`}
          {results ? ` · ${results.totalElements} appointments` : ""}
        </p>
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

      <Card title="Clinic">
        {appointments.length === 0 ? (
          <Empty>No appointments match these filters.</Empty>
        ) : (
          <Table
            head={["Time", "MRN", "Clinician", "Dept", "Priority", "Status", "No-show", ""]}
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
                <td className="px-3 py-2">{appointment.clinicianName ?? "—"}</td>
                <td className="px-3 py-2">{appointment.departmentCode}</td>
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
