# MedSync

A hospital management platform built as microservices: **Java 21 / Spring Boot 4** services, a
**Python** clinical decision-support service, a **PostgreSQL 16** database with a schema per
service, and **Kafka** for domain events.

A patient can be registered, booked with an AI no-show risk score, seen in an encounter with
SOAP notes and vitals, coded, and have blood work ordered — with results arriving straight off a
haematology analyzer over its own wire protocol and released by a pathologist.

> **Status:** the clinical core, the laboratory (including analyzer integration), the AI service
> and the web UI are implemented and verified end to end against a real stack, and so are the
> containerisation, TLS, security-testing and performance-testing layers. **1,182 tests pass** —
> 667 Java unit and integration, 91 Python, 53 web unit, 237 black-box API and security abuse cases,
> and 134 browser end-to-end, plus four k6 profiles. See [Testing](#testing) and
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
      identity :8081     patient :8082      scheduling :8083    laboratory :8084
      users · roles      patients           appointments        lab orders
      RS256 + JWKS       staff              encounters          ASTM + K-DPS
      audit trail        departments        notes · vitals      results
                         rooms · beds       NEWS2 · OPD queue

      notification :8085   admissions :8086    pharmacy :8087      billing :8088
      delivery log         casualty board      formulary           charge capture
      SMTP · HTTP SMS      bed occupancy       prescribe·dispense  GST invoices
      no PHI outbound      transfers           eMAR, two scans     payments · claims

      interop :8089        ai :8000
      consent artefacts    FastAPI
      FHIR R4 bundles      4 capabilities
      ABDM · EHI export    (Claude + models)

      /portal/** — the patient's own door, split across the five services that own the
      data. Not a service: assembling one patient's view from a portal service would
      need a credential able to read every patient's chart. Whose record it is comes
      from a signed claim on the token, never from the request.
             │                 │                    │                  │
             └── Kafka: hms.patient · hms.appointment · hms.lab · hms.admission ·
                        hms.pharmacy · hms.billing · hms.audit ────────────────────┘
                                     │
                    PostgreSQL 16 — one schema per service
    identity · patient · scheduling · laboratory · notification · admissions ·
    pharmacy · billing · interop
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
├── docker-compose.yml           postgres, kafka (KRaft), eleven services, ai-service, web
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
│   ├── admissions-service/      casualty board, bed occupancy, admissions, transfers
│   ├── pharmacy-service/        formulary, prescribing, dispensing, closed-loop eMAR
│   ├── billing-service/         charge capture, GST invoicing, payments, payer claims
│   ├── interop-service/         consent artefacts, FHIR R4 bundles, ABDM, EHI export
│   │                            (`/portal/**` is split across the five services above, not a
│   │                             service of its own — see "The patient portal")
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
and audit fan-out, LIKE-pattern helpers, AES-256-GCM column encryption for PHI, a CSV writer that
neutralises spreadsheet formulas, and the Code 128 encoder both barcodes on the platform are drawn
from — a specimen tube's label and a patient's wristband.

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

Also **NEWS2**, the National Early Warning Score. The platform captured vitals and scored nothing,
which left a deteriorating ward patient visible only to whoever happened to read the numbers and
compare them to the last set. NEWS2 is deterministic, needs no model, and everything it needs was
already recorded — except one thing: **whether the patient is on supplemental oxygen**, which is
worth two points and cannot be read off a saturation, since 96% on four litres is a very different
patient from 96% on air. That is now a field, and without it the score would under-read by 2 for
everybody on oxygen, which is the direction that gets missed.

The score comes back with the observations rather than from a second call, so the two cannot
disagree, and it carries its own working: the per-parameter breakdown, the single-parameter rule
(a total of 3 all from one parameter escalates further than a 3 spread across three), and **what
was not measured** — because a NEWS2 of 3 from four observations is a different fact from a 3 from
seven, and nothing is ever assumed normal. It is advisory throughout: nothing here lets a score
change a status, move a patient or raise an order.

**The cut-offs are in code and the escalation policy is in rows**, which is the one place
`docs/extensibility.md` argues against a table. NEWS2 is a national standard whose whole value is
that 6 means the same thing everywhere, so a deployment able to edit the bands could publish a
number it calls NEWS2 which is not. What every trust genuinely decides for itself is the response —
who is called, how fast, how often observations repeat — and that is `escalation_policies`, editable
by an administrator and audited. Scored against the Royal College of Physicians' published chart in
`News2CalculatorTest`, whose expectations come from that chart rather than from running the
implementation.

**`GET /public/queue/{roomCode}` is the platform's only unauthenticated endpoint.** It is the
corridor display, mounted where every visitor, delivery driver and passer-by in the building can
read it, so what it returns is a room code, the number being called and the next few waiting. No
name, no MRN, no appointment id, no patient id — and no count of how many people are waiting, since
"you are fourteenth" plus a visible arrival order is enough for a stranger to work out who is who.
That is enforced by the response type having nowhere to put any of it rather than by a query being
careful, and `queue_tokens` itself holds no patient identity at all. It is allowlisted through
`hms.security.public-paths`, which had existed since hms-common was written and had no user until
now — so nothing had to be loosened to add it.

#### Order sets, and care plans

Two things a clinician does with a chart that nothing else here could do: raise the half-dozen
orders a presentation always needs in one act, and write down what the episode is trying to achieve.

**An order set is rows.** Adding "fever, first line" needs no new behaviour — it is a name and a
list. What it must never be is half-filled, so a medication line without a dose, a frequency, a
duration *and* a quantity cannot be stored: an order set is the one place a template reaches a
patient without anybody typing it, and a CHECK constraint is the only kind of rule that cannot be
forgotten. The reverse is refused too — a laboratory line carrying a dose is a medicine mis-typed
as a test, which would be raised as neither.

**Applying one is a saga with compensation, not a transaction, and the code says so.** The
prescription lands in pharmacy-service's schema and the laboratory order in laboratory-service's,
so no database transaction spans them, and a comment claiming one would be the most dangerous kind.
What the platform does promise is that applying a set either raises everything it names or leaves
nothing behind:

1. The prescription goes **first**, because it is the step that can be refused on clinical grounds
   — an allergy, an interaction, a role that may not prescribe. A refusal there has left nothing.
2. The laboratory order goes second, as **one** order for every test in the set: a panel of bloods
   is one needle, and separate orders would mean separate specimens and a patient stuck twice. It
   carries the most urgent priority any line names, because taking the first or averaging would
   quietly downgrade an urgent draw.
3. If that fails, the prescription is withdrawn.
4. If the withdrawal fails too, the refusal **names the prescription**, because a clinician can
   cancel one by hand and cannot act on "something went wrong".

The caller's own token goes downstream, so a nurse applying a laboratory-only set succeeds and the
same nurse applying one that prescribes is refused by pharmacy-service. That rule lives there, once;
a copy of the role list in scheduling-service could drift from it, and the drift would be silent in
the dangerous direction.

**A care plan is goals with dates and outcomes.** A chart records what happened; this records what
was meant to happen, which is what a ward round, a discharge summary and a review all ask about and
which no note answers — "improving" is not a goal. One plan per encounter, by unique constraint. A
goal may be filed under one of that encounter's own diagnoses (checked live, so a diagnosis added
later is still available) or under none, because "mobilising independently" belongs to the admission
rather than to a problem. Any outcome other than met needs a note, and the plan **refuses to close
while a goal is still open** — which is the point: it makes somebody decide, rather than letting
"we were going to do that" disappear at discharge.

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

### `admissions-service` — schema `admissions`
Casualty and in-patient care: the emergency board, bed occupancy, admissions, ward transfers and
discharge.

**One service, because they contend for the same beds.** An appointment is a point on a calendar
and an admission is a stay lasting days, driven by acuity rather than by a clock — they share no
query shape, which is why this is not folded into scheduling. But casualty and the wards do share
something that matters more: the beds. Splitting them would leave two services writing occupancy
and nothing stopping one bed appearing in both.

**The board is ordered `triage_acuity ASC, arrived_at ASC`, and the ordering lives in the query.**
Sickest first, ties to whoever has waited longest. A casualty queue served in arrival order kills
the person who arrived last and is the sickest, so the sort is not a caller's choice: there is no
sort parameter on the endpoint and the screen has no sortable column header, because a column
somebody could sort by arrival at three in the morning would defeat the point of triage. What the
colour on the board means is the wait *against that level's own target* — two hours is fine for a
level 5 and a catastrophe for a level 2 — so a board coloured by minutes alone would shout about
the wrong patients.

**One bed, one patient, enforced by the database.** Both paths write through a single
`bed_occupancy` table carrying one partial unique index:

```sql
CREATE UNIQUE INDEX uq_bed_occupied ON bed_occupancy (bed_id) WHERE released_at IS NULL;
```

Not a check-then-act in application code, because two nurses on two terminals reach that check at
the same instant on a busy night and only the database sees both. The 409 says what to do next —
`Bed CAS-1 in GF-CAS has just been taken. Pick another from the free list.` — rather than reporting
a constraint name. A transfer releases and claims inside one transaction, so there is no moment in
which the patient reads as being in two beds and none in which they are in neither; if the
destination has just been taken the whole move rolls back and the patient stays where they were.

**A move is a row, not an overwrite.** `bed_transfers` keeps every move with its reason, because
"how many times was this patient moved overnight" is an infection-control question that an
overwritten bed code cannot answer.

**`LEFT_WITHOUT_BEING_SEEN` is a real outcome.** It is a standard emergency-department quality
metric — a department where it rises is a department people are giving up on — and recording it as
a discharge would delete the only signal that says so.

**It owns no beds.** The bed rows belong to patient-service's facility directory and are fetched
over HTTP with the caller's token, **failing closed**: if the directory cannot be reached the
allocation is refused, because allocating a bed nobody has verified is worse than refusing one.
patient-service deliberately keeps no occupancy flag on a bed — a flag written by one service and
maintained by another is a flag that goes stale, and a stale bed map is how two patients are sent
to one bed.

The board and the census are gated on `BED_MANAGE` (admin, doctor, nurse) rather than the platform's
wider clinical read. A list of who is in casualty, with what complaint, and how sick somebody judged
them is a chart in table form; the front desk books and registers, and the laboratory has less
reason still.

### `pharmacy-service` — schema `pharmacy`
The closed medication loop: formulary, prescribing, interaction and allergy checking, stock by
batch, dispensing, and administration at the bedside.

**Not a dispensary.** The temptation was to build formulary plus stock plus dispense and call it
pharmacy; every system worth comparing this one to treats the medication loop as one circuit —
prescribe → check → dispense → administer against a scanned wristband and a scanned label → record
— because that circuit is where the deaths are. Building only the dispensing end would have left
the highest-risk workflow in a hospital half-wired.

**Three acts, three roles, no overlap.** A prescriber writes the order (`PRESCRIBE`), the pharmacy
fills it (`PHARMACY_WRITE`), a nurse gives the dose (`MEDICATION_ADMINISTER`). No role on this
platform holds two of the three, and eighteen rows in `tests/api`'s authorization table exist to
keep it that way: if any one of them ever answers 2xx, one account can order a medicine, hand it
over and sign that it was given. A pharmacist reads a prescription and an allergy list and **cannot
open a chart** — the same line `CHART_READ` draws for the laboratory, drawn once more.

**Checks run on ingredients, not on names.** A patient allergic to penicillin is allergic to it
under every trade name it has ever been sold under, so `formulary_ingredients` carries the molecule
*and* its class markers: amoxicillin is AMOXICILLIN and PENICILLIN, ibuprofen is IBUPROFEN and
NSAID. That is how a class allergy and a class interaction work without a second mechanism — and it
is why an entry with no ingredients is refused at creation, since it would pass every check by
having nothing to match. Matching is whole-word rather than substring, because plain containment
makes an "ACE" allergy block paracetamol, and a checker that cries wolf is one people click through.

**Three answers, not two.** A check comes back CLEAR, OVERRIDABLE or REFUSED, and the middle one is
the point. A recorded severe or life-threatening allergy is refused outright and no reason unlocks
it; a contraindicated pairing likewise. At or above the deployment's threshold —
`hms.pharmacy.interaction-floor`, MAJOR by default — the prescriber may go ahead having written down
why, and that sentence travels with the prescription to the counter, because the pharmacist is the
last person who can question it. Below the floor, the finding is reported and does not block:
interrupting for every minor interaction is how a hospital teaches its clinicians to dismiss the
dialog without reading it.

**The checks run twice.** Again at dispensing, against the patient's record as it is *now* — an
allergy may have been recorded since the order was written, and the patient may have been started on
something else.

**One row per unordered pair.** `interaction_pairs` holds the two ingredients sorted, enforced by
`CHECK (ingredient_a < ingredient_b)`, so a deployment cannot end up holding (warfarin, aspirin) as
MAJOR and (aspirin, warfarin) as MINOR with which one fires depending on the order a caller passed
them in. The `management` column is what earns the table its keep: "these interact" gets dismissed,
"monitor INR weekly for the first month" does not.

**Stock is by batch, and expiry is enforced three times.** A batch that expires today counts as
expired. An expired delivery is refused at the door; the first-expiry-first-out query excludes
expired batches so no picker is ever offered one; and the decrement re-checks the date, because
choosing a batch and writing the row can span midnight. FEFO rather than FIFO because stock
received later can expire sooner, and picking by arrival is how a pharmacy destroys the box it
should have used.

**The database decides the races.** Stock comes out by one conditional `UPDATE` — two pharmacists
reaching for the last box both read the same quantity, and a read-modify-write would let the second
silently restore what the first took. One dose is one record by unique constraint on
(item, scheduled time): two nurses at one bedside, each believing the other had not given it, both
pass a check and only one can win an insert.

**Closed-loop administration.** A dose needs a scanned wristband matching the prescription's MRN and
a scanned label matching the drug code; both are checked before the row exists, and both are stored
verbatim rather than as a "verified" boolean, because the question asked after a wrong dose is
*which barcode was actually scanned*. There is no "scanner unavailable" flag: typing the numbers in
is allowed, since scanners fail, but an override that turns both checks off becomes the normal path
within a week. A dose **not** given is also a row, with a reason and no scans, because the absence
of a dose is a clinical fact the next shift needs.

**And the band it scans is printed here now.** `GET /patients/{id}/wristband` renders one as SVG,
carrying the MRN as a Code 128 symbol plus the name, date of birth and sex a person reads it for —
the exact opposite of the tube label's rule, which carries no identity at all because a tube leaves
the building while a band is on the wrist of the person it names. The barcode payload is the MRN and
nothing else, because that is the string `AdministrationService` compares a scan against; the
renderer says so, and the test decodes the bars back out of the rendered SVG and compares them to an
independent encoding rather than trusting that the markup mentions the number somewhere. Printing
one is `FRONT_DESK` — registration's and the ward's — and audited, because a band is an identity
artefact that leaves the platform on somebody's wrist, and a band on the wrong wrist defeats the
scan check at the one point the check cannot see. It is refused for an archived record, whose MRN
would fail every live prescription at the bedside with nothing on the band to explain why.

It lives in patient-service rather than in admissions-service, which is a correction of what this
file used to predict. A band is printed at admission, so that looked like where it belonged — but
every field on it is patient-service's, admissions-service holds none of them, and putting it there
would have meant a cross-service client fetching data the owning service can serve directly. Here,
casualty and the outpatient desk can print one too, and only in-patients get admitted. The Code 128
encoder moved to `hms-common` on the way, with the module width, quiet zone and white ground that
decide whether a symbol scans — two labels reading those numbers off one class rather than two, so
a fix reaches the ward and the bench together.

### `billing-service` — schema `billing`
Charge capture, GST invoicing, payments and payer claims. Ten tables, and four of them exist to
answer a way hospitals lose money with a database rule rather than with application care.

**A charge cannot post twice.** `posted_charges` is keyed by `(source_type, source_id,
charge_item_code)`, so a redelivered Kafka message collides with the charge it already produced and
is reported as already-posted rather than billing the patient again. Brokers redeliver and operators
replay lost partitions; without that key a patient is billed twice for one consultation, and the
second bill is the one they take to a lawyer.

**Overpayment is refused atomically.** One conditional statement takes the money and moves the
status in the same breath:

```sql
UPDATE invoices SET amount_paid = amount_paid + :amt,
       status = CASE WHEN amount_paid + :amt >= total THEN 'PAID' ELSE status END
 WHERE id = :id AND status IN ('DRAFT','ISSUED') AND amount_paid + :amt <= total
```

Zero rows is the refusal, and the service turns it into a 409 naming what is actually outstanding.
Two cashiers taking the same balance both read the same `amount_paid`; a read-modify-write would let
the second silently restore what the first collected. Deriving PAID in a second statement would
leave a window in which an invoice was fully paid and still said ISSUED, and a receipt printed in
that window would be wrong.

**Prices are snapshotted onto the line, never joined.** The deliberate opposite of the room
decision elsewhere in this platform: a room's directions must always be current, and a financial
record must never change after the fact. Repricing a charge item changes what the next invoice
charges and nothing that has already been raised.

**Tax is rows with effective dates, never 18% in the code.** GST changes by statute and an invoice
raised last year must keep the rate that applied then, so a rate change is a new row that closes its
predecessor rather than an edit — and a rate cannot start in the past, because receipts already
issued would disagree with it. **Healthcare services provided by a clinical establishment are
GST-exempt in India**, so exempt is the default and what is taxable is what a hospital *sells*: a
dispensed medicine, a consumable. A tax-exempt payer exempts the line whatever the item says, and
tax is charged on the discounted amount rather than the gross, because taxing the list price and
then discounting collects tax on money nobody paid.

**`numeric(14,2)` in the database, `BigDecimal` with explicit HALF_UP at every boundary, and no
`double` anywhere near an amount.** Rounding happens once per line — per unit magnifies the error by
the quantity, per invoice produces a total that does not equal the sum of the printed lines. HALF_UP
because it is what the person checking the bill does by hand. The scale survives to the screen too:
JSON has one number type and `JSON.parse` turned `500.00` into `500`, which rendered "500" beside
"18.00" until a formatter fixed it — no error, no log, just a bill that invites an argument.

**Charge capture is by event, so no clinical service knows billing exists.** A completed
consultation, a released laboratory order, a dispense and a discharge's bed-day count all arrive as
domain events and are priced here. Asking scheduling to call billing would make finishing a
consultation fail when the billing service is down, which is the wrong trade in a hospital. Which
charge item each event posts against is configuration (`hms.billing.capture.*`); an event naming
something the price list has never heard of is reported in the log and charged to nobody, because
substituting a plausible price would put a number on an invoice that nobody chose.

**Two separations of duties, and both are asserted.** A clinician reads what a patient was billed —
asked at the bedside, and a platform that sent them to the billing desk for a number would be routed
around within a week — and cannot raise an invoice, post a charge or take a payment: the person who
decides what was done is not the person who records that it was paid. A cashier takes money and
cannot set prices, because somebody who could discount a procedure to zero and then record it as
settled in full would need no accomplice. The laboratory and the pharmacy see none of it. The
`CASHIER` role holds no clinical read at all, so identifying a patient to bill goes through a narrow
`GET /patients/identify` that answers an id, an MRN and a name — the third narrowing of the kind
`CONTACT_READ` and `ALLERGY_READ` already make.

**A day is the deployment's own.** Invoice dates, tax resolution, the financial-year number series
and the day book's boundaries all come from one `BillingClock` bound to `hms.billing.zone`. That was
not theoretical: the day book counted payments in Asia/Kolkata while invoices were dated in the
container's UTC, and a day's billing read zero while its collections read eight hundred.

Invoice numbers are per financial year (April–March, Indian convention) and issued by a
single-statement counter with `ON CONFLICT … RETURNING`, so a gap in the sequence — the one thing an
auditor reading a numbered series cares about — cannot come from two cashiers raising an invoice at
once.

### `interop-service` — schema `interop`
Consent artefacts, FHIR R4 bundles, and a record of everything that has left the building.

**The rule the module exists for: health information does not leave without a valid consent that
covers it.** Four separate questions, asked separately so a refusal can say which one failed —
is the consent granted, is it still live, does it cover this kind of information, and does it cover
the record's own date. That last pair is the one people conflate: "you may see my records from last
year" is a different sentence from "this permission lasts a year", and they are different columns.

**There is no bypass.** No flag, no administrative override, no role that skips the check. A
break-the-glass emergency access is a real requirement and it is a *purpose code on a consent* —
recorded, and loudly audited — rather than a way around one, which is the difference between an
emergency and an exception. The check also runs *before* anything is read: a revoked consent never
causes a chart to be fetched, which the service's own test asserts with
`verify(clinical, never())`.

**Expiry is compared against the clock on every share.** A stored EXPIRED status exists so a list
query is cheap, and nothing depends on it: a platform whose consent enforcement needed a scheduled
job to have run would be a platform where a missed job is a disclosure.

**Two paths out, and the difference is load-bearing.** A *consented share* goes to a third party
and cannot exist without an artefact — the `disclosures` table's own CHECK refuses the combination.
A *patient export* hands somebody their own record, which is the EHI-export criterion rather than a
disclosure, and has no consent behind it deliberately: asking a person to consent to receiving
their own data is a formality, and formalities are how everybody learns to click through consent
screens. It is instead administrator-only and loudly audited.

**Every disclosure is written in the same transaction as the release**, and it records what was
sent, to whom, under which consent, how many resources and how many bytes — never the content. A
patient is entitled to ask who has seen their record, and "we would have to grep six services'
logs" is not an answer; a log that carried the bundles would be a second copy of the record in the
one table auditors are given broad access to.

**Bundles are composed with the caller's own token, from the four services that own the data.**
This module holds consent artefacts and a disclosure log and no clinical record of its own, so an
export is a view of the record rather than a second record that can drift. It holds no service
account either: it cannot read anything its caller could not, which matters because an interop
service with a powerful credential would be the most attractive password on the platform. The
client fails closed — an unreachable service is an error, not a section quietly missing from an
export, because a partial record presented as a complete one is undetectable at the receiving end.

**FHIR R4, built by hand.** `FhirBundleBuilder` is pure — no repository, no clock, no HTTP — so
the structure a receiving system will parse is asserted in unit tests with nothing running: a
document bundle led by its Composition, a note kept as its four sections, a blood pressure as one
Observation with two components, ICD-10 coded Conditions, reference ranges and interpretation flags
on laboratory results, a text result that stays text. Built by hand rather than with HAPI FHIR
because HAPI brings a validator *and* several hundred megabytes of structure definitions into a
platform that emits a dozen resource types. **What is lost is real: nothing here has been run
through an R4 validator.** These bundles are correct as far as the specification was read and
followed, and a deployment that has to prove conformance should validate the output rather than
trust this paragraph.

**Local codes are presented as local.** A laboratory parameter is `urn:medsync:lab-parameter` and a
formulary code is `urn:medsync:formulary`, not LOINC or SNOMED. A receiving system can map a code it
knows is local and cannot unmap one it was told was standard.

**ABHA lives on the patient record, encrypted, and never in a response.** Fourteen digits and an
address, stored with the same converter as the national id and released only through the audited
`GET /patients/{id}/identifiers`. Linking one is its own endpoint — `PUT /patients/{id}/abha`,
front-desk-only — because writing a national identifier onto a record is a distinct act somebody
should be able to find in the audit log, not a side effect of correcting a surname. The audit line
says that it happened and never what was written. A bundle carries the MRN and never the ABHA
number: an ABDM push addresses the patient at the gateway, so putting a national identifier in
every payload would buy nothing.

**Where a bundle goes is an adapter.** `LoggingAbdmGateway` is the default and reports
`transmitted: false` — the honest state for a deployment with no NHA credentials, said in the API
response and in the disclosure register rather than dressed up as a send. `HttpAbdmGateway` posts to
a configured URL. **Neither is ABDM certification**: the real data flow is a consent manager, a
callback, an encrypted payload with a key exchange and an assessed HIP, and this is a place for that
to be implemented against a sandbox. The README says so because a module with an HTTP adapter is
exactly the module somebody would otherwise describe as compliant.

### The patient portal — a prefix, not a service
`/portal/**` is the patient's own door onto their record: their appointments and self-booking,
released laboratory reports, the visits a clinician has signed, their prescriptions, their bills,
written questions to the hospital, and a copy of the whole record in FHIR to take away.

**It is not a service, and that is the design decision worth reading.** Assembling one patient's
view means reading from five services, and the obvious way to do it — a portal service that fans
out — would have to hold a credential able to read *every* patient's chart in order to show one
patient theirs. That credential would immediately be the most attractive thing on the platform.
So the prefix is split instead, one sub-path per owner: `/portal/me` is patient-service's,
`/portal/appointments` and `/portal/encounters` are scheduling's, `/portal/reports` is the
laboratory's, `/portal/prescriptions` is the pharmacy's, `/portal/invoices` is billing's,
`/portal/messages` is notification's, and `/portal/records/export` is interop's. The gateway's
route table is the only place that says so.

**Whose record it is comes from the token and from nowhere else.** A portal account is a row in
`users` with a `patient_id`, that id is a signed claim on the access token, and every portal
endpoint reads `CurrentUser.requirePatientId()`. There is no `/portal/patients/{id}` and there is
not going to be: an IDOR test against these endpoints has nothing to tamper with, which is a
stronger property than an IDOR test that passes. Where an id is unavoidable — one appointment, one
report, one conversation — the record is fetched filtered by the session's patient and a miss is a
**404 rather than a 403**, because "not yours" confirms that the id is real.

`Roles.PORTAL` is `hasRole('PATIENT')` and is the only constant in the file that does not carry
ADMIN. These endpoints answer "the signed-in patient's own record", and there is no patient an
administrator is; 403 is the honest answer where an empty record would read as "you have no
allergies". The staff-facing views of the same data are unchanged and are what an administrator
should use.

What the portal deliberately narrows, in each case for a reason that is not about the portal:

- **Released results only.** A bench result is provisional — an analyzer artefact, a mislabelled
  tube, a dilution nobody has repeated — so the list says "In the laboratory" and carries no value,
  no result count and no abnormal flag until a pathologist has verified it. Publishing round the
  release step would make the patient the first reader of a number that may be wrong.
- **Signed notes only.** A draft is a sentence somebody is still deciding whether they believe.
  Showing it to its subject makes it a statement they never made, and clinicians respond to that by
  not drafting in the system.
- **No staff free text about the patient.** The registration `notes` field is written by people who
  have never considered that its subject would read it.
- **Nothing a patient may not decide.** The self-booking request has no priority field and no room
  field, so urgency stays a triage decision and rooms stay allocated against the day's whole list.

**Enrolment is the front desk's**, because somebody has to satisfy themselves that the person
asking for access to a record is the person the record is about. It runs through patient-service —
which owns the register, refuses a patient it cannot find, and refuses one with no email address on
file, since that address is where a password reset would go — and patient-service calls
identity-service with the receptionist's own token to mint the account. The username is the MRN the
patient already carries, and the one-time password is answered once, stored only as a hash and
never logged. The account then falls into the platform's existing initial-password gate: its first
token carries no roles at all, so it can change its password and do nothing else.

**Secure messaging is where this platform's PHI rule turns around, and deliberately.**
notification-service exists on the principle that an outbound message carries no clinical
information; the `message_threads` and `thread_messages` tables hold exactly what that rule keeps
out of an SMS. The rule is about the channel, not the content: a sentence on a screen behind a
password the patient chose is the safe place for "your thyroid result is slightly low", and the
same sentence on a handset on a family plan is not. Splitting the two is what lets the hospital say
anything useful at all. A thread's status follows who wrote last rather than being set by a caller,
nothing is editable or deletable once sent, and every thread carries a standing notice that the
portal is not monitored continuously — as a field the platform supplies, not as prose a screen
could drop.

**Its own rate-limit bucket**, a fifth of the general one. Nothing a patient does looks like a
clinician loading a worklist, and the portal is the only authenticated surface a stranger can
obtain a session on by asking at a desk — so it neither needs the general allowance nor should be
able to spend it for every clinician behind the same address.

Not built, and named in the Roadmap rather than half-built: **online payment** (it needs a merchant
account and live gateway credentials, and a Pay-now button that settled an invoice without
receiving anything would balance the day book against money that does not exist) and a **published
clinician directory** for self-booking, which is why the booking form offers the clinicians this
patient has already seen rather than a list of everybody.

### `web` — Next.js 16, React 19
The clinical interface. Server components call the gateway; **the browser never receives an access
token** — the session lives in httpOnly cookies and every platform call is made server-side.

Eleven top-level menus, defined once as data in `src/lib/menu.ts`. What a role sees is a subset: the
laboratory accounts get no Clinical menu at all, because every one of its items is gated away from
them and an empty dropdown is worse than an absent one.

| Menu | Screens |
| --- | --- |
| Dashboard | today's board |
| Patients | register (search), register a patient, edit a record, the allergy list |
| Scheduling | appointment book, clinician availability, lapsed appointments, clinician schedules, the OPD token queue, and a link to the corridor display |
| Clinical | triage intake, encounter charting — vitals with a NEWS2 score, the SOAP note, signing, amendments, ICD-10 coding, laboratory ordering, prescribing, order sets and the care plan — with AI assistance beside the note; the casualty board and the admissions census with its bed map; the drug round, where a dose is given against two scans; and the order-set reference list. All gated to clinicians rather than to everybody who may look a patient up |
| Laboratory | worklist, an order's report with collection, result entry and release, specimen labels, scan a tube, test catalogue, reference ranges and interpretation rules — both retunable by a pathologist — analyzers, device messages |
| Facility | room directory, rooms, floors, room types, beds, departments — all editable by an administrator |
| Sharing | the consent register with the four conditions on every row, recording a decision (front desk), sending a record under a consent (clinicians), and what has been released about a patient and under what |
| Messaging | delivery log with the send form, patient questions (the portal's queue, oldest first, where a reply may say what an SMS may not), message wording — readable by anybody who may send, editable by an administrator |
| Pharmacy | dispensing queue with the override reason on the row, formulary with its ingredient lists, the interaction table with what to do about each pairing, stock by batch with what is about to expire — gated to the roles that may read a medication order |
| Billing | invoices (open bills first, or one patient's whole history), raise an invoice, the day book split by how money arrived, claims, and — administrator-only — the charge list, payers with their agreed tariffs, and dated tax rates |
| Administration | staff directory, users (create, roles, reset a password), roles, audit trail |

**The patient portal is a different application in the same codebase.** It is a route group of its
own — `(portal)` beside `(app)` — with its own layout, its own eight links and no clinical menu at
all: Overview, Appointments, Test results, Visits, Medicines, Bills, Messages, My record. The list
above is not shown to a patient even filtered to nothing, because the shape of a menu is itself a
description of the building. The middleware routes a portal session to `/portal` and back to it if
they type a clinical path, the layout refuses to render for a staff account, and every endpoint
behind it is gated `hasRole('PATIENT')` in five services — three layers, of which only the last is
an authorisation. `menu.test.ts` asserts that a patient reaches nothing in the staff menu and that
no staff item points into `/portal`.

Three rules hold in the navigation, and each is asserted in `web/e2e/navigation.spec.ts` against a
real browser for all eight seeded roles:

- **Roles filter, they do not disable.** Filtering happens on the server, so an item a user may not
  reach is never serialised into the page. A greyed-out item would disclose both what exists and
  that somebody else can reach it.
- **Dropdowns are disclosure widgets, never hover menus.** Click or Enter/Space opens, Escape closes
  and returns focus to the trigger, arrows move and wrap, `aria-expanded` and `aria-controls` are
  wired, and there is no hover-only path — this runs on tablets and wall-mounted terminals.
- **Every item leads to a real screen.** There was a "not built" badge and a page behind it naming
  what a module still needed — the OPD queue, the corridor display, casualty, the census, the
  pharmacy and finally Billing all passed through it. All of them are built, so the badge, the page
  and its registry are gone rather than kept as scaffolding whose every claim would now be false;
  what is still missing is in the Roadmap below, which is where somebody looks for a roadmap. The
  rule it enforced still holds and is still asserted: no mock table, no empty state implying data
  could arrive, no disabled button hinting at a workflow. A screen that looks functional and is not
  is worse than an absent one, because somebody will read a number off it.

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

For anything beyond local work, split that one role into two — see
[the role split](#database-roles) — with one command and four environment variables. Skipping it
leaves the platform behaving exactly as it did before the split existed, which is why it is a step
rather than a prerequisite.

### 2. Build and start the Java services

```bash
mvn -q package -DskipTests
scripts/local.sh start          # identity, patient, scheduling, laboratory, notification, admissions, pharmacy, billing, interop, gateway
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
| `pharmacist` | PHARMACIST — fills prescriptions, keeps the formulary and the stock. Reads a prescription and an allergy list and **cannot open a chart**, cannot prescribe, and cannot record a dose as given. |
| `cashier` | CASHIER — raises invoices, takes payments, works claims. The mirror image of the pharmacist: it can collect money and **cannot open a chart**, and `dr.rao` can read a bill and cannot take a payment. |

**The portal seeds no account either, and cannot.** A portal account has to point at a patient
record, and the seed runs before there is one — so there is no `patient` in the table above and
there never will be. Enrolling one is the front desk's job and takes half a minute: open a patient
with an email address on file, use the **Portal access** card on their chart, and read out the
one-time password. The username is their MRN, the password is shown once and stored only as a hash,
and the account can do nothing until the patient changes it. Both test suites do exactly that
rather than reaching for a fixture, which means the enrolment path is exercised before anything
else about the portal is.

Consent and health-information exchange add **no account**, deliberately: recording what a patient
decided is the front desk's (`reception`), sending a record under a consent is a clinician's
(`dr.rao`), and exporting a whole chart is an administrator's. A role called something like
"privacy officer" would have been a role that could both record a consent and act on it, which is
the one combination this module is built to prevent.

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
- **Session timeout — two bounds, because one is not a timeout.** A session idle longer than
  `HMS_SESSION_IDLE_TIMEOUT` (30 minutes; 15 for a portal account, which is opened on shared and
  family machines far more often than a clinical workstation is) cannot be refreshed, and neither
  can one older than `HMS_SESSION_MAX_LIFETIME` (7 days) however active it has been. The second
  bound is the one that makes the phrase true: a refresh token's own expiry is re-set on every
  rotation, so without it a session refreshed once a day never ends, and a stolen token that keeps
  being used keeps itself alive. Neither needed a new column — rotation inserts a row and revokes
  the old one, so the current token's `created_at` is the exact moment of last activity and the
  family's first row is the sign-in. Either breach revokes the whole family, and the refusal says
  which bound it hit rather than "invalid username or password": the caller already proved who they
  are, so there is nothing to enumerate and no password to send them off to reset.
- **Brute force** — lockout after 5 failures. Unknown usernames and wrong passwords return
  identical responses, and a decoy hash is verified so response timing does not leak existence.
- **The sign-in page will not redirect off this app.** Signing in after a timeout returns you to the
  screen you were on, which means the sign-in form carries a destination — and a destination that
  arrives in a URL is caller-supplied. `resumePath` treats it as a claim rather than an instruction:
  exactly one leading slash (so `//elsewhere.example` and `/\elsewhere.example`, both of which leave
  this origin, are out), no whitespace or control characters (that value is written into a `Location`
  header, and a newline in it splits the header), the origin re-checked after a parse rather than by
  eye, and `/api/**` refused even though it is local — `next=/api/auth/logout` would make signing in
  sign you straight back out. Anything that fails goes to the dashboard and is never repaired, since
  a value worth repairing is a value somebody built. It matters more here than on most sites: a
  sign-in page on the hospital's own domain that bounces anywhere is a phishing page with the
  hospital's name on it, and the copy it lands on keeps whatever is typed into it.
- <a id="database-roles"></a>**Database roles — DDL and the superuser out of the request path.**
  One role used to do three jobs: install extensions, run every migration, and serve every runtime
  query for all nine schemas. A SQL-injection hole in any one service therefore reached every other
  service's tables, and a Hibernate mapping mistake could drop one. `scripts/db-roles.sql` splits it
  by job:

  | Role | Job | Holds |
  | --- | --- | --- |
  | *(a person, once)* | install `pg_trgm` and `btree_gist` | superuser — runs the script, and never appears in a service's environment |
  | `hms_migrate` | owns every schema, runs every migration | DDL. Flyway's credential, and only Flyway's |
  | `hms_app` | serves every request | `USAGE` on the schemas, `SELECT/INSERT/UPDATE/DELETE` on their tables, `USAGE` on their sequences, **no DDL at all** |

  ```bash
  psql -d hms -f scripts/db-roles.sql \
       -v migrate_password="$HMS_DB_MIGRATION_PASSWORD" -v app_password="$HMS_DB_PASSWORD"
  # then: HMS_DB_USER=hms_app HMS_DB_MIGRATION_USER=hms_migrate, plus the two passwords
  ```

  The script is idempotent, hands over tables an earlier superuser created, and pre-creates the nine
  schemas so `hms_migrate` never needs `CREATE` on the database. `spring.flyway.user` defaults to
  the datasource credential in all nine services, so a deployment that has not run it behaves
  exactly as before.

  **What this does not do**, stated rather than implied: it does not isolate one service's tables
  from another's — all nine share `hms_app`. Per-service runtime roles need nine credentials in nine
  environments, and this platform ships as one compose file with one database; that is in the
  Roadmap as a named gap. CI keeps its superuser deliberately: a job running as `hms_app` would be
  testing a different deployment from the one it builds.
- **PHI at rest** — national id and insurance policy number are AES-256-GCM encrypted, excluded
  from every patient response, and served only by a separately authorised, individually audited
  endpoint.
- **RBAC** — method-level, per endpoint. A receptionist registers patients but cannot chart; a
  nurse records vitals but cannot sign a note; a lab technician enters results but cannot release
  them.
- **Audit** — every service emits audit events; identity persists them. Writes on deliberately
  failing paths (a rejected login, detected token theft) commit in their own transactions, so the
  trail survives the rollback that follows. The report filters by entity, action, actor id, a
  fragment of a username and a date range, and downloads as CSV — with every field escaped per RFC
  4180 *and* any leading `=`, `+`, `-`, `@`, tab or carriage return neutralised, because `detail`
  carries operator-supplied text and a spreadsheet executes those. Quoting does not help: the
  quotes are CSV syntax and are stripped before the spreadsheet reads the first character. The
  export is capped, and says so on its last line when the cap bites.
- <a id="care-team"></a>**A chart belongs to the clinicians looking after the patient.** Until
  S10e, `CHART_READ` was the whole answer — `hasAnyRole('ADMIN','DOCTOR','NURSE','PATHOLOGIST')` —
  so every doctor and every nurse could read every encounter on the platform. A role gate cannot
  express "is this your patient", so nothing did, and a break-glass button bolted onto that would
  have granted access the clinician already had. The narrowing and the override are the same
  mechanism seen from two sides and they shipped together.

  Membership in `scheduling.encounter_care_team`, not a derived rule, because the obvious derived
  rule is a trap: `encounters.clinician_id` is the doctor, a nurse appears in it nowhere, and
  "you are the encounter's clinician" would have locked every nurse out of every chart. So:

  | How you get on a chart | Who |
  | --- | --- |
  | Enrolled when the encounter opens | its clinician, and whoever opened it |
  | Enrolled by recording something on it | obs, a note, a diagnosis, an order set, a care plan |
  | Break-glass — a reason, at least a sentence of it | anybody else, for one shift |

  **Reading is narrowed; providing care enrols you**, and that asymmetry is the design. A symmetric
  rule would have every nurse recording a reason for every patient they were sent to obs, and a
  control everybody trips over every hour is one everybody learns to click through. It also targets
  the risk that exists: "who has been looking at my record" is a question about browsing. The other
  direction — falsifying a clinical record to gain a read — is a graver act than the read it buys,
  permanently attributable, and exactly what the audit trail is for.

  The refusal is **403 with the reason**, the deliberate opposite of the portal's 404: there the
  answer "not yours" would confirm a guessed patient id is real, while here the caller is a
  clinician who can already list patients. Which refusals may explain themselves is decided once,
  server-side — a `@PreAuthorize` failure is still flattened to a sentence that says nothing.

  The break-glass reason is stored on the care-team row, in the clinical schema, and **never** in
  the audit record's `detail`: the platform's own rule is that audit detail carries no clinical free
  text, and "query sepsis, unresponsive" is a clinical observation. The audit row carries the
  action, the encounter and the twelve-hour term.

  **Not narrowed, deliberately:** administrators (narrowing the account that repairs the platform is
  a different decision), the service lines (reporting a specimen, dispensing a drug and running a
  blood count are inherently cross-patient work a care-relationship model does not describe), and
  the *index* of a patient's visits — dates, types and counts, no clinical content. Hiding that four
  earlier visits exist is worse medicine than showing it, and it would break break-glass itself,
  which depends on somebody being able to see there is something to ask for.

  The column this turns on is now validated. `encounters.clinician_id` is a login, checked against
  `staff.user_id` — the only mapping between an account and a person here — before it is written,
  and the client **fails closed**: if the staff directory is unreachable the encounter is refused,
  because an unverified clinician must not reach a record that access depends on.
- **Accounting of disclosures** — every release of a record is written at the moment it leaves,
  never reconstructed from logs, and both the staff register and the patient's own view of it can
  be asked for a period. The patient's view deliberately omits the member of staff who released
  each record: the hospital released it and the hospital answers for it, and naming an individual
  turns an accounting of disclosures into a complaint aimed at a person. Whose accounting it is
  comes from the signed `patient_id` claim, so there is no id in the request to tamper with.
- **Rate limiting** — per client at the gateway, in four buckets that defend against four different
  things. The general one stops a client exhausting the pool; `/auth/**` is far stricter, because
  account lockout stops guessing at one account and this stops one password sprayed across a
  thousand usernames where no single account reaches its threshold; `/public/**` is the loosest,
  because a corridor display polls and what it returns is a room code and some numbers; and
  `/portal/**` is a fifth of the general limit with a counter of its own, because nothing a patient
  does looks like a clinician loading a worklist and a patient on the guest wifi must not be able
  to spend the allowance of every clinician behind the same address.
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
built-in PHI dev key protects nothing and logs a warning), run `scripts/db-roles.sql` and point the services at
`hms_app` and `hms_migrate` rather than the shared superuser, set `HMS_SEED_ENABLED=false`, generate real
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
mvn -q verify                                     # 667 Java unit and integration tests
cd services/ai-service && uv run pytest -q        # 91 Python tests
cd web && npm run lint && npm run typecheck       # web static checks
cd web && npm test                                # 53 web unit tests
cd web && npx playwright test                     # 134 browser tests, no skips
mvn -Pautomation -pl tests/api verify             # 237 API and security abuse cases
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
per minute than any human would — and the browser suite needs the portal bucket raised too, since
it drives eight portal screens as fast as Chromium can render them and the shipped limit is sized
for a person. `make dev-test-stack` starts the stack that way, and both suites
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
- **The read journey reads an encounter chart, and that is there for a control rather than for the
  record.** Since the care-relationship narrowing, every chart read runs a care-team membership
  query before the record is touched — on the busiest read path on the platform. A guard nobody
  measures is a guard nobody notices slowing down, so `chart_read` has its own latency budget and
  CI's smoke profile exercises it on every push. The profile measures a *member's* read, which is
  the read every clinician looking after a patient makes; a non-member's 403 is a correctness
  question and it is asserted in `tests/api`, not in a profile whose thresholds are about latency.

The load profile's last clean run on the development container — 20 reading VUs, 4 bookings a
second, seven minutes — for whatever a shared container's numbers are worth as a baseline:

| Endpoint | p95 | p99 |
| --- | --- | --- |
| Patient read by id | 7 ms | 11 ms |
| Clinician availability | 7 ms | 13 ms |
| Encounter chart read (care-team membership check, then the record) | 11 ms | 17 ms |
| Patient search (trigram) | 11 ms | 17 ms |
| Lab worklist | 18 ms | 27 ms |
| Sign-in (Argon2id) | 55 ms | 78 ms |
| Appointment search | 58 ms | 81 ms |
| Booking (constraint check, event publish, AI call) | 156 ms | 383 ms |

38,023 requests, **0 failures**, 61,197 of 61,197 checks passed, 0 booking conflicts, and 1,469
no-show scores returned — the last of which matters because a score that vanished under load would
mean the circuit breaker had opened.

Two things in that table are worth saying out loud rather than leaving to be inferred. **The
narrowing costs the chart read about four milliseconds**, which is what a keyed lookup on
`(encounter_id, user_id)` should cost and is the number to compare against if it ever moves. And
**appointment search is now the slowest read**, where an earlier baseline had it among the
fastest — the appointments table has grown by every perf run ever made on this container, and a
paged search pays for that in its count. It is still six times inside its budget, so it is recorded
as an observation and not as a defect; a machine whose numbers mean something is where it would be
worth chasing.

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

- **Every error on an SVG endpoint was a 500 with an empty body — if the caller asked properly.**
  Found by hand against the live stack while adding the wristband, and not by any suite: an
  endpoint declaring `produces = "image/svg+xml"` has no acceptable representation for a
  `problem+json` error when the request's `Accept` names only SVG, so writing the error failed
  *during* the write and the container answered a bare 500. Every 400, 404 and 409 on such an
  endpoint, replaced by nothing. It had been true of the specimen label since labels were built.
  Nothing caught it because a browser sends a wildcard in its `Accept` and every test in three
  suites did too: the one caller shape that was broken was the *correct* one. `GlobalExceptionHandler`
  now states the error's own content type, which makes Spring skip negotiation — an error is not a
  negotiable representation, and a caller that cannot parse the reason is still better served by
  the reason than by an empty 500. Pinned by a test that was checked against the unfixed code
  before it was kept.
- **SpotBugs caught a null id that would have been a prescription nobody could cancel.** The
  order-set saga withdraws a prescription when the laboratory step fails, which it can only do if it
  knows the prescription's id — and `RestClient` can return a null body on a 2xx. The code read the
  id straight out of it. A 2xx with no body would have looked like success, the laboratory order
  would have gone ahead, and if *that* failed there would have been a live prescription with no id
  to name. It is a hard failure now, before anything else is raised.
- **A wrong patient id read as a broken platform.** The pharmacy's allergy client fails closed, on
  purpose: if the list cannot be read, the prescription is refused rather than written unchecked.
  The first version could not tell "no such patient" from "service unreachable", so a mistyped id
  answered 500 — telling a prescriber the platform was broken when the fix was in their hands. Only
  the black-box suite could catch it: pharmacy-service's own tests stub that client, because
  patient-service is not running beside them. A 404 from the callee is now the caller's 404 and a
  403 is their 403; everything else still fails closed.

- **The day book counted a day in one zone and dated invoices in another.** A hospital cashes up in
  its own zone, so the day book bounds a day in `hms.billing.zone`; invoices were dated with
  `LocalDate.now()`, which is the JVM's. On a UTC container after half past six in the evening the
  two disagree, and the live stack showed it plainly: a day with eight hundred collected and zero
  billed, over one invoice raised that day. Found by reading real output rather than by a test, and
  every date in the module now comes from one `BillingClock`.
- **A repository projection that compiled, ran, and threw.** The day book's billed-and-count query
  was declared as `Object[]`, which is what a two-column aggregate looks like — and the shape that
  comes back is not the shape it looks like, so the day book answered 500 with an
  `ArrayIndexOutOfBoundsException`. It is a named interface projection now, whose columns the
  compiler checks.
- **A refusal that turned into a 500 because it asked the database a question.** Raising a second
  claim for one invoice hits `uq_claim_per_invoice`, and the handler read the existing claim back so
  the message could name it — inside the transaction the violation had just aborted, where
  PostgreSQL accepts no further statements. The check now runs *before* the insert and the
  constraint stays behind it as the race-condition backstop, with a refusal that names no numbers
  because it cannot read any.
- **Two decimal places survived the database, Java and the wire, and died in the browser.** JSON has
  one number type: the service sends `500.00`, `JSON.parse` hands React the number `500`, and an
  invoice rendered "500" in the total column beside "18.00" in the tax column. Nothing failed and
  nothing logged — just a bill that invites an argument at the counter. Caught by a browser test
  asserting the platform's own figure, and fixed by one formatter that every amount now goes
  through.
- **An entity that dated itself.** `Invoice.invoiceDate` was initialised to `LocalDate.now()` as a
  field default. The constructor always overwrites it, so nothing was wrong yet — and a default like
  that is how the zone bug above gets back in, so it is gone and the NOT NULL column is what fails
  loudly if a caller forgets.
- **A narrow endpoint built for one role was reached for by another, and 403'd the screen that
  needed it.** The consent screens looked a patient up through `GET /patients/identify` — the
  four-field lookup built so a cashier could identify somebody without reading the register. But
  everybody who may read a consent already has the full patient search, and the front desk is not
  in `PATIENT_IDENTIFY`, so the screen answered "No patient matches" for the exact role it exists
  for. Found by a browser test on its first run. The screens use the ordinary search now, and the
  narrow lookup stays narrow.
- **A pick-list silently lost every row past the hundredth.** The staff screen asked for
  `/admin/users?size=200` to fill its "Platform login" dropdown, and the controller answers
  `Math.min(size, 100)` — so the request looked like it asked for everything and got the first
  hundred by username. A browser test that creates one account per run tipped this development
  database past a hundred logins and the test went red; until then the screen had been green for
  weeks and wrong for any hospital with more than a hundred staff. Four pick-lists had the same
  shape. They now page through with `loadAll`, which is bounded rather than trusting the server's
  count.
- **Four booking tests filled the calendar and failed on their own fixture.** Each booked on a
  fixed day offset — +8, +15, +29, +43 — the seeded clinic gives one clinician sixteen slots a day,
  and nothing cleans up. After enough runs against the same database those days were full and the
  suite failed on a missing slot rather than on booking. The charting helper had already learned
  this and walked forward to the next day with a slot; the booking and queue specs now do the same.
  A test that is green until an invisible counter runs out is the worst kind, because the day it
  breaks has nothing to do with the change that is being blamed.
- **A browser test opened a stranger's report and failed on a haemoglobin it had never written.**
  The laboratory spec filtered the worklist to released orders and clicked the first one, which was
  its own for as long as this suite was the only thing releasing reports. The moment another suite
  released one, "the first released order" became a race between them — and its fixture made the
  same mistake in the other direction, asking whether *any* released order existed anywhere before
  deciding it had nothing to provision. So the fixture skipped itself and the spec read somebody
  else's numbers. It went red in CI on a commit that changed neither file. Both halves now name
  this suite's own patient: the fixture asks whether *that* patient has a released order, and the
  spec filters the worklist to *that* MRN. The exact ordering that broke in CI could not be
  reproduced locally — the worklist re-sorts by urgency in the browser and the tie fell the other
  way here — which is itself the argument for the fix: a test whose subject depends on a tie-break
  is a test that will disagree with itself somewhere.
- **A new rate-limit bucket had a property nobody had wired up.** The portal gets its own counter,
  defaulting to 120 requests a minute, and the constructor read
  `hms.rate-limit.portal-requests-per-minute` — a key that appeared in no YAML file, so the
  environment variable the other three buckets use had no equivalent and could not raise it. Nothing
  failed at startup: a `@Value` with a default is a working configuration. It surfaced as a 429 in
  the middle of a browser test, in a form that had rendered "Request failed (429)" exactly as it
  should. The key is in `application.yml` now, beside the other three, and in `.env.example`, the
  `Makefile` and CI beside them as well — which is the actual lesson, since a limit that cannot be
  raised for a test run is a limit somebody disables instead.
- **A record component that could not be set was a dead store, and SpotBugs was right about it.**
  Every message thread carries a standing notice that the portal is not monitored continuously, and
  the intent was that no caller could suppress it or soften the wording. First attempt: a `notice()`
  accessor — never serialised at all, because Jackson writes a record's components and its bean
  getters and that is neither. Second attempt: a component overwritten in the compact constructor —
  serialised correctly, and a parameter whose value is thrown away, which the quality gate reported
  as `IP_PARAMETER_IS_DEAD_BUT_OVERWRITTEN`. `getNotice()` is both: serialised because it is a bean
  getter, and unsettable because it takes no argument.
- **A JPA association that compiled, ran, and could never insert a row.** A message thread owned its
  messages through `@OneToMany @JoinColumn`, which reads more tidily than a back-reference and means
  Hibernate inserts the child with a null foreign key and updates it afterwards — so a `NOT NULL`
  `thread_id` rejected every message ever written, as a 409 that said "the request conflicts with
  existing data". Found by the first test that tried to start a conversation. Owning the association
  on the child is one INSERT carrying the key, and one statement rather than two.
- **The queue suite failed for forty minutes a day and blamed the queue.** The corridor display
  shows today and nothing else — deliberately, since accepting a date would let anybody on the
  internet read the shape of any past clinic. Its tests therefore have to book on the day, and
  they booked eight fifteen-minute appointments into one room, which needs two hours of the day
  left. Run at 23:43 the later fixtures landed on tomorrow's board while today's was being read,
  and the failure read `expected: 5 but was: 1` — a defect message for a clock problem. Three
  things changed: the fixtures are five minutes long, which is the shortest the service accepts,
  so the set needs forty minutes rather than two hours; the whole set is pinned to one instant
  chosen once per test, and the *authenticated* board is read for that day with `?date=`, so a run
  starting after local midnight simply books tomorrow and reads tomorrow; and the three tests of
  the today-only display, which have no such escape, now say out loud that there is not enough of
  today left rather than reporting an empty board as a defect. The same straddle hit two `tests/api`
  journeys, which pass either side of it.

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
- **A revocation that had to survive its own rejection deadlocked the platform against itself.**
  Every refusal on the refresh path revokes tokens in a `REQUIRES_NEW` transaction, because the
  method then throws and a revocation inside the caller's transaction would roll back with the
  rejection — leaving a stolen token usable. Adding the session bounds *after* the presented token
  was marked rotated broke that: the next query flushed the rotation's `UPDATE`, taking a row lock
  the new transaction then waited on forever. PostgreSQL could not break the tie, because the outer
  transaction was not waiting on a lock, it was waiting on the application. The test suite hung for
  half an hour rather than failing, which is how it was found; the fix is to run every check before
  the rotation, and the reason is now written where the split lives.
- **An empty immutable map threw where it was expected to answer null.** The disclosure register
  resolves each row's consent artefact from a batch lookup, and that lookup returns `Map.of()` when
  no row in the page has a consent — which is precisely the case for a patient whose only
  disclosure is their own download, because handing somebody their own record needs no consent.
  `Map.of().get(null)` throws rather than returning null, so the endpoint built to reassure a
  patient answered 500 for exactly the patient with the least to worry about. The service's own
  test used a consented share and passed; the API suite's portal journey exports first, and caught
  it.
- **The audit report's actor filter returned every action nobody did.** The predicate read
  `a.actorId like :actorId or a.actorId is null` — added so an unfiltered report would still show
  rows that carry no actor — and the second clause made every one of those rows match *every*
  actor filter. It is the same defect `UserRepository` documents having fixed one slice earlier,
  in a query written from the same template, and the fix is the same: compare the pattern to `%`
  to say "no filter was supplied" explicitly. It was harmless until this slice and stopped being
  so inside it, which is the interesting part. Attributing a failed sign-in against a username
  nobody holds means writing a row with a genuinely null actor — there is no account to point at —
  and in the development database the old predicate then answered "what has this doctor done" with
  81 of their own actions **plus 16 credential-stuffing attempts against names that do not exist**.
  An audit report that looks authoritative while answering a different question is worse than one
  that is missing.
- **The web app was overruling a decision the platform had already made.** `load()` replaced every
  403's message with "Your role does not have access to this." — reasonable, on the grounds that
  narrating the authorisation model to somebody it has just refused is a poor idea, and wrong,
  because the platform draws that line itself: a `@PreAuthorize` refusal is flattened server-side
  to a sentence that says nothing, while a refusal our own code raises deliberately keeps its
  message. The care-team refusal exists *precisely* to tell a clinician they may open the chart by
  recording why, and the substitution turned a working control into an apparent outage — the
  clinician telephones somebody instead of using the mechanism built for them. Found by the browser
  test written for break-glass, which saw the wall and not the door.
- **A fixture that only ever climbed ran out of building.** `FacilityApiIntegrationTest` picked its
  floor level as the highest in use plus one, which cannot go on: a level is capped at 200 because a
  building has floors rather than an unbounded sequence, and `uq_floor_level` counts retired floors,
  so nothing ever gives one back. After enough runs against the same database the fixture asked for
  201, the platform correctly refused it, and a validation rule working exactly as intended was
  reported as two broken tests. It looks for a gap now. Third of this shape in as many slices — the
  staff-search fixture and the near-midnight slot fixtures were the others — and the common cause is
  a suite that shares a long-lived database with itself.
- **My own first assertion about the audit trail was vacuous.** The API suite checked that a
  break-glass reason does not appear in `/admin/audit` and passed — against an empty list. An audit
  event crosses to identity-service over the event topic, and neither the local stack nor CI runs a
  broker, so a scheduling event never reaches that table at all; identity's own security events are
  persisted by a sink it registers for itself, which is why sign-ins show up and this did not. The
  property is now asserted on the emitted payload, in the service that produces it, where there is
  something to assert against.
- **The dependency gate earned its keep, and its output was unreadable from here.** Tomcat
  11.0.24 — the version Spring Boot 4.0.8 manages — picked up three CRITICAL advisories, all
  authorization bypasses (CVE-2026-65182, CVE-2026-68525, CVE-2026-65905), and the scan went red
  on the exact commit after the last green one with no dependency change between them: the
  vulnerability database had moved, not the code. Diagnosing it took the long way round, because
  the failing step writes SARIF to a file with `TRIVY_QUIET` set and so logs nothing, and this
  development container's egress proxy denies the log blob host, `api.osv.dev`, `nvd.nist.gov` and
  Trivy's own release assets. What was left was the locally generated SBOM — 200 components — and
  the public advisory database. `tomcat.version` is now pinned to 11.0.25, with a note to remove
  the override once a Boot release manages it.
- **Locking down the `public` schema broke a fresh install, and only a fresh install.** The role
  split revokes everything on `public`, because every table on this platform lives in a named schema
  and a writable `public` is where an injected `CREATE TABLE` would go. But `pg_trgm` and
  `btree_gist` install their operator classes there, so patient V1's
  `USING gin (... gin_trgm_ops)` failed with "operator class does not exist for access method gin".
  Invisible on any database that has been running — every index already exists and the migration is
  a no-op — and fatal on an empty one. `USAGE` stays, `CREATE` does not.
- **The portal's downloads were silently redirected away.** Both of them — "Download my record"
  and a released report — go through a route handler in the web app rather than a link at the
  gateway, because the bearer token is in an httpOnly cookie the browser will not attach to a
  cross-origin link. The middleware's patient-path exemption listed `/portal/**` and not
  `/api/portal/**`, so a patient clicking Download was redirected to the portal home. A redirect is
  a 200: nothing errored, no log line appeared, and the file simply never arrived. Found by a
  browser test written for the disclosure register, which noticed that downloading had left no
  trace in it — the register was the instrument that caught it, which is the argument for having
  built it.
- **Nobody could be told who signed in.** `AuditService` reads the actor off the security
  context, and every row an auditor asks about first — who signed in, whose sign-in failed, which
  account was locked out, whose session was burned for a replayed token — is written *before* there
  is a session to read. So all of them recorded `system` and the all-zero actor id: **5,262 of
  5,746 rows** in the development database, including every single sign-in. The report's headline
  filter was useless for exactly the actions it exists to answer questions about. Found by a
  browser test written to exercise that filter, which came back empty. The callers on those paths
  always knew whose account it was, and there is now a `recordAs` for saying so.
- **A refresh token replayed after rotation — the one event on that path that looks like theft — was
  logged and never audited.** It burned the whole token family and wrote a `WARN`, so the platform
  reacted correctly and the audit report, which is where anyone would go looking, showed nothing.
- **The k6 slot allocation itself was wrong**, and its own hard threshold caught it: 1,229
  self-inflicted booking conflicts in one 20-VU run, because `(VU % 30, ITER % 12)` stops being
  injective the moment k6 hands out VU ids above 30.

---

## Roadmap

**Implemented and verified against a running stack:** clinical core, laboratory with analyzer
integration, casualty and the in-patient census, the closed medication loop, the revenue cycle
(GST invoicing, payments, payer claims and event-driven charge capture), consent-gated health-
information exchange with FHIR R4 bundles and an EHI export, outbound messaging, AI service, web
UI, containerisation, TLS, the full test pyramid,
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
- **Further clinical modules** — imaging/PACS, and an HL7 v2 interface engine. Both are named
  gaps rather than half-built modules, which was the choice made deliberately.
- **The care-team narrowing covers the encounter chart, and clinicians.** Laboratory orders,
  prescriptions, admissions and the casualty board are still role-gated, as are administrators. The
  encounter chart is where the narrowing bites hardest and where the deciding column is local to the
  service that owns it; extending it means either a shared care-relationship service or the same
  table in four more schemas, and that is a decision rather than a chore.
- **A nurse has no ward assignment to be read from.** Nurses join a chart by providing care on it,
  which works and is honest, but the natural rule — "you are on this ward tonight" — needs a shift
  or assignment model the platform does not have. With one, the ward round would enrol nobody by
  hand and break-glass would be rarer still.
- **One runtime database role, not nine.** `scripts/db-roles.sql` takes DDL and the superuser out
  of the request path, which is the larger half, and stops there: all nine services share `hms_app`,
  so a SQL-injection hole in one still reaches another's tables. Per-service roles need nine
  credentials in nine environments and this platform ships as one compose file with one database.
- **A timed-out session resumes onto the page, not into the work.** Signing in again returns you to
  the screen the timeout interrupted, filters and all — but a half-written note or a part-filled
  form is gone, because nothing on the platform holds a draft. Keeping one is a different feature
  from resuming a location, and it would need somewhere to put clinical text that has not been
  signed.
- **ABDM certification.** The platform is integration-ready and **uncertified**, and the gap is
  not a formality: M1/M2/M3 certification needs NHA sandbox credentials and an assessment, and the
  real data flow is a consent manager, a callback, an encrypted payload with a key exchange and an
  assessed HIP. What exists is the consent artefact, the gate that cannot be bypassed, the bundles,
  the disclosure register, and an HTTP adapter with somewhere to put that protocol. The default
  adapter reports `transmitted: false` precisely so nobody can mistake the state for compliance.
- **Nothing has been through an R4 validator.** The bundles are hand-built and structurally
  asserted in unit tests; conformance to the specification's profiles is unproven. A deployment
  that has to demonstrate it should validate the output.
- **A consent request does not reach the patient.** The front desk records what the patient
  decided, in front of them. The portal is built now, and this is still a gap: there is no consent
  manager integration and no portal screen on which a patient approves or refuses a request. The
  portal does now show them what has already left — every release, to whom, under which consent —
  which is the accounting of disclosures. What it cannot show them is a request still waiting on an
  answer, because nothing puts one in front of them to answer.
- **The portal takes no money.** Bills, lines, tax and what is still owed are all there, and there
  is no Pay-now button, because taking a payment needs a gateway with a merchant account and live
  credentials. A button that settled an invoice without receiving anything would balance the day
  book against money that does not exist, and it would be found at the month end by somebody unable
  to tell which of the two records was wrong.
- **No published clinician directory for self-booking.** The portal's booking form offers the
  clinicians this patient has already seen, because that list is on their own record. A patient
  choosing somebody new needs a directory of who runs which clinic, which the platform does not
  have — inventing one here would mean the portal listing clinicians the appointment book does not.
- **No repeat-prescription request.** The portal shows what has been prescribed and offers no way
  to ask for more of it. A repeat is a request to a prescriber rather than a prescription, and
  building it as one inside the medication loop would be building the wrong thing in the most
  dangerous module on the platform.
- **A patient cannot correct their own record.** They can read their demographics and their allergy
  list and are asked to tell the front desk when something is wrong. A self-service edit needs a
  review step somebody owns, and an allergy list a patient could edit unreviewed is the list that
  refuses a prescription.
- **Four of the seven information types have no bundle.** Discharge summary, immunisation record,
  health document and wellness record are in the consent vocabulary because ABDM's is, and a
  consent may legitimately cover them; sharing one is refused with a message saying the platform
  cannot build it. That is better than an empty document, and it is a gap: the platform records no
  immunisations, holds no documents, and has no portal for a patient to record wellness data in.
- **Matching a patient by ABHA number.** Stored encrypted with a randomised IV, so it cannot be
  looked up or compared — ABDM linking will need a deterministic hash column, and the migration
  says so rather than pretending a UNIQUE constraint would help.
- **A transitions-of-care document.** A referral can carry an OP-consultation bundle; there is no
  Composition typed as a transfer summary, no receiving side, and no reconciliation of what came
  back.
- **NHCX claim submission.** `billing.claims` holds what was claimed and settled, and nothing
  submits it to a health-claims exchange. The claim rows carry what such a submission needs, which
  is why they exist in that shape.
- **A screen for composing an order set.** The endpoint exists and is administrator-only; there is
  no form, because what goes into a set is a clinical governance decision rather than a data-entry
  task — a set is applied in one click by anybody who may chart — and a form with no review step in
  front of it would be the wrong control. The reference list at `/order-sets` says so on the page.
- **Care plans do not follow a patient between visits.** One plan per encounter, by design; a
  long-term plan spanning admissions would need a different aggregate and a different owner, and
  guessing at one now would produce a table nobody could reconcile with the per-visit plans already
  written.
- **In-patient care beyond the bed.** admissions-service records where a patient is and how they
  got there; it does not hold a ward round, a nursing care plan, a fluid balance chart or a
  discharge-summary document. A discharge takes a free-text summary and that is all — enough to say
  what happened, not a structured transfer-of-care document. Observations and NEWS2 stay in
  scheduling-service against the encounter, so a ward view reads a score rather than owning one.
- **Bed-days are charged at discharge, not nightly.** billing-service consumes
  `admission.discharged` and prices the bed-day count the event carries, so a stay that has not
  ended yet costs nothing yet. One event and one idempotent charge rather than a nightly job with a
  clock to be wrong about — but it does mean an in-patient's bill cannot be shown mid-stay, which a
  hospital taking interim payments would want.
- **A cash-up.** The day book totals what was billed and collected and splits collections by
  method, and nothing signs it off: there is no drawer count, no shift close, no till
  reconciliation and no variance record. The numbers are readable and unsigned, which is named
  here rather than implied to be a control.
- **Credit notes and refunds.** An invoice with money against it cannot be cancelled, and the
  platform has no way to give money back. That is the honest state: a cancellation standing in for
  a refund would make the record say a treatment was never billed while the cash was in the drawer.
- **Receivables ageing.** The day book answers what is outstanding as of a date; nothing buckets it
  by how long it has been owed or by payer, which is the report a hospital chases money from.
- **A dispensed medicine is priced by the charge list, not by the pharmacy.** Charge capture bills
  a drug's own code when the charge list carries one and falls back to the configured dispensing
  item when it does not, so until a deployment prices its drug codes a dispense posts a
  zero-value line naming the medicine. Visible on the invoice rather than absent from it, and a
  copy of the pharmacy's prices into billing is the missing piece rather than a guess at them.
- **Charges captured from events price at list.** An event carries no payer, so an invoice opened
  by charge capture is self-paying; a payer's tariff applies to invoices a cashier raises. Pricing
  a captured charge against a payer needs the encounter's payer to travel on the clinical event,
  which is a change to services that deliberately know nothing about billing.
- **A controlled-drug register.** `formulary.controlled` is recorded and not enforced: there is no
  register, no witnessed-destruction record and no running balance reconciliation. The flag is
  honest about being a label rather than a control, which is better than a column implying one.
- **Stock adjustment and write-off.** An expired batch stays visible with its quantity until
  somebody adjusts it, and nothing in the platform can adjust it — there is no destruction record,
  no stock take and no return-to-supplier. Dispensing it is already impossible, so the gap is an
  accounting one rather than a safety one, and it is named rather than papered over with a delete.
- **A colour band for an allergy, and a band printed in colour at all.** The wristband is
  monochrome, and it deliberately carries no allergy marker: a red band is a real convention, this
  platform cannot guarantee the printer, and a monochrome "ALLERGY" line that is sometimes there
  and sometimes not teaches staff to read a band for the *absence* of a warning — the one thing a
  wristband must never be trusted for. The allergy check that refuses a prescription is
  server-side, where it cannot be smudged.
- **Drug-class cross-sensitivity beyond what is recorded.** A class allergy works by the class
  being named as an ingredient on each product, which is deliberate and explained in the migration
  — but it means a class the formulary does not mark is a class the checker does not know. There is
  no ontology and no external drug database behind it; a deployment's pharmacist is expected to
  review the seeded ingredient lists.
- **Dose calculation, paediatric or renal.** Every dose is free text as the prescriber wrote it.
  There is no mg/kg arithmetic, no maximum-dose check and no renal adjustment, so the platform
  cannot catch a decimal-point error in a dose the way it catches an allergy — and pretending
  otherwise with a units field would be worse than the honest gap.
- **Casualty triage from the AI service.** `POST /ai/triage` already returns an acuity and the
  arrival form does not offer it. The acuity on the board is a person's judgement, typed by the
  nurse who saw the patient, and wiring a suggestion into that field is a decision about how much
  a model should influence the number that decides who is seen first — worth making deliberately,
  with the assessment shown beside the field rather than filled into it.
- **NEWS2 Scale 2.** The alternative SpO2 scale, for patients with a prescribed target range of
  88–92%, is not implemented: using it requires a documented prescription for that target and the
  platform records no such prescription. Scoring a patient with chronic hypoxaemic respiratory
  failure on Scale 1 over-reads rather than under-reads, which is the safer of the two errors, and
  the gap is named here rather than papered over with a guess.
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
- **HL7 v2** — nothing here speaks it, and an interface engine stays a named gap rather than a
  half-built module. FHIR R4 is spoken now, one direction: interop-service composes bundles and
  exports them, and nothing *receives* one — there is no FHIR endpoint, no `$import`, and no
  reconciliation of an incoming record against a local one.

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
