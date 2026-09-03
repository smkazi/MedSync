"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { MessageThread } from "@/lib/types";

const REPLY_FIELDS = ["threadId", "body"] as const;

/**
 * The hospital's half of a conversation.
 *
 * <p>A reply moves the thread to ANSWERED and closing it is final on both sides. Neither is a
 * status a caller sets: the platform derives the first from who wrote last, because a settable one
 * would be set to ANSWERED by whoever wanted the queue to look shorter.
 */
export async function replyToPatient(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, REPLY_FIELDS);
  const result = await submit<MessageThread>(
    `/notifications/messages/${values.threadId}/replies`,
    "POST",
    { body: values.body },
  );
  if (!result.ok) return refused(values, result);

  revalidatePath(`/messaging/threads/${values.threadId}`);
  revalidatePath("/messaging/threads");
  return { values: {}, fieldErrors: {}, error: null, done: "Sent to the patient." };
}

export async function closeThread(form: FormData): Promise<void> {
  const id = String(form.get("threadId") ?? "");
  const result = await submit<MessageThread>(`/notifications/messages/${id}/close`, "POST");
  revalidatePath(`/messaging/threads/${id}`);
  revalidatePath("/messaging/threads");
  redirect(
    result.ok
      ? `/messaging/threads/${id}?done=${encodeURIComponent("Closed. The patient can start a new conversation.")}`
      : `/messaging/threads/${id}?problem=${encodeURIComponent(result.error)}`,
  );
}
