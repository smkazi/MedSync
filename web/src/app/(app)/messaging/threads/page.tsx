import Link from "next/link";
import { load } from "@/lib/load";
import type { MessageThreadSummary, Page as PageResponse } from "@/lib/types";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime, statusTone } from "@/components/ui";

export const metadata = { title: "Patient questions — MedSync" };

/**
 * Written questions from the portal, and the queue that answers them.
 *
 * <p>Oldest first, and the ordering is not a preference. A queue served newest-first starves the
 * person who has been waiting longest, and in an inbox nobody notices because the screen always
 * looks busy — the same argument the casualty board makes about arrival order, one floor down.
 *
 * <p>This is where the platform's PHI rule turns around. Everything on the delivery log is written
 * for the worst case in which somebody else is reading the handset; everything here is behind a
 * password the patient chose, and it may say what the SMS may not. That is the point of the link
 * every notification carries.
 */
export default async function PatientQuestions({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const { status = "" } = await searchParams;
  const query = status ? `?status=${encodeURIComponent(status)}&size=50` : "?size=50";
  const threads = await load<PageResponse<MessageThreadSummary>>(`/notifications/messages${query}`);
  const rows = threads.data?.content ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Patient questions</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Written questions from the patient portal, oldest first. Read during working hours — the
          portal tells patients so, and tells them to telephone or come in if it is urgent.
        </p>
      </div>

      <Card
        title="The queue"
        action={
          <form className="flex items-center gap-2">
            <label htmlFor="status" className="text-sm text-ink-muted">
              Show
            </label>
            <select
              id="status"
              name="status"
              defaultValue={status}
              className="rounded-md border border-line bg-surface-raised px-2 py-1 text-sm"
            >
              <option value="">Everything</option>
              <option value="OPEN">Waiting for us</option>
              <option value="ANSWERED">Answered</option>
              <option value="CLOSED">Closed</option>
            </select>
            <button
              type="submit"
              className="rounded-md border border-line px-3 py-1 text-sm hover:bg-surface"
            >
              Apply
            </button>
          </form>
        }
      >
        {threads.error ? <ErrorNote>{threads.error}</ErrorNote> : null}
        {rows.length === 0 ? (
          <Empty>Nothing in this queue.</Empty>
        ) : (
          <Table head={["Waiting since", "Patient", "About", "Department", "State", ""]}>
            {rows.map((thread) => (
              <tr key={thread.id} className="border-t border-line">
                <td className="px-3 py-2">{formatDateTime(thread.lastMessageAt)}</td>
                <td className="numeric px-3 py-2">{thread.patientMrn}</td>
                <td className="px-3 py-2">{thread.subject}</td>
                <td className="px-3 py-2 text-ink-muted">{thread.departmentCode ?? "General"}</td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(thread.status)}>{thread.status}</Badge>
                </td>
                <td className="px-3 py-2">
                  <Link href={`/messaging/threads/${thread.id}`} className="text-sm underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}
