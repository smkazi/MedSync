-- The schedule: when each antigen is expected, as rows.
--
-- V1 said "nothing here computes due" and pointed at this file for the rows a calculator reads.
-- These are those rows. Four decisions shape them, and each one is a constraint rather than a
-- convention:
--
--   1. EVERYTHING IS DAYS FROM DATE OF BIRTH. Not weeks, not months. "10 weeks" is exactly 70 days
--      and "2 months" is 59, 60, 61 or 62 depending on which two months, so a schedule written in
--      months is a schedule that is up to four days wrong -- for every child in the district, in
--      the same direction, silently. Days are unambiguous, they subtract, and the arithmetic on
--      them has no calendar in it.
--   2. NO ZONE APPEARS ANYWHERE. An interval is a difference between two dates. This is what lets
--      ImmunisationScheduleCalculator take an `asAt` date rather than read a clock, which is what
--      lets it be tested against a published chart and answer "what was due on the first".
--   3. AN INTERVAL EXISTS EXACTLY WHEN THERE IS A PREVIOUS DOSE TO MEASURE IT FROM. A biconditional,
--      for the reason chk_lot_iff_given_here is one. An interval on dose 1 is measured from nothing;
--      a second dose with no minimum interval is how two doses get given on one afternoon and
--      counted as two.
--   4. THE SCHEDULE IS BOUNDED BY AGE. A schedule claiming to apply to everybody produces a due
--      list for a sixty-year-old made of doses it has no rows for.
--
-- Both tables are configuration in docs/extensibility.md's sense: adding a dose, retuning an age or
-- widening a grace period needs no new behaviour. What is NOT configuration is what the columns
-- mean -- and IMMUNISATION_CONFIG is administrator-only because editing one row here moves the due
-- date for every child in the district at once.


-- ---------------------------------------------------------------------------
-- A published schedule.
-- ---------------------------------------------------------------------------
CREATE TABLE immunisation_schedules (
    id                   uuid         PRIMARY KEY,
    code                 varchar(32)  NOT NULL UNIQUE,
    name                 varchar(160) NOT NULL,

    -- Who this schedule is about, in days. A national childhood schedule has nothing to say about
    -- an adult, and a due list that offered one a pentavalent dose would be worse than no answer:
    -- it would be an answer, in the same table, in the same colour, as the ones that are right.
    applies_from_age_days integer     NOT NULL DEFAULT 0,
    applies_to_age_days   integer     NOT NULL,

    -- The document these rows were read off. Stored rather than assumed, for the reason a measure
    -- stores its specification version: a due date somebody rings a family about should be
    -- traceable to the thing that says it, and "the schedule" is not a citation.
    source               varchar(255) NOT NULL,

    active               boolean      NOT NULL DEFAULT true,
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_schedule_age_bounds
        CHECK (applies_from_age_days >= 0 AND applies_to_age_days > applies_from_age_days)
);


