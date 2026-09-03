-- Break-glass for a patient's record, not just one encounter's chart.
--
-- The care-team narrowing answers "is this your patient" for an *encounter*, which is the right
-- unit for a chart and the wrong one for everything else about a patient. A doctor covering a ward
-- overnight who needs the blood results of somebody they have never charted has no encounter to
-- break the glass on -- and a laboratory order does not always have one behind it, because a
-- walk-in test is ordered against a patient and nothing else.
--
-- So this is the second half of the same mechanism: a time-boxed relationship with a *patient*,
-- carrying the same reason requirement and the same expiry, which laboratory-service and
-- pharmacy-service ask about before showing a clinician somebody else's results.
--
-- Deliberately not a replacement for encounter_care_team. Membership of a care team is earned by
-- providing care and is the ordinary path; this is the exception, and keeping them separate is what
-- makes "how many people broke the glass this month" a query rather than an estimate.

CREATE TABLE patient_care_grants (
    id uuid PRIMARY KEY,
    patient_id uuid NOT NULL,
    user_id uuid NOT NULL,

    -- Required, always. There is no platform-created row in this table: every grant here is
    -- somebody deciding they need a record that is not theirs, which is precisely the act that has
    -- to carry an explanation. encounter_care_team allows a null reason for the treating clinician;
    -- this one has no such case, so the column is simply NOT NULL.
    reason text NOT NULL,

    granted_at timestamptz NOT NULL DEFAULT now(),

    -- Never null, unlike the encounter table's. A standing relationship with a patient comes from
    -- looking after them; an exception that never expired would be exactly the standing access this
    -- whole mechanism exists to stop.
    expires_at timestamptz NOT NULL,

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_grant_reason_present CHECK (btrim(reason) <> ''),
    CONSTRAINT chk_grant_expires_after_grant CHECK (expires_at > granted_at)
);

-- The question every read asks: "does this user have a live grant for this patient". Ordered so
-- the newest is first, because a re-granted exception is the row that matters.
CREATE INDEX idx_patient_grant_lookup ON patient_care_grants (patient_id, user_id, expires_at DESC);

-- And the other direction, for the review: whose records has this person opened by exception.
CREATE INDEX idx_patient_grant_user ON patient_care_grants (user_id, granted_at DESC);

-- No unique constraint on (patient_id, user_id), and that is deliberate. Cover last Tuesday and
-- cover again tonight are two decisions with two reasons, and collapsing them into one row would
-- destroy the first reason the moment the second was needed -- which is the record somebody
-- reviewing this would actually want.
