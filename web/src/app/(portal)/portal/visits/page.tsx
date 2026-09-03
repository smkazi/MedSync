import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, formatDateTime } from "@/components/ui";
import type { Encounter } from "@/lib/types";

export const metadata = { title: "Your visits — MedSync" };

/**
 * Every visit, in full: what was written, what was measured, what was diagnosed.
 *
 * <p>The full record rather than a list of dates, because that is what "view" means in the
 * certification criterion this satisfies and because a list of dates satisfies the word and none of
 * its purpose. What a clinician wrote about a consultation is what the patient came here to read.
 *
 * <p>Only signed notes reach this screen. The platform drops the drafts before they leave
 * scheduling-service, so there is no filter here to forget: a draft is a sentence somebody is still
 * deciding whether they believe, and showing it to its subject makes it a statement they never
 * made.
 */
export default async function PortalVisits() {
  const encounters = await load<Encounter[]>("/portal/encounters");
  const visits = encounters.data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your visits</h1>
        <p className="mt-1 text-sm text-ink-muted">
          What was recorded at each consultation, once the clinician had signed it.
        </p>
      </div>

      {encounters.error ? <ErrorNote>{encounters.error}</ErrorNote> : null}
      {visits.length === 0 ? (
        <Card title="Visits">
          <Empty>No consultations on your record yet.</Empty>
        </Card>
      ) : (
        visits.map((visit) => {
          const note = visit.notes.at(-1) ?? null;
          const vitals = visit.vitals.at(-1) ?? null;
          return (
            <Card
              key={visit.id}
              title={`${formatDateTime(visit.startedAt)} · ${visit.departmentCode}`}
              action={<Badge tone="neutral">{visit.encounterType}</Badge>}
            >
              <div className="space-y-4 text-sm">
                {visit.diagnoses.length > 0 ? (
                  <div>
                    <h3 className="font-medium">Diagnoses</h3>
                    <ul className="mt-1 space-y-1 text-ink-muted">
                      {visit.diagnoses.map((diagnosis) => (
                        <li key={diagnosis.id}>
                          {diagnosis.description}{" "}
                          <span className="text-xs">({diagnosis.icd10Code})</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                ) : null}

                {note ? (
                  <div>
                    <h3 className="font-medium">What was recorded</h3>
                    <dl className="mt-1 space-y-2 text-ink-muted">
                      {note.subjective ? (
                        <div>
                          <dt className="text-xs uppercase tracking-wide">What you described</dt>
                          <dd>{note.subjective}</dd>
                        </div>
                      ) : null}
                      {note.objective ? (
                        <div>
                          <dt className="text-xs uppercase tracking-wide">What was examined</dt>
                          <dd>{note.objective}</dd>
                        </div>
                      ) : null}
                      {note.assessment ? (
                        <div>
                          <dt className="text-xs uppercase tracking-wide">The clinician&apos;s assessment</dt>
                          <dd>{note.assessment}</dd>
                        </div>
                      ) : null}
                      {note.plan ? (
                        <div>
                          <dt className="text-xs uppercase tracking-wide">The plan</dt>
                          <dd>{note.plan}</dd>
                        </div>
                      ) : null}
                    </dl>
                  </div>
                ) : (
                  <Empty>
                    Nothing has been signed for this visit yet. A clinician&apos;s notes appear here
                    once they have finished them.
                  </Empty>
                )}

                {vitals ? (
                  <div>
                    <h3 className="font-medium">Observations</h3>
                    <p className="mt-1 text-ink-muted">
                      {[
                        vitals.systolicBp && vitals.diastolicBp
                          ? `Blood pressure ${vitals.systolicBp}/${vitals.diastolicBp}`
                          : null,
                        vitals.heartRate ? `Pulse ${vitals.heartRate}` : null,
                        vitals.temperatureC ? `Temperature ${vitals.temperatureC}°C` : null,
                        vitals.oxygenSaturation ? `Oxygen ${vitals.oxygenSaturation}%` : null,
                        vitals.weightKg ? `Weight ${vitals.weightKg} kg` : null,
                      ]
                        .filter(Boolean)
                        .join(" · ") || "Recorded, with no measurements to show."}
                    </p>
                  </div>
                ) : null}
              </div>
            </Card>
          );
        })
      )}
    </div>
  );
}