-- ---------------------------------------------------------------------------
-- One expected dose of one antigen.
-- ---------------------------------------------------------------------------
--
-- Keyed on the ANTIGEN and not the product, which is V1's first rule read from the other end: a
-- schedule says a child needs three doses of protection against Hib, and whether those arrive as
-- Hib vaccine or inside a pentavalent vial is a question about what is in the fridge. A schedule
-- written in products would have to be rewritten the week a clinic switched brand, and every dose
-- already recorded would stop counting.
CREATE TABLE schedule_doses (
    id                uuid        PRIMARY KEY,
    schedule_code     varchar(32) NOT NULL REFERENCES immunisation_schedules (code) ON DELETE CASCADE,
    antigen_code      varchar(32) NOT NULL REFERENCES antigens (code),
    dose_number       integer     NOT NULL,

    -- What a vaccination card calls it: "at birth", "6 weeks", "booster at 16-24 months". Shown to
    -- a parent and to the nurse on the phone, who do not think in days even though the arithmetic
    -- must. Not parsed by anything.
    label             varchar(64) NOT NULL,

    -- The earliest age at which this dose is VALID. A dose given before it does not count and does
    -- not advance the series -- an immune system does not respond to a vaccine given too early, so
    -- counting one would record protection the child does not have.
    min_age_days      integer     NOT NULL,
    -- The age at which it is EXPECTED. Separate from the minimum because they are different
    -- questions: one decides whether a dose that happened counts, the other decides whether to
    -- telephone. They are equal for most rows and must not be assumed equal by anything.
    due_age_days      integer     NOT NULL,

    -- Days that must pass since the previous counted dose of this antigen. NULL on dose 1 and
    -- required on every other, enforced below.
    min_interval_days integer,

    -- How long after the due date this stays DUE before it reads OVERDUE. A judgement, and it is a
    -- column because it is a local one: a clinic that calls at a fortnight and one that calls at
    -- three months are both running the same schedule. Hard-coding it in Java would make "overdue"
    -- a number nobody in the building could change.
    grace_days        integer     NOT NULL,

    -- The age after which this dose is no longer given at all -- a hepatitis B birth dose, an
    -- oral polio zero dose, a rotavirus course that must be started before a certain age. NULL
    -- means the window never closes. A closed window is reported rather than dropped: "this child
    -- never had the birth dose and never will" is a fact somebody should be able to see.
    max_age_days      integer,

    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_schedule_dose UNIQUE (schedule_code, antigen_code, dose_number),
    CONSTRAINT chk_dose_number CHECK (dose_number >= 1),

    -- An interval exists exactly when there is a dose to measure it from.
    --
    -- A biconditional, and both halves earn their place. Without the forward half a second dose
    -- could carry no minimum interval, which is how two doses get given on one afternoon and
    -- counted as two. Without the reverse half dose 1 could carry an interval measured from
    -- nothing, and every reader would have to guess what it was relative to.
    CONSTRAINT chk_interval_iff_not_first
        CHECK ((dose_number > 1) = (min_interval_days IS NOT NULL)),

    CONSTRAINT chk_interval_positive CHECK (min_interval_days IS NULL OR min_interval_days > 0),
    CONSTRAINT chk_ages_not_negative CHECK (min_age_days >= 0 AND grace_days >= 0),
    CONSTRAINT chk_due_not_before_valid CHECK (due_age_days >= min_age_days),
    CONSTRAINT chk_window_closes_after_due
        CHECK (max_age_days IS NULL OR max_age_days >= due_age_days)
);

-- The read the due list makes: one schedule, in antigen and dose order, for every child at once.
CREATE INDEX idx_schedule_dose_read ON schedule_doses (schedule_code, antigen_code, dose_number);


-- ---------------------------------------------------------------------------
-- The schedule, seeded.
-- ---------------------------------------------------------------------------
--
-- Seeded because a schedule table with no schedule in it produces an empty due list, and an empty
-- due list reads exactly like a healthy population -- a wrong answer that looks like good news,
-- which is the kind nobody checks.
--
-- These rows are read off India's Universal Immunization Programme schedule and converted to days
-- once, here, rather than in code: 6 weeks is 42, 10 weeks is 70, 14 weeks is 98, 9 months is 270,
-- 16-24 months is taken at 480, 5-6 years at 1826. A deployment running a different national
-- schedule inserts its own rows and changes no code.
INSERT INTO immunisation_schedules
    (id, code, name, applies_from_age_days, applies_to_age_days, source) VALUES
    ('77777777-2000-4000-8000-000000000001', 'UIP-2024', 'Universal Immunization Programme', 0, 2192,
     'National immunisation schedule for infants, children and pregnant women, as published for 2024');

-- Deliberately no fixed ids on these, unlike the catalogue in V1: nothing names a schedule row.
-- The catalogue rows carry them because a later migration and a test both have to reference an
-- antigen by id; a dose row is only ever read as part of its schedule.
--
-- Vitamin A is deliberately absent. It is in the same national programme and it is not a vaccine:
-- nine six-monthly doses of a supplement have no antigen to be covered for, and putting them here
-- would make every coverage question answer about something that is not immunity. Named in the
-- README rather than half-modelled.
INSERT INTO schedule_doses (id, schedule_code, antigen_code, dose_number, label,
                            min_age_days, due_age_days, min_interval_days, grace_days, max_age_days)
