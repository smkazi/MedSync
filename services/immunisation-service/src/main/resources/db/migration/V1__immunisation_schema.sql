-- The immunisation register, and the vocabulary it is written in.
--
-- Four rules shape this schema, and each one is a constraint rather than a convention:
--
--   1. A dose is recorded against a PRODUCT and counts toward every ANTIGEN that product
--      contains. Coverage is an antigen question and a recall is a product question; one table
--      answering both would answer one of them wrongly.
--   2. A lot number is evidence, so it is present exactly when there is evidence to have. A dose
--      given here has one; a dose a mother remembers does not, and inventing one would put
--      fabricated evidence in the one column a recall reads.
--   3. Nothing here computes "due". Due is a function of a date of birth this service does not
--      hold and this schema deliberately does not copy -- see ImmunisationScheduleCalculator, and
--      V2 for the rows it reads.
--   4. Intervals are days between dates. No zone appears in the arithmetic at all, which is the
--      only reason a schedule means the same thing in Kolkata and in a UTC container.


-- ---------------------------------------------------------------------------
-- What a vaccine protects against. The vocabulary schedules and measures are written in.
-- ---------------------------------------------------------------------------
--
-- Separate from the product for the reason the pharmacy separates an ingredient from a brand:
-- "ingredients, not brand names, are what the checks run on". The immunisation form of that is
-- that a child vaccinated against measles is vaccinated against it under every trade name and
-- inside every combination product it ever arrived in.
--
-- Keying the catalogue on the product alone fails on the question a register exists to answer.
-- A pentavalent vial delivers diphtheria, pertussis, tetanus, hepatitis B and Hib in one
-- injection, so "is this child covered for Hib?" would need code that knows what PENTA contains
-- -- behaviour living in a switch, which is exactly the failure docs/extensibility.md records as
-- its worked example.
CREATE TABLE antigens (
    id               uuid         PRIMARY KEY,
    code             varchar(32)  NOT NULL UNIQUE,
    name             varchar(160) NOT NULL,
    -- The disease, in the words a parent's card uses, for a screen to show. Not a second code:
    -- nothing matches on it and nothing groups by it.
    protects_against varchar(160) NOT NULL,
    -- Retired, never deleted. A retired antigen still has doses recorded against it, and a
    -- schedule from last year still names it.
    active           boolean      NOT NULL DEFAULT true,
    version          bigint       NOT NULL DEFAULT 0,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_antigens_active ON antigens (active, code);


-- ---------------------------------------------------------------------------
-- What the store buys and a nurse picks off a list.
-- ---------------------------------------------------------------------------
CREATE TABLE vaccine_products (
    id             uuid         PRIMARY KEY,
    code           varchar(32)  NOT NULL UNIQUE,
    name           varchar(160) NOT NULL,
    manufacturer   varchar(160) NOT NULL,
    -- How it is given. A column on the product rather than a field on the dose, because the
    -- route is a property of the vaccine: BCG is intradermal and OPV is oral, and a dose row
    -- that let somebody type otherwise would record a route the vaccine does not have.
    route          varchar(24)  NOT NULL,
    -- Doses in a vial. Recorded because an opened multi-dose vial has an open-vial policy, and a
    -- register that cannot tell a single-dose vial from a twenty-dose one cannot support one.
    -- The policy itself is NOT implemented, and the README says so rather than this column
    -- implying it.
    doses_per_vial int          NOT NULL DEFAULT 1,
    active         boolean      NOT NULL DEFAULT true,
    version        bigint       NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_route
        CHECK (route IN ('INTRAMUSCULAR', 'SUBCUTANEOUS', 'INTRADERMAL', 'ORAL', 'INTRANASAL')),
    CONSTRAINT chk_doses_per_vial CHECK (doses_per_vial >= 1)
);

CREATE INDEX idx_vaccine_products_active ON vaccine_products (active, name);


-- What a product actually contains.
--
-- Written when the product is created and never edited, which is the same decision
-- formulary_ingredients makes and matters more here. Editing a contents list in place would
-- change what already-recorded doses are counted as covering: a child recorded as having had
-- PENTA in 2024 had whatever PENTA contained in 2024, and a row edited in 2026 must not
-- retrospectively give them a Hib dose they did not receive.
CREATE TABLE vaccine_product_antigens (
    product_code varchar(32) NOT NULL REFERENCES vaccine_products (code) ON DELETE CASCADE,
    antigen_code varchar(32) NOT NULL REFERENCES antigens (code),
    PRIMARY KEY (product_code, antigen_code)
);

-- The coverage direction: "which products count toward this antigen".
CREATE INDEX idx_product_antigen_lookup ON vaccine_product_antigens (antigen_code);


-- ---------------------------------------------------------------------------
-- Stock, by lot, because expiry is a property of a lot and not of a vaccine.
-- ---------------------------------------------------------------------------
--
-- Deliberately the same shape as pharmacy.stock_batches: a lot number, an expiry that cannot be
-- null, a non-negative CHECK, and a first-expiry-first-out index ordered the way the picker
-- reads it. A second, differently shaped inventory table in a second service is how two services
-- come to disagree about what a batch is.
CREATE TABLE vaccine_lots (
    id               uuid        PRIMARY KEY,
    product_code     varchar(32) NOT NULL REFERENCES vaccine_products (code),
    lot_no           varchar(48) NOT NULL,
    expires_on       date        NOT NULL,
    quantity_on_hand integer     NOT NULL,
    received_on      date        NOT NULL DEFAULT current_date,
    -- The vaccine vial monitor stage as read at receipt, 1 to 4.
    --
    -- Recorded and not enforced, and the honest reason is that this platform has no cold-chain
    -- telemetry: nothing here knows what a fridge did overnight. Stage 3 and 4 vials must be
    -- discarded and that judgement is a person's, at the fridge, with the vial in their hand. So
    -- the column records what was read and the README names the enforcement as not built -- the
    -- same posture formulary.controlled takes one service along.
    vvm_stage        smallint,
    -- Set when a lot is taken out of use: expired, recalled, or a broken cold chain. A reason
    -- rather than a boolean, because "why" is the question asked afterwards.
    withdrawn_reason varchar(255),
    version          bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vaccine_lot UNIQUE (product_code, lot_no),
    CONSTRAINT chk_lot_not_negative CHECK (quantity_on_hand >= 0),
    CONSTRAINT chk_vvm_stage CHECK (vvm_stage IS NULL OR vvm_stage BETWEEN 1 AND 4)
);

-- First expiry, first out. Ordered the way the picker reads it, as in the pharmacy.
CREATE INDEX idx_vaccine_lot_fefo ON vaccine_lots (product_code, expires_on)
    WHERE quantity_on_hand > 0 AND withdrawn_reason IS NULL;

-- The query a bad week runs: "this lot was recalled -- who got it".
CREATE INDEX idx_vaccine_lot_no ON vaccine_lots (lot_no);


-- ---------------------------------------------------------------------------
-- The register itself: one row per dose, wherever it happened.
-- ---------------------------------------------------------------------------
--
-- A dose given somewhere else is representable on purpose, and the constraints below make it
-- say so on its face. The failure mode of refusing is not that historical doses go unrecorded;
-- it is that somebody types them in as though given here, with an invented lot number, because
-- lot_id is NOT NULL. That puts fabricated evidence into the one column a recall reads, which is
-- worse than an incomplete record: nobody goes looking further after a confident wrong answer.
CREATE TABLE immunisations (
    id           uuid        PRIMARY KEY,
    -- Another service's ids, unconstrained on purpose, exactly as everywhere else here.
    patient_id   uuid        NOT NULL,
    patient_mrn  varchar(24) NOT NULL,
    -- The visit it was given at, when there was one. Null for a dose recorded off a card, which
    -- happened at no visit of ours.
    encounter_id uuid,

    product_code varchar(32) NOT NULL REFERENCES vaccine_products (code),
    -- The product's name as it was when this was written. Snapshotted rather than joined, the
    -- same decision a prescription item and an invoice line make: renaming a catalogue entry
    -- must not rewrite what a record from last year said.
    product_name varchar(160) NOT NULL,
    lot_id       uuid        REFERENCES vaccine_lots (id),

    source       varchar(28) NOT NULL,

    -- The clinical date, and the date every interval in every schedule is measured against.
    --
    -- A date and not a timestamp, deliberately. A schedule says "28 days after dose 1", a
    -- vaccination card says a date, and an interval between two dates has no zone in it. That is
    -- what makes the due calculator zone-free by construction rather than by discipline.
    given_on     date        NOT NULL,
    -- True when the date is somebody's recollection rather than a record. A dose given here has
    -- a date this platform observed, so it can never be estimated; a mother saying "about six
    -- months" is a fact worth keeping and worth flagging, and a measure decides for itself
    -- whether it counts one.
    given_on_estimated boolean NOT NULL DEFAULT false,

    route        varchar(24),
    site         varchar(32),
    given_by     varchar(120),

    -- What was seen. Required for a dose from elsewhere: "parent reported" with nothing after it
    -- is a claim with no provenance, and the next clinician cannot tell it from a record.
    evidence     varchar(500),

    -- When the row was written, which is a different question from when the dose was given and
    -- is the only one of the two this platform witnessed.
    recorded_at  timestamptz NOT NULL DEFAULT now(),
    recorded_by  varchar(120) NOT NULL,

    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_immunisation_source
        CHECK (source IN ('ADMINISTERED_HERE', 'HISTORICAL_DOCUMENTED', 'HISTORICAL_PARENT_REPORTED')),

    -- A lot number is evidence, so it exists exactly when there is evidence to have.
    --
    -- A biconditional rather than two one-way checks, and both halves are load-bearing. Without
    -- the forward half a historical dose could carry an invented lot; without the reverse half a
    -- dose given here could be recorded with no lot at all, and the recall query would miss it
    -- silently -- which is the worse of the two.
    CONSTRAINT chk_lot_iff_given_here
        CHECK ((source = 'ADMINISTERED_HERE') = (lot_id IS NOT NULL)),

    -- A dose given here was witnessed: who gave it, by what route, into which arm.
    CONSTRAINT chk_given_here_is_complete
        CHECK (source <> 'ADMINISTERED_HERE'
               OR (route IS NOT NULL AND site IS NOT NULL AND given_by IS NOT NULL)),

    -- A dose recorded from elsewhere carries what was seen.
    CONSTRAINT chk_historical_carries_evidence
        CHECK (source = 'ADMINISTERED_HERE'
               OR (evidence IS NOT NULL AND length(btrim(evidence)) >= 8)),

    -- We know the date of a dose we gave.
    CONSTRAINT chk_estimated_only_when_historical
        CHECK (given_on_estimated = false OR source <> 'ADMINISTERED_HERE'),

    -- One product, one patient, one day.
    --
    -- The immunisation form of the pharmacy's uq_dose. Two clinics entering the same card, or one
    -- nurse clicking twice, is the failure this exists for -- and it is a unique constraint
    -- rather than a check in application code because both callers pass a check and only one can
    -- win an insert.
    CONSTRAINT uq_dose_per_day UNIQUE (patient_id, product_code, given_on)
);

-- The register as a patient's own timeline, which is how every clinical screen reads it.
CREATE INDEX idx_immunisation_patient ON immunisations (patient_id, given_on);
-- The recall read.
CREATE INDEX idx_immunisation_lot ON immunisations (lot_id) WHERE lot_id IS NOT NULL;
-- The measure's cohort read: every dose in a period, for a set of patients.
CREATE INDEX idx_immunisation_given_on ON immunisations (given_on);


-- ---------------------------------------------------------------------------
-- Adverse events following immunisation.
-- ---------------------------------------------------------------------------
--
-- Note what is NOT here: a constraint that an event cannot precede the dose it followed.
-- PostgreSQL cannot express a comparison against another table's row in a CHECK at all -- it
-- would need a trigger -- so the rule lives in AefiService and this comment says why it is not
-- beside the others. A CHECK (true) standing in for it would read as a constraint and enforce
-- nothing, which is worse than an honest absence.
CREATE TABLE adverse_events (
    id              uuid          PRIMARY KEY,
    immunisation_id uuid          NOT NULL REFERENCES immunisations (id),
    onset_on        date          NOT NULL,
    -- What happened, in the reporter's words. Free text, because an AEFI form is free text and a
    -- coded list would refuse the event nobody anticipated -- which is the only kind worth
    -- reporting.
    description     varchar(1000) NOT NULL,
    seriousness     varchar(20)   NOT NULL,
    outcome         varchar(24)   NOT NULL,
    reported_by     varchar(120)  NOT NULL,
    reported_at     timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_aefi_seriousness CHECK (seriousness IN ('MINOR', 'SEVERE', 'SERIOUS')),
    CONSTRAINT chk_aefi_outcome
        CHECK (outcome IN ('RECOVERED', 'RECOVERING', 'NOT_RECOVERED', 'DIED', 'UNKNOWN')),
    CONSTRAINT chk_aefi_description CHECK (length(btrim(description)) >= 8)
);

CREATE INDEX idx_aefi_immunisation ON adverse_events (immunisation_id);
CREATE INDEX idx_aefi_onset ON adverse_events (onset_on);


-- ---------------------------------------------------------------------------
-- Why a child is not going to be vaccinated.
-- ---------------------------------------------------------------------------
--
-- Two kinds, and the difference between them is behaviour rather than labelling: a medical
-- contraindication comes out of a coverage measure's denominator and a parental refusal does
-- not. A clinic able to exclude refusals could report a hundred per cent coverage by recording
-- refusals, and the measure would then be measuring the recording of refusals.
CREATE TABLE immunisation_exemptions (
    id           uuid         PRIMARY KEY,
    patient_id   uuid         NOT NULL,
    patient_mrn  varchar(24)  NOT NULL,
    -- Null means every antigen. A blanket exemption is a real clinical situation -- severe
    -- immunodeficiency -- and forcing one row per antigen would produce a list somebody
    -- eventually leaves incomplete, which is a child counted as due for something they must not
    -- have.
    antigen_code varchar(32)  REFERENCES antigens (code),
    kind         varchar(20)  NOT NULL,
    -- A sentence, not a checkbox. The same twenty-character floor break-glass sets, and for the
    -- same reason: "medical" is what a free-text box collects when it does not ask for a
    -- sentence, and this removes a child from a measure's denominator.
    reason       varchar(500) NOT NULL,
    recorded_by  varchar(120) NOT NULL,
    recorded_at  timestamptz  NOT NULL DEFAULT now(),
    -- When the exemption lapses. Null means it does not: a permanent contraindication is
    -- permanent, and a deferral until a course of steroids finishes is not.
    expires_on   date,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_exemption_kind CHECK (kind IN ('MEDICAL', 'REFUSED')),
    CONSTRAINT chk_exemption_reason CHECK (length(btrim(reason)) >= 20)
);

CREATE INDEX idx_exemption_patient ON immunisation_exemptions (patient_id);


-- ---------------------------------------------------------------------------
-- The catalogue, seeded.
-- ---------------------------------------------------------------------------
--
-- Seeded here and stock deliberately not, which is the pharmacy's split: fake inventory in a real
-- clinic is worse than an empty shelf, and a lot number nobody received is a lot number a recall
-- would chase.
--
-- Fixed ids in the 77777777 series, so a later migration or a test can name a row.
INSERT INTO antigens (id, code, name, protects_against) VALUES
    ('77777777-0000-4000-8000-000000000001', 'BCG',    'Bacille Calmette-Guerin', 'Tuberculosis'),
    ('77777777-0000-4000-8000-000000000002', 'HEPB',   'Hepatitis B',             'Hepatitis B'),
    ('77777777-0000-4000-8000-000000000003', 'OPV',    'Oral polio',              'Poliomyelitis'),
    ('77777777-0000-4000-8000-000000000004', 'IPV',    'Inactivated polio',       'Poliomyelitis'),
    ('77777777-0000-4000-8000-000000000005', 'DIPH',   'Diphtheria toxoid',       'Diphtheria'),
    ('77777777-0000-4000-8000-000000000006', 'PERT',   'Pertussis',               'Whooping cough'),
    ('77777777-0000-4000-8000-000000000007', 'TET',    'Tetanus toxoid',          'Tetanus'),
    ('77777777-0000-4000-8000-000000000008', 'HIB',    'Haemophilus influenzae b', 'Meningitis and pneumonia'),
    ('77777777-0000-4000-8000-000000000009', 'ROTA',   'Rotavirus',               'Rotavirus diarrhoea'),
    ('77777777-0000-4000-8000-00000000000a', 'PCV',    'Pneumococcal conjugate',  'Pneumococcal disease'),
    ('77777777-0000-4000-8000-00000000000b', 'MEAS',   'Measles',                 'Measles'),
    ('77777777-0000-4000-8000-00000000000c', 'RUB',    'Rubella',                 'Rubella'),
    ('77777777-0000-4000-8000-00000000000d', 'VITA',   'Vitamin A',               'Vitamin A deficiency'),
    ('77777777-0000-4000-8000-00000000000e', 'JE',     'Japanese encephalitis',   'Japanese encephalitis');

INSERT INTO vaccine_products (id, code, name, manufacturer, route, doses_per_vial) VALUES
    ('77777777-1000-4000-8000-000000000001', 'BCG',       'BCG vaccine',            'Generic', 'INTRADERMAL',   20),
    ('77777777-1000-4000-8000-000000000002', 'HEPB_BD',   'Hepatitis B, birth dose', 'Generic', 'INTRAMUSCULAR', 10),
    ('77777777-1000-4000-8000-000000000003', 'OPV',       'Oral polio vaccine',     'Generic', 'ORAL',          20),
    ('77777777-1000-4000-8000-000000000004', 'IPV',       'Inactivated polio vaccine', 'Generic', 'INTRAMUSCULAR', 5),
    ('77777777-1000-4000-8000-000000000005', 'PENTA',     'Pentavalent',            'Generic', 'INTRAMUSCULAR', 10),
    ('77777777-1000-4000-8000-000000000006', 'ROTA',      'Rotavirus vaccine',      'Generic', 'ORAL',          5),
    ('77777777-1000-4000-8000-000000000007', 'PCV',       'Pneumococcal conjugate', 'Generic', 'INTRAMUSCULAR', 4),
    ('77777777-1000-4000-8000-000000000008', 'MR',        'Measles-rubella',        'Generic', 'SUBCUTANEOUS',  10),
    ('77777777-1000-4000-8000-000000000009', 'JE',        'Japanese encephalitis',  'Generic', 'SUBCUTANEOUS',  5),
    ('77777777-1000-4000-8000-00000000000a', 'DPT_B',     'DPT booster',            'Generic', 'INTRAMUSCULAR', 10);

-- The join that makes coverage answerable. PENTA is the row that matters: five antigens, one
-- injection, and a child who had it is covered for all five.
INSERT INTO vaccine_product_antigens (product_code, antigen_code) VALUES
    ('BCG',     'BCG'),
    ('HEPB_BD', 'HEPB'),
    ('OPV',     'OPV'),
    ('IPV',     'IPV'),
    ('PENTA',   'DIPH'),
    ('PENTA',   'PERT'),
    ('PENTA',   'TET'),
    ('PENTA',   'HEPB'),
    ('PENTA',   'HIB'),
    ('ROTA',    'ROTA'),
    ('PCV',     'PCV'),
    ('MR',      'MEAS'),
    ('MR',      'RUB'),
    ('JE',      'JE'),
    ('DPT_B',   'DIPH'),
    ('DPT_B',   'PERT'),
    ('DPT_B',   'TET');
