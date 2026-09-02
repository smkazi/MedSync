import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { InterpretiveRule, MorphologyThreshold } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { updateInterpretiveRule, updateMorphologyThreshold } from "../actions";

/**
 * The rules behind the narrative on a report.
 *
 * <p>Shows the conditions, not just the message, because a rule that fires is only trustworthy if
 * you can see what made it fire. Anisocytosis is the case that justifies the shape: it needs both
 * RDW-CV and RDW-SD raised, since either measure alone is unreliable.
 */
export default async function InterpretationPage() {
  const [{ data: rules, error }, { data: thresholds }] = await Promise.all([
    load<InterpretiveRule[]>("/lab/interpretive-rules"),
    load<MorphologyThreshold[]>("/lab/morphology-thresholds"),
  ]);
  const mayRetune = hasRole(await currentUser(), "ADMIN", "PATHOLOGIST");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Interpretation rules</h1>
        <p className="text-sm text-ink-muted">
          The comments a report prints, and the cut-offs the derived smear morphology uses.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title="Three tiers of number, deliberately kept apart">
        <dl className="space-y-2 text-sm">
          <div className="flex gap-3">
            <dt className="w-44 shrink-0 font-medium">Reference interval</dt>
            <dd className="text-ink-muted">
              Is this value outside normal? Haemoglobin flags <strong>L</strong> below 11.5 g/dL for
              a woman.
            </dd>
          </div>
          <div className="flex gap-3">
            <dt className="w-44 shrink-0 font-medium">Interpretive threshold</dt>
            <dd className="text-ink-muted">
              Does it need saying out loud? The same haemoglobin only earns a comment below 9.0. A
              report that printed a paragraph for every out-of-range number is one nobody reads.
            </dd>
          </div>
          <div className="flex gap-3">
            <dt className="w-44 shrink-0 font-medium">Morphology cut-off</dt>
            <dd className="text-ink-muted">
              What are the cells called? A red cell is microcytic below MCV 76, while the
              microcytosis <em>comment</em> fires below 70.
            </dd>
          </div>
        </dl>
      </Card>

      {rules && (
        <Card title={`Interpretive rules (${rules.filter((rule) => rule.active).length} active)`}>
          {rules.length === 0 ? (
            <Empty>No rules are configured.</Empty>
          ) : (
            <Table head={["Rule", "Fires when", "Comment", "", ""]}>
              {rules.map((rule) => (
                <tr key={rule.id} className={rule.active ? "" : "opacity-60"}>
                  <td className="px-3 py-2 font-medium">{rule.label}</td>
                  <td className="numeric px-3 py-2 text-xs text-ink-muted">
                    {rule.conditions.length === 0 ? (
                      "—"
                    ) : (
                      <span>
                        {rule.conditions
                          .map(
                            (condition) =>
                              `${condition.parameters.join(" / ")} ${condition.operator} ${condition.threshold}`,
                          )
                          .join("  AND  ")}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2 text-ink-muted">{rule.message}</td>
                  <td className="px-3 py-2">
                    {rule.active ? null : <Badge tone="neutral">off</Badge>}
                  </td>
                  <td className="px-3 py-2">
                    {mayRetune && (
                      <EditRow label="Reword">
                        <RecordForm
                          action={updateInterpretiveRule}
                          hidden={{ code: rule.code }}
                          columns={1}
                          submitLabel="Save rule"
                          fields={[
                            {
                              name: "message",
                              label: "Comment printed on the report",
                              type: "textarea",
                              value: rule.message,
                            },
                            { name: "active", label: "Rule fires", type: "checkbox", value: rule.active },
                          ]}
                        />
                        <p className="mt-2 text-xs text-ink-muted">
                          The wording and whether it fires — not the conditions. What makes the rule
                          trigger is{" "}
                          {rule.conditions.length === 0
                            ? "not condition-driven"
                            : rule.conditions
                                .map(
                                  (condition) =>
                                    `${condition.parameters.join(" / ")} ${condition.operator} ${condition.threshold}`,
                                )
                                .join(" AND ")}
                          , and changing that is a migration rather than a form: a condition is what
                          the rule <em>means</em>, and rewording a sentence is not.
                        </p>
                      </EditRow>
                    )}
                  </td>
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      {thresholds && (
        <Card title="Morphology cut-offs">
          {thresholds.length === 0 ? (
            <Empty>No thresholds are configured.</Empty>
          ) : (
            <Table
              head={["Code", "Threshold", "What it decides", ...(mayRetune ? [""] : [])]}
            >
              {thresholds.map((threshold) => (
                <tr key={threshold.code}>
                  <td className="numeric px-3 py-2 font-medium">{threshold.code}</td>
                  <td className="numeric px-3 py-2">{threshold.threshold}</td>
                  <td className="px-3 py-2 text-ink-muted">{threshold.note}</td>
                  {mayRetune && (
                    <td className="px-3 py-2">
                      <EditRow label="Retune">
                        <RecordForm
                          action={updateMorphologyThreshold}
                          hidden={{ code: threshold.code }}
                          columns={1}
                          submitLabel="Save cut-off"
                          fields={[
                            {
                              name: "threshold",
                              label: "Cut-off",
                              type: "number",
                              step: "any",
                              required: true,
                              value: threshold.threshold,
                            },
                          ]}
                        />
                        <p className="mt-2 text-xs text-ink-muted">
                          The number, not the note. &ldquo;{threshold.note}&rdquo; appears verbatim
                          on signed reports, so rewording it is a different act from moving the
                          threshold and the service has no setter for it.
                        </p>
                      </EditRow>
                    </td>
                  )}
                </tr>
              ))}
            </Table>
          )}
        </Card>
      )}

      <p className="text-sm text-ink-muted">
        {mayRetune
          ? "Every change here is audited, and every one of them changes what appears on reports a pathologist signs. A rule's conditions, its label and its display order are migration-level: they are what the rule means, rather than how it is worded. Rules cannot be created or deleted from the API at all."
          : "Retuning a rule's wording, switching a rule off, or moving a morphology cut-off is restricted to an administrator or a pathologist, and audited — these change what appears on signed reports. A rule's conditions are migration-level either way."}
      </p>
    </div>
  );
}
