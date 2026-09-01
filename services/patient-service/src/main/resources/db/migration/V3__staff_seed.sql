-- Staff records for the demo accounts.
--
-- Until now no staff row was seeded at all, and that had a consequence well away from this table:
-- an appointment's clinician_id is an identity user id, and this directory is the only thing that
-- turns one into a name somebody can pick from a list. With no staff rows, the clinician dropdown on
-- the booking and availability screens was empty on a fresh deployment — the screens worked, there
-- was simply nobody to choose.
--
-- The user ids are the stable ones DevDataSeeder now assigns (33333333-0000-4000-8000-00000000000N).
-- This is dev reference data of the same kind as the rooms and the reference ranges: concrete enough
-- to demo, replaced wholesale by any real deployment. ON CONFLICT DO NOTHING so a deployment that
-- has already created its own staff is left alone.
--
-- user_id is UNIQUE and nullable: a ward clerk who never signs in is still staff, and a staff row
-- without a login simply cannot be a clinician on an appointment.

INSERT INTO staff (id, user_id, employee_no, full_name, department_id, designation, specialty,
                   license_no, phone, email, active) VALUES
    ('66666666-0000-4000-8000-000000000001', '33333333-0000-4000-8000-000000000001',
     'EMP-0001', 'System Administrator', '22222222-0000-4000-8000-000000000001',
     'Administrator', NULL, NULL, NULL, 'admin@hms.local', true),

    -- The clinician scheduling's seeded weekly pattern belongs to.
    ('66666666-0000-4000-8000-000000000002', '33333333-0000-4000-8000-000000000002',
     'EMP-0002', 'Dr Anika Rao', '22222222-0000-4000-8000-000000000001',
     'Consultant Physician', 'General Medicine', 'MED-2014-88213', NULL, 'rao@hms.local', true),

    ('66666666-0000-4000-8000-000000000003', '33333333-0000-4000-8000-000000000003',
     'EMP-0003', 'Sana Iqbal', '22222222-0000-4000-8000-000000000001',
     'Staff Nurse', NULL, 'NUR-2019-40551', NULL, 'iqbal@hms.local', true),

    ('66666666-0000-4000-8000-000000000004', '33333333-0000-4000-8000-000000000004',
     'EMP-0004', 'Front Desk', '22222222-0000-4000-8000-000000000001',
     'Receptionist', NULL, NULL, NULL, 'reception@hms.local', true),

    ('66666666-0000-4000-8000-000000000005', '33333333-0000-4000-8000-000000000005',
     'EMP-0005', 'Ravi Menon', '22222222-0000-4000-8000-000000000006',
     'Laboratory Technician', 'Haematology', NULL, NULL, 'labtech@hms.local', true),

    ('66666666-0000-4000-8000-000000000006', '33333333-0000-4000-8000-000000000006',
     'EMP-0006', 'Dr Imran Pathan', '22222222-0000-4000-8000-000000000006',
     'Consultant Pathologist', 'Clinical Pathology', 'PAT-2011-31907', NULL, 'pathan@hms.local', true)
ON CONFLICT (id) DO NOTHING;
