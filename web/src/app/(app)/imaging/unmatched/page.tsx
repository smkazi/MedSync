import { load } from "@/lib/load";
import type { ImagingStudy } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";

/**
 * Studies whose accession number matched no request.
 *
 * <p>These exist because the platform will not guess. A DICOM header carries patient identifiers,
 * and they are whatever was typed at the modality console — so filing on those would attach a study
 * to the wrong visit the first time somebody was scanned twice in a day, and to the wrong patient
 * the first time a name was mistyped. The accession number is the one field the worklist puts into
 * the machine and the machine writes back into every image, so it is what matching uses, and a
 * study whose number names nothing is a study nobody can safely attribute.
 *
 * <p>It is not discarded either. The images exist on a scanner's disk whatever this platform makes
 * of them, and somebody in the department has the day's paperwork and can work out whose they are.
 * This screen is that list, and its job is to be short.
 *
 * <p>What resolving one looks like today is honest and manual: work out the patient, raise the
 * request that should have existed, and have the modality resend against the accession number it
 * gets. Re-filing an already-registered study against an order is named in the README's gaps rather
 * than half-built — it is a merge, and a merge that guesses is the thing this whole screen exists to
 * avoid.
 */
export default async function UnmatchedStudiesPage() {
  const { data, error } = await load<ImagingStudy[]>("/imaging/studies/unmatched");
  const rows = data ?? [];

  const instances = rows.reduce(
    (total, study) => total + study.series.reduce((n, series) => n + series.instanceCount, 0),
    0,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Unmatched studies</h1>
        <p className="text-sm text-ink-muted">
          Images that arrived carrying an accession number the platform has no request for.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-2">
        <Stat label="Studies" value={rows.length} hint="each needs a person to resolve it" />
        <Stat label="Instances" value={instances} />
      </div>

      <Card title="Waiting to be attributed">
        {rows.length === 0 ? (
          <Empty>
            Nothing unattributed. Every study filed so far matched a request — which is what a
            department whose modalities read the worklist should expect to see here.
          </Empty>
        ) : (
          <Table
            head={[
              "Received",
              "Accession on the image",
              "Study",
              "Modality",
              "Instances",
              "Console said",
              "Pixels",
            ]}
          >
            {rows.map((study) => {
              const count = study.series.reduce((n, series) => n + series.instanceCount, 0);
              const stored = study.series.some((series) => series.stored);
              return (
                <tr key={study.id} className="border-t border-line">
                  <td className="numeric px-3 py-2 text-xs">{formatDateTime(study.receivedAt)}</td>
                  <td className="numeric px-3 py-2">
                    {/*
                      Shown even though it matched nothing, because it is the fact that identifies
                      the study to the modality: whoever resolves this reads it off here and looks
                      for it in the machine's own log.
                    */}
                    {study.accessionNo || <span className="text-ink-muted">none in the header</span>}
                  </td>
                  <td className="px-3 py-2">
                    {study.studyDescription || "—"}
                    <span className="block text-xs text-ink-muted">
                      {study.studyDate ? study.studyDate : "no study date"}
                      {study.institution ? ` · ${study.institution}` : ""}
                    </span>
                  </td>
                  <td className="px-3 py-2">
                    <Badge tone="neutral">{study.modality ?? "?"}</Badge>
                  </td>
                  <td className="numeric px-3 py-2">{count}</td>
                  <td className="px-3 py-2 text-xs">
                    {/*
                      The header's own idea of who this is, labelled as what it is. It is a lead for
                      the person resolving the study and it is not evidence: if it were trustworthy
                      the study would have been filed on it.
                    */}
                    {study.referringPhysician ? `ref. ${study.referringPhysician}` : "—"}
                  </td>
                  <td className="px-3 py-2">
                    {stored ? (
                      <Badge tone="good">archived</Badge>
                    ) : (
                      <Badge tone="warn">header only</Badge>
                    )}
                  </td>
                </tr>
              );
            })}
          </Table>
        )}
      </Card>
    </div>
  );
}
