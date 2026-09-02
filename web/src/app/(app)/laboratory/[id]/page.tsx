import Link from "next/link";
import { notFound } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { CatalogEntry, Histogram, LabOrder, ReferenceRange } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";
import { SPECIMEN_TYPES } from "../state";
import { cancelOrder, collectSpecimen, verifyOrder } from "../actions";
import { ResultsForm, type ResultRow } from "./ResultsForm";

/**
 * A laboratory report, and the chain of custody that produces it.
 *
 * Abnormal values are the only red on the page, and the reference range sits beside every result,
 * because a number without its range is not interpretable. Where the analyzer sent a distribution
 * curve it is drawn, with the indices derived from it.
 *
 * The write side is three acts owned by three roles, and each one is rendered only for the role
 * that owns it: a technician collects the tube and enters what came off the analyzer, and only a
 * pathologist verifies — which is the same act as releasing the report. The service enforces all
 * three regardless of what this page renders; hiding a button nobody may press is a courtesy, not
 * the control.
 */
export default async function LabOrderPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ problem?: string; done?: string }>;
}) {
  const { id } = await params;
  const { problem, done } = await searchParams;
  const user = await currentUser();

  let order: LabOrder;
  try {
    order = await api<LabOrder>(`/lab/orders/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const specimen = order.specimens.at(-1);

  const mayHandle = hasRole(user, "ADMIN", "LAB_TECH", "PATHOLOGIST");
  const mayVerify = hasRole(user, "ADMIN", "PATHOLOGIST");
  const mayOrder = hasRole(user, "ADMIN", "DOCTOR", "NURSE");
  const settled = order.status === "VERIFIED" || order.status === "CANCELLED";

  // The parameters to offer for hand entry come from the ordered tests' catalogue entries, and
  // each row's unit and interval from the laboratory's configured reference range for this
  // patient's sex. Both are only fetched when somebody may actually enter results - a doctor
  // reading a report has no use for either, and this page is on the clinical read path.
  const rows = mayHandle && !settled ? await resultRows(order) : [];

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

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}

      {order.clinicalNotes && (
        <Card title="Clinical details">
          <p className="text-sm">{order.clinicalNotes}</p>
        </Card>
      )}

      <Card title="Results">
        {order.results.length === 0 ? (
          <Empty>
            {order.status === "CANCELLED"
              ? "This order was cancelled before anything was recorded."
              : mayHandle
                ? "No results yet. Enter them below, or let the analyzer transmit them."
                : "No results yet. Awaiting the analyzer or the bench."}
          </Empty>
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

      {/*
        The chain of custody, in the order it happens. Each section is gated on the role the
        service gates the endpoint on, and each says what pressing the button actually does -
        because "verify" reads like a check and is in fact the release.
      */}
      {mayHandle && !settled && (
        <Card title={specimen ? "Collect another specimen" : "Collect the specimen"}>
          <form action={collectSpecimen} className="flex flex-wrap items-end gap-3">
            <input type="hidden" name="orderId" value={order.id} />
            <div>
              <label htmlFor="specimenType" className="block text-sm font-medium">
                Specimen type
              </label>
              <select
                id="specimenType"
                name="specimenType"
                defaultValue=""
                className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
              >
                {/* Blank is legal: the service falls back to the ordered test's own type. */}
                <option value="">From the ordered test</option>
                {SPECIMEN_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="submit"
              className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
            >
              Collect
            </button>
          </form>
          <p className="mt-2 text-xs text-ink-muted">
            Collecting issues an accession number from a database sequence and prints on the label
            as a barcode a handheld reads. That number, not the patient&apos;s name, is what the
            analyzer sends back.
          </p>
        </Card>
      )}

      {mayHandle && !settled && rows.length > 0 && (
        <Card title="Enter results">
          <ResultsForm orderId={order.id} rows={rows} />
        </Card>
      )}

      {order.results.length > 0 && order.status !== "CANCELLED" && (
        <Card title="Release">
          {order.status === "VERIFIED" ? (
            <p className="text-sm text-ink-muted">
              Released. Every result carries who verified it and when, and the report PDF above is
              the final one rather than a provisional.
            </p>
          ) : mayVerify ? (
            <form action={verifyOrder}>
              <input type="hidden" name="orderId" value={order.id} />
              <button
                type="submit"
                className="rounded-md border border-good/50 px-3 py-2 text-sm font-medium text-good hover:bg-good-soft"
              >
                Verify and release {order.results.length} result(s)
              </button>
              <p className="mt-1.5 text-xs text-ink-muted">
                One step, not two. Verifying <strong>is</strong> the release: the report stops being
                watermarked provisional and becomes the thing another clinician treats from. Every
                result is stamped with your name.
              </p>
            </form>
          ) : (
            <p className="text-sm text-ink-muted">
              {order.results.length} result(s) are entered and provisional. A pathologist verifies
              them, which releases the report — whoever ran the sample does not sign it off.
            </p>
          )}
        </Card>
      )}

      {mayOrder && order.results.length === 0 && order.status !== "CANCELLED" && (
        <Card title="Cancel this order">
          <form action={cancelOrder}>
            <input type="hidden" name="orderId" value={order.id} />
            <button
              type="submit"
              className="rounded-md border border-critical/50 px-3 py-2 text-sm font-medium text-critical hover:bg-critical-soft"
            >
              Cancel the order
            </button>
            <p className="mt-1.5 text-xs text-ink-muted">
              Only while nothing has been recorded. Once a result exists the service refuses —
              a number that was produced cannot be made not to have been.
            </p>
          </form>
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
 * The rows the hand-entry form offers, and what each one is measured against.
 *
 * <p>Two reads, both of them configuration rather than patient data. The catalogue says which
 * parameters an ordered test reports — a full blood count is not one number — and the reference
 * ranges supply each row's unit and its interval for this patient's sex. Prefilling the unit is
 * not cosmetic: a WBC typed as 7.36 and one typed as 7360 are the same measurement on two scales,
 * and a threshold written against one never fires against the other.
 *
 * <p>An order whose patient has no sex recorded gets no interval, deliberately. The service
 * applies none either, and a blank here is the honest rendering of that rather than a male one
 * chosen by default.
 */
async function resultRows(order: LabOrder): Promise<ResultRow[]> {
  const [{ data: catalog }, { data: ranges }] = await Promise.all([
    load<CatalogEntry[]>("/lab/catalog"),
    load<ReferenceRange[]>("/lab/reference-ranges"),
  ]);

  const ordered = new Set(order.items.map((item) => item.testCode));
  const parameters = (catalog ?? [])
    .filter((entry) => ordered.has(entry.code))
    .flatMap((entry) => entry.parameters);

  const sex = (order.patientSex ?? "").toUpperCase();
  const interval = new Map(
    (ranges ?? [])
      .filter((range) => range.sex.toUpperCase() === sex)
      .map((range) => [range.parameter.toUpperCase(), range]),
  );
  const recorded = new Map(
    order.results.map((result) => [result.parameter.toUpperCase(), result]),
  );

  // Deduplicated: two ordered panels can report the same parameter, and the service keeps one
  // current value per parameter, so two rows for it would be two inputs fighting over one row.
  return [...new Set(parameters.map((parameter) => parameter.toUpperCase()))].map((parameter) => {
    const range = interval.get(parameter);
    const existing = recorded.get(parameter);
    return {
      parameter,
      displayName: range?.displayName || existing?.displayName || parameter,
      unit: existing?.unit || range?.unit || "",
      referenceRange: range?.referenceRange ?? "",
      existing: existing?.value ?? null,
    };
  });
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
