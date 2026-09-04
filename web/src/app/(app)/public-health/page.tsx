import Link from "next/link";
import { load } from "@/lib/load";
import type { NotifiableReport } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";

/**
 * The notifiable-disease return: how many cases of each reportable condition, over a period.
 *
 * <p><strong>Counts, and nothing else.</strong> There is no MRN, name, patient id or department on
 * this screen, and not because they were left out of the markup — the query behind it returns
 * through a projection with nowhere to put an identifier, so there is nothing here to render. That
 * is what lets the whole screen be held by an epidemiologist who cannot open a chart. The names
 * behind the counts are one link away, under a different gate, and produce a disclosure record.
 *
 * <p><strong>Every configured condition appears, zeroes included.</strong> A return that omitted
 * them would render "no cholera this fortnight" and "cholera is not on our list" identically, and
 * those are very different facts about a district.
 *
 * <p>The zone is shown because a notifiable week is a statutory boundary: a return whose days were
 * cut in UTC by a hospital running on IST puts five and a half hours of every Sunday into the next
 * week's figures, and a reader has to be able to tell which return they are holding.
 */
export default async function NotifiableReturnPage({
  searchParams,
}: {
  searchParams: Promise<{ from?: string; to?: string }>;
}) {
  const { from = "", to = "" } = await searchParams;
  const filters = new URLSearchParams();
  if (from) filters.set("from", from);
  if (to) filters.set("to", to);

  const { data: report, error } = await load<NotifiableReport>(
    `/surveillance/notifiable${filters.size > 0 ? `?${filters}` : ""}`,
  );
  // The same filters, so the file and the table cannot disagree about the period.
  const csvHref = `/api/public-health/notifiable${filters.size > 0 ? `?${filters}` : ""}`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Notifiable-disease return</h1>
        <p className="text-sm text-ink-muted">
          Cases of each reportable condition over a period. With no dates set the return covers the
          last thirty days. A case is a patient, not a visit: somebody diagnosed twice in a
          fortnight is one case.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {report && (
        <>
          <form className="flex flex-wrap items-end gap-3">
            <div>
              <label htmlFor="from" className="block text-xs text-ink-muted">
                From
              </label>
              <input
                id="from"
                name="from"
                type="date"
                defaultValue={report.from}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="to" className="block text-xs text-ink-muted">
                To
              </label>
              <input
                id="to"
                name="to"
                type="date"
                defaultValue={report.to}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              Show
            </button>
            <a
              href={csvHref}
              className="rounded-md border border-line px-4 py-2 text-sm hover:bg-surface-raised"
            >
              Download CSV
            </a>
          </form>

          <div className="grid gap-4 sm:grid-cols-3">
            <Stat
              label="Cases in the period"
              value={report.totalCases}
              hint="distinct patients, across every condition"
            />
            <Stat
              label="Conditions watched for"
              value={report.conditions.length}
              hint="configuration, one row per ICD-10 code"
            />
            <Stat
              label="Days cut in"
              value={report.zone}
              hint="a statutory boundary — a week cut elsewhere is a different return"
            />
          </div>

          {report.suppressed && (
            <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
              One or more counts are below the small-cell threshold of {report.smallCellThreshold}{" "}
              and are withheld. The total above is still exact — a return whose lines do not add up
              is one somebody sends back.
            </p>
          )}

          <Card
            title="The return"
            action={
              <Link href="/public-health/line-list" className="text-sm text-accent hover:underline">
                The names behind these counts →
              </Link>
            }
          >
            {report.conditions.length === 0 ? (
              <Empty>
                No conditions are configured, so this return has no lines. That is a configuration
                problem rather than a healthy district: `scheduling.notifiable_conditions` is empty.
              </Empty>
            ) : (
              <Table head={["Code", "Condition", "Cases", "Notify within"]}>
                {report.conditions.map((line) => (
                  <tr key={line.icd10Code} className="border-t border-line">
                    <td className="numeric px-3 py-2">{line.icd10Code}</td>
                    <td className="px-3 py-2">{line.conditionName}</td>
                    <td className="numeric px-3 py-2">
                      {/*
                        Null and zero are rendered differently on purpose. A withheld count and no
                        cases are different facts, and showing a suppressed line as 0 would make
                        the return lie in the safer-looking direction.
                      */}
                      {line.suppressed ? (
                        <Badge tone="warn">withheld</Badge>
                      ) : line.cases === 0 ? (
                        <span className="text-ink-muted">0</span>
                      ) : (
                        <strong>{line.cases}</strong>
                      )}
                    </td>
                    <td className="px-3 py-2 text-xs text-ink-muted">
                      {line.notifyWithinHours} h
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>

          <p className="text-xs text-ink-muted">
            Computed {formatDateTime(report.computedAt)}, on read and never cached — a diagnosis
            recorded this morning correctly changes last fortnight&apos;s figures.{" "}
            <strong>Nothing transmits this.</strong> The hours column is recorded and enforced by
            nothing: this platform has no outbound channel to an authority, so filing the return is
            somebody downloading the file. A countdown it could not act on would be a promise
            nothing keeps.
          </p>
        </>
      )}
    </div>
  );
}
