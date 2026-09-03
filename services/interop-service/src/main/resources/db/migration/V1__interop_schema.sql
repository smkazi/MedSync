-- Consent artefacts, and a record of what moved under them.
--
-- The whole module exists for one rule: **health information does not leave this hospital without
-- a valid, unexpired, unrevoked consent that covers what is being sent.** ABDM calls the row a
-- consent artefact; the certification criteria call it authorisation; a patient calls it
-- permission. The check is a service method with no bypass, and the columns below are what it
-- checks against.
--
-- What this is NOT: a consent for treatment, which is a clinical document, and not a cookie
-- banner. It governs *disclosure* -- sending a patient's record to somebody outside this
-- deployment -- and nothing else in the platform is gated on it.

CREATE TABLE consent_artefacts (
    id              uuid PRIMARY KEY,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),

    -- The id the consent manager knows it by. Ours until an ABDM sandbox issues one, and unique
    -- either way: two rows for one artefact is two answers to "may this move", and the wrong one
    -- will be the one somebody reads.
    artefact_id     varchar(64)  NOT NULL,

    patient_id      uuid         NOT NULL,
    patient_mrn     varchar(24)  NOT NULL,

    -- Who is asking, and why. Free text rather than a foreign key: the requester is by definition
    -- outside this deployment, and a table of them would be a directory nobody maintains.
    requester       varchar(160) NOT NULL,
    requester_id    varchar(120),
    purpose_code    varchar(32)  NOT NULL,
    purpose_text    varchar(255),

    status          varchar(20)  NOT NULL DEFAULT 'REQUESTED',

    -- The clinical window the consent covers: records dated outside it are not covered, however
    -- current the consent is. Two different periods, and conflating them is the classic mistake --
    -- "you may see my records from 2024" is not "this permission lasts until 2024".
    covers_from     date         NOT NULL,
    covers_to       date         NOT NULL,

    -- When the permission itself lapses. NOT NULL on purpose: a consent with no expiry is a
    -- standing permission to read somebody's medical record forever, which nobody would sign if it
    -- were written that plainly.
    expires_at      timestamptz  NOT NULL,

    granted_at      timestamptz,
    denied_at       timestamptz,
    revoked_at      timestamptz,
    revoked_reason  varchar(255),

    -- Whatever the consent manager signed, kept verbatim and never parsed here. A signature this
    -- service cannot verify is still evidence of what was presented.
    signature       text,

    CONSTRAINT uq_artefact UNIQUE (artefact_id),
    CONSTRAINT chk_consent_status
        CHECK (status IN ('REQUESTED', 'GRANTED', 'DENIED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_period CHECK (covers_to >= covers_from),
    -- A grant with no timestamp, or a revocation with no reason, is a record that cannot answer
    -- the question it exists to answer.
    CONSTRAINT chk_granted_has_a_time CHECK (status <> 'GRANTED' OR granted_at IS NOT NULL),
    CONSTRAINT chk_revoked_has_a_reason
        CHECK (status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL))
);

CREATE INDEX idx_consent_patient ON consent_artefacts (patient_id, created_at DESC);
CREATE INDEX idx_consent_live ON consent_artefacts (status, expires_at) WHERE status = 'GRANTED';

-- What each consent covers, one row per information type.
--
-- A child table rather than an array column, because every question asked of it is "does this
-- consent cover a prescription" -- a membership test the database can index and a CHECK can
-- constrain. The vocabulary is ABDM's own, and a value outside it would be a consent covering
-- something no bundle builder can produce.
CREATE TABLE consent_hi_types (
    consent_id  uuid        NOT NULL REFERENCES consent_artefacts (id) ON DELETE CASCADE,
    hi_type     varchar(32) NOT NULL,
    PRIMARY KEY (consent_id, hi_type),
    CONSTRAINT chk_hi_type CHECK (hi_type IN (
        'OP_CONSULTATION', 'DIAGNOSTIC_REPORT', 'PRESCRIPTION', 'DISCHARGE_SUMMARY',
        'IMMUNIZATION_RECORD', 'HEALTH_DOCUMENT_RECORD', 'WELLNESS_RECORD'))
);

-- Every disclosure, and what authorised it.
--
-- This is the accounting of disclosures the certification criteria ask for, written at the moment
-- it happens rather than reconstructed from logs afterwards. It records what was sent, to whom,
-- under which consent, and how much of it -- never the content, because a log that carried the
-- bundle would be a second copy of the record with none of its protections.
CREATE TABLE disclosures (
    id           uuid         PRIMARY KEY,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),

    -- Null for an export handed to the patient themselves: that is not a disclosure to a third
    -- party and there is no consent artefact behind it, which is a distinction this column makes
    -- visible rather than hiding behind a placeholder row.
    consent_id   uuid         REFERENCES consent_artefacts (id),
    patient_id   uuid         NOT NULL,
    patient_mrn  varchar(24)  NOT NULL,
    hi_type      varchar(32)  NOT NULL,
    kind         varchar(20)  NOT NULL,
    recipient    varchar(160) NOT NULL,
    resource_count int        NOT NULL,
    byte_count   int          NOT NULL,
    released_by  varchar(120) NOT NULL,
    released_at  timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT chk_disclosure_kind CHECK (kind IN ('CONSENTED_SHARE', 'PATIENT_EXPORT', 'CARE_SUMMARY')),
    -- A consented share without a consent is the failure this whole module exists to prevent, so
    -- it is unrepresentable rather than merely refused in code.
    CONSTRAINT chk_share_names_a_consent
        CHECK (kind <> 'CONSENTED_SHARE' OR consent_id IS NOT NULL),
    CONSTRAINT chk_counts_not_negative CHECK (resource_count >= 0 AND byte_count >= 0)
);

CREATE INDEX idx_disclosure_patient ON disclosures (patient_id, released_at DESC);
CREATE INDEX idx_disclosure_consent ON disclosures (consent_id) WHERE consent_id IS NOT NULL;
