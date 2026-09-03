"use client";

import { RecordForm } from "@/components/RecordForm";
import { bookAppointment } from "./actions";

/**
 * The self-booking form.
 *
 * <p>Three fields the patient chooses and no more. There is no priority box and no room box,
 * because the request the platform accepts has nowhere to put either: urgency is a triage decision
 * and a room is allocated against the day's whole list. A greyed-out field would be a promise the
 * API does not keep.
 *
 * <p>The clinician is entered as an id rather than picked from a list, and that is an honest gap
 * rather than a design: a published directory of who is on which clinic is a screen this platform
 * does not have yet, and inventing one here would mean the portal listing clinicians that the
 * appointment book does not. It is named in the README's Roadmap.
 */
export function BookingForm({ clinicians }: { clinicians: { value: string; label: string }[] }) {
  return (
    <RecordForm
      action={bookAppointment}
      submitLabel="Request this time"
      busyLabel="Booking…"
      fields={[
        clinicians.length > 0
          ? {
              name: "clinicianId",
              label: "Who you would like to see",
              type: "select",
              options: clinicians,
              required: true,
            }
          : {
              name: "clinicianId",
              label: "Clinician reference",
              required: true,
              hint: "As given to you by the department.",
            },
        { name: "departmentCode", label: "Department", required: true, placeholder: "GEN" },
        {
          name: "startsAt",
          label: "Date and time",
          type: "text",
          required: true,
          placeholder: "2026-09-20T10:30",
          hint: "The clinic's published times are on the availability panel.",
        },
        {
          name: "durationMinutes",
          label: "Minutes",
          type: "number",
          value: 15,
          hint: "Leave at 15 unless the department asked for longer.",
        },
        {
          name: "reason",
          label: "What it is about",
          type: "textarea",
          hint: "A sentence is enough. Do not use this for anything urgent.",
        },
      ]}
    />
  );
}
