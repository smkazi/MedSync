-- The building: floors, rooms and bed positions.
--
-- Rooms live here rather than in scheduling-service because they are master data of
-- the same kind as departments and staff - a directory that changes when the building
-- changes, not when a patient arrives. Occupancy is the opposite, and lives in
-- admissions-service.
--
-- The seeded rows below are REFERENCE DATA, in the same sense as the laboratory's
-- reference ranges: a real ground floor, concrete enough that the platform can be
-- demonstrated and its room-clash guard exercised, and replaced wholesale by any
-- deployment describing its own building. Dimensions are as-drawn and informational -
-- nothing computes with them; they are here so a room is recognisable to whoever has
-- the drawings in front of them.

CREATE TABLE floors (
    id          uuid         PRIMARY KEY,
    code        varchar(8)   NOT NULL UNIQUE,
    name        varchar(60)  NOT NULL,
    -- Signed, so a basement is -1 rather than a special case.
    level       smallint     NOT NULL,
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_floor_level UNIQUE (level)
);

-- Room types are configuration, not code.
--
-- The obvious design is an enum in Java plus a CHECK constraint here, and it is wrong for
-- this platform: adding a dialysis unit, a physiotherapy room or a radiology suite would
-- need a code change, a recompile and a migration, for a hospital that simply has a
-- different set of rooms than the one this was seeded from. So the taxonomy is rows, and
-- the behaviour each type implies is columns on those rows rather than a switch statement
-- somewhere in a service.
--
-- Three flags, because the three questions are genuinely independent:
--
--   clinical       are patients seen or treated here? governs whether beds and clinical
--                  filters apply at all
--   bed_allocated  is space here handed out as a bed rather than a calendar slot? true for
--                  casualty and for wards, where arrivals are unscheduled or stays last days
--   schedulable    may a room of this type carry appointments?
--
-- A type may be clinical without being schedulable (a casualty bay), and schedulable
-- implies clinical and not bed_allocated - which is asserted below rather than assumed,
-- because getting it wrong would let a booked outpatient land in a resuscitation bay.
CREATE TABLE room_types (
    code          varchar(24)  PRIMARY KEY,
    name          varchar(60)  NOT NULL,
    description   varchar(255),
    clinical      boolean      NOT NULL,
    bed_allocated boolean      NOT NULL DEFAULT false,
    schedulable   boolean      NOT NULL DEFAULT false,
    display_order smallint     NOT NULL DEFAULT 100,
    active        boolean      NOT NULL DEFAULT true,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    -- The combinations that would misbehave, refused by the database rather than by whoever
    -- happens to read the row next.
    CONSTRAINT chk_schedulable_is_clinical CHECK (NOT schedulable OR clinical),
    CONSTRAINT chk_schedulable_not_bed_allocated CHECK (NOT (schedulable AND bed_allocated)),
    CONSTRAINT chk_bed_allocated_is_clinical CHECK (NOT bed_allocated OR clinical)
);

INSERT INTO room_types (code, name, description, clinical, bed_allocated, schedulable, display_order) VALUES
    ('CONSULTATION',   'Consulting room',   'Outpatient consultation',                     true,  false, true,  10),
    ('PROCEDURE',      'Procedure room',    'Minor procedures under local anaesthetic',    true,  false, true,  20),
    ('EMERGENCY_BAY',  'Emergency bay',     'Open casualty bay with curtained positions',  true,  true,  false, 30),
    ('EMERGENCY_ROOM', 'Emergency room',    'Enclosed casualty room for privacy',          true,  true,  false, 31),
    ('WARD',           'Ward',              'Multi-bed in-patient ward',                   true,  true,  false, 40),
    ('SUITE',          'Suite',             'Single or twin in-patient suite',             true,  true,  false, 41),
    ('LAB',            'Laboratory',        'Specimen processing and analysis',            true,  false, false, 50),
    ('PHARMACY',       'Pharmacy',          'Dispensary',                                  true,  false, false, 60),
    ('WAITING',        'Waiting area',      'Where patients wait to be called',            false, false, false, 70),
    ('RECEPTION',      'Reception',         'Front desk',                                  false, false, false, 71),
    ('STAFF',          'Staff room',        'On-call room or office',                      false, false, false, 80),
    ('FACILITY',       'Patient facility',  'Non-clinical space for patients and families', false, false, false, 90),
    ('CIRCULATION',    'Circulation',       'Lobby, corridor, lift',                       false, false, false, 95),
    ('SUPPORT',        'Support',           'Storage, plant, toilets',                     false, false, false, 96);

