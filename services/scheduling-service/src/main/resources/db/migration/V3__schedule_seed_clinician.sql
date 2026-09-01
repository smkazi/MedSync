-- Point the demo weekly pattern at a clinician who exists.
--
-- V1 seeded five rows against '55555555-0000-4000-8000-000000000001' with the comment "clinician ids
-- are resolved by the caller". Nothing resolved them: that id belonged to no user and no staff
-- record, so the seeded availability was attached to nobody and could not be reached from any screen
-- that lists clinicians.
--
-- It is now dr.rao, whose id is stable (DevDataSeeder) and who has a staff row (patient V3), so the
-- three facts finally agree: a user to authenticate as, a staff record to pick from a list, and a
-- weekly pattern to show slots from.
--
-- Delete-then-insert rather than UPDATE: uq_clinician_day is UNIQUE (clinician_id, day_of_week,
-- start_time), so an update would collide with any pattern dr.rao already has. Both statements are
-- scoped so this is a no-op on a database that never carried the placeholder.

DELETE FROM clinician_schedules WHERE clinician_id = '55555555-0000-4000-8000-000000000001';

INSERT INTO clinician_schedules (id, clinician_id, department_code, day_of_week, start_time, end_time,
                                 slot_minutes)
SELECT
    gen_random_uuid(),
    '33333333-0000-4000-8000-000000000002',
    'GEN',
    day,
    time '09:00',
    time '13:00',
    15
FROM generate_series(1, 5) AS day
ON CONFLICT (clinician_id, day_of_week, start_time) DO NOTHING;
