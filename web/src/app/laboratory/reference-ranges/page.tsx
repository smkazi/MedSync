import { load } from "@/lib/load";
import type { ReferenceRange } from "@/lib/types";
import { Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * Reference intervals, per parameter and sex.
 *
 * <p>The first of the laboratory's three tiers of number, and the page says so — because the other
 * two look like the same thing and are not. This interval decides whether a value is flagged H or L;
 * an interpretive threshold decides whether it earns a sentence on the report; a morphology cut-off
 * decides what the cells are called. Haemoglobin flags below 11.5 g/dL for a woman and only comments
 * below 9.0.
 */
export default async function ReferenceRangesPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string }>;
}) {
  const { q = "" } = await searchParams;
  const { data: ranges, error } = await load<ReferenceRange[]>("/lab/reference-ranges");

  const needle = q.trim().toUpperCase();
  const shown = (ranges ?? []).filter(
    (range) =>
      !needle ||
      range.parameter.toUpperCase().includes(needle) ||
      range.displayName.toUpperCase().includes(needle),
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Reference ranges</h1>
        <p className="text-sm text-ink-muted">
          What counts as normal, per parameter and sex. A lab adjusts these for its population and
          its instruments.
        </p>
      </div>

      {/* Filtered in the page, not the API: the whole set is 54 rows and fetching it once is cheaper
          than a round trip per keystroke. */}
      <form className="flex flex-wrap items-end gap-3">
        <div className="grow">
          <label htmlFor="q" className="block text-sm font-medium">
            Parameter
          </label>
          <input
            id="q"
            name="q"
            defaultValue={q}
            placeholder="HGB, platelet, MCV…"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Filter
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      {ranges && (
        <Card title={`Ranges (${shown.length} of ${ranges.length})`}>
          {shown.length === 0 ? (
            <Empty>No parameter matches.</Empty>
          ) : (
            <Table head={["Parameter", "Display name", "Sex", "Interval", "Unit"]}>
              {shown.map((range) => (
                <tr key={range.id}>
                  <td className="numeric px-3 py-2 font-medium">{range.parameter}</td>
                  <td className="px-3 py-2">{range.displayName}</td>
                  <td className="px-3 py-2 text-ink-muted">{range.sex}</td>
                  <td className="numeric px-3 py-2">{range.referenceRange || "—"}</td>
                  <td className="px-3 py-2 text-ink-muted">{range.unit}</td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        Editing an interval is restricted to an administrator or a pathologist and only the low and
        high bounds are writable. The form for it is not built yet; the API accepts{" "}
        <span className="numeric">PATCH /lab/reference-ranges/{"{id}"}</span> today.
      </p>
    </div>
  );
}
