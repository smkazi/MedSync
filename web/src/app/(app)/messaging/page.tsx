import Link from "next/link";
import { load } from "@/lib/load";
import { currentUser, hasRole } from "@/lib/session";
import type {
  MessagingCapabilities,
  Notification,
  Page as PageResponse,
  PatientSummary,
} from "@/lib/types";
import { RecordForm } from "@/components/RecordForm";
import { Badge, Card, Empty, ErrorNote, Stat, Table, formatDateTime } from "@/components/ui";
import { CATEGORIES, CATEGORIES_NEEDING_A_DATE } from "./state";
import { sendMessage } from "./actions";

/**
 * The delivery log, and the one form that adds to it.
 *
 * <p>The page is a log first because that is the question people actually ask: was the patient
 * told? A queue that deleted what it had processed could not answer it, and a screen that only
 * offered a send button would leave "we messaged them" as something to take on trust.
 *
 * <p>Every message says that something is ready and where to sign in and see it. None of them says
 * what it is — not a value, not a flag, not a diagnosis, not even a name. A phone number is often
 * stale, is frequently shared within a family, and SMS is plaintext to the handset, so the message
 * body is written for the worst case where somebody else is reading it. The screen says so, because
 * a sender who does not know that will try to work around it.
 */
export default async function MessagingPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; mrn?: string }>;
}) {
  const { status = "", mrn = "" } = await searchParams;
  const user = await currentUser();

  const query = new URLSearchParams({ size: "100" });
  if (status) query.set("status", status);

  const [log, capabilities, patients] = await Promise.all([
    load<PageResponse<Notification>>(`/notifications?${query}`),
    load<MessagingCapabilities>("/notifications/capabilities"),
    // Only when somebody is looking one up: the send form needs a patient id and nobody knows one
    // by heart, so the form is keyed on an MRN search rather than a dropdown of every patient.
    mrn
      ? load<PageResponse<PatientSummary>>(`/patients?q=${encodeURIComponent(mrn)}&size=10`)
      : Promise.resolve({ data: null, error: null }),
  ]);

  const rows = log.data?.content ?? [];
  const suppressed = rows.filter((row) => row.status === "SUPPRESSED").length;
  const failed = rows.filter((row) => row.status === "FAILED").length;
  const channels = capabilities.data?.channels ?? [];
  const mayReword = hasRole(user, "ADMIN");

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Messaging</h1>
        <p className="text-sm text-ink-muted">
          Everything the platform has told a patient, and whether it arrived.
        </p>
      </div>

      {log.error && <ErrorNote>{log.error}</ErrorNote>}

      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Recorded" value={rows.length} hint="most recent first" />
        <Stat
          label="Not sent"
          value={suppressed}
          hint="composed and recorded, deliberately not sent"
        />
        <Stat label="Failed" value={failed} hint="the channel tried and could not" />
      </div>

      <Card title="What this deployment can send with">
        <p className="text-sm">
          {channels.length === 0 ? (
            "No channels are configured."
          ) : (
            <>
              {channels.map((channel) => (
                <span key={channel} className="mr-2">
                  <Badge tone={channel === "LOG" ? "neutral" : "good"}>{channel}</Badge>
                </span>
              ))}
            </>
          )}
        </p>
        <p className="mt-3 text-xs text-ink-muted">
          {channels.includes("SMS") || channels.includes("EMAIL")
            ? "A channel that is not listed falls back to the delivery log rather than failing — the log records which channel was really used, not which was asked for."
            : "Only the delivery log. Messages are composed and recorded exactly as they would be sent, and nothing leaves the platform: this is a deployment with no mail server and no SMS gateway configured, which is a working state rather than a broken one."}
        </p>
        {capabilities.data && !capabilities.data.contactLookupConfigured && (
          <p className="mt-2 rounded-md border border-warn/40 bg-warn-soft px-3 py-2 text-xs text-warn">
            No service account is configured, so patient contact details cannot be looked up. Every
            message will be recorded and none will be addressed.
          </p>
        )}
      </Card>

      <Card title="Tell a patient something">
        {/*
          There is no message box, and that is the design rather than an omission. The words come
          from a template keyed on the category, because an outbound message must carry no clinical
          information and a rule that depended on what somebody typed here would not be a rule.
        */}
        <p className="mb-3 text-xs text-ink-muted">
          The wording comes from the template for whichever category is chosen. There is no message
          box on purpose: an outbound message says that something is ready and where to sign in, and
          never what it says — a phone is often shared and SMS is plaintext to the handset. Anything
          specific goes in the portal, behind a sign-in, and the message points at it.{" "}
          {mayReword ? (
            <Link href="/messaging/templates" className="text-accent hover:underline">
              Change the wording
            </Link>
          ) : (
            <Link href="/messaging/templates" className="text-accent hover:underline">
              Read the wording
            </Link>
          )}
          .
        </p>

        <form className="mb-4 flex flex-wrap items-end gap-3">
          <div className="grow">
            <label htmlFor="mrn" className="block text-sm font-medium">
              Find a patient
            </label>
            <input
              id="mrn"
              name="mrn"
              defaultValue={mrn}
              placeholder="MRN or surname"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <button
            type="submit"
            className="rounded-md border border-line px-4 py-2 text-sm font-medium hover:bg-surface"
          >
            Search
          </button>
        </form>

        {patients.data && patients.data.content.length === 0 && (
          <Empty>No patient matches “{mrn}”.</Empty>
        )}

        {patients.data && patients.data.content.length > 0 && (
          <RecordForm
            action={sendMessage}
            columns={2}
            submitLabel="Send"
            busyLabel="Sending…"
            fields={[
              {
                name: "patientId",
                label: "Patient",
                type: "select",
                required: true,
                options: patients.data.content.map((patient) => ({
                  value: patient.id,
                  label: `${patient.fullName} — ${patient.mrn}`,
                })),
              },
              {
                name: "category",
                label: "What it is about",
                type: "select",
                required: true,
                options: CATEGORIES,
                hint: "Chooses the wording. It is the whole of what a sender decides about the words.",
              },
              {
                name: "channel",
                label: "Channel",
                type: "select",
                required: true,
                value: channels.includes("SMS") ? "SMS" : "LOG",
                options: (["SMS", "EMAIL", "LOG"] as const).map((channel) => ({
                  value: channel,
                  label: channels.includes(channel)
                    ? channel
                    : `${channel} — not configured, falls back to the log`,
                })),
              },
              {
                name: "when",
                label: "Date and time",
                hint: `Only used by: ${CATEGORIES_NEEDING_A_DATE.join(", ")}. A date is not a clinical finding, which is why it is one of exactly two values a message may carry.`,
                placeholder: "12 March, 10:30",
              },
              {
                name: "reference",
                label: "Reference",
                hint: "What it is about — an order or appointment id. Recorded for tracing and never put into the message.",
              },
            ]}
          />
        )}
      </Card>

      <Card
        title={`Delivery log${status ? ` — ${status.toLowerCase()}` : ""}`}
        action={
          <div className="flex gap-2 text-xs">
            {["", "SENT", "SUPPRESSED", "FAILED"].map((option) => (
              <Link
                key={option || "all"}
                href={`/messaging${option ? `?status=${option}` : ""}`}
                className={
                  option === status
                    ? "font-semibold text-accent"
                    : "text-ink-muted hover:text-accent"
                }
              >
                {option || "all"}
              </Link>
            ))}
          </div>
        }
      >
        {rows.length === 0 ? (
          <Empty>Nothing has been sent yet.</Empty>
        ) : (
          <Table head={["When", "About", "Channel", "To", "Message", "Outcome"]}>
            {rows.map((row) => (
              <tr key={row.id}>
                <td className="numeric px-3 py-2 text-ink-muted">{formatDateTime(row.createdAt)}</td>
                <td className="px-3 py-2">
                  <span className="numeric text-xs">{row.category}</span>
                </td>
                <td className="px-3 py-2 text-ink-muted">{row.channel}</td>
                <td className="numeric px-3 py-2 text-ink-muted">{row.recipient ?? "—"}</td>
                <td className="px-3 py-2">
                  {row.body}
                  {row.failedReason && (
                    <span className="mt-1 block text-xs text-ink-muted">{row.failedReason}</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <Badge
                    tone={
                      row.status === "SENT"
                        ? "good"
                        : row.status === "SUPPRESSED"
                          ? "warn"
                          : "critical"
                    }
                  >
                    {row.status.toLowerCase()}
                  </Badge>
                </td>
              </tr>
            ))}
          </Table>
        )}
        <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
          <strong>Not sent</strong> is a real outcome rather than a failure: the message was composed
          and recorded and deliberately not sent, because there was nowhere to send it or the record
          is archived. It is written down so that “the patient was never told” has evidence behind
          it.
        </p>
      </Card>
    </div>
  );
}
