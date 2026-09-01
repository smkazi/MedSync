-- How the printed report is laid out.
--
-- The haemogram in smkazi/HaematologyIS groups parameters into red cell, white cell and platelet
-- sections rather than printing one flat table, and that grouping is most of what makes the sheet
-- readable: a clinician looks at the red cell block to think about anaemia and the platelet block to
-- think about bleeding, and interleaving them costs real time at the point of care.
--
-- In the source the grouping lived in `_param_groups`, a Python function. It is rows here, for the
-- reason recorded in docs/extensibility.md: a laboratory adding a parameter, or moving one between
-- sections, needs no new behaviour.
--
-- A parameter with no row still prints - in an "Other" section at the end - rather than vanishing.
-- Silently dropping a measured value off a clinical report because nobody configured its section
-- would be the worst possible failure mode for a table like this.

CREATE TABLE report_parameter_groups (
    parameter     varchar(24)  PRIMARY KEY,
    group_code    varchar(16)  NOT NULL,
    display_order smallint     NOT NULL DEFAULT 100,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_groups_group ON report_parameter_groups (group_code, display_order);

CREATE TABLE report_groups (
    code          varchar(16)  PRIMARY KEY,
    title         varchar(80)  NOT NULL,
    display_order smallint     NOT NULL DEFAULT 100,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

INSERT INTO report_groups (code, title, display_order) VALUES
    ('RBC',      'Red blood cell (RBC) parameters',   10),
    ('WBC',      'White blood cell (WBC) parameters', 20),
    ('PLATELET', 'Platelet parameters',               30),
    ('OTHER',    'Other parameters',                  90);

-- Order within a section follows how the parameters are read, not the alphabet: haemoglobin first
-- because it is the number everyone looks for, then the count, then the indices derived from them.
INSERT INTO report_parameter_groups (parameter, group_code, display_order) VALUES
    ('HGB',    'RBC',      10),
    ('RBC',    'RBC',      20),
    ('HCT',    'RBC',      30),
    ('MCV',    'RBC',      40),
    ('MCH',    'RBC',      50),
    ('MCHC',   'RBC',      60),
    ('RDW-CV', 'RBC',      70),
    ('RDW-SD', 'RBC',      80),

    ('WBC',    'WBC',      10),
    ('NEUT%',  'WBC',      20),
    ('LYM%',   'WBC',      30),
    ('MONO%',  'WBC',      40),
    ('EOS%',   'WBC',      50),
    ('BASO%',  'WBC',      60),
    ('MXD%',   'WBC',      70),
    ('NEUT#',  'WBC',      80),
    ('LYM#',   'WBC',      90),
    ('MONO#',  'WBC',     100),
    ('EOS#',   'WBC',     110),
    ('BASO#',  'WBC',     120),
    ('MXD#',   'WBC',     130),

    ('PLT',    'PLATELET', 10),
    ('PCT',    'PLATELET', 20),
    ('MPV',    'PLATELET', 30),
    ('PDW',    'PLATELET', 40),
    ('P-LCR',  'PLATELET', 50);
