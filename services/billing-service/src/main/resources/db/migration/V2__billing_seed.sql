-- A starting charge list, the GST rates it references, and two payers.
--
-- Reference data in a migration, like the laboratory catalogue and the formulary. **No invoices,
-- no payments and no claims** are seeded: those are transactions, and a deployment inheriting
-- somebody else's money from a migration would be worse than starting empty.
--
-- The tax rows are the interesting part. Healthcare services provided by a clinical establishment
-- are GST-exempt in India, so the clinical items below carry no tax at all — `taxable` false, and
-- no rate. What is taxable is what a hospital sells rather than provides as care: a dispensed
-- medicine, a consumable. Those name a rate, and the rate is a row with an effective date because
-- rates change by statute and an invoice raised last year must keep the rate that applied then.
--
-- The percentages here are the ordinary Indian slabs and are a starting point a deployment's
-- accountant is expected to check, not a compliance statement. This platform implements the rules
-- as understood; it is not tax advice and the README says so.

INSERT INTO tax_rates (id, code, name, percent, effective_from, effective_to) VALUES
    -- Exempt is a rate of zero with a name, rather than the absence of a rate: an exempt line on
    -- an invoice should say it is exempt, not be silent about tax.
    ('aaaaaaaa-0000-4000-8000-000000000001', 'GST_EXEMPT', 'Exempt (healthcare services)', 0.00,
     '2017-07-01', NULL),
    ('aaaaaaaa-0000-4000-8000-000000000002', 'GST_5', 'GST 5%', 5.00, '2017-07-01', NULL),
    ('aaaaaaaa-0000-4000-8000-000000000003', 'GST_12', 'GST 12%', 12.00, '2017-07-01', NULL),
    ('aaaaaaaa-0000-4000-8000-000000000004', 'GST_18', 'GST 18%', 18.00, '2017-07-01', NULL)
ON CONFLICT (code, effective_from) DO NOTHING;

INSERT INTO charge_items (id, code, name, department_code, unit_price, taxable, tax_rate_code) VALUES
    -- Clinical services: exempt.
    ('bbbbbbbb-0000-4000-8000-000000000001', 'CONSULT_OP', 'Outpatient consultation', 'GEN',  500.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000002', 'CONSULT_FU', 'Follow-up consultation',  'GEN',  300.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000003', 'CASUALTY',   'Casualty attendance',     'GEN', 1000.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000004', 'BED_GEN',    'Bed day, general ward',   'GEN', 1500.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000005', 'BED_PRIV',   'Bed day, private room',   'GEN', 4000.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000006', 'LAB_CBC',    'Complete blood count',    'LAB',  350.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000007', 'LAB_ESR',    'ESR',                     'LAB',  200.00, false, NULL),
    ('bbbbbbbb-0000-4000-8000-000000000008', 'LAB_PANEL',  'Laboratory panel',        'LAB',  600.00, false, NULL),
    -- What the hospital sells rather than provides as care: taxable, and naming its rate.
    ('bbbbbbbb-0000-4000-8000-000000000009', 'PHARM_DISP', 'Pharmacy dispensing',     'PHM',    0.00, true,  'GST_5'),
    ('bbbbbbbb-0000-4000-8000-00000000000a', 'CONSUMABLE', 'Consumables',             'GEN',  100.00, true,  'GST_12')
ON CONFLICT (code) DO NOTHING;

INSERT INTO payers (id, code, name, requires_preauth, allows_copay, settles_directly, tax_exempt) VALUES
    -- Not a real insurer's name, and deliberately so: no vendor or company name enters this
    -- repository. A deployment replaces these with the schemes it actually has.
    ('cccccccc-0000-4000-8000-000000000001', 'SELF', 'Self-paying', false, false, false, false),
    ('cccccccc-0000-4000-8000-000000000002', 'TPA_A', 'Third-party administrator (sample)',
     true, true, true, false),
    ('cccccccc-0000-4000-8000-000000000003', 'SCHEME_A', 'Government scheme (sample)',
     true, false, true, true)
ON CONFLICT (code) DO NOTHING;

-- What the sample TPA has agreed to pay. Lower than list, which is the point of a tariff, and the
-- reason an invoice for a TPA patient must price from here rather than from the charge list.
INSERT INTO payer_tariffs (payer_code, charge_item_code, price) VALUES
    ('TPA_A', 'CONSULT_OP', 400.00),
    ('TPA_A', 'CONSULT_FU', 250.00),
    ('TPA_A', 'BED_GEN',   1200.00),
    ('TPA_A', 'LAB_CBC',    300.00),
    ('SCHEME_A', 'CONSULT_OP', 250.00),
    ('SCHEME_A', 'BED_GEN',    900.00)
ON CONFLICT (payer_code, charge_item_code) DO NOTHING;
