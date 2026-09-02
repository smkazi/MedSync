"use client";

import { useState } from "react";
import type { NoteSummary } from "@/lib/types";
import { AiProvenance, Badge, ErrorNote } from "@/components/ui";

/**
 * The AI panel on the charting screen.
 *
 * Two rules govern it. Nothing it returns is written to the record automatically — the clinician
 * copies what they judge correct. And every result is labelled with what produced it, so model
 * output is never mistaken for recorded fact.
 *
 * <p>Summarising only. ICD-10 suggestion used to live here too, which put two of them on the
 * charting screen: this one, whose codes could only be read, and the one beside the diagnosis
 * field, whose codes can be picked into it. A suggestion you cannot act on is the worse of the
 * two, so it went, and {@code DiagnosisForm} can seed its lookup from the note text instead.
 */
export function AiAssist({ noteText, patientAge }: { noteText: string; patientAge?: number }) {
  const [summary, setSummary] = useState<NoteSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function call<T>(path: string, body: unknown): Promise<T | null> {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const payload = await response.json();
      if (!response.ok) {
        setError(payload.detail ?? "Request failed");
        return null;
      }
      return payload as T;
    } catch {
      setError("Could not reach the decision-support service");
      return null;
    } finally {
      setBusy(false);
    }
  }

  const hasNote = noteText.trim().length >= 10;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={!hasNote || busy}
          onClick={async () => {
            const result = await call<NoteSummary>("/api/ai/summarize", { noteText, patientAge });
            if (result) setSummary(result);
          }}
          className="rounded-md border border-accent/40 bg-accent-soft px-3 py-1.5 text-sm font-medium text-accent disabled:opacity-50"
        >
          {busy ? "Summarising…" : "Summarise note"}
        </button>
      </div>

      {!hasNote && (
        <p className="text-xs text-ink-muted">
          Write some note content first — this reads what is on the chart.
        </p>
      )}

      {error && <ErrorNote>{error}</ErrorNote>}

      {summary && (
        <div className="rounded-md border border-line bg-surface p-3">
          <h3 className="text-sm font-semibold">Suggested summary</h3>
          <p className="mt-1 text-sm">{summary.result.summary}</p>

          {summary.result.red_flags.length > 0 && (
            <div className="mt-2 flex flex-wrap items-center gap-1">
              <span className="text-xs font-medium text-ink-muted">Flagged in the note:</span>
              {summary.result.red_flags.map((flag) => (
                <Badge key={flag} tone="critical">
                  {flag}
                </Badge>
              ))}
            </div>
          )}

          {summary.result.assessment && (
            <Section label="Assessment">{summary.result.assessment}</Section>
          )}
          {summary.result.plan.length > 0 && (
            <Section label="Plan">
              <ul className="list-inside list-disc">
                {summary.result.plan.map((step) => (
                  <li key={step}>{step}</li>
                ))}
              </ul>
            </Section>
          )}
          {summary.result.follow_up && (
            <Section label="Follow up">{summary.result.follow_up}</Section>
          )}

          <AiProvenance
            model={summary.provenance.model}
            fallbackUsed={summary.provenance.fallback_used}
            confidence={summary.provenance.confidence}
          />
        </div>
      )}

    </div>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mt-2">
      <div className="text-xs font-medium uppercase tracking-wide text-ink-muted">{label}</div>
      <div className="text-sm">{children}</div>
    </div>
  );
}