CREATE TABLE rooms (
    id              uuid         PRIMARY KEY,
    code            varchar(16)  NOT NULL UNIQUE,
    name            varchar(120) NOT NULL,
    -- Foreign key, not a CHECK list. A new room type is an INSERT.
    room_type_code  varchar(24)  NOT NULL REFERENCES room_types (code),
    floor_id        uuid         NOT NULL REFERENCES floors (id),
    -- The clinic a consultation room belongs to. Null for anything non-clinical: a
    -- lobby has no department, and pretending it does would put it in clinic filters.
    department_id   uuid         REFERENCES departments (id),
    -- Bed positions the room holds. Zero for a room nobody is treated in. Kept here
    -- as well as being derivable from the beds table, because it is the designed
    -- capacity - which is what you compare actual bed rows against when someone asks
    -- why the casualty bay only has five beds in it.
    capacity        smallint     NOT NULL DEFAULT 0,
    width_ft        numeric(5,2),
    length_ft       numeric(5,2),
    -- What the wayfinding signage says. The patient-facing appointment view renders
    -- this verbatim, so it is written as an instruction to a person, not a label.
    directions      varchar(255),
    -- Whether an appointment may be booked into THIS room. Narrower than the type's
    -- `schedulable` flag: the type says the kind of room can carry appointments, this says
    -- this particular one currently does. A consulting room taken out of service for
    -- refurbishment stays in the directory so old appointments still resolve their
    -- location, with bookable false.
    bookable        boolean      NOT NULL DEFAULT false,
    active          boolean      NOT NULL DEFAULT true,
    notes           varchar(500),
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_room_capacity CHECK (capacity BETWEEN 0 AND 200),
    CONSTRAINT chk_room_dimensions CHECK (
        (width_ft IS NULL OR width_ft > 0) AND (length_ft IS NULL OR length_ft > 0))
);
CREATE INDEX idx_rooms_floor ON rooms (floor_id);
CREATE INDEX idx_rooms_type ON rooms (room_type_code) WHERE active;
CREATE INDEX idx_rooms_department ON rooms (department_id);
CREATE INDEX idx_rooms_name_trgm ON rooms USING gin (lower(name) gin_trgm_ops);

CREATE TABLE beds (
    id          uuid         PRIMARY KEY,
    room_id     uuid         NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    code        varchar(16)  NOT NULL,
    label       varchar(60),
    active      boolean      NOT NULL DEFAULT true,
    version     bigint       NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    -- Bed 1 means something different in each room, so the code is unique per room
    -- rather than globally. Whoever allocates a bed always names the room too.
    CONSTRAINT uq_bed_code_per_room UNIQUE (room_id, code)
);
CREATE INDEX idx_beds_room ON beds (room_id) WHERE active;

-- ---------------------------------------------------------------------------
-- Departments the building has that V1 did not seed.
-- ---------------------------------------------------------------------------

INSERT INTO departments (id, code, name, description) VALUES
    ('22222222-0000-4000-8000-000000000007', 'OBG',  'Obstetrics & Gynaecology',
     'Antenatal, gynaecology and obstetric care'),
    ('22222222-0000-4000-8000-000000000008', 'PHAR', 'Pharmacy',
     'Dispensary and medicines management')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Floors
-- ---------------------------------------------------------------------------

INSERT INTO floors (id, code, name, level) VALUES
    ('33333333-0000-4000-8000-000000000001', 'GF', 'Ground Floor', 0),
    ('33333333-0000-4000-8000-000000000002', 'F1', 'First Floor',  1);

-- ---------------------------------------------------------------------------
-- Rooms
--
-- bookable is true only for the four consultation rooms and the minor procedure
-- room. The casualty bay and the in-patient suites are clinical but are allocated
-- by bed, never booked on a calendar - so a booking cannot land in one by accident.
-- ---------------------------------------------------------------------------

