import Link from "next/link";
import { notFound } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import type { Histogram, LabOrder } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";

/**
 * A laboratory report.
 *
 * Abnormal values are the only red on the page, and the reference range sits beside every result,
 * because a number without its range is not interpretable. Where the analyzer sent a distribution
 * curve it is drawn, with the indices derived from it.
 */
export default async function LabOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  let order: LabOrder;
  try {
    order = await api<LabOrder>(`/lab/orders/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const specimen = order.specimens.at(-1);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">
            {order.items.map((item) => item.testName).join(", ") || "Laboratory order"}
          </h1>
          <p className="numeric text-sm text-ink-muted">
            <Link href={`/patients/${order.patientId}`} className="text-accent hover:underline">
              {order.patientMrn}
            </Link>{" "}
            · ordered {formatDateTime(order.orderedAt)} by {order.orderedBy}
            {specimen ? ` · accession ${specimen.accessionNo}` : ""}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {order.priority !== "ROUTINE" && (
            <Badge tone={statusTone(order.priority)}>{order.priority}</Badge>
          )}
          <Badge tone={statusTone(order.status)}>{order.status}</Badge>
          {order.hasAbnormalResults && <Badge tone="critical">abnormal results</Badge>}
          {order.specimens.length > 0 && (
            <Link
              href={`/laboratory/${order.id}/labels`}
              className="rounded border border-line px-3 py-1 text-sm hover:bg-surface"
            >
              Print labels
            </Link>
          )}
          {order.results.length > 0 && (
            <a
              href={`/laboratory/${order.id}/report`}
              target="_blank"
              rel="noopener"
              className="rounded border border-line px-3 py-1 text-sm hover:bg-surface"
            >
              {/* Before release it is still available, watermarked PROVISIONAL - so the label says so. */}
              {order.status === "VERIFIED" ? "Report PDF" : "Provisional PDF"}
            </a>
          )}
        </div>
      </div>

      {order.clinicalNotes && (
        <Card title="Clinical details">
          <p className="text-sm">{order.clinicalNotes}</p>
        </Card>
      )}

      <Card title="Results">
        {order.results.length === 0 ? (
          <Empty>No results yet. Awaiting the analyzer or manual entry.</Empty>
        ) : (
          <Table head={["Parameter", "Value", "Unit", "Reference", "Flag", "Source", "Status"]}>
            {order.results.map((result) => (
              <tr key={result.id} className={result.abnormal ? "bg-critical-soft/40" : ""}>
                <td className="px-3 py-2 font-medium">{result.displayName}</td>
                <td
                  className={`numeric px-3 py-2 ${result.abnormal ? "font-bold text-critical" : ""}`}
                >
                  {result.value ?? "—"}
                </td>
                <td className="px-3 py-2 text-ink-muted">{result.unit}</td>
                <td className="numeric px-3 py-2 text-ink-muted">{result.referenceRange || "—"}</td>
                <td className="px-3 py-2">
                  {result.flag ? (
                    <Badge tone="critical">{result.flag === "H" ? "high" : "low"}</Badge>
                  ) : (
                    <span className="text-xs text-ink-muted">—</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <span className="text-xs text-ink-muted">{result.source.toLowerCase()}</span>
                </td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(result.status)}>{result.status}</Badge>
                </td>
              </tr>
            ))}
          </Table>
        )}
        {order.results.some((result) => result.source === "DERIVED") && (
          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            Values marked <em>derived</em> were computed from the analyzer&apos;s distribution curve
            rather than measured directly. Where the instrument reported a parameter itself, its own
            value is shown.
          </p>
        )}
      </Card>

      {order.interpretation && (order.interpretation.notes.length > 0 || order.interpretation.morphology) && (
        <Card title="Interpretation">
          {order.interpretation.morphology && (
            <dl className="mb-3 space-y-1 text-sm">
              {order.interpretation.morphology.comment ? (
                <div className="flex gap-2">
                  <dt className="w-28 shrink-0 text-ink-muted">Smear</dt>
                  <dd>{order.interpretation.morphology.comment}</dd>
                </div>
              ) : (
                <>
                  {order.interpretation.morphology.redCells && (
                    <div className="flex gap-2">
                      <dt className="w-28 shrink-0 text-ink-muted">Red cells</dt>
                      <dd>{order.interpretation.morphology.redCells}</dd>
                    </div>
                  )}
                  {order.interpretation.morphology.whiteCells && (
                    <div className="flex gap-2">
                      <dt className="w-28 shrink-0 text-ink-muted">White cells</dt>
                      <dd>{order.interpretation.morphology.whiteCells}</dd>
                    </div>
                  )}
                  {order.interpretation.morphology.platelets && (
                    <div className="flex gap-2">
                      <dt className="w-28 shrink-0 text-ink-muted">Platelets</dt>
                      <dd>{order.interpretation.morphology.platelets}</dd>
                    </div>
                  )}
                </>
              )}
            </dl>
          )}

          {order.interpretation.notes.length > 0 && (
            <ul className="list-disc space-y-1 pl-5 text-sm">
              {order.interpretation.notes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          )}

          {/*
            Said plainly rather than buried. A reader is entitled to know which sentences on a
            report a person wrote and which the platform worked out from the numbers - and a
            pathologist who enters a smear comment overrides the derived one entirely.
          */}
          {order.interpretation.morphology?.derived !== false && (
            <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
              Derived from the measured indices and the laboratory&apos;s configured rules. Decision
              support for the reporting pathologist, not a diagnosis.
            </p>
          )}
        </Card>
      )}

      {order.histograms.length > 0 && (
        <Card title="Analyzer distributions">
          <div className="grid gap-6 md:grid-cols-3">
            {order.histograms.map((histogram) => (
              <HistogramChart key={histogram.group} histogram={histogram} />
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

/**
 * The distribution curve, drawn as inline SVG.
 *
 * No charting library: this is one polyline over a fixed axis, and a dependency would add weight
 * and a supply-chain surface for nothing.
 */
function HistogramChart({ histogram }: { histogram: Histogram }) {
  const width = 260;
  const height = 120;
  const peak = Math.max(...histogram.y, 1);
  const points = histogram.y
    .map((value, index) => {
      const x = (index / Math.max(histogram.y.length - 1, 1)) * width;
      const y = height - (value / peak) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");

  const indices = Object.entries(histogram.indices).filter(([key]) => key !== "rel_area");

  return (
    <figure>
      <figcaption className="mb-1 text-sm font-medium">{histogram.group}</figcaption>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        className="w-full rounded border border-line bg-surface"
        role="img"
        aria-label={`${histogram.group} distribution across ${histogram.y.length} channels`}
      >
        <polyline
          points={points}
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          className="text-accent"
        />
      </svg>
      <div className="mt-1 flex justify-between text-xs text-ink-muted">
        <span className="numeric">{histogram.x[0]?.toFixed(0) ?? 0}</span>
        <span>{histogram.xLabel}</span>
        <span className="numeric">{histogram.x.at(-1)?.toFixed(0) ?? 0}</span>
      </div>
      {indices.length > 0 && (
        <dl className="mt-2 space-y-0.5 text-xs">
          {indices.map(([key, value]) => (
            <div key={key} className="flex justify-between">
              <dt className="text-ink-muted">{key}</dt>
              <dd className="numeric">{value.toFixed(1)}</dd>
            </div>
          ))}
        </dl>
      )}
    </figure>
  );
}
