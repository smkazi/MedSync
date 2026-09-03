import Link from "next/link";
import { load } from "@/lib/load";
import type { ImagingWorklistEntry } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime, statusTone } from "@/components/ui";
import { PRIORITY_TONES } from "./priority";
import { ScheduleForm } from "./ScheduleForm";
import { UploadForm } from "./UploadForm";

/**
 * The modality worklist: what has been asked for and not yet scanned.
 *
 * <p>Priority before time, which is the whole reason it is not a queue — a STAT head CT asked for
 * a minute ago goes ahead of a routine knee film booked this morning. The platform orders it; this
 * screen renders what it was given rather than sorting again, because a list re-sorted by the page
 * showing it can disagree with the one the department is working from.
 *
 * <p><strong>No clinical question here, deliberately.</strong> A worklist lives on a screen beside
 * a scanner, in a room patients walk through, and the API does not return one. What a radiographer
 * needs is who is next, what to do to them, and whether it needs contrast.
 */
export default async function ImagingWorklistPage({
  searchParams,
}: {
  searchParams: Promise<{ modality?: string }>;
}) {
  const { modality } = await searchParams;
  const query = modality ? `?modality=${encodeURIComponent(modality)}` : "";
  const { data, error } = await load<ImagingWorklistEntry[]>(`/imaging/worklist${query}`);
  const rows = data ?? [];

  const urgent = rows.filter((row) => row.priority !== "ROUTINE");
  const contrast = rows.filter((row) => row.contrast);
  const unscheduled = rows.filter((row) => !row.scheduledFor);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Radiology worklist</h1>
        <p className="text-sm text-ink-muted">
          What is booked and not yet acquired, most urgent first.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Waiting" value={rows.length} />
        <Stat label="Urgent or STAT" value={urgent.length} hint="ahead of the routine list" />
        <Stat label="Needs contrast" value={contrast.length} hint="check the consent and the kit" />
        <Stat label="No slot yet" value={unscheduled.length} />
      </div>

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="modality" className="block text-sm font-medium">
            Modality
          </label>
          <select
            id="modality"
            name="modality"
            defaultValue={modality ?? ""}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          >
            <option value="">Every room</option>
            <option value="CR">CR — plain film</option>
            <option value="CT">CT</option>
            <option value="MR">MR</option>
            <option value="US">Ultrasound</option>
            <option value="MG">Mammography</option>
          </select>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Apply
        </button>
      </form>

      <Card title="The list">
        {rows.length === 0 ? (
          <Empty>
            Nothing waiting{modality ? ` for ${modality}` : ""}. A request appears here the moment a
            clinician raises it.
          </Empty>
        ) : (
          <Table
            head={[
              "Accession",
              "Patient",
              "Examination",
              "Priority",
              "Status",
              "Booked for",
              "",
            ]}
          >
            {rows.map((row) => (
              <tr key={row.id} className="border-t border-line">
                <td className="numeric px-3 py-2">
                  <Link href={`/imaging/${row.id}`} className="text-accent underline">
                    {row.accessionNo}
                  </Link>
                </td>
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
                <td className="px-3 py-2">
                  <Badge tone={statusTone(row.status)}>
                    {row.status.toLowerCase().replace(/_/g, " ")}
                  </Badge>
                </td>
                <td className="numeric px-3 py-2 text-xs">
                  {row.scheduledFor ? formatDateTime(row.scheduledFor) : "—"}
                </td>
                <td className="px-3 py-2">
                  <ScheduleForm orderId={row.id} accessionNo={row.accessionNo} />
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="File a study that came off a modality">
        <p className="mb-3 text-sm text-ink-muted">
          One DICOM instance at a time, which is how a scanner sends. The accession number written
          into the image is what files it against its request — so a study whose number matches
          nothing is registered without a patient and lands on{" "}
          <Link href="/imaging/unmatched" className="underline">
            the unmatched list
          </Link>{" "}
          rather than being attached to a guess.
        </p>
        <UploadForm />
      </Card>
    </div>
  );
}