INSERT INTO rooms (id, code, name, room_type_code, floor_id, department_id,
                   capacity, width_ft, length_ft, directions, bookable, notes) VALUES

    -- Front of house
    ('44444444-1000-4000-8000-000000000001', 'GF-LOB', 'Entrance Lobby',
     'CIRCULATION', '33333333-0000-4000-8000-000000000001', NULL,
     0, NULL, NULL, 'Main entrance, ground floor', false, NULL),

    ('44444444-1000-4000-8000-000000000002', 'GF-RCP', 'Reception & Waiting Area',
     'WAITING', '33333333-0000-4000-8000-000000000001', NULL,
     0, 23.25, 15.25, 'Straight ahead from the main entrance', false,
     'Directional signage here points to General, Paediatric and Gynaecology'),

    -- Outpatient consultation rooms. The signage at reception is the source of the
    -- directions text for the three clinics it names.
    ('44444444-1000-4000-8000-000000000003', 'GF-GEN', 'General OPD',
     'CONSULTATION', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000001',
     0, 15.50, 8.13, 'From reception, follow the signs for General', true, NULL),

    ('44444444-1000-4000-8000-000000000004', 'GF-PAED', 'Paediatric OPD',
     'CONSULTATION', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000003',
     0, NULL, NULL, 'From reception, follow the signs for Paediatric', true, NULL),

    ('44444444-1000-4000-8000-000000000005', 'GF-OBG', 'OB/GYN OPD',
     'CONSULTATION', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000007',
     0, 7.50, 15.46, 'From reception, follow the signs for Gynaecology', true, NULL),

    ('44444444-1000-4000-8000-000000000006', 'GF-MAS', 'Master OPD',
     'CONSULTATION', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000001',
     0, 15.50, 14.50, 'Ground floor, past the general consulting rooms', true,
     'Largest consulting room; used for the visiting consultant clinic'),

    ('44444444-1000-4000-8000-000000000007', 'GF-MPR', 'Minor Procedure Room',
     'PROCEDURE', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000001',
     1, NULL, NULL, 'Ground floor, adjacent to the casualty entrance', true, NULL),

    -- Casualty. Two spaces: an open bay with curtained bed positions, and a closed
    -- room for a patient who needs privacy or isolation.
    ('44444444-1000-4000-8000-000000000008', 'GF-CAS', 'Casualty',
     'EMERGENCY_BAY', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000005',
     6, 23.25, 31.25, 'Ambulance entrance, ground floor', false,
     'Open bay, six curtained bed positions'),

    ('44444444-1000-4000-8000-000000000009', 'GF-CASR', 'Casualty Room',
     'EMERGENCY_ROOM', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000005',
     2, 13.50, 16.25, 'Ambulance entrance, ground floor', false,
     'Enclosed two-bed room off the casualty bay'),

    ('44444444-1000-4000-8000-00000000000a', 'GF-RMO', 'RMO Room',
     'STAFF', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000005',
     0, 7.50, 9.00, NULL, false, 'Resident medical officer, on call for casualty'),

    -- Dispensary
    ('44444444-1000-4000-8000-00000000000b', 'GF-PHR', 'Pharmacy',
     'PHARMACY', '33333333-0000-4000-8000-000000000001',
     '22222222-0000-4000-8000-000000000008',
     0, NULL, NULL, 'Ground floor, left of the entrance lobby', false,
     'Dispensing counter opens onto the waiting area'),

    -- Support and circulation
    ('44444444-1000-4000-8000-00000000000c', 'GF-STO', 'Storage',
     'SUPPORT', '33333333-0000-4000-8000-000000000001', NULL,
     0, 15.00, 8.00, NULL, false, NULL),
    ('44444444-1000-4000-8000-00000000000d', 'GF-LIFT', 'Stretcher Lift',
     'CIRCULATION', '33333333-0000-4000-8000-000000000001', NULL,
     0, 4.58, 9.75, NULL, false, 'Serves all floors; casualty to theatre transfers'),
    ('44444444-1000-4000-8000-00000000000e', 'GF-WC1', 'Toilet 1',
     'SUPPORT', '33333333-0000-4000-8000-000000000001', NULL,
     0, NULL, NULL, NULL, false, NULL),
    ('44444444-1000-4000-8000-00000000000f', 'GF-WC2', 'Toilet 2',
     'SUPPORT', '33333333-0000-4000-8000-000000000001', NULL,
     0, NULL, NULL, NULL, false, NULL),
    ('44444444-1000-4000-8000-000000000010', 'GF-AC', 'AC Plant Room',
     'SUPPORT', '33333333-0000-4000-8000-000000000001', NULL,
     0, 5.00, 4.50, NULL, false, NULL),

    -- In-patient suites and family facilities
    ('44444444-1000-4000-8000-000000000011', 'F1-MST', 'Master Suite',
     'SUITE', '33333333-0000-4000-8000-000000000002',
     '22222222-0000-4000-8000-000000000001',
     1, NULL, NULL, 'First floor, take the lift from the lobby', false, NULL),

    ('44444444-1000-4000-8000-000000000012', 'F1-KID', 'Kids Suite',
     'SUITE', '33333333-0000-4000-8000-000000000002',
     '22222222-0000-4000-8000-000000000003',
     2, NULL, NULL, 'First floor, take the lift from the lobby', false,
     'Two beds; paediatric admissions with a parent staying'),

    ('44444444-1000-4000-8000-000000000013', 'F1-FAM', 'Family Living Room',
     'FACILITY', '33333333-0000-4000-8000-000000000002', NULL,
     0, NULL, NULL, 'First floor, beside the suites', false,
     'For families of admitted patients'),

    -- Non-clinical rooms that matter to patients and families. Modelled because a
    -- facility directory that omits them cannot answer the question people actually
    -- ask at reception.
    ('44444444-1000-4000-8000-000000000014', 'F1-POO', 'Prayer Room',
     'FACILITY', '33333333-0000-4000-8000-000000000002', NULL,
     0, NULL, NULL, 'First floor, at the end of the corridor', false, NULL),

    ('44444444-1000-4000-8000-000000000015', 'F1-MED', 'Meditation Room',
     'FACILITY', '33333333-0000-4000-8000-000000000002', NULL,
     0, NULL, NULL, 'First floor, at the end of the corridor', false, NULL);

