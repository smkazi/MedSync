-- Radiology: what was asked for, what was scanned, and what the radiologist said about it.
--
-- This is a RIS and it is not a PACS, which is a distinction worth making in the schema rather than
-- in a paragraph nobody reads. A PACS stores and serves pixels over DICOM's own network protocol.
-- What lives here is the record around them: the order, the worklist a modality reads before it
-- scans, the registry of what came back, and the report. The pixels stay wherever the archive puts
-- them and this table points at them.
--
-- Two decisions shape the rest of it.
--
-- **Identity is the modality's, not ours.** A study, a series and an instance each have a UID
-- assigned by whatever produced them, and those UIDs are globally unique by construction. Minting
-- our own primary key and treating theirs as a detail would make every later reconciliation -- with
-- an archive, with another hospital, with a viewer -- a translation. So the UIDs are unique columns
-- and the joins hang off them.
--
-- **The accession number is the link back to the order**, exactly as it is in the laboratory. The
-- modality copies it off the worklist and writes it into every image, so it is the one field that
-- survives the trip out to the scanner and back. Matching on patient identifiers instead would file
-- a study against the wrong visit the first time somebody was scanned twice in a day.

CREATE TABLE imaging_orders (
    id uuid PRIMARY KEY,
    patient_id uuid NOT NULL,
    patient_mrn varchar(24) NOT NULL,
    -- Copied onto the order rather than looked up, for the reason the laboratory copies them: a
    -- study must be interpretable years later from the order alone, without a call to a service
    -- that may have changed or be unavailable. A radiologist reporting a pelvis needs to know
    -- whether they are looking at a man or a woman.
    patient_sex varchar(1) NOT NULL DEFAULT 'O',
    patient_birth_date date,

    encounter_id uuid,

    -- What was asked for.
    modality varchar(16) NOT NULL,
    body_part varchar(64),
    procedure_code varchar(32) NOT NULL,
    procedure_name varchar(160) NOT NULL,
    -- Why. Required, and not by convention: a radiologist reporting a film with no clinical
    -- question is guessing at what they are being asked, and "?" is what a box collects when it
    -- does not insist.
    clinical_question varchar(1000) NOT NULL,
    contrast boolean NOT NULL DEFAULT false,

    priority varchar(16) NOT NULL DEFAULT 'ROUTINE',
    status varchar(16) NOT NULL DEFAULT 'ORDERED',

    ordered_by varchar(64) NOT NULL,
    ordered_at timestamptz NOT NULL DEFAULT now(),

    -- Issued when the order is made, because the worklist needs it before the scan happens. This
    -- is the number the modality writes into every image it produces.
    accession_no varchar(24) NOT NULL UNIQUE,

    scheduled_for timestamptz,
    cancelled_reason varchar(255),

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_imaging_status CHECK (status IN
        ('ORDERED', 'SCHEDULED', 'IN_PROGRESS', 'ACQUIRED', 'REPORTED', 'CANCELLED')),
    CONSTRAINT chk_imaging_priority CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    CONSTRAINT chk_imaging_question CHECK (btrim(clinical_question) <> ''),
    CONSTRAINT chk_imaging_sex CHECK (patient_sex IN ('M', 'F', 'O'))
);

CREATE INDEX idx_imaging_order_patient ON imaging_orders (patient_id, ordered_at DESC);
-- The worklist: what has been asked for and not yet acquired, urgent first. A modality asks this
-- question every few minutes, so it gets a partial index rather than a scan of every study ever
-- ordered.
CREATE INDEX idx_imaging_worklist ON imaging_orders (priority, scheduled_for)
    WHERE status IN ('ORDERED', 'SCHEDULED', 'IN_PROGRESS');

