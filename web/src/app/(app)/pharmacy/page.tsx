import Link from "next/link";
import { load } from "@/lib/load";
import { hasRole, currentUser } from "@/lib/session";
import type { Prescription, StockBatch } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { cancelPrescription, dispense } from "./actions";

/**
 * The dispensing queue.
 *
 * <p>Ordered oldest first, which is the right order here and the opposite of the casualty board's:
 * a prescription is not more urgent because of who wrote it, and the person who has been waiting
 * longest at the counter should be served next.
 *
 * <p><strong>The override reason is on the row, in full.</strong> When a prescriber went ahead
 * against a warning, the pharmacist is the last person who can catch a mistake, and "there was a
 * reason, it is on another screen" is how that check stops happening. The service refuses the
 * dispense outright if the medicine is now unsafe — the checks run a second time at the counter —
 * so what is left for a person to read is the judgement, not the rule.
 */
export default async function PharmacyPage({
  searchParams,
}: {
  searchParams: Promise<{ problem?: string; done?: string }>;
}) {
  const { problem, done } = await searchParams;
  const user = await currentUser();
  const mayDispense = hasRole(user, "ADMIN", "PHARMACIST");

  const [queue, stock] = await Promise.all([
    load<Prescription[]>("/prescriptions"),
    load<StockBatch[]>("/pharmacy/stock"),
  ]);

  const rows = queue.data ?? [];
  const outstandingLines = rows.flatMap((rx) =>
    rx.items.filter((item) => item.outstanding > 0).map((item) => ({ rx, item })),
  );
  const overridden = rows.filter((rx) => rx.overrideReason);
  const expiringSoon = (stock.data ?? []).filter(
    (batch) => !batch.expired && batch.daysToExpiry <= 90,
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Dispensing queue</h1>
        <p className="text-sm text-ink-muted">
          Prescriptions with something still to hand over, oldest first.
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
      {queue.error && <ErrorNote>{queue.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-4">
        <Stat label="Waiting" value={rows.length} hint="prescriptions with work left" />
        <Stat label="Lines" value={outstandingLines.length} hint="individual medicines" />
        <Stat label="Overridden" value={overridden.length} hint="a warning was accepted" />
        <Stat
          label="Expiring"
          value={expiringSoon.length}
          hint="batches within 90 days"
        />
      </div>

      {!mayDispense && (
        <p className="rounded-md border border-line bg-surface-raised px-3 py-2 text-sm text-ink-muted">
          You are signed in as {user?.fullName}. This screen is readable by anybody who may read a
          medication order; handing medicine over is the pharmacy&apos;s, so the dispense controls
          are not offered here.
        </p>
      )}

      <Card title="The queue">
        {rows.length === 0 ? (
          <Empty>Nothing waiting to be dispensed.</Empty>
        ) : (
          <div className="space-y-4">
            {rows.map((rx) => (
              <div key={rx.id} className="rounded-md border border-line p-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <div>
                    <Link
                      href={`/patients/${rx.patientId}`}
                      className="numeric font-medium text-accent hover:underline"
                    >
                      {rx.patientMrn}
                    </Link>
                    <span className="ml-2 text-xs text-ink-muted">
                      {rx.prescriberName} · {formatDateTime(rx.issuedAt)}
                    </span>
                  </div>
                  <Badge tone={rx.status === "ACTIVE" ? "neutral" : "good"}>
                    {rx.status.toLowerCase()}
                  </Badge>
                </div>

                {rx.overrideReason && (
                  // Not a badge and not a tooltip. A prescriber wrote a sentence explaining why
                  // they went ahead against a warning, and the pharmacist reading this row is the
                  // last person who can question it.
                  <p className="mt-2 rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
                    <strong>A warning was accepted.</strong> {rx.overrideReason}
                  </p>
                )}

                <Table head={["Medicine", "Dose", "Frequency", "Prescribed", "Left", ""]}>
                  {rx.items.map((item) => (
                    <tr key={item.id}>
                      <td className="px-3 py-2">
                        {item.drugName}
                        {item.instructions && (
                          <span className="block text-xs text-ink-muted">{item.instructions}</span>
                        )}
                      </td>
                      <td className="px-3 py-2">{item.dose}</td>
                      <td className="px-3 py-2">{item.frequency}</td>
                      <td className="numeric px-3 py-2">
                        {item.quantity}
                        <span className="ml-1 text-xs text-ink-muted">
                          / {item.durationDays} day(s)
                        </span>
                      </td>
                      <td className="numeric px-3 py-2">
                        {item.outstanding === 0 ? (
                          <Badge tone="good">done</Badge>
                        ) : (
                          item.outstanding
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {mayDispense && item.outstanding > 0 && rx.status === "ACTIVE" && (
                          <form action={dispense} className="flex items-center gap-1">
                            <input type="hidden" name="prescriptionItemId" value={item.id} />
                            <input
                              type="number"
                              name="quantity"
                              min={1}
                              max={item.outstanding}
                              defaultValue={item.outstanding}
                              aria-label={`Quantity of ${item.drugName} to dispense`}
                              className="numeric w-16 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
                            />
                            <button
                              type="submit"
                              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                            >
                              Dispense
                            </button>
                          </form>
                        )}
                      </td>
                    </tr>
                  ))}
                </Table>

                {mayDispense && rx.status === "ACTIVE"
                  && rx.items.every((item) => item.quantityDispensed === 0) && (
                  <form action={cancelPrescription} className="mt-2">
                    <input type="hidden" name="prescriptionId" value={rx.id} />
                    <button
                      type="submit"
                      className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
                    >
                      Cancel this prescription
                    </button>
                  </form>
                )}
              </div>
            ))}
          </div>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          The batch is chosen for you: first expiry, first out. A quantity is offered rather than a
          single button because a partial hand-over is normal — the outstanding number is what the
          prescriber authorised minus what has already left the pharmacy, and the platform will not
          let it go below zero. <strong>Cancel</strong> disappears once anything has been dispensed:
          the medicine is in the patient&apos;s hand by then, and stopping it is a new clinical
          instruction rather than the deletion of an old one.
        </p>
      </Card>

      {expiringSoon.length > 0 && (
        <Card title="Expiring within 90 days">
          <Table head={["Medicine", "Batch", "Expires", "In", "On hand"]}>
            {expiringSoon.map((batch) => (
              <tr key={batch.id}>
                <td className="px-3 py-2">{batch.drugName ?? batch.drugCode}</td>
                <td className="numeric px-3 py-2">{batch.batchNo}</td>
                <td className="numeric px-3 py-2">{batch.expiresOn}</td>
                <td className="numeric px-3 py-2">
                  <span className={batch.daysToExpiry <= 30 ? "font-semibold text-critical" : ""}>
                    {batch.daysToExpiry} day(s)
                  </span>
                </td>
                <td className="numeric px-3 py-2">{batch.quantityOnHand}</td>
              </tr>
            ))}
          </Table>
          <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
            Shown because first-expiry-first-out only works if somebody knows what is about to
            expire. The platform will refuse to dispense any of these on the day they expire, and
            will not accept an already-expired batch into stock at all.
          </p>
        </Card>
      )}
    </div>
  );
}
