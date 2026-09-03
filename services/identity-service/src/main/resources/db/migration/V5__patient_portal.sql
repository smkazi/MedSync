-- The PATIENT role, and the link that makes a portal session mean one patient.
--
-- Every other role on this platform answers "what may this person do". PATIENT answers that too --
-- it may do almost nothing -- but it has to answer a second question no staff role has ever had to:
-- *whose* record is this. A doctor's token names a doctor and the chart they may open is decided
-- by their role; a patient's token names a patient and the only chart they may open is their own.
--
-- So the link is a column on the account and a claim on the token, and it is never a request
-- parameter. Every portal endpoint reads the patient id out of the token and none of them accepts
-- one from the caller. That is the difference between an IDOR that is refused and an IDOR that is
-- unrepresentable: there is no field to tamper with, so there is no check to forget. The abuse
-- suite asserts it endpoint by endpoint anyway, because a rule with no test is a rule until
-- somebody adds the sixth endpoint.
--
-- UNIQUE because one patient has one portal account. Two accounts for one record would each be
-- able to read what the other sent through secure messaging, and neither person would know.
-- Nullable because every staff account has no patient, which is the overwhelming majority of rows.
--
-- No foreign key: patients live in another service's schema, exactly as clinician_id on an
-- appointment does. Enrolment goes through patient-service, which owns the record and refuses to
-- enrol a patient it cannot find -- so the id is verified where verification is possible.
ALTER TABLE users ADD COLUMN patient_id uuid;

CREATE UNIQUE INDEX uq_users_patient ON users (patient_id) WHERE patient_id IS NOT NULL;

-- The other half of the invariant -- a patient_id implies the PATIENT role, and that role implies a
-- patient_id -- spans users and user_roles, so it is not a CHECK constraint and this file will not
-- pretend otherwise. It lives in PortalAccountService, which is the only code that creates these
-- accounts, and the failure mode either way is a session that reaches nothing rather than a session
-- that reaches somebody else's record: an account with a link and no role holds no authority at
-- all, and one with the role and no link is refused by every portal endpoint for want of a patient
-- to name.

INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-00000000000a', 'PATIENT',
     'The patient portal: one person''s own appointments, released reports, invoices and messages.')
ON CONFLICT (code) DO NOTHING;
