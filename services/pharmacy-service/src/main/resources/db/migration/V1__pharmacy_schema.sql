-- The closed medication loop: prescribe, check, dispense, administer.
--
-- Four safety rules shape this schema, and each one is a constraint rather than a convention:
--
--   1. One row per interacting pair, not two. `chk_pair_ordered` forces (a < b), so a pairing
--      cannot be recorded twice with two different severities that disagree.
--   2. One dose, one record. `uq_dose` on (prescription_item_id, scheduled_for) is what stops a
--      patient being given the same dose twice by two nurses who both believed the other had not.
--   3. Stock cannot go negative. A CHECK on the batch, and the decrement is one statement.
--   4. An expired batch is refused. `expires_on` is a date and the rule lives in the service, but
--      the column is NOT NULL so "we do not know when this expires" is not representable.
--
-- Ingredients, not brand names, are what the checks run on. Two brands of the same molecule are
-- two formulary rows and one ingredient, and a patient allergic to penicillin is allergic to it
-- under every trade name it has ever been sold under.

-- ---------------------------------------------------------------------------
-- The formulary: what this hospital stocks and may prescribe.
-- ---------------------------------------------------------------------------
CREATE TABLE formulary (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    form varchar(32) NOT NULL,
    strength varchar(48) NOT NULL,
    unit varchar(24) NOT NULL,
    -- Whether this needs a controlled-drug register. Recorded rather than enforced: the register
    -- itself is not built, and a flag that claims a control the platform does not implement would
    -- be worse than an honest column. Named in the README's gaps.
    controlled boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_formulary_active ON formulary (active, name);

-- What a formulary entry actually contains.
--
-- A separate table rather than a column because a combination product has more than one, and
-- because every safety check in this service runs on ingredients: a brand name is what somebody
-- types and an ingredient is what interacts with another drug or with an allergy.
CREATE TABLE formulary_ingredients (
    drug_code varchar(32) NOT NULL REFERENCES formulary (code) ON DELETE CASCADE,
    ingredient_code varchar(64) NOT NULL,
    PRIMARY KEY (drug_code, ingredient_code)
);

CREATE INDEX idx_ingredient_lookup ON formulary_ingredients (ingredient_code);

-- ---------------------------------------------------------------------------
-- Drug-drug interactions, one row per unordered pair.
-- ---------------------------------------------------------------------------
CREATE TABLE interaction_pairs (
    id uuid PRIMARY KEY,
    ingredient_a varchar(64) NOT NULL,
    ingredient_b varchar(64) NOT NULL,
    severity varchar(20) NOT NULL,
    effect varchar(255) NOT NULL,
    management varchar(255) NOT NULL,
    source varchar(120),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- The pair is unordered, so it is stored ordered. Without this a deployment can hold
    -- (warfarin, aspirin) MAJOR and (aspirin, warfarin) MINOR, and which one fires depends on the
    -- order the caller happened to pass its ingredients in.
    CONSTRAINT chk_pair_ordered CHECK (ingredient_a < ingredient_b),
    CONSTRAINT uq_interaction_pair UNIQUE (ingredient_a, ingredient_b),
    CONSTRAINT chk_interaction_severity
        CHECK (severity IN ('MINOR', 'MODERATE', 'MAJOR', 'CONTRAINDICATED'))
);

CREATE INDEX idx_interaction_a ON interaction_pairs (ingredient_a);
CREATE INDEX idx_interaction_b ON interaction_pairs (ingredient_b);

-- ---------------------------------------------------------------------------
-- Prescriptions.
-- ---------------------------------------------------------------------------
CREATE TABLE prescriptions (
    id uuid PRIMARY KEY,
    -- Another service's ids, unconstrained on purpose, exactly as elsewhere on this platform: an
    -- encounter belongs to scheduling and a patient to patient-service, and this service must not
    -- fail because one of them is mid-migration.
    encounter_id uuid,
    patient_id uuid NOT NULL,
    patient_mrn varchar(24) NOT NULL,
    prescriber_id uuid NOT NULL,
    prescriber_name varchar(160) NOT NULL,
    status varchar(20) NOT NULL,
    -- Why an interaction or an allergy warning was accepted anyway. Null when nothing was
    -- overridden, which is the ordinary case; a sentence when something was, because "the
    -- prescriber knew and had a reason" is the only thing that distinguishes a considered decision
    -- from a mistake, and it has to be readable months later.
    override_reason varchar(500),
    issued_at timestamptz NOT NULL DEFAULT now(),
    cancelled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_prescription_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_prescription_patient ON prescriptions (patient_id, issued_at DESC);
CREATE INDEX idx_prescription_encounter ON prescriptions (encounter_id) WHERE encounter_id IS NOT NULL;
CREATE INDEX idx_prescription_open ON prescriptions (status, issued_at) WHERE status = 'ACTIVE';

CREATE TABLE prescription_items (
    id uuid PRIMARY KEY,
    prescription_id uuid NOT NULL REFERENCES prescriptions (id) ON DELETE CASCADE,
    drug_code varchar(32) NOT NULL REFERENCES formulary (code),
    -- The drug's name as it was when this was written. Snapshotted rather than joined, the same
    -- decision an invoice line makes: renaming a formulary entry must not rewrite what a
    -- prescription from last year said.
    drug_name varchar(160) NOT NULL,
    dose varchar(48) NOT NULL,
    frequency varchar(48) NOT NULL,
    duration_days integer NOT NULL,
    quantity integer NOT NULL,
    instructions varchar(500),
    quantity_dispensed integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_item_duration CHECK (duration_days > 0),
    -- Dispensing more than was prescribed is not a rounding error, it is a different quantity of a
    -- medicine leaving the pharmacy than a prescriber authorised.
    CONSTRAINT chk_not_over_dispensed CHECK (quantity_dispensed BETWEEN 0 AND quantity)
);

CREATE INDEX idx_item_prescription ON prescription_items (prescription_id);

-- ---------------------------------------------------------------------------
-- Stock, by batch, because expiry is a property of a batch and not of a drug.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_batches (
    id uuid PRIMARY KEY,
    drug_code varchar(32) NOT NULL REFERENCES formulary (code),
    batch_no varchar(48) NOT NULL,
    expires_on date NOT NULL,
    quantity_on_hand integer NOT NULL,
    received_on date NOT NULL DEFAULT current_date,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_batch UNIQUE (drug_code, batch_no),
    CONSTRAINT chk_stock_not_negative CHECK (quantity_on_hand >= 0)
);

-- First expiry, first out. The index is ordered the way the picker reads it.
CREATE INDEX idx_stock_fefo ON stock_batches (drug_code, expires_on)
    WHERE quantity_on_hand > 0;

CREATE TABLE dispenses (
    id uuid PRIMARY KEY,
    prescription_item_id uuid NOT NULL REFERENCES prescription_items (id),
    batch_id uuid NOT NULL REFERENCES stock_batches (id),
    quantity integer NOT NULL,
    dispensed_by varchar(120) NOT NULL,
    dispensed_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_dispense_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_dispense_item ON dispenses (prescription_item_id);

-- ---------------------------------------------------------------------------
-- Administration: the bedside end of the loop.
-- ---------------------------------------------------------------------------
CREATE TABLE administrations (
    id uuid PRIMARY KEY,
    prescription_item_id uuid NOT NULL REFERENCES prescription_items (id),
    scheduled_for timestamptz NOT NULL,
    administered_at timestamptz,
    administered_by varchar(120) NOT NULL,
    -- What was scanned, kept verbatim. The service checks them against the prescription before
    -- writing the row; they are stored because "which barcode did the nurse actually scan" is the
    -- question asked when a dose turns out to have been wrong, and a boolean cannot answer it.
    patient_scan varchar(64),
    drug_scan varchar(64),
    status varchar(20) NOT NULL,
    refusal_reason varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- One dose, one record. Two nurses at one bedside, each believing the other had not given it,
    -- is the failure this index exists for, and it is a unique constraint rather than a check in
    -- application code because both of them pass a check and only one can win an insert.
    CONSTRAINT uq_dose UNIQUE (prescription_item_id, scheduled_for),
    CONSTRAINT chk_administration_status
        CHECK (status IN ('GIVEN', 'REFUSED', 'OMITTED')),
    -- A dose not given needs a reason. "Refused" or "omitted" with no explanation is a gap in the
    -- record where the reason should be, and the next shift cannot tell a patient who declined
    -- from a medicine that was never available.
    CONSTRAINT chk_reason_when_not_given
        CHECK (status = 'GIVEN' OR refusal_reason IS NOT NULL)
);

CREATE INDEX idx_administration_item ON administrations (prescription_item_id, scheduled_for);
