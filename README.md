# MedSync

A hospital management platform built as microservices: **Java 21 / Spring Boot 4** services, a
**Python** clinical decision-support service, a **PostgreSQL 16** database with a schema per
service, and **Kafka** for domain events.

A patient can be registered, booked with an AI no-show risk score, seen in an encounter with
SOAP notes and vitals, coded, and have blood work ordered — with results arriving straight off a
haematology analyzer over its own wire protocol and released by a pathologist.

> **Status:** the clinical core, the laboratory (including analyzer integration), the AI service
> and the web UI are implemented and verified end to end against a real stack, and so are the
> containerisation, TLS, security-testing and performance-testing layers. **677 tests pass** —
> 354 Java unit and integration, 91 Python, 40 web unit, 115 black-box API and security abuse cases,
> and 77 browser end-to-end, plus four k6 profiles. See [Testing](#testing) and
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
      identity :8081   patient :8082  scheduling :8083  laboratory :8084  notification :8085  ai :8000
      users · roles    patients       appointments      lab orders        delivery log        FastAPI
      RS256 + JWKS     staff          encounters        ASTM + K-DPS      SMTP · HTTP SMS     4 capabilities
      audit trail      departments    notes · vitals    results           no PHI outbound     (Claude + models)
             │                 │            │                │                  │                 │
             └──── Kafka: hms.patient · hms.appointment · hms.lab · hms.audit ──────────────────────┘
                                     │
                    PostgreSQL 16 — one schema per service
         identity · patient · scheduling · laboratory · notification
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
├── docker-compose.yml           postgres, kafka (KRaft), six services, ai-service, web
├── Dockerfile.java              shared multi-stage build, ARG SERVICE, non-root
├── config/                      SpotBugs exclusions and Dependency-Check suppressions, each with a reason
├── platform/hms-common/         security, errors, events, audit, crypto, pagination
├── services/
│   ├── gateway/                 routing, CORS, rate limiting, security headers, TLS redirect
│   ├── identity-service/        users, roles, JWT/JWKS, refresh rotation, audit sink
│   ├── patient-service/         patients, staff, departments, allergies, PHI encryption
│   ├── scheduling-service/      appointments, encounters, notes, vitals, diagnoses
│   ├── laboratory-service/      orders, results, reference ranges, ASTM + K-DPS parsers
│   ├── notification-service/    channels, delivery log, message templates
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

Also the **OPD token queue**. An appointment is a time; a queue is an order, and a clinic running
forty minutes late still has a defensible order of service. A number is issued when a patient
checks in and called when their consultation starts, so the queue is a by-product of the
appointment lifecycle rather than something anybody maintains alongside it — which matters, because
a queue somebody has to keep in step with the appointment book has drifted out of step with it by
mid-morning.

**Issuing a number is one SQL statement**, and it has to be. `SELECT max(token_number) + 1`
followed by an insert is a lost update that hands two patients the same number, and when 14 is
called two people stand up and neither is wrong. So:

```sql
INSERT INTO queue_counters (room_code, service_date, next_token) VALUES (:room, :date, 2)
ON CONFLICT (room_code, service_date) DO UPDATE SET next_token = queue_counters.next_token + 1
RETURNING next_token - 1
```

Fifty simultaneous check-ins produce fifty distinct consecutive numbers with no gaps, which
`QueueTokenIssuanceTest` asserts on fifty real threads against a real PostgreSQL — false for every
read-then-write implementation and true for this one.

**`GET /public/queue/{roomCode}` is the platform's only unauthenticated endpoint.** It is the
corridor display, mounted where every visitor, delivery driver and passer-by in the building can
read it, so what it returns is a room code, the number being called and the next few waiting. No
name, no MRN, no appointment id, no patient id — and no count of how many people are waiting, since
"you are fourteenth" plus a visible arrival order is enough for a stranger to work out who is who.
That is enforced by the response type having nowhere to put any of it rather than by a query being
careful, and `queue_tokens` itself holds no patient identity at all. It is allowlisted through
`hms.security.public-paths`, which had existed since hms-common was written and had no user until
now — so nothing had to be loosened to add it.

### `laboratory-service` — schema `laboratory`
Test catalog, sex-specific reference ranges, orders, specimens with sequence-issued accession
numbers, results, histograms, and **analyzer integration**: the ASTM E1394 / LIS2-A2 and Sysmex
K-DPS parsers, ported to Java. Entry is separated from release — a technician's value is
provisional and only a pathologist verifies.

An order carries the encounter it was raised from, so a chart can show what a visit ordered rather
than every test the patient has ever had. A patient's sex is optional on an order and **not
defaulted**: reference intervals are sex-specific, so an order with no sex recorded gets no
interval and the report falls back to the analyzer's own — a haemoglobin of 12.5 g/dL reads normal
for a woman and low for a man, and picking a side by default is picking wrong half the time,
silently, on the number a clinician treats from.

### `notification-service` — schema `notification`
Outbound messaging: a delivery log, the wording behind it, and three channels — a logging channel
(the default, and not a stub), plain SMTP for email, and a **generic HTTP gateway** for SMS.

Generic on purpose. Every SMS and WhatsApp gateway takes a different POST body, a different
authentication header and a different success shape, so hard-coding one would pick a vendor for
every deployment, add a paid dependency, and make the module untestable without that vendor's
sandbox. Instead the URL, the field names and the header are configuration — which means a
deployment can point it at whichever provider it already pays, or at an open-source SMS gateway on
a SIM modem, which is what a small hospital actually runs. Same reasoning for SMTP over a mail
vendor's SDK: every hospital already has a mail server, and SMTP is what all of them speak.

**No outbound message carries protected health information.** Not a value, not a flag, not a
diagnosis, not a name, not an MRN. A phone number is often stale, is frequently shared within a
family, and SMS is plaintext to the handset — so "your haemoglobin is 9.6, which is low" is a
disclosure to whoever happens to be holding the phone, while "a report is ready, sign in to view
it" is not. A released-report message reads the same whether the report is entirely normal or
entirely not, because a notification whose *existence* implied bad news would be as much of a
disclosure as one that said so.

That rule is enforced by construction rather than stated in prose. Callers supply **no text at
all** — they choose a category, and the words come from a template — and a template may interpolate
only two values, `{portalUrl}` and `{when}`. Anything else is refused when the template is saved
and again when it is rendered. Rewording a message therefore cannot introduce a clinical value, and
it is checked in the service's own suite, in `tests/api` against the deployed gateway, and in the
browser suite on the screen where somebody would try to work around it.

Every attempt is a row: what was said, which address it went to, and whether it arrived. It is a
delivery log first and a queue second, because the question asked afterwards is almost always "was
the patient told?" and a queue that deletes what it has processed cannot answer it. `SUPPRESSED` is
a real outcome — nothing was sent, on purpose, because there was nowhere to send it or the record is
archived — recorded rather than skipped, so "the patient was never told" has evidence behind it.
Replaying the same event produces one message: the idempotency key is a unique index, not a check
in application code, because two consumers handling a redelivery both pass a check and only one can
win an insert.

**A service account, because a consumer has no caller.** Every cross-service call elsewhere in the
platform forwards the caller's own token, which is the right default. A Kafka consumer has nobody
to forward: the trigger is an event, and an event deliberately carries no credential. So this
module signs in as `svc.notification`, holding the platform's narrowest role — `SERVICE`, which
reads `GET /patients/{id}/contact` (a phone number, an email address, and whether the record is
active) and nothing else. If that password leaks, what leaks with it is a contact list rather than
a chart, and the access appears in the audit trail in the same place as every other. Unset by
default, and that is a working configuration: with no service account the module composes and
records every message and sends none, saying so in the row.

### `web` — Next.js 16, React 19
The clinical interface. Server components call the gateway; **the browser never receives an access
token** — the session lives in httpOnly cookies and every platform call is made server-side.

Nine top-level menus, defined once as data in `src/lib/menu.ts`:

| Menu | Screens |
| --- | --- |
| Dashboard | today's board |
| Patients | register (search), register a patient, edit a record, the allergy list |
| Scheduling | appointment book, clinician availability, lapsed appointments, clinician schedules, the OPD token queue, and a link to the corridor display |
| Clinical | triage intake, encounter charting — vitals, the SOAP note, signing, amendments, ICD-10 coding, laboratory ordering — with AI assistance beside the note |
| Laboratory | worklist, an order's report with collection, result entry and release, specimen labels, scan a tube, test catalogue, reference ranges and interpretation rules — both retunable by a pathologist — analyzers, device messages |
| Facility | room directory, rooms, floors, room types, beds, departments — all editable by an administrator |
| Messaging | delivery log with the send form, message wording — readable by anybody who may send, editable by an administrator |
| Pharmacy, Billing | *not built* — see below |
| Administration | staff directory, users (create, roles, reset a password), roles, audit trail |

Three rules hold in the navigation, and each is asserted in `web/e2e/navigation.spec.ts` against a
real browser for all six seeded roles:

- **Roles filter, they do not disable.** Filtering happens on the server, so an item a user may not
  reach is never serialised into the page. A greyed-out item would disclose both what exists and
  that somebody else can reach it.
- **Dropdowns are disclosure widgets, never hover menus.** Click or Enter/Space opens, Escape closes
  and returns focus to the trigger, arrows move and wrap, `aria-expanded` and `aria-controls` are
  wired, and there is no hover-only path — this runs on tablets and wall-mounted terminals.
- **A module with no backend says so.** Pharmacy and Billing appear in the menu marked "not built"
  and lead to a page naming the service and endpoints they need. No mock table, no empty state
  implying data could arrive, no disabled buttons hinting at a workflow: a clinical screen that
  looks functional but is not is worse than an absent one, because somebody will read a number off
  it.

Writes go through **server actions**, not route handlers: `api()` already runs server-side with the
session cookie, so an action can call the gateway directly and then `revalidatePath()`, and the form
still works with JavaScript disabled. `src/lib/mutate.ts` holds the one `submit()` every action
uses; `src/lib/form.ts` holds the pure state helpers, separate because a `"use client"` module that
imports the fetch drags `next/headers` into the browser bundle and fails the build. `src/app/patients/new`
is the reference implementation — field errors come back from the service's own Bean Validation
messages rather than being reimplemented in TypeScript, and the duplicate-patient 409 renders as a
question ("these look like the same person") with the candidate charts as links, not as a failure.

**Booking is slot-driven, and that is a correctness decision.** `/appointments/new` asks for a
clinician and a day, then renders the availability the platform computed — each slot carrying its
exact instant, and each unavailable one carrying *why* ("in the past", "already booked", the
blackout's own reason). The form submits the chosen instant unmodified. Nothing in the web tier
builds a timestamp: a `datetime-local` input yields a wall-clock string with no zone, and the
browser's zone is not necessarily the platform's, so a booking assembled from one is a timezone bug
waiting for the first clinician who travels.

**The seven administrative CRUD screens share one form component, and the clinical ones deliberately
do not.** Floors, room types, rooms, beds, departments, staff and accounts are flat sets of fields
that post and re-read a list; written out seven times that is seven places for the same three
mistakes — a field error rendered nowhere, a value not echoed after a refusal, a checkbox that
submits nothing when unticked. Registration, charting and the allergy list each carry a rule the
generic form cannot express, and a component grown to cover those would be worse than both.

Four rules distinguish those screens from each other, and each is asserted in `web/e2e/admin-write.spec.ts`:

- **A room is read by `code` and written by `id`.** `GET /rooms/{code}` and `PATCH /rooms/{id}` are
  deliberately asymmetric — a code is what people say out loud, an id is what survives a rename —
  so every table row carries both.
- **An unticked checkbox posts nothing at all**, which for a sparse `PATCH` reads as "leave it
  alone" rather than "set it false". Each is paired with a hidden `false` so the field is always
  present — and the *order* is load-bearing, which cost a debugging session: `FormData.get()`
  returns the first value for a repeated name, not the last, so with the twin written above the
  checkbox every flag read as false however it was set. A room type ticked clinical and schedulable
  was created as neither, and the screen showed the truth while looking like it had ignored the
  form. `readForm` now has a test that pins first-wins, because the failure is silent and global.
- **An empty role set means "unchanged", never "remove every role".** Removing an account's access
  is done by disabling it, which says what it means; a screen that stripped roles by accident would
  look exactly like one that did nothing.
- **Everything here is `ADMIN_ONLY`, so the form is absent rather than present and refused.** A
  screen that looks usable and is not is worse than one that is honest about it.

Two endpoints were added to make those screens honest rather than half-built: a department and a
bed could each be created and never corrected or retired. `PATCH /departments/{code}` and
`PATCH /beds/{id}` close that, and both retire by setting `active` false rather than deleting —
the encounters recorded under a department and the admissions that happened in a bed are still
real. Neither takes a code: three services store a department code and admissions-service will
reference a bed by its own, and none of them would learn it had changed. `GET /beds` gained
`includeInactive` for the administration screen alone; a bed out of service must never reach
anything that allocates one, so the type filter that bed allocation uses ignores the flag entirely.

**An allergy is a rule, not a remark, and the form treats it as one.** Its severity is read by the
platform: a LIFE_THREATENING entry puts the red banner on the chart and will make a dispense refuse
outright. So recording one asks a question first, the question says what the entry will *do*, and
the fields go read-only while it is on screen so the answer cannot be to a different question.
Removing an allergy is confirmed too, and for the sharper reason — it withdraws a refusal the
platform was making on the patient's behalf. Editing demographics is a screen of its own rather than
fields that become editable in place, because the chart is read at a bedside by people who are not
correcting it. Archiving is a `DELETE` that is not a delete: `active` goes false and every row
stays, because the record is a legal document and the encounters referencing it remain valid.

**The charting screen reports the note's lifecycle rather than owning it.** `PUT
/encounters/{id}/note` does three different things depending on state the service holds: it creates
revision 1, edits the current revision in place while it is unsigned, or — once a revision is
signed — creates an **amendment**, a new revision carrying `amendsId`. The button looks identical
in all three cases, so the screen says which is about to happen: amending a signed clinical note is
a different act from correcting a draft, and it warns before the click, not after. Signing is
one-way and the sign button disappears; closing is refused while the latest revision is unsigned,
and the refusal is the service's own sentence naming that revision. Revision history is rendered in
full, because an amendment only means something if what it amended is still readable.

Two smaller decisions on that screen are worth naming. A blank observation is **omitted from the
request**, not sent as zero — an unrecorded pain score and a recorded zero are different clinical
facts — and submitting an empty vitals form is refused rather than written. And ICD-10 suggestion
lives in exactly one place, beside the diagnosis field, where a suggestion can be picked into an
input the clinician can still edit; the decision-support panel summarises the note and no longer
offers codes of its own, because a suggestion you cannot act on is the worse of two.

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

That brings up PostgreSQL, Kafka in KRaft mode, all six Java services, the AI service and the web
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

The dev profile seeds one account per role:

| Username | Role |
| --- | --- |
| `admin` | ADMIN |
| `dr.rao` | DOCTOR |
| `nurse.iqbal` | NURSE |
| `reception` | RECEPTIONIST |
| `lab.tech` | LAB_TECH |
| `dr.pathan` | PATHOLOGIST |
| `new.starter` | RECEPTIONIST — **still on its initial password**, so it can do nothing but change it |
| `svc.notification` | SERVICE — **not a person.** notification-service signs in as this to find out where to send a message, because the work is triggered by an event and an event carries no caller's token. It holds the platform's narrowest role: a phone number, an email address, and no part of a chart. |

The seed password comes from `HMS_SEED_PASSWORD` and defaults to `ChangeMe!Dev2026`.

Each of those accounts has a **stable id** (`33333333-0000-4000-8000-00000000000N`) and a matching
staff record, and that pairing is load-bearing rather than tidy: an appointment's `clinician_id` *is*
a user id, and the staff directory is the only thing that turns one into a name a receptionist can
pick from a list. Before the ids were stable no migration in another service could reference these
users, so no staff row existed, so the clinician dropdown was empty on every fresh deployment — and
scheduling's own seeded weekly pattern pointed at a clinician who did not exist anywhere. Its comment
said "clinician ids are resolved by the caller"; nothing resolved them.

Because an id is a primary key, nothing repairs a database that was already seeded with random ones.
A dev database from before that change needs its schemas dropped — which is what `make dev-test-stack`
does.

**The initial-password gate.** An account that has not changed the password it was issued with gets
a session that can do exactly one useful thing: change it. The mechanism is a token minted with
**no roles**, and that choice is the point:

- It is *structural*, not a list of blocked paths. Every endpoint outside `/auth/**` sits behind a
  `@PreAuthorize` naming at least one role, so a role-less token is refused by the authorisation
  rules that already exist — in all six services, and in any service written later, without any of
  them knowing the flag exists. `/auth/me`, `/auth/logout` and `/auth/change-password` carry no role
  requirement, which is how the account fixes itself and signs out.
- Refusing the login outright would be simpler and useless: `POST /auth/change-password` needs a
  token, so the only way out of the state would be an administrator.
- It is enforced by the platform, not the UI. The middleware redirect to `/change-password` is a
  courtesy so nobody stares at a screen where nothing works; an API client that ignores it gets 403
  from everything. This replaced a banner that said the password should be changed and enforced
  nothing at all, which an API client never saw in the first place.

Two things fell out of building it. An **administrator creating an account** now sets the flag —
before, only a password *reset* did, so a user created through `POST /admin/users` kept a password
their creator knew, permanently. And **a wrong current password now says so**: login deliberately
answers "invalid username or password" for everything so accounts cannot be enumerated, and
`GlobalExceptionHandler` enforced that by flattening every `BadCredentialsException` to that
sentence — including for an authenticated user who mistyped their own current password. There is
nothing to enumerate there. It is a 400 naming the field, and it still counts toward the lockout,
because a password buys persistence past a stolen token's fifteen minutes.

Only one seeded fixture carries the flag. Flagging all six — which is what the seeder used to do —
would leave a freshly seeded platform with no usable account at all, since the demo accounts are
what every test and every walkthrough signs in as. The seeder realigns the flag on accounts it owns,
so an existing dev database picks the change up without being dropped.

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

**Everything here is open source or free to use.** PostgreSQL, Spring Boot, Next.js, FastAPI,
scikit-learn, Flyway, Playwright, REST Assured, k6, OWASP ZAP, SpotBugs/FindSecBugs, Trivy,
CycloneDX, pip-audit — no component of this platform requires a commercial licence or a paid
subscription, and the one external service credential it can use (an NVD API key) is free and
optional. That is a standing constraint on what gets built next, not an accident of what was to
hand: where a capability needs an outside service, it goes behind a port with a logging or
file-based default so the platform runs and is testable with no account anywhere, and any adapter
written for a specific provider is one implementation of that port rather than the only path
through the code. It is also why the ICD-10 subset is bundled rather than fetched, why the AI
service answers correctly with no API key, and why analyzer integration speaks ASTM over a file
rather than a vendor SDK.

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

  The web app deliberately sends `Referrer-Policy: strict-origin-when-cross-origin` rather than the
  gateway's `no-referrer`, and the difference is not cosmetic. `no-referrer` also suppresses the
  `Origin` header on a form POST — Chromium sends `Origin: null` — and Next.js compares `Origin`
  against `x-forwarded-host` to reject cross-site Server Action calls. With `no-referrer` set, every
  native form submission in the app was refused as "Invalid Server Actions request" before it reached
  an action: the stricter-looking header broke the CSRF defence it was meant to help. The gateway
  keeps `no-referrer` because a JSON API has no forms.
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
mvn -q verify                                     # 366 Java unit and integration tests
cd services/ai-service && uv run pytest -q        # 91 Python tests
cd web && npm run lint && npm run typecheck       # web static checks
cd web && npm test                                # 40 web unit tests
cd web && npx playwright test                     # 77 browser tests, no skips
mvn -Pautomation -pl tests/api verify             # 121 API and security abuse cases
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
make sbom          # CycloneDX SBOM for the whole reactor
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
| Dependencies | Trivy over a CycloneDX SBOM (fixable HIGH/CRITICAL fails) | `make sca` |
| Dependencies, Python and web | `pip-audit`, `npm audit` — advisory on every push, blocking nightly | `make sca` |
| Dependencies, second opinion | OWASP Dependency-Check (CVSS ≥ 7 fails) — **needs an NVD API key**, see below | `mvn -Psecurity verify -DnvdApiKey=…` |
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

CI runs the fast set on every push — including dependency scanning; the slow set — PIT, both ZAP
plans, the container and IaC scans, Dependency-Check's NVD feed — runs nightly and uploads SARIF, so
a new finding appears as a code-scanning alert rather than a line in a log nobody opens.

**Java dependency scanning is Trivy over an SBOM, and that is a correction rather than a
preference.** The nightly job originally ran OWASP Dependency-Check alone, and it had **never
passed**. The pom configured `<nvdApiKey>${env.NVD_API_KEY}</nvdApiKey>`; the repository secret was
never populated; so the plugin received a zero-length key and died with `Invalid API Key, length of
0`. An absent key degrades to keyless mode, an empty one is fatal — and Dependency-Check 13.0.0 has
the same bug internally ([#8715](https://github.com/dependency-check/DependencyCheck/issues/8715)),
which is why the version here is held at 12.2.2.

The same first nightly run turned up two more failures, both worth naming rather than tidying away:
the container and IaC scan had never run either, because `aquasecurity/trivy-action` was pinned to a
tag that does not exist (`0.28.0` — the action's tags carry a `v` prefix), and the ZAP job died on
startup with `Unable to create home directory: /zap/?/.ZAP/` — the ZAP image has no `/etc/passwd`
entry for the runner's uid, so under `docker run -u $(id -u)` the JVM could not resolve a user name
and `$HOME` interpolated as a literal `?`. Both are fixed: the action pin, and an explicit writable
`HOME` mounted into the container.

Chasing that turned up something worse in the same script, and it is the reason to state it here
rather than in a changelog. `security/zap/run.sh` documents `0 clean, 1 findings, 2 could not run` —
but a plan that produced no JSON report printed *"clean at or above 'medium'"* and **exited 0**. A
scan that never happened reported itself as passing. And ZAP failing to start exited 1, the code
reserved for real findings, so a container that never scanned anything was indistinguishable from a
High-severity result. Both now exit 2 with an explicit "nothing was scanned", verified by running
the script against both failure modes. That a whole workflow's first run had three of six jobs
failing is the argument for putting checks where people look: `ci.yml` runs on every push and gets
read, and the dependency gate now lives there.

The gate is HIGH/CRITICAL **and fixable**, and it takes two inputs to mean that, not one: without
`limit-severities-for-sarif` the action announces "Building SARIF report with all severities" and
quietly ignores `severity` for the SARIF pass, so the exit code gets decided over every severity
including LOW and UNKNOWN. That is a gate failing on findings its own name excludes.

It found something on its first real run, which is the point: `bcprov-jdk18on` was pinned at 1.82 in
identity-service and carried CVE-2025-14813 (CRITICAL) and CVE-2026-5598 (HIGH — private-key leakage
through non-constant-time comparison). The pin is gone; the version now comes from the imported
Spring Cloud BOM, which supplies 1.85.2 — the same version the gateway was already getting
transitively. A local pin was what let one module drift three releases behind the rest of the
reactor.

So the gate is now Trivy against the CycloneDX SBOM Maven emits: no API key, no repository secret,
seconds instead of the hours an NVD feed download takes. It runs in `ci.yml` **on every push**,
beside the Python and web audits that were always there — the reason the security scans were split
into a nightly was that they are slow, and this one is not.

Dependency-Check stays wired in the nightly as a second opinion and runs only when `NVD_API_KEY` is
set; when it is not, the job writes an explicit notice and a run-summary entry saying so.
*A skipped check is not a passed check*, so it is never quietly green.

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
- **Room search had never returned a room without a clinic.** The same predicate, one join short:
  written as the JPQL path `r.department.code`, the department filter became an *inner* join, and
  the `:departmentCode = '%' or …` guard in front of it cannot rescue a row the join has already
  dropped. So every lobby, corridor, store, ward and pharmacy was invisible to the rooms screen
  whether a clinic was filtered on or not — 27 active rooms in the database, 11 returned. The
  repository comment above the query described the opposite behaviour, confidently, and cited the
  role-filter bug above as the reason for the shape. Found by a browser test looking for a ward it
  had just created and being told there were no rooms at all. `StaffRepository` had the same shape,
  latent because every seeded staff row has a department — and the staff form built in the same
  slice makes one optional, which is what a visiting consultant has. Both are explicit
  `left join`s now, with a test on each asserting both halves: the row is findable, and a clinic
  filter still does not sweep it up.
- **`includeInactive` was accepted and discarded by `/floors` and `/room-types`.** Both screens
  asked for it and rendered an "inactive" badge that could never appear, so retiring either was a
  one-way door: the row left the only screen that could bring it back. Floors had a sharper edge —
  `uq_floor_level` counts a closed floor, so its level looked free, the create was refused, and
  nothing anywhere said what held it.
- **Staff search matched the full name only**, while the search box has always said "name, employee
  number, specialty". Looking somebody up by the number on their badge returned nothing at all: the
  promise lived in the placeholder text and nowhere else.
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
integration, outbound messaging, AI service, web UI, containerisation, TLS, the full test pyramid,
SAST/DAST tooling, and performance profiles. Dependency scanning covers Python (`pip-audit`) and the web app
(`npm audit`) on every run, and Java through Trivy over the SBOM.

**Shipped but not verified here:**

- `docker compose up`. The container this was developed in has no Docker daemon, so that path is
  validated by review only.
- **OWASP Dependency-Check has never produced a passing run**, because it needs an `NVD_API_KEY`
  that is not set on this repository. It is wired and conditional; the Java dependency gate that
  does run is Trivy over the SBOM. Add the secret (free, from
  <https://nvd.nist.gov/developers/request-an-api-key>) and it switches on with no code change.
- Trivy itself could not be exercised locally: its release assets are unreachable through this
  container's egress proxy, so the SBOM scan is verified by a CI run rather than a local one.

**Not built:**

- **A handful of laboratory endpoints and states deliberately have no screen**, because they have
  no caller either: `IN_PROGRESS` is unreachable by any code path, `Specimen.reject()` has no
  endpoint, and the test catalogue and analyzer tables have no write endpoints at all — their
  pages say so, and their menu entries are marked read-only. An interpretive rule's *conditions*,
  label and display order are migration-level rather than form-level, and rules can be neither
  created nor deleted; a morphology cut-off's **note** is likewise read-only, since it appears
  verbatim on signed reports. There is no critical-value concept anywhere in the service — no
  column, field, flag or notification — so there is no critical-range editor to build yet.
- **Further clinical modules** — billing and claims, pharmacy and inventory, imaging/PACS.
- **A named waiting-room display route.** The corridor screen is `/display/{roomCode}` and a
  kiosk has to be pointed at it by hand once; there is no per-screen configuration, no rotation
  between rooms, and no "this floor's clinics" view. A hospital with twenty screens would want
  one, and it is a page rather than a platform gap.
- **A patient portal.** Every outbound message ends in a link to one, and the link currently
  points at the clinical web app's origin. The messages are built the way they are *because* there
  is meant to be somewhere behind a sign-in to say the specific thing — until the portal exists,
  the platform is telling patients to go somewhere that is not built yet, and that is named here
  rather than hidden by making the messages say more.
- **Delivery receipts.** The HTTP gateway channel records a non-2xx as a failure and the response
  body verbatim; it does not consume a provider's delivery-report callback, so "accepted by the
  gateway" is as far as the log goes. That is the cost of being provider-neutral and it is the
  right trade for a module whose job is one sentence and a link.
- **Retries.** A failed message is recorded once with the channel's reason and is not retried. A
  retry loop needs a scheduler, a backoff policy and a decision about how long a "your report is
  ready" stays worth sending, and guessing at those would be worse than the honest log.
- **Notification preferences.** A patient cannot yet say "email, not SMS" or opt out. The channel
  is chosen by the sender or by configuration.
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
