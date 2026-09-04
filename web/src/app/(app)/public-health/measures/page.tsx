import { load } from "@/lib/load";
import type { MeasureRate, QualityMeasure } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDate, formatDateTime } from "@/components/ui";

/**
 * Clinical quality measures: a coverage rate for a period.
 *
 * <p><strong>Nothing on this screen identifies a child</strong>, and the calculator behind it never
 * selects an identifier into the shape this renders — which is why a role that cannot open a chart
 * can hold it.
 *
 * <p>Three things the screen states rather than leaves to be discovered, because each of them is a
 * decision somebody could otherwise read as a bug:
 *
 * <ul>
 *   <li><strong>A null rate is not zero.</strong> "No children reached their second birthday in
 *       this district last month" is not "none of them were vaccinated", and rendering it as 0%
 *       would put a false failure into a return somebody signs.
 *   <li><strong>The numerator is evaluated at each child's own Nth birthday</strong>, not as at
 *       today. That is what "by age two" means, and getting it wrong makes a published rate that
 *       improves retroactively.
 *   <li><strong>It is not cached.</strong> A dose entered from a card this morning correctly
 *       changes last quarter's rate, so two reads a week apart can legitimately differ — which is
 *       why the answer carries the moment it was computed and the specification version it was
 *       computed against.
 * </ul>
 *
 * <p>The three population sentences are shown in the specification's own words rather than rendered
 * from the parameters beside them. A sentence generated from the columns would always agree with
 * the code and would therefore never reveal a disagreement between the code and the specification.
 */
