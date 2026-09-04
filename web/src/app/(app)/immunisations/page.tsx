import Link from "next/link";
import { load } from "@/lib/load";
import type { DueList, DueStatus } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDate } from "@/components/ui";
import type { BadgeTone } from "@/components/ui";

/**
 * The calling list: which children in a birth cohort are due or overdue a dose.
 *
 * <p><strong>Asked for a birth cohort, because that is how the question comes up.</strong> An OPD
 * telephones the children born between two dates, and there is deliberately no "every overdue child
 * in the district" query behind this screen. Due and overdue are computed on read, from the
 * schedule rows and a date of birth, and the reason is that the state transition that matters has
 * no event behind it: a dose becomes overdue because a day passed. Nothing happens, nobody writes
 * anything, and a materialised due table would be a cache whose invalidation key is the wall clock.
 *
 * <p>What that costs is stated rather than hidden — this screen needs a birth range. What it buys is
 * the failure it cannot have: a row saying DUE for a child vaccinated yesterday, because somebody
 * telephones a mother about an appointment she attended and the register, which is right, never
 * gets consulted.
 *
 * <p><strong>As at</strong> is a real field and not decoration. Every status here is a statement
 * about one day: the same cohort read tomorrow gives different answers, and a printed calling list
 * with no date on it is a list nobody can check. Set it to a past date and the answer is what was
 * true then, without hindsight — the next dose's interval is measured from doses received by that
 * date and not from later ones.
 *
 * <p>The uncounted doses are shown rather than dropped. A dose given too early does not advance a
 * series, and one silently ignored is a dose the clinician will give again — so each carries the
 * rule that rejected it, in a sentence, following `AllergyChecker`'s rule that "matched on
 * AMOXICILLIN is checkable and allergy detected is not".
 */
const TONES: Record<DueStatus, BadgeTone> = {
  OVERDUE: "critical",
  DUE: "warn",
  NOT_YET_DUE: "neutral",
  COMPLETE: "good",
  EXEMPT: "accent",
  NO_LONGER_GIVEN: "neutral",
};

const LABELS: Record<DueStatus, string> = {
  OVERDUE: "overdue",
  DUE: "due",
  NOT_YET_DUE: "not yet due",
  COMPLETE: "complete",
  EXEMPT: "exempt",
  NO_LONGER_GIVEN: "window shut",
};

