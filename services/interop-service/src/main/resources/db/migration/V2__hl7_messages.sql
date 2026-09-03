-- Every HL7 v2 message that crossed the boundary, kept exactly as it crossed it.
--
-- The laboratory service learned this rule from analyzers and it is the same rule here: store the
-- transmission verbatim before parsing it, so a message that fails to decode is diagnosable rather
-- than lost. An interface engine is judged almost entirely on what it can tell you about the
-- message somebody swears they sent at nine o'clock, and a parsed-only record cannot answer that.
--
-- Both directions live in one table. They are the same object -- a message, its acknowledgement,
-- and whether it was accepted -- and splitting them would double every query an interface engineer
-- runs while chasing one exchange across a boundary.

CREATE TABLE hl7_messages (
    id uuid PRIMARY KEY,
    -- IN for a message this platform received, OUT for one it sent.
    direction varchar(3) NOT NULL,

    -- The message as it arrived or as it was sent, byte for byte. Not normalised, not re-encoded:
    -- the value of this column is that it is what was on the wire.
    raw text NOT NULL,

    -- Parsed out of MSH for querying. Null when the message did not parse, which is exactly the
    -- case somebody is looking for.
    message_type varchar(16),
    control_id varchar(64),
    sending_application varchar(64),
    sending_facility varchar(64),
    receiving_application varchar(64),
    receiving_facility varchar(64),
    -- MSH-7, the sender's own clock, kept beside received_at because the two disagreeing is a
    -- fault worth seeing rather than a discrepancy to hide.
    message_at timestamptz,

    -- What was said back. AA accepted, AE understood and refused, AR not understood -- and the
    -- acknowledgement itself, so "what did we actually reply" needs no reconstruction.
    ack_code varchar(2),
    ack_text varchar(500),
    ack_raw text,

    -- Null when nothing went wrong. Set for a message that could not be parsed or could not be
    -- acted on, and it is the first column an engineer reads.
    error varchar(1000),

    -- How it arrived or left: MLLP over a socket, or HTTP.
    transport varchar(8) NOT NULL,
    peer varchar(120),

    received_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_hl7_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT chk_hl7_ack_code CHECK (ack_code IS NULL OR ack_code IN ('AA', 'AE', 'AR')),
    CONSTRAINT chk_hl7_transport CHECK (transport IN ('MLLP', 'HTTP'))
);

-- The message log, newest first: the query the screen makes and the one an engineer makes.
CREATE INDEX idx_hl7_received ON hl7_messages (received_at DESC);

-- "Did you get MSG00042?" is the question this table is asked most, and it is asked by control id.
CREATE INDEX idx_hl7_control_id ON hl7_messages (control_id) WHERE control_id IS NOT NULL;

-- Everything that failed, cheaply. Partial because the failures are the small minority and the
-- whole point of the index is to find them without reading the majority.
CREATE INDEX idx_hl7_failures ON hl7_messages (received_at DESC)
    WHERE error IS NOT NULL OR ack_code IN ('AE', 'AR');
