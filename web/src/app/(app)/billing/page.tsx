import Link from "next/link";
import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { Invoice, PatientIdentity } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, statusTone } from "@/components/ui";

/**
 * What is owed.
 *
 * <p>Open invoices, oldest first — which is the service's ordering and not a column this screen
 * lets anybody re-sort, because the question a billing desk exists to answer is what has been
 * outstanding longest. A list ordered by newest first shows the money most likely to arrive on its
 * own and hides the money that will not.
 *
 * <p>An MRN switches the list to one patient's whole history, paid and cancelled included, because
 * that is the other question this screen is asked: not "who owes us" but "what has this person
 * been billed".
 */
export default async function BillingPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; problem?: string; done?: string }>;
}) {
  const { mrn = "", problem, done } = await searchParams;
  const user = await currentUser();
  const mayWrite = hasRole(user, "ADMIN", "CASHIER");
  // Whether to link an MRN to the patient register at all. A cashier may put a name to an MRN
  // through the narrow lookup and may not open the register, so offering them the link would be
  // offering a 403 — the same rule the menu follows: absent, not present and refused.
  const mayOpenRegister = hasRole(user, "ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH",
    "PATHOLOGIST");

  // The narrow lookup rather than the register: this screen needs an id for an MRN and a cashier
  // may not read demographics, which is the line PATIENT_IDENTIFY draws.
  const patients = mrn
    ? await load<PatientIdentity[]>(`/patients/identify?q=${encodeURIComponent(mrn)}`)
    : { data: null, error: null };
  const patient = (patients.data ?? [])[0];

  const invoices = await load<Invoice[]>(
    patient ? `/invoices?patientId=${patient.id}` : "/invoices",
  );
  const rows = invoices.data ?? [];
  const outstanding = rows.filter((invoice) => invoice.outstanding > 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Invoices</h1>
        <p className="text-sm text-ink-muted">
          {patient
            ? `Everything billed to ${patient.mrn}.`
            : "Every open bill, the one outstanding longest first."}
        </p>
      </div>

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}
      {invoices.error && <ErrorNote>{invoices.error}</ErrorNote>}
      {mrn && !patient && (
        <ErrorNote>No patient matches “{mrn}”. The list below is every open invoice.</ErrorNote>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Invoices" value={rows.length} hint={patient ? "for this patient" : "open"} />
        <Stat
          label="With a balance"
          value={outstanding.length}
          hint="something is still owed"
        />
        <Stat
          label="Oldest open"
          value={outstanding.length > 0 ? (outstanding[0]?.invoiceDate ?? "—") : "—"}
          hint="raised on"
        />
      </div>

      <Card
        title={patient ? `${patient.mrn}` : "Open invoices"}
        action={
          <div className="flex items-center gap-3">
            <form className="flex items-center gap-2">
              <label htmlFor="mrn" className="text-xs text-ink-muted">
                MRN
              </label>
              <input
                id="mrn"
                name="mrn"
                defaultValue={mrn}
                placeholder="MRN-2026-000001"
                className="w-40 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
              />
              <button
                type="submit"
                className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
              >
                Find
              </button>
            </form>
            {mayWrite && (
              <Link
                href="/billing/new"
                className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
              >
                Raise an invoice
              </Link>
            )}
          </div>
        }
      >
        {rows.length === 0 ? (
          <Empty>
            {patient ? "This patient has never been billed." : "Nothing is outstanding."}
          </Empty>
        ) : (
          <Table head={["Number", "Date", "MRN", "Payer", "Total", "Paid", "Outstanding", "", ""]}>
            {rows.map((invoice) => (
              <tr key={invoice.id}>
                <td className="numeric px-3 py-2">{invoice.number}</td>
                <td className="numeric px-3 py-2 text-ink-muted">{invoice.invoiceDate}</td>
                <td className="px-3 py-2">
                  {mayOpenRegister ? (
                    <Link
                      href={`/patients?q=${encodeURIComponent(invoice.patientMrn)}`}
                      className="underline"
                    >
                      {invoice.patientMrn}
                    </Link>
                  ) : (
                    invoice.patientMrn
                  )}
                </td>
                <td className="px-3 py-2 text-ink-muted">{invoice.payerCode ?? "self-paying"}</td>
                <td className="numeric px-3 py-2">{money(invoice.total)}</td>
                <td className="numeric px-3 py-2">{money(invoice.amountPaid)}</td>
                <td className="numeric px-3 py-2 font-semibold">
                  {invoice.outstanding > 0 ? money(invoice.outstanding) : "—"}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(invoice.status)}>{invoice.status.toLowerCase()}</Badge>
                </td>
                <td className="px-3 py-2">
                  <Link href={`/billing/${invoice.id}`} className="text-xs underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A draft is still collecting charges — an in-patient’s bed-days land on one nightly — and
          takes further lines. An issued invoice is a document somebody has been given and takes
          none: further charges go on a new invoice, because changing what a patient was asked to
          pay after they were asked is what a credit note is for, and this platform does not have
          one yet.
        </p>
      </Card>
    </div>
  );
}
