import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { FormularyEntry, StockBatch } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";
import { receiveStock } from "../actions";

/**
 * Stock, by batch.
 *
 * <p>By batch rather than by drug because expiry is a property of a batch: "we have 400
 * paracetamol" is not a fact a pharmacy can act on. The list is ordered by expiry rather than by
 * name for the same reason — what is about to expire is the question this screen exists to answer,
 * and first-expiry-first-out only works if somebody can see it.
 */
export default async function StockPage({
  searchParams,
}: {
  searchParams: Promise<{ drugCode?: string; problem?: string; done?: string }>;
}) {
  const { drugCode = "", problem, done } = await searchParams;
  const mayReceive = hasRole(await currentUser(), "ADMIN", "PHARMACIST");

  const [stock, formulary] = await Promise.all([
    load<StockBatch[]>(`/pharmacy/stock${drugCode ? `?drugCode=${encodeURIComponent(drugCode)}` : ""}`),
    load<FormularyEntry[]>("/pharmacy/formulary"),
  ]);

  const batches = stock.data ?? [];
  const units = batches.filter((batch) => !batch.expired)
    .reduce((total, batch) => total + batch.quantityOnHand, 0);
  const expired = batches.filter((batch) => batch.expired);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Stock</h1>
        <p className="text-sm text-ink-muted">What is on the shelf, and when it expires.</p>
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
      {stock.error && <ErrorNote>{stock.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Batches" value={batches.length} hint="held" />
        <Stat label="Units" value={units} hint="usable, excluding expired" />
        <Stat
          label="Expired"
          value={expired.length}
          hint="cannot be dispensed"
        />
      </div>

      <Card
        title="Batches"
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="drugCode" className="text-xs text-ink-muted">
              Medicine
            </label>
            <select
              id="drugCode"
              name="drugCode"
              defaultValue={drugCode}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            >
              <option value="">all</option>
              {(formulary.data ?? []).map((entry) => (
                <option key={entry.code} value={entry.code}>
                  {entry.label}
                </option>
              ))}
            </select>
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {batches.length === 0 ? (
          <Empty>No stock is held{drugCode ? ` for ${drugCode}` : ""}.</Empty>
        ) : (
          <Table head={["Medicine", "Batch", "Expires", "In", "On hand", "Received"]}>
            {batches.map((batch) => (
              <tr key={batch.id} className={batch.expired ? "bg-critical-soft/30" : ""}>
                <td className="px-3 py-2">{batch.drugName ?? batch.drugCode}</td>
                <td className="numeric px-3 py-2">{batch.batchNo}</td>
                <td className="numeric px-3 py-2">{batch.expiresOn}</td>
                <td className="numeric px-3 py-2">
                  {batch.expired ? (
                    <Badge tone="critical">expired</Badge>
                  ) : (
                    <span className={batch.daysToExpiry <= 30 ? "font-semibold text-critical" : ""}>
                      {batch.daysToExpiry} day(s)
                    </span>
                  )}
                </td>
                <td className="numeric px-3 py-2">{batch.quantityOnHand}</td>
                <td className="numeric px-3 py-2 text-ink-muted">{batch.receivedOn}</td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          An expired batch is refused at the point of dispensing, whether the picker chose it or the
          platform did, and a batch that expires today counts as expired. There is no
          write-off or destruction record here yet — an expired batch stays visible with its
          quantity until somebody adjusts it, which the platform cannot do and the README says so.
        </p>
      </Card>

      {mayReceive && (
        <Card title="Receive a delivery">
          <RecordForm
            action={receiveStock}
            columns={2}
            submitLabel="Receive into stock"
            busyLabel="Receiving…"
            fields={[
              {
                name: "drugCode",
                label: "Medicine",
                type: "select",
                required: true,
                options: (formulary.data ?? [])
                  .filter((entry) => entry.active)
                  .map((entry) => ({ value: entry.code, label: entry.label })),
              },
              { name: "batchNo", label: "Batch number", required: true },
              {
                name: "expiresOn",
                label: "Expires on",
                type: "date",
                required: true,
                hint: "Must be in the future. Expired stock is not received: stock that cannot be dispensed is not stock.",
              },
              { name: "quantity", label: "Quantity", type: "number", required: true },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
