import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { TaxRate } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table } from "@/components/ui";
import { addTaxRate } from "../actions";

/**
 * GST, as dated rows.
 *
 * <p>Never a number in the code. Rates change by statute, and an invoice raised last year has to
 * keep the rate that applied then — so a change is a new row with a start date and the previous row
 * is closed the day before, rather than an edit that would silently restate every historical
 * invoice the next time somebody recalculated one.
 *
 * <p>Which is also why a rate cannot start in the past: invoices raised before today were taxed at
 * the rate that applied then, and back-dating one would make this table disagree with the receipts
 * people are holding.
 */
export default async function TaxRatesPage() {
  const mayPrice = hasRole(await currentUser(), "ADMIN");
  const rates = await load<TaxRate[]>("/tax-rates");

  const rows = rates.data ?? [];
  const inForce = rows.filter((rate) => rate.inForceToday);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Tax rates</h1>
        <p className="text-sm text-ink-muted">
          What each rate code means, and from when. An invoice is taxed at the row its own date
          falls in.
        </p>
      </div>

      {rates.error && <ErrorNote>{rates.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-2">
        <Stat label="Rate rows" value={rows.length} hint="current and historical" />
        <Stat label="In force today" value={inForce.length} hint="one per code" />
      </div>

      <Card title="Rates">
        {rows.length === 0 ? (
          <Empty>No rates are configured.</Empty>
        ) : (
          <Table head={["Code", "Name", "Percent", "From", "Until", ""]}>
            {rows.map((rate) => (
              <tr key={rate.id} className={rate.inForceToday ? "" : "text-ink-muted"}>
                <td className="numeric px-3 py-2">{rate.code}</td>
                <td className="px-3 py-2">{rate.name}</td>
                <td className="numeric px-3 py-2">{rate.percent}%</td>
                <td className="numeric px-3 py-2">{rate.effectiveFrom}</td>
                <td className="numeric px-3 py-2">{rate.effectiveTo ?? "—"}</td>
                <td className="px-3 py-2">
                  {rate.inForceToday ? <Badge tone="good">in force</Badge> : null}
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      {mayPrice && (
        <Card title="Change a rate">
          <RecordForm
            action={addTaxRate}
            submitLabel="Record the new rate"
            busyLabel="Recording…"
            fields={[
              {
                name: "code",
                label: "Rate code",
                required: true,
                hint: "An existing code supersedes it from the date below; a new code starts a new one.",
              },
              { name: "name", label: "Name", required: true, placeholder: "GST 18%" },
              { name: "percent", label: "Percent", type: "number", required: true, step: "0.01" },
              {
                name: "effectiveFrom",
                label: "From",
                type: "date",
                required: true,
                hint: "Today or later. A rate cannot start in the past — receipts already issued would disagree with it.",
              },
            ]}
          />
        </Card>
      )}

      <p className="text-xs text-ink-muted">
        Exempt is a rate of zero with a name rather than the absence of a rate, so an exempt line on
        an invoice can say it is exempt instead of being silent about tax. These percentages are the
        ordinary slabs and a starting point a deployment’s accountant is expected to check: the
        platform implements the rules as understood and this is not tax advice.
      </p>
    </div>
  );
}
