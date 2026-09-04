-- Notifiable conditions, and the index `diagnoses` never had.
--
-- WHY THE SURVEILLANCE REPORT LIVES HERE and not in the module that produced it. To compute an
-- aggregate whose whole definition is that it carries no patient identifiers, a service somewhere
-- else would have to ship every patient identifier over the wire to count them: the aggregate
-- endpoint would internally BE the line list, and the group by would happen in a JVM instead of on
-- an index. scheduling-service owns `diagnoses`, so it owns the answer -- the rule
-- CareRelationshipClient already states as "scheduling-service owns the care team, so it owns the
-- answer; everyone else asks". The cost is that public-health reporting is split across two
-- services, which is accepted and named rather than engineered around.


-- ---------------------------------------------------------------------------
-- Which diagnoses have to be reported, and how fast.
-- ---------------------------------------------------------------------------
--
-- ONE ROW PER ICD-10 CODE, NEVER A PREFIX. A prefix widens invisibly: 'A0' would sweep cholera
-- (A00) through typhoid (A01) and amoebiasis (A06) into one line of a statutory return, and the day
-- somebody adds a code under it the return changes with no edit to this table. It also could not be
-- an equality join, so the index below would go unused and the query would degrade to a scan of
-- every diagnosis ever recorded.
--
-- `notify_within_hours` is a number rather than an urgency enum because how many hours IS the whole
-- of the behaviour. An enum would need a table mapping each value to a number, which is this column
-- with an extra join and a place for the two to disagree.
CREATE TABLE notifiable_conditions (
    id                  uuid         PRIMARY KEY,
    icd10_code          varchar(16)  NOT NULL UNIQUE,
    -- What the return calls it, which is not always what the chart calls it. Stored so a report
    -- reads in the vocabulary the authority uses rather than in the clinician's.
    condition_name      varchar(160) NOT NULL,
    -- Hours from diagnosis to notification. Recorded, and not enforced by anything here: the
    -- platform has no outbound channel to a public health authority, so a countdown it could not
    -- act on would be a promise nothing keeps. The screens show it and the README says so.
    notify_within_hours integer      NOT NULL,
    -- Retired, never deleted. A condition removed from the list this year still appears in last
    -- year's returns, and a deleted row would silently change a number somebody already filed.
    active              boolean      NOT NULL DEFAULT true,
    version             bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_notify_hours CHECK (notify_within_hours > 0)
);

CREATE INDEX idx_notifiable_active ON notifiable_conditions (active, icd10_code);


-- ---------------------------------------------------------------------------
-- The index a chart never needed and surveillance cannot work without.
-- ---------------------------------------------------------------------------
--
-- `diagnoses` has had exactly one index since it was created: (encounter_id). That serves the
-- chart's question -- "what was this visit diagnosed as" -- and cannot serve surveillance's
-- opposite one: "one code, every patient, over a period". Without this, counting notifiable cases
-- means a sequential scan of every diagnosis the hospital has ever recorded, every time somebody
-- opens the report.
--
-- DELIBERATELY NOT A PARTIAL INDEX naming the notifiable codes. It would be smaller and faster, and
-- it would hard-code into DDL the very list this migration just made configurable -- so adding a
-- condition through the API would leave the query unable to use its own index, and nobody would
-- notice until the report got slow.
CREATE INDEX idx_diagnoses_code ON diagnoses (icd10_code);


-- ---------------------------------------------------------------------------
-- Seeded, from the conditions a district actually reports.
-- ---------------------------------------------------------------------------
--
-- Configuration in the sense that matters: a deployment in another jurisdiction replaces these rows
-- and changes no code. Seeded rather than left empty because an empty list produces a report with
-- no lines in it, which reads exactly like a district with no notifiable disease -- a wrong answer
-- that looks like good news, and therefore one nobody checks. The same argument the immunisation
-- schedule makes for seeding itself.
--
-- The hours are the shape of the rule rather than a claim about any particular statute: cholera and
-- diphtheria in 24, the vaccine-preventable childhood diseases in 24 to 72, tuberculosis and the
-- vector-borne ones weekly. A deployment tunes them.
INSERT INTO notifiable_conditions (id, icd10_code, condition_name, notify_within_hours) VALUES
    (gen_random_uuid(), 'A00', 'Cholera',                          24),
    (gen_random_uuid(), 'A01', 'Typhoid and paratyphoid fever',    72),
    (gen_random_uuid(), 'A05', 'Bacterial foodborne intoxication', 72),
    (gen_random_uuid(), 'A15', 'Respiratory tuberculosis',        168),
    (gen_random_uuid(), 'A16', 'Tuberculosis, unconfirmed',       168),
    (gen_random_uuid(), 'A36', 'Diphtheria',                       24),
    (gen_random_uuid(), 'A37', 'Whooping cough',                   72),
    (gen_random_uuid(), 'A80', 'Acute poliomyelitis',              24),
    (gen_random_uuid(), 'A90', 'Dengue fever',                     72),
    (gen_random_uuid(), 'A91', 'Dengue haemorrhagic fever',        24),
    (gen_random_uuid(), 'B05', 'Measles',                          24),
    (gen_random_uuid(), 'B06', 'Rubella',                          72),
    (gen_random_uuid(), 'B15', 'Acute hepatitis A',                72),
    (gen_random_uuid(), 'B16', 'Acute hepatitis B',               168),
    (gen_random_uuid(), 'B50', 'Plasmodium falciparum malaria',    72),
    (gen_random_uuid(), 'B51', 'Plasmodium vivax malaria',         72),
    (gen_random_uuid(), 'A39', 'Meningococcal infection',          24),
    (gen_random_uuid(), 'A82', 'Rabies',                           24);
