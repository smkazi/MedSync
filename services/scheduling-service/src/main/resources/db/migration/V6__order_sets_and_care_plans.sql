-- Order sets, and care plans.
--
-- Two things a clinician does with a chart that the platform could not do before: raise the
-- half-dozen orders a presentation always needs in one act, and write down what this admission is
-- trying to achieve.
--
-- **An order set is rows, not code.** Adding "fever, first line" needs no new behaviour: it is a
-- name and a list of things to raise. What it must never be is a list somebody can half-fill --
-- hence the CHECK below, which is the load-bearing part of this migration.

CREATE TABLE order_sets (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    description varchar(500),
    -- Which department's set it is. Null means general, and that is a real state rather than
    -- missing data: "fever, first line" belongs to everybody.
    department_code varchar(32),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE order_set_items (
    id uuid PRIMARY KEY,
    order_set_id uuid NOT NULL REFERENCES order_sets (id) ON DELETE CASCADE,
    -- LAB or MEDICATION. In code as an enum too, because each value maps to a different service and
    -- a different request shape: a third value with nothing behind it would be a row that silently
    -- raises nothing.
    kind varchar(16) NOT NULL,
    -- A laboratory test code or a formulary drug code. Not a foreign key: both live in other
    -- services' schemas, and this one must not fail because the laboratory is mid-migration. The
    -- codes are checked when the set is applied, which is the moment it matters.
    code varchar(32) NOT NULL,
    -- Medication fields. Null for a laboratory item.
    dose varchar(48),
    frequency varchar(48),
    duration_days integer,
    quantity integer,
    instructions varchar(500),
    -- Laboratory field. Null for a medication.
    priority varchar(16),
    display_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_item_kind CHECK (kind IN ('LAB', 'MEDICATION')),
    -- The constraint that makes an order set safe to apply. A medication line with no dose, no
    -- frequency, no duration or no quantity is not a template a clinician can accept in one click;
    -- it is a prompt to guess, in the one place where a guess is applied to a patient without
    -- anybody typing it. Refused at the point the set is written, so it can never be applied.
    CONSTRAINT chk_medication_is_complete CHECK (
        kind <> 'MEDICATION' OR (dose IS NOT NULL AND frequency IS NOT NULL
            AND duration_days IS NOT NULL AND duration_days > 0
            AND quantity IS NOT NULL AND quantity > 0)),
    -- And the other way: a laboratory line carrying a dose is a medication somebody mis-typed as a
    -- test, which would be raised as neither.
    CONSTRAINT chk_lab_carries_no_dose CHECK (
        kind <> 'LAB' OR (dose IS NULL AND frequency IS NULL AND duration_days IS NULL
            AND quantity IS NULL)),
    CONSTRAINT uq_set_item UNIQUE (order_set_id, kind, code)
);

CREATE INDEX idx_order_set_items ON order_set_items (order_set_id, display_order);

-- ---------------------------------------------------------------------------
-- Care plans: what this episode is trying to achieve, and whether it did.
-- ---------------------------------------------------------------------------
CREATE TABLE care_plans (
    id uuid PRIMARY KEY,
    encounter_id uuid NOT NULL REFERENCES encounters (id),
    patient_id uuid NOT NULL,
    patient_mrn varchar(24) NOT NULL,
    title varchar(160) NOT NULL,
    status varchar(20) NOT NULL,
    created_by varchar(64) NOT NULL,
    closed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_plan_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    -- One plan per encounter. A second is two lists of goals for one visit, and the question
    -- "what are we trying to achieve" then has two answers that can disagree.
    CONSTRAINT uq_plan_per_encounter UNIQUE (encounter_id)
);

CREATE INDEX idx_care_plan_patient ON care_plans (patient_id, created_at DESC);

CREATE TABLE care_plan_goals (
    id uuid PRIMARY KEY,
    care_plan_id uuid NOT NULL REFERENCES care_plans (id) ON DELETE CASCADE,
    description varchar(500) NOT NULL,
    -- The problem this goal is for, as an ICD-10 code from the encounter's own diagnoses. Nullable
    -- because a goal can be general -- "mobilising independently" belongs to the admission rather
    -- than to one diagnosis -- and checked against the encounter when it is set, so a plan cannot
    -- name a problem the patient has not been given.
    problem_code varchar(16),
    -- Dated, because a goal with no date is a wish. Nullable all the same: "before discharge" is a
    -- real target that no calendar date expresses, and forcing one would produce dates nobody means.
    target_date date,
    status varchar(20) NOT NULL,
    progress_note varchar(1000),
    updated_by varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_goal_status CHECK (status IN ('OPEN', 'MET', 'NOT_MET', 'ABANDONED')),
    -- A goal that was not met, or was given up on, needs a sentence saying why. "NOT_MET" alone is
    -- the shape of a record that cannot be reviewed.
    CONSTRAINT chk_outcome_has_a_note CHECK (
        status IN ('OPEN', 'MET') OR progress_note IS NOT NULL)
);

CREATE INDEX idx_goals_plan ON care_plan_goals (care_plan_id);

-- ---------------------------------------------------------------------------
-- Three sets to start with, drawn from what this deployment's laboratory and formulary actually
-- hold. A deployment's clinicians are expected to replace them: that is the point of rows.
INSERT INTO order_sets (id, code, name, description, department_code) VALUES
    ('88888888-0000-4000-8000-000000000001', 'ANAEMIA', 'Anaemia screen',
     'First-line bloods for a patient who looks anaemic. No medicine: treatment waits for the numbers.',
     'GEN'),
    ('88888888-0000-4000-8000-000000000002', 'FEVER1', 'Fever, first line',
     'Bloods and regular paracetamol for an undifferentiated fever in an adult.', 'GEN'),
    ('88888888-0000-4000-8000-000000000003', 'ANALGESIA1', 'Simple analgesia',
     'Paracetamol regularly, ibuprofen as required. No bloods.', 'GEN')
ON CONFLICT (code) DO NOTHING;

INSERT INTO order_set_items
    (id, order_set_id, kind, code, dose, frequency, duration_days, quantity, instructions, priority, display_order)
VALUES
    ('99999999-0000-4000-8000-000000000001', '88888888-0000-4000-8000-000000000001', 'LAB', 'CBC5',
     NULL, NULL, NULL, NULL, NULL, 'ROUTINE', 1),
    ('99999999-0000-4000-8000-000000000002', '88888888-0000-4000-8000-000000000001', 'LAB', 'ESR',
     NULL, NULL, NULL, NULL, NULL, 'ROUTINE', 2),

    ('99999999-0000-4000-8000-000000000003', '88888888-0000-4000-8000-000000000002', 'LAB', 'CBC5',
     NULL, NULL, NULL, NULL, NULL, 'URGENT', 1),
    ('99999999-0000-4000-8000-000000000004', '88888888-0000-4000-8000-000000000002', 'LAB', 'ESR',
     NULL, NULL, NULL, NULL, NULL, 'URGENT', 2),
    ('99999999-0000-4000-8000-000000000005', '88888888-0000-4000-8000-000000000002', 'MEDICATION', 'PARA500',
     '1 tablet', 'four times daily', 3, 12, 'Maximum four doses in 24 hours.', NULL, 3),

    ('99999999-0000-4000-8000-000000000006', '88888888-0000-4000-8000-000000000003', 'MEDICATION', 'PARA500',
     '1 tablet', 'four times daily', 5, 20, 'Regularly, not only when it hurts.', NULL, 1),
    ('99999999-0000-4000-8000-000000000007', '88888888-0000-4000-8000-000000000003', 'MEDICATION', 'IBU400',
     '1 tablet', 'three times daily', 5, 15, 'After food. Stop if indigestion or dark stools.', NULL, 2)
ON CONFLICT (order_set_id, kind, code) DO NOTHING;
