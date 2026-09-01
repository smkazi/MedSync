-- scheduling schema: when a patient is seen, by whom, and what was recorded.

-- btree_gist lets a UNIQUE-style constraint span a range type, which is how
-- double-booking is prevented in the database rather than in application code.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A clinician's bookable hours, per weekday.
CREATE TABLE clinician_schedules (
    id             uuid        PRIMARY KEY,
    clinician_id   uuid        NOT NULL,
    department_code varchar(16) NOT NULL,
    day_of_week    integer     NOT NULL,
    start_time     time        NOT NULL,
    end_time       time        NOT NULL,
    slot_minutes   integer     NOT NULL DEFAULT 15,
    active         boolean     NOT NULL DEFAULT true,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_schedule_window CHECK (end_time > start_time),
    CONSTRAINT chk_slot_minutes CHECK (slot_minutes BETWEEN 5 AND 240),
    CONSTRAINT uq_clinician_day UNIQUE (clinician_id, day_of_week, start_time)
);
CREATE INDEX idx_schedules_clinician ON clinician_schedules (clinician_id);

-- Dates a clinician is unavailable regardless of their weekly pattern.
CREATE TABLE schedule_blackouts (
    id           uuid        PRIMARY KEY,
    clinician_id uuid        NOT NULL,
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL,
    reason       varchar(255),
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_blackout_window CHECK (ends_at > starts_at)
);
CREATE INDEX idx_blackouts_clinician ON schedule_blackouts (clinician_id, starts_at);

CREATE TABLE appointments (
    id              uuid        PRIMARY KEY,
    patient_id      uuid        NOT NULL,
    patient_mrn     varchar(24) NOT NULL,
    clinician_id    uuid        NOT NULL,
    clinician_name  varchar(160),
    department_code varchar(16) NOT NULL,
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'BOOKED',
    priority        varchar(16) NOT NULL DEFAULT 'ROUTINE',
    reason          varchar(500),
    booked_by       varchar(64) NOT NULL,
    checked_in_at   timestamptz,
    cancelled_reason varchar(255),
    -- Cached at booking from ai-service. Nullable on purpose: a decision-support
    -- outage must never block a booking.
    no_show_risk    numeric(5,4),
    no_show_band    varchar(8),
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_appointment_window CHECK (ends_at > starts_at),
    CONSTRAINT chk_appointment_status CHECK (status IN ('BOOKED', 'CHECKED_IN', 'IN_PROGRESS',
                                                       'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT chk_appointment_priority CHECK (priority IN ('ROUTINE', 'URGENT', 'STAT'))
);

-- The double-booking guard. A clinician cannot hold two overlapping appointments;
-- cancelled and no-show slots are excluded so the time becomes bookable again.
-- Enforced by PostgreSQL, so two concurrent bookings cannot both pass a check-then-insert.
ALTER TABLE appointments ADD CONSTRAINT no_overlapping_appointments
    EXCLUDE USING gist (
        clinician_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));

CREATE INDEX idx_appointments_patient ON appointments (patient_id, starts_at DESC);
CREATE INDEX idx_appointments_clinician_day ON appointments (clinician_id, starts_at);
CREATE INDEX idx_appointments_status ON appointments (status);

CREATE TABLE encounters (
    id             uuid        PRIMARY KEY,
    appointment_id uuid        UNIQUE REFERENCES appointments (id) ON DELETE SET NULL,
    patient_id     uuid        NOT NULL,
    patient_mrn    varchar(24) NOT NULL,
    clinician_id   uuid        NOT NULL,
    department_code varchar(16) NOT NULL,
    encounter_type varchar(16) NOT NULL DEFAULT 'OUTPATIENT',
    started_at     timestamptz NOT NULL DEFAULT now(),
    ended_at       timestamptz,
    status         varchar(16) NOT NULL DEFAULT 'OPEN',
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_encounter_type CHECK (encounter_type IN ('OUTPATIENT', 'INPATIENT', 'EMERGENCY',
                                                           'TELEHEALTH')),
    CONSTRAINT chk_encounter_status CHECK (status IN ('OPEN', 'CLOSED'))
);
CREATE INDEX idx_encounters_patient ON encounters (patient_id, started_at DESC);

-- Clinical notes are versioned and append-only after signing: a signed note is a legal
-- record, so a correction is an addendum, never an overwrite.
CREATE TABLE clinical_notes (
    id           uuid        PRIMARY KEY,
    encounter_id uuid        NOT NULL REFERENCES encounters (id) ON DELETE CASCADE,
    subjective   text,
    objective    text,
    assessment   text,
    plan         text,
    author       varchar(64) NOT NULL,
    signed_at    timestamptz,
    signed_by    varchar(64),
    -- Set on an addendum, pointing at the signed note it amends.
    amends_id    uuid        REFERENCES clinical_notes (id) ON DELETE SET NULL,
    revision     integer     NOT NULL DEFAULT 1,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notes_encounter ON clinical_notes (encounter_id, revision);

CREATE TABLE vitals (
    id                uuid        PRIMARY KEY,
    encounter_id      uuid        NOT NULL REFERENCES encounters (id) ON DELETE CASCADE,
    recorded_at       timestamptz NOT NULL DEFAULT now(),
    recorded_by       varchar(64) NOT NULL,
    heart_rate        integer,
    systolic_bp       integer,
    diastolic_bp      integer,
    respiratory_rate  integer,
    temperature_c     numeric(4,1),
    oxygen_saturation integer,
    weight_kg         numeric(5,2),
    height_cm         numeric(5,1),
    pain_score        integer,
    consciousness     varchar(16),
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_pain_score CHECK (pain_score IS NULL OR pain_score BETWEEN 0 AND 10),
    CONSTRAINT chk_spo2 CHECK (oxygen_saturation IS NULL OR oxygen_saturation BETWEEN 0 AND 100)
);
CREATE INDEX idx_vitals_encounter ON vitals (encounter_id, recorded_at DESC);

CREATE TABLE diagnoses (
    id           uuid        PRIMARY KEY,
    encounter_id uuid        NOT NULL REFERENCES encounters (id) ON DELETE CASCADE,
    icd10_code   varchar(16) NOT NULL,
    description  varchar(255) NOT NULL,
    -- PRIMARY, SECONDARY or PROVISIONAL.
    category     varchar(16) NOT NULL DEFAULT 'SECONDARY',
    recorded_by  varchar(64) NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_diagnosis_category CHECK (category IN ('PRIMARY', 'SECONDARY', 'PROVISIONAL')),
    CONSTRAINT uq_encounter_code UNIQUE (encounter_id, icd10_code)
);
CREATE INDEX idx_diagnoses_encounter ON diagnoses (encounter_id);

-- A demo weekly pattern for the seeded clinicians, so the booking screens have slots
-- to show on a fresh database. Clinician ids are resolved by the caller.
INSERT INTO clinician_schedules (id, clinician_id, department_code, day_of_week, start_time, end_time,
                                slot_minutes)
SELECT
    gen_random_uuid(),
    '55555555-0000-4000-8000-000000000001',
    'GEN',
    day,
    time '09:00',
    time '13:00',
    15
FROM generate_series(1, 5) AS day;
