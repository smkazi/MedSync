import Link from "next/link";
import { load } from "@/lib/load";
import type { Prescription } from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime } from "@/components/ui";
import { NOT_GIVEN_REASONS } from "../pharmacy/state";
import { administer, recordNotGiven } from "../pharmacy/actions";

/**
 * The drug round.
 *
 * <p>One patient at a time, reached by MRN, because that is how a round is actually done: the nurse
 * is standing at a bed, and a screen listing every patient's medicines invites the error the whole
 * loop exists to prevent — the right medicine given to the wrong person.
 *
 * <p><strong>Both scans are required and neither can be skipped.</strong> There is no "scanner
 * unavailable" checkbox, deliberately: an override that turns both checks off becomes the normal
 * path within a week. Typing the numbers in is allowed, because scanners fail and a nurse holding a
 * syringe cannot wait for procurement — but something has to be entered, and it has to match.
 */
export default async function EmarPage({
  searchParams,
}: {
  searchParams: Promise<{ mrn?: string; patientId?: string }>;
}) {
  const { mrn = "", patientId = "" } = await searchParams;

  const { data, error } = patientId
    ? await load<Prescription[]>(`/prescriptions?patientId=${encodeURIComponent(patientId)}`)
    : { data: null, error: null };

  const active = (data ?? []).filter((rx) => rx.status !== "CANCELLED");
  const now = new Date().toISOString().slice(0, 16);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Drug round</h1>
        <p className="text-sm text-ink-muted">
          One patient, their active medicines, and a record of every dose.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title="Who is in front of you">
        <form className="flex flex-wrap items-end gap-3">
          <div className="grow">
            <label htmlFor="patientId" className="block text-sm font-medium">
              Patient id
            </label>
            <input
              id="patientId"
              name="patientId"
              defaultValue={patientId}
              placeholder="Open the round from the patient's chart, or paste their id"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <input type="hidden" name="mrn" value={mrn} />
          <button
            type="submit"
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
          >
            Open the round
          </button>
        </form>
        <p className="mt-3 text-xs text-ink-muted">
          The round is opened by patient id rather than by searching a name, because this screen is
          used with a patient in front of you and a wristband in your hand. The wristband is checked
          against the prescription when the dose is recorded, so opening the wrong round is caught
          rather than acted on.
        </p>
      </Card>

      {patientId && active.length === 0 && !error && (
        <Card title="Medicines">
          <Empty>No active prescriptions for that patient.</Empty>
        </Card>
      )}

      {active.map((rx) => (
        <Card
          key={rx.id}
          title={`${rx.patientMrn} — prescribed by ${rx.prescriberName}`}
        >
          {rx.overrideReason && (
            <p className="mb-3 rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
              <strong>A warning was accepted when this was written.</strong> {rx.overrideReason}
            </p>
          )}

          <div className="space-y-5">
            {rx.items.map((item) => (
              <div key={item.id} className="rounded-md border border-line p-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <div>
                    <span className="font-medium">{item.drugName}</span>
                    <span className="ml-2 text-sm text-ink-muted">
                      {item.dose}, {item.frequency}
                    </span>
                  </div>
                  <span className="numeric text-xs text-ink-muted">{item.drugCode}</span>
                </div>
                {item.instructions && (
                  <p className="mt-1 text-sm">{item.instructions}</p>
                )}

                {item.administrations.length > 0 && (
                  <Table head={["Due", "Status", "Given at", "By", "Reason"]}>
                    {item.administrations.map((dose) => (
                      <tr key={dose.id}>
                        <td className="numeric px-3 py-2">{formatDateTime(dose.scheduledFor)}</td>
                        <td className="px-3 py-2">
                          <Badge
                            tone={
                              dose.status === "GIVEN"
                                ? "good"
                                : dose.status === "REFUSED"
                                  ? "warn"
                                  : "neutral"
                            }
                          >
                            {dose.status.toLowerCase()}
                          </Badge>
                        </td>
                        <td className="numeric px-3 py-2 text-ink-muted">
                          {formatDateTime(dose.administeredAt)}
                        </td>
                        <td className="px-3 py-2 text-xs">{dose.administeredBy}</td>
                        <td className="px-3 py-2 text-xs">{dose.refusalReason ?? "—"}</td>
                      </tr>
                    ))}
                  </Table>
                )}

                <div className="mt-3 grid gap-4 lg:grid-cols-2">
                  <div>
                    <h4 className="text-sm font-medium">Give this dose</h4>
                    <RecordForm
                      action={administer}
                      columns={2}
                      submitLabel="Record as given"
                      busyLabel="Recording…"
                      hidden={{ prescriptionItemId: item.id }}
                      fields={[
                        {
                          name: "scheduledFor",
                          label: "Due at",
                          type: "text",
                          required: true,
                          value: `${now}:00Z`,
                          hint: "One dose, one record: a second attempt at the same time is refused.",
                        },
                        {
                          name: "patientScan",
                          label: "Wristband",
                          required: true,
                          placeholder: rx.patientMrn,
                          hint: "Checked against this prescription before anything is written.",
                        },
                        {
                          name: "drugScan",
                          label: "Medicine label",
                          required: true,
                          placeholder: item.drugCode,
                        },
                      ]}
                    />
                  </div>

                  <div>
                    <h4 className="text-sm font-medium">Not given</h4>
                    <RecordForm
                      action={recordNotGiven}
                      columns={2}
                      submitLabel="Record the reason"
                      busyLabel="Recording…"
                      hidden={{ prescriptionItemId: item.id }}
                      fields={[
                        {
                          name: "scheduledFor",
                          label: "Due at",
                          type: "text",
                          required: true,
                          value: `${now}:00Z`,
                        },
                        {
                          name: "status",
                          label: "What happened",
                          type: "select",
                          required: true,
                          options: NOT_GIVEN_REASONS,
                        },
                        {
                          name: "reason",
                          label: "Reason",
                          required: true,
                          hint: "A dose not given is a clinical fact: the next shift needs to know why, not to find a gap.",
                        },
                      ]}
                    />
                  </div>
                </div>
              </div>
            ))}
          </div>

          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            <Link href={`/patients/${rx.patientId}`} className="text-accent hover:underline">
              Open the chart
            </Link>{" "}
            for allergies and the rest of the record. This screen deliberately shows only what is
            needed to give a dose safely.
          </p>
        </Card>
      ))}
    </div>
  );
}
