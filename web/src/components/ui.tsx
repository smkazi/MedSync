import Link from "next/link";
import { useId, type ReactNode } from "react";

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

type BadgeTone = "neutral" | "accent" | "good" | "warn" | "critical";

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
      return "good";
    case "CANCELLED":
    case "NO_SHOW":
      return "critical";
    case "IN_PROGRESS":
    case "CHECKED_IN":
    case "RESULTED":
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

/** Formats an instant for a clinical screen: unambiguous, no locale surprises. */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  return `${date.toISOString().slice(0, 10)} ${date.toISOString().slice(11, 16)}`;
}

export function formatTime(iso: string): string {
  return new Date(iso).toISOString().slice(11, 16);
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
