import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError, api } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { ImagingOrder, ImagingReport, ImagingStudy } from "@/lib/types";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";
import { PRIORITY_TONES } from "../priority";
import { CancelForm } from "./CancelForm";
import { ReportForm } from "./ReportForm";
import { SignForm } from "./SignForm";

/**
 * One examination: what was asked, what came off the machine, and what it was read as.
 *
 * <p>Three roles read this page and each sees the part that is theirs. A radiographer sees the
 * request and the studies filed against it; a radiologist sees those and the editor; the clinician
 * who asked sees the request and, once it is signed, the report. The service enforces every one of
 * those regardless of what this page renders — hiding a control nobody may use is a courtesy, not
 * the control.
 *
 * <p>The clinical question is here rather than on the worklist, and this is where it belongs: it is
 * read beside the images by the person answering it, not off a list on a screen in a corridor.
 *
 * <p><strong>A draft is labelled as a draft, loudly.</strong> The requester cannot see one at all,
 * and a radiologist looking at their own has to be able to tell at a glance that nobody else can —
 * an unreleased finding that reads like a released one is how a report gets assumed to have been
 * communicated.
 */
export default async function ImagingOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const user = await currentUser();

  let order: ImagingOrder;
  try {
    order = await api<ImagingOrder>(`/imaging/orders/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    // 403 is the care-relationship narrowing, and it arrives with the platform's own sentence
    // about how to open the chart. Rendering it beats a stack trace.
    if (error instanceof ApiError && error.status === 403) {
      return (
        <div className="space-y-4">
          <h1 className="text-xl font-semibold tracking-tight">Examination</h1>
          <ErrorNote>{error.detail}</ErrorNote>
          <p className="text-sm text-ink-muted">
            An examination is part of the encounter it was raised on, so reading it needs the same
            care relationship the chart does.
          </p>
        </div>
      );
    }
    throw error;
  }

  const mayReport = hasRole(user, "ADMIN", "RADIOLOGIST");
  const mayCancel = hasRole(user, "ADMIN", "DOCTOR", "NURSE");
  const cancellable = order.status === "ORDERED" || order.status === "SCHEDULED";

  // The report hangs off a study, not off the order: a report is a reading of images, and until
  // images exist there is nothing to read. The last study is the one being reported — a repeated
  // acquisition supersedes the earlier attempt.
  const study: ImagingStudy | undefined = order.studies.at(-1);
  const report: ImagingReport | null = study?.report ?? null;
  const released = report !== null && report.status !== "DRAFT";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">
            {order.procedureName}{" "}
            <span className="numeric text-base font-normal text-ink-muted">
              {order.accessionNo}
            </span>
          </h1>
          <p className="text-sm text-ink-muted">
            {order.modality}
            {order.bodyPart ? ` · ${order.bodyPart}` : ""} · requested by {order.orderedBy} on{" "}
            {formatDateTime(order.orderedAt)}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={PRIORITY_TONES[order.priority]}>{order.priority.toLowerCase()}</Badge>
          <Badge tone={statusTone(order.status)}>
            {order.status.toLowerCase().replace(/_/g, " ")}
          </Badge>
          {order.contrast && <Badge tone="warn">contrast</Badge>}
        </div>
      </div>

      {order.status === "CANCELLED" && order.cancelledReason && (
        <ErrorNote>This request was withdrawn: {order.cancelledReason}</ErrorNote>
      )}

      <Card title="The request">
        <dl className="grid gap-4 sm:grid-cols-2">
          <div>
            <dt className="text-xs text-ink-muted">Patient</dt>
            <dd className="numeric text-sm">
              <Link href={`/patients/${order.patientId}`} className="text-accent underline">
                {order.patientMrn}
              </Link>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-ink-muted">Booked for</dt>
            <dd className="numeric text-sm">
              {order.scheduledFor ? formatDateTime(order.scheduledFor) : "no slot yet"}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-xs text-ink-muted">Clinical question</dt>
            {/*
              `whitespace-pre-wrap`, because a clinician typing a question puts line breaks in it
              and a paragraph reflowed into one line reads as a different question.
            */}
            <dd className="whitespace-pre-wrap text-sm">{order.clinicalQuestion}</dd>
          </div>
          {order.encounterId && (
            <div className="sm:col-span-2">
              <dt className="text-xs text-ink-muted">Raised from</dt>
              <dd className="text-sm">
                <Link
                  href={`/encounters/${order.encounterId}`}
                  className="text-accent underline"
                >
                  the encounter it was ordered on
                </Link>
              </dd>
            </div>
          )}
        </dl>
      </Card>

      <Card title="What came off the modality">
        {order.studies.length === 0 ? (
          <Empty>
            Nothing filed yet. A study appears here when an image carrying{" "}
            <span className="numeric">{order.accessionNo}</span> is filed — that number is what
            attaches images to this request.
          </Empty>
        ) : (
          <div className="space-y-4">
            {order.studies.map((filed) => (
              <div key={filed.id} className="rounded-md border border-line p-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <p className="text-sm font-medium">
                    {filed.studyDescription || "Study"}{" "}
                    <span className="text-xs font-normal text-ink-muted">
                      received {formatDateTime(filed.receivedAt)}
                    </span>
                  </p>
                  <p className="numeric text-xs text-ink-muted">{filed.studyInstanceUid}</p>
                </div>
                <Table head={["Series", "Modality", "Body part", "Description", "Instances", "Pixels"]}>
                  {filed.series.map((series) => (
                    <tr key={series.id} className="border-t border-line">
                      <td className="numeric px-3 py-2">{series.seriesNumber ?? "—"}</td>
                      <td className="px-3 py-2">{series.modality ?? "—"}</td>
                      <td className="px-3 py-2">{series.bodyPart ?? "—"}</td>
                      <td className="px-3 py-2">{series.seriesDescription ?? "—"}</td>
                      <td className="numeric px-3 py-2">{series.instanceCount}</td>
                      <td className="px-3 py-2">
                        {series.stored ? (
                          <Badge tone="good">archived</Badge>
                        ) : (
                          <Badge tone="neutral">not stored</Badge>
                        )}
                      </td>
                    </tr>
                  ))}
                </Table>
              </div>
            ))}
            {/*
              Said once, under the studies, wherever no series has pixels. This is a RIS and not a
              PACS: with no archive configured the platform holds the record of the examination and
              the images are wherever the modality put them. Saying nothing here would let a
              clinician assume there is something to click.
            */}
            {order.studies.every((filed) => filed.series.every((series) => !series.stored)) && (
              <p className="text-xs text-ink-muted">
                No archive is configured, so the images themselves are not held here — the record of
                the examination is. There is no viewer either: the platform has never claimed to be
                a PACS, and the README says so among what is not built.
              </p>
            )}
          </div>
        )}
      </Card>

      <Card title="Report">
        {report === null ? (
          <Empty>
            {study
              ? "Not reported yet."
              : "Nothing to report yet — no images have been filed against this request."}
          </Empty>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              {report.status === "DRAFT" ? (
                <Badge tone="warn">draft — not released</Badge>
              ) : (
                <Badge tone="good">{report.status.toLowerCase()}</Badge>
              )}
              <span className="text-xs text-ink-muted">
                written by {report.reportedBy} on {formatDateTime(report.reportedAt)}
                {report.signedBy
                  ? ` · signed by ${report.signedBy} on ${formatDateTime(report.signedAt)}`
                  : ""}
              </span>
            </div>

            {report.status === "DRAFT" && (
              <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-xs text-warn">
                This is a draft. Nobody who asked for the examination can see it, and nobody should
                treat from it. Signing is what releases it.
              </p>
            )}

            <div>
              <h3 className="text-sm font-medium">Findings</h3>
              <p className="mt-1 whitespace-pre-wrap text-sm">{report.findings}</p>
            </div>
            <div>
              <h3 className="text-sm font-medium">Impression</h3>
              <p className="mt-1 whitespace-pre-wrap text-sm">{report.impression}</p>
            </div>

            {report.amendedFrom && (
              <div className="rounded-md border border-line bg-surface p-3">
                <h3 className="text-sm font-medium">What was signed before this amendment</h3>
                <p className="text-xs text-ink-muted">
                  Kept because somebody may have acted on it.
                  {report.amendedReason ? ` Amended: ${report.amendedReason}` : ""}
                </p>
                <p className="mt-2 whitespace-pre-wrap text-sm text-ink-muted">
                  {report.amendedFrom}
                </p>
              </div>
            )}
          </div>
        )}
      </Card>

      {mayReport && study && report !== null && report.status === "DRAFT" && (
        <Card title="Release this report">
          <SignForm studyId={study.id} />
        </Card>
      )}

      {mayReport && study && (
        <Card title={released ? "Amend the report" : "Write the report"}>
          <ReportForm
            studyId={study.id}
            findings={report?.findings ?? ""}
            impression={report?.impression ?? ""}
            signed={released}
          />
        </Card>
      )}

      {mayCancel && cancellable && (
        <Card title="Withdraw this request" tone="critical">
          <CancelForm orderId={order.id} />
        </Card>
      )}
    </div>
  );
}
