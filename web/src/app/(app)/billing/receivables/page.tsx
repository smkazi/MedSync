import Link from "next/link";
import { load } from "@/lib/load";
import { money } from "@/lib/money";
import type { Receivables } from "@/lib/types";
import { Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";

/**
 * What is owed, by how long it has been owed and by whom.
 *
 * <p>The day book answers the receivable in one number, which is the right number for a cash-up and
 * the wrong one for collecting: this week's billing and a claim a payer has sat on since March are
 * the same rupee on that line and are chased in completely different ways. Splitting it by age is
 * what turns a figure into a list of calls to make.
 *
 * <p>Ordered worst first, by the service, and read from the top. A list sorted by payer name puts
 * the oldest debt wherever the alphabet happens to put it.
 *
 * <p>The buckets say how long since the bill was raised, not how overdue it is. The platform has no
 * due date and inventing one here would be a credit term nobody agreed — so the columns are named
 * for what they measure, and a deployment with real terms would age against those instead.
 */
export default async function ReceivablesPage({
  searchParams,
}: {
  searchParams: Promise<{ on?: string }>;
}) {
  const { on = "" } = await searchParams;
  const report = await load<Receivables>(
    `/receivables${on ? `?on=${encodeURIComponent(on)}` : ""}`,
  );

  const data = report.data;
  const rows = data?.rows ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Receivables</h1>
        <p className="text-sm text-ink-muted">
          What is owed, by how long it has been owed and by whom.
        </p>
      </div>

      {report.error && <ErrorNote>{report.error}</ErrorNote>}

      <Card
        title={data ? `As at ${data.on}` : "Receivables"}
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="on" className="text-xs text-ink-muted">
              As at
            </label>
            <input
              id="on"
              name="on"
              type="date"
              defaultValue={data?.on ?? on}
              className="rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {!data ? (
          <Empty>The receivables report could not be read.</Empty>
        ) : rows.length === 0 ? (
          <Empty>Nothing is owed as at this date.</Empty>
        ) : (
          <>
            <div className="grid gap-4 sm:grid-cols-4">
              <Stat
                label="Owed"
                value={money(data.total.total)}
                hint={`across ${data.total.invoices} open invoice(s)`}
              />
              <Stat
                label="Under 30 days"
                value={money(data.total.current)}
                hint="this month's billing"
              />
              <Stat
                label="60 to 90 days"
                value={money(data.total.days60)}
                hint="worth a call"
              />
              <Stat
                label="Over 90 days"
                value={money(data.total.days90)}
                hint="least likely to arrive on its own"
              />
            </div>

            <div className="mt-4">
              <Table
                head={[
                  "Who owes it",
                  "Invoices",
                  "Under 30 days",
                  "30 to 60",
                  "60 to 90",
                  "Over 90",
                  "Total",
                ]}
              >
                {rows.map((row) => (
                  <tr key={row.payerCode ?? "self"}>
                    <td className="px-3 py-2">
                      {row.payerName}
                      {row.payerCode && (
                        <span className="ml-2 text-xs text-ink-muted">{row.payerCode}</span>
                      )}
                    </td>
                    <td className="numeric px-3 py-2 text-ink-muted">{row.invoices}</td>
                    <td className="numeric px-3 py-2">{money(row.current)}</td>
                    <td className="numeric px-3 py-2">{money(row.days30)}</td>
                    <td className="numeric px-3 py-2">{money(row.days60)}</td>
                    {/*
                      The only figure given weight, because it is the only one that means somebody
                      has to do something. Colouring every column would make none of them stand out.
                    */}
                    <td
                      className={`numeric px-3 py-2 ${row.days90 > 0 ? "font-semibold text-critical" : "text-ink-muted"}`}
                    >
                      {money(row.days90)}
                    </td>
                    <td className="numeric px-3 py-2 font-semibold">{money(row.total)}</td>
                  </tr>
                ))}
                <tr className="border-t-2 border-line">
                  <td className="px-3 py-2 font-semibold">All payers</td>
                  <td className="numeric px-3 py-2 text-ink-muted">{data.total.invoices}</td>
                  <td className="numeric px-3 py-2">{money(data.total.current)}</td>
                  <td className="numeric px-3 py-2">{money(data.total.days30)}</td>
                  <td className="numeric px-3 py-2">{money(data.total.days60)}</td>
                  <td className="numeric px-3 py-2">{money(data.total.days90)}</td>
                  <td className="numeric px-3 py-2 font-semibold">{money(data.total.total)}</td>
                </tr>
              </Table>
            </div>
          </>
        )}

        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          Aged from the date each bill was raised, since the platform records no payment term to be
          overdue against. Credited amounts are already off: a bill the hospital has said in writing
          is not owed is not chased. This total is the same figure the{" "}
          <Link href="/billing/day-book" className="underline">
            day book
          </Link>{" "}
          reports as outstanding, and a test holds the two to each other.
        </p>
      </Card>
    </div>
  );
}
