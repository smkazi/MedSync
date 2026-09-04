-- The EPIDEMIOLOGIST role.
--
-- A job, not a module, per the decision V4 recorded when it named the billing role CASHIER rather
-- than BILLING. Compiling a district return is somebody's weekly work: they read coverage rates and
-- notifiable-disease counts, and they do it whether or not anybody is on call.
--
-- Not folded into ADMIN, though an administrator can do everything this role can. The administrator
-- account is the one that repairs the platform -- it creates users, rewrites the price list and
-- exports whole charts -- and handing that account to the person who files a weekly return means
-- the return gets filed by an account nobody can constrain. The two jobs have different hours,
-- different people and different risk.
--
-- WHAT THIS ROLE MUST NOT ACQUIRE, and why it is worth writing here rather than only in the code:
-- the care-relationship narrowing is expressed as "is a clinician and is not an administrator", so
-- a role added later falls OUTSIDE the narrowing until somebody decides otherwise. That is the safer
-- direction for a check whose failure mode is locking a new role out of every record -- and it is
-- only safe while the new role holds no per-patient endpoint. So this role enters constants that
-- return counts and rates, and nothing else. The abuse-case suite asserts it: 200 on the aggregates,
-- 403 on the line list, on one patient's register, on an encounter, on a patient record and on the
-- disclosure register. Those rows are what goes red the day somebody adds EPIDEMIOLOGIST to a
-- clinical constant because a screen needed a name.
--
-- No account is created here, for the reason V2 records: a role is platform vocabulary and belongs
-- in a migration, a password does not.
INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-00000000000d', 'EPIDEMIOLOGIST',
     'Public health reporting: coverage measures and notifiable-disease counts. Aggregates only.')
ON CONFLICT (code) DO NOTHING;
