import Link from "next/link";
import { load } from "@/lib/load";
import { Badge, Card, ErrorNote, formatDateTime, statusTone } from "@/components/ui";
import type { MessageThread } from "@/lib/types";
import { ReplyForm } from "./ReplyForm";

export const metadata = { title: "A conversation — MedSync" };

/**
 * One conversation, and the reply box if it is still open.
 *
 * <p>Opening this page is what "read" means: the platform marks the hospital's messages read when
 * it serves the thread, rather than waiting for a separate call this page could forget to make. An
 * unread badge nobody trusts is an unread badge nobody looks at.
 *
 * <p>The standing notice comes from the platform on every thread and cannot be suppressed by a
 * caller, which is why it is rendered from the response rather than written into this file. The
 * person who most needs to read it is the one already typing, and they reached this page from an
 * email rather than from the list that carried the warning.
 */
export default async function PortalThread({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const thread = await load<MessageThread>(`/portal/messages/${id}`);

  if (!thread.data) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">A conversation</h1>
        <ErrorNote>{thread.error ?? "This conversation could not be opened."}</ErrorNote>
        <Link href="/portal/messages" className="text-sm underline">
          Back to your messages
        </Link>
      </div>
    );
  }

  const conversation = thread.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{conversation.subject}</h1>
        <p className="mt-1 flex items-center gap-2 text-sm text-ink-muted">
          <Badge tone={statusTone(conversation.status)}>{conversation.status}</Badge>
          {conversation.departmentCode ?? "General enquiries"}
        </p>
      </div>

      <Card title="The conversation">
        <ol className="space-y-4">
          {conversation.messages.map((message) => (
            <li
              key={message.id}
              className={
                message.authorKind === "PATIENT"
                  ? "rounded-md border border-line bg-surface px-3 py-2"
                  : "rounded-md border border-accent/30 bg-surface-raised px-3 py-2"
              }
            >
              <p className="text-xs text-ink-muted">
                {message.authorKind === "PATIENT" ? "You" : `The hospital · ${message.authorName}`}
                {" · "}
                {formatDateTime(message.sentAt)}
              </p>
              <p className="mt-1 whitespace-pre-wrap text-sm">{message.body}</p>
            </li>
          ))}
        </ol>
      </Card>

      {conversation.status === "CLOSED" ? (
        <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
          This conversation has been closed{conversation.closedAt ? ` on ${formatDateTime(conversation.closedAt)}` : ""}.
          Start a new one for a new question — it keeps each question with its own answer.
        </p>
      ) : (
        <Card title="Reply">
          <p className="mb-3 text-sm text-ink-muted">{conversation.notice}</p>
          <ReplyForm threadId={conversation.id} />
        </Card>
      )}

      <Link href="/portal/messages" className="text-sm underline">
        Back to your messages
      </Link>
    </div>
  );
}
