import { load } from "@/lib/load";
import type { AuditEntry, Page } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";

/**
 * The audit trail.
 *
 * <p>Every privileged action across the platform has been recorded since the first service shipped
 * and nothing has ever displayed it. An audit trail nobody can read is a compliance artefact rather
 * than a control.
 *
 * <p>The correlation id is shown because it is the thread that ties one action to the request that
 * caused it across four services — it is what makes an incident reconstructable.
 */
export default async function AuditPage({
  searchParams,
}: {
  searchParams: Promise<{ entity?: string; action?: string }>;
}) {
  const { entity = "", action = "" } = await searchParams;
  const params = new URLSearchParams({ size: "100" });
  if (entity) params.set("entity", entity);
  if (action) params.set("action", action);

  const { data: entries, error } = await load<Page<AuditEntry>>(`/admin/audit?${params}`);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Audit trail</h1>
        <p className="text-sm text-ink-muted">
          Who did what, and the correlation id that ties it to the request across services.
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="entity" className="block text-sm font-medium">
            Entity
          </label>
          <input
            id="entity"
            name="entity"
            defaultValue={entity}
            placeholder="Patient, LabOrder, Appointment…"
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="action" className="block text-sm font-medium">
            Action
          </label>
          <input
            id="action"
            name="action"
            defaultValue={action}
            placeholder="PATIENT_REGISTERED…"
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
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

      {entries && (
        <Card title={`Entries (${entries.totalElements})`}>
          {entries.content.length === 0 ? (
            <Empty>Nothing recorded for that filter.</Empty>
          ) : (
            <Table head={["When", "Who", "Action", "Entity", "Detail", "Service", "Correlation"]}>
              {entries.content.map((entry) => (
                <tr key={entry.id}>
                  <td className="numeric whitespace-nowrap px-3 py-2 text-ink-muted">
                    {formatDateTime(entry.occurredAt)}
                  </td>
                  <td className="px-3 py-2">{entry.username ?? "—"}</td>
                  <td className="px-3 py-2">
                    <Badge tone="accent">{entry.action}</Badge>
                  </td>
                  <td className="px-3 py-2 text-ink-muted">{entry.entity}</td>
                  <td className="px-3 py-2 text-xs text-ink-muted">{entry.detail ?? "—"}</td>
                  <td className="px-3 py-2 text-xs text-ink-muted">{entry.service}</td>
                  <td className="numeric px-3 py-2 text-xs text-ink-muted">
                    {entry.correlationId ? entry.correlationId.slice(0, 8) : "—"}
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}
    </div>
  );
}
