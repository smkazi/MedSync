-- Which encounter a lab order was raised from.
--
-- Nullable, and it stays nullable: an order can legitimately arrive without one. A walk-in for a
-- fasting glucose has no encounter, and every order already in this table predates the column.
--
-- No foreign key, deliberately. `encounter_id` belongs to scheduling-service's schema, and this
-- service must not be able to fail because that one is mid-migration -- the same reasoning that
-- leaves `patient_id` and `clinician_id` unconstrained across the platform. What holds it together
-- is that ids are minted once and never reused.
--
-- Why the column exists at all: a clinician now orders tests from the open chart, which is what
-- CPOE means, and without this the chart has no way to show the orders it raised. It could only
-- list every test the patient has ever had, in an encounter-shaped card -- which reads as "these
-- belong to this visit" and would be wrong.
ALTER TABLE lab_orders ADD COLUMN encounter_id uuid;

-- The chart's card is the only query that filters on it, and it always filters by encounter alone.
CREATE INDEX idx_lab_orders_encounter ON lab_orders (encounter_id) WHERE encounter_id IS NOT NULL;

-- An order whose patient's sex was never recorded.
--
-- `patient_sex` was NOT NULL DEFAULT 'M' with a CHECK of ('M','F'), so "we do not know" was not
-- representable and every order that omitted it was stored as male. That is not a storage detail:
-- reference intervals are sex-specific, so a haemoglobin of 12.5 g/dL reads normal for a woman and
-- low for a man, and the report carried no hint that the interval had been chosen by default.
--
-- Nullable now, and `ReferenceRangeService.find` applies no interval when it is absent -- the
-- report then falls back to the analyzer's own range, which is at least honest about whose it is.
-- The CHECK still refuses anything that is neither M nor F.
ALTER TABLE lab_orders ALTER COLUMN patient_sex DROP NOT NULL;
ALTER TABLE lab_orders ALTER COLUMN patient_sex DROP DEFAULT;
ALTER TABLE lab_orders DROP CONSTRAINT chk_order_sex;
ALTER TABLE lab_orders ADD CONSTRAINT chk_order_sex
    CHECK (patient_sex IS NULL OR patient_sex IN ('M', 'F'));
