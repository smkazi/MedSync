import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime, statusTone } from "@/components/ui";
import type { Prescription } from "@/lib/types";

export const metadata = { title: "Your medicines — MedSync" };

/**
 * A patient's own prescriptions, in full.
 *
 * <p>The dose, the frequency and the instructions rather than a list of drug names, because those
 * three are the part most often misremembered on the way home and the reason a patient opens this
 * screen at all.
 *
 * <p>Read-only, and there is nothing to add. A repeat is a request to a prescriber, not a
 * prescription, and building it as one in the most dangerous module on the platform would be
 * building the wrong thing carefully.
 */
export default async function PortalMedicines() {
  const prescriptions = await load<Prescription[]>("/portal/prescriptions");
  const rows = prescriptions.data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your medicines</h1>
        <p className="mt-1 text-sm text-ink-muted">
          What you have been prescribed here, with the dose and the instructions as written.
        </p>
      </div>

      {prescriptions.error ? <ErrorNote>{prescriptions.error}</ErrorNote> : null}
      {rows.length === 0 ? (
        <Card title="Prescriptions">
          <Empty>Nothing has been prescribed to you here yet.</Empty>
        </Card>
      ) : (
        rows.map((prescription) => (
          <Card
            key={prescription.id}
            title={`Prescribed ${formatDateTime(prescription.issuedAt)} by ${prescription.prescriberName}`}
            action={<Badge tone={statusTone(prescription.status)}>{prescription.status}</Badge>}
          >
            <Table head={["Medicine", "Dose", "How often", "For", "Instructions"]}>
              {prescription.items.map((item) => (
                <tr key={item.id} className="border-t border-line">
                  <td className="px-3 py-2">{item.drugName}</td>
                  <td className="px-3 py-2">{item.dose}</td>
                  <td className="px-3 py-2">{item.frequency}</td>
                  <td className="px-3 py-2">{item.durationDays} day(s)</td>
                  <td className="px-3 py-2 text-ink-muted">{item.instructions ?? "—"}</td>
                </tr>
              ))}
            </Table>
          </Card>
        ))
      )}

      <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
        Do not change a dose because of anything on this screen. If a medicine is not agreeing with
        you, telephone the department that prescribed it.
      </p>
    </div>
  );
}
