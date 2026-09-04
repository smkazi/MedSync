import { load } from "@/lib/load";
import type { ImmunisationSchedule } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";

/**
 * The published schedule, as rows.
 *
 * <p><strong>Everything here is in days from date of birth, and nothing is in months.</strong> That
 * is the decision worth reading on this screen: "10 weeks" is 70 days and "2 months" is 59, 60 or
 * 62 depending on which two months, and a due list four days wrong is four days wrong for every
 * child in the district. So the migration stores days, the calculator does arithmetic on days, and
 * this screen renders days — with a weeks column beside them because that is how a schedule is
 * published and a clinician has to be able to recognise the row.
 *
 * <p><strong>The grace window is configuration and not a constant in Java.</strong> "Overdue" is a
 * judgement about how long after the due date a deployment starts chasing, and a schedule with that
 * hard-coded cannot be retuned by the district using it.
 *
 * <p>Read-only. There is no form for a schedule and there deliberately is not one: a published
 * national schedule is a document somebody transcribes once under review, not something edited on a
 * Tuesday afternoon — and a deployment able to edit the intervals could publish a due list it calls
 * UIP and which is not UIP. Adding one is an INSERT by a migration, which is named in the README as
 * a gap rather than implied here.
 */
export default async function SchedulePage() {
  const { data, error } = await load<ImmunisationSchedule[]>("/immunisations/schedules");
  const schedules = data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Published schedule</h1>
        <p className="text-sm text-ink-muted">
          What is expected, at what age, and how long after the previous dose. Every number is days
          from birth — never months, because a month is not a fixed number of days and a due list
          four days wrong is wrong for every child.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      {schedules.length === 0 && !error && (
        <Empty>
          No schedule is configured, which means the calling list has no rows to compute against and
          would read like a healthy population. That is a configuration problem:
          `immunisation.immunisation_schedules` is empty.
        </Empty>
      )}

      {schedules.map((schedule) => (
        <Card
          key={schedule.code}
          title={`${schedule.code} — ${schedule.name}`}
          action={
            schedule.active ? (
              <Badge tone="good">in use</Badge>
            ) : (
              <Badge tone="neutral">retired</Badge>
            )
          }
        >
          <p className="mb-3 text-sm text-ink-muted">
            {/*
              A schedule is bounded by age, and that bound is load-bearing rather than decoration:
              one claiming to apply to everybody produces a due list for a sixty-year-old made of
              doses it has no rows for.
            */}
            Applies from {schedule.appliesFromAgeDays} to {schedule.appliesToAgeDays} days of age
            {schedule.source ? ` · ${schedule.source}` : ""}. {schedule.doses.length} expected
            dose(s) across {new Set(schedule.doses.map((dose) => dose.antigenCode)).size} antigens.
          </p>

          <Table
            head={[
              "Antigen",
              "Dose",
              "Label",
              "Earliest",
              "Due",
              "Min interval",
              "Grace",
              "Window shuts",
            ]}
          >
            {schedule.doses.map((dose) => (
              <tr key={`${dose.antigenCode}-${dose.doseNumber}`} className="border-t border-line">
                <td className="numeric px-3 py-2">{dose.antigenCode}</td>
                <td className="numeric px-3 py-2">{dose.doseNumber}</td>
                <td className="px-3 py-2 text-xs">{dose.label}</td>
                <td className="numeric px-3 py-2 text-xs">
                  {dose.minAgeDays} d
                  <span className="block text-ink-muted">{weeks(dose.minAgeDays)}</span>
                </td>
                <td className="numeric px-3 py-2 text-xs">
                  <strong>{dose.dueAgeDays} d</strong>
                  <span className="block text-ink-muted">{weeks(dose.dueAgeDays)}</span>
                </td>
                <td className="numeric px-3 py-2 text-xs">
                  {/*
                    Present exactly when this is not the first dose, and the database enforces the
                    biconditional: an interval on dose 1 is measured from nothing, and a second
                    dose with no minimum interval is how two doses get given on one afternoon and
                    counted as two.
                  */}
                  {dose.minIntervalDays === null ? (
                    <span className="text-ink-muted">first dose</span>
                  ) : (
                    `${dose.minIntervalDays} d`
                  )}
                </td>
                <td className="numeric px-3 py-2 text-xs">{dose.graceDays} d</td>
                <td className="numeric px-3 py-2 text-xs">
                  {dose.maxAgeDays === null ? (
                    <span className="text-ink-muted">never</span>
                  ) : (
                    `${dose.maxAgeDays} d`
                  )}
                </td>
              </tr>
            ))}
          </Table>
        </Card>
      ))}
    </div>
  );
}

/** The published form of the same number, so a clinician can recognise the row. */
function weeks(days: number): string {
  if (days === 0) return "at birth";
  if (days % 7 === 0) return `${days / 7} wk`;
  return `${(days / 7).toFixed(1)} wk`;
}
