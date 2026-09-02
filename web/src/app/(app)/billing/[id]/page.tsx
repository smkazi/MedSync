import Link from "next/link";
import { notFound } from "next/navigation";
import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { ChargeItem, Claim, Invoice } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime, statusTone } from "@/components/ui";
import { PAYMENT_METHODS } from "../state";
import { addLine, cancelInvoice, issueInvoice, raiseClaim, takePayment } from "../actions";

/**
 * One invoice: what is on it, what has been paid, and what is left to do.
 *
 * <p>Which forms appear is decided by the invoice's own state rather than by a role alone, because
 * the states mean different things. Lines go on a draft and not on an issued bill. A payment is
 * taken against an issued bill and not against a draft nobody has been given. A cancellation is
 * offered only while no money has arrived, because cancelling a paid invoice would make the record
 * say a treatment was never billed while the cash is in the drawer — and the platform has no
 * refund, which the note at the bottom says out loud.
 */
export default async function InvoicePage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ problem?: string; done?: string }>;
}) {
  const { id } = await params;
  const { problem, done } = await searchParams;
  const mayWrite = hasRole(await currentUser(), "ADMIN", "CASHIER");

  const [invoice, chargeItems, claims] = await Promise.all([
    load<Invoice>(`/invoices/${id}`),
    load<ChargeItem[]>("/charge-items"),
    load<Claim[]>("/claims?includeClosed=true"),
  ]);

  if (!invoice.data) {
    if (invoice.error?.includes("No invoice")) {
      notFound();
    }
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Invoice</h1>
        <ErrorNote>{invoice.error ?? "This invoice could not be read."}</ErrorNote>
      </div>
    );
  }

  const bill = invoice.data;
  const claim = (claims.data ?? []).find((row) => row.invoiceId === bill.id);
  const isDraft = bill.status === "DRAFT";
  const takesPayment = bill.status === "ISSUED" || bill.status === "DRAFT";
  const claimable = bill.status === "ISSUED" && bill.payerCode !== null && !claim;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">{bill.number}</h1>
          <p className="text-sm text-ink-muted">
            {bill.patientMrn} · raised {bill.invoiceDate} ·{" "}
            {bill.payerCode ?? "self-paying"}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge tone={statusTone(bill.status)}>{bill.status.toLowerCase()}</Badge>
          <Link href="/billing" className="text-xs underline">
            All invoices
          </Link>
        </div>
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
      {bill.cancelledReason && (
        <ErrorNote>
          Cancelled {formatDateTime(bill.cancelledAt)}: {bill.cancelledReason}
        </ErrorNote>
      )}

      <div className="grid gap-4 sm:grid-cols-4">
        <Stat label="Subtotal" value={money(bill.subtotal)} hint="before tax" />
        <Stat label="Tax" value={money(bill.taxTotal)} hint="at the rates on this invoice's date" />
        <Stat label="Total" value={money(bill.total)} hint="payable" />
        <Stat label="Outstanding" value={money(bill.outstanding)} hint={`${money(bill.amountPaid)} collected`} />
      </div>

      <Card title="What is being charged for">
        {bill.lines.length === 0 ? (
          <Empty>Nothing has been charged yet.</Empty>
        ) : (
          <Table head={["Code", "Description", "Qty", "Unit", "Discount", "Tax", "Line total"]}>
            {bill.lines.map((line) => (
              <tr key={line.id}>
                <td className="numeric px-3 py-2">{line.chargeItemCode}</td>
                <td className="px-3 py-2">{line.description}</td>
                <td className="numeric px-3 py-2">{line.qty}</td>
                <td className="numeric px-3 py-2">{money(line.unitPrice)}</td>
                <td className="numeric px-3 py-2">{line.discount > 0 ? money(line.discount) : "—"}</td>
                <td className="numeric px-3 py-2 text-ink-muted">
                  {line.taxPercent > 0 ? `${money(line.taxAmount)} (${line.taxPercent}%)` : "exempt"}
                </td>
                <td className="numeric px-3 py-2 font-semibold">{money(line.lineTotal)}</td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          Each price is the one that applied when the line was added, kept on the line rather than
          looked up again. A financial record must not change after the fact, which is the
          deliberate opposite of how a room’s directions work elsewhere in this platform.
        </p>
      </Card>

      {mayWrite && isDraft && (
        <Card title="Add a charge">
          <RecordForm
            action={addLine}
            hidden={{ invoiceId: bill.id }}
            submitLabel="Add the charge"
            busyLabel="Adding…"
            columns={2}
            fields={[
              {
                name: "chargeItemCode",
                label: "Charge item",
                type: "select",
                required: true,
                options: [
                  { value: "", label: "— pick one —" },
                  ...(chargeItems.data ?? [])
                    .filter((item) => item.active)
                    .map((item) => ({
                      value: item.code,
                      label: `${item.name} — ${money(item.unitPrice)}${item.taxable ? " + tax" : ""}`,
                    })),
                ],
              },
              { name: "qty", label: "Quantity", type: "number", required: true, step: "0.01" },
              {
                name: "discount",
                label: "Discount",
                type: "number",
                step: "0.01",
                hint: "An amount off this line, not a percentage. It cannot exceed the line.",
              },
              {
                name: "description",
                label: "Description",
                hint: "Optional. The charge item's own name is used when this is left blank.",
              },
            ]}
          />
        </Card>
      )}

      {mayWrite && (isDraft || takesPayment) && (
        <div className="grid gap-4 lg:grid-cols-2">
          {isDraft && (
            <Card title="Issue it">
              <p className="text-sm text-ink-muted">
                Issuing turns a draft into the document a patient is asked to pay. No further lines
                can be added afterwards.
              </p>
              <form action={issueInvoice} className="mt-3">
                <input type="hidden" name="invoiceId" value={bill.id} />
                <button
                  type="submit"
                  disabled={bill.lines.length === 0}
                  className="rounded bg-accent px-3 py-1.5 text-sm text-white disabled:opacity-40"
                >
                  Issue {money(bill.total)}
                </button>
                {bill.lines.length === 0 && (
                  <p className="mt-2 text-xs text-ink-muted">
                    An invoice with no lines is not a bill.
                  </p>
                )}
              </form>
            </Card>
          )}

          {takesPayment && bill.outstanding > 0 && (
            <Card title="Take a payment">
              <RecordForm
                action={takePayment}
                hidden={{ invoiceId: bill.id }}
                submitLabel="Record the payment"
                busyLabel="Recording…"
                columns={1}
                fields={[
                  {
                    name: "amount",
                    label: "Amount",
                    type: "number",
                    required: true,
                    step: "0.01",
                    hint: `${money(bill.outstanding)} is outstanding. More than that is refused rather than held as a credit.`,
                  },
                  {
                    name: "method",
                    label: "How it arrived",
                    type: "select",
                    required: true,
                    options: [{ value: "", label: "— pick one —" }, ...PAYMENT_METHODS],
                  },
                  {
                    name: "reference",
                    label: "Reference",
                    hint: "The UPI reference or card slip number somebody can look up later",
                  },
                ]}
              />
            </Card>
          )}
        </div>
      )}

      <Card title="Payments">
        {bill.payments.length === 0 ? (
          <Empty>Nothing has been collected.</Empty>
        ) : (
          <Table head={["Received", "Amount", "Method", "Reference", "By"]}>
            {bill.payments.map((payment) => (
              <tr key={payment.id}>
                <td className="numeric px-3 py-2">{formatDateTime(payment.receivedAt)}</td>
                <td className="numeric px-3 py-2 font-semibold">{money(payment.amount)}</td>
                <td className="px-3 py-2">{payment.method.toLowerCase().replace("_", " ")}</td>
                <td className="px-3 py-2 text-ink-muted">{payment.reference ?? "—"}</td>
                <td className="px-3 py-2 text-ink-muted">{payment.receivedBy}</td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {claim && (
        <Card title="The claim">
          <div className="grid gap-4 sm:grid-cols-4">
            <Stat label="Payer" value={claim.payerCode} hint={claim.preauthNo ?? "no pre-auth"} />
            <Stat label="Claimed" value={money(claim.claimedAmount)} hint={claim.status.toLowerCase()} />
            <Stat label="Settled" value={money(claim.settledAmount)} hint="received from the payer" />
            <Stat label="Shortfall" value={money(claim.shortfall)} hint="patient's or written off" />
          </div>
          {claim.denialReason && <ErrorNote>Denied: {claim.denialReason}</ErrorNote>}
          <Link href="/billing/claims" className="mt-3 inline-block text-xs underline">
            Work the claim
          </Link>
        </Card>
      )}

      {mayWrite && claimable && (
        <Card title="Claim from the payer">
          <RecordForm
            action={raiseClaim}
            hidden={{ invoiceId: bill.id }}
            submitLabel="Raise the claim"
            busyLabel="Raising…"
            columns={1}
            fields={[
              {
                name: "preauthNo",
                label: "Pre-authorisation number",
                hint: "Required when the payer says so, and refused without one. The claim is for what is outstanding, so a co-pay already collected is not claimed twice.",
              },
            ]}
          />
        </Card>
      )}

      {mayWrite && bill.amountPaid === 0 && bill.status !== "CANCELLED" && (
        <Card title="Cancel it">
          <form action={cancelInvoice} className="flex flex-wrap items-end gap-2">
            <input type="hidden" name="invoiceId" value={bill.id} />
            <div className="grow">
              <label htmlFor="reason" className="block text-sm font-medium">
                Why
              </label>
              <input
                id="reason"
                name="reason"
                required
                placeholder="Raised against the wrong encounter"
                className="mt-1 w-full rounded border border-line bg-surface-raised px-2 py-1 text-sm"
              />
            </div>
            <button
              type="submit"
              className="rounded border border-critical/50 px-3 py-1.5 text-sm text-critical hover:bg-critical-soft"
            >
              Cancel the invoice
            </button>
          </form>
          <p className="mt-3 text-xs text-ink-muted">
            Only while nothing has been collected. Once money has arrived the honest correction is a
            refund and a credit note, and this platform has neither — the README says so rather than
            letting a cancellation quietly stand in for one.
          </p>
        </Card>
      )}
    </div>
  );
}
