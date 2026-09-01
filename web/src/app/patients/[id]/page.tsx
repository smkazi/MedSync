import Link from "next/link";
import { notFound } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import type { Appointment, LabOrderSummary, Patient } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";

/**
 * The patient chart.
 *
 * A critical allergy is the first thing on the page, in the one colour this UI reserves for
 * danger. Encrypted identifiers are deliberately absent: they are served by a separate,
 * individually audited endpoint, not rendered on every chart view.
 */
export default async function PatientChart({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ registered?: string }>;
}) {
  const { id } = await params;
  const { registered } = await searchParams;

  let patient: Patient;
  try {
    patient = await api<Patient>(`/patients/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const [appointments, labOrders] = await Promise.all([
    api<Appointment[]>(`/appointments/patients/${id}`).catch(() => [] as Appointment[]),
    api<LabOrderSummary[]>(`/lab/patients/${id}/orders`).catch(() => [] as LabOrderSummary[]),
  ]);

  const criticalAllergies = patient.allergies.filter((allergy) => allergy.critical);

  return (
    <div className="space-y-6">
      {registered === "1" && (
        // `role="status"`, not `alert`: this is a confirmation. The allergy banner below is the
        // only thing on this page that interrupts a screen reader, and it stays that way.
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          Registered. MRN <span className="numeric font-medium">{patient.mrn}</span> was issued by
          the platform.
        </p>
      )}

      {criticalAllergies.length > 0 && (
        <div
          role="alert"
          className="rounded-lg border-2 border-critical bg-critical-soft px-4 py-3 text-critical"
        >
          <div className="text-xs font-semibold uppercase tracking-wide">Allergy alert</div>
          <div className="mt-1 font-semibold">
            {criticalAllergies
              .map((allergy) => `${allergy.substance} (${allergy.severity.replace("_", " ").toLowerCase()})`)
              .join(" · ")}
          </div>
          {criticalAllergies.some((allergy) => allergy.reaction) && (
            <div className="mt-0.5 text-sm">
              {criticalAllergies
                .filter((allergy) => allergy.reaction)
                .map((allergy) => allergy.reaction)
                .join("; ")}
            </div>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{patient.fullName}</h1>
          <p className="numeric text-sm text-ink-muted">
            {patient.mrn} · {patient.age}y {patient.sex.toLowerCase()} · born{" "}
            {patient.dateOfBirth}
            {patient.bloodGroup ? ` · ${patient.bloodGroup}` : ""}
          </p>
        </div>
        <div className="flex gap-2">
          {!patient.active && <Badge tone="neutral">archived</Badge>}
          {patient.deceased && <Badge tone="neutral">deceased</Badge>}
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Demographics">
          <dl className="space-y-2 text-sm">
            <Row label="Phone" value={patient.phone} />
            <Row label="Email" value={patient.email} />
            <Row label="City" value={patient.city} />
            <Row label="Insurance" value={patient.insuranceProvider} />
            <Row label="Next of kin" value={patient.emergencyContactName} />
            <Row label="Next of kin phone" value={patient.emergencyContactPhone} />
          </dl>
          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            National id and insurance policy number are encrypted at rest and released only through
            a separately audited request.
          </p>
        </Card>

        <Card title="Allergies">
          {patient.allergies.length === 0 ? (
            <Empty>No allergies recorded.</Empty>
          ) : (
            <ul className="space-y-2 text-sm">
              {patient.allergies.map((allergy) => (
                <li key={allergy.id} className="flex items-start justify-between gap-3">
                  <div>
                    <div className="font-medium">{allergy.substance}</div>
                    {allergy.reaction && (
                      <div className="text-xs text-ink-muted">{allergy.reaction}</div>
                    )}
                  </div>
                  <Badge tone={allergy.critical ? "critical" : "warn"}>
                    {allergy.severity.replace("_", " ").toLowerCase()}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title="Notes">
          {patient.notes ? (
            <p className="whitespace-pre-wrap text-sm">{patient.notes}</p>
          ) : (
            <Empty>No administrative notes.</Empty>
          )}
        </Card>
      </div>

      <Card title="Appointments">
        {appointments.length === 0 ? (
          <Empty>No appointments for this patient.</Empty>
        ) : (
          <Table head={["When", "Clinician", "Status", ""]}>
            {appointments.slice(0, 10).map((appointment) => (
              <tr key={appointment.id}>
                <td className="numeric px-3 py-2">{formatDateTime(appointment.startsAt)}</td>
                <td className="px-3 py-2">{appointment.clinicianName ?? "—"}</td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(appointment.status)}>{appointment.status}</Badge>
                </td>
                <td className="px-3 py-2 text-right">
                  {appointment.encounterId && (
                    <Link
                      href={`/encounters/${appointment.encounterId}`}
                      className="text-sm text-accent hover:underline"
                    >
                      Encounter
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="Laboratory">
        {labOrders.length === 0 ? (
          <Empty>No laboratory orders for this patient.</Empty>
        ) : (
          <Table head={["Ordered", "Accession", "Tests", "Results", "Status", ""]}>
            {labOrders.slice(0, 10).map((order) => (
              <tr key={order.id}>
                <td className="numeric px-3 py-2">{formatDateTime(order.orderedAt)}</td>
                <td className="numeric px-3 py-2">{order.accessionNo ?? "—"}</td>
                <td className="numeric px-3 py-2">{order.testCount}</td>
                <td className="numeric px-3 py-2">{order.resultCount}</td>
                <td className="px-3 py-2">
                  <div className="flex gap-1">
                    <Badge tone={statusTone(order.status)}>{order.status}</Badge>
                    {order.hasAbnormalResults && <Badge tone="critical">abnormal</Badge>}
                  </div>
                </td>
                <td className="px-3 py-2 text-right">
                  <Link href={`/laboratory/${order.id}`} className="text-sm text-accent hover:underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-muted">{label}</dt>
      <dd className="text-right">{value ?? "—"}</dd>
    </div>
  );
}
