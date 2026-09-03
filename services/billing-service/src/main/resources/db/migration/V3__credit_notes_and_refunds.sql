-- Credit notes and refunds: correcting a bill, and giving money back.
--
-- These are two documents and not one, because they answer different questions and a platform that
-- conflates them produces a record nobody can reconcile. A credit note changes what is *owed*: the
-- bill was wrong, or the treatment was not given, and the receivable comes down. A refund moves
-- *cash*: money the hospital holds goes back to the person who paid it. A bill can be credited with
-- no money ever having moved (nothing was paid yet), and money can only go back once somebody has
-- said in writing that it is not owed.
--
-- The invoice's own total is never touched. That is the same rule the platform already applies to
-- prices — snapshotted onto invoice_lines rather than joined, so a financial record cannot change
-- after the fact — and it is what keeps chk_not_overpaid (amount_paid <= total) true. Reducing
-- `total` on a paid invoice would violate that constraint, and relaxing the constraint to allow it
-- would delete the platform's only guarantee that it never took more money than it billed. So a
-- credit is its own number in its own column, and every figure a person asks for is arithmetic over
-- four columns rather than a mutation of one:
--
--   still owed  = (total - credited) - (amount_paid - refunded)
--   owed back   = (amount_paid - refunded) - (total - credited)
--
-- Both cannot be positive at once, which is the property that makes the pair meaningful.

ALTER TABLE invoices
    ADD COLUMN credited numeric(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN refunded numeric(14,2) NOT NULL DEFAULT 0;

ALTER TABLE invoices
    -- A bill cannot be forgiven for more than it charged.
    ADD CONSTRAINT chk_credited_within_total CHECK (credited >= 0 AND credited <= total),
    -- Money cannot be given back that was never received. This is the mirror of chk_not_overpaid,
    -- and it is the one that stops a refund becoming a way to withdraw cash from the platform.
    ADD CONSTRAINT chk_refunded_within_received CHECK (refunded >= 0 AND refunded <= amount_paid),
    -- And the control that matters: a refund needs a credit note behind it. Handing money back on
    -- a bill still recorded as owed would leave the patient owing it again the next time anybody
    -- reads the invoice — the same irreconcilable state cancelling a paid invoice would have
    -- produced, arrived at from the other direction.
    --
    -- It is also half of a separation of duties, and only half: a credit note is an
    -- administrator's act and a refund a cashier's, so a cashier — the role that handles cash every
    -- day — cannot decide that a charge is not owed and then pay it back. An administrator can do
    -- both, because ADMIN holds every billing role, and that is stated in the README's gaps rather
    -- than dressed up as a control. What this line guarantees regardless of who acts is that money
    -- never leaves without a written credit note behind it, which is why it lives in the database
    -- and not only in the service that writes it.
    ADD CONSTRAINT chk_refund_needs_credit CHECK (refunded <= credited);

CREATE TABLE credit_notes (
    id uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES invoices (id),
    -- Its own number in its own series, not a suffix on the invoice's. A credit note is a tax
    -- document in its own right and is expected to carry a sequential number of its own; sharing
    -- the invoice sequence would put gaps in the invoice numbering, which is exactly what an
    -- auditor reads that sequence to detect.
    number varchar(24) NOT NULL UNIQUE,
    amount numeric(14,2) NOT NULL,
    -- Required, and not by convention: a credit note with no reason is a discount somebody gave
    -- without saying why, and the whole point of the document is that it says why.
    reason varchar(255) NOT NULL,
    issued_by varchar(64) NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_credit_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_credit_reason_present CHECK (btrim(reason) <> '')
);

-- The register: what has been credited against one invoice, newest first. Also the query behind
-- "how much of this bill has been forgiven", which is read on every refund.
CREATE INDEX idx_credit_note_invoice ON credit_notes (invoice_id, issued_at DESC);

CREATE TABLE refunds (
    id uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES invoices (id),
    -- Which credit note authorised it. Nullable only because a refund may draw on several, and the
    -- constraint that actually enforces the authorisation is chk_refund_needs_credit on the
    -- invoice: the sum, not the individual link. Recorded where it is a single note, because "who
    -- authorised this payout" is the first question asked about one.
    credit_note_id uuid REFERENCES credit_notes (id),
    amount numeric(14,2) NOT NULL,
    -- How the money went back, from the same vocabulary a payment uses — deliberately the same
    -- enum rather than a second list, because a refund method that cannot be reconciled against a
    -- payment method is a cash-up that cannot balance. It need not match how the money came in:
    -- cash taken at the desk is often returned by transfer, and a card refund goes to the card
    -- whatever the patient asks for.
    method varchar(20) NOT NULL,
    reference varchar(64),
    paid_by varchar(64) NOT NULL,
    paid_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_refund_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_refund_method CHECK (method IN ('CASH', 'CARD', 'UPI', 'BANK_TRANSFER', 'INSURANCE'))
);

CREATE INDEX idx_refund_invoice ON refunds (invoice_id, paid_at DESC);
-- The day's payouts, for the same reason payments are indexed by date: a cash-up asks what left
-- the drawer as well as what entered it.
CREATE INDEX idx_refund_paid_at ON refunds (paid_at DESC);
