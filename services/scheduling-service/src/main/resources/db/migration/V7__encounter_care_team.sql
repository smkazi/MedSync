-- Who is looking after this encounter, and how they came to be.
--
-- Until now Roles.CHART_READ was the whole of chart access: hasAnyRole('ADMIN','DOCTOR','NURSE',
-- 'PATHOLOGIST'). Every doctor and every nurse could read every chart on the platform. That is a
-- role gate, and a role gate cannot answer "is this your patient" -- so this table answers it.
--
-- Membership, not a derived rule, and the reason is nurses. encounters.clinician_id is the doctor;
-- a nurse recording vitals appears in it nowhere. Narrowing to "you are the encounter's clinician"
-- would have locked every nurse out of every chart, which is a clinical catastrophe dressed as a
-- control. So the encounter's own clinician is enrolled automatically when the encounter opens --
-- the treating doctor's day is unchanged -- and everybody else joins, which is an act with a name,
-- a reason and an audit record.
CREATE TABLE encounter_care_team (
    id           uuid         PRIMARY KEY,
    encounter_id uuid         NOT NULL REFERENCES encounters (id) ON DELETE CASCADE,
    user_id      uuid         NOT NULL,
    member_role  varchar(32)  NOT NULL,

    -- Null for the encounter's own clinician, who is enrolled by the platform rather than by
    -- anybody's decision. Set for everybody else, and that asymmetry is the whole design: an
    -- assignment needs no justification, and cover for somebody else's patient does.
    reason       text,
    joined_by    uuid,
    joined_at    timestamptz  NOT NULL DEFAULT now(),

    -- When cover lapses. Null means it does not: the treating clinician stays on their own
    -- encounter. A break-glass membership expires after a shift, because covering one patient for
    -- one evening should not become standing access to their record for ever.
    expires_at   timestamptz,

    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_care_team UNIQUE (encounter_id, user_id),

    -- A reason is required of everybody who was not put here by the platform. Enforced in the
    -- database as well as in the request validation, because the guard reads this table and a row
    -- with no explanation is exactly what nobody would notice.
    CONSTRAINT chk_care_team_reason CHECK (joined_by IS NULL OR reason IS NOT NULL)
);

-- The guard's query: "is this user on this encounter's team, and not expired". Leading with
-- encounter_id because that is what every read starts from.
CREATE INDEX idx_care_team_lookup ON encounter_care_team (encounter_id, user_id);

-- And the other direction, for "whose charts am I on" and for the audit trail.
CREATE INDEX idx_care_team_user ON encounter_care_team (user_id, joined_at DESC);

-- Every encounter that already exists gets its own clinician enrolled, so the narrowing does not
-- retrospectively lock the treating doctor out of the chart they wrote. Nurses who have worked on
-- an old encounter are not in the data anywhere -- vitals record a username, not a user id -- so
-- they join through break-glass like anybody else. Said out loud rather than discovered: on a
-- database with history, the first nurse to open an old chart records a reason.
INSERT INTO encounter_care_team (id, encounter_id, user_id, member_role, joined_at)
SELECT gen_random_uuid(), e.id, e.clinician_id, 'TREATING_CLINICIAN', e.created_at
  FROM encounters e
 WHERE e.clinician_id IS NOT NULL;
