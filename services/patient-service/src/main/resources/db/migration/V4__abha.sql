-- ABHA: India's health account number, and the address it is reached at.
--
-- Two columns, both encrypted with the same converter as `national_id`, and both for the same
-- reason: an ABHA number is a national identifier that follows a person for life, so a breach that
-- exported it is worse than one that exported a phone number. `varchar(255)` because the column
-- holds ciphertext rather than the fourteen digits.
--
-- **Neither appears in any patient response.** They are released only through
-- `GET /patients/{id}/identifiers`, which is narrower than the chart and audited on every call --
-- the endpoint that exists precisely so that reading a national identifier is a distinct, recorded
-- act rather than a side effect of opening a record.
--
-- No unique constraint and no index, and that is a gap named rather than papered over: matching a
-- patient *by* their ABHA number needs a deterministic hash column, because a randomised-IV
-- ciphertext cannot be looked up or compared. ABDM linking will need one. Adding a UNIQUE here
-- would be worse than useless -- it would refuse a second patient whose ciphertext collided by
-- chance and allow two rows holding the same number.
ALTER TABLE patients
    ADD COLUMN abha_number  varchar(255),
    ADD COLUMN abha_address varchar(255);

COMMENT ON COLUMN patients.abha_number IS
    'ABHA number, 14 digits, encrypted at rest. Released only by /patients/{id}/identifiers.';
COMMENT ON COLUMN patients.abha_address IS
    'ABHA address (name@provider), encrypted at rest. Released only by /patients/{id}/identifiers.';
