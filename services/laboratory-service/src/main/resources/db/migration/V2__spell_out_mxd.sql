-- MXD is the only differential parameter still carrying its instrument code as a display name.
--
-- Every neighbour in this table spells itself out - "Lymphocytes %", "Monocytes %", "Basophils #" -
-- but MXD reads as "MXD %", which tells a clinician nothing. It is the mixed-cell channel a
-- three-part analyser reports instead of separate monocyte, eosinophil and basophil counts, and
-- naming it that way is how the instrument's own printed report names it. A result nobody can read
-- is a result nobody acts on.
--
-- A new migration rather than an edit to V1: V1 has already run against existing databases, and
-- changing it would break its Flyway checksum.

UPDATE reference_ranges
   SET display_name = 'MXD % (Mono+Eos+Baso)'
 WHERE parameter = 'MXD%';

UPDATE reference_ranges
   SET display_name = 'MXD # (Mono+Eos+Baso)'
 WHERE parameter = 'MXD#';
