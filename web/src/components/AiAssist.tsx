"use client";

import { useState } from "react";
import type { CodingResponse, NoteSummary } from "@/lib/types";
import { AiProvenance, Badge, ErrorNote } from "@/components/ui";

/**
 * The AI panel on the charting screen.
 *
 * Two rules govern it. Nothing it returns is written to the record automatically — the clinician
 * copies what they judge correct. And every result is labelled with what produced it, so model
 * output is never mistaken for recorded fact.
 */
export function AiAssist({ noteText, patientAge }: { noteText: string; patientAge?: number }) {
  const [summary, setSummary] = useState<NoteSummary | null>(null);
  const [coding, setCoding] = useState<CodingResponse | null>(null);
  const [busy, setBusy] = useState<"summary" | "coding" | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function call<T>(path: string, body: unknown, kind: "summary" | "coding"): Promise<T | null> {
    setBusy(kind);
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
      setBusy(null);
    }
  }

  const hasNote = noteText.trim().length >= 10;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={!hasNote || busy !== null}
          onClick={async () => {
            const result = await call<NoteSummary>(
              "/api/ai/summarize",
              { noteText, patientAge },
              "summary",
            );
            if (result) setSummary(result);
          }}
          className="rounded-md border border-accent/40 bg-accent-soft px-3 py-1.5 text-sm font-medium text-accent disabled:opacity-50"
        >
          {busy === "summary" ? "Summarising…" : "Summarise note"}
        </button>
        <button
          type="button"
          disabled={!hasNote || busy !== null}
          onClick={async () => {
            const result = await call<CodingResponse>("/api/ai/icd10", { text: noteText }, "coding");
            if (result) setCoding(result);
          }}
          className="rounded-md border border-accent/40 bg-accent-soft px-3 py-1.5 text-sm font-medium text-accent disabled:opacity-50"
        >
          {busy === "coding" ? "Searching…" : "Suggest ICD-10 codes"}
        </button>
      </div>

      {!hasNote && (
        <p className="text-xs text-ink-muted">
          Write some note content first — these tools read what is on the chart.
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

      {coding && (
        <div className="rounded-md border border-line bg-surface p-3">
          <h3 className="text-sm font-semibold">Suggested codes</h3>
          {coding.suggestions.length === 0 ? (
            <p className="mt-1 text-sm text-ink-muted">Nothing matched this text.</p>
          ) : (
            <ul className="mt-2 space-y-1.5 text-sm">
              {coding.suggestions.map((suggestion) => (
                <li key={suggestion.code} className="flex items-start justify-between gap-3">
                  <div>
                    <span className="numeric font-medium">{suggestion.code}</span>{" "}
                    <span>{suggestion.description}</span>
                    {suggestion.matched_terms.length > 0 && (
                      <div className="text-xs text-ink-muted">
                        matched: {suggestion.matched_terms.join(", ")}
                      </div>
                    )}
                  </div>
                  <span className="numeric text-xs text-ink-muted">
                    {(suggestion.score * 100).toFixed(0)}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <p className="mt-2 text-xs text-ink-muted">
            Record the code you judge correct using the diagnosis form; nothing here is coded
            automatically.
          </p>
          <AiProvenance
            model={coding.provenance.model}
            fallbackUsed={coding.provenance.fallback_used}
            confidence={coding.provenance.confidence}
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
