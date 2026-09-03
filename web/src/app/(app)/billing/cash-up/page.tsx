import { load } from "@/lib/load";
import { money } from "@/lib/money";
import { currentUser, hasRole } from "@/lib/session";
import type { CashSession, Page } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { closeCashSession, openCashSession } from "../actions";

/**
 * The cash-up: a drawer, a count, and a variance somebody signed for.
 *
 * <p>A shift and not a day. A drawer is handed over between people, and a day's takings cannot say
 * which of the three who sat at that counter is short two hundred — which is the only question this
 * screen is ever asked.
 *
 * <p>Only cash is counted. Card and UPI settle into the acquirer's own batch and cannot be short by
 * an error of counting, so they are listed to be ticked against the terminal rather than declared:
 * a field asking somebody to type the expected figure back in would collect agreement, not a count.
 *
 * <p>What is expected is never shown beside the field that collects the count. It is on the screen
 * — above, in the shift's own figures — but putting it next to the input is how a count becomes a
 * transcription, and the difference between those two is the entire point of doing it.
 */
export default async function CashUpPage() {
  const user = await currentUser();
  const mayWrite = hasRole(user, "ADMIN", "CASHIER");

  const [open, history] = await Promise.all([
    load<CashSession>("/cash-sessions/current"),
    load<Page<CashSession>>("/cash-sessions?size=20"),
  ]);

  // 204 for "no drawer open" is an ordinary state, not an error: the loader returns no data and no
  // message, and this screen's job is then to offer opening one.
  const drawer = open.data;
  const shifts = history.data?.content ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Cash-up</h1>
        <p className="text-sm text-ink-muted">
          A drawer, what went through it, and what was counted out at the end.
        </p>
      </div>

      {history.error && <ErrorNote>{history.error}</ErrorNote>}

      {drawer ? (
        <Card
          title="This shift"
          action={<Badge tone="warn">open since {formatDateTime(drawer.openedAt)}</Badge>}
        >
          <div className="grid gap-4 sm:grid-cols-3">
            <Stat label="Opening float" value={money(drawer.openingFloat)} hint="counted in" />
            <Stat
              label="Expected in the drawer"
              value={money(drawer.expectedCash)}
              hint="float, plus cash taken, less cash paid back"
            />
            <Stat label="Cashier" value={drawer.cashier} hint="who signs for it" />
          </div>

          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <div>
              <h3 className="text-sm font-medium">Taken this shift</h3>
              {drawer.taken.length === 0 ? (
                <Empty>Nothing has gone through this drawer yet.</Empty>
              ) : (
                <Table head={["How it arrived", "Count", "Amount"]}>
                  {drawer.taken.map((row) => (
                    <tr key={row.method}>
                      <td className="px-3 py-2">{row.method.toLowerCase().replace("_", " ")}</td>
                      <td className="numeric px-3 py-2">{row.count}</td>
                      <td className="numeric px-3 py-2 font-semibold">{money(row.amount)}</td>
                    </tr>
                  ))}
                </Table>
              )}
            </div>
            {drawer.paidBack.length > 0 && (
              <div>
                <h3 className="text-sm font-medium">Paid back out</h3>
                <Table head={["How it went back", "Count", "Amount"]}>
                  {drawer.paidBack.map((row) => (
                    <tr key={row.method}>
                      <td className="px-3 py-2">{row.method.toLowerCase().replace("_", " ")}</td>
                      <td className="numeric px-3 py-2">{row.count}</td>
                      <td className="numeric px-3 py-2 font-semibold">-{money(row.amount)}</td>
                    </tr>
                  ))}
                </Table>
              </div>
            )}
          </div>
          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            Card and UPI are here to be ticked against the terminal&rsquo;s own batch, not counted:
            they settle into the acquirer and cannot be short by a miscount. Only the cash is
            declared below.
          </p>
        </Card>
      ) : null}

      {mayWrite && drawer && (
        <Card title="Count it and close the shift">
          <RecordForm
            action={closeCashSession}
            hidden={{ sessionId: drawer.id }}
            submitLabel="Close the shift"
            busyLabel="Closing…"
            columns={1}
            fields={[
              {
                name: "declaredCash",
                label: "Cash counted out of the drawer",
                type: "number",
                required: true,
                step: "0.01",
                // Deliberately no hint naming the expected figure. It is on this page already; put
                // it beside the box and the count becomes a transcription of it.
                hint: "Count the notes and coins, then type what is there.",
              },
              {
                name: "notes",
                label: "What accounts for a difference",
                type: "textarea",
                hint: "Required only when the count disagrees, and then it is required — an unexplained variance is a number nobody will investigate.",
              },
            ]}
          />
        </Card>
      )}

      {mayWrite && !drawer && (
        <Card title="Open a drawer">
          <RecordForm
            action={openCashSession}
            submitLabel="Open the drawer"
            busyLabel="Opening…"
            columns={1}
            fields={[
              {
                name: "openingFloat",
                label: "Opening float",
                type: "number",
                required: true,
                step: "0.01",
                hint: "What is in the drawer before the shift starts. Zero is a legitimate float; leaving it out is not, because opening a shift is an act of counting.",
              },
            ]}
          />
          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            Payments are never refused for want of an open drawer — money taken with no shift open
            belongs to no shift and is reported that way rather than absorbed into one.
          </p>
        </Card>
      )}

      <Card title={hasRole(user, "ADMIN") ? "Every shift" : "Your shifts"}>
        {shifts.length === 0 ? (
          <Empty>No shift has been counted yet.</Empty>
        ) : (
          <Table
            head={["Opened", "Cashier", "Float", "Expected", "Counted", "Variance", "Closed by"]}
          >
            {shifts.map((shift) => (
              <tr key={shift.id}>
                <td className="numeric px-3 py-2">{formatDateTime(shift.openedAt)}</td>
                <td className="px-3 py-2">{shift.cashier}</td>
                <td className="numeric px-3 py-2">{money(shift.openingFloat)}</td>
                <td className="numeric px-3 py-2">{money(shift.expectedCash)}</td>
                <td className="numeric px-3 py-2">
                  {shift.declaredCash === null ? "—" : money(shift.declaredCash)}
                </td>
                {/*
                  Weighted only when it is non-zero. A column of coloured zeroes teaches the eye to
                  skip the one row that is not.
                */}
                <td
                  className={`numeric px-3 py-2 ${shift.variance ? "font-semibold text-critical" : "text-ink-muted"}`}
                >
                  {shift.variance === null
                    ? "open"
                    : `${money(shift.variance)} ${shift.varianceDescription}`}
                </td>
                <td className="px-3 py-2 text-ink-muted">
                  {shift.closedBy ?? "—"}
                  {shift.closedBy && shift.closedBy !== shift.cashier && (
                    // Worth showing: an administrator closing somebody else's abandoned drawer is
                    // a different fact from that person counting their own.
                    <span className="ml-1 text-xs">(not the cashier)</span>
                  )}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          A shift is counted once. What a closed row says is what was true when somebody signed it:
          correcting an invoice afterwards changes what is owed and never moves a figure a person
          has already put their name to.
        </p>
      </Card>
    </div>
  );
}
