import Link from "next/link";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { CatalogEntry, Encounter, LabOrderSummary, Patient } from "@/lib/types";
import { AiAssist } from "@/components/AiAssist";
import { RecordForm } from "@/components/RecordForm";
import { orderTests } from "../../laboratory/actions";
import { PRIORITIES } from "../../laboratory/state";
import { closeEncounter, recordVitals, signNote } from "./actions";
import { DiagnosisForm } from "./DiagnosisForm";
import { NoteEditor } from "./NoteEditor";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
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
export default async function EncounterPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ problem?: string; done?: string }>;
}) {
  const { id } = await params;
  const { problem, done } = await searchParams;
  const user = await currentUser();

  // `load` rather than a bare `api` call. Reading a chart needs CHART_READ, so the front desk
  // reaches this path and gets a 403 — and rethrowing that rendered the error boundary, which told
  // a receptionist "A server error occurred" for a permission decision that is not an error at
  // all. This keeps the chrome, shows the service's own wording, and a mistyped id says not found.
  const chart = await load<Encounter>(`/encounters/${id}`);
  if (!chart.data) {
    return (
      <div className="space-y-6">
        <h1 className="text-xl font-semibold tracking-tight">Encounter</h1>
        <ErrorNote>{chart.error ?? "This chart could not be loaded."}</ErrorNote>
      </div>
    );
  }
  const encounter = chart.data;

  const current = encounter.notes.at(-1) ?? null;
  const open = encounter.status === "OPEN";
  const mayChart = hasRole(user, "ADMIN", "DOCTOR", "NURSE");
  // Signing is a doctor's act. A nurse may write the note; only a clinician who can put their name
  // to it may sign, and the service enforces that with hasAnyRole('ADMIN','DOCTOR').
  const maySign = hasRole(user, "ADMIN", "DOCTOR");

  // The laboratory orders raised from this visit, and the catalogue to raise more from. Both are
  // only fetched for somebody who may chart: an encounter's order list is chart content, and the
  // service gates `GET /lab/encounters/{id}/orders` on CHART_READ for exactly that reason.
  const [labOrders, catalog, patient] = mayChart
    ? await Promise.all([
        load<LabOrderSummary[]>(`/lab/encounters/${id}/orders`),
        load<CatalogEntry[]>("/lab/catalog"),
        load<Patient>(`/patients/${encounter.patientId}`),
      ])
    : [
        { data: null, error: null },
        { data: null, error: null },
        { data: null, error: null },
      ];
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

      {problem && <ErrorNote>{problem}</ErrorNote>}
      {done && (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      )}

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

            {mayChart && (
              <div className="mt-4 border-t border-line pt-4">
                <NoteEditor encounterId={encounter.id} current={current} editable={open} />
              </div>
            )}

            {open && current && !current.signed && (
              <div className="mt-4 border-t border-line pt-4">
                {maySign ? (
                  <form action={signNote}>
                    <input type="hidden" name="encounterId" value={encounter.id} />
                    <button
                      type="submit"
                      className="rounded-md border border-good/50 px-3 py-2 text-sm font-medium text-good hover:bg-good-soft"
                    >
                      Sign revision {current.revision}
                    </button>
                    <p className="mt-1.5 text-xs text-ink-muted">
                      Signing is one-way. After it, a correction becomes an amendment rather than an
                      edit, and the signed text stays in the record.
                    </p>
                  </form>
                ) : (
                  <p className="text-xs text-ink-muted">
                    Revision {current.revision} is unsigned. A doctor signs it.
                  </p>
                )}
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

            {mayChart && open && (
              <div className="mt-4 border-t border-line pt-4">
                <DiagnosisForm encounterId={encounter.id} noteText={noteText} />
              </div>
            )}
          </Card>

          {/*
            Computerised provider order entry, on the chart rather than in the laboratory. That is
            what CPOE means and it is not incidental: a clinician ordering a test is already looking
            at the assessment that justifies it, and the order carries the encounter so the visit
            can show what it raised. The laboratory worklist owns everything after this - the tube,
            the numbers, the release.
          */}
          {mayChart && (
            <Card title="Laboratory orders">
              {labOrders.error && <ErrorNote>{labOrders.error}</ErrorNote>}

              {(labOrders.data ?? []).length === 0 ? (
                <Empty>No tests ordered on this visit.</Empty>
              ) : (
                <Table head={["Ordered", "Tests", "Results", "Status", ""]}>
                  {(labOrders.data ?? []).map((order) => (
                    <tr key={order.id}>
                      <td className="numeric px-3 py-2 text-ink-muted">
                        {formatDateTime(order.orderedAt)}
                      </td>
                      <td className="numeric px-3 py-2">
                        {order.testCount} test{order.testCount === 1 ? "" : "s"}
                        {order.priority !== "ROUTINE" && (
                          <Badge tone={statusTone(order.priority)}>{order.priority}</Badge>
                        )}
                      </td>
                      <td className="numeric px-3 py-2">
                        {order.resultCount}
                        {order.hasAbnormalResults && (
                          <span className="ml-2">
                            <Badge tone="critical">abnormal</Badge>
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <Badge tone={statusTone(order.status)}>{order.status}</Badge>
                      </td>
                      <td className="px-3 py-2">
                        <Link
                          href={`/laboratory/${order.id}`}
                          className="text-xs text-accent hover:underline"
                        >
                          Open
                        </Link>
                      </td>
                    </tr>
                  ))}
                </Table>
              )}

              {open && catalog.data && catalog.data.length > 0 && (
                <div className="mt-4 border-t border-line pt-4">
                  <RecordForm
                    action={orderTests}
                    columns={2}
                    submitLabel="Order tests"
                    busyLabel="Ordering…"
                    hidden={{
                      encounterId: encounter.id,
                      patientId: encounter.patientId,
                      patientMrn: encounter.patientMrn,
                      // Translated, not copied. The patient record's vocabulary is
                      // MALE/FEMALE/OTHER/UNKNOWN and the laboratory's reference intervals are
                      // scaled M or F, so OTHER and UNKNOWN map to blank and the order gets no
                      // sex-specific interval - rather than the male one applied by default,
                      // which is what happened silently until laboratory V5.
                      patientSex: labSex(patient.data?.sex),
                      department: encounter.departmentCode,
                    }}
                    fields={[
                      {
                        name: "testCodes",
                        label: "Tests",
                        type: "multicheck",
                        required: true,
                        options: catalog.data.map((entry) => ({
                          value: entry.code,
                          label: `${entry.name} (${entry.code})`,
                        })),
                        hint: "From the laboratory's catalogue. A retired test is refused by name rather than hidden, so an order copied from an old note says why it failed.",
                      },
                      {
                        name: "priority",
                        label: "Priority",
                        type: "select",
                        options: PRIORITIES,
                        value: "ROUTINE",
                      },
                      {
                        name: "clinicalNotes",
                        label: "Clinical details for the laboratory",
                        type: "textarea",
                        hint: "Travels with the order. This is the clinical context a pathologist reads, and it is the reason the lab does not need the chart.",
                      },
                    ]}
                  />
                </div>
              )}
            </Card>
          )}
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

            {mayChart && open && (
              <form action={recordVitals} className="mt-4 space-y-3 border-t border-line pt-4">
                <input type="hidden" name="encounterId" value={encounter.id} />
                <p className="text-xs text-ink-muted">
                  Leave anything unmeasured blank. A blank field is not recorded at all — an
                  unrecorded observation and a measured zero are different facts, and a pain score
                  is the one where that matters most.
                </p>
                <div className="grid grid-cols-2 gap-2">
                  <Obs name="heartRate" label="Heart rate" unit="bpm" />
                  <Obs name="respiratoryRate" label="Resp. rate" unit="/min" />
                  <Obs name="systolicBp" label="Systolic" unit="mmHg" />
                  <Obs name="diastolicBp" label="Diastolic" unit="mmHg" />
                  <Obs name="temperatureC" label="Temp" unit="°C" step="0.1" />
                  <Obs name="oxygenSaturation" label="SpO2" unit="%" />
                  <Obs name="weightKg" label="Weight" unit="kg" step="0.1" />
                  <Obs name="heightCm" label="Height" unit="cm" step="0.1" />
                  <Obs name="painScore" label="Pain" unit="/10" />
                  <div>
                    <label htmlFor="consciousness" className="block text-xs font-medium">
                      Consciousness
                    </label>
                    <select
                      id="consciousness"
                      name="consciousness"
                      defaultValue=""
                      className="mt-1 w-full rounded border border-line bg-surface-raised px-2 py-1.5 text-sm"
                    >
                      <option value="">—</option>
                      <option value="ALERT">Alert</option>
                      <option value="VOICE">Voice</option>
                      <option value="PAIN">Pain</option>
                      <option value="UNRESPONSIVE">Unresponsive</option>
                    </select>
                  </div>
                </div>
                <button
                  type="submit"
                  className="rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-surface"
                >
                  Record observations
                </button>
              </form>
            )}
          </Card>

          {mayChart && (
            <Card title="Decision support">
              <AiAssist noteText={noteText} />
            </Card>
          )}

          {mayChart && open && (
            <Card title="Finish">
              <form action={closeEncounter}>
                <input type="hidden" name="encounterId" value={encounter.id} />
                <button
                  type="submit"
                  className="w-full rounded-md border border-line px-3 py-2 text-sm font-medium hover:bg-surface"
                >
                  Close this encounter
                </button>
              </form>
              <p className="mt-2 text-xs text-ink-muted">
                Closing needs a signed note; the service refuses otherwise and says which revision
                is outstanding. It also completes the linked appointment.
              </p>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The patient record's sex, as the laboratory's reference intervals are scaled.
 *
 * <p>Two vocabularies, deliberately not merged. A patient record carries administrative gender and
 * needs MALE, FEMALE, OTHER and UNKNOWN to record people honestly; a haemoglobin reference interval
 * is scaled on one of two physiological ranges and has nothing to say about the other two. So the
 * translation is explicit and lossy in one direction only: what the laboratory cannot scale for it
 * declines to scale for, and the report then carries the analyzer's own range rather than an
 * interval picked by default.
 */
function labSex(sex: string | undefined): string {
  if (sex === "MALE") return "M";
  if (sex === "FEMALE") return "F";
  return "";
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

/** One observation input. Number-typed so a phone shows a numeric keypad at a bedside. */
function Obs({
  name,
  label,
  unit,
  step,
}: {
  name: string;
  label: string;
  unit: string;
  step?: string;
}) {
  return (
    <div>
      <label htmlFor={name} className="block text-xs font-medium">
        {label} <span className="text-ink-muted">{unit}</span>
      </label>
      <input
        id={name}
        name={name}
        type="number"
        step={step}
        inputMode="decimal"
        className="numeric mt-1 w-full rounded border border-line bg-surface-raised px-2 py-1.5 text-sm"
      />
    </div>
  );
}
