-- NEWS2: the supplemental-oxygen flag, and the escalation policy.
--
-- The platform captured vitals and scored nothing, which left a deteriorating ward patient visible
-- only to whoever happened to read the numbers and compare them to the last set. NEWS2 is the
-- standard answer, it is deterministic, and everything it needs was already recorded -- except one
-- thing.

-- Whether the patient is on any supplemental oxygen.
--
-- Not a detail. NEWS2 scores 2 for supplemental oxygen of any kind, which is a large share of the
-- score for a lot of ward patients, and it cannot be inferred from a saturation reading: 96% on
-- four litres is a very different patient from 96% on air. Without this column the score
-- under-read by 2 for everybody on oxygen, and under-reading is the direction that gets missed.
--
-- Defaults false because that is the ordinary case and because every row that already exists was
-- recorded before anybody was asked. A deployment that cares about the difference on historical
-- rows has to say so; guessing would put a number on a chart that nobody measured.
-- The table is `vitals`, not `vitals_records` — the entity is VitalsRecord and the table is
-- not, which is worth the note because the first draft of this migration guessed from the class
-- name and Flyway answered `relation "vitals_records" does not exist`.
ALTER TABLE vitals
    ADD COLUMN on_supplemental_oxygen boolean NOT NULL DEFAULT false;

-- What a score means locally.
--
-- The NEWS2 *cut-offs* are not in this table and will not be: NEWS2 is a national standard whose
-- whole value is that a score of 6 means the same thing in every hospital using it, so a
-- deployment able to edit the bands could produce a number it calls NEWS2 which is not NEWS2 --
-- a wrong answer carrying the authority of a standard. Those live in News2Calculator, in code, and
-- docs/extensibility.md records why.
--
-- What every trust genuinely does decide for itself is the response: who is called, how quickly,
-- and how often observations are repeated. That is local policy, it differs between a district
-- general and a tertiary centre, and it is what belongs in rows.
CREATE TABLE escalation_policies (
    id                uuid         PRIMARY KEY,
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    -- NONE, LOW, LOW_MEDIUM, MEDIUM, HIGH -- the published bands, which the calculator decides.
    band              varchar(16)  NOT NULL UNIQUE,
    -- How often observations are repeated at this band.
    monitoring        varchar(120) NOT NULL,
    -- Who responds, in the hospital's own words.
    response          varchar(400) NOT NULL,
    -- Where they are looked after.
    setting           varchar(200) NOT NULL,
    CONSTRAINT chk_escalation_band
        CHECK (band IN ('NONE', 'LOW', 'LOW_MEDIUM', 'MEDIUM', 'HIGH'))
);

-- The Royal College of Physicians' published response, as the default. A hospital edits these; it
-- does not edit what produces the band.
INSERT INTO escalation_policies (id, band, monitoring, response, setting) VALUES
    ('55555555-0000-4000-8000-000000000001', 'NONE',
     'Minimum every 12 hours',
     'Continue routine monitoring.',
     'Ward'),
    ('55555555-0000-4000-8000-000000000002', 'LOW',
     'Minimum every 4 to 6 hours',
     'Assessment by a registered nurse, who decides whether the frequency of monitoring or the '
     || 'level of care should change.',
     'Ward'),
    ('55555555-0000-4000-8000-000000000003', 'LOW_MEDIUM',
     'Minimum every hour',
     'Urgent review by a clinician competent to assess acutely ill patients. A single parameter '
     || 'scoring 3 escalates on its own, whatever the total.',
     'Ward'),
    ('55555555-0000-4000-8000-000000000004', 'MEDIUM',
     'Minimum every hour',
     'Urgent review by a clinician competent to assess acutely ill patients, with a decision on '
     || 'whether higher-level care is needed.',
     'Ward, with critical care advice available'),
    ('55555555-0000-4000-8000-000000000005', 'HIGH',
     'Continuous monitoring of vital signs',
     'Emergency assessment by a team with critical care competencies, including airway skills. '
     || 'Usually transfer to a higher level of care.',
     'Higher-level or critical care');
