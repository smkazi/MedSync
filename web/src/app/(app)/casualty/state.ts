/**
 * Casualty field names and the acuity vocabulary. Out of `actions.ts` because a `"use server"`
 * module may export only async functions.
 */

export const ARRIVAL_FIELDS = [
  "patientId",
  "patientMrn",
  "triageAcuity",
  "presentingComplaint",
] as const;

/**
 * The five triage levels, with the words a triage nurse uses.
 *
 * <p>Manchester-style: 1 is immediate and 5 is non-urgent. In code rather than a table because
 * each level carries behaviour — the board's ordering, and the response time a department is
 * measured against — and because a configurable list would let somebody add a sixth level that
 * the sort would place arbitrarily.
 */
export const ACUITIES = [
  { value: "1", label: "1 — Immediate: life-threatening, seen now" },
  { value: "2", label: "2 — Very urgent: within 10 minutes" },
  { value: "3", label: "3 — Urgent: within an hour" },
  { value: "4", label: "4 — Standard: within two hours" },
  { value: "5", label: "5 — Non-urgent: within four hours" },
] as const;

/** How long a level is meant to wait, for the board to flag a breach against. */
export const TARGET_MINUTES: Record<number, number> = { 1: 0, 2: 10, 3: 60, 4: 120, 5: 240 };

export const ADMISSION_SOURCES = [
  { value: "CASUALTY", label: "From casualty" },
  { value: "ELECTIVE", label: "Elective (planned)" },
  { value: "TRANSFER", label: "Transfer from another hospital" },
  { value: "MATERNITY", label: "Maternity" },
] as const;
