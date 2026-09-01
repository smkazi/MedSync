-- patient schema: the demographic record every other service points at.

-- Trigram indexes make "search as you type" over names and MRNs fast without a
-- separate search engine. Requires the pg_trgm extension (superuser on first install).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE departments (
    id          uuid         PRIMARY KEY,
    code        varchar(16)  NOT NULL UNIQUE,
    name        varchar(120) NOT NULL,
    description varchar(500),
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

-- Clinical staff. user_id links to identity.users but is not a foreign key:
-- a service must not depend on another service's tables.
CREATE TABLE staff (
    id            uuid         PRIMARY KEY,
    user_id       uuid         UNIQUE,
    employee_no   varchar(32)  NOT NULL UNIQUE,
    full_name     varchar(160) NOT NULL,
    department_id uuid         REFERENCES departments (id),
    designation   varchar(64)  NOT NULL,
    specialty     varchar(120),
    license_no    varchar(64),
    phone         varchar(32),
    email         varchar(255),
    active        boolean      NOT NULL DEFAULT true,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_staff_department ON staff (department_id);
CREATE INDEX idx_staff_name_trgm ON staff USING gin (lower(full_name) gin_trgm_ops);

-- Medical record numbers are issued from a sequence so two concurrent
-- registrations can never be handed the same MRN.
CREATE SEQUENCE mrn_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE patients (
    id                      uuid         PRIMARY KEY,
    mrn                     varchar(24)  NOT NULL UNIQUE,
    first_name              varchar(80)  NOT NULL,
    last_name               varchar(80)  NOT NULL,
    date_of_birth           date         NOT NULL,
    sex                     varchar(16)  NOT NULL,
    blood_group             varchar(8),
    phone                   varchar(32),
    email                   varchar(255),
    address_line1           varchar(160),
    address_line2           varchar(160),
    city                    varchar(80),
    state                   varchar(80),
    postal_code             varchar(16),
    country                 varchar(80),
    -- AES-256-GCM ciphertext, never searched, never logged.
    national_id             varchar(255),
    insurance_provider      varchar(120),
    insurance_policy_no     varchar(255),
    emergency_contact_name  varchar(160),
    emergency_contact_phone varchar(32),
    notes                   varchar(2000),
    -- Registration is soft-deleted: a chart is archived, never erased.
    active                  boolean      NOT NULL DEFAULT true,
    deceased                boolean      NOT NULL DEFAULT false,
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_patient_sex CHECK (sex IN ('MALE', 'FEMALE', 'OTHER', 'UNKNOWN'))
);
CREATE INDEX idx_patients_mrn_trgm  ON patients USING gin (lower(mrn) gin_trgm_ops);
CREATE INDEX idx_patients_name_trgm ON patients USING gin (lower(first_name || ' ' || last_name) gin_trgm_ops);
CREATE INDEX idx_patients_phone     ON patients (phone);
CREATE INDEX idx_patients_dob       ON patients (date_of_birth);

CREATE TABLE patient_allergies (
    id          uuid         PRIMARY KEY,
    patient_id  uuid         NOT NULL REFERENCES patients (id) ON DELETE CASCADE,
    substance   varchar(120) NOT NULL,
    reaction    varchar(255),
    severity    varchar(16)  NOT NULL,
    recorded_by varchar(64),
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_allergy_severity CHECK (severity IN ('MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING')),
    CONSTRAINT uq_patient_substance UNIQUE (patient_id, substance)
);
CREATE INDEX idx_allergies_patient ON patient_allergies (patient_id);

INSERT INTO departments (id, code, name, description) VALUES
    ('22222222-0000-4000-8000-000000000001', 'GEN',  'General Medicine',   'Outpatient and inpatient general medicine'),
    ('22222222-0000-4000-8000-000000000002', 'CARD', 'Cardiology',         'Cardiac diagnostics and care'),
    ('22222222-0000-4000-8000-000000000003', 'PAED', 'Paediatrics',        'Child and adolescent health'),
    ('22222222-0000-4000-8000-000000000004', 'ORTH', 'Orthopaedics',       'Musculoskeletal and trauma care'),
    ('22222222-0000-4000-8000-000000000005', 'EMER', 'Emergency',          'Emergency department and triage'),
    ('22222222-0000-4000-8000-000000000006', 'PATH', 'Pathology',          'Laboratory and diagnostic services');
