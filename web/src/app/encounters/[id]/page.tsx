import Link from "next/link";
import { notFound } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { currentUser, hasRole } from "@/lib/session";
import type { Encounter } from "@/lib/types";
import { AiAssist } from "@/components/AiAssist";
import {
  Badge,
  Card,
  Empty,
  Table,
  formatDateTime,
  statusTone,
} from "@/components/ui";

/**
 * The charting screen.
 *
 * A note's revision history is shown, not just its current text: an addendum only means something
 * if you can read what it amended. AI assistance sits beside the note, never inside it.
 */
export default async function EncounterPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const user = await currentUser();

  let encounter: Encounter;
  try {
    encounter = await api<Encounter>(`/encounters/${id}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const current = encounter.notes.at(-1) ?? null;
  const latestVitals = encounter.vitals.at(0) ?? null;
  const noteText = current
    ? [current.subjective, current.objective, current.assessment, current.plan]
        .filter(Boolean)
        .join("\n")
    : "";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Encounter</h1>
          <p className="numeric text-sm text-ink-muted">
            <Link href={`/patients/${encounter.patientId}`} className="text-accent hover:underline">
              {encounter.patientMrn}
            </Link>{" "}
            · {encounter.encounterType.toLowerCase()} · {encounter.departmentCode} · started{" "}
            {formatDateTime(encounter.startedAt)}
          </p>
        </div>
        <div className="flex gap-2">
          <Badge tone={statusTone(encounter.status)}>{encounter.status}</Badge>
          {current && (
            <Badge tone={current.signed ? "good" : "warn"}>
              {current.signed ? `signed rev ${current.revision}` : `rev ${current.revision} unsigned`}
            </Badge>
          )}
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card title="Clinical note">
            {!current ? (
              <Empty>No note recorded on this encounter yet.</Empty>
            ) : (
              <div className="space-y-3 text-sm">
                <NoteSection label="Subjective" text={current.subjective} />
                <NoteSection label="Objective" text={current.objective} />
                <NoteSection label="Assessment" text={current.assessment} />
                <NoteSection label="Plan" text={current.plan} />
                <p className="border-t border-line pt-2 text-xs text-ink-muted">
                  Revision {current.revision} by {current.author}
                  {current.signed
                    ? ` · signed by ${current.signedBy} at ${formatDateTime(current.signedAt)}`
                    : " · unsigned"}
                  {current.amendsId ? " · amends an earlier signed revision" : ""}
                </p>
              </div>
            )}
          </Card>

          {encounter.notes.length > 1 && (
            <Card title="Revision history">
              <p className="mb-3 text-xs text-ink-muted">
                A signed note is never overwritten. Each correction is a new revision, and the
                original stays readable.
              </p>
              <Table head={["Rev", "Author", "Signed", "Assessment"]}>
                {encounter.notes.map((note) => (
                  <tr key={note.id} className={note.id === current?.id ? "bg-accent-soft/40" : ""}>
                    <td className="numeric px-3 py-2">{note.revision}</td>
                    <td className="px-3 py-2">{note.author}</td>
                    <td className="px-3 py-2">
                      {note.signed ? (
                        <Badge tone="good">{note.signedBy}</Badge>
                      ) : (
                        <Badge tone="warn">unsigned</Badge>
                      )}
                    </td>
                    <td className="px-3 py-2">{note.assessment ?? "—"}</td>
                  </tr>
                ))}
              </Table>
            </Card>
          )}

          <Card title="Diagnoses">
            {encounter.diagnoses.length === 0 ? (
              <Empty>No diagnoses coded.</Empty>
            ) : (
              <Table head={["Code", "Description", "Category", "Recorded by"]}>
                {encounter.diagnoses.map((diagnosis) => (
                  <tr key={diagnosis.id}>
                    <td className="numeric px-3 py-2 font-medium">{diagnosis.icd10Code}</td>
                    <td className="px-3 py-2">{diagnosis.description}</td>
                    <td className="px-3 py-2">
                      <Badge tone={diagnosis.category === "PRIMARY" ? "accent" : "neutral"}>
                        {diagnosis.category.toLowerCase()}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 text-ink-muted">{diagnosis.recordedBy}</td>
                  </tr>
                ))}
              </Table>
            )}
          </Card>
        </div>

        <div className="space-y-6">
          <Card title="Latest observations">
            {!latestVitals ? (
              <Empty>No vitals recorded.</Empty>
            ) : (
              <dl className="space-y-1.5 text-sm">
                <Vital label="Heart rate" value={latestVitals.heartRate} unit="bpm" />
                <Vital
                  label="Blood pressure"
                  value={
                    latestVitals.systolicBp && latestVitals.diastolicBp
                      ? `${latestVitals.systolicBp}/${latestVitals.diastolicBp}`
                      : null
                  }
                  unit="mmHg"
                />
                <Vital label="Respiratory rate" value={latestVitals.respiratoryRate} unit="/min" />
                <Vital label="Temperature" value={latestVitals.temperatureC} unit="°C" />
                <Vital label="SpO2" value={latestVitals.oxygenSaturation} unit="%" />
                <Vital label="Pain" value={latestVitals.painScore} unit="/10" />
                <Vital label="BMI" value={latestVitals.bodyMassIndex} unit="" />
                <p className="border-t border-line pt-2 text-xs text-ink-muted">
                  {formatDateTime(latestVitals.recordedAt)} by {latestVitals.recordedBy}
                </p>
              </dl>
            )}
          </Card>

          {hasRole(user, "ADMIN", "DOCTOR", "NURSE") && (
            <Card title="Decision support">
              <AiAssist noteText={noteText} />
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

function NoteSection({ label, text }: { label: string; text: string | null }) {
  if (!text) return null;
  return (
    <div>
      <div className="text-xs font-semibold uppercase tracking-wide text-ink-muted">{label}</div>
      <p className="whitespace-pre-wrap">{text}</p>
    </div>
  );
}

function Vital({
  label,
  value,
  unit,
}: {
  label: string;
  value: number | string | null;
  unit: string;
}) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-ink-muted">{label}</dt>
      <dd className="numeric">
        {value === null ? "—" : `${value}${unit ? ` ${unit}` : ""}`}
      </dd>
    </div>
  );
}
