-- Charge capture, GST invoicing, payments and payer claims.
--
-- Four things go wrong in billing systems, and each one gets a database answer here rather than
-- application care. Application care is how a hospital ends up billing a patient twice for one
-- consultation and finding out from the patient.
--
--   1. **Prices are snapshotted onto the line, never joined.** The deliberate opposite of the room
--      decision elsewhere in this platform: a room's directions must always be current, and a
--      financial record must never change after the fact. Re-pricing last year's invoice because
--      somebody edited a charge item is not a bug report anybody can act on.
--   2. **An invoice cannot be overpaid**, by CHECK, and the payment itself is one conditional
--      UPDATE. Two cashiers taking the same balance both read the same `amount_paid`.
--   3. **A charge cannot post twice.** `posted_charges` has a primary key over the source, and it
--      is the most important constraint in this file: events get redelivered, and without it a
--      redelivered "report released" bills the patient again.
--   4. **`numeric(14,2)` everywhere money appears**, with `BigDecimal` and an explicit
--      RoundingMode at every boundary in the code. No floating point anywhere near an amount.

-- ---------------------------------------------------------------------------
-- Tax, as rows with effective dates.
-- ---------------------------------------------------------------------------
--
-- Never a hard-coded 18%. Rates change by statute, and an invoice raised last year must keep the
-- rate that applied then — which is why the rate is resolved by the invoice's own date and then
-- copied onto the line.
CREATE TABLE tax_rates (
    id uuid PRIMARY KEY,
    code varchar(24) NOT NULL,
    name varchar(120) NOT NULL,
    percent numeric(5,2) NOT NULL,
    effective_from date NOT NULL,
    -- Null means "still in force". A closed period is a rate that was superseded.
    effective_to date,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_percent CHECK (percent >= 0 AND percent <= 100),
    CONSTRAINT chk_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    -- One row per code per start date. A second row starting the same day is two answers to
    -- "what is the rate today", and which one wins would depend on the query plan.
    CONSTRAINT uq_rate_period UNIQUE (code, effective_from)
);

CREATE INDEX idx_tax_rate_lookup ON tax_rates (code, effective_from DESC);

-- ---------------------------------------------------------------------------
-- What the hospital charges for.
-- ---------------------------------------------------------------------------
CREATE TABLE charge_items (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    department_code varchar(32),
    unit_price numeric(14,2) NOT NULL,
    -- Exempt by default, and that is the correct default rather than a lazy one: healthcare
    -- services provided by a clinical establishment are GST-exempt in India, so most of a
    -- hospital's charge list carries no tax. What is taxable — a pharmacy sale, a consumable, a
    -- non-clinical service — says so and names its rate.
    taxable boolean NOT NULL DEFAULT false,
    tax_rate_code varchar(24),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_price_not_negative CHECK (unit_price >= 0),
    -- A taxable item with no rate would silently be taxed at nothing, which is the same as being
    -- exempt but without anybody having decided it.
    CONSTRAINT chk_taxable_names_a_rate CHECK (NOT taxable OR tax_rate_code IS NOT NULL)
);

CREATE INDEX idx_charge_items_active ON charge_items (active, name);

-- ---------------------------------------------------------------------------
-- Who pays.
-- ---------------------------------------------------------------------------
CREATE TABLE payers (
    id uuid PRIMARY KEY,
    code varchar(32) NOT NULL UNIQUE,
    name varchar(160) NOT NULL,
    requires_preauth boolean NOT NULL DEFAULT false,
    allows_copay boolean NOT NULL DEFAULT true,
    settles_directly boolean NOT NULL DEFAULT false,
    -- Some payers are exempt whatever the item says: a government scheme, a charity fund.
    tax_exempt boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- A payer that demands pre-authorisation and then does not settle directly is a contradiction:
    -- pre-authorisation exists so that the hospital can bill the payer rather than the patient.
    -- Recorded as a CHECK because it is the kind of configuration mistake that only shows up as an
    -- unpaid invoice three months later.
    CONSTRAINT chk_preauth_implies_direct CHECK (NOT requires_preauth OR settles_directly)
);

-- What a payer has agreed to pay for each item. Absent means the list price applies.
CREATE TABLE payer_tariffs (
    payer_code varchar(32) NOT NULL REFERENCES payers (code) ON DELETE CASCADE,
    charge_item_code varchar(32) NOT NULL REFERENCES charge_items (code) ON DELETE CASCADE,
    price numeric(14,2) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payer_code, charge_item_code),
    CONSTRAINT chk_tariff_not_negative CHECK (price >= 0)
);

