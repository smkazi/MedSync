"use client";

import { RecordForm } from "@/components/RecordForm";
import { startThread } from "./actions";

export function StartThreadForm() {
  return (
    <RecordForm
      action={startThread}
      submitLabel="Send"
      busyLabel="Sending…"
      columns={1}
      fields={[
        { name: "subject", label: "What it is about", required: true, placeholder: "My discharge medicines" },
        {
          name: "departmentCode",
          label: "Department (optional)",
          placeholder: "GEN",
          hint: "Leave blank and it goes to general enquiries.",
        },
        { name: "body", label: "Your message", type: "textarea", required: true },
      ]}
    />
  );
}
