import Link from "next/link";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type {
  CarePlan,
  CatalogEntry,
  Encounter,
  FormularyEntry,
  LabOrderSummary,
  News2,
  OrderSet,
  Patient,
  Prescription,
} from "@/lib/types";
import { AiAssist } from "@/components/AiAssist";
import { RecordForm } from "@/components/RecordForm";
import { orderTests } from "../../laboratory/actions";
import { prescribe } from "../../pharmacy/actions";
import { PRIORITIES } from "../../laboratory/state";
import { applyOrderSet, closeEncounter, recordVitals, signNote, startCarePlan } from "./actions";
import { CarePlanPanel } from "./CarePlanPanel";
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
  const [labOrders, catalog, patient, prescriptions, formulary, orderSets, carePlan] = mayChart
    ? await Promise.all([
        load<LabOrderSummary[]>(`/lab/encounters/${id}/orders`),
        load<CatalogEntry[]>("/lab/catalog"),
        load<Patient>(`/patients/${encounter.patientId}`),
        load<Prescription[]>(`/prescriptions?encounterId=${id}`),
        load<FormularyEntry[]>("/pharmacy/formulary"),
        load<OrderSet[]>(`/order-sets?department=${encodeURIComponent(encounter.departmentCode ?? "")}`),
        // A 404 here is the ordinary case — most encounters have no plan — so the error is
        // rendered as an absence rather than as a failure.
        load<CarePlan>(`/care-plans/encounters/${id}`),
      ])
    : [
        { data: null, error: null },
        { data: null, error: null },
        { data: null, error: null },
        { data: null, error: null },
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

          {/*
            Prescribing, on the chart, for the same reason laboratory ordering is: a clinician
            writing a medicine is already looking at the assessment that justifies it, and the
            order carries the encounter so the visit can show what it raised.

            One medicine per submission, and the screen says so. The platform checks each line
            against the patient's allergy list and against the pairings among *this* order's
            ingredients; a prescriber adding a second medicine gets a second check. What this form
            cannot do is check two lines submitted together — it posts one — which is a limitation
            of the form rather than of the service, and naming it is better than implying a check
            that did not run.
          */}
          {mayChart && maySign && (
            <Card title="Medicines">
              {prescriptions.error && <ErrorNote>{prescriptions.error}</ErrorNote>}

              {(prescriptions.data ?? []).length === 0 ? (
                <Empty>Nothing prescribed on this visit.</Empty>
              ) : (
                <div className="space-y-3">
                  {(prescriptions.data ?? []).map((rx) => (
                    <div key={rx.id} className="rounded-md border border-line p-3">
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <span className="text-xs text-ink-muted">
                          {formatDateTime(rx.issuedAt)} · {rx.prescriberName}
                        </span>
                        <Badge tone={statusTone(rx.status)}>{rx.status.toLowerCase()}</Badge>
                      </div>
                      {rx.overrideReason && (
                        <p className="mt-2 rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-xs text-warn">
                          <strong>Warning accepted:</strong> {rx.overrideReason}
                        </p>
                      )}
                      <ul className="mt-2 space-y-1 text-sm">
                        {rx.items.map((item) => (
                          <li key={item.id}>
                            <span className="font-medium">{item.drugName}</span> — {item.dose},{" "}
                            {item.frequency}, {item.durationDays} day(s)
                            <span className="ml-2 text-xs text-ink-muted">
                              {item.quantityDispensed} of {item.quantity} dispensed
                            </span>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              )}

              {open && formulary.data && formulary.data.length > 0 && (
                <div className="mt-4 border-t border-line pt-4">
                  <RecordForm
                    action={prescribe}
                    columns={2}
                    submitLabel="Prescribe"
                    busyLabel="Checking…"
                    hidden={{
                      encounterId: encounter.id,
                      patientId: encounter.patientId,
                      patientMrn: encounter.patientMrn,
                    }}
                    fields={[
                      {
                        name: "drugCode",
                        label: "Medicine",
                        type: "select",
                        required: true,
                        options: formulary.data
                          .filter((entry) => entry.active)
                          .map((entry) => ({ value: entry.code, label: entry.label })),
                      },
                      { name: "dose", label: "Dose", required: true, placeholder: "1 tablet" },
                      {
                        name: "frequency",
                        label: "Frequency",
                        required: true,
                        placeholder: "twice daily",
                      },
                      { name: "durationDays", label: "For (days)", type: "number", required: true },
                      {
                        name: "quantity",
                        label: "Quantity to dispense",
                        type: "number",
                        required: true,
                      },
                      {
                        name: "instructions",
                        label: "Instructions for the patient",
                        placeholder: "After food",
                      },
                      {
                        name: "overrideReason",
                        label: "Reason, if a warning is raised",
                        type: "textarea",
                        hint: "Leave blank. If the platform finds an interaction it can let through, it refuses once and asks for this; a recorded allergy at severe or above is refused outright and no reason unlocks it.",
                      },
                    ]}
                  />
                </div>
              )}
            </Card>
          )}

          {/*
            Order sets: the reason clinicians tolerate computerised ordering at all. A fever needs
            the same six things every time, and typing them one at a time is where the sixth gets
            forgotten at four in the morning.

            Applying one is a saga across two services rather than a transaction — a prescription
            lands in the pharmacy's schema and a laboratory order in the laboratory's — so the
            outcome is reported in full. If the tests fail after the prescription was raised, the
            prescription is withdrawn; if the withdrawal fails too, the message names it, because a
            clinician can cancel one by hand and cannot act on "something went wrong".
          */}
          {mayChart && open && (orderSets.data ?? []).length > 0 && (
            <Card title="Order sets">
              <div className="space-y-3">
                {(orderSets.data ?? []).map((set) => (
                  <div key={set.id} className="rounded-md border border-line p-3">
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <span className="font-medium">{set.name}</span>
                      <span className="numeric text-xs text-ink-muted">{set.code}</span>
                    </div>
                    {set.description && (
                      <p className="mt-1 text-sm text-ink-muted">{set.description}</p>
                    )}
                    <ul className="mt-2 space-y-1 text-sm">
                      {set.items.map((item) => (
                        <li key={item.id}>
                          <Badge tone={item.kind === "MEDICATION" ? "warn" : "neutral"}>
                            {item.kind === "MEDICATION" ? "medicine" : "test"}
                          </Badge>{" "}
                          <span className="numeric">{item.code}</span>
                          {item.kind === "MEDICATION" ? (
                            <span className="text-ink-muted">
                              {" "}
                              — {item.dose}, {item.frequency}, {item.durationDays} day(s),{" "}
                              {item.quantity} to dispense
                            </span>
                          ) : (
                            <span className="text-ink-muted"> — {item.priority?.toLowerCase()}</span>
                          )}
                        </li>
                      ))}
                    </ul>
                    <form action={applyOrderSet} className="mt-3 flex flex-wrap items-end gap-2">
                      <input type="hidden" name="encounterId" value={encounter.id} />
                      <input type="hidden" name="code" value={set.code} />
                      {set.items.some((item) => item.kind === "MEDICATION") && (
                        <div className="grow">
                          <label
                            htmlFor={`reason-${set.code}`}
                            className="block text-xs text-ink-muted"
                          >
                            Reason, if a warning is raised
                          </label>
                          <input
                            id={`reason-${set.code}`}
                            name="overrideReason"
                            className="mt-1 w-full rounded border border-line bg-surface-raised px-2 py-1 text-xs"
                          />
                        </div>
                      )}
                      <button
                        type="submit"
                        className="rounded border border-line px-3 py-1.5 text-xs font-medium hover:bg-surface"
                      >
                        Apply
                      </button>
                    </form>
                  </div>
                ))}
              </div>
              <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
                Every line is shown before it is raised, with its dose, because a set applied in one
                click is a set nobody reads unless the screen makes them. The tests go out as one
                order — a panel of bloods is one needle — at the most urgent priority any line
                carries. A medicine the patient reacts to refuses the whole set, and refuses it
                before anything has been raised.
              </p>
            </Card>
          )}

          {/*
            The care plan. A chart records what happened; this records what was meant to happen,
            which is what a ward round, a discharge summary and a review all ask about and which no
            note answers. "Improving" is not a goal.
          */}
          {mayChart && (
            <Card title="Care plan">
              {!carePlan.data ? (
                <div>
                  <Empty>No care plan on this visit.</Empty>
                  {open && (
                    <form action={startCarePlan} className="mt-3 flex flex-wrap items-end gap-2">
                      <input type="hidden" name="encounterId" value={encounter.id} />
                      <div className="grow">
                        <label htmlFor="plan-title" className="block text-sm font-medium">
                          What is this episode trying to achieve?
                        </label>
                        <input
                          id="plan-title"
                          name="title"
                          required
                          placeholder="Admission plan"
                          className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
                        />
                      </div>
                      <button
                        type="submit"
                        className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
                      >
                        Start a plan
                      </button>
                    </form>
                  )}
                </div>
              ) : (
                <CarePlanPanel plan={carePlan.data} encounterId={encounter.id}
                               diagnoses={encounter.diagnoses} />
              )}
              <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
                A goal may be filed under one of this visit&apos;s own diagnoses, or under none —
                &ldquo;mobilising independently&rdquo; belongs to the admission rather than to a
                problem. It cannot name a diagnosis nobody made. Anything other than
                <strong> met</strong> needs a note, because &ldquo;not met&rdquo; on its own is a
                record nobody can learn from.
              </p>
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
                <Vital
                  label="Oxygen"
                  value={latestVitals.onSupplementalOxygen ? "supplemental" : "air"}
                  unit=""
                />
                <Vital label="BMI" value={latestVitals.bodyMassIndex} unit="" />
                <p className="border-t border-line pt-2 text-xs text-ink-muted">
                  {formatDateTime(latestVitals.recordedAt)} by {latestVitals.recordedBy}
                </p>
              </dl>
            )}

            {latestVitals?.news2 && <News2Panel news2={latestVitals.news2} />}

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
                  {/*
                    Two points on NEWS2, and unreadable from the saturation: 96% on four litres is
                    a very different patient from 96% on air. Its own field because the score
                    cannot infer it, and before it existed the score under-read by 2 for everybody
                    on oxygen — which is the direction that gets missed.

                    The hidden twin after the checkbox, not before: FormData.get returns the first
                    value for a repeated name, so the order is load-bearing.
                  */}
                  <label className="col-span-2 flex items-center gap-2 self-end text-xs">
                    <input
                      type="checkbox"
                      name="onSupplementalOxygen"
                      value="true"
                      className="size-4 rounded border-line"
                    />
                    <input type="hidden" name="onSupplementalOxygen" value="false" />
                    <span>On supplemental oxygen</span>
                  </label>
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
 * The early warning score, beside the observations it was derived from.
 *
 * <p>Advisory, and the panel says so. Nothing about NEWS2 in this platform changes a status, moves
 * a patient or raises an order — it is a number next to the numbers, and an early warning score
 * that acted on its own would be a clinical decision made by a table of ranges.
 *
 * <p>Three things are rendered that a bare total would hide, and each is there because leaving it
 * out would invite a wrong reading: the per-parameter breakdown, because a score whose working
 * cannot be seen is not one a clinician should act on; what was <em>not</em> measured, because a
 * NEWS2 of 3 from four observations is a different fact from a 3 from seven; and the
 * single-parameter rule, because a total of 3 that is all from one parameter escalates further
 * than a 3 spread across three.
 */
function News2Panel({ news2 }: { news2: News2 }) {
  const tone =
    news2.band === "HIGH"
      ? "critical"
      : news2.band === "MEDIUM"
        ? "warn"
        : news2.band === "LOW_MEDIUM"
          ? "warn"
          : "neutral";

  return (
    <div className="mt-4 border-t border-line pt-4">
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-xs font-medium uppercase tracking-wide text-ink-muted">NEWS2</span>
        <span className="flex items-center gap-2">
          <span className="numeric text-2xl font-semibold">{news2.total}</span>
          <Badge tone={tone}>{news2.band.replace("_", "–").toLowerCase()}</Badge>
        </span>
      </div>

      {news2.anyParameterScoredThree && (
        <p className="mt-2 rounded-md border border-warn/40 bg-warn-soft px-2 py-1.5 text-xs text-warn">
          A single parameter scored 3, which escalates on its own whatever the total.
        </p>
      )}

      <dl className="mt-2 space-y-0.5 text-xs">
        {news2.components.map((component) => (
          <div key={component.parameter} className="flex justify-between gap-2">
            <dt className="text-ink-muted">{component.parameter}</dt>
            <dd className="numeric">
              {component.value}
              <span className={component.score > 0 ? "ml-2 font-semibold text-critical" : "ml-2"}>
                {component.score}
              </span>
            </dd>
          </div>
        ))}
      </dl>

      {news2.missing.length > 0 && (
        <p className="mt-2 text-xs text-warn">
          Not measured: {news2.missing.join(", ")}. Nothing is assumed normal, so the score is
          lower than a complete set would give.
        </p>
      )}

      {news2.escalation && (
        <p className="mt-2 border-t border-line pt-2 text-xs text-ink-muted">
          <strong>{news2.escalation.monitoring}.</strong> {news2.escalation.response} (
          {news2.escalation.setting})
        </p>
      )}

      <p className="mt-2 text-xs text-ink-muted">
        Advisory. The score never changes a status or raises an order on its own.
      </p>
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
