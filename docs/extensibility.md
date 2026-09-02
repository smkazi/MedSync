# What is configurable, what is code, and why

MedSync is meant to run in more than one hospital. Buildings differ, clinics differ, formularies
differ. So the default answer to "can this be changed without a deployment?" should be yes.

But not everything. A taxonomy is safe to make configurable when **adding a member requires no new
behaviour**. When each member carries behaviour — a state transition, a safety rule, a parser — a
configurable list is worse than a hard-coded one, because someone will add a value and the system
will silently do nothing with it.

This page records which side of that line each vocabulary in the platform falls on, so the next
person can tell a decision from an oversight.

---

## Configurable: rows, not code

Change these through the API or a migration. No recompile, no deployment. Every one of them is
editable from a browser as well, by an administrator — a vocabulary that can only be extended by
someone with `curl` is configurable in principle and fixed in practice.

**Nothing here is deleted; it is retired.** Setting `active` false takes a department, room, bed or
staff record out of the pick-lists and leaves every row that references it intact, because the
encounters recorded under a department and the admissions that happened in a bed are still real.
For the same reason a *code* is never editable once created: a department code is stored by three
services and a room code is cached on appointments, and none of them would learn it had changed.

| Vocabulary | Where | Extended by |
| --- | --- | --- |
| **Room types** | `patient.room_types` | `POST /room-types` |
| Floors, rooms, bed positions | `patient.floors`, `.rooms`, `.beds` | `POST` / `PATCH` `/floors`, `/rooms`, `/rooms/{code}/beds` and `/beds/{id}` |
| Departments | `patient.departments` | `POST /departments`, `PATCH /departments/{code}` |
| Staff, designations, specialties | `patient.staff` | `POST /staff`, `PATCH /staff/{id}` |
| Laboratory test catalogue | `laboratory.lab_test_catalog` | migration or admin |
| Reference ranges | `laboratory.reference_ranges` | `PATCH /lab/reference-ranges/{id}` |
| **Interpretive rules** and their ANDed conditions | `laboratory.interpretive_rules`, `.interpretive_rule_conditions` | `PATCH /lab/interpretive-rules/{code}` |
| Histogram flag explanations | `laboratory.histogram_flag_notes` | migration or admin |
| Parameter unit scales | `laboratory.parameter_scales` | migration or admin |
| Morphology cut-offs | `laboratory.morphology_thresholds` | `PATCH /lab/morphology-thresholds/{code}` (the number, not the note) |
| Analyzers | `laboratory.analyzers` | migration or admin |
| Clinician schedules and blackouts | `scheduling.clinician_schedules`, `.schedule_blackouts` | `POST /schedules` |
| **NEWS2 escalation policy** | `scheduling.escalation_policies` | `PATCH /escalation-policies/{id}` — the response to a band, never the band itself |
| **Message wording** | `notification.message_templates` | `PATCH /notifications/templates/{id}` — with one limit, below |
| ICD-10 subset | `services/ai-service/data/icd10_subset.json` | replace the file |

### Three tiers of threshold, and why none of them collapses into another

The laboratory now holds three separate sets of numbers about the same parameter, and the temptation
to merge them should be resisted every time it comes up:

| Tier | Table | Question it answers | Haemoglobin, female |
| --- | --- | --- | --- |
| Reference interval | `reference_ranges` | Is this value outside normal? | flags `L` below 11.5 g/dL |
| Interpretive threshold | `interpretive_rule_conditions` | Does it need saying out loud on the report? | comments below 9.0 g/dL |
| Morphology cut-off | `morphology_thresholds` | What do the cells get called? | MCV < 76 reads "microcytic" |

A report that printed a paragraph for every out-of-range number is a report nobody reads, so the
middle tier is deliberately wider than the first. And a red cell is called microcytic below MCV 76
while the microcytosis *comment* fires below 70 and the reference interval starts at 80 — three
numbers, three purposes.

All three tiers are now retunable at runtime, each by its own endpoint, and each write is audited.
The morphology tier was the last to become writable: two of three being editable and the third
migration-only meant the number deciding whether a blood film reads "microcytic" was changeable by
nobody. What stays read-only there is the **note** — the words the cells get called — because it
appears verbatim on a signed report, and retuning a number is a different act from rewording what a
pathologist has already put their name to.

`LabApiIntegrationTest.twoTiersAreDistinct` pins the first two apart: a haemoglobin of 10.8 g/dL
must flag `L` and must **not** produce a narrative.

### Message wording is configuration; the values it may interpolate are not

The words a patient reads are rows: a hospital rewrites them, translates them, and the legal team
has opinions about them. What a template may *interpolate* is a closed set two entries wide —
`{portalUrl}` and `{when}` — and `MessageComposer` refuses anything else, both when a template is
saved and again when it is rendered.

That refusal is where the platform's no-PHI-in-outbound-messages rule actually lives. Without it the
rule would be prose, and prose erodes: adding `{value}` to a template would be enough to put a
laboratory result into an SMS, and nobody would have written a line of code to do it. A phone number
is often stale, is frequently shared within a family, and SMS is plaintext to the handset — so a
message says that something is ready and where to sign in, and never what it says.

`{when}` is allowed because a date is not a clinical finding: somebody reading a shared handset
learns that this person has an appointment, which the message's existence already told them, and not
what it is for or who it is with. Adding a third placeholder is the one change that could break the
rule, so it should not happen without deciding in writing that the new value is not a clinical fact.

The same reasoning makes a released-report message identical whether the report is entirely normal
or entirely not. A notification whose *existence* implied bad news would be as much of a disclosure
as one that said so.

### `parameter_scales` is the one that fails silently

