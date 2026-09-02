import { load } from "@/lib/load";
import type { Analyzer } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";

/** Configured analyzers. Read-only, and only active ones are returned. */
export default async function AnalyzersPage() {
  const { data: analyzers, error } = await load<Analyzer[]>("/lab/analyzers");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Analyzers</h1>
        <p className="text-sm text-ink-muted">
          The instruments the laboratory accepts transmissions from.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {analyzers && (
        <Card title="Instruments">
          {analyzers.length === 0 ? (
            <Empty>No analyzers are configured.</Empty>
          ) : (
            <Table head={["Name", "Model", "Protocol", "Transport", "Last seen"]}>
              {analyzers.map((analyzer) => (
                <tr key={analyzer.id}>
                  <td className="px-3 py-2 font-medium">{analyzer.name}</td>
                  <td className="px-3 py-2 text-ink-muted">{analyzer.model}</td>
                  <td className="px-3 py-2">
                    <Badge tone="accent">{analyzer.protocol}</Badge>
                  </td>
                  <td className="px-3 py-2 text-ink-muted">{analyzer.transport}</td>
                  <td className="px-3 py-2 text-ink-muted">
                    {analyzer.lastSeen ? formatDateTime(analyzer.lastSeen) : "never"}
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        Read-only, and the endpoint returns only <em>active</em> analyzers — so a decommissioned
        instrument is invisible here rather than shown as retired. Both are gaps in the API.
      </p>
    </div>
  );
}
