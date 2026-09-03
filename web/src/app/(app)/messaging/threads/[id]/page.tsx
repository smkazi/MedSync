import Link from "next/link";
import { load } from "@/lib/load";
import type { MessageThread } from "@/lib/types";
import { Badge, Card, ErrorNote, formatDateTime, statusTone } from "@/components/ui";
import { ReplyForm } from "../ReplyForm";
import { closeThread } from "../actions";

export const metadata = { title: "A patient's question — MedSync" };

/**
 * One conversation, from the hospital's side.
 *
 * <p>The patient is named by MRN rather than by name, which is the same narrowing every other
 * cross-module screen makes: answering a question needs to know whose record it is, not who they
 * are, and the chart is one click away for anybody who needs the rest.
 */
export default async function StaffThread({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ done?: string; problem?: string }>;
}) {
  const { id } = await params;
  const { done, problem } = await searchParams;
  const thread = await load<MessageThread>(`/notifications/messages/${id}`);

  if (!thread.data) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">A patient&apos;s question</h1>
        <ErrorNote>{thread.error ?? "This conversation could not be opened."}</ErrorNote>
        <Link href="/messaging/threads" className="text-sm underline">
          Back to the queue
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
          <span className="numeric">{conversation.patientMrn}</span>
          <Link href={`/patients/${conversation.patientId}`} className="underline">
            Open chart
          </Link>
        </p>
      </div>

      {problem ? <ErrorNote>{problem}</ErrorNote> : null}
      {done ? (
        <p
          role="status"
          className="rounded-md border border-good/40 bg-good-soft px-3 py-2 text-sm text-good"
        >
          {done}
        </p>
      ) : null}

      <Card title="The conversation">
        <ol className="space-y-4">
          {conversation.messages.map((message) => (
            <li
              key={message.id}
              className={
                message.authorKind === "PATIENT"
                  ? "rounded-md border border-accent/30 bg-surface-raised px-3 py-2"
                  : "rounded-md border border-line bg-surface px-3 py-2"
              }
            >
              <p className="text-xs text-ink-muted">
                {message.authorKind === "PATIENT" ? "The patient" : `Us · ${message.authorName}`}
                {" · "}
                {formatDateTime(message.sentAt)}
                {message.authorKind === "STAFF"
                  ? message.readByPatientAt
                    ? ` · read ${formatDateTime(message.readByPatientAt)}`
                    : " · not read yet"
                  : ""}
              </p>
              <p className="mt-1 whitespace-pre-wrap text-sm">{message.body}</p>
            </li>
          ))}
        </ol>
      </Card>

      {conversation.status === "CLOSED" ? (
        <p className="rounded-md border border-line bg-surface-raised px-4 py-3 text-sm text-ink-muted">
          Closed{conversation.closedAt ? ` on ${formatDateTime(conversation.closedAt)}` : ""}. Neither
          side can add to it; the patient can start a new conversation.
        </p>
      ) : (
        <>
          <Card title="Reply">
            <ReplyForm threadId={conversation.id} />
          </Card>
          <form action={closeThread}>
            <input type="hidden" name="threadId" value={conversation.id} />
            <button
              type="submit"
              className="rounded-md border border-line px-3 py-1.5 text-sm hover:bg-surface"
            >
              Close this conversation
            </button>
          </form>
        </>
      )}

      <Link href="/messaging/threads" className="text-sm underline">
        Back to the queue
      </Link>
    </div>
  );
}
