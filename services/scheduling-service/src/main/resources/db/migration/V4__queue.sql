-- The OPD token queue.
--
-- An outpatient clinic hands a patient a number at the desk and calls numbers in a corridor. It is
-- the oldest workflow in a hospital and the one patients judge the place by, and it exists here for
-- one reason the appointment book cannot cover: an appointment is a time, and a queue is an order.
-- A clinic running forty minutes late still has a defensible order of service, and the number is
-- what makes it visible without anybody having to ask the desk.

-- One counter per room per day.
--
-- Not a sequence, and not `SELECT max(token_number) + 1`. A sequence cannot restart per room per
-- day without creating one sequence per room per day; the max+1 read is a lost update that hands
-- two patients the same number, which is precisely the failure that makes a queue useless -- two
-- people stand up when 14 is called and neither is wrong.
--
-- What issues a token is one statement:
--
--   INSERT INTO queue_counters (room_code, service_date, next_token) VALUES (:room, :date, 2)
--   ON CONFLICT (room_code, service_date)
--   DO UPDATE SET next_token = queue_counters.next_token + 1
--   RETURNING next_token - 1
--
-- The insert path leaves next_token at 2 and returns 1; the conflict path increments and returns
-- what was there. Either way the read and the write are the same statement, so there is no window.
-- Same shape as `UserRepository.recordFailedLogin`, for the same reason.
CREATE TABLE queue_counters (
    room_code    varchar(24) NOT NULL,
    service_date date        NOT NULL,
    next_token   integer     NOT NULL DEFAULT 1,
    PRIMARY KEY (room_code, service_date)
);

-- Keyed by room *code*, not room id.
--
-- The wall display is reached as /public/queue/{roomCode} and must be answerable without a token
-- and without calling patient-service -- a screen in a corridor that goes blank because the
-- facility directory is restarting is worse than no screen. A code is safe to depend on because a
-- code is never editable once created, which is a rule the platform already relies on: a room code
-- is cached on every appointment for exactly the same reason.
CREATE TABLE queue_tokens (
    id             uuid        PRIMARY KEY,
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- One token per appointment, enforced rather than assumed: checking in twice must not hand out
    -- a second number, and a second number would leave a gap in the sequence that nobody answers.
    appointment_id uuid        NOT NULL UNIQUE,
    room_code      varchar(24) NOT NULL,
    service_date   date        NOT NULL,
    token_number   integer     NOT NULL,
    issued_at      timestamptz NOT NULL,
    called_at      timestamptz,
    status         varchar(16) NOT NULL,
    CONSTRAINT uq_token UNIQUE (room_code, service_date, token_number),
    CONSTRAINT chk_token_status CHECK (status IN ('WAITING', 'CALLED', 'DONE')),
    -- A called token has a time; a waiting one does not. Two columns that can disagree are two
    -- columns that will.
    CONSTRAINT chk_called_at CHECK (
        (status = 'WAITING' AND called_at IS NULL) OR (status <> 'WAITING' AND called_at IS NOT NULL))
);

-- The board, and the display. Both read one room on one day, ordered by number.
CREATE INDEX idx_queue_room_date ON queue_tokens (room_code, service_date, token_number);
