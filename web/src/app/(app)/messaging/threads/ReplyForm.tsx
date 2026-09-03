"use client";

import { RecordForm } from "@/components/RecordForm";
import { replyToPatient } from "./actions";

export function ReplyForm({ threadId }: { threadId: string }) {
  return (
    <RecordForm
      action={replyToPatient}
      hidden={{ threadId }}
      submitLabel="Send to the patient"
      busyLabel="Sending…"
      columns={1}
      fields={[
        {
          name: "body",
          label: "Your reply",
          type: "textarea",
          required: true,
          hint: "The patient reads this behind their own sign-in, so it may say what an SMS may not.",
        },
      ]}
    />
  );
}
