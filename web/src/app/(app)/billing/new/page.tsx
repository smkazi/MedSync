import Link from "next/link";
import { load } from "@/lib/load";
import type { PatientIdentity, Payer } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Card, Empty, ErrorNote, Table } from "@/components/ui";
import { raiseInvoice } from "../actions";

/**
 * Raise an invoice.
 *
 * <p>Two steps, and the first one is not friction for its own sake: an invoice carries the
 * patient's id <em>and</em> their MRN, and a cashier typing an MRN into a field is a cashier who
 * can mistype one. So the MRN is searched, the patient is chosen from what came back, and the id
 * travels hidden — a bill raised against the wrong person is a bill somebody else is asked to pay.
 *
 * <p>The date is the invoice's own and defaults to today. It decides the tax: an invoice for
 * treatment given last quarter is taxed at the rate that applied then, not the rate in force this
 * morning, and back-dating it here is how that is done rather than by a rate somebody edits.
 */
export default async function NewInvoicePage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; patientId?: string }>;
}) {
  const { mrn = "", patientId = "" } = await searchParams;

  const [patients, payers] = await Promise.all([
    // The narrow lookup, not the register. A cashier may put a name to an MRN and may not read
    // demographics, so `/patients?q=` would answer 403 for the very role this screen is for.
    mrn
      ? load<PatientIdentity[]>(`/patients/identify?q=${encodeURIComponent(mrn)}`)
      : Promise.resolve({ data: null, error: null }),
    load<Payer[]>("/payers"),
  ]);

  const found = patients.data ?? [];
  const chosen = found.find((patient) => patient.id === patientId);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Raise an invoice</h1>
        <p className="text-sm text-ink-muted">
          Find the patient, then say who is paying. The charges go on next.
        </p>
      </div>

      {patients.error && <ErrorNote>{patients.error}</ErrorNote>}
      {payers.error && <ErrorNote>{payers.error}</ErrorNote>}

      <Card title="1. The patient">
        <form className="flex flex-wrap items-end gap-2">
          <div>
            <label htmlFor="mrn" className="block text-sm font-medium">
              MRN or name
            </label>
            <input
              id="mrn"
              name="mrn"
              defaultValue={mrn}
              placeholder="MRN-2026-000001"
              className="mt-1 w-56 rounded border border-line bg-surface-raised px-2 py-1 text-sm"
            />
          </div>
          <button
            type="submit"
            className="rounded border border-line px-3 py-1.5 text-sm hover:bg-surface"
          >
            Search
          </button>
        </form>

        {mrn && (
          <div className="mt-4">
            {found.length === 0 ? (
              <Empty>Nobody matches “{mrn}”.</Empty>
            ) : (
              <Table head={["MRN", "Name", "", ""]}>
                {found.map((patient) => (
                  <tr key={patient.id} className={patient.id === patientId ? "bg-accent-soft/40" : ""}>
                    <td className="numeric px-3 py-2">{patient.mrn}</td>
                    <td className="px-3 py-2">{patient.fullName}</td>
                    <td className="px-3 py-2 text-ink-muted">
                      {patient.active ? "" : "archived"}
                    </td>
                    <td className="px-3 py-2">
                      <Link
                        href={`/billing/new?mrn=${encodeURIComponent(mrn)}&patientId=${patient.id}`}
                        className="text-xs underline"
                      >
                        {patient.id === patientId ? "Chosen" : "Bill this patient"}
                      </Link>
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </div>
        )}
      </Card>

      {chosen && (
        <Card title={`2. Who is paying for ${chosen.mrn}`}>
          <RecordForm
            action={raiseInvoice}
            hidden={{ patientId: chosen.id, patientMrn: chosen.mrn }}
            submitLabel="Raise the invoice"
            busyLabel="Raising…"
            fields={[
              {
                name: "payerCode",
                label: "Payer",
                type: "select",
                hint: "Leave as self-paying when the patient pays at the desk",
                options: [
                  { value: "", label: "— self-paying —" },
                  ...(payers.data ?? [])
                    .filter((payer) => payer.active)
                    .map((payer) => ({
                      value: payer.code,
                      label: `${payer.name} (${payer.code})`,
                    })),
                ],
              },
              {
                name: "invoiceDate",
                label: "Invoice date",
                type: "date",
                hint: "Decides the tax rate and the number series. Today unless the treatment was earlier.",
              },
              {
                name: "encounterId",
                label: "Encounter id",
                hint: "Optional. Charges captured from that encounter join this invoice instead of opening their own.",
              },
            ]}
          />
        </Card>
      )}

      <p className="text-xs text-ink-muted">
        A payer with an agreed tariff prices from it rather than from the charge list — that is what
        a tariff is for, and billing them the list price is a claim that will be short-paid. A
        tax-exempt payer exempts every line whatever the charge item says, because an exemption is a
        property of who is paying.
      </p>
    </div>
  );
}
