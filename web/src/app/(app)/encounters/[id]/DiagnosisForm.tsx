"use client";

import { useState } from "react";
import type { CodingResponse } from "@/lib/types";
import { AiProvenance, Badge, ErrorNote } from "@/components/ui";
import { addDiagnosis } from "./actions";

/**
 * Recording a diagnosis, with ICD-10 suggestions beside the field.
 *
 * <p>The rule the AI panel already follows applies here too, and more sharply because this form
 * writes to the record: a suggestion never fills the field on its own. Picking one is a click the
 * clinician makes, the code and text land in inputs they can still edit, and the provenance stays
 * on screen so a model's guess is never mistaken for a recorded fact.
 *
 * <p>This is the only ICD-10 lookup on the screen. It takes {@code noteText} so it can be seeded
 * from the chart in one click, which is what the decision-support panel's own coding button used
 * to do — except that one's codes could only be read, and these can be picked into the field.
 */
export function DiagnosisForm({
  encounterId,
  noteText,
}: {
  encounterId: string;
  noteText: string;
}) {
  const [text, setText] = useState("");
  const [coding, setCoding] = useState<CodingResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // One controlled object initialised to empty strings, never null. Starting it as null and using
  // `value={chosen?.code ?? undefined}` would flip each input from uncontrolled to controlled the
  // moment a suggestion was picked, which React warns about and which loses the field's state.
  const [chosen, setChosen] = useState({ code: "", description: "" });

  async function suggest() {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/ai/icd10", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text }),
      });
      const payload = await response.json();
      if (!response.ok) {
        setError(payload.detail ?? "Could not fetch suggestions");
        return;
      }
      setCoding(payload as CodingResponse);
    } catch {
      setError("Could not reach the decision-support service");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <form action={addDiagnosis} className="space-y-3">
        <input type="hidden" name="encounterId" value={encounterId} />
        <div className="grid gap-3 sm:grid-cols-[9rem_1fr_10rem]">
          <div>
            <label htmlFor="icd10Code" className="block text-sm font-medium">
              ICD-10<span className="ml-0.5 text-accent">*</span>
            </label>
            <input
              id="icd10Code"
              name="icd10Code"
              required
              value={chosen.code}
              onChange={(event) => setChosen({ ...chosen, code: event.target.value })}
              placeholder="I21.9"
              className="numeric mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="description" className="block text-sm font-medium">
              Description<span className="ml-0.5 text-accent">*</span>
            </label>
            <input
              id="description"
              name="description"
              required
              value={chosen.description}
              onChange={(event) => setChosen({ ...chosen, description: event.target.value })}
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="category" className="block text-sm font-medium">
              Category
            </label>
            <select
              id="category"
              name="category"
              defaultValue="SECONDARY"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            >
              <option value="PRIMARY">Primary</option>
              <option value="SECONDARY">Secondary</option>
              <option value="COMPLICATION">Complication</option>
              <option value="COMORBIDITY">Comorbidity</option>
            </select>
          </div>
        </div>
        <button
          type="submit"
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90"
        >
          Add diagnosis
        </button>
      </form>

      <div className="rounded-md border border-line bg-surface p-3">
        <label htmlFor="codingText" className="block text-sm font-medium">
          Suggest a code
        </label>
        <p className="mt-0.5 text-xs text-ink-muted">
          Describe the diagnosis in words, or search the note itself. Nothing here is written to the
          record until you pick a suggestion and press Add.
        </p>
        <div className="mt-2 flex gap-2">
          <input
            id="codingText"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="acute myocardial infarction"
            className="w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
          <button
            type="button"
            onClick={suggest}
            disabled={busy || text.trim().length < 3}
            className="shrink-0 rounded-md border border-line px-3 py-2 text-sm hover:bg-surface-raised disabled:opacity-50"
          >
            {busy ? "Asking…" : "Suggest"}
          </button>
        </div>

        {noteText.trim().length >= 10 && (
          <button
            type="button"
            onClick={() => setText(noteText)}
            className="mt-2 text-xs text-accent hover:underline"
          >
            Use the note text
          </button>
        )}

        {error && (
          <div className="mt-2">
            <ErrorNote>{error}</ErrorNote>
          </div>
        )}

        {coding && (
          <div className="mt-3 space-y-2">
            {coding.suggestions.length === 0 ? (
              <p className="text-sm text-ink-muted">No codes matched that description.</p>
            ) : (
              <ul className="space-y-1">
                {coding.suggestions.map((suggestion) => (
                  <li key={suggestion.code}>
                    <button
                      type="button"
                      onClick={() =>
                        setChosen({ code: suggestion.code, description: suggestion.description })
                      }
                      className="flex w-full items-baseline gap-2 rounded border border-line bg-surface-raised px-2 py-1.5 text-left text-sm hover:bg-surface"
                    >
                      <span className="numeric font-medium">{suggestion.code}</span>
                      <span className="grow">{suggestion.description}</span>
                      {/* The retrieval score, not a probability - labelled as such so it is not
                          read as "97% sure this is the right code". */}
                      <Badge tone="neutral">score {suggestion.score.toFixed(2)}</Badge>
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <AiProvenance
              model={coding.provenance.model}
              fallbackUsed={coding.provenance.fallback_used}
              confidence={coding.provenance.confidence}
            />
          </div>
        )}
      </div>
    </div>
  );
}
