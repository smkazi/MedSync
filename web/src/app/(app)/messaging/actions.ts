"use server";

import { revalidatePath } from "next/cache";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { MessageTemplate, Notification } from "@/lib/types";
import { SEND_FIELDS } from "./state";

/**
 * Outbound messaging.
 *
 * <p>Note what the send form cannot do: write the message. A caller picks a category and the words
 * come from a template, because the module's rule is that an outbound message carries no protected
 * health information — and a rule that depended on what somebody typed into a free-text box would
 * not be a rule at all. If a clinician needs to say something specific, the specific thing goes in
 * the portal behind a sign-in and the message says to go and read it.
 */

export async function sendMessage(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, SEND_FIELDS);

  const result = await submit<Notification>("/notifications", "POST", withoutBlanks(values));
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/messaging");

  // The platform's own outcome, not a cheerful assumption. A SUPPRESSED result means the message
  // was composed and recorded and deliberately not sent, and the reason is the thing the sender
  // needs to read - "no phone number on file" is a task for the front desk.
  const sent = result.data;
  return {
    values: {},
    fieldErrors: {},
    error: null,
    done:
      sent.status === "SENT"
        ? `Sent on ${sent.channel.toLowerCase()} to ${sent.recipient}.`
        : sent.status === "SUPPRESSED"
          ? `Not sent, and recorded as such. ${sent.failedReason ?? ""}`.trim()
          : `Delivery failed. ${sent.failedReason ?? ""}`.trim(),
  };
}

/**
 * Rewords a template.
 *
 * <p>The refusal worth surfacing verbatim is the placeholder one: the service answers with the
 * closed set a message may interpolate and why, and reworded into anything else it would be a
 * disclosure. "Bad Request" would leave an administrator guessing.
 */
export async function updateTemplate(_previous: FormState, form: FormData): Promise<FormState> {
  const id = String(form.get("id") ?? "");
  const values = readForm(form, ["subject", "body", "active"] as const);

  const body: Record<string, unknown> = {};
  for (const [field, value] of Object.entries(withoutBlanks(values))) {
    const text = String(value);
    body[field] = text === "true" || text === "false" ? text === "true" : text;
  }

  const result = await submit<MessageTemplate>(`/notifications/templates/${id}`, "PATCH", body);
  if (!result.ok) {
    return refused(values, result);
  }
  revalidatePath("/messaging/templates");
  return { values: {}, fieldErrors: {}, error: null, done: "Wording updated." };
}
