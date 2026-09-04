import Link from "next/link";
import { load } from "@/lib/load";
import type { NotifiableLineList } from "@/lib/types";
import { Card, Empty, ErrorNote, Stat, Table, formatDate, formatDateTime } from "@/components/ui";

/**
 * The names behind the counts, and the one screen on this platform where looking and sending are
 * deliberately different acts.
 *
 * <p><strong>This page is a look. The download is a notification.</strong> Opening it is audited,
 * like every other read of a record inside the hospital. Downloading the file registers a
 * disclosure against every patient it names — which the patient can then see in their own portal
 * accounting — and if the disclosure register cannot be reached, <em>no file is produced at all</em>
 * and the platform answers 503. That ordering is the whole design: a list of named patients that
 * went out with no record of having gone out is the one outcome this module exists to refuse.
 *
 * <p>The screen says that in prose above the button rather than relying on the operator having
 * read a manual, and the response carries the same sentence so the two cannot drift.
 *
 * <p><strong>Administrator only, and the epidemiologist deliberately not.</strong> That looks
 * backwards — the epidemiologist is the person who files the return — and it is the point: the
 * property that lets one role hold the whole surveillance module without holding a chart is that it
 * reads only aggregates, and a role handed the names once no longer has it. Somebody must be able
 * to produce this, because notification is compelled by law, and the platform gives the job to the
 * account already accountable for exports and whole-chart releases.
 */
export default async function LineListPage({
  searchParams,
}: {
  searchParams: Promise<{ from?: string; to?: string }>;
}) {
  const { from = "", to = "" } = await searchParams;
  const filters = new URLSearchParams();
  if (from) filters.set("from", from);
  if (to) filters.set("to", to);

  const { data: list, error } = await load<NotifiableLineList>(
    `/surveillance/notifiable/line-list${filters.size > 0 ? `?${filters}` : ""}`,
  );
  const csvHref = `/api/public-health/line-list${filters.size > 0 ? `?${filters}` : ""}`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Notifiable line list</h1>
        <p className="text-sm text-ink-muted">
          Every notifiable case in the period, by patient. With no dates set this covers the last
          thirty days.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {list && (
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
                defaultValue={list.from}
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
                defaultValue={list.to}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              Show
            </button>
          </form>

          <div className="grid gap-4 sm:grid-cols-3">
            <Stat label="Notifications" value={list.cases.length} hint="one per diagnosis" />
            <Stat
              label="Patients named"
              value={list.patients}
              hint="one disclosure row each, if you download"
            />
            <Stat label="Would go to" value={list.recipient} hint="configured, not typed here" />
          </div>

          {/*
            The warning sits above the button rather than beside the table, because the table is
            the harmless half. `list.note` is the service's own sentence, rendered rather than
            paraphrased: the rule is enforced there, and a screen that restated it in its own words
            would be a second copy to keep in step.
          */}
          <Card title="Notify the authority" tone="critical">
            <p className="text-sm">
              This screen is a <strong>look</strong>. {list.note}
            </p>
            <p className="mt-2 text-sm text-ink-muted">
              Downloading the file writes one disclosure per patient named above, against your
              account, and the patient can see it in their own record of who has seen their chart.
              If the disclosure register cannot be reached, no file is produced — the platform will
              not hand out a list it cannot account for.
            </p>
            {list.cases.length > 0 ? (
              <a
                href={csvHref}
                className="mt-3 inline-block rounded-md bg-critical px-4 py-2 text-sm font-medium text-white hover:opacity-90"
              >
                Register the disclosure and download
              </a>
            ) : (
              <p className="mt-3 text-sm text-ink-muted">
                Nothing to notify in this period, so there is nothing to register.
              </p>
            )}
          </Card>

          <Card
            title="Cases"
            action={
              <Link href="/public-health" className="text-sm text-accent hover:underline">
                ← The counts
              </Link>
            }
          >
            {list.cases.length === 0 ? (
              <Empty>
                No notifiable diagnoses in this period. A quiet fortnight and a misconfigured
                condition list look the same here, so check the{" "}
                <Link href="/public-health" className="text-accent hover:underline">
                  return
                </Link>{" "}
                if you expected lines.
              </Empty>
            ) : (
              <Table head={["MRN", "Code", "Condition", "Diagnosed", "Notify within", ""]}>
                {list.cases.map((row, index) => (
                  <tr key={`${row.patientId}-${row.icd10Code}-${index}`} className="border-t border-line">
                    <td className="numeric px-3 py-2">{row.patientMrn}</td>
                    <td className="numeric px-3 py-2">{row.icd10Code}</td>
                    <td className="px-3 py-2">{row.conditionName}</td>
                    <td className="px-3 py-2">{formatDate(row.diagnosedOn)}</td>
                    <td className="px-3 py-2 text-xs text-ink-muted">{row.notifyWithinHours} h</td>
                    <td className="px-3 py-2">
                      {/*
                        The patient id is on this response and off the file, and this link is why
                        it is here: somebody working the list needs to reach the chart. The
                        authority receiving the file gets an MRN, because an internal UUID is a
                        number nobody outside the building can use.
                      */}
                      <Link
                        href={`/patients/${row.patientId}`}
                        className="text-sm text-accent hover:underline"
                      >
                        Chart
                      </Link>
                    </td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>

          <p className="text-xs text-ink-muted">
            Computed {formatDateTime(list.computedAt)}, days cut in {list.zone}.
          </p>
        </>
      )}
    </div>
  );
}
