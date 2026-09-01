-- Interpretive comments on a haematology report.
--
-- Ported from reports/report_generator.py in smkazi/HaematologyIS, which produced these narratives
-- for a working pathology laboratory. Three things came across; one changed on the way.
--
-- WHAT CAME ACROSS
--
--   1. Analyzer flag rules - "Leucopenia - low total WBC count." and friends, each triggered when
--      every condition of a rule holds.
--   2. Sysmex histogram flag codes - WL, WU, T1, RL, MP, PL, AG and the rest, each with the
--      explanation a technician needs (what the instrument could not separate, and what to verify).
--   3. Parameter scale normalisation - an analyzer may transmit WBC as 7.36 or as 7360, and a rule
--      written against one scale silently never fires against the other.
--
-- WHAT CHANGED: THE THRESHOLDS ARE ROWS, NOT CONSTANTS
--
-- In the source these live in Python literals, so a laboratory that wanted "anaemia" to comment
-- below 10.0 rather than 9.0 needed a code change. They are rows here, for the same reason
-- reference ranges and room types are: adding or retuning a rule requires no new behaviour, so it
-- is configuration. See docs/extensibility.md.
--
-- THE ALERT LEVEL IS NOT THE REFERENCE INTERVAL, AND THAT IS THE POINT
--
-- These thresholds are deliberately wider than the reference ranges seeded in V1. Haemoglobin is
-- flagged L below 11.5 g/dL for a woman, but only earns a narrative comment below 9.0. Platelets
-- flag below 150 and comment below 60. Two tiers, because they answer different questions: "is this
-- outside normal?" versus "does this need saying out loud on the report?". A report that printed a
-- paragraph for every out-of-range number would be a report nobody reads.

