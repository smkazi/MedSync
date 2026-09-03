import Link from "next/link";
import { load } from "@/lib/load";
import type { ImagingWorklistEntry } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { PRIORITY_TONES } from "../priority";

/**
 * The reporting queue: scanned, and nobody has read it yet.
 *
 * <p>The radiologist's list, and the mirror of the radiography room's. A row leaves the worklist
 * when the images arrive and appears here; it leaves here when a report is signed. An examination
 * sitting on neither list is either not booked or finished, which is the only pair of states this
 * department has to keep track of.
 *
 * <p>Priority before time again, for the reason it always is: an unread STAT head CT is the whole
 * reason the study was expedited, and a queue that lost that ordering after the scan would have
 * thrown the urgency away at the last step.
 *
 * <p>No clinical question here either, even though a radiologist very much needs one — it is on the
 * examination itself, one click away, where it is read beside the images rather than off a list.
 */
export default async function ReportingQueuePage() {
  const { data, error } = await load<ImagingWorklistEntry[]>("/imaging/reporting-queue");
  const rows = data ?? [];

  const urgent = rows.filter((row) => row.priority !== "ROUTINE");
  const oldest = rows.reduce<string | null>(
    (earliest, row) =>
      row.scheduledFor && (earliest === null || row.scheduledFor < earliest)
        ? row.scheduledFor
        : earliest,
    null,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Reporting queue</h1>
        <p className="text-sm text-ink-muted">
          Examinations that have been acquired and not yet reported.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Unread" value={rows.length} />
        <Stat label="Urgent or STAT" value={urgent.length} hint="report these first" />
        <Stat
          label="Longest waiting"
          value={oldest ? formatDateTime(oldest) : "—"}
          hint="booked for"
        />
      </div>

      <Card title="Unread examinations">
        {rows.length === 0 ? (
          <Empty>
            Nothing unread. An examination appears here as soon as its images are filed against it.
          </Empty>
        ) : (
          <Table head={["Accession", "Patient", "Examination", "Priority", "Booked for", ""]}>
            {rows.map((row) => (
              <tr key={row.id} className="border-t border-line">
                <td className="numeric px-3 py-2">{row.accessionNo}</td>
                <td className="numeric px-3 py-2">
                  {row.patientMrn}
                  <span className="ml-2 text-xs text-ink-muted">
                    {row.patientSex}
                    {row.patientBirthDate ? ` · ${row.patientBirthDate}` : ""}
                  </span>
                </td>
                <td className="px-3 py-2">
                  {row.procedureName}
                  {row.contrast && (
                    <span className="ml-2">
                      <Badge tone="warn">contrast</Badge>
                    </span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <Badge tone={PRIORITY_TONES[row.priority]}>{row.priority.toLowerCase()}</Badge>
                </td>
                <td className="numeric px-3 py-2 text-xs">
                  {row.scheduledFor ? formatDateTime(row.scheduledFor) : "—"}
                </td>
                <td className="px-3 py-2">
                  <Link href={`/imaging/${row.id}`} className="text-sm text-accent underline">
                    Report
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
