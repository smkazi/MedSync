import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type { MessageTemplate } from "@/lib/types";
import { EditRow, RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Table } from "@/components/ui";
import { updateTemplate } from "../actions";

/**
 * The platform's voice to a patient.
 *
 * <p>Rows rather than compiled strings because a hospital rewrites these, translates them, and has
 * a legal opinion about them. What is not configurable is which values a template may interpolate:
 * the set is two wide, and the service refuses anything else at the moment somebody writes it. That
 * refusal is where the whole no-PHI-in-messages rule actually lives — if a template could say
 * <code>{"{value}"}</code>, then rewording a message would be enough to put a laboratory result
 * into an SMS.
 */
export default async function MessageTemplatesPage() {
  const { data: templates, error } = await load<MessageTemplate[]>("/notifications/templates");
  const mayReword = hasRole(await currentUser(), "ADMIN");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Message wording</h1>
        <p className="text-sm text-ink-muted">
          What a patient actually reads, per category and channel.
        </p>
      </div>

      {error && <ErrorNote>{error}</ErrorNote>}

      <Card title="Two values, and no others">
        <dl className="space-y-2 text-sm">
          <div className="flex gap-3">
            <dt className="w-32 shrink-0 font-medium">
              <span className="numeric">{"{portalUrl}"}</span>
            </dt>
            <dd className="text-ink-muted">
              Where the patient signs in to read the thing this message says exists. The whole design
              depends on there being somewhere behind a sign-in to point at.
            </dd>
          </div>
          <div className="flex gap-3">
            <dt className="w-32 shrink-0 font-medium">
              <span className="numeric">{"{when}"}</span>
            </dt>
            <dd className="text-ink-muted">
              A date and time, for the appointment categories. Allowed because a date is not a
              clinical finding: somebody reading a shared handset learns that this person has an
              appointment, which the message&apos;s existence already told them, and not what it is
              for or who it is with.
            </dd>
          </div>
        </dl>
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          Anything else is refused when the template is saved. A phone number is often stale and
          frequently shared, and SMS is plaintext to the handset — so a message is written for the
          case where somebody other than the patient is reading it. A message whose <em>existence</em>{" "}
          implied bad news would be as much of a disclosure as one that said so, which is why a
          released report reads the same whether it is entirely normal or entirely not.
        </p>
      </Card>

      {templates && (
        <Card title={`Templates (${templates.filter((row) => row.active).length} active)`}>
          {templates.length === 0 ? (
            <Empty>No templates are configured.</Empty>
          ) : (
            <Table
              head={["Category", "Channel", "Subject", "Body", "", ...(mayReword ? [""] : [])]}
            >
              {templates.map((template) => (
                <tr key={template.id} className={template.active ? "" : "opacity-60"}>
                  <td className="numeric px-3 py-2 font-medium">{template.category}</td>
                  <td className="px-3 py-2 text-ink-muted">{template.channel}</td>
                  <td className="px-3 py-2 text-ink-muted">{template.subject ?? "—"}</td>
                  <td className="px-3 py-2">{template.body}</td>
                  <td className="px-3 py-2">
                    {template.active ? null : <Badge tone="neutral">off</Badge>}
                  </td>
                  {mayReword && (
                    <td className="px-3 py-2">
                      <EditRow label="Reword">
                        <RecordForm
                          action={updateTemplate}
                          hidden={{ id: template.id }}
                          columns={1}
                          submitLabel="Save wording"
                          fields={[
                            ...(template.channel === "EMAIL"
                              ? [
                                  {
                                    name: "subject",
                                    label: "Subject",
                                    value: template.subject,
                                    hint: "The part shown on a locked screen, so held to the same rule as the body.",
                                  },
                                ]
                              : []),
                            {
                              name: "body",
                              label: "Body",
                              type: "textarea" as const,
                              value: template.body,
                            },
                            {
                              name: "active",
                              label: "Template is in use",
                              type: "checkbox" as const,
                              value: template.active,
                            },
                          ]}
                        />
                        <p className="mt-2 text-xs text-ink-muted">
                          Switching a template off does not stop the message: the platform records a
                          row saying there was no active wording for that category and channel, so
                          the gap is visible rather than silent.
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

      {!mayReword && (
        <p className="text-sm text-ink-muted">
          Rewording these is restricted to an administrator, and audited — this is what every patient
          reads.
        </p>
      )}
    </div>
  );
}
