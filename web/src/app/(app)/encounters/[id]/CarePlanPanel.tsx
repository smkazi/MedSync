import type { CareGoal, CarePlan, Diagnosis } from "@/lib/types";
import { Badge, Empty, Table, statusTone } from "@/components/ui";
import { GOAL_OUTCOMES } from "./state";
import { addCareGoal, closeCarePlan, recordCareGoal } from "./actions";

/**
 * The care plan, once one exists.
 *
 * <p>Its own component rather than more markup on the chart, for a plain reason: the chart page
 * loads the plan as `{ data, error }` and every reference to it inside a conditional had to be
 * re-narrowed. Passing the narrowed value in once is clearer than proving it is non-null eleven
 * times, and the plan is a self-contained thing on the screen anyway.
 *
 * <p>An overdue goal — past its date and still open — is tinted rather than merely labelled,
 * because a ward round reads this at a glance and the label is what somebody scrolls past.
 */
export function CarePlanPanel({
  plan,
  encounterId,
  diagnoses,
}: {
  plan: CarePlan;
  encounterId: string;
  diagnoses: Diagnosis[];
}) {
  const active = plan.status === "ACTIVE";

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="font-medium">{plan.title}</span>
        <Badge tone={statusTone(plan.status)}>{plan.status.toLowerCase()}</Badge>
      </div>

      {plan.goals.length === 0 ? (
        <Empty>No goals yet.</Empty>
      ) : (
        <Table head={["Goal", "Problem", "By", "Status", ""]}>
          {plan.goals.map((goal) => (
            <GoalRow key={goal.id} goal={goal} encounterId={encounterId} planActive={active} />
          ))}
        </Table>
      )}

      {active && (
        <div className="space-y-3 border-t border-line pt-3">
          <form action={addCareGoal} className="flex flex-wrap items-end gap-2">
            <input type="hidden" name="encounterId" value={encounterId} />
            <input type="hidden" name="planId" value={plan.id} />
            <div className="grow">
              <label htmlFor="goal-description" className="block text-xs text-ink-muted">
                Goal
              </label>
              <input
                id="goal-description"
                name="description"
                required
                placeholder="Mobilising independently"
                className="mt-1 w-full rounded border border-line bg-surface-raised px-2 py-1 text-sm"
              />
            </div>
            <div>
              <label htmlFor="goal-problem" className="block text-xs text-ink-muted">
                Problem
              </label>
              <select
                id="goal-problem"
                name="problemCode"
                className="mt-1 rounded border border-line bg-surface-raised px-2 py-1 text-sm"
              >
                {/* Only this encounter's own diagnoses. The service refuses anything else, so
                    offering a free-text code would be offering a refusal. */}
                <option value="">— none —</option>
                {diagnoses.map((diagnosis) => (
                  <option key={diagnosis.id} value={diagnosis.icd10Code}>
                    {diagnosis.icd10Code}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="goal-date" className="block text-xs text-ink-muted">
                By
              </label>
              <input
                id="goal-date"
                name="targetDate"
                type="date"
                className="mt-1 rounded border border-line bg-surface-raised px-2 py-1 text-sm"
              />
            </div>
            <button
              type="submit"
              className="rounded border border-line px-3 py-1.5 text-sm font-medium hover:bg-surface"
            >
              Add
            </button>
          </form>

          <form action={closeCarePlan} className="flex flex-wrap items-center gap-2">
            <input type="hidden" name="encounterId" value={encounterId} />
            <input type="hidden" name="planId" value={plan.id} />
            <input type="hidden" name="outcome" value="COMPLETED" />
            <button
              type="submit"
              className="rounded border border-line px-3 py-1.5 text-xs hover:bg-surface"
            >
              Close the plan
            </button>
            <span className="text-xs text-ink-muted">
              Refused while a goal is still open — which is the point: it makes somebody decide,
              rather than letting &ldquo;we were going to do that&rdquo; disappear at discharge.
            </span>
          </form>
        </div>
      )}
    </div>
  );
}

function GoalRow({
  goal,
  encounterId,
  planActive,
}: {
  goal: CareGoal;
  encounterId: string;
  planActive: boolean;
}) {
  return (
    <tr className={goal.overdue ? "bg-warn-soft/30" : ""}>
      <td className="px-3 py-2">
        {goal.description}
        {goal.progressNote && (
          <span className="block text-xs text-ink-muted">
            {goal.progressNote} — {goal.updatedBy}
          </span>
        )}
      </td>
      <td className="numeric px-3 py-2 text-xs">{goal.problemCode ?? "—"}</td>
      <td className="numeric px-3 py-2 text-xs">
        {goal.targetDate ?? "—"}
        {goal.overdue && <span className="ml-1 font-semibold text-warn">overdue</span>}
      </td>
      <td className="px-3 py-2">
        <Badge
          tone={goal.status === "MET" ? "good" : goal.status === "OPEN" ? "neutral" : "warn"}
        >
          {goal.status.toLowerCase().replace("_", " ")}
        </Badge>
      </td>
      <td className="px-3 py-2">
        {goal.status === "OPEN" && planActive && (
          <form action={recordCareGoal} className="flex flex-wrap items-center gap-1">
            <input type="hidden" name="encounterId" value={encounterId} />
            <input type="hidden" name="goalId" value={goal.id} />
            <select
              name="status"
              aria-label={`Outcome for ${goal.description}`}
              className="rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
            >
              {GOAL_OUTCOMES.map((outcome) => (
                <option key={outcome.value} value={outcome.value}>
                  {outcome.label}
                </option>
              ))}
            </select>
            <input
              name="progressNote"
              placeholder="note"
              aria-label={`Note for ${goal.description}`}
              className="w-28 rounded border border-line bg-surface-raised px-1.5 py-1 text-xs"
            />
            <button
              type="submit"
              className="rounded border border-line px-2 py-1 text-xs hover:bg-surface"
            >
              Record
            </button>
          </form>
        )}
      </td>
    </tr>
  );
}
