-- A fourth kind of disclosure: a notifiable-disease line list.
--
-- The register already records what left, to whom, and who released it. This adds the one kind of
-- release the platform could not represent -- a list of named patients sent to a public health
-- authority because the law requires it -- and the constraint that makes it say what it is.
--
-- WHY IT BELONGS IN THE SAME TABLE. A separate table would be a second accounting of disclosures,
-- and a patient asking "who has seen my record" would have to be answered from two places by code
-- that remembered to ask both. `idx_disclosure_patient` is what PortalRecordsController reads when
-- they ask, and it does not need to learn about a new table.


-- `PUBLIC_HEALTH_REPORT` is twenty-one characters and the column holds twenty. Widened first,
-- because a CHECK naming a value the column cannot store would pass its own migration and fail
-- every insert.
ALTER TABLE disclosures ALTER COLUMN kind TYPE varchar(32);

ALTER TABLE disclosures DROP CONSTRAINT chk_disclosure_kind;
ALTER TABLE disclosures ADD CONSTRAINT chk_disclosure_kind
    CHECK (kind IN ('CONSENTED_SHARE', 'PATIENT_EXPORT', 'CARE_SUMMARY', 'PUBLIC_HEALTH_REPORT'));

-- The mirror of chk_share_names_a_consent, in the opposite direction, and both halves of the pair
-- are load-bearing.
--
-- A consented share cannot exist WITHOUT a consent: that is the failure the whole module exists to
-- prevent. A public-health report cannot exist WITH one: notification is compelled by law, needs no
-- permission, and a row naming a consent would make this register read as though the patient had
-- agreed to it. Somebody reading the accounting later -- the patient themselves, through the
-- portal -- would be told they consented to something they were never asked about, which is worse
-- than an incomplete record because it is a false one.
ALTER TABLE disclosures ADD CONSTRAINT chk_public_health_has_no_consent
    CHECK (kind <> 'PUBLIC_HEALTH_REPORT' OR consent_id IS NULL);
