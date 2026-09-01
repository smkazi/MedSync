# ai-service

Clinical decision support for MedSync. Four capabilities behind one rule: **nothing here writes to
a patient record, and every response states how it was produced.** A clinician accepts a
suggestion; the platform never acts on one by itself.

| Endpoint | What it does |
| --- | --- |
| `POST /ai/notes/summarize` | Summarises a clinical note into SOAP-shaped sections, with red flags drawn only from the note |
| `POST /ai/appointments/no-show-risk` | Calibrated probability a patient does not attend, with the factors behind it |
| `POST /ai/triage` | ESI-style acuity 1-5 from vitals and presenting complaint, with the drivers that set it |
| `POST /ai/icd10/suggest` | Ranked ICD-10 suggestions retrieved from a bundled subset |

## It runs with no secrets

Every capability has a deterministic fallback, so the service answers correctly with **no API key
and no network**:

- Summarisation falls back to extractive summarising, which copies from the note and never
  generates. A shorter, duller summary is the right trade when no model is available; fabricating
  clinical content is not.
- No-show risk falls back to the logistic form its training data was generated from.
- Triage is rules-first by design and needs no model at all.
- ICD-10 retrieval runs entirely on the bundled subset.

The `provenance` block on every response reports `model`, `fallback_used` and `confidence`, so a
clinician reading the screen knows whether a model or a rule produced it.

## Running

```bash
uv sync --extra dev
uv run python -m training.train_noshow      # optional: trains the calibrated model
uv run uvicorn app.main:app --port 8000
```

Configuration is environment-driven with the `HMS_AI_` prefix:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HMS_AI_ANTHROPIC_API_KEY` | unset | Enables model-backed summarisation |
| `HMS_AI_SUMMARY_MODEL` | `claude-opus-5` | Model used for summarisation |
| `HMS_AI_SUMMARY_EFFORT` | `medium` | Below the default: a clinician is waiting |
| `HMS_AI_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | identity-service's published keys |
| `HMS_AI_JWT_ISSUER` / `HMS_AI_JWT_AUDIENCE` | `http://localhost:8081` / `hms` | Token validation |
| `HMS_AI_MODEL_DIR` | `models` | Where the trained artifact lives |

## Authentication

This service is a resource server like every Java service: it validates the same RS256 access
tokens against identity-service's JWKS, accepts **RS256 only**, and requires `exp`, `iat` and
`sub`. It never sees a password and never issues a token. Per-endpoint roles are enforced with
`require_roles`.

## The no-show model is trained on synthetic data, deliberately

`training/generate_noshow_data.py` generates the history the model learns from. A model trained on
real attendance data learns the access barriers of the population it came from — shipping that in a
repository would leak patient behaviour and hard-code one hospital's inequities into everyone
else's scheduling. The generative process encodes relationships documented in the scheduling
literature (lead time, prior non-attendance, first visit, distance, reminder contact), so the
model demonstrates the mechanism honestly without claiming to be a validated instrument.

The score is a scheduling aid. Its recommended actions are all about making attendance easier —
reminders, confirmation calls, overbooking a slot. It must never be used to deny or deprioritise
care.

## The ICD-10 subset is a subset

`data/icd10_subset.json` is a curated set of common presentations authored for this repository —
89 codes with 333 clinical synonyms. It is not the complete classification. Replace it with your
licensed release before using this for billing-grade coding.
