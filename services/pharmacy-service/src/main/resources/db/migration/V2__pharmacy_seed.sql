-- A starting formulary and a starting interaction table.
--
-- Reference data, in a migration, like the laboratory's test catalogue and the platform's room
-- types. **Stock is deliberately not seeded**: a formulary entry is a statement about what this
-- hospital may prescribe, which every deployment needs, and a stock batch is a statement about
-- what is physically on a shelf, which no deployment should inherit from a migration. Fake
-- inventory in a real pharmacy is worse than an empty one.
--
-- Two things about the ingredient rows are load-bearing.
--
-- **Class markers are ingredients.** Amoxicillin carries AMOXICILLIN *and* PENICILLIN; ibuprofen
-- carries IBUPROFEN and NSAID. That is not sloppiness about what a molecule is: it is how a class
-- allergy and a class interaction work without a second mechanism. A patient whose chart says
-- "penicillin" is allergic to amoxicillin, and nobody records the allergy as "amoxicillin,
-- benzylpenicillin, amoxicillin-clavulanate". A separate class table would need the same rows and
-- a second matching path through every check.
--
-- **Pairs are written against whichever level is clinically true.** Warfarin interacts with NSAIDs
-- as a class, so that pairing is recorded against NSAID once rather than against each member.
-- Clarithromycin's effect on warfarin and on simvastatin is a property of clarithromycin and not
-- of macrolides — azithromycin barely touches CYP3A4 — so those two are recorded drug by drug.
-- Recording a pairing at both levels would report the same interaction twice.
--
-- The severities are the standard published gradings and the management text is what a prescriber
-- is meant to do instead; a deployment's pharmacist is expected to review and extend both, which
-- is why they are rows.

