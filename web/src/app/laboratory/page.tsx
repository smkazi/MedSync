import Link from "next/link";
import { api } from "@/lib/api";
import type { LabOrderSummary, Page } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Stat,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";

/**
 * The laboratory worklist.
 *
 * Ordered so the work that blocks a clinician sits at the top: STAT first, then results waiting on
 * a pathologist's release.
 */
export default async function LaboratoryPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; status?: string }>;
}) {
  const { mrn, status } = await searchParams;
  const params = new URLSearchParams({ size: "100" });
  if (mrn) params.set("mrn", mrn);
  if (status) params.set("status", status);

  let results: Page<LabOrderSummary> | null = null;
  let error: string | null = null;
  try {
    results = await api<Page<LabOrderSummary>>(`/lab/orders?${params}`);
  } catch (caught) {
    error = caught instanceof Error ? caught.message : "Could not load the worklist";
  }

  const orders = (results?.content ?? []).slice().sort(byUrgency);
  const awaitingRelease = orders.filter((order) => order.status === "RESULTED");
  const abnormal = orders.filter((order) => order.hasAbnormalResults);
  const collected = orders.filter((order) => order.status === "COLLECTED");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Laboratory</h1>
        <p className="text-sm text-ink-muted">Orders needing attention, most urgent first.</p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Open orders" value={orders.length} />
        <Stat label="On the bench" value={collected.length} hint="specimen received" />
        <Stat label="Awaiting release" value={awaitingRelease.length} hint="pathologist review" />
        <Stat label="With abnormal results" value={abnormal.length} />
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="mrn" className="block text-sm font-medium">
            MRN
          </label>
          <input
            id="mrn"
            name="mrn"
            defaultValue={mrn ?? ""}
            placeholder="MRN-2026-…"
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="status" className="block text-sm font-medium">
            Status
          </label>
          <select
            id="status"
            name="status"
            defaultValue={status ?? ""}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Open work</option>
            <option value="ORDERED">Ordered</option>
            <option value="COLLECTED">Collected</option>
            <option value="RESULTED">Resulted</option>
            <option value="VERIFIED">Verified</option>
          </select>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Apply
        </button>
      </form>

      {/*
        The scan box. A handheld scanner is a keyboard that types the barcode and presses Enter, so
        this is a plain GET form with autoFocus - a technician at the bench scans without touching
        the mouse. Separate from the filter form above because it navigates rather than filters.
      */}
      <form action="/laboratory/scan" method="get" className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="accession" className="block text-sm font-medium">
            Scan a tube
          </label>
          <input
            id="accession"
            name="accession"
            autoFocus
            autoComplete="off"
            placeholder="L2026-000042"
            className="numeric mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
        >
          Open
        </button>
      </form>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title="Worklist">
        {orders.length === 0 ? (
          <Empty>Nothing outstanding.</Empty>
        ) : (
          <Table
            head={["Ordered", "Accession", "MRN", "Priority", "Tests", "Results", "Status", ""]}
          >
            {orders.map((order) => (
              <tr key={order.id} className={order.priority === "STAT" ? "bg-critical-soft/40" : ""}>
                <td className="numeric px-3 py-2">{formatDateTime(order.orderedAt)}</td>
                <td className="numeric px-3 py-2">{order.accessionNo ?? "—"}</td>
                <td className="numeric px-3 py-2">
                  <Link href={`/patients/${order.patientId}`} className="text-accent hover:underline">
                    {order.patientMrn}
                  </Link>
                </td>
                <td className="px-3 py-2">
                  {order.priority === "ROUTINE" ? (
                    <span className="text-xs text-ink-muted">routine</span>
                  ) : (
                    <Badge tone={statusTone(order.priority)}>{order.priority}</Badge>
                  )}
                </td>
                <td className="numeric px-3 py-2">{order.testCount}</td>
                <td className="numeric px-3 py-2">{order.resultCount}</td>
                <td className="px-3 py-2">
                  <div className="flex gap-1">
                    <Badge tone={statusTone(order.status)}>{order.status}</Badge>
                    {order.hasAbnormalResults && <Badge tone="critical">abnormal</Badge>}
                  </div>
                </td>
                <td className="px-3 py-2 text-right">
                  <Link href={`/laboratory/${order.id}`} className="text-sm text-accent hover:underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}

/** STAT before urgent before routine; within a priority, results awaiting release come first. */
function byUrgency(a: LabOrderSummary, b: LabOrderSummary): number {
  const priorityRank = { STAT: 0, URGENT: 1, ROUTINE: 2 } as const;
  const priority = priorityRank[a.priority] - priorityRank[b.priority];
  if (priority !== 0) return priority;
  const awaiting = Number(b.status === "RESULTED") - Number(a.status === "RESULTED");
  if (awaiting !== 0) return awaiting;
  return a.orderedAt.localeCompare(b.orderedAt);
}