-- Accession numbers come from a sequence, so two concurrent orders can never be handed the same
-- one -- which would put two patients' images in one study.
CREATE SEQUENCE imaging_accession_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE imaging_studies (
    id uuid PRIMARY KEY,
    -- The modality's own identifier, and the natural key. Unique because it is unique in the world.
    study_instance_uid varchar(64) NOT NULL UNIQUE,

    -- Null when nothing matched. An unmatched study is kept and reported rather than discarded or
    -- guessed at: filing images against the wrong patient is worse than filing them against none,
    -- and the images exist whatever this platform thinks of them.
    order_id uuid REFERENCES imaging_orders (id),

    accession_no varchar(24),
    patient_id uuid,
    patient_mrn varchar(24),

    modality varchar(16),
    study_description varchar(160),
    study_date date,
    institution varchar(120),
    referring_physician varchar(160),

    received_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_study_order ON imaging_studies (order_id);
CREATE INDEX idx_study_accession ON imaging_studies (accession_no);
-- Everything that arrived and matched nothing. Partial, because these are the minority and finding
-- them is the whole reason to look.
CREATE INDEX idx_study_unmatched ON imaging_studies (received_at DESC) WHERE order_id IS NULL;

CREATE TABLE imaging_series (
    id uuid PRIMARY KEY,
    series_instance_uid varchar(64) NOT NULL UNIQUE,
    study_id uuid NOT NULL REFERENCES imaging_studies (id) ON DELETE CASCADE,
    series_number integer,
    modality varchar(16),
    series_description varchar(160),
    body_part varchar(64),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_series_study ON imaging_series (study_id, series_number);

CREATE TABLE imaging_instances (
    id uuid PRIMARY KEY,
    sop_instance_uid varchar(64) NOT NULL UNIQUE,
    series_id uuid NOT NULL REFERENCES imaging_series (id) ON DELETE CASCADE,
    sop_class_uid varchar(64),
    instance_number integer,
    rows_count integer,
    columns_count integer,
    transfer_syntax_uid varchar(64),

    -- Where the pixels are, and deliberately not the pixels. This platform is not an archive: it
    -- records that an instance exists, what it is of and where it was put, and a viewer or a PACS
    -- reads the file. Storing multi-gigabyte studies in a relational database is a decision a
    -- deployment makes with an object store, not one made here by default.
    storage_uri varchar(500),
    byte_count bigint,

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_instance_series ON imaging_instances (series_id, instance_number);

CREATE TABLE imaging_reports (
    id uuid PRIMARY KEY,
    -- One report per study. An amendment is a new version of this row's text with the previous one
    -- kept beside it, not a second report: two reports on one study is two answers to the same
    -- question, and the wrong one will be the one somebody reads.
    study_id uuid NOT NULL UNIQUE REFERENCES imaging_studies (id) ON DELETE CASCADE,

    findings text NOT NULL,
    impression text NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    -- The reporting radiologist. A draft is provisional and only a signed report is releasable,
    -- which is the same separation the laboratory makes between entering a value and verifying it.
    reported_by varchar(64) NOT NULL,
    reported_at timestamptz NOT NULL DEFAULT now(),
    signed_by varchar(64),
    signed_at timestamptz,

    -- Set when a signed report is superseded. The previous text is kept, because a report that was
    -- acted on is part of the record whether or not it was later corrected.
    amended_from text,
    amended_reason varchar(500),

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_report_status CHECK (status IN ('DRAFT', 'SIGNED', 'AMENDED')),
    CONSTRAINT chk_report_findings CHECK (btrim(findings) <> ''),
    CONSTRAINT chk_report_impression CHECK (btrim(impression) <> ''),
    -- A signed report has a signature. Enforced here because "signed" is the word every downstream
    -- reader trusts, and a row that said SIGNED with nobody's name against it would be trusted
    -- exactly as much.
    CONSTRAINT chk_report_signed CHECK (
        (status = 'DRAFT' AND signed_by IS NULL AND signed_at IS NULL)
        OR (status IN ('SIGNED', 'AMENDED') AND signed_by IS NOT NULL AND signed_at IS NOT NULL)
    ),
    CONSTRAINT chk_report_amended CHECK (
        status <> 'AMENDED' OR (amended_from IS NOT NULL AND btrim(coalesce(amended_reason, '')) <> '')
    )
);

-- The catalogue of what can be ordered. Seeded with the common examinations rather than left empty,
-- because an ordering screen with nothing in it is a screen nobody can use on the day it ships.
CREATE TABLE imaging_procedures (
    code varchar(32) PRIMARY KEY,
    name varchar(160) NOT NULL,
    modality varchar(16) NOT NULL,
    body_part varchar(64),
    -- Roughly how long the room is occupied, which is what a scheduling screen needs and what
    -- makes a worklist estimate anything at all.
    minutes integer NOT NULL DEFAULT 15,
    contrast boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO imaging_procedures (code, name, modality, body_part, minutes, contrast) VALUES
    ('XR_CHEST_PA',  'Chest X-ray, PA',                  'CR', 'CHEST',   10, false),
    ('XR_CHEST_LAT', 'Chest X-ray, lateral',             'CR', 'CHEST',   10, false),
    ('XR_ABDO',      'Abdomen X-ray, supine',            'CR', 'ABDOMEN', 10, false),
    ('XR_KNEE',      'Knee X-ray, two views',            'CR', 'KNEE',    10, false),
    ('CT_HEAD',      'CT head, non-contrast',            'CT', 'HEAD',    15, false),
    ('CT_ABDO_C',    'CT abdomen and pelvis with contrast', 'CT', 'ABDOMEN', 30, true),
    ('CT_CHEST_PE',  'CT pulmonary angiogram',           'CT', 'CHEST',   30, true),
    ('US_ABDO',      'Ultrasound abdomen',               'US', 'ABDOMEN', 30, false),
    ('US_OBS',       'Ultrasound obstetric',             'US', 'PELVIS',  30, false),
    ('MR_BRAIN',     'MRI brain',                        'MR', 'HEAD',    45, false),
    ('MR_LSPINE',    'MRI lumbar spine',                 'MR', 'LSPINE',  45, false),
    ('MG_BILAT',     'Mammogram, bilateral',             'MG', 'BREAST',  20, false);