-- ---------------------------------------------------------------------------
INSERT INTO formulary (id, code, name, form, strength, unit, controlled) VALUES
    ('66666666-0000-4000-8000-000000000001', 'PARA500',      'Paracetamol',              'TABLET',    '500 mg',  'tablet', false),
    ('66666666-0000-4000-8000-000000000002', 'IBU400',       'Ibuprofen',                'TABLET',    '400 mg',  'tablet', false),
    ('66666666-0000-4000-8000-000000000003', 'ASPIRIN75',    'Aspirin',                  'TABLET',    '75 mg',   'tablet', false),
    ('66666666-0000-4000-8000-000000000004', 'AMOX500',      'Amoxicillin',              'CAPSULE',   '500 mg',  'capsule', false),
    ('66666666-0000-4000-8000-000000000005', 'AMOXCLAV625',  'Amoxicillin-Clavulanate',  'TABLET',    '625 mg',  'tablet', false),
    ('66666666-0000-4000-8000-000000000006', 'BENPEN1M',     'Benzylpenicillin',         'INJECTION', '1 MU',    'vial',   false),
    ('66666666-0000-4000-8000-000000000007', 'AZITH500',     'Azithromycin',             'TABLET',    '500 mg',  'tablet', false),
    ('66666666-0000-4000-8000-000000000008', 'CLARITH500',   'Clarithromycin',           'TABLET',    '500 mg',  'tablet', false),
    ('66666666-0000-4000-8000-000000000009', 'WARF5',        'Warfarin',                 'TABLET',    '5 mg',    'tablet', false),
    ('66666666-0000-4000-8000-00000000000a', 'SIMVA20',      'Simvastatin',              'TABLET',    '20 mg',   'tablet', false),
    ('66666666-0000-4000-8000-00000000000b', 'ENALAPRIL5',   'Enalapril',                'TABLET',    '5 mg',    'tablet', false),
    ('66666666-0000-4000-8000-00000000000c', 'SPIRO25',      'Spironolactone',           'TABLET',    '25 mg',   'tablet', false),
    ('66666666-0000-4000-8000-00000000000d', 'METFORMIN500', 'Metformin',                'TABLET',    '500 mg',  'tablet', false),
    ('66666666-0000-4000-8000-00000000000e', 'MTX2P5',       'Methotrexate',             'TABLET',    '2.5 mg',  'tablet', false),
    ('66666666-0000-4000-8000-00000000000f', 'ONDAN4',       'Ondansetron',              'TABLET',    '4 mg',    'tablet', false),
    -- Controlled, and the flag is recorded rather than enforced: there is no controlled-drug
    -- register in this platform yet, and a flag that implied one would be a claim the code cannot
    -- keep. Named in the README's gaps.
    ('66666666-0000-4000-8000-000000000010', 'TRAMADOL50',   'Tramadol',                 'CAPSULE',   '50 mg',   'capsule', true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO formulary_ingredients (drug_code, ingredient_code) VALUES
    ('PARA500',      'PARACETAMOL'),
    ('IBU400',       'IBUPROFEN'),
    ('IBU400',       'NSAID'),
    ('ASPIRIN75',    'ASPIRIN'),
    ('ASPIRIN75',    'NSAID'),
    ('ASPIRIN75',    'SALICYLATE'),
    ('AMOX500',      'AMOXICILLIN'),
    ('AMOX500',      'PENICILLIN'),
    ('AMOXCLAV625',  'AMOXICILLIN'),
    ('AMOXCLAV625',  'CLAVULANIC_ACID'),
    ('AMOXCLAV625',  'PENICILLIN'),
    ('BENPEN1M',     'BENZYL_PENICILLIN'),
    ('BENPEN1M',     'PENICILLIN'),
    ('AZITH500',     'AZITHROMYCIN'),
    ('AZITH500',     'MACROLIDE'),
    ('CLARITH500',   'CLARITHROMYCIN'),
    ('CLARITH500',   'MACROLIDE'),
    ('WARF5',        'WARFARIN'),
    ('SIMVA20',      'SIMVASTATIN'),
    ('SIMVA20',      'STATIN'),
    ('ENALAPRIL5',   'ENALAPRIL'),
    ('ENALAPRIL5',   'ACE_INHIBITOR'),
    ('SPIRO25',      'SPIRONOLACTONE'),
    ('SPIRO25',      'POTASSIUM_SPARING_DIURETIC'),
    ('METFORMIN500', 'METFORMIN'),
    ('MTX2P5',       'METHOTREXATE'),
    ('ONDAN4',       'ONDANSETRON'),
    ('TRAMADOL50',   'TRAMADOL'),
    ('TRAMADOL50',   'OPIOID')
ON CONFLICT (drug_code, ingredient_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Pairings. Stored with the ingredients sorted, which the CHECK constraint requires and the
-- application's own constructor does for it; written sorted here so the migration matches.
INSERT INTO interaction_pairs (id, ingredient_a, ingredient_b, severity, effect, management, source) VALUES
    -- Contraindicated: not a warning with a checkbox. Clarithromycin inhibits CYP3A4 strongly and
    -- simvastatin depends on it, so the pairing multiplies exposure and with it rhabdomyolysis risk.
    ('77777777-0000-4000-8000-000000000001', 'CLARITHROMYCIN', 'SIMVASTATIN', 'CONTRAINDICATED',
     'Clarithromycin inhibits CYP3A4 and raises simvastatin exposure several-fold, risking myopathy and rhabdomyolysis',
     'Do not co-prescribe. Withhold the statin for the course, or use azithromycin instead',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000002', 'CLARITHROMYCIN', 'WARFARIN', 'MAJOR',
     'Clarithromycin raises the INR by inhibiting warfarin metabolism; bleeding risk rises within days',
     'Choose another antibiotic where possible. If not, check the INR within 3-5 days and again after the course',
     'Standard product information'),

    -- Written against the class rather than against each NSAID: this is a property of NSAIDs, and
    -- one row covers ibuprofen, aspirin and whatever the next one added is.
    ('77777777-0000-4000-8000-000000000003', 'NSAID', 'WARFARIN', 'MAJOR',
     'Additive bleeding risk: NSAIDs impair platelet function and irritate the gastric mucosa while warfarin is anticoagulating',
     'Avoid. Use paracetamol for analgesia; if an NSAID is unavoidable, add gastroprotection and monitor the INR',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000004', 'METHOTREXATE', 'NSAID', 'MAJOR',
     'NSAIDs reduce renal clearance of methotrexate, raising its concentration and marrow toxicity',
     'Avoid at antirheumatic doses and never at high dose. Use paracetamol; if unavoidable, monitor the full blood count',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000005', 'ACE_INHIBITOR', 'POTASSIUM_SPARING_DIURETIC', 'MODERATE',
     'Both raise serum potassium; together they can produce dangerous hyperkalaemia, particularly in renal impairment',
     'A common and often intended combination in heart failure. Check potassium and creatinine within a week and then periodically',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000006', 'ACE_INHIBITOR', 'NSAID', 'MODERATE',
     'NSAIDs blunt the antihypertensive effect and, with an ACE inhibitor, can precipitate acute kidney injury',
     'Use the shortest possible NSAID course, keep the patient hydrated, and check renal function',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000007', 'ASPIRIN', 'IBUPROFEN', 'MODERATE',
     'Ibuprofen competes with aspirin at the platelet COX-1 site and can reduce its cardioprotective effect; gastric risk is additive',
     'Where aspirin is for cardioprotection, take it at least two hours before ibuprofen, or use paracetamol instead',
     'Standard product information'),

    ('77777777-0000-4000-8000-000000000008', 'ONDANSETRON', 'TRAMADOL', 'MODERATE',
     'Ondansetron reduces the analgesic effect of tramadol, and both prolong the QT interval',
     'Expect to need more analgesia than usual; consider a different antiemetic if pain control is poor',
     'Standard product information')
ON CONFLICT (ingredient_a, ingredient_b) DO NOTHING;
