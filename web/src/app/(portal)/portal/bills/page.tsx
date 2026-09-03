import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime, statusTone } from "@/components/ui";
import type { Invoice, PortalBalance } from "@/lib/types";

export const metadata = { title: "Your bills — MedSync" };

/**
 * A patient's own bills, line by line, with the tax shown.
 *
 * <p>Read-only, and the missing button is deliberate rather than unfinished. Taking money needs a
 * payment gateway with live merchant credentials, and a Pay-now button that settled an invoice
 * without receiving anything would balance the day book against money that does not exist — which
 * would be discovered at the month end by somebody unable to tell which of the two records was
 * wrong. Named as a gap in the README's Roadmap.
 *
 * <p>Cancelled bills are listed rather than hidden. A patient told they owed money and then told
 * they did not should be able to see both.
 */
export default async function PortalBills() {
  const [balance, invoices] = await Promise.all([
    load<PortalBalance>("/portal/invoices/balance"),
    load<Invoice[]>("/portal/invoices"),
  ]);
  const rows = invoices.data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your bills</h1>
        <p className="mt-1 text-sm text-ink-muted">
          What has been charged, what has been paid, and what is still owed.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat
          label="Still owed"
          value={balance.data ? money(balance.data.outstanding) : "—"}
          hint={balance.error ?? "Across every live bill"}
        />
        <Stat label="Unpaid bills" value={balance.data?.unpaidInvoices ?? 0} />
        <Stat label="Bills in total" value={balance.data?.invoices ?? 0} />
      </div>

      {invoices.error ? <ErrorNote>{invoices.error}</ErrorNote> : null}
      {rows.length === 0 ? (
        <Card title="Bills">
          <Empty>Nothing has been billed to you here.</Empty>
        </Card>
      ) : (
        rows.map((invoice) => (
          <Card
            key={invoice.id}
            title={`${invoice.number} · ${invoice.invoiceDate}`}
            action={<Badge tone={statusTone(invoice.status)}>{invoice.status}</Badge>}
          >
            <Table head={["What for", "Qty", "Price", "Discount", "Tax", "Line total"]}>
              {invoice.lines.map((line) => (
                <tr key={line.id} className="border-t border-line">
                  <td className="px-3 py-2">{line.description}</td>
                  <td className="px-3 py-2 tabular-nums">{line.qty}</td>
                  <td className="px-3 py-2 tabular-nums">{money(line.unitPrice)}</td>
                  <td className="px-3 py-2 tabular-nums">{money(line.discount)}</td>
                  <td className="px-3 py-2 tabular-nums">
                    {money(line.taxAmount)}
                    <span className="block text-xs text-ink-muted">{line.taxPercent}%</span>
                  </td>
                  <td className="px-3 py-2 tabular-nums font-medium">{money(line.lineTotal)}</td>
                </tr>
              ))}
            </Table>

            <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-4">
              <div>
                <dt className="text-ink-muted">Subtotal</dt>
                <dd className="tabular-nums">{money(invoice.subtotal)}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Tax</dt>
                <dd className="tabular-nums">{money(invoice.taxTotal)}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Total</dt>
                <dd className="tabular-nums font-medium">{money(invoice.total)}</dd>
              </div>
              <div>
                <dt className="text-ink-muted">Still owed</dt>
                <dd className="tabular-nums font-medium">{money(invoice.outstanding)}</dd>
              </div>
            </dl>

            {invoice.payments.length > 0 ? (
              <div className="mt-3">
                <h3 className="text-sm font-medium">Payments received</h3>
                <ul className="mt-1 space-y-1 text-sm text-ink-muted">
                  {invoice.payments.map((payment) => (
                    <li key={payment.id}>
                      {money(payment.amount)} by {payment.method} on{" "}
                      {formatDateTime(payment.receivedAt)}
                      {payment.reference ? ` · ${payment.reference}` : ""}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {invoice.cancelledReason ? (
              <p className="mt-3 text-sm text-ink-muted">
                This bill was cancelled: {invoice.cancelledReason}
              </p>
            ) : null}
          </Card>
        ))
      )}

      <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
        Payment is taken at the hospital&apos;s cash desk. This portal shows what is owed and does
        not take money.
      </p>
    </div>
  );
}