An analyzer may transmit WBC as `7.36` (×10³/µL) or `7360` (absolute /µL) depending on model and
configuration. A threshold written against one scale never fires against the other, and it fails
with no error at all — just a comment that stops appearing. Verified live: a WBC of `1200 /µL`
normalises to 1.2 and reports **Leucopenia**; without the scale row it reads as 1200 and reports
Leucocytosis, which is the exact clinical inversion the table exists to prevent.

### Room types are the worked example

Room types started as a Java enum plus a `CHECK` constraint, with the behaviour each type implied
living in a `switch` in `FacilityService`. Adding a dialysis unit to a hospital that has one would
have meant a new enum constant, a recompile, a redeploy, a migration to widen the constraint — and
an edit to the very method that consumes the taxonomy. That last part is the Open/Closed Principle
violated in the most literal way available.

They are now rows in `patient.room_types`, and the behaviour is **columns on those rows**:

| Flag | Question it answers |
| --- | --- |
| `clinical` | Are patients seen or treated here? Governs beds and clinical filters. |
| `bed_allocated` | Is space handed out as a bed rather than a calendar slot? |
| `schedulable` | May rooms of this type carry appointments? |

Three flags rather than one enum, because the three questions are independent: a casualty bay is
clinical and bed-allocated and never schedulable. The combinations that would misbehave —
schedulable-and-bed-allocated would let a booked outpatient be sent to a resuscitation position —
are refused by `CHECK` constraints on the table, not by whoever edits the row next.

`FacilityService.validate` now asks the type what it is instead of naming four constants, so a new
type configured with `bed_allocated = true` is governed by the same rule the day it is inserted.
`FacilityApiIntegrationTest.aNewRoomTypeWorksImmediately` asserts exactly that, end to end.

---

## Code, deliberately

Each of these carries behaviour. Making them configurable would let someone add a value the system
cannot act on.

| Vocabulary | Why it stays in code |
| --- | --- |
| `AppointmentStatus` | Drives a state machine — `checkIn()`, `begin()`, `complete()` — and the double-booking exclusion constraint names `CANCELLED` and `NO_SHOW` in its `WHERE` clause. A new status needs a transition and probably a migration. |
| `EncounterStatus` | `OPEN`/`CLOSED` gates note signing and appointment completion. |
| `AllergySeverity` | `SEVERE` and `LIFE_THREATENING` will refuse a dispense outright (pharmacy, planned). A configurable list means someone adds "VERY SEVERE" and it silently stops blocking. This one is a patient-safety rule wearing a taxonomy's clothes. |
| `OrderStatus`, `ResultStatus` | Encode separation of duties: a lab technician enters, a pathologist releases. The permitted transitions are the control. |
| `Protocol` (`ASTM`, `KDPS`) | Each value maps to a parser class. A configured value with no parser behind it is a runtime failure at the worst moment — an analyzer transmitting a real sample. |
| `NotificationCategory` | Each value is the key to a template *and* the thing a caller is allowed to choose instead of writing text. A configurable list would let somebody add a category with no wording behind it, which the platform records as "no active template" rather than sending — visible, but still a message a patient did not get. |
| **NEWS2 cut-offs** | The one place this page argues *against* a table. NEWS2 is a national standard, and its whole value is that a score of 6 means the same thing in every hospital using it — so a deployment able to edit the bands could publish a number it calls NEWS2 which is not NEWS2, a wrong answer carrying the authority of a standard. The score lives in `News2Calculator`, tested against the Royal College of Physicians' published chart. What *is* local, and what is therefore a table, is the escalation policy: who responds, how fast, how often observations repeat. A district general and a tertiary centre answer that differently and both are right. |
| `TokenStatus` | Three states, and they mirror the appointment's: WAITING at check-in, CALLED when the consultation starts, DONE when it ends. There is deliberately no SKIPPED — a patient who does not answer their number is a no-show on the *appointment*, which that state machine already records, and a second place to say the same thing is a second place for the two to disagree. |
| `NotificationChannel` | Each value maps to an adapter. A configured channel with no adapter is a message that silently becomes a log line. The *presence* of an adapter is configuration — no mail host means no email channel — and `GET /notifications/capabilities` reports which really exist so a screen does not offer one that does not. |
| Roles | Named in `@PreAuthorize` expressions, which are compiled. The `identity.roles` table is data, but granting a role a capability is a code change. |
| The navigation menu (`web/src/lib/menu.ts`) | A menu item needs a route, a page and a role gate that matches what the API enforces. All three are code, so a configurable menu would let somebody add an item that leads nowhere — or, worse, one whose role list is more generous than the `@PreAuthorize` behind it, which reads as a permission and is not one. It is *data* in the sense that it is one list rather than markup scattered through a layout; it is not configuration. |

### Two of these are open questions, not settled answers

- **`Priority`** (`ROUTINE`, `URGENT`, `STAT`) currently only orders and labels. It is the strongest
  candidate to become configuration. It is not yet, because the casualty queue is about to order by
  triage acuity and the interaction between the two needs settling first.
- **`Sex`** is used to pick sex-specific reference ranges, which is a clinical variable. A serious
  deployment needs administrative gender recorded separately from the sex used for interpretation,
  and the platform currently has one field doing both jobs. That is a real gap, named here rather
  than papered over: it is not a case for making the enum configurable, it is a case for a second
  field.

---

## The rule for new work

Before adding an enum, ask: **if a hospital added a fifteenth member of this list tomorrow, would
any code have to change?**

- **No** → reference table, with any behaviour as columns, plus a `CHECK` constraint for the
  combinations that cannot be allowed to exist.
- **Yes** → enum, and write down here what the behaviour is.

Either way it belongs in this table, so the choice is visible.
