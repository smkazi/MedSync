-- Outbound messaging.
--
-- The table this service exists for is `notifications`: every message the platform tried to send,
-- what it said, where it went, and whether it arrived. It is a delivery log first and a queue
-- second, because the question asked afterwards is almost always "was the patient told?" and a
-- queue that deletes what it has processed cannot answer it.

CREATE TABLE message_templates (
    id           uuid         PRIMARY KEY,
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    -- What the message is about. One template per category and channel: an SMS has no subject and
    -- must be short, an email can be a sentence longer.
    category     varchar(32)  NOT NULL,
    channel      varchar(16)  NOT NULL,
    subject      varchar(200),
    body         varchar(1000) NOT NULL,
    active       boolean      NOT NULL DEFAULT true,
    CONSTRAINT uq_template UNIQUE (category, channel)
);

-- Why templates are rows and the wording is not compiled in:
--
-- A hospital rewrites these. They are the platform's voice to a patient, they get translated, and
-- the legal team has opinions about them -- all of which is configuration, not code.
--
-- What is *not* configurable is which values a template may interpolate. The placeholder set is
-- closed and checked at render time (see MessageComposer), because the whole PHI rule lives here:
-- if a template could write {diagnosis} or {value}, then rewording a message would be enough to
-- put a laboratory result into an SMS, and the rule would be a comment rather than a property.

CREATE TABLE notifications (
    id              uuid          PRIMARY KEY,
    version         bigint        NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    channel         varchar(16)   NOT NULL,
    category        varchar(32)   NOT NULL,
    -- The address it went to. Null when there was nothing to send to, which is a real outcome and
    -- not an error: a patient with no phone number and no email on file is SUPPRESSED, recorded,
    -- and visible -- rather than a message that silently never existed.
    recipient       varchar(255),
    subject         varchar(200),
    body            varchar(1000) NOT NULL,
    status          varchar(16)   NOT NULL,
    attempts        integer       NOT NULL DEFAULT 0,
    -- Who it was about. An id, never a name or an MRN: this column is for joining, and a delivery
    -- log full of medical record numbers is a second copy of the patient index.
    patient_id      uuid,
    -- What it was about, for tracing back -- an order id, an appointment id. Never rendered into
    -- the message.
    reference       varchar(64),
    -- A redelivered Kafka message must not re-send. Derived from the event id for the consumer
    -- path and from the request for the API path, so both are replay-safe.
    --
    -- UNIQUE is doing the work here, not application care: two consumer instances processing the
    -- same redelivered event at the same moment both pass a "have I seen this?" check and then one
    -- of them loses the insert. That is the design -- the loser treats the violation as "already
    -- sent" rather than as a failure.
    idempotency_key varchar(120)  NOT NULL UNIQUE,
    sent_at         timestamptz,
    failed_reason   varchar(500),
    CONSTRAINT chk_notification_status
        CHECK (status IN ('SENT', 'FAILED', 'SUPPRESSED'))
);

-- The two questions the screens ask: what happened recently, and what happened for this patient.
CREATE INDEX idx_notifications_created ON notifications (created_at DESC);
CREATE INDEX idx_notifications_patient ON notifications (patient_id, created_at DESC)
    WHERE patient_id IS NOT NULL;
-- Answering "did anything fail?" without scanning the log.
CREATE INDEX idx_notifications_failed ON notifications (created_at DESC) WHERE status = 'FAILED';

-- The seeded wording.
--
-- Every one of these says that something is ready and where to go and see it. None of them says
-- what it is. That is the rule the module is built around: a phone number is often stale, is
-- frequently shared within a family, and SMS is plaintext to the handset -- so "your haemoglobin
-- is low" is a disclosure to whoever is holding the phone, while "a report is ready, sign in"
-- is not.
INSERT INTO message_templates (id, category, channel, subject, body) VALUES
    ('44444444-0000-4000-8000-000000000001', 'LAB_REPORT_READY', 'SMS', NULL,
     'A laboratory report is ready. Sign in to view it: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000002', 'LAB_REPORT_READY', 'EMAIL',
     'A report is ready to view',
     'A laboratory report is ready. Sign in to view it: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000003', 'LAB_REPORT_READY', 'LOG', NULL,
     'A laboratory report is ready. Sign in to view it: {portalUrl}'),

    ('44444444-0000-4000-8000-000000000004', 'APPOINTMENT_CONFIRMED', 'SMS', NULL,
     'Your appointment is confirmed for {when}. Details: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000005', 'APPOINTMENT_CONFIRMED', 'EMAIL',
     'Your appointment is confirmed',
     'Your appointment is confirmed for {when}. Details, and how to change it: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000006', 'APPOINTMENT_CONFIRMED', 'LOG', NULL,
     'Your appointment is confirmed for {when}. Details: {portalUrl}'),

    ('44444444-0000-4000-8000-000000000007', 'APPOINTMENT_REMINDER', 'SMS', NULL,
     'Reminder: you have an appointment on {when}. Details: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000008', 'APPOINTMENT_REMINDER', 'EMAIL',
     'A reminder about your appointment',
     'Reminder: you have an appointment on {when}. Details: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000009', 'APPOINTMENT_REMINDER', 'LOG', NULL,
     'Reminder: you have an appointment on {when}. Details: {portalUrl}'),

    ('44444444-0000-4000-8000-000000000010', 'APPOINTMENT_CANCELLED', 'SMS', NULL,
     'Your appointment on {when} has been cancelled. To rebook: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000011', 'APPOINTMENT_CANCELLED', 'EMAIL',
     'Your appointment has been cancelled',
     'Your appointment on {when} has been cancelled. To rebook: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000012', 'APPOINTMENT_CANCELLED', 'LOG', NULL,
     'Your appointment on {when} has been cancelled. To rebook: {portalUrl}'),

    -- The one a person originates by hand. Still not free text: the platform says a message is
    -- waiting and the message itself lives behind a sign-in, which is the only place it can be
    -- read by the person it is for and nobody else.
    ('44444444-0000-4000-8000-000000000013', 'PORTAL_MESSAGE', 'SMS', NULL,
     'You have a new message from the hospital. Sign in to read it: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000014', 'PORTAL_MESSAGE', 'EMAIL',
     'You have a new message',
     'You have a new message from the hospital. Sign in to read it: {portalUrl}'),
    ('44444444-0000-4000-8000-000000000015', 'PORTAL_MESSAGE', 'LOG', NULL,
     'You have a new message from the hospital. Sign in to read it: {portalUrl}');
