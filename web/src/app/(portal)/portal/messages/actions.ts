"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { readForm, refused, withoutBlanks, type FormState } from "@/lib/form";
import { submit } from "@/lib/mutate";
import type { MessageThread } from "@/lib/types";
import { REPLY_FIELDS, START_THREAD_FIELDS } from "./state";

/**
 * Starting a conversation, and replying to one.
 *
 * <p>Neither sends a patient id: the thread is filed under the session's patient and a reply is
 * refused unless the thread already belongs to them. Nothing here can be pointed at somebody
 * else's correspondence by editing the page.
 */
export async function startThread(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, START_THREAD_FIELDS);
  const result = await submit<MessageThread>(
    "/portal/messages",
    "POST",
    withoutBlanks(values),
  );
  if (!result.ok) return refused(values, result);

  revalidatePath("/portal/messages");
  revalidatePath("/portal");
  // Redirect into the thread rather than returning state: the form is on the list page and the
  // thing the patient wants next is the conversation they have just opened.
  redirect(`/portal/messages/${result.data.id}`);
}

export async function replyToThread(_previous: FormState, form: FormData): Promise<FormState> {
  const values = readForm(form, REPLY_FIELDS);
  const result = await submit<MessageThread>(
    `/portal/messages/${values.threadId}/replies`,
    "POST",
    { body: values.body },
  );
  if (!result.ok) return refused(values, result);

  revalidatePath(`/portal/messages/${values.threadId}`);
  revalidatePath("/portal/messages");
  return { values: {}, fieldErrors: {}, error: null, done: "Sent." };
}
