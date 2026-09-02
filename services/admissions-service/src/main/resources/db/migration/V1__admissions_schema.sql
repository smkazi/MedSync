-- Casualty and in-patient admissions.
--
-- One service for both, because they contend for the same beds. An appointment is a point on a
-- calendar; an admission is a stay lasting days, driven by acuity rather than by a diary. They
-- share no query shape with scheduling, which is why this is not folded into it -- but casualty
-- and the wards share the one thing that must never be got wrong, which is who is in which bed.

-- Who is in which bed, for both paths, in one table.
--
-- This is the design decision the rest of the schema hangs off. The obvious shape is an
-- `occupied` flag on the bed, or an occupancy table per path -- and both are wrong for the same
-- reason: two tables with two indexes leaves nothing stopping one bed appearing in both, and
-- application code keeping two indexes consistent is exactly the bug a constraint should be
-- preventing. So there is one table, both paths write through it, and one partial unique index
-- makes double occupancy unrepresentable rather than merely unlikely.
--
-- Released rather than deleted: "who was in bed 4 last Tuesday" is a real question after an
-- infection-control incident, and a row that is deleted on discharge cannot answer it.
CREATE TABLE bed_occupancy (
    id            uuid        PRIMARY KEY,
    version       bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- Another service's id, like every cross-service reference here: no foreign key, because this
    -- service must not fail because patient-service is mid-migration.
    bed_id        uuid        NOT NULL,
    bed_code      varchar(24) NOT NULL,
    room_code     varchar(24) NOT NULL,
    -- CASUALTY or ADMISSION. Which path put the patient there, so a release can be traced back.
    occupant_type varchar(16) NOT NULL,
    occupant_id   uuid        NOT NULL,
    since         timestamptz NOT NULL DEFAULT now(),
    released_at   timestamptz,
    CONSTRAINT chk_occupant_type CHECK (occupant_type IN ('CASUALTY', 'ADMISSION'))
);

-- The whole point. One patient per bed, enforced by the database rather than by remembering to
-- check: two clinicians allocating the last bed at the same instant both pass an application-level
-- "is it free?" and one of them then loses this insert, which is the design.
CREATE UNIQUE INDEX uq_bed_occupied ON bed_occupancy (bed_id) WHERE released_at IS NULL;
-- "Who is this attendance or admission in?" -- one row while current, several once historical.
CREATE INDEX idx_occupancy_occupant ON bed_occupancy (occupant_type, occupant_id);

CREATE TABLE casualty_attendances (
    id                   uuid         PRIMARY KEY,
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    patient_id           uuid         NOT NULL,
    patient_mrn          varchar(24)  NOT NULL,
    arrived_at           timestamptz  NOT NULL DEFAULT now(),
    -- Manchester-style: 1 is immediate, 5 is non-urgent. The queue orders by this before arrival
    -- time, which is the entire clinical point of the module -- a casualty queue ordered by
    -- arrival kills people.
    triage_acuity        smallint     NOT NULL,
    presenting_complaint varchar(255) NOT NULL,
    bed_id               uuid,
    bed_code             varchar(24),
    room_code            varchar(24),
    -- WAITING -> IN_BED -> ADMITTED | DISCHARGED | LEFT_WITHOUT_BEING_SEEN
    status               varchar(32)  NOT NULL,
    -- Set when the attendance becomes an admission, so the two halves of one visit join up.
    admission_id         uuid,
    closed_at            timestamptz,
    triaged_by           varchar(64)  NOT NULL,
    CONSTRAINT chk_acuity CHECK (triage_acuity BETWEEN 1 AND 5),
    CONSTRAINT chk_attendance_status CHECK (status IN
        ('WAITING', 'IN_BED', 'ADMITTED', 'DISCHARGED', 'LEFT_WITHOUT_BEING_SEEN')),
    -- A bed is either fully recorded or not recorded. Three columns that can half-agree are three
    -- columns that will.
    CONSTRAINT chk_attendance_bed CHECK (
        (bed_id IS NULL AND bed_code IS NULL AND room_code IS NULL)
        OR (bed_id IS NOT NULL AND bed_code IS NOT NULL AND room_code IS NOT NULL))
);

-- The board's only query: everybody still open, sickest first, then longest waiting.
CREATE INDEX idx_casualty_open ON casualty_attendances (triage_acuity, arrived_at)
    WHERE status IN ('WAITING', 'IN_BED');
CREATE INDEX idx_casualty_patient ON casualty_attendances (patient_id, arrived_at DESC);

CREATE TABLE admissions (
    id                     uuid         PRIMARY KEY,
    version                bigint       NOT NULL DEFAULT 0,
    created_at             timestamptz  NOT NULL DEFAULT now(),
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    patient_id             uuid         NOT NULL,
    patient_mrn            varchar(24)  NOT NULL,
    -- The casualty attendance this came from, when it came from one. Null for a planned
    -- admission, which is a real state rather than missing data.
    attendance_id          uuid,
    bed_id                 uuid         NOT NULL,
    bed_code               varchar(24)  NOT NULL,
    room_code              varchar(24)  NOT NULL,
    admitting_clinician_id uuid         NOT NULL,
    -- CASUALTY, ELECTIVE, TRANSFER, MATERNITY. Where the patient came from, which is what the
    -- census is grouped by and what a bed-day charge is priced from.
    source                 varchar(24)  NOT NULL,
    admitted_at            timestamptz  NOT NULL DEFAULT now(),
    expected_discharge     date,
    discharged_at          timestamptz,
    discharge_summary      varchar(1000),
    status                 varchar(16)  NOT NULL,
    CONSTRAINT chk_admission_status CHECK (status IN ('ADMITTED', 'DISCHARGED')),
    CONSTRAINT chk_admission_source CHECK (source IN
        ('CASUALTY', 'ELECTIVE', 'TRANSFER', 'MATERNITY')),
    -- Discharged means discharged: a status and a timestamp that can disagree is a census that
    -- counts a patient who went home on Tuesday.
    CONSTRAINT chk_discharged CHECK (
        (status = 'ADMITTED' AND discharged_at IS NULL)
        OR (status = 'DISCHARGED' AND discharged_at IS NOT NULL))
);

CREATE INDEX idx_admissions_open ON admissions (room_code, admitted_at) WHERE status = 'ADMITTED';
CREATE INDEX idx_admissions_patient ON admissions (patient_id, admitted_at DESC);

-- Every move, with the reason.
--
-- Its own table rather than an updated column on the admission, because a ward move is a fact
-- with a time: "how many times was this patient moved overnight" is an infection-control and a
-- quality question, and overwriting bed_code answers neither.
CREATE TABLE bed_transfers (
    id           uuid         PRIMARY KEY,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    admission_id uuid         NOT NULL REFERENCES admissions (id),
    from_bed_id  uuid         NOT NULL,
    from_bed_code varchar(24) NOT NULL,
    to_bed_id    uuid         NOT NULL,
    to_bed_code  varchar(24)  NOT NULL,
    moved_at     timestamptz  NOT NULL DEFAULT now(),
    moved_by     varchar(64)  NOT NULL,
    reason       varchar(255) NOT NULL,
    -- A transfer to the bed the patient is already in is not a transfer.
    CONSTRAINT chk_transfer_moves CHECK (from_bed_id <> to_bed_id)
);

CREATE INDEX idx_transfers_admission ON bed_transfers (admission_id, moved_at DESC);
