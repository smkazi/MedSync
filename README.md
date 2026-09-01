# MedSync

A hospital management platform built as microservices: **Java 21 / Spring Boot 4** services, a
**Python** clinical decision-support service, a **PostgreSQL 16** database with a schema per
service, and **Kafka** for domain events.

A patient can be registered, booked with an AI no-show risk score, seen in an encounter with
SOAP notes and vitals, coded, and have blood work ordered — with results arriving straight off a
haematology analyzer over its own wire protocol and released by a pathologist.

> **Status:** the clinical core, the laboratory (including analyzer integration), the AI service
> and the web UI are implemented and verified end to end against a real stack, and so are the
> containerisation, TLS, security-testing and performance-testing layers. **391 tests pass** —
> 199 Java unit and integration, 91 Python, 17 web unit, 72 black-box API and security abuse cases,
> and 12 browser end-to-end, plus four k6 profiles. See [Testing](#testing) and
> [Security](#security); what is left is in the [Roadmap](#roadmap).

---

## Contents

- [Architecture](#architecture)
- [What each service does](#what-each-service-does)
- [Running it](#running-it)
- [Design decisions worth knowing](#design-decisions-worth-knowing)
- [Security](#security)
- [Testing](#testing)
- [Security and performance testing](#security-and-performance-testing)
- [What the test suites found](#what-the-test-suites-found)
- [Roadmap](#roadmap)
- [Attribution](#attribution)

---

## Architecture

```
                          ┌──────────────────────┐
        browser  ───────► │  web  (Next.js 16)   │  :3000
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

## Repository layout

```
.
├── pom.xml                      Maven aggregator; -Pquality / -Psecurity / -Pmutation / -Pautomation
├── Makefile                     every task, one place. `make help`
├── docker-compose.yml           postgres, kafka (KRaft), five services, ai-service, web
├── Dockerfile.java              shared multi-stage build, ARG SERVICE, non-root
├── config/                      SpotBugs exclusions and Dependency-Check suppressions, each with a reason
├── platform/hms-common/         security, errors, events, audit, crypto, pagination
├── services/
│   ├── gateway/                 routing, CORS, rate limiting, security headers, TLS redirect
│   ├── identity-service/        users, roles, JWT/JWKS, refresh rotation, audit sink
│   ├── patient-service/         patients, staff, departments, allergies, PHI encryption
│   ├── scheduling-service/      appointments, encounters, notes, vitals, diagnoses
│   ├── laboratory-service/      orders, results, reference ranges, ASTM + K-DPS parsers
│   └── ai-service/              FastAPI: summarisation, no-show risk, triage, ICD-10
├── web/                         Next.js 16 app, Playwright suite in e2e/
├── tests/
│   ├── api/                     REST Assured journeys and security abuse cases
│   └── perf/                    k6 smoke, load, stress, soak
├── security/
│   ├── zap/                     ZAP Automation Framework plans and the gated runner
│   └── pentest/                 sqlmap, nuclei, testssl.sh
├── scripts/                     local.sh (run natively), gen-certs.sh (dev TLS)
└── .github/workflows/           ci.yml on every push, security.yml nightly
```

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

### `web` — Next.js 16, React 19
The clinical interface: dashboard, patient search and chart, appointment book, encounter charting
with AI assistance beside the note, triage intake and the laboratory worklist. Server components
call the gateway; **the browser never receives an access token** — the session lives in httpOnly
cookies and every platform call is made server-side.

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

### The short way: containers

```bash
cp .env.example .env        # then edit it
docker compose up --build -d
```

That brings up PostgreSQL, Kafka in KRaft mode, all five Java services, the AI service and the web
app. Open http://localhost:3000. `make up` and `make down` are the same thing with less typing;
`make help` lists every target.

One honest caveat: the compose stack is **shipped but not verified here** — the container this was
developed in has no Docker daemon, so that path is validated by review only. Everything below,
running natively, is what has actually been exercised.

### What you need to run it natively

| | Version | Note |
| --- | --- | --- |
| Java | 21+ | |
| Maven | 3.9+ | |
| PostgreSQL | 16 | one instance; the services create their own schemas |
| Python | 3.11+ | with [`uv`](https://docs.astral.sh/uv/) for `ai-service` |
| Node | 22+ | for the web app |

Optional, for the security and performance layers: [k6](https://k6.io),
[OWASP ZAP](https://www.zaproxy.org), `sqlmap`, `nuclei`, `testssl.sh`, `gitleaks`. Every script
that needs one reports it as missing and skips rather than pretending to pass.

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

### 3. Start the web UI

```bash
cd web
npm install
GATEWAY_URL=http://localhost:8080 IDENTITY_URL=http://localhost:8081 npm run dev
```

Then open http://localhost:3000.

### 4. Start the AI service

```bash
cd services/ai-service
uv sync --extra dev
uv run python -m training.train_noshow      # optional: trains the calibrated no-show model
uv run uvicorn app.main:app --port 8000
```

### 5. Sign in

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
| `HMS_RATE_LIMIT_RPM` | `600` | Per-client requests a minute at the gateway |
| `HMS_RATE_LIMIT_AUTH_RPM` | `20` | The same, for `/auth/**` |
| `HMS_RATE_LIMIT_ENABLED` | `true` | Turn off if something in front already limits |

### TLS

```bash
make certs                          # local CA + per-service PKCS#12 keystores, into certs/
SPRING_PROFILES_ACTIVE=tls,dev scripts/local.sh start
```

The `tls` profile serves HTTPS on each service (gateway on 8443), redirects plain HTTP with a 308,
sends HSTS, pins TLS 1.2 and 1.3 with a curated forward-secret AEAD cipher list, and switches the
database to `sslmode=verify-full` and Kafka to `SASL_SSL`. Certificates are generated, never
committed — `certs/` and every key extension are in `.gitignore`.

Plain HTTP stays the default for local work on purpose: a dev CA that k6, curl and the JVM all
have to be taught to trust turns every quick check into a detour.

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
- **Rate limiting** — per client at the gateway, with a far stricter bucket on `/auth/**`. Account
  lockout stops guessing at one account; this stops one password sprayed across a thousand
  usernames, where no single account ever reaches its threshold. Neither substitutes for the other.
- **Security headers** — set at the gateway, the only thing every browser response passes through:
  `nosniff`, `X-Frame-Options: DENY`, `no-referrer`, a locked-down `Permissions-Policy`, COOP/CORP,
  and a `default-src 'none'` CSP for the JSON API. The web app has its own nonce-based policy. The
  runtime and its version are stripped from every response.
- **Errors** — one problem+json shape everywhere; no stack traces, SQL, or clinical text in a
  response body. An unsupported method is a 405 with `Allow`, a wrong content type a 415, a lost
  race on a versioned row a 409 — never a 500 that says "we are broken" when it is not true.
- **Correlation ids** — an inbound `X-Correlation-Id` is honoured so a trace spans services, but
  validated against `[A-Za-z0-9._:-]{1,64}` first. It reaches a response header and every log line
  for the request, so it is two injection sinks at once; a malformed one is replaced rather than
  stripped, because there is no legitimate trace to preserve in a value no real client could send.
- **TLS** — see [TLS](#tls). TLS 1.2 and 1.3 only, forward-secret AEAD ciphers, HSTS, HTTP
  redirected rather than served, `sslmode=verify-full` to the database, `SASL_SSL` to Kafka.

**Before deploying:** supply `HMS_JWT_PRIVATE_KEY` and `HMS_PHI_KEY` from a secret manager (the
built-in PHI dev key protects nothing and logs a warning), give each service its own least-privilege
database role instead of the shared superuser, set `HMS_SEED_ENABLED=false`, generate real
certificates rather than `make certs` output, and put Redis behind the rate limiter if you run more
than one gateway (the counters are in-process, so N gateways enforce N times the limit — a
deliberate trade for the single-gateway deployment here, not an oversight).

---

## Testing

```bash
make test          # Java + Python
make test-web      # lint and typecheck
make test-e2e      # Playwright, needs the stack running
make test-api      # REST Assured journeys and abuse cases, needs the stack running
make help          # everything else
```

Or directly:

```bash
mvn -q verify                                     # 199 Java unit and integration tests
cd services/ai-service && uv run pytest -q        # 91 Python tests
cd web && npm run lint && npm run typecheck       # web static checks
cd web && npm test                                # 17 web unit tests
cd web && npx playwright test                     # 12 browser tests
mvn -Pautomation -pl tests/api verify             # 72 API and security abuse cases
```

| Layer | What runs | Where |
| --- | --- | --- |
| Unit | JUnit 5 + AssertJ, pytest, Vitest | every module, no I/O |
| Edge | Gateway rate limiting, security headers, correlation-id validation | `services/gateway` |
| Integration | `@SpringBootTest` against a real PostgreSQL 16, Flyway-migrated; embedded Kafka for event paths | per service |
| API / contract | REST Assured, black box through the gateway | `tests/api` |
| Security abuse cases | REST Assured: JWT forgery, refresh replay, role escalation, IDOR, injection | `tests/api/.../security` |
| Browser | Playwright against the running stack | `web/e2e` |
| Performance | k6 smoke, load, stress, soak | `tests/perf` |
| Mutation | PIT over domain, service and device packages | `mvn -Pmutation test` |

Integration tests run against a **real PostgreSQL** (`hms_test` by default, override with
`HMS_TEST_DB_URL`), because the behaviour that matters — an exclusion constraint, a `jsonb` column,
trigram search, transaction boundaries — is not reproducible in an in-memory substitute.

The analyzer parsers are tested against **byte-for-byte frames captured from a Sysmex RX-21/XP-100**
for two different patients, so the decoders are proven against the real wire format rather than a
reconstruction.

The `tests/api` suites are **black box**: no Spring context, no repositories, no test slices — just
HTTP against a deployed gateway, as a client sees it. That is the point. An integration test inside
a service passes happily while the gateway's routing, the resource server's JWKS validation, or a
cross-service contract is broken, because none of those are in the picture.

The Playwright suite drives a **real browser against the running stack** — no mocks — and asserts
the properties that only appear when it is wired together: that no access token reaches
`document.cookie`, that a critical allergy is announced as an alert, that encrypted identifiers
never render on a chart, and that AI output always carries its advisory framing.

**Two suites need the rate limiter raised**, because they deliberately make more sign-in attempts
per minute than any human would. `make dev-test-stack` starts the stack that way, and both suites
detect a 429 and say exactly what to change rather than reporting a mysterious failure rate. The
limiter itself is covered by `EdgeFilterTest` in the gateway module, so raising it costs no coverage.

---

## Security and performance testing

```bash
make security      # SAST + dependency scanning + secret scanning
make vapt          # OWASP ZAP: unauthenticated baseline, then an authenticated full scan
make pentest       # sqlmap, nuclei, testssl.sh against a running stack
make perf-smoke    # k6: one pass of everything, one VU
make perf-load     # a normal clinic day: 10:1 reads to bookings
make perf-stress   # ramp past peak until something gives, then check it recovers
make perf-soak     # modest load held for an hour (PERF_SOAK_DURATION=8h for overnight)
```

| Class | Tool | Wiring |
| --- | --- | --- |
| SAST (Java) | SpotBugs + FindSecBugs | `mvn -Pquality verify`, gates on Medium and above |
| SAST (Python) | Bandit + Ruff `S` rules | `uv run bandit`, `uv run ruff check` |
| SAST (multi-language) | Semgrep — OWASP Top Ten, JWT, secrets, Dockerfile | nightly workflow, SARIF |
| Dependencies | OWASP Dependency-Check (CVSS ≥ 7 fails), `pip-audit`, `npm audit` | `mvn -Psecurity verify` |
| Secrets | gitleaks over full history | CI, every push |
| Containers / IaC | Trivy on a built image and the compose file | nightly workflow, SARIF |
| DAST / VAPT | OWASP ZAP Automation Framework, two plans, risk-threshold gate | `security/zap/`, `make vapt` |
| Targeted pen-test | sqlmap on the free-text endpoints, nuclei on the gateway, testssl.sh on 8443 | `security/pentest/run.sh` |

Notes that matter more than the table:

- **`/auth/**` is excluded from the authenticated ZAP scan on purpose.** Identity locks an account
  after five failures, so letting the injection rules hammer the login body would lock the scan
  user and turn every request after that into a false 401. Login is attacked by the unauthenticated
  baseline and by the abuse-case suite, which *asserts* the lockout rather than tripping over it.
- **Every ZAP requestor step is an assertion.** `/patients` must be 401 anonymously; `/admin/users`
  must be 403 as a doctor; `/actuator/env` and `/actuator/heapdump` must be 404 through the gateway.
  A 200 there fails the scan before any active rule runs.
- **A skipped check is not a passed check.** `make pentest` reports each missing tool by name and
  exits telling you so.
- **The performance profiles isolate each run on its own clinician.** Booking is guarded by an
  exclusion constraint over `(clinician_id, tstzrange)`, and each iteration derives its slot from
  `(VU, iteration)`, so a conflict can only mean the constraint fired on something the test did not
  cause. `booking_conflicts` is therefore a hard threshold rather than a reported number — which is
  exactly how a bad slot allocation got caught (see below).
- **`make perf-smoke` first.** If the smoke profile is red, the numbers from the others mean nothing.

The load profile's last clean run on the development container — 20 reading VUs, 4 bookings a
second, seven minutes — for whatever a shared container's numbers are worth as a baseline:

| Endpoint | p95 |
| --- | --- |
| Patient search (trigram) | 17 ms |
| Patient read by id | 15 ms |
| Appointment search | 16 ms |
| Clinician availability | 14 ms |
| Lab worklist | 22 ms |
| Sign-in (Argon2id) | 112 ms |
| Booking (constraint check, event publish, AI call) | 239 ms |

23,558 requests, **0 failures**, 35,317 of 35,317 checks passed, 0 booking conflicts, and 1,469
no-show scores returned — the last of which matters because a score that vanished under load would
mean the circuit breaker had opened.

CI runs the fast set on every push; the slow set — Dependency-Check's NVD feed, PIT, both ZAP plans
— runs nightly and uploads SARIF, so a new finding appears as a code-scanning alert rather than a
line in a log nobody opens.

---

## What the test suites found

Worth writing down, because it is the argument for having built them:

- **Concurrent sign-ins for one account returned 500.** The login path stamped `last_login_at` by
  mutating the optimistically locked `users` row, so two simultaneous logins collided on the version
  column and one failed its commit — a shared front-desk account on two workstations, intermittently.
  Found by the k6 soak profile at 8 VUs: 55 failures in 743 requests. The failure counter had the
  same fault plus a quieter one — parallel guesses both read the same count and both wrote count+1,
  so a burst could be counted once, which is the exact burst a lockout threshold exists to stop.
  Both are now single SQL statements. Five concurrency tests cover it.
- **A lab technician could read a patient's full encounter record**, including signed SOAP notes.
  Found by the authorization abuse suite. Minimum necessary access is the rule for PHI: a blood
  count does not need the history, assessment and plan. Encounter reads are now narrower than
  patient lookup.
- **The gateway sent no security headers at all** — `X-Content-Type-Options` was simply absent on
  every API response. The web app had its own; anything a browser fetched straight from the gateway
  had none.
- **The note summariser reported "chest pain" as a red flag on a note reading "No chest pain".**
  Triage already handled negation; the summariser did not. A red-flag field that cries wolf is one
  clinicians stop reading, which costs the cases it exists to catch.
- **Filtering users by role returned every role-less account too.** The left join leaves `r.code`
  null for a user with no roles, and the predicate admitted nulls — so asking for pathologists
  matched every account that had no role at all.
- **Case conversion used the default locale in twelve identifier-normalisation sites.** On a JVM
  started with `tr_TR`, Turkish lower-casing of "I" means a patient named IQBAL stops matching a
  search for "iqbal". Found by SpotBugs.
- **A client-supplied correlation id was echoed into a response header and every log line,
  unvalidated** — response splitting on one side, forged audit entries on the other.
- **Parsed analyzer frames were not actually immutable.** `Histogram`, `KdpsSample` and
  `AstmRecord.Sample` handed out the live lists they were built with. This is measured patient data
  that is persisted as JSONB and used to derive MPV, PDW and RDW.
- **The k6 slot allocation itself was wrong**, and its own hard threshold caught it: 1,229
  self-inflicted booking conflicts in one 20-VU run, because `(VU % 30, ITER % 12)` stops being
  injective the moment k6 hands out VU ids above 30.

---

## Roadmap

**Implemented and verified against a running stack:** clinical core, laboratory with analyzer
integration, AI service, web UI, containerisation, TLS, the full test pyramid, SAST/SCA/DAST
tooling, and performance profiles.

**Shipped but not verified here:** `docker compose up`. The container this was developed in has no
Docker daemon, so that path is validated by review only.

**Not built:**

- **Further clinical modules** — billing and claims, pharmacy and inventory, imaging/PACS.
- **Analyzer transport** — the RS-232/TCP device gateway. The ported parsers are
  transport-agnostic, exactly as they are in the source project, and `POST /lab/device-messages`
  is the seam a device gateway plugs into.
- **Distributed rate limiting** — Redis-backed, for more than one gateway instance. See
  [Security](#security) for what the in-process limiter does and does not promise.
- **Per-service database roles** — the schema-per-service split is there; the least-privilege
  runtime and migration roles that should own each schema are not, and one superuser is still doing
  both jobs in dev.
- **A real reference-range catalogue** — the seeded set covers the CBC parameters the ASTM parser
  emits. A deployment needs its own, per instrument and per population.
- **HL7 v2 / FHIR interfaces** — nothing here speaks either yet.

Two things about the AI layer are worth stating plainly rather than leaving in a roadmap: the
no-show model is trained on **synthetic data** (ROC AUC 0.67, Brier 0.10 on a held-out split — a
useful nudge for a reminder call, not a clinical instrument), and every AI response carries its
model, a confidence, and an advisory disclaimer, because nothing in this system writes to a patient
record on the strength of a model's opinion.

---

## Attribution

The ASTM E1394 / LIS2-A2 and Sysmex K-DPS analyzer parsers in `laboratory-service` are ported to
Java from [`smkazi/HaematologyIS`](https://github.com/smkazi/HaematologyIS), where they were
hardened against real Sysmex output (Poch-100i, XP-100, XP-300, XQ-320, XN-330). The test fixtures
are the captures from that project's own suite. See `NOTICE`.

All data in this repository is synthetic. There is no patient data here.