-- ---------------------------------------------------------------------------
-- Bed positions
--
-- One row per physical bed, so a bed can be allocated, occupied and reported on
-- individually. The casualty bay's six positions are curtained rather than walled,
-- which changes nothing about allocation and everything about how the board reads,
-- hence the labels.
-- ---------------------------------------------------------------------------

INSERT INTO beds (id, room_id, code, label) VALUES
    -- Casualty bay
    ('55555555-1000-4000-8000-000000000001', '44444444-1000-4000-8000-000000000008', 'CAS-1', 'Bay 1'),
    ('55555555-1000-4000-8000-000000000002', '44444444-1000-4000-8000-000000000008', 'CAS-2', 'Bay 2'),
    ('55555555-1000-4000-8000-000000000003', '44444444-1000-4000-8000-000000000008', 'CAS-3', 'Bay 3'),
    ('55555555-1000-4000-8000-000000000004', '44444444-1000-4000-8000-000000000008', 'CAS-4', 'Bay 4'),
    ('55555555-1000-4000-8000-000000000005', '44444444-1000-4000-8000-000000000008', 'CAS-5', 'Bay 5'),
    ('55555555-1000-4000-8000-000000000006', '44444444-1000-4000-8000-000000000008', 'CAS-6', 'Bay 6'),
    -- Enclosed casualty room
    ('55555555-1000-4000-8000-000000000007', '44444444-1000-4000-8000-000000000009', 'CASR-1', 'Room bed A'),
    ('55555555-1000-4000-8000-000000000008', '44444444-1000-4000-8000-000000000009', 'CASR-2', 'Room bed B'),
    -- Minor procedure
    ('55555555-1000-4000-8000-000000000009', '44444444-1000-4000-8000-000000000007', 'MPR-1', 'Procedure couch'),
    -- In-patient suites
    ('55555555-1000-4000-8000-00000000000a', '44444444-1000-4000-8000-000000000011', 'MST-1', 'Master suite bed'),
    ('55555555-1000-4000-8000-00000000000b', '44444444-1000-4000-8000-000000000012', 'KID-1', 'Kids suite bed A'),
    ('55555555-1000-4000-8000-00000000000c', '44444444-1000-4000-8000-000000000012', 'KID-2', 'Kids suite bed B');
