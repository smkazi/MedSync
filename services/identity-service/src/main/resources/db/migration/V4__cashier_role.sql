-- The CASHIER role.
--
-- Billing is where this platform's separation of duties is easiest to get wrong and most tempting
-- to blur. A cashier raises invoices, takes money, and chases what payers owe. They do not read a
-- chart, order a test, or write a prescription -- and a clinician does not post a payment. Both
-- halves matter: the first is minimum-necessary access to PHI, and the second is the oldest
-- financial control there is, which is that the person who decides what is owed is not the person
-- who records that it was paid.
--
-- The code is CASHIER rather than BILLING because a role names a person's job, not a module. The
-- SpEL constants that use it are Roles.BILLING_READ and Roles.BILLING_WRITE, which name the acts.
--
-- No account is created here, for the reason V2 records: a role is platform vocabulary and belongs
-- in a migration, a password does not.
INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-000000000009', 'CASHIER',
     'Billing: invoices, payments, claims and the day book. Reads no clinical record.')
ON CONFLICT (code) DO NOTHING;
