"use client";

import { RecordForm } from "@/components/RecordForm";
import { replyToThread } from "../actions";

export function ReplyForm({ threadId }: { threadId: string }) {
  return (
    <RecordForm
      action={replyToThread}
      hidden={{ threadId }}
      submitLabel="Send reply"
      busyLabel="Sending…"
      columns={1}
      fields={[{ name: "body", label: "Your reply", type: "textarea", required: true }]}
    />
  );
}
