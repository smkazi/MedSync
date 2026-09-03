import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { Consent, Page as PageResponse, PatientSummary } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime, statusTone } from "@/components/ui";
import { HI_TYPES, PURPOSES } from "./state";
import { denyConsent, grantConsent, requestConsent, revokeConsent, shareRecord } from "./actions";

/**
 * Consent: what a patient has permitted, and what may therefore be sent.
 *
 * <p>The screen is arranged around the four questions the platform asks before anything leaves —
 * is it granted, is it still live, does it cover this kind of record, does it cover the record's
 * date — because a consent register that shows only a status teaches people to read "granted" as
 * "we may send anything".
 *
 * <p>Recording a decision and acting on one are different forms for different roles here, which
 * mirrors the API: the front desk writes down what the patient said, a clinician shares a record
 * under it, and neither can do the other's half.
 */
export default async function SharingPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; includeFinished?: string; problem?: string; done?: string }>;
}) {
  const { mrn = "", includeFinished, problem, done } = await searchParams;
  const user = await currentUser();
  const mayRecord = hasRole(user, "ADMIN", "RECEPTIONIST");
  const mayShare = hasRole(user, "ADMIN", "DOCTOR");
  const showFinished = includeFinished === "true";

  // The ordinary patient search, not the narrow lookup: everybody who may read a consent already
  // has CLINICAL_READ, and `/patients/identify` exists for the one role that does not — the
  // billing desk. Using it here would have been a 403 for the front desk, which is exactly how
  // this screen first failed.
  const patients = mrn
    ? await load<PageResponse<PatientSummary>>(`/patients?q=${encodeURIComponent(mrn)}&size=10`)
    : { data: null, error: null };
  const patient = (patients.data?.content ?? [])[0];

  const query = new URLSearchParams();
  if (patient) query.set("patientId", patient.id);
  if (showFinished) query.set("includeFinished", "true");
  const consents = await load<Consent[]>(`/consents${query.size > 0 ? `?${query}` : ""}`);

  const rows = consents.data ?? [];
  const live = rows.filter((consent) => consent.live);
  const pending = rows.filter((consent) => consent.status === "REQUESTED");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Consent</h1>
        <p className="text-sm text-ink-muted">
          Nothing leaves this hospital without one of these covering it.
        </p>
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
      {consents.error && <ErrorNote>{consents.error}</ErrorNote>}
      {mrn && !patient && <ErrorNote>No patient matches “{mrn}”.</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Live" value={live.length} hint="granted and not lapsed" />
        <Stat label="Awaiting the patient" value={pending.length} hint="requested, not answered" />
        <Stat
          label="Listed"
          value={rows.length}
          hint={showFinished ? "including finished" : "open only"}
        />
      </div>

      <Card
        title={patient ? `${patient.mrn}` : "Consents"}
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="mrn" className="text-xs text-ink-muted">
              MRN
            </label>
            <input
              id="mrn"
              name="mrn"
              defaultValue={mrn}
              className="w-40 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
            />
            <label className="flex items-center gap-1 text-xs text-ink-muted">
              <input
                type="checkbox"
                name="includeFinished"
                value="true"
                defaultChecked={showFinished}
              />
              finished too
            </label>
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Show
            </button>
          </form>
        }
      >
        {rows.length === 0 ? (
          <Empty>No consents{patient ? ` for ${patient.mrn}` : ""}.</Empty>
        ) : (
          <Table
            head={["Artefact", "Patient", "Requester", "Purpose", "Covers", "Records dated", "Until", "", ""]}
          >
            {rows.map((consent) => (
              <tr key={consent.id} className={consent.live ? "" : "text-ink-muted"}>
                <td className="numeric px-3 py-2">{consent.artefactId}</td>
                <td className="numeric px-3 py-2">{consent.patientMrn}</td>
                <td className="px-3 py-2">{consent.requester}</td>
                <td className="px-3 py-2 text-ink-muted">
                  {consent.purposeCode.toLowerCase().replaceAll("_", " ")}
                </td>
                <td className="px-3 py-2 text-xs">
                  {consent.hiTypes
                    .map((type) => type.toLowerCase().replaceAll("_", " "))
                    .join(", ")}
                </td>
                <td className="numeric px-3 py-2 text-xs">
                  {consent.coversFrom} → {consent.coversTo}
                </td>
                <td className="numeric px-3 py-2 text-xs">{formatDateTime(consent.expiresAt)}</td>
                <td className="px-3 py-2">
                  <Badge tone={consent.live ? "good" : statusTone(consent.status)}>
                    {consent.status.toLowerCase()}
                  </Badge>
                  {consent.revokedReason && (
                    <span className="mt-1 block text-xs text-critical">
                      {consent.revokedReason}
                    </span>
                  )}
                </td>
                <td className="px-3 py-2">
                  {mayRecord && consent.status === "REQUESTED" && (
                    <div className="space-y-1">
                      <form action={grantConsent} className="flex items-center gap-1">
                        <input type="hidden" name="artefactId" value={consent.artefactId} />
                        <label className="sr-only" htmlFor={`sig-${consent.id}`}>
                          Signature for {consent.artefactId}
                        </label>
                        <input
                          id={`sig-${consent.id}`}
                          name="signature"
                          placeholder="Signature, if any"
                          className="w-32 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
                        />
                        <button type="submit" className="text-xs underline">
                          Granted
                        </button>
                      </form>
                      <form action={denyConsent}>
                        <input type="hidden" name="artefactId" value={consent.artefactId} />
                        <button type="submit" className="text-xs text-critical underline">
                          Refused
                        </button>
                      </form>
                    </div>
                  )}
                  {mayRecord && (consent.status === "GRANTED" || consent.status === "EXPIRED") && (
                    <form action={revokeConsent} className="flex items-center gap-1">
                      <input type="hidden" name="artefactId" value={consent.artefactId} />
                      <label className="sr-only" htmlFor={`revoke-${consent.id}`}>
                        Why {consent.artefactId} is being withdrawn
                      </label>
                      <input
                        id={`revoke-${consent.id}`}
                        name="reason"
                        required
                        placeholder="Why it is withdrawn"
                        className="w-36 rounded border border-line bg-surface-raised px-2 py-1 text-xs"
                      />
                      <button type="submit" className="text-xs text-critical underline">
                        Revoke
                      </button>
                    </form>
                  )}
                  {!mayRecord && <span className="text-xs text-ink-muted">read-only</span>}
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          “Covers” is the kind of record, and “records dated” is the clinical period — they are
          different questions, and a consent for last year’s records is not a consent that lasts a
          year. A revoked consent stays on this list with its reason, because the question asked
          afterwards is whether a disclosure was lawful at the time.
        </p>
      </Card>

      {mayShare && (
        <Card title="Send a record under a consent">
          <RecordForm
            action={shareRecord}
            submitLabel="Send it"
            busyLabel="Sending…"
            fields={[
              {
                name: "artefactId",
                label: "Consent",
                type: "select",
                required: true,
                hint: "Only a live consent can authorise anything.",
                options: [
                  { value: "", label: "— pick one —" },
                  ...live.map((consent) => ({
                    value: consent.artefactId,
                    label: `${consent.artefactId} — ${consent.patientMrn} → ${consent.requester}`,
                  })),
                ],
              },
              {
                name: "hiType",
                label: "What kind of record",
                type: "select",
                required: true,
                options: [{ value: "", label: "— pick one —" }, ...HI_TYPES],
              },
              {
                name: "recordId",
                label: "Record id",
                required: true,
                hint: "The encounter, laboratory order or prescription being sent.",
              },
            ]}
          />
          <p className="mt-3 text-xs text-ink-muted">
            The platform checks the consent before it reads the record, so a refusal never fetches
            a chart. With no ABDM gateway configured, a send is recorded and nothing is
            transmitted — the response says which, and so does the disclosure register.
          </p>
        </Card>
      )}

      {mayRecord && (
        <Card title="Record a consent request">
          <RecordForm
            action={requestConsent}
            hidden={patient ? { patientId: patient.id, patientMrn: patient.mrn } : undefined}
            submitLabel="Record the request"
            busyLabel="Recording…"
            fields={[
              ...(patient
                ? []
                : [
                    { name: "patientId", label: "Patient id", required: true },
                    { name: "patientMrn", label: "MRN", required: true },
                  ]),
              { name: "requester", label: "Who is asking", required: true },
              {
                name: "requesterId",
                label: "Their identifier",
                hint: "An HIU id, a facility code — whatever the request carried.",
              },
              {
                name: "purposeCode",
                label: "Why",
                type: "select",
                required: true,
                options: [{ value: "", label: "— pick one —" }, ...PURPOSES],
              },
              { name: "purposeText", label: "In their words" },
              {
                name: "hiTypes",
                label: "What it covers",
                type: "multicheck",
                options: HI_TYPES,
                hint: "A consent covering nothing would authorise nothing while looking like permission.",
              },
              {
                name: "coversFrom",
                label: "Records dated from",
                type: "date",
                required: true,
              },
              { name: "coversTo", label: "Records dated to", type: "date", required: true },
              {
                name: "expiresAt",
                label: "Permission lapses",
                required: true,
                hint: "An instant, e.g. 2026-12-31T23:59. A consent with no end is standing permission forever.",
              },
              {
                name: "artefactId",
                label: "Consent manager’s id",
                hint: "Left blank, the platform mints a LOCAL- id and says so.",
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