export default async function CallingListPage({
  searchParams,
}: {
  searchParams: Promise<{ bornFrom?: string; bornTo?: string; asAt?: string; scheduleCode?: string }>;
}) {
  const { bornFrom = "", bornTo = "", asAt = "", scheduleCode = "" } = await searchParams;

  const asked = Boolean(bornFrom && bornTo);
  const query = new URLSearchParams();
  if (bornFrom) query.set("bornFrom", bornFrom);
  if (bornTo) query.set("bornTo", bornTo);
  if (asAt) query.set("asAt", asAt);
  if (scheduleCode) query.set("scheduleCode", scheduleCode);

  const { data: list, error } = asked
    ? await load<DueList>(`/immunisations/due?${query}`)
    : { data: null, error: null };

  const children = list?.children ?? [];
  const outstanding = children.filter((child) =>
    child.due.some((due) => due.status === "DUE" || due.status === "OVERDUE"),
  );

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Immunisation calling list</h1>
        <p className="text-sm text-ink-muted">
          Children born between two dates, and where each of them stands against the published
          schedule. Computed on read — there is no overdue table to go stale.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <form className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="bornFrom" className="block text-xs text-ink-muted">
            Born from
          </label>
          <input
            id="bornFrom"
            name="bornFrom"
            type="date"
            required
            defaultValue={bornFrom}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="bornTo" className="block text-xs text-ink-muted">
            Born to
          </label>
          <input
            id="bornTo"
            name="bornTo"
            type="date"
            required
            defaultValue={bornTo}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="asAt" className="block text-xs text-ink-muted">
            As at
          </label>
          <input
            id="asAt"
            name="asAt"
            type="date"
            defaultValue={asAt}
            className="mt-1 rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
          <p className="mt-1 text-xs text-ink-muted">Today if left blank</p>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Build the list
        </button>
      </form>

      {!asked && (
        <Empty>
          Choose a birth range. A fortnight of births is the usual size of a calling day, and the
          cohort read behind this is capped — deliberately, and it says so when it bites.
        </Empty>
      )}

      {list && (
        <>
          <div className="grid gap-4 sm:grid-cols-4">
            <Stat label="Children in the cohort" value={list.cohortSize} />
            <Stat
              label="With something outstanding"
              value={outstanding.length}
              hint="due or overdue — the ones to telephone"
            />
            <Stat label="As at" value={formatDate(list.asAt)} hint="every status below is about this day" />
            <Stat label="Schedule" value={list.scheduleCode} hint={list.scheduleName} />
          </div>

          {list.truncated && (
            <p className="rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-sm text-warn">
              {list.note ??
                "The cohort hit its cap, so this list is incomplete. Narrow the birth range."}{" "}
              The children past the cap are precisely the ones nobody telephones, which is why this
              says so instead of quietly returning a shorter list.
            </p>
          )}

          <Card title="The list">
            {children.length === 0 ? (
              <Empty>
                No children were born in that range — or none of them is registered here. Both look
                the same from this screen; the patient register is where to check.
              </Empty>
            ) : (
              <Table head={["Child", "Born", "Age", "Outstanding", "Not counted", ""]}>
                {children.map((child) => {
                  const outstandingDue = child.due.filter(
                    (due) => due.status === "DUE" || due.status === "OVERDUE",
                  );
                  return (
                    <tr key={child.patientId} className="border-t border-line align-top">
                      <td className="px-3 py-2">
                        {child.fullName}
                        <span className="numeric block text-xs text-ink-muted">{child.mrn}</span>
                      </td>
                      <td className="px-3 py-2 text-xs">{formatDate(child.dateOfBirth)}</td>
                      <td className="numeric px-3 py-2 text-xs">
                        {child.ageDays} d
                        {!child.inSchedule && (
                          <span className="block text-ink-muted">
                            {/*
                              A schedule is bounded by age. A child outside it has no rows to be
                              due, which is a different answer from "up to date" and is said as
                              such rather than rendered as an empty cell.
                            */}
                            outside the schedule
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {outstandingDue.length === 0 ? (
                          <span className="text-xs text-ink-muted">
                            {child.inSchedule ? "nothing due" : (child.note ?? "—")}
                          </span>
                        ) : (
                          <ul className="space-y-1">
                            {outstandingDue.map((due) => (
                              <li key={`${due.antigenCode}-${due.doseNumber}`} className="text-xs">
                                <Badge tone={TONES[due.status]}>{LABELS[due.status]}</Badge>{" "}
                                <strong>{due.antigenCode}</strong> dose {due.doseNumber} · {due.label}
                                <span className="block text-ink-muted">
                                  {/*
                                    The service's own sentence. Every row names the rule and the
                                    date it was measured against, so a clinician can check the row
                                    rather than trust it.
                                  */}
                                  {due.because}
                                  {due.basedOnEstimatedDate && " (from a recollected date)"}
                                  {due.refusalRecorded && " — a refusal is on file"}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        {child.uncounted.length === 0 ? (
                          <span className="text-xs text-ink-muted">—</span>
                        ) : (
                          <ul className="space-y-1">
                            {child.uncounted.map((dose) => (
                              <li key={dose.doseId} className="text-xs">
                                <Badge tone="warn">not counted</Badge> {dose.antigenCode} ·{" "}
                                {formatDate(dose.givenOn)}
                                <span className="block text-ink-muted">{dose.because}</span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <Link
                          href={`/immunisations/patients/${child.patientId}`}
                          className="text-sm text-accent hover:underline"
                        >
                          Register
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </Table>
            )}
          </Card>

          <p className="text-xs text-ink-muted">
            Read for the cohort born {formatDate(list.bornFrom)} → {formatDate(list.bornTo)}. The
            names and birthdays here come from the patient directory under its own narrow
            permission; this list is not narrowed per child, because a cohort narrowed to the
            caller&apos;s own patients is not a cohort.
          </p>
        </>
      )}
    </div>
  );
}