export default async function MeasuresPage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string; periodFrom?: string; periodTo?: string }>;
}) {
  const { code = "", periodFrom = "", periodTo = "" } = await searchParams;

  const { data: measures, error } = await load<QualityMeasure[]>("/measures");
  const available = measures ?? [];
  const selected = code || available[0]?.code || "";

  // A rate is only fetched once a period is asked for. The endpoint defaults one, but a screen
  // that computed a district's coverage the moment somebody opened a menu would be doing a
  // deployment's heaviest read on a page load nobody asked for.
  const wanted = new URLSearchParams();
  if (periodFrom) wanted.set("periodFrom", periodFrom);
  if (periodTo) wanted.set("periodTo", periodTo);
  const asked = Boolean(selected) && wanted.size > 0;
  const { data: rate, error: rateError } = asked
    ? await load<MeasureRate>(`/measures/${encodeURIComponent(selected)}/rate?${wanted}`)
    : { data: null, error: null };

  const specification = available.find((measure) => measure.code === selected);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Clinical quality measures</h1>
        <p className="text-sm text-ink-muted">
          Coverage over a period, computed from the immunisation register. Counts and a percentage —
          no child is named here, and the query behind it selects no identifier.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}
      {rateError && <ErrorNote>{rateError}</ErrorNote>}

      {available.length === 0 && !error ? (
        <Empty>
          No measures are configured. A measure is rows — its age, its antigens and dose counts, its
          steward and its specification version — inserted by a migration or by hand; there is no
          form for one, which is named in the README as a gap rather than implied here.
        </Empty>
      ) : (
        <>
          <form className="flex flex-wrap items-end gap-3">
            <div>
              <label htmlFor="code" className="block text-xs text-ink-muted">
                Measure
              </label>
              <select
                id="code"
                name="code"
                defaultValue={selected}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              >
                {available.map((measure) => (
                  <option key={measure.code} value={measure.code}>
                    {measure.code} — {measure.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="periodFrom" className="block text-xs text-ink-muted">
                Period from
              </label>
              <input
                id="periodFrom"
                name="periodFrom"
                type="date"
                defaultValue={periodFrom}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="periodTo" className="block text-xs text-ink-muted">
                Period to
              </label>
              <input
                id="periodTo"
                name="periodTo"
                type="date"
                defaultValue={periodTo}
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              />
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              Compute
            </button>
          </form>

          {!asked && (
            <p className="text-sm text-ink-muted">
              Choose a period. The period is when children <em>reached</em> the measure&apos;s age,
              not when they were born — the birth range that implies is shown with the answer so the
              arithmetic can be checked.
            </p>
          )}

          {rate && (
            <>
              <div className="grid gap-4 sm:grid-cols-4">
                <Stat
                  label="Coverage"
                  value={
                    rate.rate === null ? (
                      <span className="text-ink-muted">no denominator</span>
                    ) : (
                      `${rate.rate}%`
                    )
                  }
                  hint={
                    rate.rate === null
                      ? "not zero per cent — nobody reached the age in this period"
                      : `${rate.numerator} of ${rate.denominator}`
                  }
                />
                <Stat
                  label="Initial population"
                  value={rate.initialPopulation}
                  hint="children who reached the age in the period"
                />
                <Stat
                  label="Denominator"
                  value={rate.denominator}
                  hint="after medical exclusions"
                />
                <Stat label="Numerator" value={rate.numerator} hint="fully covered by that date" />
              </div>

              {rate.truncated && (
                <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
                  {rate.note ??
                    "The birth cohort behind this rate hit its cap, so this number is computed " +
                      "from part of the population. Narrow the period."}
                </p>
              )}

              <Card title="What was computed">
                <dl className="grid gap-3 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-xs text-ink-muted">Period</dt>
                    <dd>
                      {formatDate(rate.periodFrom)} → {formatDate(rate.periodTo)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-ink-muted">
                      Which is children born between
                    </dt>
                    <dd>
                      {formatDate(rate.bornFrom)} → {formatDate(rate.bornTo)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-ink-muted">Schedule counted against</dt>
                    <dd className="numeric">{rate.scheduleCode}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-ink-muted">Specification</dt>
                    <dd>
                      {rate.steward} · {rate.specificationVersion}
                    </dd>
                  </div>
                </dl>
                <p className="mt-3 text-xs text-ink-muted">
                  Computed {formatDateTime(rate.computedAt)}. Not cached, and not stored: there is
                  no record of &quot;this is the number we filed on the 14th&quot;, which is named
                  in the README as a gap rather than implied by this screen.
                </p>
              </Card>
            </>
          )}

          {specification && (
            <Card title={`${specification.code} — what it asks`}>
              <dl className="space-y-3 text-sm">
                <div>
                  <dt className="text-xs text-ink-muted">Initial population</dt>
                  <dd>{specification.initialPopulation}</dd>
                </div>
                <div>
                  <dt className="text-xs text-ink-muted">Denominator</dt>
                  <dd>{specification.denominator}</dd>
                </div>
                <div>
                  <dt className="text-xs text-ink-muted">Denominator exclusion</dt>
                  <dd>{specification.denominatorExclusion}</dd>
                </div>
                <div>
                  <dt className="text-xs text-ink-muted">Numerator</dt>
                  <dd>{specification.numerator}</dd>
                </div>
              </dl>

              <div className="mt-4">
                <Table head={["Antigen", "Doses required"]}>
                  {specification.antigens.map((antigen) => (
                    <tr key={antigen.antigenCode} className="border-t border-line">
                      <td className="numeric px-3 py-2">{antigen.antigenCode}</td>
                      <td className="numeric px-3 py-2">{antigen.dosesRequired}</td>
                    </tr>
                  ))}
                </Table>
              </div>

              <p className="mt-3 text-xs text-ink-muted">
                By age {specification.byAgeDays} days.{" "}
                {specification.countsEstimatedDates ? (
                  <Badge tone="warn">counts recollected dates</Badge>
                ) : (
                  <Badge tone="neutral">documented dates only</Badge>
                )}{" "}
                — a rate that silently counted a parent&apos;s recollection would be higher than one
                that did not, and nobody reading the number would know which they had.
              </p>
            </Card>
          )}
        </>
      )}
    </div>
  );
}
