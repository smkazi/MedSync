-- The PHARMACIST role.
--
-- A pharmacist reads a prescription and the patient's allergy list, dispenses against them, and
-- keeps the formulary and the stock. They deliberately cannot read the chart: the platform already
-- draws that line for the laboratory (CHART_READ is narrower than CLINICAL_READ), and the same
-- reasoning holds here -- the clinical context a dispense needs travels on the prescription, and a
-- pharmacist checking an interaction has no use for the history, assessment and plan.
--
-- Nor can they prescribe or administer. Those are three acts by three people: a prescriber orders,
-- the pharmacy dispenses, a nurse gives the dose at the bedside and records it against a scanned
-- wristband. A role that could do all three would make the closed loop a formality.
--
-- No account is created here, for the reason V2 records: a role is platform vocabulary and belongs
-- in a migration, a password does not.
INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-000000000008', 'PHARMACIST',
     'Pharmacy: formulary, stock, dispensing. Reads prescriptions and allergies, not charts.')
ON CONFLICT (code) DO NOTHING;
