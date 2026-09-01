-- laboratory schema: orders, specimens, results, and what the analyzers sent.

CREATE TABLE lab_test_catalog (
    id            uuid         PRIMARY KEY,
    code          varchar(24)  NOT NULL UNIQUE,
    name          varchar(160) NOT NULL,
    department    varchar(32)  NOT NULL DEFAULT 'HAEMATOLOGY',
    specimen_type varchar(32)  NOT NULL DEFAULT 'WHOLE_BLOOD',
    -- Parameters this panel is expected to report, so an order knows what is outstanding.
    parameters    varchar(1000) NOT NULL DEFAULT '',
    active        boolean      NOT NULL DEFAULT true,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- Sex-specific normal ranges. A result is flagged high/low against these when the
-- analyzer did not flag it itself.
CREATE TABLE reference_ranges (
    id           uuid         PRIMARY KEY,
    parameter    varchar(24)  NOT NULL,
    sex          varchar(1)   NOT NULL,
    normal_low   numeric(12,4),
    normal_high  numeric(12,4),
    unit         varchar(24)  NOT NULL DEFAULT '',
    display_name varchar(80)  NOT NULL DEFAULT '',
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_range_sex CHECK (sex IN ('M', 'F')),
    CONSTRAINT uq_range_parameter_sex UNIQUE (parameter, sex)
);

CREATE TABLE analyzers (
    id         uuid         PRIMARY KEY,
    name       varchar(80)  NOT NULL UNIQUE,
    model      varchar(80)  NOT NULL,
    serial_no  varchar(64),
    -- ASTM (E1394/LIS2-A2 text records) or KDPS (Sysmex fixed-offset binary).
    protocol   varchar(16)  NOT NULL DEFAULT 'ASTM',
    transport  varchar(16)  NOT NULL DEFAULT 'TCP',
    active     boolean      NOT NULL DEFAULT true,
    last_seen  timestamptz,
    version    bigint       NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_analyzer_protocol CHECK (protocol IN ('ASTM', 'KDPS'))
);

-- patient_id references patient.patients but is not a foreign key: services own
-- their own schemas, and a lab order must survive a patient-service outage.
CREATE TABLE lab_orders (
    id             uuid         PRIMARY KEY,
    patient_id     uuid         NOT NULL,
    patient_mrn    varchar(24)  NOT NULL,
    patient_sex    varchar(1)   NOT NULL DEFAULT 'M',
    ordered_by     varchar(64)  NOT NULL,
    department     varchar(32)  NOT NULL DEFAULT 'PATH',
    priority       varchar(16)  NOT NULL DEFAULT 'ROUTINE',
    status         varchar(16)  NOT NULL DEFAULT 'ORDERED',
    clinical_notes varchar(1000),
    ordered_at     timestamptz  NOT NULL DEFAULT now(),
    version        bigint       NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_priority CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT')),
    CONSTRAINT chk_order_status CHECK (status IN ('ORDERED', 'COLLECTED', 'IN_PROGRESS', 'RESULTED',
                                                 'VERIFIED', 'CANCELLED')),
    CONSTRAINT chk_order_sex CHECK (patient_sex IN ('M', 'F'))
);
CREATE INDEX idx_lab_orders_patient ON lab_orders (patient_id);
CREATE INDEX idx_lab_orders_status  ON lab_orders (status);
CREATE INDEX idx_lab_orders_mrn     ON lab_orders (patient_mrn);

CREATE TABLE lab_order_items (
    id         uuid        PRIMARY KEY,
    order_id   uuid        NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    test_code  varchar(24) NOT NULL,
    test_name  varchar(160) NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_order_test UNIQUE (order_id, test_code)
);

-- Accession numbers come from a sequence: two specimens received at the same
-- moment must never share one.
CREATE SEQUENCE accession_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE specimens (
    id            uuid        PRIMARY KEY,
    order_id      uuid        NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    accession_no  varchar(24) NOT NULL UNIQUE,
    specimen_type varchar(32) NOT NULL DEFAULT 'WHOLE_BLOOD',
    collected_at  timestamptz,
    received_at   timestamptz,
    collected_by  varchar(64),
    status        varchar(16) NOT NULL DEFAULT 'PENDING',
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_specimen_status CHECK (status IN ('PENDING', 'COLLECTED', 'RECEIVED', 'REJECTED'))
);
CREATE INDEX idx_specimens_order ON specimens (order_id);

CREATE TABLE lab_results (
    id           uuid         PRIMARY KEY,
    order_id     uuid         NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    specimen_id  uuid         REFERENCES specimens (id) ON DELETE SET NULL,
    parameter    varchar(24)  NOT NULL,
    value        varchar(64),
    unit         varchar(24)  NOT NULL DEFAULT '',
    normal_low   numeric(12,4),
    normal_high  numeric(12,4),
    -- H (high), L (low) or blank. Derived from the reference range when the
    -- analyzer did not flag it, so any out-of-range value is highlighted.
    flag         varchar(2)   NOT NULL DEFAULT '',
    ref_text     varchar(64)  NOT NULL DEFAULT '',
    source       varchar(16)  NOT NULL DEFAULT 'ANALYZER',
    status       varchar(16)  NOT NULL DEFAULT 'ENTERED',
    entered_by   varchar(64),
    verified_by  varchar(64),
    verified_at  timestamptz,
    analyzer_id  uuid         REFERENCES analyzers (id) ON DELETE SET NULL,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_result_source CHECK (source IN ('ANALYZER', 'MANUAL', 'DERIVED')),
    CONSTRAINT chk_result_status CHECK (status IN ('ENTERED', 'VERIFIED', 'AMENDED')),
    CONSTRAINT chk_result_flag CHECK (flag IN ('', 'H', 'L')),
    CONSTRAINT uq_result_order_parameter UNIQUE (order_id, parameter)
);
CREATE INDEX idx_lab_results_order ON lab_results (order_id);
CREATE INDEX idx_lab_results_status ON lab_results (status);

-- Distribution curves, stored as JSON because their shape is the analyzer's, not ours.
CREATE TABLE histograms (
    id          uuid        PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES lab_orders (id) ON DELETE CASCADE,
    specimen_id uuid        REFERENCES specimens (id) ON DELETE SET NULL,
    group_code  varchar(8)  NOT NULL,
    curve       jsonb       NOT NULL,
    indices     jsonb,
    version     bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_histogram_order_group UNIQUE (order_id, group_code)
);

-- Every analyzer transmission is retained verbatim. When a result is questioned,
-- the raw frame is the evidence of what the instrument actually sent.
CREATE TABLE device_messages (
    id           uuid        PRIMARY KEY,
    analyzer_id  uuid        REFERENCES analyzers (id) ON DELETE SET NULL,
    protocol     varchar(16) NOT NULL,
    raw_payload  text        NOT NULL,
    payload_bytes integer    NOT NULL DEFAULT 0,
    sample_id    varchar(64),
    matched_order_id uuid    REFERENCES lab_orders (id) ON DELETE SET NULL,
    parsed_ok    boolean     NOT NULL DEFAULT false,
    result_count integer     NOT NULL DEFAULT 0,
    error        varchar(500),
    received_at  timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_message_protocol CHECK (protocol IN ('ASTM', 'KDPS'))
);
CREATE INDEX idx_device_messages_received ON device_messages (received_at DESC);
CREATE INDEX idx_device_messages_sample ON device_messages (sample_id);

INSERT INTO lab_test_catalog (id, code, name, department, specimen_type, parameters) VALUES
    ('33333333-0000-4000-8000-000000000001', 'CBC', 'Complete Blood Count', 'HAEMATOLOGY', 'WHOLE_BLOOD',
     'WBC,RBC,HGB,HCT,MCV,MCH,MCHC,PLT,RDW-CV,RDW-SD,PDW,MPV,P-LCR,LYM%,MXD%,NEUT%,LYM#,MXD#,NEUT#'),
    ('33333333-0000-4000-8000-000000000002', 'CBC5', 'CBC with 5-part Differential', 'HAEMATOLOGY',
     'WHOLE_BLOOD',
     'WBC,RBC,HGB,HCT,MCV,MCH,MCHC,PLT,NEUT%,LYM%,MONO%,EOS%,BASO%,NEUT#,LYM#,MONO#,EOS#,BASO#'),
    ('33333333-0000-4000-8000-000000000003', 'ESR', 'Erythrocyte Sedimentation Rate', 'HAEMATOLOGY',
     'WHOLE_BLOOD', 'ESR'),
    ('33333333-0000-4000-8000-000000000004', 'PLTC', 'Platelet Count', 'HAEMATOLOGY', 'WHOLE_BLOOD',
     'PLT,MPV,PDW,P-LCR,PCT');

-- Adult reference ranges for the CBC parameters the analyzer parsers emit.
-- HGB, RBC and HCT are sex-specific; the rest share one range.
-- WBC and PLT are held on the analyzer's transmitted 10^3/uL scale.
INSERT INTO reference_ranges (id, parameter, sex, normal_low, normal_high, unit, display_name) VALUES
    (gen_random_uuid(), 'WBC',    'M',   4.0,  11.0,  '10^3/uL', 'WBC Count'),
    (gen_random_uuid(), 'WBC',    'F',   4.0,  11.0,  '10^3/uL', 'WBC Count'),
    (gen_random_uuid(), 'RBC',    'M',   3.5,   6.0,  '10^6/uL', 'RBC Count'),
    (gen_random_uuid(), 'RBC',    'F',   3.5,   5.5,  '10^6/uL', 'RBC Count'),
    (gen_random_uuid(), 'HGB',    'M',  13.0,  16.0,  'g/dL',    'Haemoglobin'),
    (gen_random_uuid(), 'HGB',    'F',  11.5,  14.5,  'g/dL',    'Haemoglobin'),
    (gen_random_uuid(), 'HCT',    'M',  35.0,  45.0,  '%',       'PCV / Haematocrit'),
    (gen_random_uuid(), 'HCT',    'F',  33.0,  43.0,  '%',       'PCV / Haematocrit'),
    (gen_random_uuid(), 'MCV',    'M',  76.0,  96.0,  'fL',      'MCV'),
    (gen_random_uuid(), 'MCV',    'F',  76.0,  96.0,  'fL',      'MCV'),
    (gen_random_uuid(), 'MCH',    'M',  25.0,  32.0,  'pg',      'MCH'),
    (gen_random_uuid(), 'MCH',    'F',  25.0,  32.0,  'pg',      'MCH'),
    (gen_random_uuid(), 'MCHC',   'M',  30.0,  36.0,  'g/dL',    'MCHC'),
    (gen_random_uuid(), 'MCHC',   'F',  30.0,  36.0,  'g/dL',    'MCHC'),
    (gen_random_uuid(), 'PLT',    'M', 150.0, 450.0,  '10^3/uL', 'Platelet Count'),
    (gen_random_uuid(), 'PLT',    'F', 150.0, 450.0,  '10^3/uL', 'Platelet Count'),
    (gen_random_uuid(), 'RDW-CV', 'M',  11.0,  14.0,  '%',       'RDW-CV'),
    (gen_random_uuid(), 'RDW-CV', 'F',  11.0,  14.0,  '%',       'RDW-CV'),
    (gen_random_uuid(), 'RDW-SD', 'M',  37.0,  54.0,  'fL',      'RDW-SD'),
    (gen_random_uuid(), 'RDW-SD', 'F',  37.0,  54.0,  'fL',      'RDW-SD'),
    (gen_random_uuid(), 'PDW',    'M',  10.0,  15.0,  '%',       'PDW'),
    (gen_random_uuid(), 'PDW',    'F',  10.0,  15.0,  '%',       'PDW'),
    (gen_random_uuid(), 'MPV',    'M',   7.4,  10.4,  'fL',      'MPV'),
    (gen_random_uuid(), 'MPV',    'F',   7.4,  10.4,  'fL',      'MPV'),
    (gen_random_uuid(), 'P-LCR',  'M',  13.0,  25.0,  '%',       'P-LCR'),
    (gen_random_uuid(), 'P-LCR',  'F',  13.0,  25.0,  '%',       'P-LCR'),
    (gen_random_uuid(), 'PCT',    'M',   0.08,  1.0,  '%',       'PCT'),
    (gen_random_uuid(), 'PCT',    'F',   0.08,  1.0,  '%',       'PCT'),
    (gen_random_uuid(), 'NEUT%',  'M',  45.0,  70.0,  '%',       'Neutrophils %'),
    (gen_random_uuid(), 'NEUT%',  'F',  45.0,  70.0,  '%',       'Neutrophils %'),
    (gen_random_uuid(), 'LYM%',   'M',  25.0,  40.0,  '%',       'Lymphocytes %'),
    (gen_random_uuid(), 'LYM%',   'F',  25.0,  40.0,  '%',       'Lymphocytes %'),
    (gen_random_uuid(), 'MONO%',  'M',   2.0,   8.0,  '%',       'Monocytes %'),
    (gen_random_uuid(), 'MONO%',  'F',   2.0,   8.0,  '%',       'Monocytes %'),
    (gen_random_uuid(), 'EOS%',   'M',   0.0,   7.0,  '%',       'Eosinophils %'),
    (gen_random_uuid(), 'EOS%',   'F',   0.0,   7.0,  '%',       'Eosinophils %'),
    (gen_random_uuid(), 'BASO%',  'M',   0.0,   1.0,  '%',       'Basophils %'),
    (gen_random_uuid(), 'BASO%',  'F',   0.0,   1.0,  '%',       'Basophils %'),
    (gen_random_uuid(), 'MXD%',   'M',   3.0,  17.0,  '%',       'MXD %'),
    (gen_random_uuid(), 'MXD%',   'F',   3.0,  17.0,  '%',       'MXD %'),
    (gen_random_uuid(), 'NEUT#',  'M',   2.0,   7.5,  '10^3/uL', 'Neutrophils #'),
    (gen_random_uuid(), 'NEUT#',  'F',   2.0,   7.5,  '10^3/uL', 'Neutrophils #'),
    (gen_random_uuid(), 'LYM#',   'M',   1.5,   4.0,  '10^3/uL', 'Lymphocytes #'),
    (gen_random_uuid(), 'LYM#',   'F',   1.5,   4.0,  '10^3/uL', 'Lymphocytes #'),
    (gen_random_uuid(), 'MONO#',  'M',   0.2,   0.8,  '10^3/uL', 'Monocytes #'),
    (gen_random_uuid(), 'MONO#',  'F',   0.2,   0.8,  '10^3/uL', 'Monocytes #'),
    (gen_random_uuid(), 'EOS#',   'M',   0.04,  0.4,  '10^3/uL', 'Eosinophils #'),
    (gen_random_uuid(), 'EOS#',   'F',   0.04,  0.4,  '10^3/uL', 'Eosinophils #'),
    (gen_random_uuid(), 'BASO#',  'M',   0.01,  0.1,  '10^3/uL', 'Basophils #'),
    (gen_random_uuid(), 'BASO#',  'F',   0.01,  0.1,  '10^3/uL', 'Basophils #'),
    (gen_random_uuid(), 'MXD#',   'M',   0.1,   1.8,  '10^3/uL', 'MXD #'),
    (gen_random_uuid(), 'MXD#',   'F',   0.1,   1.8,  '10^3/uL', 'MXD #'),
    (gen_random_uuid(), 'ESR',    'M',   0.0,  15.0,  'mm/hr',   'ESR'),
    (gen_random_uuid(), 'ESR',    'F',   0.0,  20.0,  'mm/hr',   'ESR');

INSERT INTO analyzers (id, name, model, protocol, transport) VALUES
    ('44444444-0000-4000-8000-000000000001', 'Haematology-1', 'Sysmex XP-300', 'ASTM', 'TCP'),
    ('44444444-0000-4000-8000-000000000002', 'Haematology-2', 'Sysmex KX-21',  'KDPS', 'RS232');
