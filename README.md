# MedSync

A hospital management platform built as microservices: **Java 21 / Spring Boot 4** services, a
**Python** clinical decision-support service, a **PostgreSQL 16** database with a schema per
service, and **Kafka** for domain events.

A patient can be registered, booked with an AI no-show risk score, seen in an encounter with
SOAP notes and vitals, coded, and have blood work ordered — with results arriving straight off a
haematology analyzer over its own wire protocol and released by a pathologist.

> **Status:** the clinical core, the laboratory (including analyzer integration) and the AI service
> are implemented and verified end to end against a real PostgreSQL. **271 tests pass** (183 Java,
> 88 Python). The web UI and the container/CI tooling are the remaining work — see
> [Roadmap](#roadmap).

---

## Contents

- [Architecture](#architecture)
- [What each service does](#what-each-service-does)
- [Running it](#running-it)
- [Design decisions worth knowing](#design-decisions-worth-knowing)
- [Security](#security)
- [Testing](#testing)
- [Roadmap](#roadmap)
- [Attribution](#attribution)

---

## Architecture

```
                          ┌──────────────────────┐
        browser  ───────► │  web  (Next.js)      │   ← planned
                          └──────────┬───────────┘
                                     │  REST, bearer token
                          ┌──────────▼───────────────────────┐
                          │  gateway            :8080        │  Spring Cloud Gateway
                          │  routing · CORS · correlation id  │
                          └──┬──────┬──────┬──────┬──────┬───┘
             ┌───────────────┘      │      │      │      │
             ▼                      ▼      ▼      ▼      ▼
      identity :8081        patient :8082  scheduling :8083  laboratory :8084   ai :8000
      users · roles         patients       appointments      lab orders         FastAPI
      RS256 + JWKS          staff          encounters        ASTM + K-DPS       4 capabilities
      audit trail           departments    notes · vitals    results            (Claude + models)
             │                      │            │                │                 │
             └──── Kafka: hms.patient · hms.appointment · hms.lab · hms.audit ───────┘
                                     │
                    PostgreSQL 16 — one schema per service
              identity · patient · scheduling · laboratory
```

**Authentication.** `identity-service` is the only holder of passwords and the only issuer of
tokens. It signs RS256 access tokens and publishes its public keys at
`/.well-known/jwks.json`. Every other service — including the Python one — is a stateless OAuth2
resource server that validates tokens against that JWKS **offline**, so no service calls identity
on the request path.

**Events.** Services publish domain events through an `EventPublisher` with two transports,
selected by `hms.events.transport`: `kafka` in a deployed environment, or `log` so the entire
platform runs on **PostgreSQL alone** for local development and CI. Audit events from every
service are consumed by identity into `identity.audit_log`.

**Data ownership.** One PostgreSQL instance, one schema per service, each with its own Flyway
migrations. No service reads another's tables; cross-service references (a patient id on a lab
order) are plain columns, not foreign keys, so a service survives another's outage.

---

## What each service does

### `platform/hms-common`
The shared library: RFC 9457 problem responses, the resource-server security chain and role
vocabulary, correlation-id propagation, a UUID-keyed JPA base entity, the domain-event envelope
and audit fan-out, LIKE-pattern helpers, and AES-256-GCM column encryption for PHI.

### `identity-service` — schema `identity`
Argon2id password hashing, RS256 access tokens (15 min) with a published JWKS, single-use refresh
tokens with rotation and **theft detection** (replaying a rotated token revokes the whole family),
account lockout after repeated failures, role management, and the platform-wide audit trail.

### `gateway`
The single entry point. Routes are declared in Java, so the public URL map is compiled and
reviewable in one place, and **only the documented prefixes are reachable** — a service's actuator
endpoints stay unroutable, so the gateway is not an open proxy.

### `patient-service` — schema `patient`
Patients with MRNs issued from a PostgreSQL sequence, duplicate-registration detection that
answers `409` **with the candidate charts** so the front desk can resolve it, trigram-indexed
search over name/MRN/phone, allergies with a critical-severity flag surfaced on the chart, plus
clinical staff and departments. Charts are **archived, never deleted**.

### `scheduling-service` — schema `scheduling`
Clinician weekly patterns and blackouts, slot generation, appointments with a full status machine,
encounters, SOAP clinical notes, vitals (with derived BMI) and coded diagnoses. Booking asks
`ai-service` for a no-show score through a circuit breaker.

### `laboratory-service` — schema `laboratory`
Test catalog, sex-specific reference ranges, orders, specimens with sequence-issued accession
numbers, results, histograms, and **analyzer integration**: the ASTM E1394 / LIS2-A2 and Sysmex
K-DPS parsers, ported to Java. Entry is separated from release — a technician's value is
provisional and only a pathologist verifies.

### `services/ai-service` — Python, FastAPI
Four clinical decision-support capabilities. Details in
[`services/ai-service/README.md`](services/ai-service/README.md).

| Endpoint | What it does |
| --- | --- |
| `POST /ai/notes/summarize` | SOAP-shaped summary of a clinical note (Claude API), with red flags drawn only from the note |
| `POST /ai/appointments/no-show-risk` | Calibrated probability with the factors behind it |
| `POST /ai/triage` | ESI-style acuity 1–5 from vitals and complaint, with the drivers that set it |
| `POST /ai/icd10/suggest` | Ranked ICD-10 suggestions retrieved from a bundled subset |

Every capability has a deterministic fallback, so the service answers correctly with **no API key
and no network**, and every response carries a `provenance` block saying whether a model or a rule
produced it.

---

## Running it

### What you need

| | Version | Note |
| --- | --- | --- |
| Java | 21+ | |
| Maven | 3.9+ | |
| PostgreSQL | 16 | one instance; the services create their own schemas |
| Python | 3.11+ | with [`uv`](https://docs.astral.sh/uv/) for `ai-service` |

### 1. Database

```bash
createdb hms
psql -d hms -c "CREATE USER hms WITH PASSWORD 'hms' SUPERUSER;"   # dev only; see Security
```

Superuser is needed **on first run only**, to install the `pg_trgm` and `btree_gist` extensions.
Flyway creates and migrates every schema on service start.

### 2. Build and start the Java services

```bash
mvn -q package -DskipTests
scripts/local.sh start          # identity, patient, scheduling, laboratory, gateway
scripts/local.sh status
scripts/local.sh logs identity-service
scripts/local.sh stop
```

`scripts/local.sh` runs the services natively with the `dev` profile: seeded users, and events to
the log rather than Kafka, so no broker is required.

### 3. Start the AI service

```bash
cd services/ai-service
uv sync --extra dev
uv run python -m training.train_noshow      # optional: trains the calibrated no-show model
uv run uvicorn app.main:app --port 8000
```

### 4. Sign in

The dev profile seeds one account per role, all flagged must-change-password:

| Username | Role |
| --- | --- |
| `admin` | ADMIN |
| `dr.rao` | DOCTOR |
| `nurse.iqbal` | NURSE |
| `reception` | RECEPTIONIST |
| `lab.tech` | LAB_TECH |
| `dr.pathan` | PATHOLOGIST |

The seed password comes from `HMS_SEED_PASSWORD` and defaults to `ChangeMe!Dev2026`.

```bash
TOKEN=$(curl -s -X POST localhost:8081/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"reception","password":"ChangeMe!Dev2026"}' | jq -r .accessToken)

curl -s localhost:8080/patients -H "Authorization: Bearer $TOKEN"
```

### Configuration

Every service is environment-driven. The ones you are most likely to set:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HMS_DB_URL` / `HMS_DB_USER` / `HMS_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/hms`, `hms`, `hms` | Database |
| `HMS_EVENTS_TRANSPORT` | `log` | `kafka` in a deployed environment |
| `HMS_KAFKA` | `localhost:9092` | Broker, when the transport is `kafka` |
| `HMS_JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | Where resource servers fetch keys |
| `HMS_JWT_PRIVATE_KEY` / `HMS_JWT_PUBLIC_KEY` | generated on first boot | Signing keys; supply from a secret manager |
| `HMS_PHI_KEY` | **dev key, warns loudly** | Base64 AES-256 key for encrypted patient identifiers |
| `HMS_SEED_ENABLED` | `false` (`true` in `dev`) | Seeds the demo accounts |
| `HMS_AI_ANTHROPIC_API_KEY` | unset | Enables model-backed note summarisation |
| `HMS_TIMEZONE` | `UTC` | Clinic-local zone for slot generation |

---

## Design decisions worth knowing

**Double-booking is prevented by the database, not by a check.** A read-then-insert loses to a
concurrent booking, so `appointments` carries a GiST exclusion constraint over
`(clinician_id, tstzrange(starts_at, ends_at))`, excluding cancelled and no-show rows. Two
receptionists clicking the same slot get one booking and one `409`.

**Identifiers come from sequences.** MRNs and lab accession numbers are drawn from PostgreSQL
sequences rather than `max(...) + 1`, so two simultaneous registrations can never collide. A
rolled-back transaction simply leaves a gap.

**A signed clinical note is never edited.** It is a legal record of what a clinician asserted at
that time. Editing a signed note creates the next revision as an addendum pointing back at what it
amends, and the original stays readable. An encounter cannot be closed while its note is unsigned.

**Lab results are interpreted, not echoed.** Analyzers under-report: an instrument will send a
value with the flag `N` that is plainly outside the range. Results are flagged against the lab's
own sex-specific ranges, so an anaemic patient's haemoglobin is marked low even when the
instrument called it normal. Verified against a real Sysmex XP-300 transmission.

**An unmatched analyzer message is never guessed at.** Every transmission is stored verbatim
*before* parsing, so a decode failure is diagnosable rather than lost. A sample is matched to an
order by accession number, then by MRN among orders awaiting results — and if neither matches, the
message is retained and reported. Filing a result on the wrong patient is worse than filing none.

**Decision support can fail without stopping care.** The no-show score is fetched through a
circuit breaker with a 2-second limit and a fallback that returns nothing. If `ai-service` is slow
or down the appointment is still booked; it simply carries no risk badge. Verified by killing the
service mid-flow.

**Every AI response says how it was produced.** Each carries `model`, `fallback_used` and
`confidence`, so a clinician always knows whether a model or a rule is on screen. Nothing writes
to a patient record on its own.

---

## Security

- **Passwords** — Argon2id, with bcrypt still registered so imported hashes verify and upgrade.
- **Tokens** — RS256 only (`alg: none` and HMAC confusion are rejected), issuer and audience
  verified, `exp`/`iat`/`sub` required, 15-minute lifetime.
- **Refresh tokens** — stored only as SHA-256 hashes, single-use, rotated, with reuse treated as
  theft: the whole rotation family is revoked. Changing or resetting a password ends every session.
- **Brute force** — lockout after 5 failures. Unknown usernames and wrong passwords return
  identical responses, and a decoy hash is verified so response timing does not leak existence.
- **PHI at rest** — national id and insurance policy number are AES-256-GCM encrypted, excluded
  from every patient response, and served only by a separately authorised, individually audited
  endpoint.
- **RBAC** — method-level, per endpoint. A receptionist registers patients but cannot chart; a
  nurse records vitals but cannot sign a note; a lab technician enters results but cannot release
  them.
- **Audit** — every service emits audit events; identity persists them. Writes on deliberately
  failing paths (a rejected login, detected token theft) commit in their own transactions, so the
  trail survives the rollback that follows.
- **Errors** — one problem+json shape everywhere; no stack traces, SQL, or clinical text in a
  response body.

**Before deploying:** supply `HMS_JWT_PRIVATE_KEY` and `HMS_PHI_KEY` from a secret manager (the
built-in PHI dev key protects nothing and logs a warning), give each service its own least-privilege
database role instead of the shared superuser, set `HMS_SEED_ENABLED=false`, and terminate TLS in
front of the gateway. Hardening work still outstanding is listed in the [Roadmap](#roadmap).

---

## Testing

```bash
mvn -q verify                                   # 183 Java tests
cd services/ai-service && uv run pytest -q      # 88 Python tests
cd services/ai-service && uv run ruff check . && uv run bandit -q -r app training
```

Integration tests run against a **real PostgreSQL** (`hms_test` by default, override with
`HMS_TEST_DB_URL`), because the behaviour that matters — an exclusion constraint, a `jsonb` column,
trigram search, transaction boundaries — is not reproducible in an in-memory substitute.

The analyzer parsers are tested against **byte-for-byte frames captured from a Sysmex RX-21/XP-100**
for two different patients, so the decoders are proven against the real wire format rather than a
reconstruction.

---

## Roadmap

Implemented and verified: clinical core, laboratory with analyzer integration, AI service.

Not yet built:

- **Web UI** — Next.js app: dashboard, patient search and chart, appointment calendar, encounter
  charting with AI assistance, triage intake, lab worklist, admin.
- **Containerisation and CI** — `docker-compose.yml` for the full stack, GitHub Actions running the
  Java, Python and web suites.
- **TLS** — gateway TLS termination with HSTS, dev certificate generation, `sslmode=verify-full`
  for the database, and secure cookies.
- **Security testing** — SAST (SpotBugs + FindSecBugs, Semgrep), dependency scanning (OWASP
  Dependency-Check, `pip-audit`), secret scanning (gitleaks), and DAST/VAPT via the OWASP ZAP
  automation framework with an authenticated scan.
- **Automation and performance testing** — REST Assured API journeys, Playwright end-to-end flows,
  and k6 smoke/load/stress/soak profiles.
- **Further clinical modules** — billing, pharmacy and inventory, imaging.

---

## Attribution

The ASTM E1394 / LIS2-A2 and Sysmex K-DPS analyzer parsers in `laboratory-service` are ported to
Java from [`smkazi/HaematologyIS`](https://github.com/smkazi/HaematologyIS), where they were
hardened against real Sysmex output (Poch-100i, XP-100, XP-300, XQ-320, XN-330). The test fixtures
are the captures from that project's own suite. See `NOTICE`.

All data in this repository is synthetic. There is no patient data here.
