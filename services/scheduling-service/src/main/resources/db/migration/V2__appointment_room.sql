-- Which room an appointment happens in.
--
-- Until now an appointment carried a department code and nothing else, so the platform could
-- tell a patient which clinic to attend but not which door to walk through - and, worse, two
-- clinicians could be booked into the same consulting room at the same time with nothing
-- objecting.
--
-- room_id and room_code are both cached here rather than joined, because rooms live in
-- patient-service and a service must not read another's tables. The id is the durable
-- reference; the code is what a human reads and what the front desk searches by. The room's
-- name, floor and wayfinding text are deliberately NOT cached - they are read live when an
-- appointment is rendered, so renaming a room or rewriting its directions does not leave stale
-- text on tomorrow's appointments.

ALTER TABLE appointments
    ADD COLUMN room_id   uuid,
    ADD COLUMN room_code varchar(16);

-- A room cannot host two overlapping appointments, for the same reason a clinician cannot.
--
-- A SEPARATE constraint, not a composite with clinician_id. A composite over
-- (clinician_id, room_id, range) would be satisfied by two different clinicians in the same room
-- at the same time - which is precisely the case this exists to stop.
--
-- room_id is nullable: an appointment may be booked before a room is assigned, and a
-- teleconsultation never has one. The partial WHERE excludes those rows, so a null room is not
-- treated as a room that many appointments share.
--
-- Cancelled and no-show slots are excluded on the same terms as the clinician constraint, so the
-- room becomes bookable again the moment an appointment is cancelled.
ALTER TABLE appointments ADD CONSTRAINT no_overlapping_room_bookings
    EXCLUDE USING gist (
        room_id WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (room_id IS NOT NULL AND status NOT IN ('CANCELLED', 'NO_SHOW'));

-- Availability asks "what is in this room today", which is a range scan on one room.
CREATE INDEX idx_appointments_room_day ON appointments (room_id, starts_at)
    WHERE room_id IS NOT NULL;
