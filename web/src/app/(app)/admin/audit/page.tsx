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
  searchParams: Promise<{
    entity?: string;
    action?: string;
    actorId?: string;
    username?: string;
    from?: string;
    to?: string;
  }>;
}) {
  const { entity = "", action = "", actorId = "", username = "", from = "", to = "" } = await searchParams;
  const filters = new URLSearchParams();
  if (entity) filters.set("entity", entity);
  if (action) filters.set("action", action);
  if (actorId) filters.set("actorId", actorId);
  if (username) filters.set("username", username);
  if (from) filters.set("from", from);
  if (to) filters.set("to", to);

  const params = new URLSearchParams(filters);
  params.set("size", "100");
  const { data: entries, error } = await load<Page<AuditEntry>>(`/admin/audit?${params}`);
  // The same filters, so the file and the table on screen cannot disagree about the period.
  const csvHref = `/api/admin/audit${filters.size > 0 ? `?${filters}` : ""}`;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Audit trail</h1>
        <p className="text-sm text-ink-muted">
          Who did what, and the correlation id that ties it to the request across services. With no
          dates set the report covers the last thirty days.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {/* No filters and no download link when the report could not be read. Offering a Download
          CSV button to somebody the platform has just refused would produce a second refusal, in
          a file they cannot open, which is a worse answer than the one above. */}
      {entries && (
        <>
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
        <div>
          <label htmlFor="username" className="block text-sm font-medium">
            Who
          </label>
          <input
            id="username"
            name="username"
            defaultValue={username}
            placeholder="part of a username"
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="from" className="block text-sm font-medium">
            From
          </label>
          <input
            id="from"
            name="from"
            type="date"
            defaultValue={from}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="to" className="block text-sm font-medium">
            To
          </label>
          <input
            id="to"
            name="to"
            type="date"
            defaultValue={to}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Filter
        </button>
        <a
          href={csvHref}
          className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface-raised"
        >
          Download CSV
        </a>
      </form>

      <Card title={`Entries (${entries.totalElements})`}>
          {entries.content.length === 0 ? (
            <Empty>Nothing recorded for that filter.</Empty>
          ) : (
            <Table
              head={["When", "Who", "Actor id", "Action", "Entity", "Detail", "Service", "Correlation"]}
            >
              {entries.content.map((entry) => (
                <tr key={entry.id}>
                  <td className="numeric whitespace-nowrap px-3 py-2 text-ink-muted">
                    {formatDateTime(entry.occurredAt)}
                  </td>
                  <td className="px-3 py-2">{entry.username ?? "—"}</td>
                  {/* Shown because it is filterable: a filter whose value never appears cannot be
                      checked, and an empty result reads the same as a mistyped id. */}
                  <td className="numeric px-3 py-2 text-xs text-ink-muted">
                    {entry.actorId ? entry.actorId.slice(0, 8) : "—"}
                  </td>
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
        </>
      )}
    </div>
  );
}
