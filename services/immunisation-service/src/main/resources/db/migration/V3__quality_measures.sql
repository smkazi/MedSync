-- Clinical quality measures over the register.
--
-- One rule shapes both tables, and it is the sharpest configuration decision in this module:
--
--   A MEASURE'S PARAMETERS ARE ROWS. THE KIND OF QUESTION IT ASKS IS CODE.
--
-- Making the whole thing configuration would be the NEWS2 failure with the sign reversed. There,
-- a deployment able to edit the bands could publish a number it calls NEWS2 which is not NEWS2 --
-- a wrong answer carrying a standard's authority. Here, a deployment able to add a `kind` with no
-- calculator behind it could publish a rate that is not a rate at all: a percentage rendered from
-- two zeroes. The first is a wrong answer; the second is an answer with nothing behind it, which is
-- worse, because the first can at least be checked.
--
-- So `kind` carries a CHECK naming exactly the calculators that exist, and everything a kind reads
-- is a column. Adding a second coverage measure is an INSERT. Adding a second kind is a code change
-- and a migration, in that order.
--
-- Nothing here is computed or cached. A rate is derived on every read, for the reason
-- ConsentStatus.EXPIRED is: a cached rate is a rate that can be published stale, and there is no
-- invalidation key -- a dose entered from a card this morning correctly changes last quarter's
-- number. Each answer is stamped with the specification version and the moment it was computed.


-- ---------------------------------------------------------------------------
-- What is measured, and by whose specification.
-- ---------------------------------------------------------------------------
CREATE TABLE quality_measures (
    id                    uuid         PRIMARY KEY,
    code                  varchar(32)  NOT NULL UNIQUE,
    name                  varchar(160) NOT NULL,

    -- Which calculator answers it. The CHECK below is the whole safety property of this table.
    kind                  varchar(48)  NOT NULL,

    -- The age at which the numerator is evaluated, in days from date of birth.
    --
    -- Evaluated AS AT the child's Nth birthday, not as at today, because that is what "by age two"
    -- means. Getting it wrong makes a rate that improves retroactively: a dose given at three
    -- years old would start counting toward a two-year-old coverage figure, and last quarter's
    -- published number would rise every time somebody caught up.
    by_age_days           integer      NOT NULL,

    -- Who publishes the specification, and which version of it produced this number.
    --
    -- Stored rather than assumed, because a rate published without saying which specification
    -- produced it is a number nobody downstream can check -- and this one goes into a return
    -- somebody signs.
    steward               varchar(160) NOT NULL,
    specification_version varchar(48)  NOT NULL,

    -- The three populations, in the specification's own words rather than rendered from the
    -- parameters below.
    --
    -- Deliberate duplication. A sentence generated from the columns would always agree with the
    -- code and would therefore never reveal a disagreement between the code and the specification,
    -- which is the only disagreement worth finding. These are transcribed, and if they stop
    -- matching what the calculator does then one of the two is wrong and somebody can see it.
    initial_population    text         NOT NULL,
    denominator           text         NOT NULL,
    denominator_exclusion text         NOT NULL,
    numerator             text         NOT NULL,

    -- Whether a dose whose date is somebody's recollection counts.
    --
    -- A column because it changes the number, and a number that silently counted recollected dates
    -- would be higher than one that did not with nobody reading it able to tell which they had.
    -- Defaults to false: a measure that goes into a statutory return counts records.
    counts_estimated_dates boolean     NOT NULL DEFAULT false,

    active                boolean      NOT NULL DEFAULT true,
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),

    -- The list of implemented calculators, and the reason this table is safe to write to.
    --
    -- A row naming a kind nothing implements would be refused here rather than published as a
    -- percentage of nothing. Adding a value to this list without adding the calculator behind it
    -- is the one edit that would break the guarantee, which is why the list lives in DDL where a
    -- migration has to say it out loud.
    CONSTRAINT chk_measure_kind CHECK (kind IN ('ANTIGEN_COVERAGE_BY_AGE')),
    CONSTRAINT chk_measure_age CHECK (by_age_days > 0)
);

