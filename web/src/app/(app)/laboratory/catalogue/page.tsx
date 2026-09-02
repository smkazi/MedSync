import { load } from "@/lib/load";
import type { CatalogEntry } from "@/lib/types";
import { Card, Empty, ErrorNote, Table } from "@/components/ui";

/** The test catalogue. Read-only: the API has no write endpoints for it. */
export default async function CataloguePage() {
  const { data: catalog, error } = await load<CatalogEntry[]>("/lab/catalog");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Test catalogue</h1>
        <p className="text-sm text-ink-muted">
          What can be ordered, and which parameters each test reports.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {catalog && (
        <Card title="Tests">
          {catalog.length === 0 ? (
            <Empty>The catalogue is empty.</Empty>
          ) : (
            <Table head={["Code", "Name", "Department", "Specimen", "Parameters"]}>
              {catalog.map((entry) => (
                <tr key={entry.id}>
                  <td className="numeric px-3 py-2 font-medium">{entry.code}</td>
                  <td className="px-3 py-2">{entry.name}</td>
                  <td className="px-3 py-2 text-ink-muted">{entry.department}</td>
                  <td className="px-3 py-2 text-ink-muted">
                    {entry.specimenType.toLowerCase().replace(/_/g, " ")}
                  </td>
                  <td className="px-3 py-2 text-xs text-ink-muted">
                    {entry.parameters.length} — {entry.parameters.slice(0, 8).join(", ")}
                    {entry.parameters.length > 8 ? "…" : ""}
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        Read-only. The catalogue is seeded by a migration and the service exposes no write endpoints
        for it, so there is nothing to edit here — a gap in the API, not the screen.
      </p>
    </div>
  );
}
