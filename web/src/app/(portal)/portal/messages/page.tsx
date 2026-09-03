import Link from "next/link";
import { load } from "@/lib/load";
import { Badge, Card, Empty, ErrorNote, Table, formatDateTime, statusTone } from "@/components/ui";
import type { MessageThreadSummary } from "@/lib/types";
import { StartThreadForm } from "./StartThreadForm";

export const metadata = { title: "Your messages — MedSync" };

/**
 * The patient's inbox.
 *
 * <p>Subjects only. A list that previewed the first line of each conversation would put a clinical
 * sentence into every screenshot, every shoulder-surf and every back-button cache of this page —
 * and the platform does not send the bodies to this endpoint, so there is nothing here to leak
 * whatever this markup did.
 */
export default async function PortalMessages() {
  const threads = await load<MessageThreadSummary[]>("/portal/messages");
  const rows = threads.data ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Your messages</h1>
        <p className="mt-1 text-sm text-ink-muted">
          Written questions to the hospital, and its answers. Read during working hours.
        </p>
      </div>

      <Card title="Conversations">
        {threads.error ? <ErrorNote>{threads.error}</ErrorNote> : null}
        {rows.length === 0 ? (
          <Empty>Nothing yet. You can start a conversation below.</Empty>
        ) : (
          <Table head={["About", "Department", "Last activity", "State", ""]}>
            {rows.map((thread) => (
              <tr key={thread.id} className="border-t border-line">
                <td className="px-3 py-2">
                  {thread.subject}
                  {thread.unreadByPatient ? (
                    <Badge tone="accent">New reply</Badge>
                  ) : null}
                </td>
                <td className="px-3 py-2 text-ink-muted">{thread.departmentCode ?? "General"}</td>
                <td className="px-3 py-2">{formatDateTime(thread.lastMessageAt)}</td>
                <td className="px-3 py-2">
                  <Badge tone={statusTone(thread.status)}>{thread.status}</Badge>
                </td>
                <td className="px-3 py-2">
                  <Link href={`/portal/messages/${thread.id}`} className="text-sm underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
          </Table>
        )}
      </Card>

      <Card title="Ask the hospital something">
        <p className="mb-3 text-sm text-ink-muted">
          Messages are read during working hours and are not monitored continuously. If this is
          urgent or you feel unwell, telephone the hospital or come to casualty.
        </p>
        <StartThreadForm />
      </Card>
    </div>
  );
}