CREATE INDEX idx_quality_measures_active ON quality_measures (active, code);


-- ---------------------------------------------------------------------------
-- What each measure requires, per antigen.
-- ---------------------------------------------------------------------------
--
-- Antigens rather than products, for the reason the schedule is written in them: a child covered
-- for measles is covered under every trade name and inside every combination product. A measure
-- written in products would report a district's coverage falling the week a clinic changed brand.
--
-- Several rows per measure, ANDed: a composite measure is satisfied by a child who has every one of
-- them. That is what makes CIS-2 a single rate rather than eight.
CREATE TABLE quality_measure_antigens (
    id             uuid        PRIMARY KEY,
    measure_code   varchar(32) NOT NULL REFERENCES quality_measures (code) ON DELETE CASCADE,
    antigen_code   varchar(32) NOT NULL REFERENCES antigens (code),
    doses_required integer     NOT NULL,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_measure_antigen UNIQUE (measure_code, antigen_code),
    CONSTRAINT chk_doses_required CHECK (doses_required >= 1)
);

CREATE INDEX idx_measure_antigen_read ON quality_measure_antigens (measure_code, antigen_code);


-- ---------------------------------------------------------------------------
-- One measure, seeded.
-- ---------------------------------------------------------------------------
--
-- Childhood immunisation status by age two: the shape almost every national and payer programme
-- publishes, and a composite rather than eight separate rates because a child protected against
-- seven of eight things is not a covered child.
--
-- The dose counts are the ones UIP-2024 expects by 730 days, read off V2 rather than off a
-- specification written for another country's schedule -- a measure whose numerator cannot be
-- reached by the schedule the clinic actually runs would report a permanent zero and blame the
-- clinic for it. Rotavirus, pneumococcal, JE and BCG are deliberately out of this composite: the
-- first three are recent additions a two-year-old in the earliest cohorts could not have had, and
-- BCG at birth is measured by a different indicator entirely.
INSERT INTO quality_measures
    (id, code, name, kind, by_age_days, steward, specification_version,
     initial_population, denominator, denominator_exclusion, numerator, counts_estimated_dates)
VALUES (
    '77777777-3000-4000-8000-000000000001',
    'CIS-2',
    'Childhood immunisation status by age two',
    'ANTIGEN_COVERAGE_BY_AGE',
    730,
    'National immunisation programme, district reporting',
    '2024.1',
    'Children who reached their second birthday during the measurement period.',
    'The initial population.',
    'Children with a recorded medical contraindication covering any antigen this measure requires, live on their second birthday. A parental refusal is NOT an exclusion: a clinic able to exclude refusals could report full coverage by recording refusals, and the measure would then be measuring the recording of refusals.',
    'Children who, as at their second birthday, had the required number of counted doses of every antigen this measure names. A dose given before the schedule''s minimum age, or sooner after the previous dose than its minimum interval allows, is not a counted dose.',
    false);

INSERT INTO quality_measure_antigens (id, measure_code, antigen_code, doses_required) VALUES
    ('77777777-3100-4000-8000-000000000001', 'CIS-2', 'DIPH', 4),
    ('77777777-3100-4000-8000-000000000002', 'CIS-2', 'PERT', 4),
    ('77777777-3100-4000-8000-000000000003', 'CIS-2', 'TET',  4),
    ('77777777-3100-4000-8000-000000000004', 'CIS-2', 'OPV',  4),
    ('77777777-3100-4000-8000-000000000005', 'CIS-2', 'HIB',  3),
    ('77777777-3100-4000-8000-000000000006', 'CIS-2', 'HEPB', 3),
    ('77777777-3100-4000-8000-000000000007', 'CIS-2', 'MEAS', 1),
    ('77777777-3100-4000-8000-000000000008', 'CIS-2', 'RUB',  1);
