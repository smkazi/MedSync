-- Secure messaging: a two-way conversation between a patient and the hospital.
--
-- This service's founding rule is that an outbound message carries no PHI: a phone number is often
-- stale, frequently shared, and SMS is plaintext to the handset, so a released-report notification
-- says a report is ready and links to the portal rather than saying what the report found.
--
-- These two tables are the other end of that link, and they hold exactly what the SMS refuses to.
-- That is not an exception to the rule, it is the point of it. The rule is about the channel and
-- not about the content: a message on a screen behind a password the patient chose, reachable only
-- by a session bound to their own record, is the safe place for the sentence "your thyroid result
-- is slightly low". Sending that sentence to a handset on a family plan is not. Splitting the two
-- is what lets the hospital say anything useful at all.
--
-- What is deliberately absent: any way to edit or delete a message once it is sent. A thread is a
-- record of what the hospital told a patient and what they told it back, and both sides sometimes
-- need it a year later. Closing a thread stops it, nothing erases it.

CREATE TABLE message_threads (
    id                uuid         PRIMARY KEY,
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    -- Whose conversation this is. Every portal read is filtered on it and it is never supplied by
    -- a caller: the patient comes from the `patient_id` claim on the session's token.
    patient_id        uuid         NOT NULL,
    patient_mrn       varchar(24)  NOT NULL,
    subject           varchar(160) NOT NULL,
    -- Which department should answer, or null for the general enquiries queue. Not a foreign key:
    -- departments are patient-service's master data, exactly as clinician_id on an appointment is.
    department_code   varchar(16),
    -- OPEN: waiting for the hospital. ANSWERED: the hospital has replied and the patient may
    -- follow up. CLOSED: finished, and no further replies are accepted on either side.
    status            varchar(16)  NOT NULL,
    -- Denormalised so a thread list sorts without touching the messages table. A patient's inbox
    -- is the first screen the portal draws and it sorts by this on every load.
    last_message_at   timestamptz  NOT NULL,
    closed_at         timestamptz,
    CONSTRAINT chk_thread_status CHECK (status IN ('OPEN', 'ANSWERED', 'CLOSED')),
    CONSTRAINT chk_closed_has_a_time CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE INDEX idx_threads_patient ON message_threads (patient_id, last_message_at DESC);
CREATE INDEX idx_threads_open ON message_threads (status, last_message_at) WHERE status <> 'CLOSED';

CREATE TABLE thread_messages (
    id                uuid         PRIMARY KEY,
    version           bigint       NOT NULL DEFAULT 0,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    thread_id         uuid         NOT NULL REFERENCES message_threads (id) ON DELETE CASCADE,
    -- Which side wrote it. Stored rather than inferred from the author's name, because a name is
    -- how a message is displayed and this is what decides whether the patient may read it at all.
    author_kind       varchar(16)  NOT NULL,
    author_name       varchar(160) NOT NULL,
    body              text         NOT NULL,
    sent_at           timestamptz  NOT NULL,
    -- When the patient opened the thread after this message arrived. Null on their own messages,
    -- which they have obviously read, and null on a staff message they have not opened yet: that
    -- is the whole of the unread badge, and it is one column rather than a second table.
    read_by_patient_at timestamptz,
    CONSTRAINT chk_author_kind CHECK (author_kind IN ('PATIENT', 'STAFF')),
    CONSTRAINT chk_body_not_empty CHECK (length(btrim(body)) > 0)
);

CREATE INDEX idx_thread_messages ON thread_messages (thread_id, sent_at);
