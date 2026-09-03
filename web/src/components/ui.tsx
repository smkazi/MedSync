import Link from "next/link";
import { useId, type ReactNode } from "react";
import { DISPLAY_ZONE } from "@/lib/zone";

/** The small set of primitives every screen is built from. */

export function Card({
  title,
  action,
  children,
  tone = "default",
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
  tone?: "default" | "critical";
}) {
  const border = tone === "critical" ? "border-critical/40" : "border-line";
  // A titled card is a landmark: `aria-labelledby` is what turns the <section> into a named region
  // rather than a generic box. Screens here render a dozen of them, several containing a form with
  // the same field labels, and without a name on the container neither a screen reader user nor a
  // test can say which "Name" field they mean.
  const headingId = useId();
  return (
    <section
      className={`rounded-lg border ${border} bg-surface-raised shadow-sm`}
      aria-labelledby={title ? headingId : undefined}
    >
      {title && (
        <header className="flex items-center justify-between gap-3 border-b border-line px-4 py-3">
          <h2 id={headingId} className="text-sm font-semibold tracking-tight">
            {title}
          </h2>
          {action}
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  );
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface-raised p-4">
      <div className="text-xs font-medium uppercase tracking-wide text-ink-muted">{label}</div>
      <div className="numeric mt-1 text-2xl font-semibold">{value}</div>
      {hint && <div className="mt-1 text-xs text-ink-muted">{hint}</div>}
    </div>
  );
}

export type BadgeTone = "neutral" | "accent" | "good" | "warn" | "critical";

const badgeTones: Record<BadgeTone, string> = {
  neutral: "bg-surface text-ink-muted border-line",
  accent: "bg-accent-soft text-accent border-accent/30",
  good: "bg-good-soft text-good border-good/30",
  warn: "bg-warn-soft text-warn border-warn/30",
  critical: "bg-critical-soft text-critical border-critical/40",
};

export function Badge({ children, tone = "neutral" }: { children: ReactNode; tone?: BadgeTone }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${badgeTones[tone]}`}
    >
      {children}
    </span>
  );
}

/** Maps a workflow status onto a colour, in one place so the whole UI agrees. */
export function statusTone(status: string): BadgeTone {
  switch (status) {
    case "COMPLETED":
    case "VERIFIED":
    case "CLOSED":
    // Money: paid in full and settled in full are the same kind of fact as a closed encounter.
    case "PAID":
    case "SETTLED":
      return "good";
    case "CANCELLED":
    case "NO_SHOW":
    case "DENIED":
      return "critical";
    // Settled short is not a failure and is not finished either: somebody has to decide whether
    // the balance goes to the patient or is written off.
    case "PARTIALLY_SETTLED":
      return "warn";
    case "IN_PROGRESS":
    case "CHECKED_IN":
    case "RESULTED":
    case "ISSUED":
    case "SUBMITTED":
      return "accent";
    case "STAT":
      return "critical";
    case "URGENT":
      return "warn";
    default:
      return "neutral";
  }
}

export function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead>
          <tr className="border-b border-line">
            {head.map((column) => (
              <th
                key={column}
                className="whitespace-nowrap px-3 py-2 text-xs font-semibold uppercase tracking-wide text-ink-muted"
              >
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">{children}</tbody>
      </table>
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-ink-muted">{children}</p>;
}

export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="rounded-md border border-critical/40 bg-critical-soft px-3 py-2 text-sm text-critical"
    >
      {children}
    </div>
  );
}

export function ButtonLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="inline-flex items-center rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
    >
      {children}
    </Link>
  );
}

/**
 * The date and time parts of an instant, in `DISPLAY_ZONE` — the deployment's zone, not the
 * browser's and not UTC. `@/lib/zone` carries the argument for that and the conversion back from a
 * typed wall-clock time; the short version is that the platform decides on the server what day
 * something happened on, so rendering UTC puts a date on the screen that cannot be typed into its
 * own date box.
 *
 * <p>Assembled from `formatToParts` rather than by slicing a formatted string, so no locale can
 * reorder the fields on us — the whole point of these helpers is that a clinical screen shows an
 * unambiguous date. Hour 24 is normalised to 00, which is how `hour12: false` reports midnight.
 */
function partsIn(iso: string): { date: string; time: string } {
  const found = new Map(new Intl.DateTimeFormat("en-GB", {
    timeZone: DISPLAY_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(iso)).map((part) => [part.type, part.value]));
  const hour = found.get("hour") === "24" ? "00" : found.get("hour");
  return {
    date: `${found.get("year")}-${found.get("month")}-${found.get("day")}`,
    time: `${hour}:${found.get("minute")}`,
  };
}

/** Formats an instant for a clinical screen: unambiguous, no locale surprises. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const { date, time } = partsIn(iso);
  return `${date} ${time}`;
}

export function formatTime(iso: string): string {
  return partsIn(iso).time;
}

/** The date alone, in the display zone — which is what a date filter beside it expects. */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return partsIn(iso).date;
}

/**
 * The disclaimer shown wherever AI output appears. It is not decoration: a clinician has to be
 * able to tell model output from recorded fact.
 */
export function AiProvenance({
  model,
  fallbackUsed,
  confidence,
}: {
  model: string;
  fallbackUsed: boolean;
  confidence: number;
}) {
  return (
    <p className="mt-3 border-t border-line pt-2 text-xs text-ink-muted">
      <Badge tone={fallbackUsed ? "warn" : "accent"}>
        {fallbackUsed ? "rule-based fallback" : model}
      </Badge>{" "}
      confidence {(confidence * 100).toFixed(0)}%. Advisory only — review before it informs care.
    </p>
  );
}
