"use client";

import { useState } from "react";
import type { TriageResponse } from "@/lib/types";
import { AiProvenance, Badge, ErrorNote } from "@/components/ui";

/** Acuity 1 is the sickest, so the colour scale runs the other way from a usual score. */
const acuityTone = (acuity: number) =>
  acuity <= 2 ? ("critical" as const) : acuity === 3 ? ("warn" as const) : ("good" as const);

export function TriageForm() {
  const [result, setResult] = useState<TriageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const form = new FormData(event.currentTarget);

    const numberOrNull = (name: string) => {
      const raw = form.get(name);
      if (raw === null || String(raw).trim() === "") return null;
      return Number(raw);
    };

    const body = {
      presenting_complaint: String(form.get("complaint") ?? ""),
      patient_age: Number(form.get("age") ?? 0),
      patient_sex: String(form.get("sex") ?? "") || null,
      vitals: {
        heart_rate: numberOrNull("heartRate"),
        systolic_bp: numberOrNull("systolicBp"),
        diastolic_bp: numberOrNull("diastolicBp"),
        respiratory_rate: numberOrNull("respiratoryRate"),
        temperature_c: numberOrNull("temperature"),
        oxygen_saturation: numberOrNull("spo2"),
        pain_score: numberOrNull("pain"),
        consciousness: String(form.get("consciousness") ?? "") || null,
      },
    };

    try {
      const response = await fetch("/api/ai/triage", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const payload = await response.json();
      if (!response.ok) {
        setError(payload.detail ?? "Assessment failed");
      } else {
        setResult(payload as TriageResponse);
      }
    } catch {
      setError("Could not reach the triage service");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <form onSubmit={onSubmit} className="space-y-4">
        <div>
          <label htmlFor="complaint" className="block text-sm font-medium">
            Presenting complaint
          </label>
          <textarea
            id="complaint"
            name="complaint"
            required
            rows={3}
            placeholder="Central chest pain radiating to left arm, sweating"
            className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
          />
          <p className="mt-1 text-xs text-ink-muted">
            Negated findings are understood — &ldquo;no fever&rdquo; will not raise the acuity.
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <Input label="Age" name="age" type="number" required min={0} max={130} />
          <div>
            <label htmlFor="sex" className="block text-sm font-medium">
              Sex
            </label>
            <select
              id="sex"
              name="sex"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            >
              <option value="">Not recorded</option>
              <option value="FEMALE">Female</option>
              <option value="MALE">Male</option>
              <option value="OTHER">Other</option>
            </select>
          </div>
        </div>

        <fieldset className="grid grid-cols-2 gap-3">
          <legend className="mb-1 text-sm font-medium">Observations</legend>
          <Input label="Heart rate" name="heartRate" type="number" min={0} max={300} />
          <Input label="Resp. rate" name="respiratoryRate" type="number" min={0} max={90} />
          <Input label="Systolic BP" name="systolicBp" type="number" min={0} max={300} />
          <Input label="Diastolic BP" name="diastolicBp" type="number" min={0} max={200} />
          <Input label="Temp (°C)" name="temperature" type="number" step="0.1" min={20} max={45} />
          <Input label="SpO2 (%)" name="spo2" type="number" min={0} max={100} />
          <Input label="Pain (0-10)" name="pain" type="number" min={0} max={10} />
          <div>
            <label htmlFor="consciousness" className="block text-sm font-medium">
              AVPU
            </label>
            <select
              id="consciousness"
              name="consciousness"
              className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
            >
              <option value="">Not recorded</option>
              <option value="ALERT">Alert</option>
              <option value="VOICE">Voice</option>
              <option value="PAIN">Pain</option>
              <option value="UNRESPONSIVE">Unresponsive</option>
            </select>
          </div>
        </fieldset>

        <button
          type="submit"
          disabled={busy}
          className="rounded-md bg-accent px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Assessing…" : "Assess acuity"}
        </button>
      </form>

      <div>
        {error && <ErrorNote>{error}</ErrorNote>}
        {result && (
          <div className="rounded-lg border border-line bg-surface p-4">
            <div className="flex items-baseline gap-3">
              <span className="numeric text-4xl font-bold">{result.acuity}</span>
              <div>
                <Badge tone={acuityTone(result.acuity)}>{result.acuity_label}</Badge>
                <div className="mt-1 text-sm text-ink-muted">
                  See within {result.target_assessment_minutes} minutes
                </div>
              </div>
            </div>

            <p className="mt-3 text-sm font-medium">{result.recommended_disposition}</p>

            <div className="mt-3">
              <div className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
                What set this acuity
              </div>
              <ul className="mt-1 list-inside list-disc text-sm">
                {result.drivers.map((driver) => (
                  <li key={driver}>{driver}</li>
                ))}
              </ul>
            </div>

            {result.red_flags.length > 0 && (
              <div className="mt-3 flex flex-wrap items-center gap-1">
                <span className="text-xs font-semibold uppercase tracking-wide text-ink-muted">
                  Red flags
                </span>
                {result.red_flags.map((flag) => (
                  <Badge key={flag} tone="critical">
                    {flag}
                  </Badge>
                ))}
              </div>
            )}

            <AiProvenance
              model={result.provenance.model}
              fallbackUsed={result.provenance.fallback_used}
              confidence={result.provenance.confidence}
            />
          </div>
        )}
      </div>
    </div>
  );
}

function Input({
  label,
  name,
  type = "text",
  ...rest
}: {
  label: string;
  name: string;
  type?: string;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div>
      <label htmlFor={name} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        {...rest}
        className="mt-1 w-full rounded-md border border-line bg-surface-raised px-3 py-2 text-sm"
      />
    </div>
  );
}
