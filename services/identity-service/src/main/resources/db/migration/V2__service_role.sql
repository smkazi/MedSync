-- The SERVICE role: an identity for work that no user asked for.
--
-- Every cross-service call so far has forwarded the caller's own token, which is the right default
-- and needs no role of its own. Event-driven work has no caller: when a report is released and the
-- patient has to be told, the trigger is a Kafka message, and a message deliberately carries no
-- credential. So the consumer needs an account, and an account needs a role.
--
-- Deliberately the narrowest role on the platform. It grants exactly one thing -- reading a
-- patient's phone number and email address through `GET /patients/{id}/contact` -- so that a
-- service which only needs somewhere to send a message is not handed CLINICAL_READ and with it
-- demographics, allergies and every appointment. If this account's password leaks, what leaks with
-- it is a contact list, not a chart.
--
-- No account is created here. Roles are platform vocabulary and belong in a migration; a password
-- does not, and a hash committed to a repository is a credential committed to a repository however
-- it is spelled. The demo account comes from DevDataSeeder using the same environment-supplied
-- seed password as every other demo login; a real deployment creates it through
-- `POST /admin/users` like any other account and sets the service's password variable to match.
INSERT INTO roles (id, code, description) VALUES
    ('11111111-0000-4000-8000-000000000007', 'SERVICE',
     'Service account: narrow contact lookup for event-driven work. Never granted to a person.')
ON CONFLICT (code) DO NOTHING;
