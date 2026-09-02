import type { NotificationCategory } from "@/lib/types";

/**
 * Field names and the pick-lists behind them. Out of `actions.ts` because a `"use server"` module
 * may export only async functions.
 */

export const SEND_FIELDS = ["category", "channel", "patientId", "reference", "when"] as const;

/**
 * What a message can be about — and, because the wording comes from a template keyed on this, the
 * whole of a sender's influence over the words.
 */
export const CATEGORIES: { value: NotificationCategory; label: string }[] = [
  { value: "PORTAL_MESSAGE", label: "A message is waiting in the portal" },
  { value: "LAB_REPORT_READY", label: "A laboratory report is ready" },
  { value: "APPOINTMENT_CONFIRMED", label: "An appointment is confirmed" },
  { value: "APPOINTMENT_REMINDER", label: "A reminder about an appointment" },
  { value: "APPOINTMENT_CANCELLED", label: "An appointment was cancelled" },
];

/** The categories whose template interpolates a date, so the form asks for one. */
export const CATEGORIES_NEEDING_A_DATE: NotificationCategory[] = [
  "APPOINTMENT_CONFIRMED",
  "APPOINTMENT_REMINDER",
  "APPOINTMENT_CANCELLED",
];
