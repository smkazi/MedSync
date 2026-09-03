-- The cash-up: a shift, a counted drawer, and a variance somebody signed for.
--
-- The day book totals what was billed and collected and splits collections by method. Nothing
-- closed it. A hospital's till is reconciled per shift and per person -- not per calendar day --
-- because a drawer is handed over, and "the 14th took eight thousand" cannot tell you which of the
-- three people who sat at that counter is short two hundred.
--
-- What a session is: one cashier, one drawer, from opening float to counted close. Only cash is
-- counted against it. Card and UPI settle into the acquirer's batch and cannot be short by an
-- error of counting, so declaring them would invite somebody to type the expected figure back in
-- and call it reconciled; they are reported for the cashier to tick against the terminal's own
-- batch, and the variance is on cash alone, where a variance can physically exist.

CREATE TABLE cash_sessions (
    id uuid PRIMARY KEY,
    -- The person, not the workstation. A cash drawer is somebody's responsibility for a shift,
    -- and the username is who signs for it at the end.
    cashier varchar(64) NOT NULL,
    opened_at timestamptz NOT NULL DEFAULT now(),
    -- What was in the drawer at the start, counted and declared by the person opening it. Zero is
    -- a legitimate float and is not the same as not having counted, which is why there is no
    -- default: opening a session states a number.
    opening_float numeric(14,2) NOT NULL,
    closed_at timestamptz,
    -- Whoever closed it, which is usually but not always the cashier: an administrator closes a
    -- shift somebody walked away from, and the row has to say that it was not the cashier who
    -- counted. Two names because they answer two questions.
    closed_by varchar(64),
    -- Counted out of the drawer at the end. Null while open.
    declared_cash numeric(14,2),
    -- What the platform says should have been there: float, plus cash taken, less cash paid back.
    -- Frozen onto the row at close rather than recomputed on read -- a later correction to an
    -- invoice must not silently move a figure a person has signed against.
    expected_cash numeric(14,2),
    -- declared - expected. Stored rather than derived for the same reason, and because "was this
    -- shift over or short" is the query the whole table exists to answer.
    variance numeric(14,2),
    -- Required when the count disagrees; see chk_variance_explained.
    notes varchar(1000),
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_cash_session_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_opening_float_not_negative CHECK (opening_float >= 0),
    CONSTRAINT chk_declared_not_negative CHECK (declared_cash IS NULL OR declared_cash >= 0),
    -- A closed session carries its whole account, and an open one carries none of it. Without
    -- this a half-closed row -- closed_at set, nothing counted -- would read as a signed-off
    -- shift in every report that filters on status.
    CONSTRAINT chk_closed_is_complete CHECK (
        (status = 'OPEN'
             AND closed_at IS NULL AND closed_by IS NULL
             AND declared_cash IS NULL AND expected_cash IS NULL AND variance IS NULL)
        OR (status = 'CLOSED'
             AND closed_at IS NOT NULL AND closed_by IS NOT NULL
             AND declared_cash IS NOT NULL AND expected_cash IS NOT NULL AND variance IS NOT NULL)
    ),
    -- A discrepancy has to be explained in writing. The entire value of a cash-up is that somebody
    -- accounts for the difference while they still remember the shift; a variance with no note is
    -- a number that will be investigated by nobody, and the count may as well not have happened.
    CONSTRAINT chk_variance_explained CHECK (
        variance IS NULL OR variance = 0 OR btrim(coalesce(notes, '')) <> ''
    )
);

-- One open drawer per cashier, enforced here rather than by a check-then-insert that two browser
-- tabs would both pass. Partial, so a cashier may have any number of closed sessions and exactly
-- one open.
CREATE UNIQUE INDEX uq_one_open_session_per_cashier
    ON cash_sessions (cashier) WHERE status = 'OPEN';

CREATE INDEX idx_cash_session_opened ON cash_sessions (opened_at DESC);

-- Which drawer a payment went into, and which one paid a refund back out.
--
-- Stamped when the money moves rather than inferred afterwards from timestamps. A window query --
-- "payments by this cashier between these two instants" -- looks equivalent and is not: it
-- silently reassigns money taken in the minute between one shift closing and the next opening,
-- and it cannot survive a session being closed retrospectively at all. Nullable because a payment
-- must never be refused for want of an open shift; the money is real whether or not somebody
-- remembered to open a drawer, and the unattributed total is reported rather than hidden.
ALTER TABLE payments ADD COLUMN cash_session_id uuid REFERENCES cash_sessions (id);
ALTER TABLE refunds  ADD COLUMN cash_session_id uuid REFERENCES cash_sessions (id);

CREATE INDEX idx_payment_session ON payments (cash_session_id) WHERE cash_session_id IS NOT NULL;
CREATE INDEX idx_refund_session  ON refunds  (cash_session_id) WHERE cash_session_id IS NOT NULL;