-- ---------------------------------------------------------------------------------------------
-- Rules and their conditions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE interpretive_rules (
    id            uuid         PRIMARY KEY,
    code          varchar(32)  NOT NULL UNIQUE,
    label         varchar(60)  NOT NULL,
    message       varchar(500) NOT NULL,
    display_order smallint     NOT NULL DEFAULT 100,
    active        boolean      NOT NULL DEFAULT true,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- Conditions are ANDed within a rule. Anisocytosis needs both RDW-CV and RDW-SD raised, because
-- either alone is unreliable - which is exactly why a rule is a row set rather than one column.
CREATE TABLE interpretive_rule_conditions (
    id         uuid           PRIMARY KEY,
    rule_id    uuid           NOT NULL REFERENCES interpretive_rules (id) ON DELETE CASCADE,
    -- Comma-separated aliases, tried in order. One analyzer sends LYM#, another LYMPH#, another LY#.
    parameters varchar(120)   NOT NULL,
    operator   varchar(2)     NOT NULL,
    threshold  numeric(14,4)  NOT NULL,
    version    bigint         NOT NULL DEFAULT 0,
    created_at timestamptz    NOT NULL DEFAULT now(),
    updated_at timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT chk_interpretive_operator CHECK (operator IN ('<', '>', '<=', '>='))
);
CREATE INDEX idx_interpretive_conditions_rule ON interpretive_rule_conditions (rule_id);

-- ---------------------------------------------------------------------------------------------
-- Instrument histogram flag codes
-- ---------------------------------------------------------------------------------------------

CREATE TABLE histogram_flag_notes (
    code       varchar(8)   PRIMARY KEY,
    message    varchar(500) NOT NULL,
    active     boolean      NOT NULL DEFAULT true,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------
-- Unit-scale normalisation
-- ---------------------------------------------------------------------------------------------
--
-- An analyzer may transmit WBC as 7.36 (10^3/uL) or 7360 (absolute /uL) depending on model and
-- configuration. A rule written against one scale never fires against the other, and never fires
-- silently - no error, just an interpretive comment that stops appearing. So a value above `above`
-- is taken to be on the absolute scale and divided.
--
-- The guard value sits between the two scales' plausible ranges rather than at a round number:
-- a WBC of 1000 x10^3/uL is not survivable and 1000/uL is profound leucopenia, so 1000 separates
-- them safely.
CREATE TABLE parameter_scales (
    parameter  varchar(24)   PRIMARY KEY,
    above      numeric(14,4) NOT NULL,
    divide_by  numeric(14,4) NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT chk_scale_divisor CHECK (divide_by > 0)
);

-- ---------------------------------------------------------------------------------------------
-- Named thresholds for the derived morphology narrative
-- ---------------------------------------------------------------------------------------------
--
-- Morphology is derived from the numeric indices when a pathologist has not entered a smear
-- comment by hand. The *structure* of the sentence is code - size, then chromia, then anisocytosis -
-- because each part carries behaviour. The numbers are configuration.
--
-- Note these differ again from both tiers above: the source names a red cell microcytic below MCV
-- 76, while the interpretive comment for microcytosis fires below 70 and the reference interval
-- starts at 80. Three numbers, three purposes, and collapsing them would lose meaning.
CREATE TABLE morphology_thresholds (
    code       varchar(32)   PRIMARY KEY,
    threshold  numeric(14,4) NOT NULL,
    note       varchar(200)  NOT NULL DEFAULT '',
    version    bigint        NOT NULL DEFAULT 0,
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------
-- Seed: the working laboratory's rule set
-- ---------------------------------------------------------------------------------------------
-- Reference data in the same sense as the reference ranges: concrete enough to produce a real
-- report, replaced wholesale by any deployment with its own pathologist's wording.

INSERT INTO interpretive_rules (id, code, label, message, display_order) VALUES
    (gen_random_uuid(), 'LEUCOPENIA', 'Leucopenia',
     'Leucopenia — low total WBC count.', 10),
    (gen_random_uuid(), 'LEUCOCYTOSIS', 'Leucocytosis',
     'Leucocytosis — raised total WBC count.', 20),
    (gen_random_uuid(), 'LYMPHOPENIA', 'Lymphopenia',
     'Lymphopenia — low absolute lymphocyte count.', 30),
    (gen_random_uuid(), 'LYMPHOCYTOSIS', 'Lymphocytosis',
     'Lymphocytosis — raised absolute lymphocyte count.', 40),
    (gen_random_uuid(), 'MID_INCREASED', 'Increased Mid Cells',
     'Increased mid cells (mono / eos / baso) — review differential.', 50),
    (gen_random_uuid(), 'GRANULOPENIA', 'Granulopenia',
     'Granulopenia — low absolute granulocyte count.', 60),
    (gen_random_uuid(), 'GRANULOCYTOSIS', 'Granulocytosis',
     'Granulocytosis — raised absolute granulocyte count.', 70),
    (gen_random_uuid(), 'ERYTHROCYTOSIS', 'Erythrocytosis',
     'Erythrocytosis — raised red cell count.', 80),
    (gen_random_uuid(), 'ANISOCYTOSIS', 'Anisocytosis',
     'Anisocytosis — increased red cell size variation (raised RDW).', 90),
    (gen_random_uuid(), 'MACROCYTOSIS', 'Macrocytosis',
     'Macrocytosis — macrocytic red cell indices.', 100),
    (gen_random_uuid(), 'MICROCYTOSIS', 'Microcytosis',
     'Microcytosis — microcytic indices; consider iron deficiency / thalassaemia trait.', 110),
    (gen_random_uuid(), 'ANAEMIA', 'Anaemia',
     'Anaemia — low haemoglobin; suggest peripheral smear correlation.', 120),
    (gen_random_uuid(), 'HYPOCHROMIA', 'Hypochromia',
     'Hypochromia — low MCHC.', 130),
    (gen_random_uuid(), 'THROMBOCYTOSIS', 'Thrombocytosis',
     'Thrombocytosis — raised platelet count.', 140),
    (gen_random_uuid(), 'THROMBOCYTOPENIA', 'Thrombocytopenia',
     'Thrombocytopenia — low platelet count; kindly verify on peripheral smear.', 150);

INSERT INTO interpretive_rule_conditions (id, rule_id, parameters, operator, threshold)
SELECT gen_random_uuid(), r.id, c.parameters, c.operator, c.threshold
FROM interpretive_rules r
JOIN (VALUES
    ('LEUCOPENIA',       'WBC',                 '<',  2.50),
    ('LEUCOCYTOSIS',     'WBC',                 '>', 18.00),
    ('LYMPHOPENIA',      'LYM#,LYMPH#,LY#',     '<',  0.80),
    ('LYMPHOCYTOSIS',    'LYM#,LYMPH#,LY#',     '>',  4.00),
    ('MID_INCREASED',    'MID#,MXD#',           '>',  1.50),
    ('GRANULOPENIA',     'GRAN#,NEUT#,GR#',     '<',  1.00),
    ('GRANULOCYTOSIS',   'GRAN#,NEUT#,GR#',     '>', 11.00),
    ('ERYTHROCYTOSIS',   'RBC',                 '>',  6.50),
    -- Both conditions must hold: either RDW measure alone is unreliable.
    ('ANISOCYTOSIS',     'RDW-CV,RDW',          '>', 22.00),
    ('ANISOCYTOSIS',     'RDW-SD',              '>', 64.00),
    ('MACROCYTOSIS',     'MCV',                 '>', 113.00),
    ('MICROCYTOSIS',     'MCV',                 '<', 70.00),
    ('ANAEMIA',          'HGB,HB',              '<',  9.00),
    ('HYPOCHROMIA',      'MCHC',                '<', 29.00),
    ('THROMBOCYTOSIS',   'PLT',                 '>', 600.00),
    ('THROMBOCYTOPENIA', 'PLT',                 '<', 60.00)
) AS c(code, parameters, operator, threshold) ON c.code = r.code;

INSERT INTO histogram_flag_notes (code, message) VALUES
    ('WL', 'WBC histogram abnormal at the lower discriminator (WL) — WBC may read falsely high '
           '(lyse-resistant RBC, nucleated RBC, or platelet clumps). Kindly verify on peripheral smear.'),
    ('WU', 'WBC histogram abnormal at the upper discriminator (WU) — WBC may read falsely low '
           '(extreme leukocytosis or WBC aggregation). Verify; pre-dilution may be required.'),
    ('T1', 'WBC differential: valley 1 not found (T1) — lymphocyte / mixed-cell separation '
           'unreliable; kindly review the smear.'),
    ('T2', 'WBC differential: valley 2 not found (T2) — mixed-cell / neutrophil separation '
           'unreliable; kindly review the smear.'),
    ('F1', 'Abnormal WBC differential distribution (F1) — kindly review the peripheral smear.'),
    ('F2', 'Abnormal WBC differential distribution (F2) — kindly review the peripheral smear.'),
    ('F3', 'Abnormal WBC differential distribution (F3) — kindly review the peripheral smear.'),
    ('RL', 'RBC histogram abnormal at the lower discriminator (RL) — platelet count may be affected '
           '(giant platelets, microcytes or fragments). Kindly verify the platelet count.'),
    ('RU', 'RBC histogram abnormal at the upper discriminator (RU) — possible RBC agglutination '
           '(cold agglutinins); warm the sample to 37°C and verify.'),
    ('MP', 'Multiple peaks on the histogram (MP) — two cell populations (anisocytosis); the count is '
           'not affected but review is advised.'),
    ('DW', 'Distribution width could not be calculated (DW) — abnormal histogram curve; RDW / PDW '
           'may be suppressed.'),
    ('PL', 'Platelet histogram abnormal at the lower discriminator (PL) — background, cell fragments '
           'or aggregation; platelets may read falsely high. Kindly verify.'),
    ('PU', 'Platelet histogram abnormal at the upper discriminator (PU) — giant platelets or clumps; '
           'platelets may read falsely low. Verify by smear or an alternate method.'),
    ('AG', 'Platelet aggregation detected (AG) — platelet count may read falsely low; recollect '
           '(consider sodium citrate) and verify.');

INSERT INTO parameter_scales (parameter, above, divide_by) VALUES
    ('WBC',   1000.0,  1000.0),
    ('PLT',  10000.0,  1000.0),
    ('LYM#',   100.0,  1000.0),
    ('MID#',   100.0,  1000.0),
    ('GRAN#',  100.0,  1000.0),
    ('RBC',   1000.0,  1000000.0);

INSERT INTO morphology_thresholds (code, threshold, note) VALUES
    ('MCV_MICROCYTIC',  76.0,   'Below this the red cells are called microcytic'),
    ('MCV_MACROCYTIC',  96.0,   'Above this the red cells are called macrocytic'),
    ('MCH_HYPOCHROMIC', 27.0,   'Below this, or below MCHC_HYPOCHROMIC, the cells are hypochromic'),
    ('MCHC_HYPOCHROMIC', 30.0,  'Alternative trigger for hypochromia'),
    ('RDW_SD_ANISO',    56.0,   'Preferred anisocytosis trigger: the direct fL width'),
    ('RDW_CV_ANISO',    14.5,   'Fallback anisocytosis trigger when RDW-SD was not transmitted'),
    ('WBC_HIGH',        11.0,   'Above this the narrative says leucocytosis'),
    ('WBC_LOW',          4.0,   'Below this the narrative says leucopenia'),
    ('NEUT_PCT_HIGH',   75.0,   'Neutrophilia'),
    ('NEUT_PCT_LOW',    40.0,   'Neutropenia'),
    ('LYM_PCT_HIGH',    45.0,   'Lymphocytosis'),
    ('LYM_PCT_LOW',     20.0,   'Lymphopenia'),
    ('PLT_LOW',        150.0,   'Below this the smear comment reports thrombocytopenia'),
    ('PLT_HIGH',       450.0,   'Above this the smear comment reports thrombocytosis');