-- ---------------------------------------------------------------------------
-- Invoices.
-- ---------------------------------------------------------------------------
CREATE TABLE invoices (
    id uuid PRIMARY KEY,
    -- Another service's ids, unconstrained on purpose, as everywhere on this platform.
    patient_id uuid NOT NULL,
    patient_mrn varchar(24) NOT NULL,
    encounter_id uuid,
    payer_code varchar(32) REFERENCES payers (code),
    -- Human-facing, sequential per financial year, and unique. A tax invoice needs a number a
    -- person can quote back.
    number varchar(24) NOT NULL UNIQUE,
    status varchar(20) NOT NULL,
    subtotal numeric(14,2) NOT NULL DEFAULT 0,
    discount numeric(14,2) NOT NULL DEFAULT 0,
    tax_total numeric(14,2) NOT NULL DEFAULT 0,
    total numeric(14,2) NOT NULL DEFAULT 0,
    amount_paid numeric(14,2) NOT NULL DEFAULT 0,
    -- The date the invoice's tax rates are resolved against. Held rather than derived from
    -- created_at, so a back-dated invoice is taxed at the rate that applied on its own date.
    invoice_date date NOT NULL DEFAULT current_date,
    issued_at timestamptz,
    cancelled_at timestamptz,
    cancelled_reason varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_invoice_status CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'CANCELLED')),
    CONSTRAINT chk_not_overpaid CHECK (amount_paid <= total),
    CONSTRAINT chk_paid_not_negative CHECK (amount_paid >= 0),
    CONSTRAINT chk_totals_not_negative CHECK (subtotal >= 0 AND tax_total >= 0 AND total >= 0)
);

CREATE INDEX idx_invoice_patient ON invoices (patient_id, created_at DESC);
CREATE INDEX idx_invoice_open ON invoices (status, invoice_date) WHERE status IN ('DRAFT', 'ISSUED');
CREATE INDEX idx_invoice_encounter ON invoices (encounter_id) WHERE encounter_id IS NOT NULL;

CREATE TABLE invoice_lines (
    id uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES invoices (id) ON DELETE CASCADE,
    charge_item_code varchar(32) NOT NULL,
    -- Snapshotted, all of it. The description, the price, the tax percent: what this invoice says
    -- is what it said on the day, whatever the charge list says now.
    description varchar(255) NOT NULL,
    qty numeric(10,2) NOT NULL,
    unit_price numeric(14,2) NOT NULL,
    discount numeric(14,2) NOT NULL DEFAULT 0,
    tax_percent numeric(5,2) NOT NULL DEFAULT 0,
    tax_amount numeric(14,2) NOT NULL DEFAULT 0,
    line_total numeric(14,2) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_qty_positive CHECK (qty > 0),
    CONSTRAINT chk_line_not_negative CHECK (unit_price >= 0 AND discount >= 0 AND line_total >= 0),
    -- A discount larger than the line is a refund wearing a discount's clothes.
    CONSTRAINT chk_discount_within_line CHECK (discount <= qty * unit_price)
);

CREATE INDEX idx_lines_invoice ON invoice_lines (invoice_id);

CREATE TABLE payments (
    id uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES invoices (id),
    amount numeric(14,2) NOT NULL,
    method varchar(20) NOT NULL,
    reference varchar(64),
    received_by varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_positive CHECK (amount > 0),
    CONSTRAINT chk_method CHECK (method IN ('CASH', 'CARD', 'UPI', 'BANK_TRANSFER', 'INSURANCE'))
);

CREATE INDEX idx_payments_invoice ON payments (invoice_id, received_at);

-- ---------------------------------------------------------------------------
-- The constraint that stops a patient being billed twice.
-- ---------------------------------------------------------------------------
--
-- Every charge that arrives from somewhere else — a completed appointment, a released report, a
-- dispense, a bed-day — is recorded here first, keyed by where it came from. Events get
-- redelivered by design: a consumer that reads one twice must produce one charge, and the only
-- way to be sure of that is a key the second attempt collides with.
CREATE TABLE posted_charges (
    source_type varchar(32) NOT NULL,
    source_id uuid NOT NULL,
    charge_item_code varchar(32) NOT NULL,
    invoice_line_id uuid NOT NULL REFERENCES invoice_lines (id) ON DELETE CASCADE,
    posted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_type, source_id, charge_item_code)
);

-- ---------------------------------------------------------------------------
-- Claims against a payer.
-- ---------------------------------------------------------------------------
CREATE TABLE claims (
    id uuid PRIMARY KEY,
    invoice_id uuid NOT NULL REFERENCES invoices (id),
    payer_code varchar(32) NOT NULL REFERENCES payers (code),
    preauth_no varchar(64),
    submitted_at timestamptz,
    status varchar(20) NOT NULL,
    claimed_amount numeric(14,2) NOT NULL,
    settled_amount numeric(14,2),
    denial_reason varchar(255),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_claim_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'SETTLED', 'PARTIALLY_SETTLED', 'DENIED')),
    CONSTRAINT chk_claimed_positive CHECK (claimed_amount > 0),
    CONSTRAINT chk_settled_within_claim
        CHECK (settled_amount IS NULL OR (settled_amount >= 0 AND settled_amount <= claimed_amount)),
    -- A denied claim needs a reason. "Denied", alone, is a row nobody can appeal.
    CONSTRAINT chk_denial_has_a_reason CHECK (status <> 'DENIED' OR denial_reason IS NOT NULL),
    -- One live claim per invoice. Two claims for one invoice is how a hospital claims twice.
    CONSTRAINT uq_claim_per_invoice UNIQUE (invoice_id)
);

CREATE INDEX idx_claims_payer ON claims (payer_code, status);

-- ---------------------------------------------------------------------------
-- The invoice number sequence, per financial year.
-- ---------------------------------------------------------------------------
--
-- One statement issues a number, the `recordFailedLogin` shape used three times already in this
-- platform. SELECT max+1 then INSERT is a lost update that hands two invoices the same number, and
-- an invoice number is the thing a patient quotes back.
CREATE TABLE invoice_counters (
    series varchar(16) PRIMARY KEY,
    next_number integer NOT NULL DEFAULT 1
);