VALUES
    -- At birth. Three doses with windows that close: a birth dose given at eight months is not a
    -- birth dose, and the register should say the window shut rather than call the family in.
    (gen_random_uuid(), 'UIP-2024', 'BCG',  1, 'At birth',            0,   0,    NULL, 60,  365),
    (gen_random_uuid(), 'UIP-2024', 'HEPB', 1, 'Birth dose',          0,   0,    NULL, 7,   14),
    (gen_random_uuid(), 'UIP-2024', 'OPV',  1, 'Zero dose at birth',  0,   0,    NULL, 14,  28),

    -- 6, 10 and 14 weeks. The pentavalent series delivers four of these five antigens; the fifth,
    -- hepatitis B, continues the series the birth dose began.
    (gen_random_uuid(), 'UIP-2024', 'OPV',  2, '6 weeks',             42,  42,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'OPV',  3, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'OPV',  4, '14 weeks',            98,  98,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'OPV',  5, 'Booster, 16-24 months', 480, 480, 28,  180, NULL),

    (gen_random_uuid(), 'UIP-2024', 'DIPH', 1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'DIPH', 2, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'DIPH', 3, '14 weeks',            98,  98,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'DIPH', 4, 'Booster, 16-24 months', 480, 480, 28,  180, NULL),
    (gen_random_uuid(), 'UIP-2024', 'DIPH', 5, 'Booster, 5-6 years',  1826, 1826, 28,  365, NULL),

    (gen_random_uuid(), 'UIP-2024', 'PERT', 1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'PERT', 2, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'PERT', 3, '14 weeks',            98,  98,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'PERT', 4, 'Booster, 16-24 months', 480, 480, 28,  180, NULL),
    (gen_random_uuid(), 'UIP-2024', 'PERT', 5, 'Booster, 5-6 years',  1826, 1826, 28,  365, NULL),

    (gen_random_uuid(), 'UIP-2024', 'TET',  1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'TET',  2, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'TET',  3, '14 weeks',            98,  98,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'TET',  4, 'Booster, 16-24 months', 480, 480, 28,  180, NULL),
    (gen_random_uuid(), 'UIP-2024', 'TET',  5, 'Booster, 5-6 years',  1826, 1826, 28,  365, NULL),

    (gen_random_uuid(), 'UIP-2024', 'HIB',  1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'HIB',  2, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'HIB',  3, '14 weeks',            98,  98,   28,   28,  NULL),

    (gen_random_uuid(), 'UIP-2024', 'HEPB', 2, '6 weeks',             42,  42,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'HEPB', 3, '10 weeks',            70,  70,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'HEPB', 4, '14 weeks',            98,  98,   28,   28,  NULL),

    -- Rotavirus. The window closes because the course carries an age limit of its own: starting it
    -- late is not recommended, so a due list that kept calling would be calling for a dose nobody
    -- would give.
    (gen_random_uuid(), 'UIP-2024', 'ROTA', 1, '6 weeks',             42,  42,   NULL, 28,  105),
    (gen_random_uuid(), 'UIP-2024', 'ROTA', 2, '10 weeks',            70,  70,   28,   28,  240),
    (gen_random_uuid(), 'UIP-2024', 'ROTA', 3, '14 weeks',            98,  98,   28,   28,  240),

    (gen_random_uuid(), 'UIP-2024', 'IPV',  1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'IPV',  2, '14 weeks',            98,  98,   28,   28,  NULL),

    (gen_random_uuid(), 'UIP-2024', 'PCV',  1, '6 weeks',             42,  42,   NULL, 28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'PCV',  2, '14 weeks',            98,  98,   28,   28,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'PCV',  3, 'Booster, 9 months',   270, 270,  28,   90,  NULL),

    -- 9-12 months and 16-24 months.
    (gen_random_uuid(), 'UIP-2024', 'MEAS', 1, '9-12 months',         270, 270,  NULL, 90,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'MEAS', 2, '16-24 months',        480, 480,  28,   180, NULL),
    (gen_random_uuid(), 'UIP-2024', 'RUB',  1, '9-12 months',         270, 270,  NULL, 90,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'RUB',  2, '16-24 months',        480, 480,  28,   180, NULL),
    (gen_random_uuid(), 'UIP-2024', 'JE',   1, '9-12 months',         270, 270,  NULL, 90,  NULL),
    (gen_random_uuid(), 'UIP-2024', 'JE',   2, '16-24 months',        480, 480,  28,   180, NULL);
