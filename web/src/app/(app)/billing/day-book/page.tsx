import { load } from "@/lib/load";
import { money } from "@/lib/money";
import type { DayBook } from "@/lib/types";
import { Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";

/**
 * The day's position: billed, collected, refunded, outstanding.
 *
 * <p>Split by method, and the split is the point. Cash reconciles against what is in the drawer,
 * card against the terminal's own batch, UPI against references somebody can look up. A single
 * grand total reconciles against nothing, and a discrepancy nobody can localise is a discrepancy
 * nobody finds.
 *
 * <p>Billed and collected are different questions and both are here: money billed today may be
 * collected next month, and money collected today may be for a bill raised in March. Outstanding
 * is neither — it is everything still owed as of this date, which is the number a hospital's
 * receivables actually are.
 *
 * <p>Money out is shown beside money in rather than folded into it. Collected is gross, and the
 * day's actual take is collected less refunded: the first reconciles against the receipts, the
 * second against the refund vouchers, and only the third against the drawer. Credited is on the
 * billing side, not the cash side — a credit note withdraws a charge without any money moving,
 * and netting it into collections would make the two impossible to tell apart.
 */
export default async function DayBookPage({
  searchParams,
}: {
  searchParams: Promise<{ on?: string }>;
}) {
  const { on = "" } = await searchParams;
  const book = await load<DayBook>(`/day-book${on ? `?on=${encodeURIComponent(on)}` : ""}`);

  const day = book.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Day book</h1>
        <p className="text-sm text-ink-muted">
          What was billed, what was collected, what went back out, and what is still owed.
        </p>
      </div>

      {book.error && <ErrorNote>{book.error}</ErrorNote>}

      <Card
        title={day ? `The ${day.on}` : "The day"}
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="on" className="text-xs text-ink-muted">
              Date
            </label>
            <input
              id="on"
              name="on"
              type="date"
              defaultValue={day?.on ?? on}
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
        {!day ? (
          <Empty>The day book could not be read.</Empty>
        ) : (
          <>
            <div className="grid gap-4 sm:grid-cols-3">
              <Stat label="Billed" value={money(day.billed)} hint={`${day.invoices} invoice(s) dated today`} />
              <Stat
                label="Credited"
                value={money(day.credited)}
                hint="withdrawn by credit note; no money moved"
              />
              <Stat
                label="Outstanding"
                value={money(day.outstanding)}
                hint="everything still owed as of this date"
              />
              <Stat
                label="Collected"
                value={money(day.collected)}
                hint={`${day.payments} payment(s) taken today, before anything went back`}
              />
              <Stat
                label="Refunded"
                value={money(day.refunded)}
                hint={`${day.refunds} refund(s) paid back today`}
              />
              <Stat
                label="Net taken"
                value={money(day.net)}
                hint="collected less refunded — the drawer figure"
              />
            </div>

            <div className="mt-4">
              {day.byMethod.length === 0 ? (
                <Empty>Nothing was collected on this day.</Empty>
              ) : (
                <Table head={["How it arrived", "Payments", "Amount"]}>
                  {day.byMethod.map((row) => (
                    <tr key={row.method}>
                      <td className="px-3 py-2">{row.method.toLowerCase().replace("_", " ")}</td>
                      <td className="numeric px-3 py-2">{row.count}</td>
                      <td className="numeric px-3 py-2 font-semibold">{money(row.amount)}</td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>

            {/*
              Only when there were any. A refunds table that is empty on most days trains the eye
              to skip the place a payout would appear, which is the one row worth noticing.
            */}
            {day.refundsByMethod.length > 0 && (
              <div className="mt-4">
                <Table head={["How it went back", "Refunds", "Amount"]}>
                  {day.refundsByMethod.map((row) => (
                    <tr key={row.method}>
                      <td className="px-3 py-2">{row.method.toLowerCase().replace("_", " ")}</td>
                      <td className="numeric px-3 py-2">{row.count}</td>
                      <td className="numeric px-3 py-2 font-semibold">-{money(row.amount)}</td>
                    </tr>
                  ))}
                </Table>
              </div>
            )}
          </>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A day runs midnight to midnight in the deployment’s own zone rather than in UTC, so a
          cash-up at eight in the evening finds the evening’s takings in today. There is no cash-up
          record, drawer count or shift close here yet: the numbers are readable and nothing signs
          them off, which the README names as a gap rather than implying otherwise.
        </p>
      </Card>
    </div>
  );
}
