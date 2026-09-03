-- The RADIOGRAPHER and RADIOLOGIST roles.
--
-- Two roles rather than one, and the reason is the same one the laboratory already demonstrates.
-- There, a technician enters a result and only a pathologist verifies it, because the person who
-- produced a number must not be the person who signs off what it means. Radiology has the identical
-- shape: a radiographer positions the patient, runs the modality and sends the images; a radiologist
-- reads them and signs a report other clinicians then treat from. They are two jobs done by two
-- people with different training, and a single role covering both would let whoever acquired an
-- image also be the one to say what it shows.
--
-- Not folded into PATHOLOGIST either, though both sign a diagnostic report. A pathologist and a
-- radiologist are different people with different registrations, and one role for both would mean
-- either could sign the other's work -- exactly the blurring the CASHIER decision in V4 refused for
-- billing.
--
-- The codes name jobs, not the module, per that same decision. The SpEL constants that use them are
-- Roles.IMAGING_ACQUIRE and Roles.IMAGING_REPORT, which name the acts.
--
-- No account is created here, for the reason V2 records: a role is platform vocabulary and belongs
-- in a migration, a password does not.
INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-00000000000b', 'RADIOGRAPHER',
     'Radiology acquisition: the modality worklist, and registering the studies that come back.'),
    ('11111111-0000-4000-8000-00000000000c', 'RADIOLOGIST',
     'Radiology reporting: reads studies and signs the report clinicians treat from.')
ON CONFLICT (code) DO NOTHING;
